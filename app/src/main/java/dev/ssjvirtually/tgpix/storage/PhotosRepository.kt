package dev.ssjvirtually.tgpix.storage

import dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MergeResult(
    val mergedPhotos: List<LocalPhoto>,
    val uploadedUris: Set<String>
)

object PhotosRepository {

    private fun String.normalize(): String {
        val lower = this.lowercase()
        if (lower.startsWith(".trashed-")) {
            val index = lower.indexOf('-', 9) // 9 is ".trashed-".length
            if (index != -1) {
                return lower.substring(index + 1)
            }
        }
        return lower
    }

    suspend fun mergeAndDeduplicate(
        localPhotos: List<LocalPhoto>,
        cloudLogs: List<CloudPhotoEntity>,
        uploadedPaths: List<String>
    ): MergeResult = withContext(Dispatchers.Default) {
        if (cloudLogs.isEmpty() && uploadedPaths.isEmpty()) {
            return@withContext MergeResult(
                mergedPhotos = localPhotos.sortedByDescending { it.dateTaken },
                uploadedUris = emptySet()
            )
        }

        val list = mutableListOf<LocalPhoto>()
        val uploadedUris = mutableSetOf<String>()
        
        uploadedUris.addAll(uploadedPaths)
        
        // Build helper maps for multi-layered matching to eliminate duplicates
        val localByFingerprint = localPhotos.associateBy { "${it.name.normalize()}_${it.size}_${it.dateTaken}" }
        val localByName = localPhotos.groupBy { it.name.normalize() }
        val localByDateAndSize = localPhotos.associateBy { "${it.dateTaken / 1000}_${it.size}" }
        val localByDate = localPhotos.groupBy { it.dateTaken / 1000 }
        
        val matchedLocalKeys = mutableSetOf<String>()
        val addedUris = mutableSetOf<String>()

        for (cloud in cloudLogs) {
            val cloudNormName = cloud.fileName.normalize()
            val cloudFingerprintDate = if (cloud.dateTaken > 0L) cloud.dateTaken else cloud.uploadedAt
            val cloudFingerprint = "${cloudNormName}_${cloud.fileSize}_$cloudFingerprintDate"
            val parsedDate = parseDateFromFilename(cloud.fileName)
            val displayDate = if (cloud.dateTaken > 0L) cloud.dateTaken else (parsedDate ?: cloud.uploadedAt)
            
            // Try matching cloud photo to local photo in order of specificity:
            var matchingLocal = localByFingerprint[cloudFingerprint]
            
            if (matchingLocal == null) {
                matchingLocal = localByName[cloudNormName]?.firstOrNull()
            }
            
            if (matchingLocal == null && parsedDate != null) {
                matchingLocal = localByDateAndSize["${parsedDate / 1000}_${cloud.fileSize}"]
            }
            
            if (matchingLocal == null && parsedDate != null) {
                val parsedSeconds = parsedDate / 1000
                val candidates = mutableListOf<LocalPhoto>()
                localByDate[parsedSeconds]?.let { candidates.addAll(it) }
                localByDate[parsedSeconds - 1]?.let { candidates.addAll(it) }
                localByDate[parsedSeconds + 1]?.let { candidates.addAll(it) }

                matchingLocal = candidates.firstOrNull { candidate ->
                    val cName = candidate.name.normalize()
                    cName == cloudNormName || 
                    (cName.startsWith("img_") && cloudNormName.startsWith("img_")) || 
                    (cName.startsWith("photo_") && cloudNormName.startsWith("photo_"))
                }
            }
            
            if (matchingLocal != null) {
                if (!addedUris.contains(matchingLocal.uri)) {
                    list.add(matchingLocal.copy(tags = cloud.tags))
                    addedUris.add(matchingLocal.uri)
                }
                matchedLocalKeys.add(matchingLocal.name.lowercase())
                uploadedUris.add(matchingLocal.uri) // Match found! This local photo is uploaded/synced.
            } else {
                val cloudUri = "cloud://${cloud.messageId}/${cloud.telegramFileId}/${cloud.fileName}"
                if (!addedUris.contains(cloudUri)) {
                    list.add(
                        LocalPhoto(
                            id = -cloud.messageId,
                            uri = cloudUri,
                            name = cloud.fileName,
                            size = cloud.fileSize,
                            dateTaken = displayDate,
                            tags = cloud.tags
                        )
                    )
                    addedUris.add(cloudUri)
                }
            }
        }

        // 2. Inject unsynced local device photos
        for (local in localPhotos) {
            if (!matchedLocalKeys.contains(local.name.lowercase()) && !addedUris.contains(local.uri)) {
                list.add(local)
                addedUris.add(local.uri)
            }
        }

        // 3. Sort strictly by date taken descending
        MergeResult(
            mergedPhotos = list.sortedByDescending { it.dateTaken },
            uploadedUris = uploadedUris
        )
    }
}
