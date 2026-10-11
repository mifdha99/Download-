package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.UdaDownloaderApp
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as? UdaDownloaderApp ?: return
            val pendingResult = goAsync()
            app.applicationScope.launch {
                try {
                    val settings = app.settingsRepository.getSettingsSnapshot()
                    app.queueManager.reconcileAndRestoreQueue()
                    QueueRecoveryWorker.scheduleNetworkRecovery(context, settings.wifiOnly)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
