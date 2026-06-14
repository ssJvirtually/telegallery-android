package dev.ssjvirtually.tgpix.storage

import dev.ssjvirtually.tgpix.ErrorMonitor
import android.content.Context
import android.content.Intent
import android.widget.Toast
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

open class BackupManager {
    companion object : BackupManager()

    enum class BackupResult {
        SUCCESS,
        SKIPPED_CONDITIONS_NOT_MET,
        SKIPPED_RESTORE_ACTIVE,
        FAILED
    }

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

    private fun getSchemaVersionFromFile(dbFile: File): Int {
        val bytes = ByteArray(4)
        java.io.RandomAccessFile(dbFile, "r").use { raf ->
            raf.seek(60)
            raf.readFully(bytes)
        }
        return java.nio.ByteBuffer.wrap(bytes).int
    }

    open fun getBackupChatId(context: Context): Long {
        val customId = PreferencesManager.getDbChatId(context)
        if (customId != 0L) return customId
        
        val myId = TdlibManager.myUserId
        if (myId != 0L) return myId
        
        return PreferencesManager.getChatId(context)
    }

    open suspend fun resolveBackupChatId(context: Context): Long {
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
                ErrorMonitor.log(e)
            }
            if (attempt < 4) delay(1500) // Wait before retrying
        }
    }

    open suspend fun resolveMyUserId(): Long {
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
                ErrorMonitor.log(e)
            }
        }
        return myId
    }

    open fun scheduleBackup(context: Context) {
        val appContext = context.applicationContext
        if (PreferencesManager.isRestoreActive(appContext)) {
            TdlibManager.addLog("BackupManager: Skipping backup scheduling and canceling db_backup because restore is active.")
            WorkManager.getInstance(appContext).cancelUniqueWork("db_backup")
            return
        }
        val workManager = WorkManager.getInstance(appContext)
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

    data class SnapshotInfo(
        val messageId: Long,
        val version: Int,
        val timestamp: Long,
        val deviceId: String,
        val recordCount: Int
    )

    open suspend fun getLatestSnapshotInfo(chatId: Long): SnapshotInfo? {
        var lastMessageId = 0L
        var crawling = true
        
        while (crawling) {
            val getHistory = TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.fromMessageId = lastMessageId
                this.offset = 0
                this.limit = 100
                this.onlyLocal = false
            }
            
            val result = TdlibManager.sendRequest(getHistory)
            
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                // Messages are returned newest-first from TDLib
                for (message in result.messages) {
                    val docContent = message.content as? TdApi.MessageDocument
                    if (docContent != null) {
                        val captionText = docContent.caption?.text ?: ""
                        if (captionText.contains("#tgpix_snapshot")) {
                            val jsonStr = captionText.substringAfter("#tgpix_snapshot").trim()
                            val jsonObj = try { org.json.JSONObject(jsonStr) } catch (e: Exception) { null } ?: continue
                            val schemaVersion = jsonObj.optInt("schemaVersion", 0)
                            if (schemaVersion <= UploadDatabase.DATABASE_VERSION) {
                                return SnapshotInfo(
                                    messageId = message.id,
                                    version = jsonObj.optInt("snapshotVersion", 0),
                                    timestamp = jsonObj.optLong("timestamp", 0L),
                                    deviceId = jsonObj.optString("deviceId", ""),
                                    recordCount = jsonObj.optInt("recordCount", 0)
                                )
                            }
                        }
                    }
                }
                lastMessageId = result.messages.last().id
            } else {
                crawling = false
            }
        }
        return null
    }

    open suspend fun countMessagesSince(chatId: Long, sinceMessageId: Long): Int {
        var count = 0
        var lastMessageId = 0L
        var crawling = true
        
        while (crawling) {
            val getHistory = TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.fromMessageId = lastMessageId
                this.offset = 0
                this.limit = 50
                this.onlyLocal = false
            }
            
            val result = TdlibManager.sendRequest(getHistory)
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                for (msg in result.messages) {
                    if (msg.id <= sinceMessageId) {
                        crawling = false
                        break
                    }
                    count++
                }
                lastMessageId = result.messages.last().id
            } else {
                crawling = false
            }
        }
        return count
    }

    open suspend fun backupDatabase(context: Context, force: Boolean = false): BackupResult {
        if (PreferencesManager.isRestoreActive(context)) {
            TdlibManager.addLog("BackupManager: Database backup aborted because a restore is active.")
            return BackupResult.SKIPPED_RESTORE_ACTIVE
        }
        if (isBackupRunning) return BackupResult.FAILED
        isBackupRunning = true
        var resultStatus = BackupResult.FAILED
        
        try {
            val targetChatId = resolveBackupChatId(context)
            if (targetChatId == 0L) {
                isBackupRunning = false
                return BackupResult.FAILED
            }
            
            resultStatus = withContext(Dispatchers.IO) {
                val db = UploadDatabase.getDatabase(context)
                val myDeviceId = PreferencesManager.getDeviceId(context)

                // 1. Fully-synced pre-condition: Ensure our local database has replayed all recent channel events
                try {
                    dev.ssjvirtually.tgpix.telegram.HistorySyncManager.syncMetadataHistory(context, targetChatId)
                } catch (e: Exception) {
                    TdlibManager.addLog("Failed to sync metadata history before backup: ${e.message}")
                }

                // 2. Fetch latest snapshot info from the backup channel
                val latestSnapshot = getLatestSnapshotInfo(targetChatId)

                // 3. Verify snapshot conditions (age >= 3 days or pending events/uploads >= 50)
                val currentCount = db.cloudDao().getRecordCountDirect()
                val shouldSnapshot = if (force) {
                    TdlibManager.addLog("Force backup requested. Proceeding with snapshot.")
                    true
                } else if (latestSnapshot == null) {
                    TdlibManager.addLog("No existing remote database snapshot found. Will initiate first snapshot.")
                    true
                } else {
                    val ageMs = System.currentTimeMillis() - latestSnapshot.timestamp
                    val ageDays = ageMs / (24 * 60 * 60 * 1000L)
                    val newPhotosCount = Math.abs(currentCount - latestSnapshot.recordCount)
                    val pendingEventsCount = countMessagesSince(targetChatId, latestSnapshot.messageId)
                    val totalUnsnapshottedChanges = newPhotosCount + pendingEventsCount
                    
                    TdlibManager.addLog("Snapshot check: last snapshot v${latestSnapshot.version} is $ageDays days old. " +
                            "New photos: $newPhotosCount, pending metadata events: $pendingEventsCount (Total changes: $totalUnsnapshottedChanges).")
                    
                    ageDays >= 3 || totalUnsnapshottedChanges >= 50
                }

                if (!shouldSnapshot) {
                    TdlibManager.addLog("Database backup condition not met (age < 3 days and < 50 total changes). Skipping snapshot creation.")
                    return@withContext BackupResult.SKIPPED_CONDITIONS_NOT_MET
                }
                
                val dbFile = context.getDatabasePath("upload_database")
                if (!dbFile.exists()) {
                    return@withContext BackupResult.FAILED
                }

                // 4. Flush Room's WAL logs and truncate the WAL file size OUTSIDE the transaction
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
                    ErrorMonitor.log(e)
                    false
                }

                if (!checkpointSuccess) {
                    return@withContext BackupResult.FAILED
                }

                // 5. Safely create a copy of the database
                val tempBackupFile = File(context.filesDir, "tgpix_backup.db")
                if (tempBackupFile.exists()) {
                    tempBackupFile.delete()
                }

                val finalCount = db.cloudDao().getRecordCountDirect()
                val lastCount = PreferencesManager.getLastBackupRecordCount(context)

                // If database is empty but we previously had backups, abort to protect remote
                if (finalCount == 0 && lastCount > 0) {
                    TdlibManager.addLog("Backup safety check failed: Local DB is empty, aborting upload to protect remote backup.")
                    return@withContext BackupResult.FAILED
                }

                var copySuccess = false
                try {
                    db.openHelper.writableDatabase.execSQL("VACUUM INTO '${tempBackupFile.absolutePath}'")
                    copySuccess = true
                    TdlibManager.addLog("Database copy generated via VACUUM INTO.")
                } catch (e: Exception) {
                    TdlibManager.addLog("VACUUM INTO failed: ${e.message}. Falling back to transactional copy.")
                    try {
                        db.withTransaction {
                            dbFile.copyTo(tempBackupFile, overwrite = true)
                        }
                        copySuccess = true
                        TdlibManager.addLog("Database copy generated via transactional copy fallback.")
                    } catch (ex: Exception) {
                        TdlibManager.addLog("Transactional copy fallback failed: ${ex.message}")
                        ErrorMonitor.log(ex)
                    }
                }

                if (!copySuccess || !tempBackupFile.exists() || tempBackupFile.length() == 0L) {
                    TdlibManager.addLog("Failed to create database snapshot file.")
                    return@withContext BackupResult.FAILED
                }
                
                // 6. Compress DB backup file using GZIP
                val compressedFile = File(context.filesDir, "snapshot_${System.currentTimeMillis()}.db.gz")
                try {
                    java.util.zip.GZIPOutputStream(FileOutputStream(compressedFile)).use { gzip ->
                        FileInputStream(tempBackupFile).use { fis ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (fis.read(buffer).also { len = it } != -1) {
                                gzip.write(buffer, 0, len)
                            }
                        }
                    }
                } catch (e: Exception) {
                    ErrorMonitor.log(e)
                    if (tempBackupFile.exists()) tempBackupFile.delete()
                    if (compressedFile.exists()) compressedFile.delete()
                    return@withContext BackupResult.FAILED
                } finally {
                    if (tempBackupFile.exists()) {
                        tempBackupFile.delete()
                    }
                }
                
                // 7. Compute integrity checksum
                val sha256 = computeSha256(compressedFile)

                // 8. Optimistic Concurrency Control Check: Fetch latest snapshot info again before uploading
                val latestSnapshotBeforeUpload = getLatestSnapshotInfo(targetChatId)
                val expectedVersion = if (latestSnapshot == null) 1 else latestSnapshot.version + 1

                if (latestSnapshotBeforeUpload != null) {
                    val currentLatestVersion = latestSnapshotBeforeUpload.version
                    val currentLatestMsgId = latestSnapshotBeforeUpload.messageId
                    val initialMsgId = latestSnapshot?.messageId

                    if (currentLatestVersion >= expectedVersion || currentLatestMsgId != initialMsgId) {
                        TdlibManager.addLog("Aborting snapshot upload: another device successfully uploaded a newer snapshot (v$currentLatestVersion) while we were generating ours.")
                        if (compressedFile.exists()) {
                            compressedFile.delete()
                        }
                        return@withContext BackupResult.FAILED
                    }
                }

                val nextVersion = expectedVersion
                
                val jsonMeta = org.json.JSONObject().apply {
                    put("snapshotVersion", nextVersion)
                    put("deviceId", myDeviceId)
                    put("recordCount", finalCount)
                    put("schemaVersion", UploadDatabase.DATABASE_VERSION)
                    put("sha256", sha256)
                    put("timestamp", System.currentTimeMillis())
                }
                val captionText = "#tgpix_snapshot $jsonMeta"
                
                // 9. Upload compressed snapshot to Metadata Channel
                val uploadResult = uploadFile(compressedFile, targetChatId, captionText)
                var uploadSuccess = false
                if (uploadResult is TdApi.Message) {
                    val newMsgId = uploadResult.id
                    
                    PreferencesManager.setLastBackupMessageId(context, newMsgId)
                    PreferencesManager.setLastBackupRecordCount(context, finalCount)
                    
                    TdlibManager.addLog("SQLite snapshot v$nextVersion updated successfully to Message ID $newMsgId in chat $targetChatId (Records: $finalCount).")
 
                    // 10. Prune older snapshots (keep last 10)
                    pruneOldSnapshots(targetChatId)
                    
                    UploadDatabase.recordEvent(
                        context,
                        "db_backup_success",
                        "Successfully backed up database snapshot v$nextVersion to Telegram Message ID $newMsgId (Records: $finalCount)"
                    )
                    uploadSuccess = true
                } else if (uploadResult is TdApi.Error) {
                    TdlibManager.addLog("Database backup upload failed: ${uploadResult.message}")
                    TdlibManager.checkAndHandleChatError(context, uploadResult)
                    UploadDatabase.recordEvent(
                        context,
                        "db_backup_failed",
                        "Failed to back up database. Telegram Error: [${uploadResult.code}] ${uploadResult.message}"
                    )
                }
                
                if (compressedFile.exists()) {
                    compressedFile.delete()
                }
                if (uploadSuccess) BackupResult.SUCCESS else BackupResult.FAILED
            }
        } catch (e: Exception) {
            ErrorMonitor.log(e)
            UploadDatabase.recordEvent(
                context,
                "db_backup_failed",
                "Exception during database backup: ${e.message}"
            )
        } finally {
            isBackupRunning = false
        }
        return resultStatus
    }

    open suspend fun getLatestSnapshotVersion(chatId: Long): Int {
        var highestVersion = 0
        try {
            val info = getLatestSnapshotInfo(chatId)
            if (info != null) {
                highestVersion = info.version
            }
        } catch (e: Exception) {
            ErrorMonitor.log(e)
        }
        return highestVersion
    }

    open suspend fun pruneOldSnapshots(chatId: Long) {
        try {
            val snapshotMessages = mutableListOf<TdApi.Message>()
            var lastMessageId = 0L
            var crawling = true
            
            while (crawling) {
                val getHistory = TdApi.GetChatHistory().apply {
                    this.chatId = chatId
                    this.fromMessageId = lastMessageId
                    this.offset = 0
                    this.limit = 100
                    this.onlyLocal = false
                }
                val result = TdlibManager.sendRequest(getHistory)
                if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                    result.messages.filter { msg ->
                        val docContent = msg.content as? TdApi.MessageDocument
                        val caption = docContent?.caption?.text ?: ""
                        caption.contains("#tgpix_snapshot")
                    }.let { snapshotMessages.addAll(it) }
                    lastMessageId = result.messages.last().id
                } else {
                    crawling = false
                }
            }
            
            if (snapshotMessages.size > 10) {
                val sortedMsgs = snapshotMessages.sortedByDescending { it.id }
                val toDelete = sortedMsgs.subList(10, sortedMsgs.size)
                val msgIds = toDelete.map { it.id }.toLongArray()
                
                if (msgIds.isNotEmpty()) {
                    TdlibManager.getClient().send(TdApi.DeleteMessages(chatId, msgIds, true)) { deleteResult ->
                        if (deleteResult is TdApi.Ok) {
                            TdlibManager.addLog("Old SQLite snapshots pruned successfully from Telegram.")
                        } else if (deleteResult is TdApi.Error) {
                            TdlibManager.addLog("Failed to prune old SQLite snapshots: ${deleteResult.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorMonitor.log(e)
        }
    }

    private fun restartApp(context: Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private suspend fun tryRestoreSnapshot(context: Context, chatId: Long): Boolean {
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
            ErrorMonitor.log(e)
        }

        TdlibManager.addLog("Searching for remote database snapshots (#tgpix_snapshot) in chat $chatId via paginated history crawl...")
        var lastMessageId = 0L
        var crawling = true
        
        while (crawling) {
            val getHistory = TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.fromMessageId = lastMessageId
                this.offset = 0
                this.limit = 100
                this.onlyLocal = false
            }
            
            val result = TdlibManager.sendRequest(getHistory)
            
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                // Messages are returned newest-first; scan each batch for snapshots
                for (message in result.messages) {
                    val docContent = message.content as? TdApi.MessageDocument ?: continue
                    val document = docContent.document ?: continue
                    val captionText = docContent.caption?.text ?: ""
                    if (!captionText.contains("#tgpix_snapshot")) continue
                    
                    val jsonStr = captionText.substringAfter("#tgpix_snapshot").trim()
                    val jsonObj = try { org.json.JSONObject(jsonStr) } catch (e: Exception) { null } ?: continue
                    
                    val schemaVersion = jsonObj.optInt("schemaVersion", 0)
                    if (schemaVersion > UploadDatabase.DATABASE_VERSION) {
                        TdlibManager.addLog("Skipping snapshot (Msg ID: ${message.id}): snapshot schema version ($schemaVersion) is newer than app version (${UploadDatabase.DATABASE_VERSION}).")
                        continue
                    }

                    TdlibManager.addLog("Attempting to download snapshot (Msg ID: ${message.id}, File ID: ${document.document.id})...")
                    
                    val downloadedFile = downloadFile(document.document.id)
                    if (downloadedFile != null && downloadedFile.exists()) {
                        val expectedHash = jsonObj.optString("sha256", "")
                        if (expectedHash.isNotEmpty()) {
                            val actualHash = computeSha256(downloadedFile)
                            if (actualHash != expectedHash) {
                                TdlibManager.addLog("Snapshot (Msg ID: ${message.id}) integrity check FAILED. Expected: $expectedHash, Got: $actualHash.")
                                continue
                            }
                            TdlibManager.addLog("Snapshot integrity verified (SHA-256 match).")
                        }

                        val decompressedFile = File(context.cacheDir, "temp_restored_db.db")
                        val decompressedSuccess = try {
                            java.util.zip.GZIPInputStream(FileInputStream(downloadedFile)).use { gzip ->
                                FileOutputStream(decompressedFile).use { fos ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (gzip.read(buffer).also { len = it } != -1) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            true
                        } catch (e: Exception) {
                            TdlibManager.addLog("Failed to decompress snapshot: ${e.message}")
                            false
                        } finally {
                            if (downloadedFile.exists()) downloadedFile.delete()
                        }

                        if (!decompressedSuccess || !decompressedFile.exists()) {
                            continue
                        }

                        val header = ByteArray(15)
                        try {
                            FileInputStream(decompressedFile).use { it.read(header) }
                        } catch (e: Exception) {
                            TdlibManager.addLog("Failed to read header of snapshot: ${e.message}")
                            decompressedFile.delete()
                            continue
                        }
                        if (!String(header).startsWith("SQLite format 3")) {
                            TdlibManager.addLog("Snapshot (Msg ID: ${message.id}) magic header check FAILED. Not a valid SQLite database.")
                            decompressedFile.delete()
                            continue
                        }

                        TdlibManager.addLog("Snapshot decompressed and validated. Restoring data in-process from Msg ID: ${message.id}...")

                        val recordsCount = jsonObj.optInt("recordCount", 0)
                        val snapshotVersion = jsonObj.optInt("snapshotVersion", 0)
                        
                        val restored = UploadDatabase.restoreDataFromFile(context, decompressedFile)
                        decompressedFile.delete()
                        
                        if (restored >= 0) {
                            TdlibManager.addLog("In-process snapshot restore complete: $restored cloud_photos rows loaded into live database.")
                            
                            PreferencesManager.setLastBackupMessageId(context, message.id)
                            PreferencesManager.setLastBackupRecordCount(context, recordsCount)
                            // Reset metadata bookmark to 0 so ALL metadata events in the
                            // backup channel are replayed chronologically. The snapshot
                            // contains raw data but not the effects of DELETE/RESTORE/ALBUM
                            // events that occurred after it was created.
                            PreferencesManager.setLastReplayedMetadataMsgId(context, 0L)
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "Snapshot restored: $restored photos loaded ✓", Toast.LENGTH_SHORT).show()
                            }
                            UploadDatabase.recordEvent(
                                context,
                                "restore_success",
                                "Successfully restored snapshot v$snapshotVersion ($restored records) from Msg ID: ${message.id}"
                            )
                            return true
                        } else {
                            TdlibManager.addLog("In-process restore failed for Msg ID: ${message.id}.")
                        }
                    } else {
                        TdlibManager.addLog("Failed to download snapshot file (Msg ID: ${message.id}).")
                    }
                }
                lastMessageId = result.messages.last().id
            } else {
                crawling = false
            }
        }
        return false
    }

    open suspend fun restoreDatabase(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val db = UploadDatabase.getDatabase(context)
            val currentCount = db.cloudDao().getRecordCountDirect()
            if (currentCount > 0) {
                return@withContext false
            }
            
            val targetChatId = resolveBackupChatId(context)
            var restored = false
            if (targetChatId != 0L) {
                restored = tryRestoreSnapshot(context, targetChatId)
            }
            return@withContext restored
        }
    }

    open suspend fun restoreDatabaseForce(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val targetChatId = resolveBackupChatId(context)
            var restored = false
            if (targetChatId != 0L) {
                restored = tryRestoreSnapshot(context, targetChatId)
            }
            
            if (!restored) {
                UploadDatabase.recordEvent(
                    context,
                    "restore_failed",
                    "Failed to restore database from backup channel ($targetChatId)"
                )
            }
            return@withContext restored
        }
    }

    open suspend fun reconstructAlbumsFromBackupChannel(context: Context) {
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
            ErrorMonitor.log(e)
        }

        val allAlbumMessages = mutableListOf<TdApi.Message>()
        var lastMessageId = 0L
        var crawling = true
        
        while (crawling) {
            val getHistory = TdApi.GetChatHistory().apply {
                this.chatId = targetChatId
                this.fromMessageId = lastMessageId
                this.offset = 0
                this.limit = 100
                this.onlyLocal = false
            }
            
            val result = TdlibManager.sendRequest(getHistory)
            
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                result.messages.filter { msg ->
                    val docContent = msg.content as? TdApi.MessageDocument
                    val caption = docContent?.caption?.text ?: ""
                    caption.contains("#tgpix_album")
                }.let { allAlbumMessages.addAll(it) }
                lastMessageId = result.messages.last().id
            } else {
                crawling = false
            }
        }
        
        if (allAlbumMessages.isNotEmpty()) {
            TdlibManager.addLog("Found ${allAlbumMessages.size} album manifests in Backup Channel. Reconstructing...")
            for (message in allAlbumMessages) {
                try {
                    reconstructAlbum(context, message)
                } catch (e: Exception) {
                    ErrorMonitor.log(e)
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

    open suspend fun onAlbumUpdated(context: Context, albumId: Long) {
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
            ErrorMonitor.log(e)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    open suspend fun onAlbumDeleted(context: Context, telegramMessageId: Long?) {
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
            ErrorMonitor.log(e)
        }
    }

    open suspend fun reconstructAlbum(context: Context, message: TdApi.Message) {
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
                ErrorMonitor.log(e)
            } finally {
                if (downloadedFile.exists()) {
                    downloadedFile.delete()
                }
            }
        }
    }

    open suspend fun registerDevice(context: Context) {
        if (PreferencesManager.isDeviceRegistered(context)) return
        val dbChatId = resolveBackupChatId(context)
        if (dbChatId == 0L) return
        
        val deviceId = PreferencesManager.getDeviceId(context)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val version = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "DEVICE_REGISTER")
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("version", version)
            put("timestamp", timestamp)
        }
        
        val success = sendMetadataEvent(context, eventObj.toString())
        if (success) {
            PreferencesManager.setDeviceRegistered(context, true)
            try {
                val db = UploadDatabase.getDatabase(context)
                db.deviceDao().insert(RegisteredDeviceEntity(deviceId, deviceName, version, timestamp))
                TdlibManager.addLog("Device successfully registered on Telegram and locally: $deviceId ($deviceName)")
            } catch (e: Exception) {
                ErrorMonitor.log(e)
            }
        } else {
            TdlibManager.addLog("Failed to send device registration metadata event.")
        }
    }

    open suspend fun sendMetadataEvent(context: Context, eventJson: String): Boolean {
        val dbChatId = resolveBackupChatId(context)
        if (dbChatId == 0L) return false
        
        return suspendCancellableCoroutine { cont ->
            val request = TdApi.SendMessage().apply {
                this.chatId = dbChatId
                this.inputMessageContent = TdApi.InputMessageText().apply {
                    text = TdApi.FormattedText(eventJson, emptyArray())
                }
            }
            TdlibManager.getClient().send(request) { result ->
                if (result is TdApi.Message) {
                    TdlibManager.registerPendingUpload(result.id) { res ->
                        cont.resume(res is TdApi.Message)
                    }
                } else {
                    cont.resume(false)
                }
            }
        }
    }

    open suspend fun deleteCloudPhoto(context: Context, messageId: Long) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "DELETE")
            put("messageId", messageId)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        
        val db = UploadDatabase.getDatabase(context)
        val cloudPhoto = db.cloudDao().findByMessageId(messageId)
        if (cloudPhoto != null) {
            db.cloudDao().insert(cloudPhoto.copy(isTrashed = true, trashedAt = timestamp))
        }
        
        sendMetadataEvent(context, eventObj.toString())
    }

    open suspend fun restoreCloudPhoto(context: Context, messageId: Long) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "RESTORE")
            put("messageId", messageId)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        
        val db = UploadDatabase.getDatabase(context)
        val cloudPhoto = db.cloudDao().findByMessageId(messageId)
        if (cloudPhoto != null) {
            db.cloudDao().insert(cloudPhoto.copy(isTrashed = false, trashedAt = 0L))
        }
        
        sendMetadataEvent(context, eventObj.toString())
    }

    open suspend fun deleteCloudPhotoPermanently(context: Context, messageId: Long): Boolean {
        val vaultChatId = PreferencesManager.getChatId(context)
        if (vaultChatId == 0L) return false
        
        return withContext(Dispatchers.IO) {
            val db = UploadDatabase.getDatabase(context)
            val cloudPhoto = db.cloudDao().findByMessageId(messageId)
            val photoUri = cloudPhoto?.fileName ?: "photo_${messageId}.jpg"
            
            val success = suspendCancellableCoroutine<Boolean> { cont ->
                TdlibManager.getClient().send(TdApi.DeleteMessages(vaultChatId, longArrayOf(messageId), true)) { res ->
                    cont.resume(res is TdApi.Ok)
                }
            }
            
            if (success) {
                db.cloudDao().deleteByMessageId(messageId)
                db.albumDao().removePhotoFromAllAlbums(photoUri, messageId)
                db.albumDao().removePhotoFromAllAlbums("cloud://$messageId/0/$photoUri", messageId)
                TdlibManager.addLog("Permanently deleted photo message $messageId from Telegram and local cache.")
                true
            } else {
                TdlibManager.addLog("Failed to delete photo message $messageId from Telegram.")
                false
            }
        }
    }

    open suspend fun pruneExpiredTrash(context: Context) {
        val vaultChatId = PreferencesManager.getChatId(context)
        if (vaultChatId == 0L) return
        
        withContext(Dispatchers.IO) {
            val db = UploadDatabase.getDatabase(context)
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L)
            
            val expiredPhotos = db.cloudDao().getAll().filter { it.isTrashed && it.trashedAt < thirtyDaysAgo }
            if (expiredPhotos.isEmpty()) return@withContext
            
            TdlibManager.addLog("Pruning ${expiredPhotos.size} expired trashed photos (older than 30 days)...")
            
            val messageIds = expiredPhotos.map { it.messageId }.toLongArray()
            
            val success = suspendCancellableCoroutine<Boolean> { cont ->
                TdlibManager.getClient().send(TdApi.DeleteMessages(vaultChatId, messageIds, true)) { res ->
                    cont.resume(res is TdApi.Ok)
                }
            }
            
            if (success) {
                for (photo in expiredPhotos) {
                    db.cloudDao().deleteByMessageId(photo.messageId)
                    db.albumDao().removePhotoFromAllAlbums(photo.fileName, photo.messageId)
                    db.albumDao().removePhotoFromAllAlbums("cloud://${photo.messageId}/0/${photo.fileName}", photo.messageId)
                }
                TdlibManager.addLog("Pruned ${expiredPhotos.size} expired trashed photos from Telegram and local cache.")
            } else {
                TdlibManager.addLog("Failed to prune expired trashed photos from Telegram.")
            }
        }
    }

    open suspend fun requestLeaderRole(context: Context) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "LEADER_CHANGED")
            put("leaderDeviceId", deviceId)
            put("timestamp", timestamp)
        }
        
        val success = sendMetadataEvent(context, eventObj.toString())
        if (success) {
            PreferencesManager.setCurrentLeaderDeviceId(context, deviceId)
            TdlibManager.addLog("Successfully requested Snapshot Leader role for device: $deviceId")
        }
    }

    open suspend fun logAlbumCreate(context: Context, albumUuid: String, name: String) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "ALBUM_CREATE")
            put("albumId", albumUuid)
            put("name", name)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        sendMetadataEvent(context, eventObj.toString())
    }

    open suspend fun logAlbumDelete(context: Context, albumUuid: String) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "ALBUM_DELETE")
            put("albumId", albumUuid)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        sendMetadataEvent(context, eventObj.toString())
    }

    open suspend fun logAlbumAdd(context: Context, albumUuid: String, messageId: Long) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "ALBUM_ADD")
            put("albumId", albumUuid)
            put("messageId", messageId)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        sendMetadataEvent(context, eventObj.toString())
    }

    open suspend fun logAlbumRemove(context: Context, albumUuid: String, messageId: Long) {
        val deviceId = PreferencesManager.getDeviceId(context)
        val timestamp = System.currentTimeMillis()
        
        val eventObj = org.json.JSONObject().apply {
            put("type", "ALBUM_REMOVE")
            put("albumId", albumUuid)
            put("messageId", messageId)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        sendMetadataEvent(context, eventObj.toString())
    }

    open suspend fun applyMetadataEvent(context: Context, eventJson: String, messageId: Long = 0L) {
        val db = UploadDatabase.getDatabase(context)
        try {
            val obj = org.json.JSONObject(eventJson)
            val type = obj.optString("type")
            val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            
            when (type) {
                "DEVICE_REGISTER" -> {
                    val deviceId = obj.getString("deviceId")
                    val deviceName = obj.getString("deviceName")
                    val version = obj.getString("version")
                    db.deviceDao().insert(RegisteredDeviceEntity(deviceId, deviceName, version, timestamp))
                    TdlibManager.addLog("Replayed event DEVICE_REGISTER: $deviceId ($deviceName)")
                }
                "LEADER_CHANGED" -> {
                    val leaderId = obj.getString("leaderDeviceId")
                    PreferencesManager.setCurrentLeaderDeviceId(context, leaderId)
                    TdlibManager.addLog("Replayed event LEADER_CHANGED: leader is now $leaderId")
                }
                "DELETE" -> {
                    val msgId = obj.getLong("messageId")
                    val cloudPhoto = db.cloudDao().findByMessageId(msgId)
                    if (cloudPhoto != null) {
                        db.cloudDao().insert(cloudPhoto.copy(isTrashed = true, trashedAt = timestamp))
                        TdlibManager.addLog("Replayed event DELETE: messageId $msgId marked as trashed.")
                    } else {
                        db.cloudDao().insert(
                            CloudPhotoEntity(
                                messageId = msgId,
                                telegramFileId = 0,
                                uniqueRemoteId = "deleted_$msgId",
                                fileName = "deleted_$msgId",
                                uploadedAt = timestamp,
                                fileSize = 0L,
                                isDocument = false,
                                isTrashed = true,
                                trashedAt = timestamp
                            )
                        )
                        TdlibManager.addLog("Replayed event DELETE: messageId $msgId not indexed yet; created trashed placeholder.")
                    }
                }
                "RESTORE" -> {
                    val msgId = obj.getLong("messageId")
                    val cloudPhoto = db.cloudDao().findByMessageId(msgId)
                    if (cloudPhoto != null) {
                        db.cloudDao().insert(cloudPhoto.copy(isTrashed = false, trashedAt = 0L))
                        TdlibManager.addLog("Replayed event RESTORE: messageId $msgId marked as restored.")
                    }
                }
                "ALBUM_CREATE" -> {
                    val albumId = obj.getString("albumId")
                    val name = obj.getString("name")
                    val existing = db.albumDao().findByUuid(albumId)
                    if (existing == null) {
                        db.albumDao().insertAlbum(
                            AlbumEntity(
                                uuid = albumId,
                                name = name,
                                createdAt = timestamp
                            )
                        )
                        TdlibManager.addLog("Replayed event ALBUM_CREATE: '$name' (UUID: $albumId)")
                    }
                }
                "ALBUM_DELETE" -> {
                    val albumId = obj.getString("albumId")
                    val existing = db.albumDao().findByUuid(albumId)
                    if (existing != null) {
                        db.albumDao().deleteAlbum(existing.id)
                        db.albumDao().deleteAlbumPhotos(existing.id)
                        TdlibManager.addLog("Replayed event ALBUM_DELETE: UUID $albumId")
                    }
                }
                "ALBUM_ADD" -> {
                    val albumId = obj.getString("albumId")
                    val msgId = obj.optLong("messageId", 0L)
                    val album = db.albumDao().findByUuid(albumId)
                    if (album != null && msgId != 0L) {
                        val cloudPhoto = db.cloudDao().findByMessageId(msgId)
                        val photoUri = cloudPhoto?.fileName ?: "photo_${msgId}.jpg"
                        db.albumDao().insertAlbumPhotos(
                            listOf(AlbumPhotoEntity(albumId = album.id, photoUri = photoUri))
                        )
                        TdlibManager.addLog("Replayed event ALBUM_ADD: photo $photoUri added to album '${album.name}'")
                    }
                }
                "ALBUM_REMOVE" -> {
                    val albumId = obj.getString("albumId")
                    val msgId = obj.optLong("messageId", 0L)
                    val album = db.albumDao().findByUuid(albumId)
                    if (album != null && msgId != 0L) {
                        val cloudPhoto = db.cloudDao().findByMessageId(msgId)
                        val photoUri = cloudPhoto?.fileName ?: "photo_${msgId}.jpg"
                        db.albumDao().removePhotoFromAlbum(album.id, photoUri)
                        db.albumDao().removePhotoFromAlbum(album.id, "cloud://$msgId/0/$photoUri")
                        TdlibManager.addLog("Replayed event ALBUM_REMOVE: photo $photoUri removed from album '${album.name}'")
                    }
                }
            }
            if (messageId != 0L) {
                PreferencesManager.setLastReplayedMetadataMsgId(context, messageId)
            }
        } catch (e: Exception) {
            TdlibManager.addLog("Failed to replay metadata event: ${e.message}")
            ErrorMonitor.log(e)
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
                    val fileId = when (val c = result.content) {
                        is TdApi.MessageDocument -> c.document.document.id
                        is TdApi.MessagePhoto -> c.photo.sizes.lastOrNull()?.photo?.id
                        else -> null
                    }
                    if (fileId != null) {
                        TdlibManager.registerPendingTempFile(fileId, file.absolutePath)
                    }
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
