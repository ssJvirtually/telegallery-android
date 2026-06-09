package dev.ssjvirtually.tgpix.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class RestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationId = 888
    private val channelId = "tgpix_restore_channel"

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0)
    }

    private fun createForegroundInfo(recoveredCount: Int): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Restoration Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the status of the background vault restoration."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val progressText = if (recoveredCount > 0) {
            "Restoring your gallery — $recoveredCount photos recovered so far..."
        } else {
            "Restoring your gallery — starting scan..."
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("TGPix Restoration")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result {
        val applicationContext = applicationContext

        val chatId = PreferencesManager.getChatId(applicationContext)
        if (chatId == 0L) {
            TdlibManager.addLog("RestoreWorker: No target chat configured. Skipping restore.")
            return Result.failure()
        }

        try {
            setForeground(getForegroundInfo())
            TdlibManager.addLog("RestoreWorker: Promoted restore worker to Foreground Service.")
        } catch (e: Exception) {
            TdlibManager.addLog("RestoreWorker: Failed to start as foreground service: ${e.message}")
        }

        try {
            // Initialize TDLib safely
            TdlibManager.initialize(applicationContext)

            // Wait for authorization state to become Ready
            val isReady = withTimeoutOrNull(20000) {
                TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
            }

            if (isReady == null) {
                TdlibManager.addLog("RestoreWorker: Telegram client not authenticated or timed out. Retrying later.")
                return Result.retry()
            }

            // 1. Attempt database file restore first if the local database is empty
            val db = dev.ssjvirtually.tgpix.storage.UploadDatabase.getDatabase(applicationContext)
            val currentCount = db.cloudDao().getRecordCountDirect()
            if (currentCount == 0) {
                TdlibManager.addLog("RestoreWorker: Live database is empty. Attempting to restore from backup files...")
                val restored = try {
                    dev.ssjvirtually.tgpix.storage.BackupManager.restoreDatabase(applicationContext)
                } catch (e: Exception) {
                    TdlibManager.addLog("RestoreWorker: Error restoring database file: ${e.message}")
                    false
                }
                if (restored) {
                    TdlibManager.addLog("RestoreWorker: Database file restored successfully. Proceeding with full crawl to resolve session file IDs...")
                } else {
                    TdlibManager.addLog("RestoreWorker: No database backup files found. Starting history crawl...")
                }
            }

            // 2. Perform history crawl to resolve volatile session IDs or rebuild timeline
            val startMsgId = PreferencesManager.getLastScannedMessageId(applicationContext)
            
            TdlibManager.syncCloudHistory(
                context = applicationContext,
                chatId = chatId,
                forceFullCrawl = true,
                startMessageId = startMsgId,
                onProgress = { count, lastId ->
                    PreferencesManager.setLastScannedMessageId(applicationContext, lastId)
                    
                    // Update WorkManager progress for UI observers
                    setProgress(
                        workDataOf(
                            "recoveredCount" to count,
                            "progressText" to "Restoring: $count photos recovered"
                        )
                    )
                    
                    // Update persistent notification status
                    try {
                        setForeground(createForegroundInfo(count))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )

            // Clear checkpoint upon successful completion of history crawl
            PreferencesManager.setLastScannedMessageId(applicationContext, 0L)
            TdlibManager.addLog("RestoreWorker: Completed history crawl successfully.")
            return Result.success()

        } catch (e: Exception) {
            TdlibManager.addLog("RestoreWorker: Exception during restore: ${e.message}")
            e.printStackTrace()
            return Result.retry()
        }
    }
}
