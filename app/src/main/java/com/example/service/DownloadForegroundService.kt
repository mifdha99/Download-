package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.UdaDownloaderApp
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely(buildSummaryNotification(emptyList()))
        startObservingTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely(buildSummaryNotification(emptyList()))
        if (monitorJob?.isActive != true) {
            startObservingTransfers()
        }
        return START_NOT_STICKY
    }

    private fun startObservingTransfers() {
        val app = application as? UdaDownloaderApp ?: return
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            combine(
                app.queueManager.allTasksFlow,
                app.settingsRepository.settingsFlow
            ) { tasks, settings ->
                tasks to settings.notificationsEnabled
            }.collect { (tasks, notificationsEnabled) ->
                val activeTasks = tasks.filter {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING
                }
                val queuedTasks = tasks.filter { it.status == DownloadStatus.QUEUED }

                if (activeTasks.isEmpty() && queuedTasks.isEmpty()) {
                    // Beri jeda singkat untuk memastikan tidak ada transisi antrean yang sedang berlangsung
                    delay(800L)
                    if (!app.queueManager.hasActiveTransfers()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                }

                if (notificationsEnabled) {
                    val notification = buildSummaryNotification(activeTasks)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID_FOREGROUND, notification)
                }
            }
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_FOREGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification)
            }
        }
    }

    private fun buildSummaryNotification(activeTasks: List<DownloadTaskEntity>): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, UdaDownloaderApp.CHANNEL_ACTIVE_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (activeTasks.isEmpty()) {
            builder.setContentTitle("Uda Downloader")
                .setContentText("Menyiapkan antrean unduhan...")
                .setProgress(100, 0, true)
            return builder.build()
        }

        if (activeTasks.size == 1) {
            val task = activeTasks.first()
            val percent = task.progressPercent
            val speedText = FormatUtils.formatSpeed(task.speedBytesPerSec)
            val progressBytesText = if (task.totalBytes > 0L) {
                "${FormatUtils.formatBytes(task.downloadedBytes)} / ${FormatUtils.formatBytes(task.totalBytes)}"
            } else {
                FormatUtils.formatBytes(task.downloadedBytes)
            }

            builder.setContentTitle(task.fileName)
                .setContentText("$progressBytesText • $speedText")

            if (percent != null) {
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(100, 0, true)
            }

            // Tombol Jeda & Batalkan untuk tugas tunggal
            val pauseIntent = Intent(this, DownloadNotificationReceiver::class.java).apply {
                action = DownloadNotificationReceiver.ACTION_PAUSE
                putExtra(DownloadNotificationReceiver.EXTRA_TASK_ID, task.id)
            }
            val pausePi = PendingIntent.getBroadcast(
                this,
                task.id.hashCode(),
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cancelIntent = Intent(this, DownloadNotificationReceiver::class.java).apply {
                action = DownloadNotificationReceiver.ACTION_CANCEL
                putExtra(DownloadNotificationReceiver.EXTRA_TASK_ID, task.id)
            }
            val cancelPi = PendingIntent.getBroadcast(
                this,
                task.id.hashCode() + 1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.btn_pause), pausePi)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_cancel), cancelPi)
        } else {
            val totalSpeed = activeTasks.sumOf { it.speedBytesPerSec }
            val knownTotalTasks = activeTasks.filter { it.totalBytes > 0L }
            val combinedPercent = if (knownTotalTasks.size == activeTasks.size) {
                val sumDown = knownTotalTasks.sumOf { it.downloadedBytes }.toDouble()
                val sumTotal = knownTotalTasks.sumOf { it.totalBytes }.toDouble()
                if (sumTotal > 0.0) ((sumDown / sumTotal) * 100.0).toInt().coerceIn(0, 100) else null
            } else null

            builder.setContentTitle("Mengunduh ${activeTasks.size} file secara bersamaan")
                .setContentText("Kecepatan total: ${FormatUtils.formatSpeed(totalSpeed)}")

            if (combinedPercent != null) {
                builder.setProgress(100, combinedPercent, false)
            } else {
                builder.setProgress(100, 0, true)
            }

            val pauseAllIntent = Intent(this, DownloadNotificationReceiver::class.java).apply {
                action = DownloadNotificationReceiver.ACTION_PAUSE_ALL
            }
            val pauseAllPi = PendingIntent.getBroadcast(
                this,
                999,
                pauseAllIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Jeda Semua", pauseAllPi)
        }

        return builder.build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID_FOREGROUND = 4101

        fun startOrUpdate(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
