package dev.ssjvirtually.tgpix.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import java.security.MessageDigest

data class LocalPhoto(
    val id: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val dateTaken: Long,
    val tags: String = ""
)

object MediaStoreScanner {
    fun scan(context: Context): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        // Sort by date taken descending (newest first)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val queryUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        try {
            context.contentResolver.query(
                queryUri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "photo_$id.jpg"
                    if (name.startsWith(".") || name.contains(".trashed", ignoreCase = true)) {
                        continue
                    }
                    val size = cursor.getLong(sizeColumn)
                    
                    var dateTaken = cursor.getLong(dateTakenColumn)
                    if (dateTaken == 0L) {
                        // fallback to date modified (which is in seconds, convert to milliseconds)
                        dateTaken = cursor.getLong(dateModifiedColumn) * 1000
                    }
                    
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    photos.add(LocalPhoto(id, contentUri, name, size, dateTaken))
                }
            }
        } catch (e: Exception) {
            TdlibManager.addLog("Error scanning MediaStore: ${e.message}")
            e.printStackTrace()
        }

        TdlibManager.addLog("MediaStoreScanner: scanned ${photos.size} photos.")
        return photos
    }
}

fun LocalPhoto.getPartialHash(context: Context): String {
    return try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
            val buffer = ByteArray(65536) // 64KB
            val bytesRead = stream.read(buffer)
            if (bytesRead > 0) {
                val digest = MessageDigest.getInstance("MD5")
                digest.update(buffer, 0, bytesRead)
                digest.digest().joinToString("") { String.format("%02x", it) }
            } else {
                ""
            }
        } ?: ""
    } catch (e: Exception) {
        android.util.Log.e("TGPix", "Failed to compute partial hash for $name", e)
        ""
    }
}

fun LocalPhoto.getFingerprint(context: Context): String {
    val partialHash = getPartialHash(context)
    return if (partialHash.isNotEmpty()) {
        "${name}_${size}_${dateTaken}_$partialHash"
    } else {
        "${name}_${size}_${dateTaken}"
    }
}
