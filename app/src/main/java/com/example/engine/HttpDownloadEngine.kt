package com.example.engine

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class FileProbeMetadata(
    val finalUrl: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val supportsRange: Boolean,
    val eTag: String?,
    val lastModified: String?,
    val isHtmlWarning: Boolean
)

data class TransferProgressSnapshot(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val supportsRange: Boolean,
    val eTag: String?,
    val lastModified: String?,
    val finalUrl: String,
    val fileName: String,
    val mimeType: String,
    val resumeWarning: String?,
    val isHtmlWarning: Boolean,
    val activeSegments: Int
)

sealed class EngineTransferOutcome {
    data class Success(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val sha256: String,
        val finalUrl: String,
        val fileName: String,
        val mimeType: String,
        val supportsRange: Boolean,
        val resumeWarning: String?,
        val isHtmlWarning: Boolean,
        val usedSegments: Int
    ) : EngineTransferOutcome()

    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : EngineTransferOutcome()

    data class Cancelled(val downloadedBytes: Long) : EngineTransferOutcome()

    data class Failure(
        val messageIndo: String,
        val isTransient: Boolean,
        val httpCode: Int? = null,
        val downloadedBytes: Long = 0L
    ) : EngineTransferOutcome()
}

class RedirectSecurityException(message: String) : IOException(message)

