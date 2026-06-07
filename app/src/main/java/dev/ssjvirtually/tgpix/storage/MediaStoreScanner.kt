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
    // Best available capture/creation date for this photo. Resolution order:
    // 1. DATE_TAKEN  — EXIF capture time (present in camera photos)
    // 2. DATE_ADDED  — when the file first appeared in the media library
    //                  (closest to "file created" for screenshots, WhatsApp, Snapchat, downloads)
    // 3. DATE_MODIFIED — filesystem last-modified time (always present, last resort)
    val dateTaken: Long,
    val tags: String = ""
)

private val waFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd", java.util.Locale.US)
private val dtFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.US)
private val hyphenFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US)
private val underscoreFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss", java.util.Locale.US)
private val dateOnlyFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US)

object MediaStoreScanner {
    private val waRegex = Regex("""IMG[-_](\d{8})[-_]WA""")
    private val dtRegex = Regex("""(\d{8})[-_](\d{6})""")
    private val hyphenDtRegex = Regex("""(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""")
    private val underscoreDtRegex = Regex("""(\d{4})_(\d{2})_(\d{2})_(\d{2})_(\d{2})_(\d{2})""")
    private val dateOnlyRegex = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    private fun parseDateFromFilename(name: String): Long? {
        try {
            // WhatsApp format: IMG-YYYYMMDD-WAxxxx
            waRegex.find(name)?.let { match ->
                val dateStr = match.groupValues[1] // YYYYMMDD
                val localDate = java.time.LocalDate.parse(dateStr, waFormatter)
                return localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }

            // Timestamp format with date and time: YYYYMMDD_HHMMSS or YYYYMMDD-HHMMSS
            dtRegex.find(name)?.let { match ->
                val dateStr = match.groupValues[1] // YYYYMMDD
                val timeStr = match.groupValues[2] // HHMMSS
                val localDateTime = java.time.LocalDateTime.parse("${dateStr}_${timeStr}", dtFormatter)
                return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }

            // Hyphenated date and time: YYYY-MM-DD-HH-MM-SS
            hyphenDtRegex.find(name)?.let { match ->
                val localDateTime = java.time.LocalDateTime.parse(match.value, hyphenFormatter)
                return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }

            // Underscored date and time: YYYY_MM_DD_HH_MM_SS
            underscoreDtRegex.find(name)?.let { match ->
                val localDateTime = java.time.LocalDateTime.parse(match.value, underscoreFormatter)
                return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }

            // Just YYYY-MM-DD
            dateOnlyRegex.find(name)?.let { match ->
                val localDate = java.time.LocalDate.parse(match.value, dateOnlyFormatter)
                return localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun scan(context: Context): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,     // set when file first added to media library ("file created")
            MediaStore.Images.Media.DATE_MODIFIED    // filesystem last-modified (always present)
        )

        // Sort by date taken descending (newest first)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val queryUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        // Date formats are now handled thread-safely by static DateTimeFormatter instances

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
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "photo_$id.jpg"
                    if (name.startsWith(".") || name.contains(".trashed", ignoreCase = true)) {
                        continue
                    }
                    val size = cursor.getLong(sizeColumn)

                    // Resolve the best available date for this photo using a multi-level fallback:
                    //   Level 1: DATE_TAKEN — EXIF-based capture time. Present in camera photos.
                    //            Null/0 for screenshots, WhatsApp images, Snapchat, downloads.
                    //   Level 2: Filename date — for WhatsApp/Snapchat/Screenshots with no EXIF data.
                    //   Level 3: DATE_ADDED — when the file first appeared in the media library (seconds * 1000).
                    //   Level 4: DATE_MODIFIED — filesystem last-modified (seconds * 1000).
                    val dateTaken: Long = cursor.getLong(dateTakenColumn).takeIf { it > 0L }
                        ?: parseDateFromFilename(name)
                        ?: (cursor.getLong(dateAddedColumn) * 1000L).takeIf { it > 0L }
                        ?: (cursor.getLong(dateModifiedColumn) * 1000L)

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
