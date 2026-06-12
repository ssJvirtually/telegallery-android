package dev.ssjvirtually.tgpix

import dev.ssjvirtually.tgpix.storage.CloudPhotoEntity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.PhotosRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosRepositoryTest {

    private val repository = PhotosRepository()

    @Test
    fun testMergeAndDeduplicate_emptyInputs() = runBlocking {
        val result = repository.mergeAndDeduplicate(
            localPhotos = emptyList(),
            cloudLogs = emptyList(),
            uploadedPaths = emptyList()
        )
        assertTrue(result.mergedPhotos.isEmpty())
        assertTrue(result.uploadedUris.isEmpty())
    }

    @Test
    fun testMergeAndDeduplicate_localOnly() = runBlocking {
        val localPhoto = LocalPhoto(
            id = 1L,
            uri = "content://media/external/images/media/1",
            name = "IMG_20230612_120000.jpg",
            size = 1024L,
            dateTaken = 1686571200000L,
            bucketId = "camera",
            bucketName = "Camera"
        )
        val result = repository.mergeAndDeduplicate(
            localPhotos = listOf(localPhoto),
            cloudLogs = emptyList(),
            uploadedPaths = emptyList()
        )
        assertEquals(1, result.mergedPhotos.size)
        assertEquals(localPhoto.uri, result.mergedPhotos[0].uri)
        assertTrue(result.uploadedUris.isEmpty())
    }

    @Test
    fun testMergeAndDeduplicate_exactFingerprintMatch() = runBlocking {
        val localPhoto = LocalPhoto(
            id = 1L,
            uri = "content://media/external/images/media/1",
            name = "img_20230612_120000.jpg",
            size = 1024L,
            dateTaken = 1686571200000L,
            bucketId = "camera",
            bucketName = "Camera"
        )
        val cloudPhoto = CloudPhotoEntity(
            messageId = 100L,
            telegramFileId = 200,
            uniqueRemoteId = "remote_id_1",
            fileName = "img_20230612_120000.jpg",
            uploadedAt = 1686571200000L,
            fileSize = 1024L,
            isDocument = false,
            contentFingerprint = "img_20230612_120000.jpg_1024_1686571200000",
            tags = "#test #vacation"
        )
        val result = repository.mergeAndDeduplicate(
            localPhotos = listOf(localPhoto),
            cloudLogs = listOf(cloudPhoto),
            uploadedPaths = emptyList()
        )
        // Deduplicated: should produce exactly 1 item representing the matched local photo with cloud tags
        assertEquals(1, result.mergedPhotos.size)
        assertEquals(localPhoto.uri, result.mergedPhotos[0].uri)
        assertEquals("#test #vacation", result.mergedPhotos[0].tags)
        // The local photo URI should be in uploadedUris set
        assertTrue(result.uploadedUris.contains(localPhoto.uri))
    }

    @Test
    fun testMergeAndDeduplicate_cloudOnlyFallback() = runBlocking {
        val cloudPhoto = CloudPhotoEntity(
            messageId = 100L,
            telegramFileId = 200,
            uniqueRemoteId = "remote_id_1",
            fileName = "img_20230612_120000.jpg",
            uploadedAt = 1686571200000L,
            fileSize = 1024L,
            isDocument = false,
            contentFingerprint = "img_20230612_120000.jpg_1024_1686571200000",
            tags = "#sunset"
        )
        val result = repository.mergeAndDeduplicate(
            localPhotos = emptyList(),
            cloudLogs = listOf(cloudPhoto),
            uploadedPaths = emptyList()
        )
        // No local match: should generate a cloud-only fallback photo record
        assertEquals(1, result.mergedPhotos.size)
        assertEquals("cloud://100/200/img_20230612_120000.jpg", result.mergedPhotos[0].uri)
        assertEquals("#sunset", result.mergedPhotos[0].tags)
    }
}
