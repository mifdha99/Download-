package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "uda_settings")

enum class ThemeMode(val label: String) {
    SYSTEM("Ikuti Sistem"),
    LIGHT("Mode Terang"),
    DARK("Mode Gelap")
}

data class AppSettings(
    val maxConcurrentDownloads: Int = 3,
    val downloadSubfolder: String = "UdaDownloader",
    val wifiOnly: Boolean = false,
    val autoRetryEnabled: Boolean = true,
    val maxRetries: Int = 3,
    val multiSegmentEnabled: Boolean = false,
    val segmentsPerDownload: Int = 2,
    val notificationsEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val confirmBeforeDeleteFile: Boolean = true,
    val clipboardDetectionEnabled: Boolean = true
)

class SettingsRepository(
    private val context: Context,
    private val overrideSettings: AppSettings? = null
) {

    private object Keys {
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent_downloads")
        val DOWNLOAD_SUBFOLDER = stringPreferencesKey("download_subfolder")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val AUTO_RETRY = booleanPreferencesKey("auto_retry_enabled")
        val MAX_RETRIES = intPreferencesKey("max_retries")
        val MULTI_SEGMENT = booleanPreferencesKey("multi_segment_enabled")
        val SEGMENTS_COUNT = intPreferencesKey("segments_per_download")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CONFIRM_DELETE_FILE = booleanPreferencesKey("confirm_before_delete_file")
        val CLIPBOARD_DETECTION = booleanPreferencesKey("clipboard_detection_enabled")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        overrideSettings ?: run {
            val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
            val theme = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.SYSTEM)
            AppSettings(
                maxConcurrentDownloads = (prefs[Keys.MAX_CONCURRENT] ?: 3).coerceIn(1, 5),
                downloadSubfolder = sanitizeSubfolder(prefs[Keys.DOWNLOAD_SUBFOLDER] ?: "UdaDownloader"),
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
                autoRetryEnabled = prefs[Keys.AUTO_RETRY] ?: true,
                maxRetries = (prefs[Keys.MAX_RETRIES] ?: 3).coerceIn(1, 5),
                multiSegmentEnabled = prefs[Keys.MULTI_SEGMENT] ?: false,
                segmentsPerDownload = (prefs[Keys.SEGMENTS_COUNT] ?: 2).coerceIn(1, 4),
                notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
                themeMode = theme,
                confirmBeforeDeleteFile = prefs[Keys.CONFIRM_DELETE_FILE] ?: true,
                clipboardDetectionEnabled = prefs[Keys.CLIPBOARD_DETECTION] ?: true
            )
        }
    }

    suspend fun getSettingsSnapshot(): AppSettings = overrideSettings ?: settingsFlow.first()

    suspend fun setMaxConcurrentDownloads(value: Int) {
        context.dataStore.edit { it[Keys.MAX_CONCURRENT] = value.coerceIn(1, 5) }
    }

    suspend fun setDownloadSubfolder(subfolder: String) {
        context.dataStore.edit { it[Keys.DOWNLOAD_SUBFOLDER] = sanitizeSubfolder(subfolder) }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setAutoRetryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RETRY] = enabled }
    }

    suspend fun setMaxRetries(value: Int) {
        context.dataStore.edit { it[Keys.MAX_RETRIES] = value.coerceIn(1, 5) }
    }

    suspend fun setMultiSegmentEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MULTI_SEGMENT] = enabled }
    }

    suspend fun setSegmentsPerDownload(value: Int) {
        context.dataStore.edit { it[Keys.SEGMENTS_COUNT] = value.coerceIn(1, 4) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setConfirmBeforeDeleteFile(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CONFIRM_DELETE_FILE] = enabled }
    }

    suspend fun setClipboardDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CLIPBOARD_DETECTION] = enabled }
    }

    companion object {
        fun sanitizeSubfolder(input: String): String {
            val cleaned = input.trim()
                .replace("..", "")
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
            return cleaned.ifEmpty { "UdaDownloader" }.take(40)
        }
    }
}
