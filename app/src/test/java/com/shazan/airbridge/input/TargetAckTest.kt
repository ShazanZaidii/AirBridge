package com.shazan.airbridge.input

import com.shazan.airbridge.input.TargetAck.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAckTest {
    @Test fun serviceOffIsUnavailable() {
        assertFalse(TargetAck.canAccept(serviceRunning = false, overlayAttached = true, canInject = true, screenW = 1080, screenH = 2400))
        assertEquals(Reply.UNAVAILABLE, TargetAck.reply(accepted = false, cursorShown = false))
    }

    @Test fun overlayOrGesturesMissingIsUnavailable() {
        assertFalse(TargetAck.canAccept(true, overlayAttached = false, canInject = true, screenW = 1080, screenH = 2400))
        assertFalse(TargetAck.canAccept(true, overlayAttached = true, canInject = false, screenW = 1080, screenH = 2400))
        assertFalse(TargetAck.canAccept(true, true, true, screenW = 1, screenH = 1))
    }

    @Test fun ackOnlyWhenTheCursorIsShown() {
        assertTrue(TargetAck.canAccept(true, true, true, 1080, 2400))
        assertEquals(Reply.ACK, TargetAck.reply(accepted = true, cursorShown = true))
        assertEquals(Reply.UNAVAILABLE, TargetAck.reply(accepted = true, cursorShown = false))
    }

    @Test fun notificationRateLimitTenMinutes() {
        val limit = OncePer(600_000)
        assertTrue(limit.tryAcquire(1_000))
        assertFalse(limit.tryAcquire(2_000))
        assertFalse(limit.tryAcquire(600_999))
        assertTrue(limit.tryAcquire(601_000))
    }
}
