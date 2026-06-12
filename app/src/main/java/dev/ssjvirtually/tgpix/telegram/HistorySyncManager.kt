package dev.ssjvirtually.tgpix.telegram

import dev.ssjvirtually.tgpix.ErrorMonitor
import android.content.Context
import android.webkit.MimeTypeMap
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.CloudPhotoEntity
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.UploadEntity
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.io.File

open class HistorySyncManager {
    companion object : HistorySyncManager()

    private data class ParsedMetadata(
        val id: Long,
        val name: String,
        val size: Long,
        val dateTaken: Long,
        val tags: List<String> = emptyList(),
        val hash: String = "",
        val isHd: Boolean? = null,
        val originalSizeBytes: Long? = null
    )

    private fun parseMetadataFromCaption(caption: String): ParsedMetadata? {
        if (!caption.contains("#tgpix_metadata")) return null
        try {
            val jsonStr = caption.substringAfter("#tgpix_metadata").trim()
            val jsonObj = JSONObject(jsonStr)
            
            val tagsList = mutableListOf<String>()
            val tagsArray = jsonObj.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tagsList.add(tagsArray.getString(i))
                }
            }

            return ParsedMetadata(
                id = jsonObj.optLong("id", 0L),
                name = jsonObj.optString("name", ""),
                size = jsonObj.optLong("size", 0L),
                dateTaken = jsonObj.optLong("dateTaken", 0L),
                tags = tagsList,
                hash = jsonObj.optString("hash", ""),
                isHd = if (jsonObj.has("isHd")) jsonObj.getBoolean("isHd") else null,
                originalSizeBytes = if (jsonObj.has("origSize")) jsonObj.getLong("origSize") else null
            )
        } catch (e: Exception) {
            ErrorMonitor.log(e)
        }
        return null
    }

    open suspend fun syncCloudHistory(
        context: Context,
        chatId: Long,
        forceFullCrawl: Boolean = false,
        startMessageId: Long = 0L,
        onProgress: (suspend (recoveredCount: Int, lastMessageId: Long) -> Unit)? = null
    ) {
        val database = UploadDatabase.getDatabase(context)
        val cloudDao = database.cloudDao()
        
        // Staging: Clear any stale Room DB cache items referencing DB backups
        try {
            cloudDao.deleteBackupDbFiles()
        } catch (e: Exception) {}
        
        var lastMessageId = startMessageId
        var crawling = true
        var totalRecovered = 0
        TdlibManager.addLog("Starting server vault synchronization crawl (forceFullCrawl=$forceFullCrawl, startMessageId=$startMessageId)...")

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
                val entities = mutableListOf<CloudPhotoEntity>()
                for (msg in result.messages) {
                    val content = msg.content
                    if (content is TdApi.MessageDocument && content.caption.text.contains("#tgpix_album")) {
                        try {
                            BackupManager.reconstructAlbum(context, msg)
                        } catch (e: Exception) {
                            ErrorMonitor.log(e)
                        }
                        continue
                    }

                    // Check if this message already exists in the local database
                    if (!forceFullCrawl && cloudDao.exists(msg.id)) {
                        TdlibManager.addLog("Found existing message index in database. Terminating crawl.")
                        crawling = false
                        break
                    }

                    var fileName = ""
                    var fileId = 0
                    var thumbFileId = 0
                    var remoteId = ""
                    var fileSize = 0L
                    var isDoc = false
                    var uploadedAt = msg.date.toLong() * 1000L
                    var dateTaken = msg.date.toLong() * 1000L
                    var tags = ""
                    var metadata: ParsedMetadata? = null
                    var width = 0
                    var height = 0
                    
                    if (content is TdApi.MessagePhoto) {
                        val sizes = content.photo.sizes
                        if (sizes.isNotEmpty()) {
                            val largest = sizes.last()
                            fileId = largest.photo.id
                            remoteId = largest.photo.remote.id
                            fileSize = largest.photo.size.toLong()
                            
                            // Store first photo size as thumbnail (usually 's' or 'm')
                            thumbFileId = sizes.first().photo.id
                            width = largest.width
                            height = largest.height
                            
                            val captionText = content.caption.text
                            metadata = parseMetadataFromCaption(captionText)
                            if (metadata != null) {
                                fileName = metadata.name
                                uploadedAt = metadata.dateTaken
                                dateTaken = metadata.dateTaken
                                fileSize = metadata.size
                                tags = metadata.tags.joinToString(" ")
                            } else {
                                fileName = if (captionText.isNotEmpty()) {
                                    captionText.substringBefore("\n").trim()
                                } else {
                                    "photo_${msg.id}.jpg"
                                }
                                dateTaken = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(fileName) ?: (msg.date.toLong() * 1000L)
                            }
                        }
                    } else if (content is TdApi.MessageDocument) {
                        val doc = content.document
                        val docName = if (doc.fileName.isNotEmpty()) doc.fileName else {
                            val captionText = content.caption.text
                            if (captionText.isNotEmpty()) captionText.substringBefore("\n").trim() else "doc_${msg.id}"
                        }
                        
                        // Filter to only catalog document attachments that are valid image formats
                        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
                        val extension = docName.substringAfterLast('.', "").lowercase()
                        val isImage = imageExtensions.contains(extension)
                        
                        if (isImage && !docName.startsWith("tgpix_backup", ignoreCase = true)) {
                            fileId = doc.document.id
                            remoteId = doc.document.remote.id
                            fileSize = doc.document.size
                            isDoc = true
                            
                            // Store document thumbnail if present, fallback to document fileId
                            thumbFileId = doc.thumbnail?.file?.id ?: doc.document.id
                            width = doc.thumbnail?.width ?: 0
                            height = doc.thumbnail?.height ?: 0
                            
                            val captionText = content.caption.text
                            metadata = parseMetadataFromCaption(captionText)
                            if (metadata != null) {
                                fileName = metadata.name
                                uploadedAt = metadata.dateTaken
                                dateTaken = metadata.dateTaken
                                fileSize = metadata.size
                                tags = metadata.tags.joinToString(" ")
                            } else {
                                fileName = docName
                                dateTaken = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(fileName) ?: (msg.date.toLong() * 1000L)
                            }
                        }
                    }
                    
                    val existing = cloudDao.findByMessageId(msg.id)
                    val localThumb = if (existing?.localCachedThumbnailPath != null && File(existing.localCachedThumbnailPath).exists()) {
                        existing.localCachedThumbnailPath
                    } else null
                    val localLarge = if (existing?.localCachedLargePath != null && File(existing.localCachedLargePath).exists()) {
                        existing.localCachedLargePath
                    } else null

                    if (fileId != 0 && fileName.isNotEmpty()) {
                        val computedFingerprint = if (metadata != null && metadata.hash.isNotEmpty()) {
                            "${fileName}_${fileSize}_${dateTaken}_${metadata.hash}"
                        } else {
                            "${fileName}_${fileSize}_${dateTaken}"
                        }
                        val isHdValue = metadata?.isHd ?: !isDoc
                        val originalSizeValue = metadata?.originalSizeBytes ?: fileSize
                        
                        val extension = fileName.substringAfterLast('.', "").lowercase()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"

                         entities.add(
                            CloudPhotoEntity(
                                messageId = msg.id,
                                telegramFileId = fileId,
                                uniqueRemoteId = remoteId,
                                fileName = fileName,
                                uploadedAt = uploadedAt,
                                fileSize = fileSize,
                                isDocument = isDoc,
                                localCachedThumbnailPath = localThumb,
                                localCachedLargePath = localLarge,
                                contentFingerprint = computedFingerprint,
                                telegramThumbnailFileId = thumbFileId,
                                tags = tags,
                                fileIdCachedAt = System.currentTimeMillis(),
                                isHd = isHdValue,
                                originalSizeBytes = originalSizeValue,
                                dateTaken = dateTaken,
                                mimeType = mime,
                                width = width,
                                height = height,
                                isTrashed = false,
                                trashedAt = 0L
                            )
                        )
                    }
                }
                
                if (entities.isNotEmpty()) {
                    cloudDao.insertBatch(entities)
                    totalRecovered += entities.size
                    TdlibManager.addLog("Indexed ${entities.size} vault photos from Telegram server.")
                }
                
                lastMessageId = result.messages.last().id
                onProgress?.invoke(totalRecovered, lastMessageId)
            } else {
                crawling = false
            }
        }
        TdlibManager.addLog("Vault server synchronization crawl completed.")

        // Clean up duplicate photo uploads from both Telegram and local database
        try {
            val duplicates = cloudDao.getDuplicateMessageIds()
            if (duplicates.isNotEmpty()) {
                TdlibManager.addLog("Found ${duplicates.size} duplicate messages in server vault. Deleting duplicates from Telegram...")
                TdlibManager.sendRequest(TdApi.DeleteMessages(chatId, duplicates.toLongArray(), true))
                cloudDao.deleteDuplicatesFromCloudPhotos()
                TdlibManager.addLog("Local database duplicate cloud photos cleaned up.")
            }
        } catch (e: Exception) {
            TdlibManager.addLog("Failed to clean up duplicate vault messages: ${e.message}")
        }
        
        // Trigger storage cache cleanup to stay within 500 MB limit
        try {
            TdlibManager.optimizeStorage(context)
        } catch (e: Exception) {
            ErrorMonitor.log(e)
        }
    }

    open suspend fun parseAndIndexUploadedMessage(context: Context, msg: TdApi.Message) {
        val database = UploadDatabase.getDatabase(context)
        val cloudDao = database.cloudDao()
        
        val content = msg.content
        var fileName = ""
        var fileId = 0
        var thumbFileId = 0
        var remoteId = ""
        var fileSize = 0L
        var isDoc = false
        var uploadedAt = msg.date.toLong() * 1000L
        var dateTaken = msg.date.toLong() * 1000L
        var tags = ""
        var metadata: ParsedMetadata? = null
        var width = 0
        var height = 0
        
        if (content is TdApi.MessagePhoto) {
            val sizes = content.photo.sizes
            if (sizes.isNotEmpty()) {
                val largest = sizes.last()
                fileId = largest.photo.id
                remoteId = largest.photo.remote.id
                fileSize = largest.photo.size.toLong()
                thumbFileId = sizes.first().photo.id
                width = largest.width
                height = largest.height
                
                val captionText = content.caption.text
                metadata = parseMetadataFromCaption(captionText)
                if (metadata != null) {
                    fileName = metadata.name
                    uploadedAt = metadata.dateTaken
                    dateTaken = metadata.dateTaken
                    fileSize = metadata.size
                    tags = metadata.tags.joinToString(" ")
                } else {
                    fileName = if (captionText.isNotEmpty()) {
                        captionText.substringBefore("\n").trim()
                    } else {
                        "photo_${msg.id}.jpg"
                    }
                    dateTaken = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(fileName) ?: (msg.date.toLong() * 1000L)
                }
            }
        } else if (content is TdApi.MessageDocument) {
            val doc = content.document
            val docName = if (doc.fileName.isNotEmpty()) doc.fileName else {
                val captionText = content.caption.text
                if (captionText.isNotEmpty()) captionText.substringBefore("\n").trim() else "doc_${msg.id}"
            }
            
            val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
            val extension = docName.substringAfterLast('.', "").lowercase()
            val isImage = imageExtensions.contains(extension)
            
            if (isImage && !docName.startsWith("tgpix_backup", ignoreCase = true)) {
                fileId = doc.document.id
                remoteId = doc.document.remote.id
                fileSize = doc.document.size
                isDoc = true
                thumbFileId = doc.thumbnail?.file?.id ?: doc.document.id
                width = doc.thumbnail?.width ?: 0
                height = doc.thumbnail?.height ?: 0
                
                val captionText = content.caption.text
                metadata = parseMetadataFromCaption(captionText)
                if (metadata != null) {
                    fileName = metadata.name
                    uploadedAt = metadata.dateTaken
                    dateTaken = metadata.dateTaken
                    fileSize = metadata.size
                    tags = metadata.tags.joinToString(" ")
                } else {
                    fileName = docName
                    dateTaken = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(fileName) ?: (msg.date.toLong() * 1000L)
                }
            }
        }
        
        if (fileId != 0 && fileName.isNotEmpty()) {
            val computedFingerprint = if (metadata != null && metadata.hash.isNotEmpty()) {
                "${fileName}_${fileSize}_${dateTaken}_${metadata.hash}"
            } else {
                "${fileName}_${fileSize}_${dateTaken}"
            }
            val isHdValue = metadata?.isHd ?: !isDoc
            val originalSizeValue = metadata?.originalSizeBytes ?: fileSize
            
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
            
            val entity = CloudPhotoEntity(
                messageId = msg.id,
                telegramFileId = fileId,
                uniqueRemoteId = remoteId,
                fileName = fileName,
                uploadedAt = uploadedAt,
                fileSize = fileSize,
                isDocument = isDoc,
                contentFingerprint = computedFingerprint,
                telegramThumbnailFileId = thumbFileId,
                tags = tags,
                fileIdCachedAt = System.currentTimeMillis(),
                isHd = isHdValue,
                originalSizeBytes = originalSizeValue,
                dateTaken = dateTaken,
                mimeType = mime,
                width = width,
                height = height,
                isTrashed = false,
                trashedAt = 0L
            )
            
            cloudDao.insertBatch(listOf(entity))
            TdlibManager.addLog("Indexed uploaded photo '${fileName}' into database in real-time.")
        }
    }

    open suspend fun syncMetadataHistory(context: Context, dbChatId: Long) {
        val lastReplayedId = PreferencesManager.getLastReplayedMetadataMsgId(context)
        var lastMessageId = 0L
        var crawling = true
        val eventList = mutableListOf<Pair<Long, String>>()
        
        TdlibManager.addLog("Syncing metadata history from Metadata Channel (lastReplayedId=$lastReplayedId)...")
        
        while (crawling) {
            val getHistory = TdApi.GetChatHistory().apply {
                this.chatId = dbChatId
                this.fromMessageId = lastMessageId
                this.offset = 0
                this.limit = 50
                this.onlyLocal = false
            }
            
            val result = TdlibManager.sendRequest(getHistory)
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                for (msg in result.messages) {
                    if (msg.id <= lastReplayedId) {
                        crawling = false
                        break
                    }
                    val content = msg.content
                    if (content is TdApi.MessageText) {
                        val text = content.text.text
                        if (text.startsWith("{") && text.contains("\"type\"")) {
                            eventList.add(Pair(msg.id, text))
                        }
                    }
                }
                lastMessageId = result.messages.last().id
            } else {
                crawling = false
            }
        }
        
        if (eventList.isNotEmpty()) {
            eventList.reverse()
            TdlibManager.addLog("Replaying ${eventList.size} historical metadata events chronologically...")
            
            var maxMsgId = lastReplayedId
            for ((msgId, json) in eventList) {
                try {
                    BackupManager.applyMetadataEvent(context, json, msgId)
                    if (msgId > maxMsgId) {
                        maxMsgId = msgId
                    }
                } catch (e: Exception) {
                    ErrorMonitor.log(e)
                }
            }
            PreferencesManager.setLastReplayedMetadataMsgId(context, maxMsgId)
        }
        TdlibManager.addLog("Metadata history sync completed.")
    }
}
