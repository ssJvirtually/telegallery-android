package dev.ssjvirtually.tgpix.worker

import dev.ssjvirtually.tgpix.ErrorMonitor
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi

class DatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Initialize TDLib (no-op if already running)
            TdlibManager.initialize(applicationContext)

            // 2. Wait up to 20 seconds for Telegram auth to be Ready
            //    (session files load instantly on subsequent launches; only slow on first-ever launch)
            val isReady = withTimeoutOrNull(20_000) {
                TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
            }
            if (isReady == null) {
                TdlibManager.addLog("DatabaseBackupWorker: TDLib not authenticated in time. Retrying later.")
                return Result.retry()
            }

            // 3. Wait up to 15 seconds for network connection to be established
            //    (TDLib may still be connecting to servers even after auth state is Ready)
            val isConnected = withTimeoutOrNull(15_000) {
                TdlibManager.connectionStatus.first {
                    it == TdlibManager.ConnectionStatus.CONNECTED
                }
            }
            if (isConnected == null) {
                TdlibManager.addLog("DatabaseBackupWorker: No Telegram connection in time. Retrying later.")
                return Result.retry()
            }

            if (PreferencesManager.isRestoreActive(applicationContext)) {
                TdlibManager.addLog("DatabaseBackupWorker: Aborting backup run because a restore/sync is currently active.")
                return Result.success()
            }

            // 4. Run the backup now that TDLib is fully ready
            val success = BackupManager.backupDatabase(applicationContext)
            if (success) {
                PreferencesManager.setConsecutiveBackupFailures(applicationContext, 0)
                Result.success()
            } else {
                if (PreferencesManager.getChatId(applicationContext) != 0L) {
                    val currentFailures = PreferencesManager.getConsecutiveBackupFailures(applicationContext) + 1
                    PreferencesManager.setConsecutiveBackupFailures(applicationContext, currentFailures)
                    if (currentFailures >= 3) {
                        val lastBackupTime = PreferencesManager.getLastDailyBackupTime(applicationContext)
                        val daysSinceLastBackup = if (lastBackupTime > 0L) {
                            (System.currentTimeMillis() - lastBackupTime) / (24 * 60 * 60 * 1000L)
                        } else {
                            0L
                        }
                        val daysText = if (daysSinceLastBackup > 0L) "for $daysSinceLastBackup days" else "recently"
                        showBackupWarningNotification(applicationContext, daysText)
                    }
                }
                Result.retry()
            }
        } catch (e: Exception) {
            TdlibManager.addLog("DatabaseBackupWorker: Exception during backup: ${e.message}")
            ErrorMonitor.log(e)
            if (PreferencesManager.getChatId(applicationContext) != 0L) {
                val currentFailures = PreferencesManager.getConsecutiveBackupFailures(applicationContext) + 1
                PreferencesManager.setConsecutiveBackupFailures(applicationContext, currentFailures)
                if (currentFailures >= 3) {
                    val lastBackupTime = PreferencesManager.getLastDailyBackupTime(applicationContext)
                    val daysSinceLastBackup = if (lastBackupTime > 0L) {
                        (System.currentTimeMillis() - lastBackupTime) / (24 * 60 * 60 * 1000L)
                    } else {
                        0L
                    }
                    val daysText = if (daysSinceLastBackup > 0L) "for $daysSinceLastBackup days" else "recently"
                    showBackupWarningNotification(applicationContext, daysText)
                }
            }
            Result.retry()
        }
    }

    private fun showBackupWarningNotification(context: Context, daysText: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "tgpix_backup_alerts_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "TGPix Backup Warnings",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warnings about repeating database backup failures."
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = android.content.Intent(context, dev.ssjvirtually.tgpix.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            200,
            intent,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setContentTitle("Backup Warning")
            .setContentText("TGPix couldn't back up your gallery $daysText. Tap to retry.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
            
        notificationManager.notify(999, notification)
    }
}
