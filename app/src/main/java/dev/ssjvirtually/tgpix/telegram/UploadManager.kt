package dev.ssjvirtually.tgpix.telegram

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

object UploadManager {
    suspend fun uploadPhoto(
        context: Context,
        photo: LocalPhoto,
        chatId: Long,
        isHd: Boolean
    ): TdApi.Object {
        val fileName = photo.name
        val uploadCacheDir = File(context.cacheDir, "tgpix_uploads")
        uploadCacheDir.mkdirs()
        val tempFile = File(uploadCacheDir, fileName)
        
        try {
            // 1. Copy photo from system MediaStore content resolver to internally-accessible cache file
            try {
                val uri = Uri.parse(photo.uri)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return TdApi.Error(400, "Failed to copy photo to local cache: ${e.message}")
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return TdApi.Error(400, "Failed to copy photo to local cache (file is empty)")
            }

            // 2. Perform on-device, privacy-first AI image classification before upload
            val aiTags = classifyImage(context, tempFile.absolutePath)

            // 3. Perform TDLib message transmission under a 2-minute safety timeout
            val result = withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
                        
                        // Extract EXIF metadata using androidx.exifinterface
                        var width = 0
                        var height = 0
                        var make = "Unknown"
                        var model = "Unknown"
                        var lens = "Unknown"
                        var aperture = "Unknown"
                        var shutter = "Unknown"
                        var iso = "Unknown"
                        var focal = "Unknown"
                        var flashStr = "Unknown"
                        var flashFired = false
                        
                        try {
                            val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)
                            width = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
                            height = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0)
                            
                            make = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE)?.trim() ?: "Unknown"
                            model = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)?.trim() ?: "Unknown"
                            lens = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL)?.trim() ?: "Unknown"
                            
                            val apertureVal = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0.0)
                            aperture = if (apertureVal > 0.0) "f/$apertureVal" else "Unknown"
                            
                            val shutterVal = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                            shutter = if (shutterVal > 0.0) {
                                if (shutterVal < 1.0) {
                                    val inverse = Math.round(1.0 / shutterVal)
                                    "1/$inverse sec ($shutterVal)"
                                } else {
                                    "$shutterVal sec"
                                }
                            } else {
                                "Unknown"
                            }
                            
                            val isoVal = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                            iso = if (isoVal > 0) "$isoVal" else "Unknown"
                            
                            val focalVal = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                            focal = if (focalVal > 0.0) "${focalVal}mm" else "Unknown"
                            
                            val flashVal = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_FLASH, -1)
                            if (flashVal >= 0) {
                                flashFired = (flashVal and 1) == 1
                                flashStr = if (flashFired) "Flash fired ($flashVal)" else "Flash did not fire ($flashVal)"
                            }
                        } catch (exifEx: Exception) {
                            exifEx.printStackTrace()
                        }

                        // Dynamically generate search hashtags
                        val tags = mutableListOf<String>()
                        
                        // Inject our on-device AI classification tags!
                        tags.addAll(aiTags)

                        if (make != "Unknown" && make.isNotEmpty()) {
                            val makeTag = "#" + make.lowercase().replace("\\s+".toRegex(), "_").replace("[^a-z0-9_]".toRegex(), "")
                            tags.add(makeTag)
                        }
                        if (model != "Unknown" && model.isNotEmpty()) {
                            val modelTag = "#" + model.lowercase().replace("\\s+".toRegex(), "_").replace("[^a-z0-9_]".toRegex(), "")
                            tags.add(modelTag)
                        }
                        val ext = photo.name.substringAfterLast('.', "").lowercase()
                        if (ext.isNotEmpty()) {
                            tags.add("#$ext")
                        }
                        try {
                            val takenCal = java.util.Calendar.getInstance()
                            takenCal.timeInMillis = photo.dateTaken
                            val yr = takenCal.get(java.util.Calendar.YEAR)
                            val mth = takenCal.get(java.util.Calendar.MONTH) + 1
                            tags.add("#year_$yr")
                            tags.add("#month_${yr}_$mth")
                        } catch (e: Exception) {}
                        if (flashFired) {
                            tags.add("#flash_used")
                        }
                        val tagsLine = tags.distinct().joinToString(" ")

                        val formattedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(photo.dateTaken))
                        val addedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                        val formattedSize = String.format(java.util.Locale.US, "%.1f MB", photo.size / (1024.0 * 1024.0))
                        val dimensions = if (width > 0 && height > 0) "$width x $height" else "Unknown"
                        val escapedName = photo.name.replace("\\", "\\\\").replace("\"", "\\\"")
                        
                        // Prepare tags for JSON storage (comma separated)
                        val tagsJsonArray = tags.distinct().joinToString(",") { "\"$it\"" }
                        val metadataJson = """{"id":${photo.id},"name":"$escapedName","size":${photo.size},"dateTaken":${photo.dateTaken},"tags":[$tagsJsonArray]}"""

                        val captionText = """
                            📷 **Photo Metadata**
                            📁 File: ${photo.name}
                            📏 Size: $formattedSize
                            📐 Dimensions: $dimensions
                            📅 Taken: $formattedDate
                            📅 Added: $addedDate

                            📸 **Camera Info**
                            🏭 Make: $make
                            📱 Model: $model
                            🔍 Lens: $lens

                            ⚙️ **Technical**
                            🕳️ Aperture: $aperture
                            ⚡ Shutter: $shutter
                            🎛️ ISO: $iso
                            🔭 Focal: $focal
                            💡 Flash: $flashStr

                            🏷️ **Tags**
                            $tagsLine
                            
                            ---
                            #tgpix_metadata $metadataJson
                        """.trimIndent()

                        val inputMessageContent = if (isHd) {
                            TdApi.InputMessageDocument().apply {
                                this.document = inputFile
                                caption = TdApi.FormattedText(captionText, emptyArray())
                            }
                        } else {
                            TdApi.InputMessagePhoto().apply {
                                this.photo = inputFile
                                caption = TdApi.FormattedText(captionText, emptyArray())
                            }
                        }

                        val request = TdApi.SendMessage().apply {
                            this.chatId = chatId
                            this.inputMessageContent = inputMessageContent
                        }

                        TdlibManager.getClient().send(request) { result ->
                            if (result is TdApi.Message) {
                                // Register continuation to resume when UpdateMessageSendSucceeded fires
                                TdlibManager.pendingUploads[result.id] = continuation
                                val modeStr = if (isHd) "HD Lossless" else "Compressed"
                                TdlibManager.addLog("Upload queued for '${fileName}' ($modeStr) (Msg ID: ${result.id}). Sending to Telegram...")
                            } else if (result is TdApi.Error) {
                                continuation.resume(result)
                            } else {
                                continuation.resume(TdApi.Error(500, "Unexpected response from TDLib: ${result::class.java.simpleName}"))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        continuation.resume(TdApi.Error(500, e.message ?: "Unknown upload initialization error"))
                    }
                }
            }
            return result ?: TdApi.Error(408, "Upload timed out after 2 minutes")
        } finally {
            // Clean up the temp file from the cache directory immediately when the suspension finishes
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                    TdlibManager.addLog("Cleaned up temp upload file: ${tempFile.name}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun classifyImage(context: Context, cacheFilePath: String): List<String> = suspendCancellableCoroutine { continuation ->
        try {
            val file = File(cacheFilePath)
            if (!file.exists()) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val detectedTags = labels
                        .filter { it.confidence >= 0.60f } // 60% confidence threshold
                        .map { label ->
                            "#" + label.text.lowercase()
                                .replace("\\s+".toRegex(), "_")
                                .replace("[^a-z0-9_]".toRegex(), "")
                        }
                    continuation.resume(detectedTags)
                }
                .addOnFailureListener { _ ->
                    // Fail silently to keep uploads moving
                    continuation.resume(emptyList())
                }
        } catch (e: Exception) {
            e.printStackTrace()
            continuation.resume(emptyList())
        }
    }

    suspend fun sharePhotosToTelegramChat(context: Context, photos: List<LocalPhoto>, targetChatId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            var allSuccess = true
            val uploadCacheDir = File(context.cacheDir, "tgpix_temp_share")
            uploadCacheDir.mkdirs()
            
            val chunks = photos.chunked(10)
            for (chunk in chunks) {
                val tempFiles = mutableListOf<File>()
                val inputMessageContents = mutableListOf<TdApi.InputMessageContent>()
                var prepareSuccess = true
                
                for (photo in chunk) {
                    val tempFile = File(uploadCacheDir, photo.name)
                    tempFiles.add(tempFile)
                    try {
                        if (photo.uri.startsWith("cloud://")) {
                            val parts = photo.uri.substringAfter("cloud://").split("/")
                            val fileId = parts[1].toInt()
                            
                            var fileObj = suspendCancellableCoroutine<TdApi.File?> { cont ->
                                TdlibManager.getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { res ->
                                    cont.resume(res as? TdApi.File)
                                }
                            }
                            
                            var attempts = 0
                            while (fileObj != null && !fileObj.local.isDownloadingCompleted && attempts < 15) {
                                delay(1000)
                                attempts++
                                fileObj = suspendCancellableCoroutine { cont ->
                                    TdlibManager.getClient().send(TdApi.GetFile(fileId)) { res ->
                                        cont.resume(res as? TdApi.File)
                                    }
                                }
                            }
                            
                            if (fileObj != null && fileObj.local.isDownloadingCompleted && fileObj.local.path.isNotEmpty()) {
                                val downloadedFile = File(fileObj.local.path)
                                downloadedFile.copyTo(tempFile, overwrite = true)
                            }
                        } else {
                            val uri = Uri.parse(photo.uri)
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                        
                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            prepareSuccess = false
                            break
                        }
                        
                        val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
                        val inputMessageContent = TdApi.InputMessagePhoto().apply {
                            this.photo = inputFile
                            caption = TdApi.FormattedText("Shared via TGPix", emptyArray())
                        }
                        inputMessageContents.add(inputMessageContent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        prepareSuccess = false
                        break
                    }
                }
                
                if (!prepareSuccess || inputMessageContents.size != chunk.size) {
                    allSuccess = false
                    for (f in tempFiles) {
                        try { if (f.exists()) f.delete() } catch (e: Exception) {}
                    }
                    continue
                }
                
                try {
                    if (inputMessageContents.size == 1) {
                        val request = TdApi.SendMessage().apply {
                            this.chatId = targetChatId
                            this.inputMessageContent = inputMessageContents.first()
                        }
                        val res = suspendCancellableCoroutine<TdApi.Object> { cont ->
                            TdlibManager.getClient().send(request) { result ->
                                if (result is TdApi.Message) {
                                    TdlibManager.pendingUploads[result.id] = cont
                                } else {
                                    cont.resume(result)
                                }
                            }
                        }
                        if (res is TdApi.Error) {
                            allSuccess = false
                        }
                    } else {
                        val request = TdApi.SendMessageAlbum().apply {
                            this.chatId = targetChatId
                            this.inputMessageContents = inputMessageContents.toTypedArray()
                        }
                        val messagesResult = suspendCancellableCoroutine<TdApi.Object> { cont ->
                            TdlibManager.getClient().send(request) { result ->
                                cont.resume(result)
                            }
                        }
                        
                        if (messagesResult is TdApi.Messages) {
                            coroutineScope {
                                val jobs = messagesResult.messages.map { msg ->
                                    async {
                                        suspendCancellableCoroutine<TdApi.Object> { cont ->
                                            TdlibManager.pendingUploads[msg.id] = cont
                                        }
                                    }
                                }
                                val results = jobs.map { it.await() }
                                if (results.any { it is TdApi.Error }) {
                                    allSuccess = false
                                }
                            }
                        } else {
                            allSuccess = false
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    allSuccess = false
                } finally {
                    for (f in tempFiles) {
                        try { if (f.exists()) f.delete() } catch (e: Exception) {}
                    }
                }
            }
            allSuccess
        }
    }

    suspend fun sharePhotosToSystem(context: Context, photos: List<LocalPhoto>) {
        withContext(Dispatchers.IO) {
            val shareCacheDir = File(context.cacheDir, "tgpix_temp_share")
            shareCacheDir.mkdirs()
            
            val hasCloudPhotos = photos.any { it.uri.startsWith("cloud://") }
            if (hasCloudPhotos) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Preparing cloud photos for sharing...", Toast.LENGTH_SHORT).show()
                }
            }
            
            val shareUris = ArrayList<android.net.Uri>()
            for (photo in photos) {
                if (photo.uri.startsWith("cloud://")) {
                    try {
                        val parts = photo.uri.substringAfter("cloud://").split("/")
                        val fileId = parts[1].toInt()
                        
                        var fileObj = suspendCancellableCoroutine<TdApi.File?> { cont ->
                            TdlibManager.getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { res ->
                                cont.resume(res as? TdApi.File)
                            }
                        }
                        
                        var attempts = 0
                        while (fileObj != null && !fileObj.local.isDownloadingCompleted && attempts < 15) {
                            delay(1000)
                            attempts++
                            fileObj = suspendCancellableCoroutine { cont ->
                                TdlibManager.getClient().send(TdApi.GetFile(fileId)) { res ->
                                    cont.resume(res as? TdApi.File)
                                }
                            }
                        }
                        
                        if (fileObj != null && fileObj.local.isDownloadingCompleted && fileObj.local.path.isNotEmpty()) {
                            val tempFile = File(shareCacheDir, photo.name)
                            val downloadedFile = File(fileObj.local.path)
                            downloadedFile.copyTo(tempFile, overwrite = true)
                            
                            val authority = "${context.packageName}.fileprovider"
                            val uri = FileProvider.getUriForFile(context, authority, tempFile)
                            shareUris.add(uri)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    shareUris.add(android.net.Uri.parse(photo.uri))
                }
            }
            
            if (shareUris.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Photos"))
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No photos could be prepared for sharing.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
