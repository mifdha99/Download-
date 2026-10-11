package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.UdaDownloaderApp
import kotlinx.coroutines.launch

class DownloadNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? UdaDownloaderApp ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val pendingResult = goAsync()

        app.applicationScope.launch {
            try {
                when (action) {
                    ACTION_PAUSE -> {
                        if (!taskId.isNullOrBlank()) {
                            app.queueManager.pauseTask(taskId)
                        }
                    }
                    ACTION_RESUME -> {
                        if (!taskId.isNullOrBlank()) {
                            app.queueManager.resumeTask(taskId)
                        }
                    }
                    ACTION_CANCEL -> {
                        if (!taskId.isNullOrBlank()) {
                            app.queueManager.cancelTask(taskId)
                        }
                    }
                    ACTION_PAUSE_ALL -> {
                        app.queueManager.pauseAllActive()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.udadownloader.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.udadownloader.app.ACTION_RESUME"
        const val ACTION_CANCEL = "com.udadownloader.app.ACTION_CANCEL"
        const val ACTION_PAUSE_ALL = "com.udadownloader.app.ACTION_PAUSE_ALL"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
