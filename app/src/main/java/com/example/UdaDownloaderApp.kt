package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.engine.DownloadQueueManager
import com.example.engine.HttpDownloadEngine
import com.example.engine.MediaStorePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UdaDownloaderApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val mediaStorePublisher: MediaStorePublisher by lazy { MediaStorePublisher(this) }
    val httpDownloadEngine: HttpDownloadEngine by lazy { HttpDownloadEngine() }

    val queueManager: DownloadQueueManager by lazy {
        DownloadQueueManager(
            context = this,
            downloadDao = database.downloadDao(),
            settingsRepository = settingsRepository,
            mediaStorePublisher = mediaStorePublisher,
            httpEngine = httpDownloadEngine,
            externalScope = applicationScope
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        applicationScope.launch {
            queueManager.reconcileAndRestoreQueue()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val activeChannel = NotificationChannel(
                CHANNEL_ACTIVE_DOWNLOADS,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(activeChannel)
        }
    }

    companion object {
        const val CHANNEL_ACTIVE_DOWNLOADS = "uda_active_downloads_channel"
    }
}
