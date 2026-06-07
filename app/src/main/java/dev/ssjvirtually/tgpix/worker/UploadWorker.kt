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
import dev.ssjvirtually.tgpix.storage.getFingerprint
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.UploadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import org.drinkless.tdlib.TdApi

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private val uploadMutex = Mutex()
    }

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

        // Concurrency Guard: Skip if another backup process is already active
        if (!uploadMutex.tryLock()) {
            TdlibManager.addLog("Worker: Another backup sync task is already running. Skipping this instance.")
            return Result.success()
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
        // No timeout on acquire() — the finally block unconditionally releases both locks,
        // so there is no risk of permanent battery drain. A hard 1-hour limit would silently
        // cut off large photo libraries (500 photos × 5 s delay = 41 min, plus upload time).
        wakeLock.setReferenceCounted(false)

        val wifiManager = applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TGPix::BackupWifiLock")
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "TGPix::BackupWifiLock")
        }

        try {
            // Acquire with no timeout — released unconditionally in the finally block
            wakeLock.acquire()
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

            // 7. Scan all photos on the device and sort to backup from oldest to newest (by date taken)
            val photos = MediaStoreScanner.scan(applicationContext).sortedBy { it.dateTaken }
            if (photos.isEmpty()) {
                TdlibManager.addLog("Worker: No photos found on device to sync.")
                return Result.success()
            }

            // 8. Open database
            val db = UploadDatabase.getDatabase(applicationContext)
            val dao = db.dao()

            // 8a. Load all local uploads and cloud photos to perform fast in-memory matching
            val localUploads = dao.getAll()
            val localUploadedPaths = localUploads.map { it.path }.toSet()

            val cloudPhotos = db.cloudDao().getAll()
            val cloudByFileName = cloudPhotos.groupBy { it.fileName.lowercase() }

            val unsyncedPhotos = mutableListOf<dev.ssjvirtually.tgpix.storage.LocalPhoto>()
            val syncedToInsert = mutableListOf<UploadEntity>()

            for (photo in photos) {
                if (photo.uri in localUploadedPaths) {
                    continue
                }

                // Check if already in cloud database (e.g. from another device or prior sync)
                val candidatesByName = cloudByFileName[photo.name.lowercase()]
                var existingInCloud: dev.ssjvirtually.tgpix.storage.CloudPhotoEntity? = null
                if (candidatesByName != null) {
                    val prefix = "${photo.name}_${photo.size}_"
                    existingInCloud = candidatesByName.firstOrNull { it.contentFingerprint.startsWith(prefix) }
                        ?: candidatesByName.firstOrNull() // fallback to first match by filename
                }

                if (existingInCloud != null) {
                    syncedToInsert.add(
                        UploadEntity(
                            mediaStoreId = photo.id,
                            path = photo.uri,
                            contentFingerprint = existingInCloud.contentFingerprint,
                            uploadedAt = existingInCloud.uploadedAt,
                            telegramMessageId = existingInCloud.messageId
                        )
                    )
                } else {
                    unsyncedPhotos.add(photo)
                }
            }

            // Batch insert synced files
            if (syncedToInsert.isNotEmpty()) {
                dao.insertBatch(syncedToInsert)
                TdlibManager.addLog("Worker: Marked ${syncedToInsert.size} photos as synced in database (matched with cloud).")
            }

            if (unsyncedPhotos.isEmpty()) {
                TdlibManager.addLog("Worker: No unsynced photos to back up.")
                return Result.success()
            }

            TdlibManager.addLog("Worker: Found ${unsyncedPhotos.size} unsynced photos out of ${photos.size} total local photos.")

            var uploadedCount = 0
            var failedCount = 0
            var skippedCount = 0

            // 9. Loop and upload unsynced photos sequentially (one-by-one)
            for (photo in unsyncedPhotos) {
                // Re-verify backup toggle mid-run in case it was switched off during active sequence
                if (!PreferencesManager.isBackupActive(applicationContext)) {
                    TdlibManager.addLog("Worker: Backup was disabled during execution. Aborting active sync.")
                    break
                }

                // Belt-and-suspenders: re-acquire WakeLock each iteration so it stays held
                // even if it was somehow released externally. setReferenceCounted(false) means
                // multiple acquire() calls don't stack — only one release() is ever needed.
                if (!wakeLock.isHeld) {
                    wakeLock.acquire()
                    TdlibManager.addLog("Worker: Re-acquired WakeLock mid-run.")
                }

                // Per-photo isolation: an exception on one photo must never abort the entire
                // batch. Corrupt files, missing content resolver paths, or transient TDLib
                // errors should log clearly and skip to the next photo.
                try {
                    val fileKey = photo.uri

                    // Update user-visible notification with active upload progress
                    try {
                        val progressMsg = "Backing up: ${photo.name} (${uploadedCount + 1}/${unsyncedPhotos.size})"
                        setForeground(createForegroundInfo(progressMsg))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val fingerprint = photo.getFingerprint(applicationContext)
                    val currentIsHd = PreferencesManager.isHdMode(applicationContext)
                    val modeStr = if (currentIsHd) "HD" else "standard quality"
                    TdlibManager.addLog("Worker: backing up photo '${photo.name}' in $modeStr...")

                    val uploadResult = UploadManager.uploadPhoto(applicationContext, photo, chatId, currentIsHd)

                    when (uploadResult) {
                        is TdApi.Message -> {
                            TdlibManager.addLog("Worker: successfully backed up '${photo.name}'! Message ID: ${uploadResult.id}")
                            dao.insert(
                                UploadEntity(
                                    mediaStoreId = photo.id,
                                    path = fileKey,
                                    contentFingerprint = fingerprint,
                                    uploadedAt = System.currentTimeMillis(),
                                    telegramMessageId = uploadResult.id
                                )
                            )
                            try {
                                TdlibManager.parseAndIndexUploadedMessage(applicationContext, uploadResult)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            uploadedCount++
                        }
                        is TdApi.Error -> {
                            TdlibManager.addLog("Worker: failed to back up '${photo.name}': [${uploadResult.code}] ${uploadResult.message}")
                            failedCount++
                        }
                        else -> {
                            TdlibManager.addLog("Worker: backup returned unexpected response for '${photo.name}': ${uploadResult::class.java.simpleName}")
                            skippedCount++
                        }
                    }
                } catch (e: Exception) {
                    // Isolate this photo — log and continue with the rest of the batch
                    TdlibManager.addLog("Worker: exception uploading '${photo.name}' — skipping. Error: ${e.message}")
                    e.printStackTrace()
                    failedCount++
                }

                // Add 5-second throttle delay between uploads to prevent rate limiting (FLOOD_WAIT) on Telegram's servers
                TdlibManager.addLog("Worker: waiting 5 seconds before next upload to prevent server flooding...")
                delay(5000)
            }

            TdlibManager.addLog("Worker: backup run completed. Uploaded: $uploadedCount, Failed: $failedCount, Skipped: $skippedCount.")

            return Result.success()

        } catch (e: Exception) {
            TdlibManager.addLog("Worker: Execution failed with error: ${e.message}")
            return Result.failure()
        } finally {
            uploadMutex.unlock()
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
