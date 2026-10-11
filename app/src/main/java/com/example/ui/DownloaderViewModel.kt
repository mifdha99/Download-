package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.UdaDownloaderApp
import com.example.data.AppSettings
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.data.ThemeMode
import com.example.engine.EnqueueResult
import com.example.engine.FileProbeMetadata
import com.example.engine.UrlValidationResult
import com.example.engine.UrlValidator
import com.example.util.FormatUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab(val title: String) {
    HOME("Beranda"),
    DOWNLOADS("Unduhan"),
    COMPLETED("Selesai"),
    SETTINGS("Pengaturan")
}

data class ProbeUiState(
    val isLoading: Boolean = false,
    val metadata: FileProbeMetadata? = null,
    val errorIndo: String? = null
)

data class HomeUiState(
    val urlInput: String = "",
    val customFileNameInput: String = "",
    val urlValidationError: String? = null,
    val clipboardSuggestedUrl: String? = null,
    val dismissedClipboardUrl: String? = null,
    val isSubmitting: Boolean = false,
    val probeState: ProbeUiState = ProbeUiState(),
    val statusBannerMessage: String? = null,
    val isBannerWarning: Boolean = false,
    val availableStorageText: String = ""
)

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as UdaDownloaderApp
    private val queueManager = app.queueManager
    private val settingsRepository = app.settingsRepository
    private val mediaStorePublisher = app.mediaStorePublisher
    private val httpEngine = app.httpDownloadEngine

    private val _selectedTab = MutableStateFlow(AppTab.HOME)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _homeState = MutableStateFlow(
        HomeUiState(
            availableStorageText = FormatUtils.formatBytes(mediaStorePublisher.getAvailableBytes())
        )
    )
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    val allTasks: StateFlow<List<DownloadTaskEntity>> = queueManager.allTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeQueueTasks: StateFlow<List<DownloadTaskEntity>> = queueManager.activeQueueFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTasks: StateFlow<List<DownloadTaskEntity>> = queueManager.completedTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
        refreshAvailableStorage()
    }

    fun refreshAvailableStorage() {
        _homeState.update {
            it.copy(availableStorageText = FormatUtils.formatBytes(mediaStorePublisher.getAvailableBytes()))
        }
    }

    fun onUrlInputChanged(newValue: String) {
        val trimmed = newValue.trim()
        val validationError = if (trimmed.isEmpty()) {
            null
        } else {
            when (val res = UrlValidator.validateUrl(trimmed)) {
                is UrlValidationResult.Valid -> null
                is UrlValidationResult.Invalid -> res.messageId
            }
        }
        _homeState.update {
            it.copy(
                urlInput = newValue,
                urlValidationError = validationError,
                probeState = ProbeUiState()
            )
        }
    }

    fun onCustomFileNameChanged(newName: String) {
        _homeState.update { it.copy(customFileNameInput = newName) }
    }

    fun pasteUrlFromClipboard(clipboardText: String?) {
        val extracted = UrlValidator.extractFirstValidUrl(clipboardText)
        if (extracted != null) {
            onUrlInputChanged(extracted)
            _homeState.update {
                it.copy(
                    statusBannerMessage = "URL berhasil ditempel dari clipboard.",
                    isBannerWarning = false
                )
            }
        } else if (!clipboardText.isNullOrBlank()) {
            onUrlInputChanged(clipboardText.trim())
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Teks ditempel, namun bukan format URL HTTP/HTTPS yang valid.",
                    isBannerWarning = true
                )
            }
        } else {
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Clipboard kosong atau tidak berisi teks.",
                    isBannerWarning = true
                )
            }
        }
    }

    /**
     * Memeriksa isi clipboard saat aplikasi dibuka di foreground (tanpa mengunduh otomatis).
     */
    fun checkClipboardForSuggestion(clipboardText: String?) {
        if (!settings.value.clipboardDetectionEnabled) return
        val validUrl = UrlValidator.extractFirstValidUrl(clipboardText) ?: return
        val currentState = _homeState.value
        if (validUrl == currentState.dismissedClipboardUrl || validUrl == currentState.urlInput.trim()) {
            return
        }
        // Jangan sarankan ulang URL yang sudah ada di daftar tugas terbaru
        if (allTasks.value.any { it.url == validUrl }) return

        _homeState.update {
            it.copy(clipboardSuggestedUrl = validUrl)
        }
    }

    fun acceptClipboardSuggestion() {
        val suggested = _homeState.value.clipboardSuggestedUrl ?: return
        onUrlInputChanged(suggested)
        _homeState.update {
            it.copy(
                clipboardSuggestedUrl = null,
                dismissedClipboardUrl = suggested
            )
        }
        probeCurrentUrl()
    }

    fun dismissClipboardSuggestion() {
        val suggested = _homeState.value.clipboardSuggestedUrl
        _homeState.update {
            it.copy(
                clipboardSuggestedUrl = null,
                dismissedClipboardUrl = suggested
            )
        }
    }

    /**
     * Menangani URL yang dibagikan dari aplikasi lain (Android Sharesheet / ACTION_VIEW).
     * Mengisi kolom URL dan menampilkan pemeriksa informasi file tanpa langsung mengunduh tanpa izin.
     */
    fun handleIncomingSharedText(sharedText: String?) {
        val validUrl = UrlValidator.extractFirstValidUrl(sharedText)
        if (validUrl != null) {
            _selectedTab.value = AppTab.HOME
            onUrlInputChanged(validUrl)
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Tautan diterima dari aplikasi luar. Tekan 'Unduh' untuk memulai transfer.",
                    isBannerWarning = false
                )
            }
            probeCurrentUrl()
        } else if (!sharedText.isNullOrBlank()) {
            _selectedTab.value = AppTab.HOME
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Tautan yang dibagikan bukan URL HTTP/HTTPS yang valid.",
                    isBannerWarning = true
                )
            }
        }
    }

    fun probeCurrentUrl() {
        val rawUrl = _homeState.value.urlInput.trim()
        when (val validation = UrlValidator.validateUrl(rawUrl)) {
            is UrlValidationResult.Invalid -> {
                _homeState.update {
                    it.copy(urlValidationError = validation.messageId)
                }
                return
            }
            is UrlValidationResult.Valid -> {
                viewModelScope.launch {
                    _homeState.update {
                        it.copy(probeState = ProbeUiState(isLoading = true))
                    }
                    val result = httpEngine.probeUrlMetadata(validation.normalizedUrl)
                    result.fold(
                        onSuccess = { meta ->
                            _homeState.update { state ->
                                state.copy(
                                    probeState = ProbeUiState(isLoading = false, metadata = meta),
                                    customFileNameInput = state.customFileNameInput.ifBlank { meta.fileName }
                                )
                            }
                        },
                        onFailure = { err ->
                            _homeState.update { state ->
                                state.copy(
                                    probeState = ProbeUiState(
                                        isLoading = false,
                                        errorIndo = err.message ?: "Gagal memeriksa informasi file di server."
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    fun startDownloadFromInput() {
        val rawUrl = _homeState.value.urlInput.trim()
        val customName = _homeState.value.customFileNameInput.trim().takeIf { it.isNotBlank() }

        when (val validation = UrlValidator.validateUrl(rawUrl)) {
            is UrlValidationResult.Invalid -> {
                _homeState.update {
                    it.copy(urlValidationError = validation.messageId)
                }
                return
            }
            is UrlValidationResult.Valid -> {
                viewModelScope.launch {
                    _homeState.update { it.copy(isSubmitting = true, urlValidationError = null) }
                    when (val res = queueManager.enqueueDownload(validation.normalizedUrl, customName)) {
                        is EnqueueResult.Enqueued -> {
                            val msg = if (res.isHtmlWarning) {
                                "Ditambahkan: '${res.task.fileName}'. Perhatian: Tautan ini terdeteksi sebagai halaman web HTML, bukan file langsung."
                            } else {
                                "Unduhan '${res.task.fileName}' dimasukkan ke antrean."
                            }
                            _homeState.update {
                                it.copy(
                                    urlInput = "",
                                    customFileNameInput = "",
                                    probeState = ProbeUiState(),
                                    isSubmitting = false,
                                    statusBannerMessage = msg,
                                    isBannerWarning = res.isHtmlWarning
                                )
                            }
                            refreshAvailableStorage()
                        }
                        is EnqueueResult.InvalidUrl -> {
                            _homeState.update {
                                it.copy(
                                    isSubmitting = false,
                                    urlValidationError = res.reasonIndo
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun enqueueMultipleUrls(multilineText: String) {
        val lines = multilineText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _homeState.update { it.copy(isSubmitting = true) }
            val (addedCount, errors) = queueManager.enqueueBatchUrls(lines)
            val msg = buildString {
                append("$addedCount URL berhasil ditambahkan ke antrean.")
                if (errors.isNotEmpty()) {
                    append(" (${errors.size} URL ditolak karena tidak valid).")
                }
            }
            _homeState.update {
                it.copy(
                    isSubmitting = false,
                    statusBannerMessage = msg,
                    isBannerWarning = errors.isNotEmpty()
                )
            }
            refreshAvailableStorage()
        }
    }

    fun clearBannerMessage() {
        _homeState.update { it.copy(statusBannerMessage = null) }
    }

    fun pauseTask(taskId: String) {
        viewModelScope.launch { queueManager.pauseTask(taskId) }
    }

    fun pauseAllTasks() {
        viewModelScope.launch { queueManager.pauseAllActive() }
    }

    fun resumeTask(taskId: String) {
        viewModelScope.launch { queueManager.resumeTask(taskId) }
    }

    fun resumeAllTasks() {
        viewModelScope.launch { queueManager.resumeAllPaused() }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch { queueManager.cancelTask(taskId) }
    }

    fun retryTask(taskId: String, restartFromZero: Boolean = false) {
        viewModelScope.launch { queueManager.retryTask(taskId, restartFromZero) }
    }

    fun changeTaskPriority(taskId: String, delta: Int) {
        viewModelScope.launch { queueManager.changePriority(taskId, delta) }
    }

    fun deleteTask(taskId: String, deletePhysicalFile: Boolean) {
        viewModelScope.launch {
            queueManager.deleteTask(taskId, deleteCompletedFile = deletePhysicalFile)
            refreshAvailableStorage()
        }
    }

    fun openCompletedFile(task: DownloadTaskEntity) {
        val res = mediaStorePublisher.openCompletedFile(task.destinationUri, task.fileName, task.mimeType)
        if (res.isFailure) {
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Tidak ada aplikasi yang dapat membuka '${task.fileName}', atau file telah dipindahkan.",
                    isBannerWarning = true
                )
            }
        }
    }

    fun shareCompletedFile(task: DownloadTaskEntity) {
        val res = mediaStorePublisher.shareCompletedFile(task.destinationUri, task.fileName, task.mimeType)
        if (res.isFailure) {
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Gagal membagikan '${task.fileName}'.",
                    isBannerWarning = true
                )
            }
        }
    }

    fun openSystemDownloadsFolder() {
        val res = mediaStorePublisher.openDownloadsFolder()
        if (res.isFailure) {
            _homeState.update {
                it.copy(
                    statusBannerMessage = "Tidak dapat membuka pengelola file sistem secara langsung.",
                    isBannerWarning = true
                )
            }
        }
    }

    // Settings Actions
    fun updateMaxConcurrent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxConcurrentDownloads(value)
            queueManager.processQueue()
        }
    }

    fun updateDownloadSubfolder(subfolder: String) {
        viewModelScope.launch { settingsRepository.setDownloadSubfolder(subfolder) }
    }

    fun updateWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWifiOnly(enabled)
            queueManager.processQueue()
        }
    }

    fun updateAutoRetry(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoRetryEnabled(enabled) }
    }

    fun updateMaxRetries(value: Int) {
        viewModelScope.launch { settingsRepository.setMaxRetries(value) }
    }

    fun updateMultiSegment(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMultiSegmentEnabled(enabled) }
    }

    fun updateSegmentsPerDownload(value: Int) {
        viewModelScope.launch { settingsRepository.setSegmentsPerDownload(value) }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun updateConfirmBeforeDelete(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setConfirmBeforeDeleteFile(enabled) }
    }

    fun updateClipboardDetection(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setClipboardDetectionEnabled(enabled) }
    }

    fun cleanupOrphanedTempFiles(onResult: (Int, Long) -> Unit) {
        viewModelScope.launch {
            val (count, bytes) = queueManager.cleanupUnusedTempFiles()
            refreshAvailableStorage()
            onResult(count, bytes)
        }
    }
}
