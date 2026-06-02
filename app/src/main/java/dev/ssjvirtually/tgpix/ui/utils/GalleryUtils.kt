package dev.ssjvirtually.tgpix.ui.utils

import androidx.compose.runtime.*
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import androidx.compose.ui.platform.LocalContext

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
            android.util.Log.d("TGPix", "Requesting messageId=$messageId (isThumbnail=$isThumbnail) from chatId=$chatId")
            val messageResult = TdlibManager.sendRequest(TdApi.GetMessage(chatId, messageId))
            if (messageResult is TdApi.Message) {
                val content = messageResult.content
                var targetFileId = 0
                
                if (content is TdApi.MessagePhoto) {
                    val sizes = content.photo.sizes
                    if (sizes.isNotEmpty()) {
                        targetFileId = if (isThumbnail) {
                            sizes.first().photo.id
                        } else {
                            sizes.last().photo.id
                        }
                    }
                } else if (content is TdApi.MessageDocument) {
                    val doc = content.document
                    targetFileId = if (isThumbnail) {
                        doc.thumbnail?.file?.id ?: doc.document.id
                    } else {
                        doc.document.id
                    }
                }
                
                if (targetFileId != 0) {
                    val fileResult = TdlibManager.sendRequest(TdApi.GetFile(targetFileId))
                    if (fileResult is TdApi.File) {
                        if (fileResult.local.isDownloadingCompleted) {
                            localPath = fileResult.local.path
                            android.util.Log.d("TGPix", "File already downloaded: $localPath")
                        } else {
                            android.util.Log.d("TGPix", "Starting download for targetFileId=$targetFileId")
                            val downloadResult = TdlibManager.sendRequest(TdApi.DownloadFile(targetFileId, 1, 0, 0, false))
                            if (downloadResult is TdApi.File) {
                                var downloaded = false
                                while (!downloaded) {
                                    delay(1000)
                                    val pollResult = TdlibManager.sendRequest(TdApi.GetFile(targetFileId))
                                    if (pollResult is TdApi.File && pollResult.local.isDownloadingCompleted) {
                                        localPath = pollResult.local.path
                                        downloaded = true
                                        android.util.Log.d("TGPix", "Download completed: $localPath")
                                    } else if (pollResult is TdApi.Error) {
                                        android.util.Log.e("TGPix", "Error polling file: code=${pollResult.code} message=${pollResult.message}")
                                        break
                                    }
                                }
                            } else if (downloadResult is TdApi.Error) {
                                android.util.Log.e("TGPix", "DownloadFile failed for targetFileId=$targetFileId: code=${downloadResult.code} message=${downloadResult.message}")
                            }
                        }
                    } else if (fileResult is TdApi.Error) {
                        android.util.Log.e("TGPix", "GetFile failed for targetFileId=$targetFileId: code=${fileResult.code} message=${fileResult.message}")
                    }
                } else {
                    android.util.Log.w("TGPix", "No valid targetFileId found in message content")
                }
            } else if (messageResult is TdApi.Error) {
                android.util.Log.e("TGPix", "GetMessage failed for messageId=$messageId: code=${messageResult.code} message=${messageResult.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("TGPix", "Exception in rememberCloudThumbnailPath: ${e.message}", e)
        }
    }
    
    return localPath
}
