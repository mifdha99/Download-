package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.UdaDownloaderApp

/**
 * WorkManager Worker untuk memulihkan antrean unduhan dan menjalankan retry persisten
 * ketika kondisi jaringan terpenuhi atau setelah perangkat dinyalakan ulang.
 */
class QueueRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UdaDownloaderApp ?: return Result.success()
        return runCatching {
            app.queueManager.reconcileAndRestoreQueue()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "uda_queue_recovery_work"

        fun scheduleNetworkRecovery(context: Context, wifiOnly: Boolean) {
            runCatching {
                val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build()

                val request = OneTimeWorkRequestBuilder<QueueRecoveryWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
