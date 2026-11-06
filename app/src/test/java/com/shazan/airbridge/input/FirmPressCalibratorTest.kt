package com.shazan.airbridge.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmPressCalibratorTest {

    @Test fun offUntilEnoughOrdinaryTouches() {
        val c = FirmPressCalibrator()
        repeat(FirmPressCalibrator.MIN_SAMPLES - 1) { c.learn(0.3f + it * 0.01f) }
        assertEquals(Float.POSITIVE_INFINITY, c.threshold())
        c.learn(0.32f)
        assertTrue(c.threshold().isFinite())
    }

    @Test fun thresholdSitsClearlyAboveNormalTouches() {
        val c = FirmPressCalibrator()
        listOf(0.28f, 0.30f, 0.31f, 0.33f, 0.29f, 0.35f, 0.30f, 0.32f, 0.34f, 0.31f).forEach(c::learn)
        val t = c.threshold()
        assertTrue("above every ordinary touch: $t", t > 0.35f)
        assertTrue("reachable by pressing harder: $t", t < 0.6f)
    }

    @Test fun phonesThatReportConstantPressureNeverFire() {
        val c = FirmPressCalibrator()
        repeat(20) { c.learn(1.0f) }
        assertFalse(c.pressureVaries)
        assertEquals(Float.POSITIVE_INFINITY, c.threshold())
    }

    @Test fun keepsOnlyRecentSamplesAndIgnoresJunk() {
        val c = FirmPressCalibrator()
        c.learn(Float.NaN); c.learn(0f); c.learn(-1f)
        assertEquals(0, c.sampleCount)
        repeat(100) { c.learn(0.3f + (it % 5) * 0.01f) }
        assertEquals(FirmPressCalibrator.MAX_SAMPLES, c.sampleCount)
    }

    @Test fun firmPressesAreNotLearnt() {
        val c = FirmPressCalibrator()
        FirmPress.touchEnded(fingers = 1, peak = 0.9f, wasFirm = true, into = c)
        FirmPress.touchEnded(fingers = 3, peak = 0.9f, wasFirm = false, into = c)
        assertEquals(0, c.sampleCount)
        FirmPress.touchEnded(fingers = 1, peak = 0.3f, wasFirm = false, into = c)
        assertEquals(1, c.sampleCount)
    }
}
