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
            val db = UploadDatabase.getDatabase(context)
            val cachedPhoto = db.cloudDao().findByMessageId(messageId)

            // Fast path: the file is already on disk from a previous download
            val existingPath = if (isThumbnail) cachedPhoto?.localCachedThumbnailPath else cachedPhoto?.localCachedLargePath
            if (!existingPath.isNullOrEmpty() && java.io.File(existingPath).exists()) {
                localPath = existingPath
                return@LaunchedEffect
            }

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

            val (fullFileId, thumbFileId) = resolveFileIds() ?: return@LaunchedEffect
            val targetFileId = if (isThumbnail) thumbFileId else fullFileId
            val path = performDownload(targetFileId)

            if (path != null) {
                localPath = path
                if (cachedPhoto != null) {
                    val updated = if (isThumbnail) {
                        cachedPhoto.copy(
                            telegramFileId = fullFileId,
                            telegramThumbnailFileId = thumbFileId,
                            fileIdCachedAt = System.currentTimeMillis(),
                            localCachedThumbnailPath = path
                        )
                    } else {
                        cachedPhoto.copy(
                            telegramFileId = fullFileId,
                            telegramThumbnailFileId = thumbFileId,
                            fileIdCachedAt = System.currentTimeMillis(),
                            localCachedLargePath = path
                        )
                    }
                    db.cloudDao().insert(updated)
                }
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
            val db = UploadDatabase.getDatabase(context)
            val cachedPhoto = db.cloudDao().findByMessageId(messageId)

            // Fast path: the file is already on disk
            val existingPath = if (isThumbnail) cachedPhoto?.localCachedThumbnailPath else cachedPhoto?.localCachedLargePath
            if (!existingPath.isNullOrEmpty() && java.io.File(existingPath).exists()) {
                localPath = existingPath
                progress = 1.0f
                return@LaunchedEffect
            }

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

            val (fullFileId, thumbFileId) = resolveFileIds() ?: return@LaunchedEffect
            val targetFileId = if (isThumbnail) thumbFileId else fullFileId
            val path = performDownload(targetFileId)

            if (path != null) {
                localPath = path
                progress = 1.0f
                if (cachedPhoto != null) {
                    val updated = if (isThumbnail) {
                        cachedPhoto.copy(
                            telegramFileId = fullFileId,
                            telegramThumbnailFileId = thumbFileId,
                            fileIdCachedAt = System.currentTimeMillis(),
                            localCachedThumbnailPath = path
                        )
                    } else {
                        cachedPhoto.copy(
                            telegramFileId = fullFileId,
                            telegramThumbnailFileId = thumbFileId,
                            fileIdCachedAt = System.currentTimeMillis(),
                            localCachedLargePath = path
                        )
                    }
                    db.cloudDao().insert(updated)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TGPix", "Exception in rememberCloudPhotoDownloadState: ${e.message}", e)
        }
    }

    return CloudPhotoDownloadState(localPath, progress)
}
