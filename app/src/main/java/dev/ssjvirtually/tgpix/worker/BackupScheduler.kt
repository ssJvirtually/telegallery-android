package dev.ssjvirtually.tgpix.worker

import android.content.Context
import androidx.work.*
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import java.util.concurrent.TimeUnit

object BackupScheduler {
    
    fun schedulePhotoBackup(context: Context) {
        val appContext = context.applicationContext
        val isBackupActive = PreferencesManager.isBackupActive(appContext)
        val chatId = PreferencesManager.getChatId(appContext)
        
        if (!isBackupActive || chatId == 0L) {
            // Cancel background backups instantly
            WorkManager.getInstance(appContext).cancelUniqueWork("upload_worker")
            WorkManager.getInstance(appContext).cancelUniqueWork("upload_worker_one_time")
            return
        }

        val wifiOnly = PreferencesManager.isWifiOnly(appContext)
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        // 1. Enqueue Periodic Work for long-term scheduling (every 15 mins)
        val request = PeriodicWorkRequestBuilder<UploadWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
        ).build()

        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(
                "upload_worker",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

        // 2. Enqueue One-Time Work to run immediately
        val oneTimeRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build()
            ).build()

        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(
                "upload_worker_one_time",
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
    }
}
