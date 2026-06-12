package dev.ssjvirtually.tgpix.ui.utils

import androidx.compose.runtime.*
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import androidx.compose.ui.platform.LocalContext
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred

sealed class GalleryItem {
    data class Header(val date: String) : GalleryItem()
    data class PhotoItem(val photo: LocalPhoto) : GalleryItem()
}

fun isCloudPhoto(uri: String): Boolean = uri.startsWith("cloud://")

fun parseCloudPhotoUri(uri: String): Triple<Long, Int, String>? {
    if (!isCloudPhoto(uri)) return null
    try {
        val parts = uri.removePrefix("cloud://").split("/", limit = 3)
        if (parts.size >= 3) {
            val messageId = parts[0].toLong()
            val telegramFileId = parts[1].toInt()
            val fileName = parts[2]
            return Triple(messageId, telegramFileId, fileName)
        }
    } catch (e: Exception) {}
    return null
}

private val fnDateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
private val fnDateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US)

fun parseDateFromFilename(fileName: String): Long? {
    try {
        // Pattern 1: YYYY-MM-DD_HH-MM-SS (e.g. photo_2026-05-29_16-09-11.jpg)
        val pattern1 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})")
        val matcher1 = pattern1.matcher(fileName)
        if (matcher1.find()) {
            val dateStr = "${matcher1.group(1)}-${matcher1.group(2)}-${matcher1.group(3)} ${matcher1.group(4)}:${matcher1.group(5)}:${matcher1.group(6)}"
            val localDateTime = java.time.LocalDateTime.parse(dateStr, fnDateTimeFormatter)
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        // Pattern 2: YYYYMMDD_HHMMSS (e.g. IMG_20260529_204420.jpg)
        val pattern2 = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})")
        val matcher2 = pattern2.matcher(fileName)
        if (matcher2.find()) {
            val dateStr = "${matcher2.group(1)}-${matcher2.group(2)}-${matcher2.group(3)} ${matcher2.group(4)}:${matcher2.group(5)}:${matcher2.group(6)}"
            val localDateTime = java.time.LocalDateTime.parse(dateStr, fnDateTimeFormatter)
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        // Pattern 3: YYYY-MM-DD (e.g. 2026-05-29.jpg)
        val pattern3 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})")
        val matcher3 = pattern3.matcher(fileName)
        if (matcher3.find()) {
            val dateStr = "${matcher3.group(1)}-${matcher3.group(2)}-${matcher3.group(3)}"
            val localDate = java.time.LocalDate.parse(dateStr, fnDateFormatter)
            return localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        // Pattern 4: YYYYMMDD (e.g. 20260529.jpg)
        val pattern4 = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})")
        val matcher4 = pattern4.matcher(fileName)
        if (matcher4.find()) {
            val dateStr = "${matcher4.group(1)}-${matcher4.group(2)}-${matcher4.group(3)}"
            val localDate = java.time.LocalDate.parse(dateStr, fnDateFormatter)
            return localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    } catch (e: Exception) {}
    return null
}


/**
 * Resolves the full+thumbnail file IDs for a given Telegram message by calling GetMessage.
 *
 * KEY DESIGN DECISION: TDLib file IDs are session-scoped. They are assigned fresh each
 * time TDLib initialises (i.e. each app install / fresh login). Any file ID stored in the
 * Room database from a previous session is completely invalid in the current session —
 * calling GetFile() or DownloadFile() with a stale ID will fail silently (TdApi.Error or
 * empty path), causing blank thumbnails.
 *
 * We therefore ALWAYS call GetMessage first. TDLib serves this from its local SQLite store
 * when the message has been loaded before, so the round-trip is fast (< 50 ms on device).
 * Only after obtaining the current-session file IDs do we call DownloadFile.
 */
