package com.example.tguploader.telegram

import android.content.Context
import android.net.Uri
import com.example.tguploader.storage.LocalPhoto
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

object UploadManager {
    suspend fun uploadPhoto(
        context: Context,
        photo: LocalPhoto,
        chatId: Long
    ): TdApi.Object {
        val fileName = photo.name
        val tempFile = File(context.cacheDir, fileName)
        
        try {
            return suspendCancellableCoroutine { continuation ->
                try {
                    val uri = Uri.parse(photo.uri)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        continuation.resume(TdApi.Error(400, "Failed to copy photo to local cache"))
                        return@suspendCancellableCoroutine
                    }

                    val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
                    
                    val inputPhoto = TdApi.InputMessagePhoto().apply {
                        this.photo = inputFile
                        caption = TdApi.FormattedText(fileName, emptyArray())
                    }

                    val request = TdApi.SendMessage().apply {
                        this.chatId = chatId
                        inputMessageContent = inputPhoto
                    }

                    TdlibManager.getClient().send(request) { result ->
                        if (result is TdApi.Message) {
                            // Register continuation to resume when UpdateMessageSendSucceeded fires
                            TdlibManager.pendingUploads[result.id] = continuation
                            TdlibManager.addLog("Upload queued for '${fileName}' (Msg ID: ${result.id}). Sending to Telegram...")
                        } else if (result is TdApi.Error) {
                            continuation.resume(result)
                        } else {
                            continuation.resume(TdApi.Error(500, "Unexpected response from TDLib: ${result::class.java.simpleName}"))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    continuation.resume(TdApi.Error(500, e.message ?: "Unknown upload initialization error"))
                }
            }
        } finally {
            // Clean up the temp file from the cache directory immediately when the suspension finishes
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                    TdlibManager.addLog("Cleaned up temp upload file: ${tempFile.name}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
