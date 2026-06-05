package dev.ssjvirtually.tgpix.telegram

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

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
        var thumbFile: File? = null
        
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
                            val thumbResult = createThumbnail(context, tempFile)
                            TdApi.InputMessageDocument().apply {
                                this.document = inputFile
                                if (thumbResult != null) {
                                    thumbFile = thumbResult.file
                                    this.thumbnail = TdApi.InputThumbnail(
                                        TdApi.InputFileLocal(thumbResult.file.absolutePath),
                                        thumbResult.width,
                                        thumbResult.height
                                    )
                                }
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
                                TdlibManager.registerPendingUpload(result.id) { res ->
                                    continuation.resume(res)
                                }
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
            try {
                val f = thumbFile
                if (f != null && f.exists()) {
                    f.delete()
                    TdlibManager.addLog("Cleaned up temp thumbnail file: ${f.name}")
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
            
            // Explicitly request ARGB_8888 configuration to avoid hardware/RGB_565 decoding on some devices
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeFile(cacheFilePath, options)
            if (bitmap == null) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            
            // Convert to ARGB_8888 if the decoder ignored the config preference
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (converted != null) {
                    bitmap.recycle()
                    bitmap = converted
                }
            }
            
            val image = InputImage.fromBitmap(bitmap, 0)
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    try { bitmap.recycle() } catch (e: Exception) {}
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
                    try { bitmap.recycle() } catch (e: Exception) {}
                    // Fail silently to keep uploads moving
                    continuation.resume(emptyList())
                }
        } catch (e: Exception) {
            e.printStackTrace()
            continuation.resume(emptyList())
        }
    }

    private suspend fun downloadCloudPhoto(context: Context, cloudUri: String): File? {
        val parts = cloudUri.substringAfter("cloud://").split("/")
        if (parts.size < 3) return null
        val messageId = parts[0].toLong()
        val vaultChatId = PreferencesManager.getChatId(context)
        if (vaultChatId == 0L) return null

        try {
            val messageResult = suspendCancellableCoroutine<TdApi.Object> { cont ->
                TdlibManager.getClient().send(TdApi.GetMessage(vaultChatId, messageId)) { res ->
                    cont.resume(res)
                }
            }
            if (messageResult is TdApi.Message) {
                val content = messageResult.content
                var targetFileId = 0
                if (content is TdApi.MessagePhoto) {
                    val sizes = content.photo.sizes
                    if (sizes.isNotEmpty()) {
                        targetFileId = sizes.last().photo.id
                    }
                } else if (content is TdApi.MessageDocument) {
                    targetFileId = content.document.document.id
                }

                if (targetFileId != 0) {
                    var fileObj = suspendCancellableCoroutine<TdApi.File?> { cont ->
                        TdlibManager.getClient().send(TdApi.DownloadFile(targetFileId, 1, 0, 0, false)) { res ->
                            cont.resume(res as? TdApi.File)
                        }
                    }
                    var attempts = 0
                    while (fileObj != null && !fileObj.local.isDownloadingCompleted && attempts < 15) {
                        delay(1000)
                        attempts++
                        fileObj = suspendCancellableCoroutine { cont ->
                            TdlibManager.getClient().send(TdApi.GetFile(targetFileId)) { res ->
                                cont.resume(res as? TdApi.File)
                            }
                        }
                    }
                    if (fileObj != null && fileObj.local.isDownloadingCompleted && fileObj.local.path.isNotEmpty()) {
                        val downloadedFile = File(fileObj.local.path)
                        if (downloadedFile.exists() && downloadedFile.length() > 0L) {
                            return downloadedFile
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    suspend fun sharePhotosToTelegramChat(context: Context, photos: List<LocalPhoto>, targetChatId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            var allSuccess = true
            val uploadCacheDir = File(context.cacheDir, "tgpix_temp_share")
            uploadCacheDir.mkdirs()
            
            val isHd = PreferencesManager.isHdMode(context)
            
            if (isHd) {
                // Send each photo individually as a Document
                for (photo in photos) {
                    val tempFile = File(uploadCacheDir, photo.name)
                    var thumbFile: File? = null
                    try {
                        if (photo.uri.startsWith("cloud://")) {
                            val downloaded = downloadCloudPhoto(context, photo.uri)
                            if (downloaded != null && downloaded.exists()) {
                                downloaded.copyTo(tempFile, overwrite = true)
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
                            allSuccess = false
                            continue
                        }
                        
                        val thumbResult = createThumbnail(context, tempFile)
                        val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
                        val inputMessageContent = TdApi.InputMessageDocument().apply {
                            this.document = inputFile
                            if (thumbResult != null) {
                                thumbFile = thumbResult.file
                                this.thumbnail = TdApi.InputThumbnail(
                                    TdApi.InputFileLocal(thumbResult.file.absolutePath),
                                    thumbResult.width,
                                    thumbResult.height
                                )
                            }
                            caption = TdApi.FormattedText("Shared via TGPix", emptyArray())
                        }
                        
                        val request = TdApi.SendMessage().apply {
                            this.chatId = targetChatId
                            this.inputMessageContent = inputMessageContent
                        }
                        
                        val res = suspendCancellableCoroutine<TdApi.Object> { cont ->
                            TdlibManager.getClient().send(request) { result ->
                                if (result is TdApi.Message) {
                                    TdlibManager.registerPendingUpload(result.id) { res ->
                                        cont.resume(res)
                                    }
                                } else {
                                    cont.resume(result)
                                }
                            }
                        }
                        
                        if (res is TdApi.Error) {
                            allSuccess = false
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        allSuccess = false
                    } finally {
                        try { if (tempFile.exists()) tempFile.delete() } catch (e: Exception) {}
                        try {
                            val f = thumbFile
                            if (f != null && f.exists()) {
                                f.delete()
                            }
                        } catch (e: Exception) {}
                    }
                }
            } else {
                // Send photos as compressed photo album chunks
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
                                val downloaded = downloadCloudPhoto(context, photo.uri)
                                if (downloaded != null && downloaded.exists()) {
                                    downloaded.copyTo(tempFile, overwrite = true)
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
                                        TdlibManager.registerPendingUpload(result.id) { res ->
                                            cont.resume(res)
                                        }
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
                            val deferreds = mutableListOf<CompletableDeferred<TdApi.Object>>()
                            val messagesResult = suspendCancellableCoroutine<TdApi.Object> { cont ->
                                TdlibManager.getClient().send(request) { result ->
                                    if (result is TdApi.Messages) {
                                        for (msg in result.messages) {
                                            val deferred = CompletableDeferred<TdApi.Object>()
                                            deferreds.add(deferred)
                                            TdlibManager.registerPendingUpload(msg.id) { res ->
                                                deferred.complete(res)
                                            }
                                        }
                                    }
                                    cont.resume(result)
                                }
                            }
                            
                            if (messagesResult is TdApi.Messages) {
                                val results = deferreds.map { it.await() }
                                if (results.any { it is TdApi.Error }) {
                                    allSuccess = false
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
                        val downloaded = downloadCloudPhoto(context, photo.uri)
                        if (downloaded != null && downloaded.exists()) {
                            val tempFile = File(shareCacheDir, photo.name)
                            downloaded.copyTo(tempFile, overwrite = true)
                            
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

    private data class ThumbnailResult(
        val file: File,
        val width: Int,
        val height: Int
    )

    private fun createThumbnail(context: Context, imageFile: File): ThumbnailResult? {
        try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            
            val originalWidth = bounds.outWidth
            val originalHeight = bounds.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return null

            var rotationAngle = 0f
            try {
                val exif = androidx.exifinterface.media.ExifInterface(imageFile.absolutePath)
                val orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
                when (orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotationAngle = 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotationAngle = 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotationAngle = 270f
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val maxTargetSize = 320
            val sampleSize = calculateInSampleSize(bounds, maxTargetSize, maxTargetSize)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options) ?: return null

            val scaledBitmap = scaleAndRotateBitmap(bitmap, maxTargetSize, rotationAngle)
            if (scaledBitmap != bitmap) {
                bitmap.recycle()
            }

            val thumbFile = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
            var quality = 80
            var compressSuccess: Boolean
            do {
                try {
                    FileOutputStream(thumbFile).use { out ->
                        compressSuccess = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    compressSuccess = false
                }
                quality -= 10
            } while (compressSuccess && thumbFile.length() > 200 * 1024 && quality > 10)

            val finalWidth = scaledBitmap.width
            val finalHeight = scaledBitmap.height
            scaledBitmap.recycle()

            return if (compressSuccess && thumbFile.exists() && thumbFile.length() > 0) {
                ThumbnailResult(thumbFile, finalWidth, finalHeight)
            } else {
                try { thumbFile.delete() } catch (e: Exception) {}
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun scaleAndRotateBitmap(bitmap: Bitmap, maxSize: Int, rotationAngle: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val ratio = minOf(
            maxSize.toFloat() / width,
            maxSize.toFloat() / height
        )
        val newWidth = maxOf(1, (width * ratio).toInt())
        val newHeight = maxOf(1, (height * ratio).toInt())
        
        val matrix = Matrix()
        matrix.postScale(newWidth.toFloat() / width, newHeight.toFloat() / height)
        if (rotationAngle != 0f) {
            matrix.postRotate(rotationAngle)
        }
        
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