@Composable
fun rememberCloudThumbnailPath(messageId: Long, isThumbnail: Boolean): String? {
    val context = LocalContext.current
    var localPath by remember(messageId, isThumbnail) { mutableStateOf<String?>(null) }

    LaunchedEffect(messageId, isThumbnail) {
        val chatId = PreferencesManager.getChatId(context)
        if (chatId == 0L) return@LaunchedEffect

        try {
            // 1. Check memory cache (pending write buffer) first
            val pendingPath = ThumbnailWriteBuffer.get(messageId, isThumbnail)
            if (!pendingPath.isNullOrEmpty() && java.io.File(pendingPath).exists()) {
                localPath = pendingPath
                return@LaunchedEffect
            }

            val db = UploadDatabase.getDatabase(context)
            val cachedPhoto = db.cloudDao().findByMessageId(messageId)

            // Fast path: the file is already on disk from a previous download
            val existingPath = if (isThumbnail) cachedPhoto?.localCachedThumbnailPath else cachedPhoto?.localCachedLargePath
            if (!existingPath.isNullOrEmpty() && java.io.File(existingPath).exists()) {
                localPath = existingPath
                return@LaunchedEffect
            }

            // Deduplicate concurrent download requests and execute out-of-lifecycle
            val path = ThumbnailWriteBuffer.getOrDownload(messageId, isThumbnail) {
                // Always resolve current-session file IDs via GetMessage
                suspend fun resolveFileIds(): Pair<Int, Int>? {
                    val result = TdlibManager.sendRequest(TdApi.GetMessage(chatId, messageId))
                    if (result is TdApi.Message) {
                        when (val content = result.content) {
                            is TdApi.MessagePhoto -> {
                                val sizes = content.photo.sizes
                                if (sizes.isNotEmpty()) {
                                    return Pair(sizes.last().photo.id, sizes.first().photo.id)
                                }
                            }
                            is TdApi.MessageDocument -> {
                                val doc = content.document
                                val thumbId = doc.thumbnail?.file?.id ?: doc.document.id
                                return Pair(doc.document.id, thumbId)
                            }
                            is TdApi.MessageVideo -> {
                                val video = content.video
                                val thumbId = video.thumbnail?.file?.id ?: video.video.id
                                return Pair(video.video.id, thumbId)
                            }
                            else -> {}
                        }
                    }
                    return null
                }

                suspend fun performDownload(fileId: Int): String? {
                    if (fileId == 0) return null
                    val fileResult = TdlibManager.sendRequest(TdApi.GetFile(fileId))
                    if (fileResult is TdApi.File) {
                        if (fileResult.local.isDownloadingCompleted) return fileResult.local.path
                        val dlResult = TdlibManager.sendRequest(TdApi.DownloadFile(fileId, 1, 0, 0, false))
                        if (dlResult is TdApi.File) {
                            if (dlResult.local.isDownloadingCompleted) return dlResult.local.path
                            var attempts = 0
                            while (attempts < 15) {
                                delay(1000)
                                val poll = TdlibManager.sendRequest(TdApi.GetFile(fileId))
                                if (poll is TdApi.File) {
                                    if (poll.local.isDownloadingCompleted) return poll.local.path
                                } else if (poll is TdApi.Error) break
                                attempts++
                            }
                        }
                    }
                    return null
                }

                val hasFreshSessionIds = cachedPhoto != null &&
                        cachedPhoto.fileIdCachedAt > TdlibManager.sessionStartTime &&
                        cachedPhoto.telegramFileId != 0

                val (fullFileId, thumbFileId) = if (hasFreshSessionIds && cachedPhoto != null) {
                    Pair(cachedPhoto.telegramFileId, cachedPhoto.telegramThumbnailFileId)
                } else {
                    resolveFileIds() ?: return@getOrDownload null
                }

                val targetFileId = if (isThumbnail) thumbFileId else fullFileId
                val downloadedPath = performDownload(targetFileId)

                if (downloadedPath != null) {
                    ThumbnailWriteBuffer.enqueue(
                        messageId = messageId,
                        isThumbnail = isThumbnail,
                        fullFileId = fullFileId,
                        thumbFileId = thumbFileId,
                        path = downloadedPath
                    )
                }
                downloadedPath
            }

            if (path != null) {
                localPath = path
            }
        } catch (e: Exception) {
            android.util.Log.e("TGPix", "Exception in rememberCloudThumbnailPath: ${e.message}", e)
        }
    }

    return localPath
}

data class CloudPhotoDownloadState(
    val path: String?,
    val progress: Float
)