class HttpDownloadEngine(
    private val allowLoopbackForTesting: Boolean = false,
    customClient: OkHttpClient? = null
) {

    val httpClient: OkHttpClient = customClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val urlString = request.url.toString()
            when (val validation = UrlValidator.validateUrl(urlString, allowLoopbackForTesting)) {
                is UrlValidationResult.Invalid -> {
                    throw RedirectSecurityException(
                        "Pengalihan URL ditolak demi keamanan: ${validation.messageId}"
                    )
                }
                is UrlValidationResult.Valid -> chain.proceed(request)
            }
        }
        .build()

    /**
     * Memeriksa informasi file di server (ukuran, nama file, tipe konten, dukungan Range, ETag).
     */
    suspend fun probeUrlMetadata(url: String): Result<FileProbeMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val validation = UrlValidator.validateUrl(url, allowLoopbackForTesting)
            if (validation is UrlValidationResult.Invalid) {
                throw IllegalArgumentException(validation.messageId)
            }

            // Coba permintaan HEAD terlebih dahulu
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .build()

            var response: Response? = null
            try {
                response = httpClient.newCall(headReq).execute()
                if (response.isSuccessful) {
                    return@runCatching extractProbeMetadata(url, response, probeUsedRange = false)
                }
            } catch (_: Exception) {
                // Lanjutkan ke fallback GET Range bytes=0-0 bila HEAD gagal
            } finally {
                response?.close()
            }

            // Fallback: GET dengan Range bytes=0-0 untuk mendeteksi dukungan Range sekaligus metadata
            val rangeProbeReq = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .build()

            httpClient.newCall(rangeProbeReq).execute().use { getResp ->
                if (!getResp.isSuccessful && getResp.code != 206) {
                    val statusInfo = UrlValidator.interpretHttpStatus(getResp.code)
                    throw IOException(statusInfo.messageIndo)
                }
                extractProbeMetadata(url, getResp, probeUsedRange = true)
            }
        }
    }

    private fun extractProbeMetadata(
        originalUrl: String,
        response: Response,
        probeUsedRange: Boolean
    ): FileProbeMetadata {
        val finalUrl = response.request.url.toString()
        val contentDisposition = response.header("Content-Disposition")
        val rawContentType = response.header("Content-Type")
        val resolvedName = UrlValidator.resolveFileName(finalUrl.ifEmpty { originalUrl }, contentDisposition, rawContentType)
        val mimeType = UrlValidator.guessMimeTypeFromFileName(resolvedName, rawContentType)

        val acceptRanges = response.header("Accept-Ranges")?.lowercase() ?: ""
        val contentRange = UrlValidator.parseContentRange(response.header("Content-Range"))
        val supportsRange = response.code == 206 || acceptRanges.contains("bytes") || contentRange != null

        val contentLengthHeader = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val totalBytes = when {
            contentRange != null && contentRange.totalBytes > 0L -> contentRange.totalBytes
            probeUsedRange && response.code == 206 && contentRange == null -> -1L
            contentLengthHeader > 0L -> contentLengthHeader
            else -> -1L
        }

        val eTag = response.header("ETag")
        val lastModified = response.header("Last-Modified")
        val isHtml = UrlValidator.isHtmlContentType(mimeType, resolvedName)

        return FileProbeMetadata(
            finalUrl = finalUrl,
            fileName = resolvedName,
            mimeType = mimeType,
            totalBytes = totalBytes,
            supportsRange = supportsRange,
            eTag = eTag,
            lastModified = lastModified,
            isHtmlWarning = isHtml
        )
    }

    /**
     * Menjalankan transfer file nyata ke file sementara (.part) dengan dukungan:
     * - Jeda & Lanjutkan (HTTP Range + validator ETag/Last-Modified)
     * - Penanganan aman apabila server mengabaikan Range (HTTP 200)
     * - Multi-segmen opsional jika diaktifkan dan server terbukti mendukung Range
     * - Pelaporan progres kecepatan aktual dan ETA
     * - Verifikasi ukuran akhir dan SHA-256
     */
    suspend fun executeTransfer(
        url: String,
        tempFile: File,
        initialFileName: String,
        initialMimeType: String,
        initialTotalBytes: Long,
        initialSupportsRange: Boolean,
        storedETag: String?,
        storedLastModified: String?,
        enableMultiSegment: Boolean,
        desiredSegments: Int,
        isPausedCheck: () -> Boolean,
        isCancelledCheck: () -> Boolean,
        spaceCheck: (Long) -> Unit,
        onProgress: suspend (TransferProgressSnapshot) -> Unit
    ): EngineTransferOutcome = withContext(Dispatchers.IO) {
        try {
            val validation = UrlValidator.validateUrl(url, allowLoopbackForTesting)
            if (validation is UrlValidationResult.Invalid) {
                return@withContext EngineTransferOutcome.Failure(
                    messageIndo = validation.messageId,
                    isTransient = false
                )
            }

            tempFile.parentFile?.mkdirs()
            var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            // Jika unduhan baru (existingBytes == 0), multi-segment diaktifkan, coba probe untuk kepastian Range & ukuran
            if (existingBytes == 0L && enableMultiSegment && desiredSegments > 1) {
                val probe = probeUrlMetadata(url).getOrNull()
                if (probe != null &&
                    probe.supportsRange &&
                    probe.totalBytes >= MIN_MULTISEGMENT_SIZE_BYTES
                ) {
                    val multiResult = executeMultiSegmentTransfer(
                        url = probe.finalUrl,
                        tempFile = tempFile,
                        probe = probe,
                        segmentCount = desiredSegments.coerceIn(2, 4),
                        isPausedCheck = isPausedCheck,
                        isCancelledCheck = isCancelledCheck,
                        spaceCheck = spaceCheck,
                        onProgress = onProgress
                    )
                    if (multiResult != null) {
                        return@withContext multiResult
                    }
                    // Jika multi-segmen tidak kompatibel di tengah jalan, bersihkan dan lanjutkan single-stream
                    if (tempFile.exists()) tempFile.delete()
                    existingBytes = 0L
                }
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")

            val requestedResume = existingBytes > 0L
            if (requestedResume) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
                val validator = storedETag ?: storedLastModified
                if (!validator.isNullOrBlank()) {
                    reqBuilder.header("If-Range", validator)
                }
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code

                // Tangani HTTP 416 Range Not Satisfiable
                if (code == 416) {
                    if (initialTotalBytes > 0L && existingBytes == initialTotalBytes && tempFile.exists()) {
                        val sha = computeSha256(tempFile)
                        return@withContext EngineTransferOutcome.Success(
                            downloadedBytes = existingBytes,
                            totalBytes = initialTotalBytes,
                            sha256 = sha,
                            finalUrl = response.request.url.toString(),
                            fileName = initialFileName,
                            mimeType = initialMimeType,
                            supportsRange = initialSupportsRange,
                            resumeWarning = null,
                            isHtmlWarning = UrlValidator.isHtmlContentType(initialMimeType, initialFileName),
                            usedSegments = 1
                        )
                    } else {
                        // File lokal rusak atau melebihi ukuran file di server; hapus .part agar percobaan berikutnya bersih
                        tempFile.delete()
                        return@withContext EngineTransferOutcome.Failure(
                            messageIndo = "Rentang byte tidak sesuai dengan file di server (HTTP 416). File sementara direset, silakan Coba Lagi.",
                            isTransient = false,
                            httpCode = 416,
                            downloadedBytes = 0L
                        )
                    }
                }

                val statusInterpretation = UrlValidator.interpretHttpStatus(code)
                if (!statusInterpretation.isSuccess) {
                    return@withContext EngineTransferOutcome.Failure(
                        messageIndo = statusInterpretation.messageIndo,
                        isTransient = statusInterpretation.isTransientError,
                        httpCode = code,
                        downloadedBytes = existingBytes
                    )
                }

                val finalUrl = response.request.url.toString()
                val contentDisposition = response.header("Content-Disposition")
                val rawContentType = response.header("Content-Type")
                val resolvedName = if (initialFileName.isNotBlank() && initialFileName != "unduhan_file") {
                    initialFileName
                } else {
                    UrlValidator.resolveFileName(finalUrl, contentDisposition, rawContentType)
                }
                val resolvedMime = UrlValidator.guessMimeTypeFromFileName(resolvedName, rawContentType ?: initialMimeType)
                val isHtml = UrlValidator.isHtmlContentType(resolvedMime, resolvedName)

                val acceptRangesHeader = response.header("Accept-Ranges")?.lowercase() ?: ""
                val contentRange = UrlValidator.parseContentRange(response.header("Content-Range"))
                val serverSupportsRange = code == 206 || acceptRangesHeader.contains("bytes") || contentRange != null
                val newETag = response.header("ETag") ?: storedETag
                val newLastModified = response.header("Last-Modified") ?: storedLastModified

                var resumeWarning: String? = null
                var appendMode = false

                if (requestedResume) {
                    if (code == 206) {
                        if (contentRange != null && contentRange.startByte != existingBytes) {
                            // Server mengembalikan offset berbeda dari yang diminta: mulai ulang dari 0 demi integritas
                            tempFile.delete()
                            existingBytes = 0L
                            appendMode = false
                            resumeWarning = "Server mengirim offset byte berbeda. Unduhan dimulai ulang dari awal demi menjaga keutuhan file."
                        } else {
                            appendMode = true
                        }
                    } else if (code == 200) {
                        // Server mengabaikan Range atau validator If-Range berubah; jangan tambahkan ke file lama!
                        tempFile.delete()
                        existingBytes = 0L
                        appendMode = false
                        resumeWarning = if (!serverSupportsRange) {
                            "Server tidak mendukung fitur lanjutkan (Range). Unduhan dimulai ulang dari byte awal."
                        } else {
                            "File di server berubah atau server mengembalikan HTTP 200. Unduhan dimulai ulang dari awal dengan aman."
                        }
                    }
                } else if (!serverSupportsRange) {
                    resumeWarning = "Server ini tidak mendukung permintaan Range (tidak dapat dilanjutkan dari byte terakhir jika dijeda)."
                }

                val contentLength = response.body?.contentLength() ?: -1L
                val totalBytes = when {
                    contentRange != null && contentRange.totalBytes > 0L -> contentRange.totalBytes
                    code == 206 && contentLength > 0L -> existingBytes + contentLength
                    code == 200 && contentLength > 0L -> contentLength
                    initialTotalBytes > 0L && appendMode -> initialTotalBytes
                    else -> -1L
                }

                if (totalBytes > existingBytes) {
                    spaceCheck(totalBytes - existingBytes)
                }

                val body = response.body ?: return@withContext EngineTransferOutcome.Failure(
                    messageIndo = "Respons server kosong (tidak ada data file).",
                    isTransient = true,
                    downloadedBytes = existingBytes
                )

                var currentBytes = existingBytes
                var windowStartTime = System.currentTimeMillis()
                var windowStartBytes = currentBytes
                var currentSpeed = 0L

                // Laporkan status awal tepat saat mulai menerima stream
                onProgress(
                    TransferProgressSnapshot(
                        downloadedBytes = currentBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        supportsRange = serverSupportsRange,
                        eTag = newETag,
                        lastModified = newLastModified,
                        finalUrl = finalUrl,
                        fileName = resolvedName,
                        mimeType = resolvedMime,
                        resumeWarning = resumeWarning,
                        isHtmlWarning = isHtml,
                        activeSegments = 1
                    )
                )

                RandomAccessFile(tempFile, "rw").use { raf ->
                    if (appendMode) {
                        raf.seek(existingBytes)
                    } else {
                        raf.setLength(0L)
                        raf.seek(0L)
                    }

                    body.byteStream().use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            coroutineContext.ensureActive()

                            if (isCancelledCheck()) {
                                return@withContext EngineTransferOutcome.Cancelled(currentBytes)
                            }
                            if (isPausedCheck()) {
                                return@withContext EngineTransferOutcome.Paused(currentBytes, totalBytes)
                            }

                            raf.write(buffer, 0, bytesRead)
                            currentBytes += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - windowStartTime
                            if (elapsedMs >= PROGRESS_UPDATE_INTERVAL_MS) {
                                val deltaBytes = currentBytes - windowStartBytes
                                val instantSpeed = if (elapsedMs > 0) (deltaBytes * 1000L) / elapsedMs else 0L
                                currentSpeed = if (currentSpeed == 0L) {
                                    instantSpeed
                                } else {
                                    ((currentSpeed * 3L) + (instantSpeed * 7L)) / 10L
                                }
                                val eta = if (totalBytes > currentBytes && currentSpeed > 0L) {
                                    ((totalBytes - currentBytes) / currentSpeed).coerceAtLeast(1L)
                                } else {
                                    -1L
                                }

                                onProgress(
                                    TransferProgressSnapshot(
                                        downloadedBytes = currentBytes,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = currentSpeed,
                                        etaSeconds = eta,
                                        supportsRange = serverSupportsRange,
                                        eTag = newETag,
                                        lastModified = newLastModified,
                                        finalUrl = finalUrl,
                                        fileName = resolvedName,
                                        mimeType = resolvedMime,
                                        resumeWarning = resumeWarning,
                                        isHtmlWarning = isHtml,
                                        activeSegments = 1
                                    )
                                )
                                windowStartTime = now
                                windowStartBytes = currentBytes
                            }
                        }
                    }
                }

                // Pemeriksaan sekali lagi apakah dihentikan tepat di akhir loop
                if (isCancelledCheck()) {
                    return@withContext EngineTransferOutcome.Cancelled(currentBytes)
                }
                if (isPausedCheck()) {
                    return@withContext EngineTransferOutcome.Paused(currentBytes, totalBytes)
                }

                // Pemeriksaan integritas dasar: pastikan ukuran file sesuai dengan totalBytes bila diketahui
                val actualFileLength = tempFile.length()
                if (totalBytes > 0L && actualFileLength != totalBytes) {
                    return@withContext EngineTransferOutcome.Failure(
                        messageIndo = "Transfer terputus sebelum selesai ($actualFileLength dari $totalBytes byte diterima).",
                        isTransient = true,
                        downloadedBytes = actualFileLength
                    )
                }

                val sha256 = computeSha256(tempFile)
                EngineTransferOutcome.Success(
                    downloadedBytes = actualFileLength,
                    totalBytes = if (totalBytes > 0L) totalBytes else actualFileLength,
                    sha256 = sha256,
                    finalUrl = finalUrl,
                    fileName = resolvedName,
                    mimeType = resolvedMime,
                    supportsRange = serverSupportsRange,
                    resumeWarning = resumeWarning,
                    isHtmlWarning = isHtml,
                    usedSegments = 1
                )
            }
        } catch (e: CancellationException) {
            val bytes = if (tempFile.exists()) tempFile.length() else 0L
            if (isCancelledCheck()) {
                EngineTransferOutcome.Cancelled(bytes)
            } else if (isPausedCheck()) {
                EngineTransferOutcome.Paused(bytes, initialTotalBytes)
            } else {
                throw e
            }
        } catch (e: Exception) {
            val bytes = if (tempFile.exists()) tempFile.length() else 0L
            val (msgIndo, isTransient) = classifyException(e)
            EngineTransferOutcome.Failure(
                messageIndo = msgIndo,
                isTransient = isTransient,
                downloadedBytes = bytes
            )
        }
    }

    /**
     * Unduhan multi-segmen paralel untuk satu file besar.
     * Hanya diaktifkan jika server telah terverifikasi mendukung Range dan ukuran total diketahui.
     * Setiap segmen memiliki rentang byte berbeda [startByte..endByte] dan ditulis ke offset tepat.
     */
    private suspend fun executeMultiSegmentTransfer(
        url: String,
        tempFile: File,
        probe: FileProbeMetadata,
        segmentCount: Int,
        isPausedCheck: () -> Boolean,
        isCancelledCheck: () -> Boolean,
        spaceCheck: (Long) -> Unit,
        onProgress: suspend (TransferProgressSnapshot) -> Unit
    ): EngineTransferOutcome? = coroutineScope {
        val totalBytes = probe.totalBytes
        spaceCheck(totalBytes)

        // Pre-allocate ukuran file sementara
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val totalDownloaded = AtomicLong(0L)
        val chunkSize = totalBytes / segmentCount
        var windowStartTime = System.currentTimeMillis()
        var windowStartBytes = 0L
        var smoothedSpeed = 0L
        val progressLock = Any()

        try {
            val jobs = (0 until segmentCount).map { index ->
                val startByte = index * chunkSize
                val endByte = if (index == segmentCount - 1) totalBytes - 1 else ((index + 1) * chunkSize) - 1

                async(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=$startByte-$endByte")
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.code != 206) {
                            throw IllegalStateException("FALLBACK_SINGLE_STREAM")
                        }
                        val contentRange = UrlValidator.parseContentRange(resp.header("Content-Range"))
                        if (contentRange != null && contentRange.startByte != startByte) {
                            throw IllegalStateException("FALLBACK_SINGLE_STREAM")
                        }

                        val body = resp.body ?: throw IOException("Stream segmen #$index kosong.")
                        val expectedSegmentLength = (endByte - startByte) + 1L
                        var segmentWritten = 0L

                        RandomAccessFile(tempFile, "rw").use { segRaf ->
                            segRaf.seek(startByte)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    ensureActive()
                                    if (isCancelledCheck() || isPausedCheck()) {
                                        throw CancellationException("Transfer dihentikan pengguna")
                                    }
                                    val maxToWrite = (expectedSegmentLength - segmentWritten).toInt().coerceAtMost(read)
                                    if (maxToWrite <= 0) break
                                    segRaf.write(buffer, 0, maxToWrite)
                                    segmentWritten += maxToWrite
                                    val cumulative = totalDownloaded.addAndGet(maxToWrite.toLong())

                                    var snapshotToEmit: TransferProgressSnapshot? = null
                                    synchronized(progressLock) {
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - windowStartTime
                                        if (elapsed >= PROGRESS_UPDATE_INTERVAL_MS) {
                                            val delta = cumulative - windowStartBytes
                                            val instant = if (elapsed > 0) (delta * 1000L) / elapsed else 0L
                                            smoothedSpeed = if (smoothedSpeed == 0L) instant else ((smoothedSpeed * 3L) + (instant * 7L)) / 10L
                                            val eta = if (totalBytes > cumulative && smoothedSpeed > 0L) {
                                                ((totalBytes - cumulative) / smoothedSpeed).coerceAtLeast(1L)
                                            } else -1L
                                            snapshotToEmit = TransferProgressSnapshot(
                                                downloadedBytes = cumulative,
                                                totalBytes = totalBytes,
                                                speedBytesPerSec = smoothedSpeed,
                                                etaSeconds = eta,
                                                supportsRange = true,
                                                eTag = probe.eTag,
                                                lastModified = probe.lastModified,
                                                finalUrl = probe.finalUrl,
                                                fileName = probe.fileName,
                                                mimeType = probe.mimeType,
                                                resumeWarning = null,
                                                isHtmlWarning = probe.isHtmlWarning,
                                                activeSegments = segmentCount
                                            )
                                            windowStartTime = now
                                            windowStartBytes = cumulative
                                        }
                                    }
                                    snapshotToEmit?.let { onProgress(it) }
                                }
                            }
                        }

                        if (segmentWritten != expectedSegmentLength) {
                            throw IOException("Segmen #${index + 1} tidak lengkap ($segmentWritten / $expectedSegmentLength byte).")
                        }
                    }
                }
            }

            jobs.awaitAll()

            if (totalDownloaded.get() != totalBytes || tempFile.length() != totalBytes) {
                return@coroutineScope EngineTransferOutcome.Failure(
                    messageIndo = "Ukuran gabungan multi-segmen tidak sesuai (${totalDownloaded.get()} / $totalBytes byte).",
                    isTransient = true,
                    downloadedBytes = 0L
                )
            }

            val sha256 = computeSha256(tempFile)
            EngineTransferOutcome.Success(
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                sha256 = sha256,
                finalUrl = probe.finalUrl,
                fileName = probe.fileName,
                mimeType = probe.mimeType,
                supportsRange = true,
                resumeWarning = null,
                isHtmlWarning = probe.isHtmlWarning,
                usedSegments = segmentCount
            )
        } catch (e: IllegalStateException) {
            if (e.message == "FALLBACK_SINGLE_STREAM") {
                null // Kembalikan null agar dieksekusi dengan mode single-stream standar
            } else {
                throw e
            }
        } catch (e: CancellationException) {
            // Pada multi-segmen yang belum selesai semua segmennya, hapus file pre-alokasi bila dibatalkan/dijeda
            // dan simpan status
            tempFile.delete()
            if (isCancelledCheck()) {
                EngineTransferOutcome.Cancelled(0L)
            } else {
                EngineTransferOutcome.Paused(0L, totalBytes)
            }
        }
    }

    fun classifyException(e: Throwable): Pair<String, Boolean> {
        val msg = e.message ?: ""
        return when {
            e is InsufficientStorageException || msg.contains("ENOSPC", ignoreCase = true) ||
                msg.contains("No space left", ignoreCase = true) -> {
                (e.message ?: "Ruang penyimpanan perangkat penuh. Kosongkan ruang terlebih dahulu.") to false
            }
            e is RedirectSecurityException -> {
                (e.message ?: "Pengalihan URL ditolak demi keamanan.") to false
            }
            e is SSLException -> {
                "Koneksi aman (TLS/SSL) gagal diverifikasi. Periksa sertifikat server atau tanggal perangkat." to false
            }
            e is UnknownHostException -> {
                "Server tidak dapat ditemukan atau koneksi internet terputus (DNS gagal)." to true
            }
            e is SocketTimeoutException -> {
                "Koneksi ke server kehabisan waktu tunggu (Timeout). Akan dicoba kembali jika retry aktif." to true
            }
            e is IOException -> {
                "Gangguan jaringan atau pembacaan stream terputus: ${msg.take(90)}" to true
            }
            else -> {
                "Kesalahan saat mengunduh: ${msg.ifEmpty { e.javaClass.simpleName }.take(100)}" to false
            }
        }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val USER_AGENT = "UdaDownloader/1.0 (Android; Mobile)"
        const val STREAM_BUFFER_SIZE = 32 * 1024
        const val PROGRESS_UPDATE_INTERVAL_MS = 380L
        const val MIN_MULTISEGMENT_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB
    }
}
