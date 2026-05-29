package com.example.tguploader.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.tguploader.storage.MediaStoreScanner
import com.example.tguploader.storage.PreferencesManager
import com.example.tguploader.storage.UploadDatabase
import com.example.tguploader.storage.UploadEntity
import com.example.tguploader.telegram.TdlibManager
import com.example.tguploader.telegram.UploadManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

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

        // 3. Initialize TDLib safely
        TdlibManager.initialize(applicationContext)

        // 4. Wait for authorization state to become Ready (loaded from local session files)
        val isReady = withTimeoutOrNull(15000) {
            TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
        }

        if (isReady == null) {
            TdlibManager.addLog("Worker: Telegram client not authenticated or timed out. Retrying later.")
            return Result.retry()
        }

        // 5. Scan all photos on the device
        val photos = MediaStoreScanner.scan(applicationContext)
        if (photos.isEmpty()) {
            TdlibManager.addLog("Worker: No photos found on device to sync.")
            return Result.success()
        }

        // 6. Open database
        val db = UploadDatabase.getDatabase(applicationContext)
        val dao = db.dao()

        var uploadedCount = 0
        val isHd = PreferencesManager.isHdMode(applicationContext)

        // 7. Loop and upload new photos sequentially (one-by-one)
        for (photo in photos) {
            // Re-verify backup toggle mid-run in case it was switched off during active sequence
            if (!PreferencesManager.isBackupActive(applicationContext)) {
                TdlibManager.addLog("Worker: Backup was disabled during execution. Aborting active sync.")
                break
            }

            val fileKey = photo.uri
            val existing = dao.find(fileKey)

            if (existing == null) {
                val modeStr = if (isHd) "HD" else "standard quality"
                TdlibManager.addLog("Worker: backing up photo '${photo.name}' in $modeStr...")
                
                val uploadResult = UploadManager.uploadPhoto(applicationContext, photo, chatId, isHd)

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
            }
        }

        TdlibManager.addLog("Worker: backup run completed. Newly backed up: $uploadedCount photos.")
        return Result.success()
    }
}