@Composable
fun rememberCloudPhotoDownloadState(messageId: Long, isThumbnail: Boolean): CloudPhotoDownloadState {
    val context = LocalContext.current
    var localPath by remember(messageId, isThumbnail) { mutableStateOf<String?>(null) }
    var progress by remember(messageId, isThumbnail) { mutableStateOf(0f) }

    LaunchedEffect(messageId, isThumbnail) {
        val chatId = PreferencesManager.getChatId(context)
        if (chatId == 0L) return@LaunchedEffect

        try {
            // 1. Check memory cache (pending write buffer) first
            val pendingPath = ThumbnailWriteBuffer.get(messageId, isThumbnail)
            if (!pendingPath.isNullOrEmpty() && java.io.File(pendingPath).exists()) {
                localPath = pendingPath
                progress = 1.0f
                return@LaunchedEffect
            }

            val db = UploadDatabase.getDatabase(context)
            val cachedPhoto = db.cloudDao().findByMessageId(messageId)

            // Fast path: the file is already on disk
            val existingPath = if (isThumbnail) cachedPhoto?.localCachedThumbnailPath else cachedPhoto?.localCachedLargePath
            if (!existingPath.isNullOrEmpty() && java.io.File(existingPath).exists()) {
                localPath = existingPath
                progress = 1.0f
                return@LaunchedEffect
            }

            // Deduplicate concurrent download requests and execute out-of-lifecycle
            val path = ThumbnailWriteBuffer.getOrDownload(messageId, isThumbnail) {
                // Always resolve current-session file IDs via GetMessage (session-scoped IDs)
                suspend fun resolveFileIds(): Pair<Int, Int>? {
                    val result = TdlibManager.sendRequest(TdApi.GetMessage(chatId, messageId))
                    if (result is TdApi.Message) {
                        when (val content = result.content) {
                            is TdApi.MessagePhoto -> {
                                val sizes = content.photo.sizes
                                if (sizes.isNotEmpty()) {
                                    return Pair(sizes.last().photo.id, sizes.first().photo.id)
                                }
                            }
                            is TdApi.MessageDocument -> {
                                val doc = content.document
                                val thumbId = doc.thumbnail?.file?.id ?: doc.document.id
                                return Pair(doc.document.id, thumbId)
                            }
                            is TdApi.MessageVideo -> {
                                val video = content.video
                                val thumbId = video.thumbnail?.file?.id ?: video.video.id
                                return Pair(video.video.id, thumbId)
                            }
                            else -> {}
                        }
                    }
                    return null
                }

                suspend fun performDownload(fileId: Int): String? {
                    if (fileId == 0) return null
                    val fileResult = TdlibManager.sendRequest(TdApi.GetFile(fileId))
                    if (fileResult is TdApi.File) {
                        if (fileResult.local.isDownloadingCompleted) {
                            progress = 1.0f
                            return fileResult.local.path
                        }
                        val dlResult = TdlibManager.sendRequest(TdApi.DownloadFile(fileId, 1, 0, 0, false))
                        if (dlResult is TdApi.File) {
                            if (dlResult.local.isDownloadingCompleted) {
                                progress = 1.0f
                                return dlResult.local.path
                            }
                            var attempts = 0
                            while (attempts < 60) {
                                delay(500)
                                val poll = TdlibManager.sendRequest(TdApi.GetFile(fileId))
                                if (poll is TdApi.File) {
                                    val localFile = poll.local
                                    val totalSize = poll.size
                                    progress = if (totalSize > 0) {
                                        (localFile.downloadedSize.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    if (localFile.isDownloadingCompleted) {
                                        progress = 1.0f
                                        return localFile.path
                                    }
                                } else if (poll is TdApi.Error) break
                                attempts++
                            }
                        }
                    }
                    return null
                }

                val hasFreshSessionIds = cachedPhoto != null &&
                        cachedPhoto.fileIdCachedAt > TdlibManager.sessionStartTime &&
                        cachedPhoto.telegramFileId != 0

                val (fullFileId, thumbFileId) = if (hasFreshSessionIds && cachedPhoto != null) {
                    Pair(cachedPhoto.telegramFileId, cachedPhoto.telegramThumbnailFileId)
                } else {
                    resolveFileIds() ?: return@getOrDownload null
                }

                val targetFileId = if (isThumbnail) thumbFileId else fullFileId
                val downloadedPath = performDownload(targetFileId)

                if (downloadedPath != null) {
                    ThumbnailWriteBuffer.enqueue(
                        messageId = messageId,
                        isThumbnail = isThumbnail,
                        fullFileId = fullFileId,
                        thumbFileId = thumbFileId,
                        path = downloadedPath
                    )
                }
                downloadedPath
            }

            if (path != null) {
                localPath = path
                progress = 1.0f
            }
        } catch (e: Exception) {
            android.util.Log.e("TGPix", "Exception in rememberCloudPhotoDownloadState: ${e.message}", e)
        }
    }

    return CloudPhotoDownloadState(localPath, progress)
}

object ThumbnailWriteBuffer {
    data class PendingPath(
        val fullFileId: Int,
        val thumbFileId: Int,
        val path: String
    )
    
    private val pendingThumbnails = ConcurrentHashMap<Long, PendingPath>()
    private val pendingLarges = ConcurrentHashMap<Long, PendingPath>()
    private val inFlightDownloads = ConcurrentHashMap<String, Deferred<String?>>()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isLoopStarted = false

    fun get(messageId: Long, isThumbnail: Boolean): String? {
        return if (isThumbnail) pendingThumbnails[messageId]?.path else pendingLarges[messageId]?.path
    }

    fun enqueue(messageId: Long, isThumbnail: Boolean, fullFileId: Int, thumbFileId: Int, path: String) {
        val pending = PendingPath(fullFileId, thumbFileId, path)
        if (isThumbnail) {
            pendingThumbnails[messageId] = pending
        } else {
            pendingLarges[messageId] = pending
        }
    }

    suspend fun getOrDownload(
        messageId: Long,
        isThumbnail: Boolean,
        downloadBlock: suspend () -> String?
    ): String? {
        val cacheKey = "${messageId}_${isThumbnail}"

        // Check RAM buffer first
        get(messageId, isThumbnail)?.let { return it }

        // Check in-flight downloads
        val deferred = inFlightDownloads[cacheKey]
        if (deferred != null) {
            try {
                return deferred.await()
            } catch (_: Exception) {}
        }

        // Run download in the global supervisor scope so it survives Composable disposal
        val newDeferred = scope.async {
            try {
                downloadBlock()
            } catch (e: Exception) {
                android.util.Log.e("ThumbnailWriteBuffer", "Download block failed for msg $messageId", e)
                null
            }
        }

        val existing = inFlightDownloads.putIfAbsent(cacheKey, newDeferred)
        val activeDeferred = existing ?: newDeferred

        return try {
            activeDeferred.await()
        } finally {
            inFlightDownloads.remove(cacheKey, activeDeferred)
        }
    }

    fun startTimeoutLoop(context: android.content.Context) {
        synchronized(this) {
            if (isLoopStarted) return
            isLoopStarted = true
        }
        scope.launch {
            while (isActive) {
                delay(2000)
                try {
                    flush(context.applicationContext)
                } catch (e: Exception) {
                    android.util.Log.e("ThumbnailWriteBuffer", "Failed to flush thumbnail updates", e)
                }
            }
        }
    }

    private suspend fun flush(context: android.content.Context) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (pendingThumbnails.isEmpty() && pendingLarges.isEmpty()) return@withContext

        val thumbsSnapshot = pendingThumbnails.toMap()
        val largesSnapshot = pendingLarges.toMap()

        thumbsSnapshot.keys.forEach { pendingThumbnails.remove(it) }
        largesSnapshot.keys.forEach { pendingLarges.remove(it) }

        val db = UploadDatabase.getDatabase(context)
        try {
            val now = System.currentTimeMillis()
            val thumbUpdates = thumbsSnapshot.map { (id, data) ->
                dev.ssjvirtually.tgpix.storage.ThumbnailPathUpdate(
                    messageId = id,
                    fileId = data.fullFileId,
                    thumbFileId = data.thumbFileId,
                    cachedAt = now,
                    path = data.path
                )
            }
            val largeUpdates = largesSnapshot.map { (id, data) ->
                dev.ssjvirtually.tgpix.storage.ThumbnailPathUpdate(
                    messageId = id,
                    fileId = data.fullFileId,
                    thumbFileId = data.thumbFileId,
                    cachedAt = now,
                    path = data.path
                )
            }

            if (thumbUpdates.isNotEmpty()) {
                db.cloudDao().batchUpdateThumbnailPaths(thumbUpdates)
            }
            if (largeUpdates.isNotEmpty()) {
                db.cloudDao().batchUpdateLargePaths(largeUpdates)
            }
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailWriteBuffer", "Transaction update failed", e)
            // Re-enqueue in case of failure
            thumbsSnapshot.forEach { (id, data) -> pendingThumbnails.putIfAbsent(id, data) }
            largesSnapshot.forEach { (id, data) -> pendingLarges.putIfAbsent(id, data) }
        }
    }
}
