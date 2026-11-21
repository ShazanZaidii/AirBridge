package com.shazan.airbridge.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipMediaTest {
    private val mb = 1024L * 1024

    @Test fun anImageIsOneItemOfAnImageType() {
        assertTrue(ClipMedia.isImage(listOf("image/png")))
        assertTrue(ClipMedia.isImage(listOf("image/jpeg")))
        assertFalse(ClipMedia.isImage(listOf("image/png", "image/png")))
        assertFalse(ClipMedia.isImage(listOf("application/pdf")))
        assertFalse(ClipMedia.isImage(listOf(null)))
        assertFalse(ClipMedia.isImage(emptyList()))
    }

    @Test fun anImageIsLimitedTo25Megabytes() {
        assertTrue(ClipMedia.allowed(listOf(25 * mb), image = true))
        assertFalse(ClipMedia.allowed(listOf(25 * mb + 1), image = true))
    }

    @Test fun filesAreLimitedTo100MegabytesAndTwentyItems() {
        assertTrue(ClipMedia.allowed(listOf(60 * mb, 40 * mb), image = false))
        assertFalse(ClipMedia.allowed(listOf(60 * mb, 40 * mb + 1), image = false))
        assertTrue(ClipMedia.allowed(List(20) { 1L }, image = false))
        assertFalse(ClipMedia.allowed(List(21) { 1L }, image = false))
    }

    @Test fun anImageGetsTheLowerLimitEvenThoughFilesMayBeBigger() {
        assertFalse(ClipMedia.allowed(listOf(30 * mb), image = true))
        assertTrue(ClipMedia.allowed(listOf(30 * mb), image = false))
    }

    @Test fun nothingOrAnUnknownSizeIsNotSent() {
        assertFalse(ClipMedia.allowed(emptyList(), image = false))
        assertFalse(ClipMedia.allowed(listOf(-1L), image = true))
        assertEquals(25L * mb, ClipMedia.IMAGE_MAX_BYTES)
    }
}
