package com.example.tguploader.worker

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.tguploader.storage.FolderScanner
import com.example.tguploader.storage.PreferencesManager
import com.example.tguploader.storage.UploadDatabase
import com.example.tguploader.storage.UploadEntity
import com.example.tguploader.telegram.TdlibManager
import com.example.tguploader.telegram.UploadManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val applicationContext = applicationContext
        
        // 1. Verify that we have a configured folder and target chat
        val folderUri = PreferencesManager.getFolder(applicationContext) ?: return Result.failure()
        val chatId = PreferencesManager.getChatId(applicationContext)
        if (chatId == 0L) return Result.failure()

        // 2. Initialize TDLib safely
        TdlibManager.initialize(applicationContext)

        // 3. Wait for authorization state to become Ready (loaded from local session files)
        val isReady = withTimeoutOrNull(15000) {
            TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
        }

        if (isReady == null) {
            // TDLib was not ready in 15 seconds, retry later
            return Result.retry()
        }

        // 4. Scan files in the target folder
        val files = FolderScanner.scan(applicationContext)
        TdlibManager.addLog("Worker: scanned ${files.size} audio files to sync.")
        if (files.isEmpty()) {
            return Result.success()
        }

        // 5. Open database
        val db = UploadDatabase.getDatabase(applicationContext)
        val dao = db.dao()

        var uploadedCount = 0

        // 6. Loop and upload new files sequentially
        for (file in files) {
            val fileKey = file.uri.toString()
            val existing = dao.find(fileKey)

            if (existing == null) {
                val fileName = file.name ?: "Unknown"
                TdlibManager.addLog("Worker: starting upload for '$fileName' to chat $chatId...")
                
                val uploadResult = UploadManager.uploadFile(applicationContext, file, chatId)

                // If upload is successful (returns a Message object)
                if (uploadResult is TdApi.Message) {
                    TdlibManager.addLog("Worker: successfully uploaded '$fileName'! Msg ID: ${uploadResult.id}")
                    dao.insert(
                        UploadEntity(
                            path = fileKey,
                            uploadedAt = System.currentTimeMillis()
                        )
                    )
                    uploadedCount++
                } else if (uploadResult is TdApi.Error) {
                    TdlibManager.addLog("Worker: failed to upload '$fileName': [code ${uploadResult.code}] ${uploadResult.message}")
                } else {
                    TdlibManager.addLog("Worker: upload returned unexpected response: ${uploadResult::class.java.simpleName}")
                }
            } else {
                val fileName = file.name ?: "Unknown"
                TdlibManager.addLog("Worker: '$fileName' was already synced previously, skipping.")
            }
        }

        return Result.success()
    }
}
