package com.example.tguploader.ui.utils

import androidx.compose.runtime.*
import com.example.tguploader.storage.LocalPhoto
import com.example.tguploader.telegram.TdlibManager
import org.drinkless.tdlib.TdApi

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
fun rememberCloudThumbnailPath(fileId: Int): String? {
    var localPath by remember(fileId) { mutableStateOf<String?>(null) }
    
    LaunchedEffect(fileId) {
        TdlibManager.getClient().send(TdApi.GetFile(fileId)) { result ->
            if (result is TdApi.File) {
                if (result.local.isDownloadingCompleted) {
                    localPath = result.local.path
                } else if (!result.local.isDownloadingActive) {
                    TdlibManager.getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { downloadResult ->
                        if (downloadResult is TdApi.File) {
                            // Download started
                        }
                    }
                }
            }
        }
    }
    
    LaunchedEffect(fileId, localPath) {
        if (localPath == null) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                TdlibManager.getClient().send(TdApi.GetFile(fileId)) { result ->
                    if (result is TdApi.File && result.local.isDownloadingCompleted) {
                        localPath = result.local.path
                    }
                }
                if (localPath != null) break
            }
        }
    }
    
    return localPath
}
