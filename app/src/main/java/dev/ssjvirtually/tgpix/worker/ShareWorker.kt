package dev.ssjvirtually.tgpix.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.UploadEntity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.UploadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeUnit

class ShareWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationId = 888
    private val channelId = "tgpix_share_channel"

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Preparing shared photos...")
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Photo Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the status of active in-app Telegram sharing."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("TGPix Sharing")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.ic_menu_send)
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
        val targetChatId = inputData.getLong("chat_id", 0L)
        val photoUris = inputData.getStringArray("photo_uris") ?: emptyArray()

        if (targetChatId == 0L || photoUris.isEmpty()) {
            TdlibManager.addLog("ShareWorker: Invalid arguments. Aborting.")
            return Result.failure()
        }

        // 1. Promote to Foreground Service to display progress notification
        try {
            setForeground(getForegroundInfo())
            TdlibManager.addLog("ShareWorker: Promoted manual sharing to Foreground Service.")
        } catch (e: Exception) {
            TdlibManager.addLog("ShareWorker: Failed to set foreground: ${e.message}")
        }

        // 2. Preempt background backup sync if sharing > 10 items
        var isPreempted = false
        if (photoUris.size > 10) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("upload_worker")
            isPreempted = true
            TdlibManager.addLog("ShareWorker: Suspended background 'upload_worker' to prioritize manual share.")
        }

        try {
            // 3. Wait for TDLib
            TdlibManager.initialize(applicationContext)
            val isReady = withTimeoutOrNull(15000) {
                TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
            }

            if (isReady == null) {
                TdlibManager.addLog("ShareWorker: TDLib client not authenticated or timed out.")
                return Result.retry()
            }

            // 4. Resolve local & cloud photos to share
            val db = UploadDatabase.getDatabase(applicationContext)
            val allLocalPhotos = MediaStoreScanner.scan(applicationContext)
            val photosToShare = photoUris.mapNotNull { uri ->
                if (uri.startsWith("cloud://")) {
                    val parts = uri.substringAfter("cloud://").split("/")
                    val msgId = parts[0].toLong()
                    val name = parts[2]
                    LocalPhoto(
                        id = -msgId,
                        uri = uri,
                        name = name,
                        size = 0L,
                        dateTaken = System.currentTimeMillis()
                    )
                } else {
                    allLocalPhotos.find { it.uri == uri }
                }
            }

            val mainChatId = PreferencesManager.getChatId(applicationContext)
            val isHd = PreferencesManager.isHdMode(applicationContext)

            // 5. Backup-Before-Share pipeline loop (upload if local-only and not backed up yet)
            for ((index, photo) in photosToShare.withIndex()) {
                val isSynced = db.dao().find(photo.uri) != null
                if (!isSynced && !photo.uri.startsWith("cloud://") && mainChatId != 0L) {
                    try {
                        val backupProgressMsg = "Backing up: ${photo.name} before sharing (${index + 1}/${photosToShare.size})..."
                        setForeground(createForegroundInfo(backupProgressMsg))
                    } catch (e: Exception) {}
                    
                    TdlibManager.addLog("ShareWorker: '${photo.name}' is not backed up. Uploading to main vault first...")
                    val backupResult = UploadManager.uploadPhoto(applicationContext, photo, mainChatId, isHd)
                    if (backupResult is TdApi.Message) {
                        db.dao().insert(
                            UploadEntity(
                                mediaStoreId = photo.id,
                                path = photo.uri,
                                contentFingerprint = "${photo.name}_${photo.size}_${photo.dateTaken}",
                                uploadedAt = System.currentTimeMillis(),
                                telegramMessageId = backupResult.id
                            )
                        )
                        TdlibManager.addLog("ShareWorker: Successfully backed up '${photo.name}' to main vault.")
                    } else {
                        TdlibManager.addLog("ShareWorker: Failed to back up '${photo.name}' before sharing. Continuing.")
                    }
                }
            }

            // 6. Batch Share to target chat
            try {
                setForeground(createForegroundInfo("Sharing ${photosToShare.size} items..."))
            } catch (e: Exception) {}
            
            val shareResult = UploadManager.sharePhotosToTelegramChat(applicationContext, photosToShare, targetChatId)
            
            if (shareResult) {
                TdlibManager.addLog("ShareWorker: Manual sharing completed successfully for all ${photosToShare.size} items.")
            } else {
                TdlibManager.addLog("ShareWorker: Manual sharing finished, but some items failed.")
            }
            return Result.success()

        } catch (e: Exception) {
            TdlibManager.addLog("ShareWorker: Sharing failed: ${e.message}")
            return Result.failure()
        } finally {
            // 6. Restore background sync if preempted
            if (isPreempted && PreferencesManager.isBackupActive(applicationContext)) {
                val wifiOnly = PreferencesManager.isWifiOnly(applicationContext)
                val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                    "upload_worker",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                TdlibManager.addLog("ShareWorker: Resumed background 'upload_worker' sync task.")
            }
        }
    }
}
