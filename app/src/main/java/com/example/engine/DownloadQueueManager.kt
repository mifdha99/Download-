package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.data.DownloadDao
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.data.SettingsRepository
import com.example.service.DownloadForegroundService
import com.example.service.QueueRecoveryWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class EnqueueResult {
    data class Enqueued(
        val task: DownloadTaskEntity,
        val isHtmlWarning: Boolean
    ) : EnqueueResult()

    data class InvalidUrl(val reasonIndo: String) : EnqueueResult()
}

/**
 * Mengelola antrean unduhan, batas transfer bersamaan (concurrency),
 * kepemilikan tunggal per file (single active worker per task ID),
 * retry bertahap (exponential backoff), dan pemulihan status.
 */
class DownloadQueueManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val settingsRepository: SettingsRepository,
    private val mediaStorePublisher: MediaStorePublisher,
    private val httpEngine: HttpDownloadEngine,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val retryBaseDelayMs: Long = 1500L,
    private val enforceNetworkCheck: Boolean = true,
    private val startForegroundServiceCallback: (Context) -> Unit = { ctx ->
        DownloadForegroundService.startOrUpdate(ctx)
    }
) {
    private val dispatchMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedByUserFlags = ConcurrentHashMap<String, Boolean>()
    private val cancelledByUserFlags = ConcurrentHashMap<String, Boolean>()

    val allTasksFlow: Flow<List<DownloadTaskEntity>> = downloadDao.observeAllTasks()
    val activeQueueFlow: Flow<List<DownloadTaskEntity>> = downloadDao.observeActiveQueueTasks()
    val completedTasksFlow: Flow<List<DownloadTaskEntity>> = downloadDao.observeCompletedTasks()

    init {
        registerNetworkCallback()
    }

    /**
     * Memulihkan status unduhan saat aplikasi atau perangkat dibuka kembali.
     * Tugas yang sebelumnya berstatus DOWNLOADING atau CONNECTING (tetapi tidak punya worker aktif
     * karena proses dihentikan OS) direkonsiliasi dengan ukuran file .part nyata di disk
     * lalu dimasukkan kembali ke antrean secara aman tanpa duplikasi.
     */
    suspend fun reconcileAndRestoreQueue() {
        dispatchMutex.withLock {
            val allTasks = downloadDao.getAllTasksSync()
            for (task in allTasks) {
                val isCurrentlyRunningInMemory = activeJobs[task.id]?.isActive == true
                if ((task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.CONNECTING) &&
                    !isCurrentlyRunningInMemory
                ) {
                    val tempFile = File(task.tempFilePath)
                    val actualPartBytes = if (tempFile.exists()) tempFile.length() else 0L
                    downloadDao.updateTask(
                        task.copy(
                            downloadedBytes = actualPartBytes,
                            status = DownloadStatus.QUEUED,
                            speedBytesPerSec = 0L,
                            etaSeconds = -1L,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else if (task.status == DownloadStatus.PAUSED) {
                    val tempFile = File(task.tempFilePath)
                    val actualPartBytes = if (tempFile.exists()) tempFile.length() else 0L
                    if (actualPartBytes != task.downloadedBytes) {
                        downloadDao.updateTask(
                            task.copy(
                                downloadedBytes = actualPartBytes,
                                speedBytesPerSec = 0L,
                                etaSeconds = -1L,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
        processQueue()
    }

    /**
     * Memvalidasi URL, melakukan probe metadata awal bila memungkinkan, menyimpan ke Room,
     * dan menjadwalkan antrean.
     */
    suspend fun enqueueDownload(
        rawUrl: String,
        customFileName: String? = null,
        priority: Int = 0,
        allowLoopback: Boolean = false
    ): EnqueueResult {
        val validation = UrlValidator.validateUrl(rawUrl, allowLoopback)
        if (validation is UrlValidationResult.Invalid) {
            return EnqueueResult.InvalidUrl(validation.messageId)
        }
        val validUrl = (validation as UrlValidationResult.Valid).normalizedUrl

        // Probe metadata dengan cepat (tidak memblokir bila server lambat merespons HEAD)
        val probe = httpEngine.probeUrlMetadata(validUrl).getOrNull()
        val settings = settingsRepository.getSettingsSnapshot()

        val existingNames = downloadDao.getAllFileNames().toSet()
        val rawDesiredName = customFileName?.takeIf { it.isNotBlank() }
            ?: probe?.fileName
            ?: UrlValidator.resolveFileName(validUrl, null, null)
        val uniqueFileName = UrlValidator.resolveUniqueFileName(rawDesiredName, existingNames)
        val mimeType = probe?.mimeType ?: UrlValidator.guessMimeTypeFromFileName(uniqueFileName, null)
        val isHtml = probe?.isHtmlWarning ?: UrlValidator.isHtmlContentType(mimeType, uniqueFileName)

        val taskId = UUID.randomUUID().toString()
        val tempFile = mediaStorePublisher.createTempPartFile(taskId)

        val entity = DownloadTaskEntity(
            id = taskId,
            url = validUrl,
            finalUrl = probe?.finalUrl ?: validUrl,
            fileName = uniqueFileName,
            mimeType = mimeType,
            totalBytes = probe?.totalBytes ?: -1L,
            downloadedBytes = 0L,
            tempFilePath = tempFile.absolutePath,
            destinationUri = null,
            customSubfolder = settings.downloadSubfolder,
            status = DownloadStatus.QUEUED,
            priority = priority,
            speedBytesPerSec = 0L,
            etaSeconds = -1L,
            supportsRange = probe?.supportsRange ?: false,
            eTag = probe?.eTag,
            lastModified = probe?.lastModified,
            resumeWarning = if (probe != null && !probe.supportsRange) {
                "Server tidak mengiklankan dukungan Range pada pemeriksaan awal."
            } else null,
            isHtmlWarning = isHtml,
            segmentCount = 1,
            retryCount = 0,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        downloadDao.insertTask(entity)
        processQueue()
        return EnqueueResult.Enqueued(entity, isHtml)
    }

    /**
     * Menambahkan beberapa URL sekaligus ke dalam antrean.
     */
    suspend fun enqueueBatchUrls(urls: List<String>): Pair<Int, List<String>> {
        var successCount = 0
        val errors = mutableListOf<String>()
        for (raw in urls) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            when (val res = enqueueDownload(trimmed)) {
                is EnqueueResult.Enqueued -> successCount++
                is EnqueueResult.InvalidUrl -> errors.add("$trimmed: ${res.reasonIndo}")
            }
        }
        return successCount to errors
    }

    /**
     * Memeriksa slot unduhan bersamaan yang tersedia dan memulai worker untuk tugas QUEUED
     * berdasarkan urutan prioritas tertinggi dan waktu masuk antrean.
     */
    suspend fun processQueue() {
        dispatchMutex.withLock {
            // Bersihkan referensi job yang sudah selesai
            val finishedIds = activeJobs.filterValues { !it.isActive }.keys
            finishedIds.forEach { activeJobs.remove(it) }

            val settings = settingsRepository.getSettingsSnapshot()
            val maxConcurrent = settings.maxConcurrentDownloads.coerceIn(1, 5)

            val availableSlots = maxConcurrent - activeJobs.size
            if (availableSlots <= 0) return

            // Periksa pembatasan jaringan (Wi-Fi saja vs Seluler)
            val networkIssue = checkNetworkConstraint(settings.wifiOnly)
            val queuedTasks = downloadDao.getQueuedTasksOrdered()
            if (queuedTasks.isEmpty()) return

            if (networkIssue != null) {
                // Tandai informasi menunggu jaringan pada tugas di antrean tetapi tetap berstatus QUEUED
                for (queued in queuedTasks) {
                    if (queued.errorMessage != networkIssue) {
                        downloadDao.updateStatus(
                            id = queued.id,
                            status = DownloadStatus.QUEUED,
                            errorMessage = networkIssue
                        )
                    }
                }
                QueueRecoveryWorker.scheduleNetworkRecovery(context, wifiOnly = settings.wifiOnly)
                return
            }

            var startedAny = false
            for (candidate in queuedTasks) {
                if (activeJobs.size >= maxConcurrent) break
                if (activeJobs.containsKey(candidate.id)) continue

                pausedByUserFlags.remove(candidate.id)
                cancelledByUserFlags.remove(candidate.id)

                val job = externalScope.launch {
                    runTaskWorker(candidate.id)
                }
                activeJobs[candidate.id] = job
                startedAny = true
            }

            if (startedAny || activeJobs.isNotEmpty()) {
                runCatching { startForegroundServiceCallback(context) }
            }
        }
    }

    private suspend fun runTaskWorker(taskId: String) {
        try {
            val initialTask = downloadDao.getTaskById(taskId) ?: return
            val settings = settingsRepository.getSettingsSnapshot()

            var attempt = 0
            val maxRetries = if (settings.autoRetryEnabled) settings.maxRetries.coerceIn(1, 5) else 0

            while (true) {
                val currentTask = downloadDao.getTaskById(taskId) ?: return
                if (pausedByUserFlags[taskId] == true) {
                    downloadDao.updateStatus(taskId, DownloadStatus.PAUSED, null)
                    return
                }
                if (cancelledByUserFlags[taskId] == true) {
                    handleCancelledCleanup(currentTask)
                    return
                }

                // Periksa kondisi jaringan sebelum memulai koneksi
                val netError = checkNetworkConstraint(settings.wifiOnly)
                if (netError != null) {
                    downloadDao.updateStatus(taskId, DownloadStatus.QUEUED, netError)
                    QueueRecoveryWorker.scheduleNetworkRecovery(context, settings.wifiOnly)
                    return
                }

                downloadDao.updateStatus(
                    id = taskId,
                    status = DownloadStatus.CONNECTING,
                    errorMessage = if (attempt > 0) "Mencoba ulang (${attempt}/$maxRetries)..." else null
                )

                val tempFile = File(currentTask.tempFilePath)

                val outcome = httpEngine.executeTransfer(
                    url = currentTask.url,
                    tempFile = tempFile,
                    initialFileName = currentTask.fileName,
                    initialMimeType = currentTask.mimeType,
                    initialTotalBytes = currentTask.totalBytes,
                    initialSupportsRange = currentTask.supportsRange,
                    storedETag = currentTask.eTag,
                    storedLastModified = currentTask.lastModified,
                    enableMultiSegment = settings.multiSegmentEnabled,
                    desiredSegments = settings.segmentsPerDownload,
                    isPausedCheck = { pausedByUserFlags[taskId] == true },
                    isCancelledCheck = { cancelledByUserFlags[taskId] == true },
                    spaceCheck = { needed -> mediaStorePublisher.ensureEnoughSpace(needed) },
                    onProgress = { snap ->
                        val latest = downloadDao.getTaskById(taskId) ?: return@executeTransfer
                        downloadDao.updateTask(
                            latest.copy(
                                finalUrl = snap.finalUrl,
                                fileName = snap.fileName,
                                mimeType = snap.mimeType,
                                downloadedBytes = snap.downloadedBytes,
                                totalBytes = snap.totalBytes,
                                speedBytesPerSec = snap.speedBytesPerSec,
                                etaSeconds = snap.etaSeconds,
                                supportsRange = snap.supportsRange,
                                eTag = snap.eTag,
                                lastModified = snap.lastModified,
                                resumeWarning = snap.resumeWarning,
                                isHtmlWarning = snap.isHtmlWarning,
                                segmentCount = snap.activeSegments,
                                status = DownloadStatus.DOWNLOADING,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                )

                when (outcome) {
                    is EngineTransferOutcome.Success -> {
                        val latest = downloadDao.getTaskById(taskId) ?: return
                        val published = mediaStorePublisher.publishCompletedDownload(
                            tempFile = tempFile,
                            desiredFileName = outcome.fileName.ifBlank { latest.fileName },
                            mimeType = outcome.mimeType.ifBlank { latest.mimeType },
                            subfolder = latest.customSubfolder.ifBlank { settings.downloadSubfolder }
                        )
                        downloadDao.updateTask(
                            latest.copy(
                                finalUrl = outcome.finalUrl,
                                fileName = published.finalFileName,
                                mimeType = published.mimeType,
                                downloadedBytes = outcome.downloadedBytes,
                                totalBytes = outcome.totalBytes,
                                destinationUri = published.destinationUri,
                                checksumSha256 = outcome.sha256,
                                supportsRange = outcome.supportsRange,
                                resumeWarning = outcome.resumeWarning,
                                isHtmlWarning = outcome.isHtmlWarning,
                                segmentCount = outcome.usedSegments,
                                status = DownloadStatus.COMPLETED,
                                speedBytesPerSec = 0L,
                                etaSeconds = 0L,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        return
                    }

                    is EngineTransferOutcome.Paused -> {
                        val latest = downloadDao.getTaskById(taskId) ?: return
                        val actualBytes = if (tempFile.exists()) tempFile.length() else outcome.downloadedBytes
                        downloadDao.updateTask(
                            latest.copy(
                                downloadedBytes = actualBytes,
                                totalBytes = if (outcome.totalBytes > 0L) outcome.totalBytes else latest.totalBytes,
                                status = DownloadStatus.PAUSED,
                                speedBytesPerSec = 0L,
                                etaSeconds = -1L,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        return
                    }

                    is EngineTransferOutcome.Cancelled -> {
                        val latest = downloadDao.getTaskById(taskId) ?: return
                        handleCancelledCleanup(latest)
                        return
                    }

                    is EngineTransferOutcome.Failure -> {
                        val latest = downloadDao.getTaskById(taskId) ?: return
                        val actualBytes = if (tempFile.exists()) tempFile.length() else outcome.downloadedBytes

                        if (outcome.isTransient && settings.autoRetryEnabled && attempt < maxRetries) {
                            attempt++
                            val backoffMs = (retryBaseDelayMs * (1 shl (attempt - 1))).coerceAtMost(12_000L)
                            downloadDao.updateTask(
                                latest.copy(
                                    downloadedBytes = actualBytes,
                                    retryCount = latest.retryCount + 1,
                                    status = DownloadStatus.CONNECTING,
                                    speedBytesPerSec = 0L,
                                    etaSeconds = -1L,
                                    errorMessage = "${outcome.messageIndo} — Mencoba ulang ($attempt/$maxRetries) dalam ${backoffMs / 1000} dtk...",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            delay(backoffMs)
                            continue
                        } else {
                            downloadDao.updateTask(
                                latest.copy(
                                    downloadedBytes = actualBytes,
                                    status = DownloadStatus.FAILED,
                                    speedBytesPerSec = 0L,
                                    etaSeconds = -1L,
                                    errorMessage = outcome.messageIndo,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            if (outcome.isTransient) {
                                QueueRecoveryWorker.scheduleNetworkRecovery(context, settings.wifiOnly)
                            }
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val latest = downloadDao.getTaskById(taskId)
            if (latest != null) {
                downloadDao.updateStatus(
                    id = taskId,
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Gagal menyimpan atau memproses file unduhan."
                )
            }
        } finally {
            activeJobs.remove(taskId)
            externalScope.launch {
                processQueue()
            }
        }
    }

    private suspend fun handleCancelledCleanup(task: DownloadTaskEntity) {
        runCatching { File(task.tempFilePath).delete() }
        downloadDao.updateTask(
            task.copy(
                downloadedBytes = 0L,
                status = DownloadStatus.CANCELLED,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                errorMessage = "Unduhan dibatalkan oleh pengguna.",
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Menjeda satu tugas unduhan yang sedang berjalan atau menunggu di antrean.
     */
    suspend fun pauseTask(taskId: String) {
        pausedByUserFlags[taskId] = true
        val runningJob = activeJobs.remove(taskId)
        val task = downloadDao.getTaskById(taskId)
        if (task != null) {
            val tempFile = File(task.tempFilePath)
            val actualBytes = if (tempFile.exists()) tempFile.length() else task.downloadedBytes
            val warning = if (!task.supportsRange && actualBytes > 0L) {
                "Perhatian: Server ini belum mengonfirmasi dukungan Range. Melanjutkan mungkin mengulang dari awal."
            } else {
                task.resumeWarning
            }
            downloadDao.updateTask(
                task.copy(
                    downloadedBytes = actualBytes,
                    status = DownloadStatus.PAUSED,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L,
                    resumeWarning = warning,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        runningJob?.cancel()
        processQueue()
    }

    /**
     * Menjeda seluruh unduhan aktif dan dalam antrean.
     */
    suspend fun pauseAllActive() {
        val all = downloadDao.getAllTasksSync()
        for (task in all) {
            if (task.status.canPause()) {
                pausedByUserFlags[task.id] = true
                activeJobs.remove(task.id)?.cancel()
                val actualBytes = File(task.tempFilePath).takeIf { it.exists() }?.length() ?: task.downloadedBytes
                downloadDao.updateTask(
                    task.copy(
                        downloadedBytes = actualBytes,
                        status = DownloadStatus.PAUSED,
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * Melanjutkan tugas yang dijeda.
     */
    suspend fun resumeTask(taskId: String) {
        val task = downloadDao.getTaskById(taskId) ?: return
        if (task.status != DownloadStatus.PAUSED && task.status != DownloadStatus.FAILED) return

        pausedByUserFlags.remove(taskId)
        cancelledByUserFlags.remove(taskId)

        val tempFile = File(task.tempFilePath)
        val actualBytes = if (tempFile.exists()) tempFile.length() else 0L

        downloadDao.updateTask(
            task.copy(
                downloadedBytes = actualBytes,
                status = DownloadStatus.QUEUED,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        processQueue()
    }

    /**
     * Melanjutkan semua tugas yang sedang berstatus PAUSED.
     */
    suspend fun resumeAllPaused() {
        val all = downloadDao.getAllTasksSync()
        for (task in all) {
            if (task.status == DownloadStatus.PAUSED) {
                pausedByUserFlags.remove(task.id)
                cancelledByUserFlags.remove(task.id)
                val tempFile = File(task.tempFilePath)
                val actualBytes = if (tempFile.exists()) tempFile.length() else 0L
                downloadDao.updateTask(
                    task.copy(
                        downloadedBytes = actualBytes,
                        status = DownloadStatus.QUEUED,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        processQueue()
    }

    /**
     * Membatalkan tugas unduhan dan membersihkan file sementara (.part).
     */
    suspend fun cancelTask(taskId: String) {
        cancelledByUserFlags[taskId] = true
        val runningJob = activeJobs.remove(taskId)
        val task = downloadDao.getTaskById(taskId)
        runningJob?.cancel()
        if (task != null) {
            handleCancelledCleanup(task)
        }
        processQueue()
    }

    /**
     * Mencoba ulang tugas yang gagal atau dibatalkan.
     */
    suspend fun retryTask(taskId: String, restartFromZero: Boolean = false) {
        val task = downloadDao.getTaskById(taskId) ?: return
        pausedByUserFlags.remove(taskId)
        cancelledByUserFlags.remove(taskId)

        val tempFile = File(task.tempFilePath)
        if (restartFromZero && tempFile.exists()) {
            tempFile.delete()
        }
        val actualBytes = if (!restartFromZero && tempFile.exists()) tempFile.length() else 0L

        downloadDao.updateTask(
            task.copy(
                downloadedBytes = actualBytes,
                status = DownloadStatus.QUEUED,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        processQueue()
    }

    /**
     * Mengubah prioritas tugas dalam antrean (angka lebih tinggi diproses lebih dulu).
     */
    suspend fun changePriority(taskId: String, delta: Int) {
        val task = downloadDao.getTaskById(taskId) ?: return
        val newPriority = (task.priority + delta).coerceIn(-10, 10)
        downloadDao.updatePriority(taskId, newPriority)
        processQueue()
    }

    /**
     * Menghapus tugas dari daftar.
     * File yang sudah selesai TIDAK akan dihapus kecuali deleteCompletedFile == true.
     */
    suspend fun deleteTask(taskId: String, deleteCompletedFile: Boolean = false) {
        cancelledByUserFlags[taskId] = true
        activeJobs.remove(taskId)?.cancel()

        val task = downloadDao.getTaskById(taskId)
        if (task != null) {
            runCatching { File(task.tempFilePath).delete() }
            if (deleteCompletedFile && task.status == DownloadStatus.COMPLETED) {
                mediaStorePublisher.deletePublishedFile(task.destinationUri)
            }
        }
        downloadDao.deleteTaskById(taskId)
        processQueue()
    }

    /**
     * Membersihkan file sementara (.part) yang tidak lagi terpakai oleh tugas aktif/dijeda.
     */
    suspend fun cleanupUnusedTempFiles(): Pair<Int, Long> {
        val allTasks = downloadDao.getAllTasksSync()
        val keepTaskIds = allTasks
            .filter {
                it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.CONNECTING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PAUSED ||
                    it.status == DownloadStatus.FAILED
            }
            .map { it.id }
            .toSet()
        return mediaStorePublisher.cleanupOrphanedTempFiles(keepTaskIds)
    }

    fun hasActiveTransfers(): Boolean = activeJobs.any { it.value.isActive }

    /**
     * Memeriksa ketersediaan jaringan dan aturan Wi-Fi saja.
     * Mengembalikan pesan masalah Bahasa Indonesia bila jaringan tidak memenuhi syarat, atau null bila siap.
     */
    fun checkNetworkConstraint(wifiOnly: Boolean): String? {
        if (!enforceNetworkCheck) return null
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val activeNetwork = cm.activeNetwork
                ?: return "Menunggu koneksi internet tersedia..."
            val caps = cm.getNetworkCapabilities(activeNetwork)
                ?: return "Menunggu koneksi internet aktif..."
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return "Tidak ada akses internet. Unduhan akan dilanjutkan saat terhubung."
            }
            if (wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return "Menunggu jaringan Wi-Fi (Mode 'Hanya Wi-Fi' aktif di Pengaturan)."
            }
            null
        }.getOrNull()
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        externalScope.launch {
                            processQueue()
                        }
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        externalScope.launch {
                            processQueue()
                        }
                    }
                }
            )
        }
    }
}
