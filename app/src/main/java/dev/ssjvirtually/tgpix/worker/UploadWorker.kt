package dev.ssjvirtually.tgpix.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.UploadEntity
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.UploadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationId = 999
    private val channelId = "tgpix_backup_channel"

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Preparing photo backup...")
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Photo Backup Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the status of the background photo backup."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("TGPix Photo Backup")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
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
        
        // 1. Verify if backups are currently enabled by the user in Settings
        val isBackupActive = PreferencesManager.isBackupActive(applicationContext)
        if (!isBackupActive) {
            TdlibManager.addLog("Worker: Backup is paused/disabled by the user. Skipping.")
            return Result.success()
        }

        // 2. Verify that we have a configured target chat
        val chatId = PreferencesManager.getChatId(applicationContext)
        if (chatId == 0L) {
            TdlibManager.addLog("Worker: No target chat configured. Skipping backup.")
            return Result.failure()
        }

        // 3. Promote worker to foreground service to prevent OS termination & bypass 10-minute timeout
        try {
            setForeground(getForegroundInfo())
            TdlibManager.addLog("Worker: Promoted backup worker to Foreground Service.")
        } catch (e: Exception) {
            TdlibManager.addLog("Worker: Failed to start as foreground service: ${e.message}")
        }

        // 4. Acquire WakeLock and WifiLock to keep CPU & Network alive when phone is locked
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGPix::BackupWakeLock")
        
        val wifiManager = applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TGPix::BackupWifiLock")
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "TGPix::BackupWifiLock")
        }

        try {
            // Acquire locks with a 1-hour safety timeout to prevent permanent battery drain in failure states
            wakeLock.acquire(60 * 60 * 1000L)
            wifiLock.acquire()
            TdlibManager.addLog("Worker: Acquired CPU WakeLock and Wi-Fi High-Performance Lock.")

            // 5. Initialize TDLib safely
            TdlibManager.initialize(applicationContext)

            // 6. Wait for authorization state to become Ready (loaded from local session files)
            val isReady = withTimeoutOrNull(15000) {
                TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
            }

            if (isReady == null) {
                TdlibManager.addLog("Worker: Telegram client not authenticated or timed out. Retrying later.")
                return Result.retry()
            }

            // 7. Scan all photos on the device
            val photos = MediaStoreScanner.scan(applicationContext)
            if (photos.isEmpty()) {
                TdlibManager.addLog("Worker: No photos found on device to sync.")
                return Result.success()
            }

            // 8. Open database
            val db = UploadDatabase.getDatabase(applicationContext)
            val dao = db.dao()

            var uploadedCount = 0

            // 9. Loop and upload new photos sequentially (one-by-one)
            for (photo in photos) {
                // Re-verify backup toggle mid-run in case it was switched off during active sequence
                if (!PreferencesManager.isBackupActive(applicationContext)) {
                    TdlibManager.addLog("Worker: Backup was disabled during execution. Aborting active sync.")
                    break
                }

                val fileKey = photo.uri
                val existing = dao.find(fileKey)

                if (existing == null) {
                    // Update user-visible notification with active upload progress
                    try {
                        val progressMsg = "Backing up: ${photo.name} (${uploadedCount + 1}/${photos.size})"
                        setForeground(createForegroundInfo(progressMsg))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Prevent duplicate upload if already in cloud database from another device
                    val fingerprint = "${photo.name}_${photo.size}_${photo.dateTaken}"
                    val existingInCloud = db.cloudDao().findByFingerprint(fingerprint)
                        ?: db.cloudDao().findByFileName(photo.name)
                    
                    if (existingInCloud != null) {
                        dao.insert(
                            UploadEntity(
                                path = fileKey,
                                uploadedAt = existingInCloud.uploadedAt
                            )
                        )
                        TdlibManager.addLog("Worker: '${photo.name}' already exists in cloud history. Skipping and marking as synced.")
                        continue
                    }

                    val currentIsHd = PreferencesManager.isHdMode(applicationContext)
                    val modeStr = if (currentIsHd) "HD" else "standard quality"
                    TdlibManager.addLog("Worker: backing up photo '${photo.name}' in $modeStr...")
                    
                    val uploadResult = UploadManager.uploadPhoto(applicationContext, photo, chatId, currentIsHd)

                    if (uploadResult is TdApi.Message) {
                        TdlibManager.addLog("Worker: successfully backed up '${photo.name}'! Message ID: ${uploadResult.id}")
                        dao.insert(
                            UploadEntity(
                                path = fileKey,
                                uploadedAt = System.currentTimeMillis()
                            )
                        )
                        uploadedCount++
                    } else if (uploadResult is TdApi.Error) {
                        TdlibManager.addLog("Worker: failed to back up '${photo.name}': [${uploadResult.code}] ${uploadResult.message}")
                    } else {
                        TdlibManager.addLog("Worker: backup returned unexpected response: ${uploadResult::class.java.simpleName}")
                    }

                    // Add 5-second throttle delay between uploads to prevent rate limiting (FLOOD_WAIT) on Telegram's servers
                    TdlibManager.addLog("Worker: waiting 5 seconds before next upload to prevent server flooding...")
                    delay(5000)
                }
            }

            TdlibManager.addLog("Worker: backup run completed. Newly backed up: $uploadedCount photos.")
            return Result.success()

        } catch (e: Exception) {
            TdlibManager.addLog("Worker: Execution failed with error: ${e.message}")
            return Result.failure()
        } finally {
            // 10. Always release WakeLock and WifiLock under all circumstances to prevent battery drain
            if (wifiLock.isHeld) {
                wifiLock.release()
                TdlibManager.addLog("Worker: Released Wi-Fi Lock.")
            }
            if (wakeLock.isHeld) {
                wakeLock.release()
                TdlibManager.addLog("Worker: Released CPU WakeLock.")
            }
        }
    }
}
