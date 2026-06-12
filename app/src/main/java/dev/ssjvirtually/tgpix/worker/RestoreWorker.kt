package dev.ssjvirtually.tgpix.worker

import dev.ssjvirtually.tgpix.ErrorMonitor
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
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.HistorySyncManager
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                TdlibManager.addLog("RestoreWorker: Foreground service start not allowed (Android 14+ constraints). Falling back to standard background execution: ${e.message}")
            } else if (e is IllegalStateException) {
                TdlibManager.addLog("RestoreWorker: Progress update failed, worker likely stopped: ${e.message}")
            } else {
                TdlibManager.addLog("RestoreWorker: Failed to start as foreground service: ${e.message}")
            }
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
            var restoredFromSnapshot = false
            if (currentCount == 0) {
                TdlibManager.addLog("RestoreWorker: Live database is empty. Attempting to restore from remote snapshot...")
                val restored = try {
                    dev.ssjvirtually.tgpix.storage.BackupManager.restoreDatabase(applicationContext)
                } catch (e: Exception) {
                    TdlibManager.addLog("RestoreWorker: Error restoring database snapshot: ${e.message}")
                    false
                }
                if (restored) {
                    restoredFromSnapshot = true
                    TdlibManager.addLog("RestoreWorker: Database snapshot restored successfully.")
                } else {
                    TdlibManager.addLog("RestoreWorker: No database snapshot found or restore failed. Proceeding with history crawl...")
                }
            }

            // 2. Replay metadata events from Metadata Channel
            val dbChatId = BackupManager.resolveBackupChatId(applicationContext)
            if (dbChatId != 0L) {
                try {
                    HistorySyncManager.syncMetadataHistory(applicationContext, dbChatId)
                } catch (e: Exception) {
                    TdlibManager.addLog("RestoreWorker: Failed to sync metadata history: ${e.message}")
                }
            }

            // 3. Sync media uploads from Vault Channel
            // If we successfully restored from a snapshot, we override forceFullCrawl to false to avoid redundant crawls.
            val forceFullCrawl = if (restoredFromSnapshot) false else inputData.getBoolean("forceFullCrawl", true)
            val startMsgId = PreferencesManager.getLastScannedMessageId(applicationContext)
            
            HistorySyncManager.syncCloudHistory(
                context = applicationContext,
                chatId = chatId,
                forceFullCrawl = forceFullCrawl,
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
                        ErrorMonitor.log(e)
                    }
                }
            )

            // Clear checkpoint upon successful completion of history crawl
            PreferencesManager.setLastScannedMessageId(applicationContext, 0L)

            try {
                BackupManager.reconstructAlbumsFromBackupChannel(applicationContext)
            } catch (e: Exception) {
                TdlibManager.addLog("RestoreWorker: Failed to reconstruct albums: ${e.message}")
            }
            
            TdlibManager.addLog("RestoreWorker: Completed restore sequence successfully.")
            return Result.success()

        } catch (e: Exception) {
            TdlibManager.addLog("RestoreWorker: Exception during restore: ${e.message}")
            ErrorMonitor.log(e)
            return Result.retry()
        }
    }
}
