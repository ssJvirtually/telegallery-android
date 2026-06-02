package com.example.tguploader.storage

import android.content.Context
import com.example.tguploader.telegram.TdlibManager
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume

object BackupManager {

    private var isBackupRunning = false

    private fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getBackupChatId(context: Context): Long {
        val customId = context.getSharedPreferences("TeleGalleryPrefs", Context.MODE_PRIVATE)
            .getLong("db_chat_id", 0L)
        if (customId != 0L) return customId
        
        val myId = TdlibManager.myUserId
        if (myId != 0L) return myId
        
        return PreferencesManager.getChatId(context)
    }

    suspend fun resolveBackupChatId(context: Context): Long {
        val customId = context.getSharedPreferences("TeleGalleryPrefs", Context.MODE_PRIVATE)
            .getLong("db_chat_id", 0L)
        if (customId != 0L) return customId
        
        var myId = TdlibManager.myUserId
        if (myId == 0L) {
            try {
                val user = suspendCancellableCoroutine<TdApi.User?> { cont ->
                    TdlibManager.getClient().send(TdApi.GetMe()) { res ->
                        cont.resume(res as? TdApi.User)
                    }
                }
                if (user != null) {
                    TdlibManager.myUserId = user.id
                    myId = user.id
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (myId != 0L) return myId
        return PreferencesManager.getChatId(context)
    }

    suspend fun backupDatabase(context: Context) {
        if (isBackupRunning) return
        isBackupRunning = true
        
        try {
            val targetChatId = resolveBackupChatId(context)
            if (targetChatId == 0L) {
                isBackupRunning = false
                return
            }
            withContext(Dispatchers.IO) {
                val db = UploadDatabase.getDatabase(context)
                
                // 1. Flush Room's WAL logs to the main database file
                try {
                    db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // 2. Locate the database file
                val dbFile = context.getDatabasePath("upload_database")
                if (!dbFile.exists()) {
                    isBackupRunning = false
                    return@withContext
                }
                
                // 3. Verify record count - safety checks
                val currentCount = db.cloudDao().getRecordCountDirect()
                val lastCount = PreferencesManager.getLastBackupRecordCount(context)
                
                // If database is empty but we previously had backups, abort to protect remote
                if (currentCount == 0 && lastCount > 0) {
                    TdlibManager.addLog("Backup safety check failed: Local DB is empty, aborting upload to protect remote backup.")
                    isBackupRunning = false
                    return@withContext
                }
                
                // 4. Create local temporary copy in cache
                val tempBackupFile = File(context.cacheDir, "telegallery_backup.db")
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(tempBackupFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                if (!tempBackupFile.exists() || tempBackupFile.length() == 0L) {
                    isBackupRunning = false
                    return@withContext
                }
                
                // 5. Compute integrity checksum and upload document message to Telegram
                val sha256 = computeSha256(tempBackupFile)
                val uploadResult = uploadFile(tempBackupFile, targetChatId, "TeleGallery SQLite Database Backup #telegallery_backup sha256:$sha256")
                if (uploadResult is TdApi.Message) {
                    val newMsgId = uploadResult.id
                    val oldMsgId = PreferencesManager.getLastBackupMessageId(context)
                    
                    PreferencesManager.setLastBackupMessageId(context, newMsgId)
                    PreferencesManager.setLastBackupRecordCount(context, currentCount)
                    
                    // 6. Delete the old backup message from Telegram history to keep it clean
                    if (oldMsgId != 0L) {
                        TdlibManager.getClient().send(TdApi.DeleteMessages(targetChatId, longArrayOf(oldMsgId), true)) { deleteResult ->
                            if (deleteResult is TdApi.Ok) {
                                TdlibManager.addLog("Old SQLite backup message ($oldMsgId) pruned successfully from Telegram.")
                            }
                        }
                    }
                    TdlibManager.addLog("SQLite backup updated successfully to Message ID $newMsgId in chat $targetChatId (Records: $currentCount).")
                } else if (uploadResult is TdApi.Error) {
                    TdlibManager.addLog("Database backup upload failed: ${uploadResult.message}")
                }
                
                // Clean up local temp file
                if (tempBackupFile.exists()) {
                    tempBackupFile.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isBackupRunning = false
        }
    }

    suspend fun restoreDatabase(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val db = UploadDatabase.getDatabase(context)
            
            // If the local database is not empty, no need to restore from backup
            val currentCount = db.cloudDao().getRecordCountDirect()
            if (currentCount > 0) {
                return@withContext false
            }
            
            val targetChatId = resolveBackupChatId(context)
            if (targetChatId == 0L) {
                return@withContext false
            }
            
            TdlibManager.addLog("Local database is empty. Scanning for remote SQLite backup in chat $targetChatId...")
            
            // 1. Search for messages in target chat tagged with #telegallery_backup
            val searchQuery = TdApi.SearchChatMessages().apply {
                this.chatId = targetChatId
                query = "#telegallery_backup"
                filter = TdApi.SearchMessagesFilterDocument()
                limit = 1
            }
            
            val searchResult = suspendCancellableCoroutine<TdApi.Object> { continuation ->
                TdlibManager.getClient().send(searchQuery) { result ->
                    continuation.resume(result)
                }
            }
            
            if (searchResult is TdApi.FoundChatMessages && searchResult.messages.isNotEmpty()) {
                val message = searchResult.messages[0]
                val docContent = message.content as? TdApi.MessageDocument
                val document = docContent?.document
                if (document != null) {
                    TdlibManager.addLog("Found remote SQLite backup. Downloading backup file (File ID: ${document.document.id})...")
                    
                    // 2. Download the document file from Telegram
                    val downloadedFile = downloadFile(document.document.id)
                    if (downloadedFile != null && downloadedFile.exists()) {
                        // Verify integrity via SHA-256 checksum
                        val captionText = docContent?.caption?.text ?: ""
                        val expectedHash = captionText.substringAfter("sha256:", "").trim()
                        if (expectedHash.isNotEmpty()) {
                            val actualHash = computeSha256(downloadedFile)
                            if (actualHash != expectedHash) {
                                TdlibManager.addLog("Backup integrity check FAILED. Expected: $expectedHash, Got: $actualHash. Aborting restore.")
                                return@withContext false
                            }
                            TdlibManager.addLog("Backup integrity verified (SHA-256 match).")
                        }

                        TdlibManager.addLog("Backup downloaded successfully. Restoring local database...")
                        
                        // Close current Room database so we can overwrite its files
                        db.close()
                        
                        val dbFile = context.getDatabasePath("upload_database")
                        
                        // Overwrite target database files
                        FileInputStream(downloadedFile).use { input ->
                            FileOutputStream(dbFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Also delete any WAL files to prevent conflicts with old WAL states
                        context.getDatabasePath("upload_database-wal").delete()
                        context.getDatabasePath("upload_database-shm").delete()
                        
                        PreferencesManager.setLastBackupMessageId(context, message.id)
                        
                        // Re-initialize/open database to trigger updates
                        val newDb = UploadDatabase.getDatabase(context)
                        val restoredCount = newDb.cloudDao().getRecordCountDirect()
                        PreferencesManager.setLastBackupRecordCount(context, restoredCount)
                        
                        TdlibManager.addLog("Local database successfully restored from remote backup (Restored records: $restoredCount).")
                        return@withContext true
                    }
                }
            }
            TdlibManager.addLog("No remote SQLite backup found. Gracefully falling back to full backward timeline scan.")
            return@withContext false
        }
    }

    suspend fun restoreDatabaseForce(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val db = UploadDatabase.getDatabase(context)
            val targetChatId = resolveBackupChatId(context)
            if (targetChatId == 0L) {
                return@withContext false
            }
            
            TdlibManager.addLog("Force restoring database. Scanning for remote SQLite backup in chat $targetChatId...")
            
            // 1. Search for messages in target chat tagged with #telegallery_backup
            val searchQuery = TdApi.SearchChatMessages().apply {
                this.chatId = targetChatId
                query = "#telegallery_backup"
                filter = TdApi.SearchMessagesFilterDocument()
                limit = 1
            }
            
            val searchResult = suspendCancellableCoroutine<TdApi.Object> { continuation ->
                TdlibManager.getClient().send(searchQuery) { result ->
                    continuation.resume(result)
                }
            }
            
            if (searchResult is TdApi.FoundChatMessages && searchResult.messages.isNotEmpty()) {
                val message = searchResult.messages[0]
                val docContent = message.content as? TdApi.MessageDocument
                val document = docContent?.document
                if (document != null) {
                    TdlibManager.addLog("Found remote SQLite backup. Downloading backup file (File ID: ${document.document.id})...")
                    
                    // 2. Download the document file from Telegram
                    val downloadedFile = downloadFile(document.document.id)
                    if (downloadedFile != null && downloadedFile.exists()) {
                        // Verify integrity via SHA-256 checksum
                        val captionText = docContent?.caption?.text ?: ""
                        val expectedHash = captionText.substringAfter("sha256:", "").trim()
                        if (expectedHash.isNotEmpty()) {
                            val actualHash = computeSha256(downloadedFile)
                            if (actualHash != expectedHash) {
                                TdlibManager.addLog("Backup integrity check FAILED. Expected: $expectedHash, Got: $actualHash. Aborting restore.")
                                return@withContext false
                            }
                            TdlibManager.addLog("Backup integrity verified (SHA-256 match).")
                        }
                        
                        TdlibManager.addLog("Backup downloaded successfully. Force restoring local database...")
                        
                        // Close current Room database so we can overwrite its files
                        db.close()
                        
                        val dbFile = context.getDatabasePath("upload_database")
                        
                        // Overwrite target database files
                        FileInputStream(downloadedFile).use { input ->
                            FileOutputStream(dbFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Also delete any WAL files to prevent conflicts with old WAL states
                        context.getDatabasePath("upload_database-wal").delete()
                        context.getDatabasePath("upload_database-shm").delete()
                        
                        PreferencesManager.setLastBackupMessageId(context, message.id)
                        
                        // Re-initialize/open database to trigger updates
                        val newDb = UploadDatabase.getDatabase(context)
                        val restoredCount = newDb.cloudDao().getRecordCountDirect()
                        PreferencesManager.setLastBackupRecordCount(context, restoredCount)
                        
                        TdlibManager.addLog("Local database successfully force-restored (Restored records: $restoredCount).")
                        return@withContext true
                    }
                }
            }
            TdlibManager.addLog("No remote SQLite backup found to force restore.")
            return@withContext false
        }
    }

    private suspend fun downloadFile(fileId: Int): File? {
        return withContext(Dispatchers.IO) {
            var file = suspendCancellableCoroutine<TdApi.File?> { cont ->
                TdlibManager.getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { res ->
                    cont.resume(res as? TdApi.File)
                }
            }
            
            if (file == null) return@withContext null
            
            // Loop until complete or timeout (say 15 seconds)
            var attempts = 0
            while (file != null && !file.local.isDownloadingCompleted && attempts < 15) {
                delay(1000)
                attempts++
                file = suspendCancellableCoroutine { cont ->
                    TdlibManager.getClient().send(TdApi.GetFile(fileId)) { res ->
                        cont.resume(res as? TdApi.File)
                    }
                }
            }
            
            if (file != null && file.local.isDownloadingCompleted) {
                File(file.local.path)
            } else null
        }
    }

    private suspend fun uploadFile(file: File, chatId: Long, captionText: String): TdApi.Object {
        return suspendCancellableCoroutine { continuation ->
            val inputFile = TdApi.InputFileLocal(file.absolutePath)
            val inputMessageContent = TdApi.InputMessageDocument().apply {
                this.document = inputFile
                this.caption = TdApi.FormattedText(captionText, emptyArray())
            }
            
            val request = TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = inputMessageContent
            }
            
            TdlibManager.getClient().send(request) { result ->
                if (result is TdApi.Message) {
                    TdlibManager.pendingUploads[result.id] = continuation
                } else if (result is TdApi.Error) {
                    continuation.resume(result)
                } else {
                    continuation.resume(TdApi.Error(500, "Unexpected TDLib response"))
                }
            }
        }
    }
}
