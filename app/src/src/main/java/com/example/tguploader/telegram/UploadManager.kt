package com.example.tguploader.telegram

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

object UploadManager {
    suspend fun uploadFile(
        context: Context,
        file: DocumentFile,
        chatId: Long
    ): TdApi.Object = suspendCancellableCoroutine { continuation ->
        try {
            val fileName = file.name ?: "recording_${System.currentTimeMillis()}.mp3"
            val tempFile = File(context.cacheDir, fileName)
            
            context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                continuation.resume(TdApi.Error(400, "Failed to copy virtual SAF file to local cache"))
                return@suspendCancellableCoroutine
            }

            val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
            
            val document = TdApi.InputMessageDocument().apply {
                this.document = inputFile
                caption = TdApi.FormattedText(fileName, emptyArray())
            }

            val request = TdApi.SendMessage().apply {
                this.chatId = chatId
                inputMessageContent = document
            }

            TdlibManager.getClient().send(request) { result ->
                if (result is TdApi.Message) {
                    // Register local ID to map to wait for the physical upload acknowledgment
                    TdlibManager.pendingUploads[result.id] = continuation
                    TdlibManager.addLog("Upload queued for '${fileName}' (local msg ID: ${result.id}). Uploading to server...")
                } else if (result is TdApi.Error) {
                    // Direct send rejection (e.g. Write access denied, slow mode, etc.)
                    try {
                        if (tempFile.exists()) tempFile.delete()
                    } catch (e: Exception) {}
                    continuation.resume(result)
                } else {
                    try {
                        if (tempFile.exists()) tempFile.delete()
                    } catch (e: Exception) {}
                    continuation.resume(TdApi.Error(500, "Unexpected response from TDLib: ${result::class.java.simpleName}"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            continuation.resume(TdApi.Error(500, e.message ?: "Unknown error"))
        }
    }
}
