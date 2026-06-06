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

fun parseDateFromFilename(fileName: String): Long? {
    try {
        // Pattern 1: YYYY-MM-DD_HH-MM-SS (e.g. photo_2026-05-29_16-09-11.jpg)
        val pattern1 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})")
        val matcher1 = pattern1.matcher(fileName)
        if (matcher1.find()) {
            val dateStr = "${matcher1.group(1)}-${matcher1.group(2)}-${matcher1.group(3)} ${matcher1.group(4)}:${matcher1.group(5)}:${matcher1.group(6)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }

        // Pattern 2: YYYYMMDD_HHMMSS (e.g. IMG_20260529_204420.jpg)
        val pattern2 = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})")
        val matcher2 = pattern2.matcher(fileName)
        if (matcher2.find()) {
            val dateStr = "${matcher2.group(1)}-${matcher2.group(2)}-${matcher2.group(3)} ${matcher2.group(4)}:${matcher2.group(5)}:${matcher2.group(6)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }

        // Pattern 3: YYYY-MM-DD (e.g. 2026-05-29.jpg)
        val pattern3 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})")
        val matcher3 = pattern3.matcher(fileName)
        if (matcher3.find()) {
            val dateStr = "${matcher3.group(1)}-${matcher3.group(2)}-${matcher3.group(3)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }

        // Pattern 4: YYYYMMDD (e.g. 20260529.jpg)
        val pattern4 = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})")
        val matcher4 = pattern4.matcher(fileName)
        if (matcher4.find()) {
            val dateStr = "${matcher4.group(1)}-${matcher4.group(2)}-${matcher4.group(3)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }
    } catch (e: Exception) {}
    return null
}


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
            
            // Check if we already have the local path cached in DB and it exists on disk
            val existingPath = if (isThumbnail) cachedPhoto?.localCachedThumbnailPath else cachedPhoto?.localCachedLargePath
            if (!existingPath.isNullOrEmpty() && java.io.File(existingPath).exists()) {
                localPath = existingPath
                return@LaunchedEffect
            }
            
            suspend fun performDownload(fileId: Int): String? {
                val fileResult = TdlibManager.sendRequest(TdApi.GetFile(fileId))
                if (fileResult is TdApi.File) {
                    if (fileResult.local.isDownloadingCompleted) {
                        return fileResult.local.path
                    }
                    
                    val downloadResult = TdlibManager.sendRequest(TdApi.DownloadFile(fileId, 1, 0, 0, false))
                    if (downloadResult is TdApi.File) {
                        if (downloadResult.local.isDownloadingCompleted) {
                            return downloadResult.local.path
                        }
                        var attempts = 0
                        while (attempts < 15) {
                            delay(1000)
                            val pollResult = TdlibManager.sendRequest(TdApi.GetFile(fileId))
                            if (pollResult is TdApi.File) {
                                if (pollResult.local.isDownloadingCompleted) {
                                    return pollResult.local.path
                                }
                            } else if (pollResult is TdApi.Error) {
                                break
                            }
                            attempts++
                        }
                    }
                }
                return null
            }

            var targetFileId = 0
            val isFresh = cachedPhoto != null && 
                    cachedPhoto.telegramFileId != 0 && 
                    (System.currentTimeMillis() - cachedPhoto.fileIdCachedAt < 30 * 60 * 1000L)
            
            if (isFresh && cachedPhoto != null) {
                targetFileId = if (isThumbnail) cachedPhoto.telegramThumbnailFileId else cachedPhoto.telegramFileId
                android.util.Log.d("TGPix", "Using cached fresh targetFileId=$targetFileId for messageId=$messageId")
            } else {
                android.util.Log.d("TGPix", "Cache stale or missing for messageId=$messageId. Fetching fresh message...")
                val messageResult = TdlibManager.sendRequest(TdApi.GetMessage(chatId, messageId))
                if (messageResult is TdApi.Message) {
                    val content = messageResult.content
                    var fileId = 0
                    var thumbFileId = 0
                    
                    if (content is TdApi.MessagePhoto) {
                        val sizes = content.photo.sizes
                        if (sizes.isNotEmpty()) {
                            fileId = sizes.last().photo.id
                            thumbFileId = sizes.first().photo.id
                        }
                    } else if (content is TdApi.MessageDocument) {
                        val doc = content.document
                        fileId = doc.document.id
                        thumbFileId = doc.thumbnail?.file?.id ?: doc.document.id
                    }
                    
                    if (fileId != 0) {
                        targetFileId = if (isThumbnail) thumbFileId else fileId
                        // Update cached photo in database
                        if (cachedPhoto != null) {
                            db.cloudDao().insert(
                                cachedPhoto.copy(
                                    telegramFileId = fileId,
                                    telegramThumbnailFileId = thumbFileId,
                                    fileIdCachedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
            
            var path: String? = null
            if (targetFileId != 0) {
                path = performDownload(targetFileId)
            }
            
            // Self-healing fallback: if download fails, refresh file ID and retry
            if (path == null) {
                android.util.Log.d("TGPix", "Download failed with cached ID. Fetching fresh message for messageId=$messageId...")
                val messageResult = TdlibManager.sendRequest(TdApi.GetMessage(chatId, messageId))
                if (messageResult is TdApi.Message) {
                    val content = messageResult.content
                    var fileId = 0
                    var thumbFileId = 0
                    
                    if (content is TdApi.MessagePhoto) {
                        val sizes = content.photo.sizes
                        if (sizes.isNotEmpty()) {
                            fileId = sizes.last().photo.id
                            thumbFileId = sizes.first().photo.id
                        }
                    } else if (content is TdApi.MessageDocument) {
                        val doc = content.document
                        fileId = doc.document.id
                        thumbFileId = doc.thumbnail?.file?.id ?: doc.document.id
                    }
                    
                    if (fileId != 0) {
                        targetFileId = if (isThumbnail) thumbFileId else fileId
                        // Update cached photo in database
                        if (cachedPhoto != null) {
                            db.cloudDao().insert(
                                cachedPhoto.copy(
                                    telegramFileId = fileId,
                                    telegramThumbnailFileId = thumbFileId,
                                    fileIdCachedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        path = performDownload(targetFileId)
                    }
                }
            }
            
            if (path != null) {
                localPath = path
                // Save the successfully downloaded path to Room
                if (cachedPhoto != null) {
                    val updated = if (isThumbnail) {
                        cachedPhoto.copy(localCachedThumbnailPath = path)
                    } else {
                        cachedPhoto.copy(localCachedLargePath = path)
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
