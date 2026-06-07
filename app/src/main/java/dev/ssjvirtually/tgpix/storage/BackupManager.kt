package dev.ssjvirtually.tgpix.storage

import android.content.Context
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import java.util.concurrent.TimeUnit
import dev.ssjvirtually.tgpix.worker.DatabaseBackupWorker
import androidx.room.withTransaction
import dev.ssjvirtually.tgpix.storage.AlbumEntity
import dev.ssjvirtually.tgpix.storage.AlbumPhotoEntity

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
        val customId = PreferencesManager.getDbChatId(context)
        if (customId != 0L) return customId
        
        val myId = TdlibManager.myUserId
        if (myId != 0L) return myId
        
        return PreferencesManager.getChatId(context)
    }

    suspend fun resolveBackupChatId(context: Context): Long {
        val customId = PreferencesManager.getDbChatId(context)
        if (customId != 0L) {
            // Ensure TDLib has loaded/cached this chat before we try to send to it.
            // On fresh sessions, GetChat may return "Chat not found" until TDLib syncs.
            // We retry a few times with a short delay to give TDLib time to populate.
            ensureChatLoaded(customId)
            return customId
        }
        
        val myId = resolveMyUserId()
        val targetId = if (myId != 0L) myId else PreferencesManager.getChatId(context)
        
        if (targetId != 0L) {
            ensureChatLoaded(targetId)
        }
        
        return targetId
    }

    /**
     * Ensures TDLib has loaded the given chat into its local cache.
     * On fresh sessions, channels/supergroups are not immediately known.
     * Retries GetChat up to 5 times with 1-second delays before giving up.
     */
    private suspend fun ensureChatLoaded(chatId: Long) {
        repeat(5) { attempt ->
            try {
                val result = suspendCancellableCoroutine<TdApi.Object> { cont ->
                    val request = if (chatId > 0L) {
                        TdApi.CreatePrivateChat(chatId, false)
                    } else {
                        TdApi.GetChat(chatId)
                    }
                    TdlibManager.getClient().send(request) { res ->
                        cont.resume(res)
                    }
                }
                if (result is TdApi.Chat) {
                    return // Success — chat is loaded
                }
                if (result is TdApi.Error) {
                    TdlibManager.addLog("ensureChatLoaded: attempt ${attempt + 1} for chat $chatId failed: ${result.message}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (attempt < 4) delay(1500) // Wait before retrying
        }
    }

    suspend fun resolveMyUserId(): Long {
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
        return myId
    }

    fun scheduleBackup(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DatabaseBackupWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            "db_backup",
            ExistingWorkPolicy.KEEP,
            request
        )
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
                val dbFile = context.getDatabasePath("upload_database")
                if (!dbFile.exists()) {
                    isBackupRunning = false
                    return@withContext
                }

                // 1. Flush Room's WAL logs and truncate the WAL file size OUTSIDE the transaction
                val checkpointSuccess = try {
                    val cursor = db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
                    cursor.use { c ->
                        if (c.moveToFirst()) {
                            val busy = c.getInt(0)
                            val log = c.getInt(1)
                            val checkpointed = c.getInt(2)
                            if (busy != 0 || log != checkpointed) {
                                TdlibManager.addLog("WAL checkpoint incomplete: busy=$busy log=$log checkpointed=$checkpointed")
                                false
                            } else {
                                true
                            }
                        } else {
                            false
                        }
                    }
                } catch (e: Exception) {
                    TdlibManager.addLog("Failed to execute WAL checkpoint: ${e.message}")
                    e.printStackTrace()
                    false
                }

                if (!checkpointSuccess) {
                    isBackupRunning = false
                    return@withContext
                }

                // 2. Safely lock the database and perform a copy while in a Room transaction
                val transactionResult = try {
                    db.withTransaction {
                        val currentCount = db.cloudDao().getRecordCountDirect()
                        val lastCount = PreferencesManager.getLastBackupRecordCount(context)

                        // If database is empty but we previously had backups, abort to protect remote
                        if (currentCount == 0 && lastCount > 0) {
                            TdlibManager.addLog("Backup safety check failed: Local DB is empty, aborting upload to protect remote backup.")
                            null
                        } else {
                            // Use filesDir (real /data/data/ path) instead of cacheDir (/data/user/0/ symlink).
                            // TDLib calls realpath() on InputFileLocal paths and rejects symlinked paths.
                            val tempFile = File(context.filesDir, "tgpix_backup.db")
                            // Perform binary copy while exclusive write lock is held by Room transaction
                            dbFile.copyTo(tempFile, overwrite = true)
                            Pair(tempFile, currentCount)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

                if (transactionResult == null) {
                    isBackupRunning = false
                    return@withContext
                }

                val (tempBackupFile, currentCount) = transactionResult

                if (!tempBackupFile.exists() || tempBackupFile.length() == 0L) {
                    isBackupRunning = false
                    return@withContext
                }
                
                // 5. Compute integrity checksum and upload document message to Telegram (performed outside database transaction)
                val sha256 = computeSha256(tempBackupFile)
                
                // Determine if we need to do daily master backup
                val now = System.currentTimeMillis()
                val lastDailyBackupTime = PreferencesManager.getLastDailyBackupTime(context)
                val needMasterBackup = now - lastDailyBackupTime >= 24 * 60 * 60 * 1000L || lastDailyBackupTime == 0L
                
                val tempMasterFile = if (needMasterBackup) {
                    // Use filesDir (real path) to avoid TDLib's realpath() rejecting symlinked cacheDir paths
                    val masterFile = File(context.filesDir, "tgpix_master_backup.db")
                    try {
                        tempBackupFile.copyTo(masterFile, overwrite = true)
                        masterFile
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } else null
                val dbVersion = UploadDatabase.DATABASE_VERSION
                val uploadResult = uploadFile(
                    tempBackupFile,
                    targetChatId,
                    "TGPix SQLite Database Backup #tgpix_backup v$dbVersion sha256:$sha256 records:$currentCount"
                )
                if (uploadResult is TdApi.Message) {
                    val newMsgId = uploadResult.id
                    
                    // Maintain a rolling list of the last 3 backup message IDs
                    val backupIds = PreferencesManager.getBackupMessageIds(context).toMutableList()
                    backupIds.add(newMsgId)
                    
                    if (backupIds.size > 3) {
                        // Prune oldest backups, keeping only the 3 newest
                        while (backupIds.size > 3) {
                            val oldestMsgId = backupIds.removeAt(0)
                            if (oldestMsgId != 0L) {
                                TdlibManager.getClient().send(TdApi.DeleteMessages(targetChatId, longArrayOf(oldestMsgId), true)) { deleteResult ->
                                    if (deleteResult is TdApi.Ok) {
                                        TdlibManager.addLog("Old SQLite backup message ($oldestMsgId) pruned successfully from Telegram.")
                                    } else if (deleteResult is TdApi.Error) {
                                        TdlibManager.addLog("Failed to prune old SQLite backup message ($oldestMsgId): ${deleteResult.message}")
                                    }
                                }
                            }
                        }
                    }
                    
                    PreferencesManager.saveBackupMessageIds(context, backupIds)
                    PreferencesManager.setLastBackupMessageId(context, newMsgId)
                    PreferencesManager.setLastBackupRecordCount(context, currentCount)
                    
                    TdlibManager.addLog("SQLite backup updated successfully to Message ID $newMsgId in chat $targetChatId (Records: $currentCount).")
 
                    // 6. Daily master backup strategy
                    if (tempMasterFile != null && tempMasterFile.exists()) {
                        performDailyMasterBackup(context, tempMasterFile, sha256, currentCount)
                    }
                } else if (uploadResult is TdApi.Error) {
                    TdlibManager.addLog("Database backup upload failed: ${uploadResult.message}")
                    if (tempMasterFile != null && tempMasterFile.exists()) {
                        tempMasterFile.delete()
                    }
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

    private suspend fun performDailyMasterBackup(context: Context, tempMasterFile: File, sha256: String, recordsCount: Int) {
        val myUserId = resolveMyUserId()
        if (myUserId == 0L) {
            TdlibManager.addLog("Daily master backup aborted: Unable to resolve myUserId")
            if (tempMasterFile.exists()) {
                tempMasterFile.delete()
            }
            return
        }

        if (!tempMasterFile.exists()) {
            TdlibManager.addLog("Daily master backup aborted: Master backup file does not exist")
            return
        }

        // Ensure Saved Messages chat is initialized
        try {
            suspendCancellableCoroutine<TdApi.Object> { cont ->
                TdlibManager.getClient().send(TdApi.CreatePrivateChat(myUserId, false)) { res ->
                    cont.resume(res)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            TdlibManager.addLog("Uploading daily master backup (#tgpix_master_backup) to Saved Messages (Chat ID: $myUserId)...")
            val dbVersion = UploadDatabase.DATABASE_VERSION
            val caption = "TGPix SQLite Master Database Backup #tgpix_master_backup v$dbVersion sha256:$sha256 records:$recordsCount"
            val uploadResult = uploadFile(tempMasterFile, myUserId, caption)
            if (uploadResult is TdApi.Message) {
                val newMasterMsgId = uploadResult.id
                val oldMasterMsgId = PreferencesManager.getLastMasterBackupMessageId(context)

                // Delete old master message from Saved Messages
                if (oldMasterMsgId != 0L) {
                    TdlibManager.getClient().send(TdApi.DeleteMessages(myUserId, longArrayOf(oldMasterMsgId), true)) { deleteResult ->
                        if (deleteResult is TdApi.Ok) {
                            TdlibManager.addLog("Old daily master backup message ($oldMasterMsgId) deleted from Saved Messages.")
                        } else if (deleteResult is TdApi.Error) {
                            TdlibManager.addLog("Failed to delete old daily master backup message ($oldMasterMsgId): ${deleteResult.message}")
                        }
                    }
                }

                PreferencesManager.setLastMasterBackupMessageId(context, newMasterMsgId)
                PreferencesManager.setLastDailyBackupTime(context, System.currentTimeMillis())
                TdlibManager.addLog("Daily master database backup completed successfully (Msg ID: $newMasterMsgId).")
            } else if (uploadResult is TdApi.Error) {
                TdlibManager.addLog("Daily master database backup upload failed: ${uploadResult.message}")
            }
        } finally {
            if (tempMasterFile.exists()) {
                tempMasterFile.delete()
            }
        }
    }

    private suspend fun tryRestoreFromChatAndTag(context: Context, chatId: Long, tag: String): Boolean {
        if (chatId == 0L) return false
        
        try {
            suspendCancellableCoroutine<TdApi.Object> { cont ->
                val request = if (chatId > 0L) {
                    TdApi.CreatePrivateChat(chatId, false)
                } else {
                    TdApi.GetChat(chatId)
                }
                TdlibManager.getClient().send(request) { res ->
                    cont.resume(res)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        TdlibManager.addLog("Searching for remote SQLite backups in chat $chatId with tag $tag...")
        val searchQuery = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            query = tag
            filter = TdApi.SearchMessagesFilterDocument()
            limit = 5
        }
        
        val searchResult = suspendCancellableCoroutine<TdApi.Object> { continuation ->
            TdlibManager.getClient().send(searchQuery) { result ->
                continuation.resume(result)
            }
        }
        
        if (searchResult is TdApi.FoundChatMessages && searchResult.messages.isNotEmpty()) {
            TdlibManager.addLog("Found ${searchResult.messages.size} remote SQLite backup messages for tag $tag. Trying to restore...")
            for (message in searchResult.messages) {
                val docContent = message.content as? TdApi.MessageDocument
                val document = docContent?.document
                if (document != null) {
                    val captionText = docContent.caption?.text ?: ""
                    val backupVersion = captionText.substringAfter(" v", "").trim().substringBefore(" ").toIntOrNull()
                    if (backupVersion != null && backupVersion > UploadDatabase.DATABASE_VERSION) {
                        TdlibManager.addLog("Skipping backup (Msg ID: ${message.id}): backup version ($backupVersion) is newer than app database version (${UploadDatabase.DATABASE_VERSION}). Please update the app.")
                        continue
                    }

                    TdlibManager.addLog("Attempting to download backup (Msg ID: ${message.id}, File ID: ${document.document.id})...")
                    
                    val downloadedFile = downloadFile(document.document.id)
                    if (downloadedFile != null && downloadedFile.exists()) {
                        // Verify integrity via SHA-256 checksum
                        val expectedHash = captionText.substringAfter("sha256:", "").trim().substringBefore(" ")
                        if (expectedHash.isNotEmpty()) {
                            val actualHash = computeSha256(downloadedFile)
                            if (actualHash != expectedHash) {
                                TdlibManager.addLog("Backup (Msg ID: ${message.id}) integrity check FAILED. Expected: $expectedHash, Got: $actualHash. Trying next available backup...")
                                continue
                            }
                            TdlibManager.addLog("Backup integrity verified (SHA-256 match).")
                        }

                        TdlibManager.addLog("Backup downloaded successfully. Restoring local database from Msg ID: ${message.id}...")
                        
                        // Close current Room database so we can overwrite its files
                        UploadDatabase.closeDatabase()
                        
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
                        newDb.cloudDao().clearAllCachedPaths()
                        val restoredCount = newDb.cloudDao().getRecordCountDirect()
                        PreferencesManager.setLastBackupRecordCount(context, restoredCount)
                        
                        TdlibManager.addLog("Local database successfully restored from remote backup (Restored records: $restoredCount).")
                        return true
                    } else {
                        TdlibManager.addLog("Failed to download backup file (Msg ID: ${message.id}). Trying next available backup...")
                    }
                }
            }
        }
        return false
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
            val myUserId = resolveMyUserId()
            
            // Priority 1: Search Backup Channel first for the newest #tgpix_backup database file
            var restored = false
            if (targetChatId != 0L) {
                restored = tryRestoreFromChatAndTag(context, targetChatId, "#tgpix_backup")
            }
            
            // Priority 2: Scan Saved Messages for the #tgpix_master_backup file
            if (!restored && myUserId != 0L) {
                restored = tryRestoreFromChatAndTag(context, myUserId, "#tgpix_master_backup")
            }
            
            return@withContext restored
        }
    }

    suspend fun restoreDatabaseForce(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val targetChatId = resolveBackupChatId(context)
            val myUserId = resolveMyUserId()
            
            // Priority 1: Search Backup Channel first for the newest #tgpix_backup database file
            var restored = false
            if (targetChatId != 0L) {
                restored = tryRestoreFromChatAndTag(context, targetChatId, "#tgpix_backup")
            }
            
            // Priority 2: Scan Saved Messages for the #tgpix_master_backup file
            if (!restored && myUserId != 0L) {
                restored = tryRestoreFromChatAndTag(context, myUserId, "#tgpix_master_backup")
            }
            
            return@withContext restored
        }
    }

    suspend fun reconstructAlbumsFromBackupChannel(context: Context) {
        val targetChatId = resolveBackupChatId(context)
        if (targetChatId == 0L) return
        
        TdlibManager.addLog("Reconstructing albums using #tgpix_album manifests from Backup Channel (Chat ID: $targetChatId)...")
        
        // Ensure chat is created/loaded
        try {
            suspendCancellableCoroutine<TdApi.Object> { cont ->
                val request = if (targetChatId > 0L) {
                    TdApi.CreatePrivateChat(targetChatId, false)
                } else {
                    TdApi.GetChat(targetChatId)
                }
                TdlibManager.getClient().send(request) { res ->
                    cont.resume(res)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val searchQuery = TdApi.SearchChatMessages().apply {
            this.chatId = targetChatId
            query = "#tgpix_album"
            filter = TdApi.SearchMessagesFilterDocument()
            limit = 100
        }
        
        val searchResult = suspendCancellableCoroutine<TdApi.Object> { continuation ->
            TdlibManager.getClient().send(searchQuery) { result ->
                continuation.resume(result)
            }
        }
        
        if (searchResult is TdApi.FoundChatMessages && searchResult.messages.isNotEmpty()) {
            TdlibManager.addLog("Found ${searchResult.messages.size} album manifests in Backup Channel. Reconstructing...")
            for (message in searchResult.messages) {
                try {
                    reconstructAlbum(context, message)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
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

    private fun serializeManifest(
        albumUuid: String,
        name: String,
        createdAt: Long,
        updatedAt: Long,
        photoMessageIds: List<Long>,
        coverMessageId: Long?
    ): String {
        val jsonObj = org.json.JSONObject()
        jsonObj.put("albumId", albumUuid)
        jsonObj.put("name", name)
        jsonObj.put("createdAt", createdAt)
        jsonObj.put("updatedAt", updatedAt)
        
        val jsonArray = org.json.JSONArray()
        photoMessageIds.forEach { jsonArray.put(it) }
        jsonObj.put("photoMessageIds", jsonArray)
        
        if (coverMessageId != null) {
            jsonObj.put("coverMessageId", coverMessageId)
        } else {
            jsonObj.put("coverMessageId", org.json.JSONObject.NULL)
        }
        
        return jsonObj.toString()
    }

    suspend fun onAlbumUpdated(context: Context, albumId: Long) {
        val database = UploadDatabase.getDatabase(context)
        val albumDao = database.albumDao()
        val cloudDao = database.cloudDao()

        val album = albumDao.getAlbumById(albumId) ?: return
        val targetChatId = resolveBackupChatId(context)
        if (targetChatId == 0L) return

        // 1. Gather all photos in this album and resolve their Telegram message IDs
        val albumPhotos = albumDao.getAlbumPhotosDirect(albumId)
        val photoMessageIds = mutableListOf<Long>()
        albumPhotos.forEach { entity ->
            val uri = entity.photoUri
            val idFromFilename = if (uri.startsWith("photo_") && uri.endsWith(".jpg")) {
                uri.substringAfter("photo_").substringBefore(".jpg").toLongOrNull()
            } else null

            if (idFromFilename != null) {
                photoMessageIds.add(idFromFilename)
            } else {
                val cloudPhoto = cloudDao.findByFileName(uri)
                if (cloudPhoto != null) {
                    photoMessageIds.add(cloudPhoto.messageId)
                }
            }
        }

        // 2. Serialize manifest to JSON
        val manifestJson = serializeManifest(
            albumUuid = album.uuid,
            name = album.name,
            createdAt = album.createdAt,
            updatedAt = System.currentTimeMillis(),
            photoMessageIds = photoMessageIds,
            coverMessageId = album.coverPhotoMessageId
        )

        // 3. Write JSON to temporary file
        val tempFile = File(context.cacheDir, "album_${album.uuid}.json")
        try {
            tempFile.writeText(manifestJson)

            // 4. Upload manifest document to Telegram
            val captionText = "#tgpix_album albumId:${album.uuid}"
            val uploadResult = uploadFile(tempFile, targetChatId, captionText)
            if (uploadResult is TdApi.Message) {
                val newMsgId = uploadResult.id

                // 5. Delete old manifest message if it exists
                val oldMsgId = album.telegramMessageId
                if (oldMsgId != null && oldMsgId != 0L) {
                    suspendCancellableCoroutine<TdApi.Object> { cont ->
                        TdlibManager.getClient().send(TdApi.DeleteMessages(targetChatId, longArrayOf(oldMsgId), true)) { res ->
                            cont.resume(res)
                        }
                    }
                }

                // 6. Update database with new message ID
                albumDao.updateTelegramMessageId(albumId, newMsgId)
                TdlibManager.addLog("Album '${album.name}' manifest backup updated to Message ID $newMsgId")
            } else {
                val errMsg = if (uploadResult is TdApi.Error) uploadResult.message else "Unknown error"
                TdlibManager.addLog("Failed to upload album manifest: $errMsg")
            }
        } catch (e: Exception) {
            TdlibManager.addLog("Exception in onAlbumUpdated: ${e.message}")
            e.printStackTrace()
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    suspend fun onAlbumDeleted(context: Context, telegramMessageId: Long?) {
        if (telegramMessageId == null || telegramMessageId == 0L) return
        val targetChatId = resolveBackupChatId(context)
        if (targetChatId == 0L) return
        try {
            suspendCancellableCoroutine<TdApi.Object> { cont ->
                TdlibManager.getClient().send(TdApi.DeleteMessages(targetChatId, longArrayOf(telegramMessageId), true)) { res ->
                    cont.resume(res)
                }
            }
            TdlibManager.addLog("Album manifest message ID $telegramMessageId deleted from Telegram.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun reconstructAlbum(context: Context, message: TdApi.Message) {
        val documentContent = message.content as? TdApi.MessageDocument ?: return
        val doc = documentContent.document
        
        TdlibManager.addLog("Downloading album manifest (Msg ID: ${message.id}, File ID: ${doc.document.id})...")
        val downloadedFile = downloadFile(doc.document.id)
        if (downloadedFile != null && downloadedFile.exists()) {
            try {
                val jsonStr = downloadedFile.readText()
                val jsonObj = org.json.JSONObject(jsonStr)
                
                val albumUuid = jsonObj.getString("albumId")
                val name = jsonObj.getString("name")
                val createdAt = jsonObj.getLong("createdAt")
                val coverMessageId = if (jsonObj.has("coverMessageId") && !jsonObj.isNull("coverMessageId")) {
                    jsonObj.getLong("coverMessageId")
                } else null
                
                val photoMessageIdsArray = jsonObj.getJSONArray("photoMessageIds")
                val photoMessageIds = mutableListOf<Long>()
                for (i in 0 until photoMessageIdsArray.length()) {
                    photoMessageIds.add(photoMessageIdsArray.getLong(i))
                }
                
                val database = UploadDatabase.getDatabase(context)
                val albumDao = database.albumDao()
                
                // Check if this album already exists locally by UUID
                val existingAlbum = albumDao.findByUuid(albumUuid)
                
                if (existingAlbum != null && existingAlbum.telegramMessageId == message.id) {
                    // Already processed this exact version, skip
                    return
                }
                
                val albumEntity = AlbumEntity(
                    id = existingAlbum?.id ?: 0,
                    uuid = albumUuid,
                    name = name,
                    createdAt = createdAt,
                    telegramMessageId = message.id,
                    coverPhotoMessageId = coverMessageId
                )
                
                val albumId = albumDao.insertAlbum(albumEntity)
                
                // Clear existing album photos locally to overwrite with manifest
                albumDao.deleteAlbumPhotos(albumId)
                
                // Insert photo relationships
                val albumPhotos = mutableListOf<AlbumPhotoEntity>()
                val cloudDao = database.cloudDao()
                photoMessageIds.forEach { msgId ->
                    val cloudPhoto = cloudDao.findByMessageId(msgId)
                    val photoUri = cloudPhoto?.fileName ?: "photo_${msgId}.jpg"
                    albumPhotos.add(AlbumPhotoEntity(albumId = albumId, photoUri = photoUri))
                }
                
                if (albumPhotos.isNotEmpty()) {
                    albumDao.insertAlbumPhotos(albumPhotos)
                }
                
                TdlibManager.addLog("Reconstructed album '$name' (UUID: $albumUuid, Photos: ${albumPhotos.size}) from Telegram message ID ${message.id}")
            } catch (e: Exception) {
                TdlibManager.addLog("Failed to reconstruct album from message ID ${message.id}: ${e.message}")
                e.printStackTrace()
            } finally {
                if (downloadedFile.exists()) {
                    downloadedFile.delete()
                }
            }
        }
    }

    private suspend fun uploadFile(file: File, chatId: Long, captionText: String): TdApi.Object {
        return suspendCancellableCoroutine { continuation ->
            // Use canonicalPath to resolve symlinks — TDLib's realpath() rejects /data/user/0/ symlinks.
            // canonicalPath resolves to the real /data/data/ path on all Android versions.
            val resolvedPath = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
            val inputFile = TdApi.InputFileLocal(resolvedPath)
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
                    TdlibManager.registerPendingUpload(result.id) { res ->
                        continuation.resume(res)
                    }
                } else if (result is TdApi.Error) {
                    continuation.resume(result)
                } else {
                    continuation.resume(TdApi.Error(500, "Unexpected TDLib response"))
                }
            }
        }
    }
}
