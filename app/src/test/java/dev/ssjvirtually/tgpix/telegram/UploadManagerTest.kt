package dev.ssjvirtually.tgpix.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadManagerTest {

    @Test
    fun testGetFloodWaitSeconds_validFloodWait() {
        val error = TdApi.Error(420, "FLOOD_WAIT_18")
        val seconds = UploadManager.getFloodWaitSeconds(error)
        assertEquals(18L, seconds)
    }

    @Test
    fun testGetFloodWaitSeconds_floodWaitCaseInsensitive() {
        val error = TdApi.Error(420, "flood_wait_45")
        val seconds = UploadManager.getFloodWaitSeconds(error)
        assertEquals(45L, seconds)
    }

    @Test
    fun testGetFloodWaitSeconds_floodWaitNoDigitsFallback() {
        val error = TdApi.Error(420, "FLOOD_WAIT_UNKNOWN")
        val seconds = UploadManager.getFloodWaitSeconds(error)
        assertEquals(10L, seconds)
    }

    @Test
    fun testGetFloodWaitSeconds_notFloodWait() {
        val error = TdApi.Error(400, "BAD_REQUEST")
        val seconds = UploadManager.getFloodWaitSeconds(error)
        assertNull(seconds)
    }

    @Test
    fun testIsTransportOrNetworkError_errorCode3() {
        val error = TdApi.Error(3, "Connection closed")
        assertTrue(UploadManager.isTransportOrNetworkError(error))
    }

    @Test
    fun testIsTransportOrNetworkError_errorCode500() {
        val error = TdApi.Error(500, "Internal Server Error")
        assertTrue(UploadManager.isTransportOrNetworkError(error))
    }

    @Test
    fun testIsTransportOrNetworkError_messageContainingNetwork() {
        val error = TdApi.Error(400, "A temporary network error occurred")
        assertTrue(UploadManager.isTransportOrNetworkError(error))
    }

    @Test
    fun testIsTransportOrNetworkError_nonTransportError() {
        val error = TdApi.Error(409, "Upload already in progress")
        assertFalse(UploadManager.isTransportOrNetworkError(error))
    }
}
