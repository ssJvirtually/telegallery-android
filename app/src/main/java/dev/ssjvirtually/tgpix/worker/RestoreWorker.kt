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

        // Initialize TDLib safely
        TdlibManager.initialize(applicationContext)

        // Wait for authorization state to become Ready (timeout 5 seconds)
        val isReady = withTimeoutOrNull(5000) {
            TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
        }

        if (isReady == null) {
            TdlibManager.addLog("RestoreWorker: Telegram client not authenticated or timed out. Aborting restore.")
            PreferencesManager.setRestoreActive(applicationContext, false)
            return Result.failure()
        }

        PreferencesManager.setRestoreActive(applicationContext, true)
        // Instantly cancel any active photo backup and database backup workers since restore is now active
        try {
            BackupScheduler.schedulePhotoBackup(applicationContext)
            androidx.work.WorkManager.getInstance(applicationContext).cancelUniqueWork("db_backup")
        } catch (e: Exception) {
            TdlibManager.addLog("RestoreWorker: Failed to cancel backup workers on startup: ${e.message}")
        }

        var restoreCompleted = false
        try {
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

            // 1. Attempt database file restore first if the local database is empty
            val db = dev.ssjvirtually.tgpix.storage.UploadDatabase.getDatabase(applicationContext)
            val currentCount = db.cloudDao().getRecordCountDirect()
            val wasFreshCrawl = currentCount == 0
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

            // 3. Sync media uploads from Vault Channel.
            // After a snapshot restore, perform a delta crawl (forceFullCrawl = false) to pick up
            // any photos uploaded after the snapshot. Also skip duplicate cleanup because the
            // snapshot + delta crawl naturally produces overlapping records via INSERT OR REPLACE.
            // On a truly fresh device with no snapshot, always do a full crawl from the beginning
            // (startMsgId = 0) regardless of any stale lastScannedMessageId from old sessions.
            val forceFullCrawl = if (restoredFromSnapshot) false else inputData.getBoolean("forceFullCrawl", true)
            // If no snapshot was restored, this is a full crawl from scratch — ignore any leftover
            // lastScannedMessageId that may have been saved by a previous interrupted session on
            // another device, as it would cause the crawl to start mid-history and miss older photos.
            val startMsgId = if (restoredFromSnapshot) PreferencesManager.getLastScannedMessageId(applicationContext) else 0L

            // Skip destructive duplicate cleanup unless this was a genuine fresh full crawl
            // from an empty database. Delta crawls and redundant runs must never delete photos.
            val skipDuplicateCleanup = restoredFromSnapshot || !wasFreshCrawl

            HistorySyncManager.syncCloudHistory(
                context = applicationContext,
                chatId = chatId,
                forceFullCrawl = forceFullCrawl,
                startMessageId = startMsgId,
                skipDuplicateCleanup = skipDuplicateCleanup,
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

            restoreCompleted = true
            TdlibManager.addLog("RestoreWorker: Restore completed successfully.")

            // Publish a first-time database snapshot now that we have a full crawl.
            // This ensures the next fresh-device login finds a snapshot immediately
            // instead of having to repeat the full history crawl.
            // Only do this for genuine fresh crawls — not redundant delta runs on
            // a database that already had records when the worker started.
            if (!restoredFromSnapshot && wasFreshCrawl) {
                // Clear restore active first so scheduleBackup is allowed to schedule the work!
                PreferencesManager.setRestoreActive(applicationContext, false)
                
                TdlibManager.addLog("RestoreWorker: Scheduling initial database snapshot after first full crawl.")
                try {
                    dev.ssjvirtually.tgpix.storage.BackupManager.scheduleBackup(applicationContext)
                } catch (e: Exception) {
                    TdlibManager.addLog("RestoreWorker: Failed to schedule initial snapshot: ${e.message}")
                }
            }

            // Sync lastBackupRecordCount with the final database state so that the
            // debounce backup trigger in GalleryViewModel doesn't immediately schedule
            // a redundant DatabaseBackupWorker on the data we just restored.
            try {
                val finalDb = dev.ssjvirtually.tgpix.storage.UploadDatabase.getDatabase(applicationContext)
                val finalCount = finalDb.cloudDao().getRecordCountDirect()
                PreferencesManager.setLastBackupRecordCount(applicationContext, finalCount)
                TdlibManager.addLog("RestoreWorker: Synced lastBackupRecordCount to $finalCount after restore.")
            } catch (e: Exception) {
                TdlibManager.addLog("RestoreWorker: Failed to sync backup record count: ${e.message}")
            }

            TdlibManager.addLog("RestoreWorker: Completed restore sequence successfully.")
            return Result.success()

        } catch (e: Exception) {
            TdlibManager.addLog("RestoreWorker: Unexpected exception during restore: ${e.message}")
            ErrorMonitor.log(e)
            // Max 5 attempts (runAttemptCount starts at 0), then give up and release the restore guard
            if (runAttemptCount >= 4) {
                TdlibManager.addLog("RestoreWorker: Max retries ($runAttemptCount) reached. Giving up.")
                restoreCompleted = true
                return Result.failure()
            }
            return Result.retry()
        } finally {
            // Only release the restore guard and reschedule uploads if the
            // restore actually completed. Otherwise `isRestoreActive` stays
            // true so the debounce backup observer cannot schedule a
            // DatabaseBackupWorker that would snapshot a partial database
            // and overwrite the good remote snapshot with incomplete data.
            if (restoreCompleted) {
                PreferencesManager.setRestoreActive(applicationContext, false)
                try {
                    BackupScheduler.schedulePhotoBackup(applicationContext)
                } catch (e: Exception) {
                    TdlibManager.addLog("RestoreWorker: Failed to reschedule backups: ${e.message}")
                }
            } else {
                TdlibManager.addLog("RestoreWorker: Restore did not complete — keeping isRestoreActive=true to prevent partial backups.")
            }
        }
    }
}
