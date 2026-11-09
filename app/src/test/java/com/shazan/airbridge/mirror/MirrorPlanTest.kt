package com.shazan.airbridge.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPlanTest {

    @Test fun requestDefaultsAndClamps() {
        val d = MirrorPlan.request(null, null, null, null, null)
        assertEquals(listOf("h264"), d.codecs)
        assertEquals(1920, d.maxW); assertEquals(1920, d.maxH)
        assertEquals(60, d.fps); assertEquals(8_000_000, d.bitrate)

        val c = MirrorPlan.request(listOf("", "hevc", "h264"), 50.0, 99_999.0, 500.0, Double.NaN)
        assertEquals(listOf("hevc", "h264"), c.codecs)
        assertEquals(MirrorPlan.MIN_SIDE, c.maxW)
        assertEquals(4096, c.maxH)
        assertEquals(120, c.fps)
        assertEquals(MirrorPlan.DEFAULT_BITRATE, c.bitrate)
        assertEquals(listOf("h264"), MirrorPlan.request(emptyList(), null, null, null, null).codecs)
    }

    @Test fun codecChoice() {
        // HEVC only when the sink lists it first and the phone has a hardware encoder.
        assertEquals("hevc", MirrorPlan.chooseCodec(listOf("hevc", "h264"), hevcEncoder = true, avcEncoder = true))
        assertEquals("h264", MirrorPlan.chooseCodec(listOf("h264", "hevc"), hevcEncoder = true, avcEncoder = true))
        assertEquals("h264", MirrorPlan.chooseCodec(listOf("hevc", "h264"), hevcEncoder = false, avcEncoder = true))
        assertEquals("hevc", MirrorPlan.chooseCodec(listOf("hevc"), hevcEncoder = true, avcEncoder = true))
        assertNull(MirrorPlan.chooseCodec(listOf("hevc"), hevcEncoder = false, avcEncoder = true))
        assertNull(MirrorPlan.chooseCodec(listOf("av1"), hevcEncoder = true, avcEncoder = true))
        assertEquals("hevc", MirrorPlan.chooseCodec(listOf("h264", "hevc"), hevcEncoder = true, avcEncoder = false))
    }

    @Test fun fitKeepsAspectNeverUpscalesAndAligns() {
        assertEquals(MirrorPlan.Size(1080, 2400), MirrorPlan.fit(1080, 2400, 2400, 2400))
        // Portrait phone into a 1920-tall box: 0.8 scale.
        assertEquals(MirrorPlan.Size(864, 1920), MirrorPlan.fit(1080, 2400, 1920, 1920))
        // Landscape after rotation.
        assertEquals(MirrorPlan.Size(1920, 864), MirrorPlan.fit(2400, 1080, 1920, 1920))
        // Small display, big box: unchanged.
        assertEquals(MirrorPlan.Size(720, 1600), MirrorPlan.fit(720, 1600, 4096, 4096))
        // Alignment rounds down.
        val s = MirrorPlan.fit(1080, 2412, 1000, 1000, align = 16)
        assertEquals(0, s.w % 16); assertEquals(0, s.h % 16)
        assertTrue(s.h <= 1000)
    }

    @Test fun fitShrinksUntilEncoderAccepts() {
        // Encoder that tops out at 1920 on either side.
        val s = MirrorPlan.fit(1440, 3200, 4096, 4096) { w, h -> w <= 1920 && h <= 1920 }
        assertTrue(s.h <= 1920)
        val aspect = 3200.0 / 1440.0
        assertEquals(aspect, s.h.toDouble() / s.w, 0.01)
        // Nothing accepted: stops at the minimum instead of looping forever.
        val tiny = MirrorPlan.fit(1080, 2400, 4096, 4096) { _, _ -> false }
        assertTrue(minOf(tiny.w, tiny.h) <= MirrorPlan.MIN_SIDE)
    }

    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 3)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 9, 9, 9)
    private val pSlice = byteArrayOf(0, 0, 1, 0x41, 7, 7)

    @Test fun annexBInspection() {
        assertEquals(listOf(7, 8, 5), MirrorPlan.nalTypes(sps + pps + idr, "h264"))
        assertTrue(MirrorPlan.hasParamSets(sps + pps + idr, "h264"))
        assertFalse(MirrorPlan.hasParamSets(idr, "h264"))
        assertFalse(MirrorPlan.hasPicture(sps + pps, "h264")) // codec config buffer
        assertTrue(MirrorPlan.hasPicture(pSlice, "h264"))
        // HEVC: VPS 32, SPS 33, PPS 34, IDR_W_RADL 19 (type << 1 in the first header byte).
        val hevc = byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1, 0, 0, 0, 1, 0x26, 1, 5)
        assertEquals(listOf(32, 33, 34, 19), MirrorPlan.nalTypes(hevc, "hevc"))
        assertTrue(MirrorPlan.hasParamSets(hevc, "hevc"))
        assertTrue(MirrorPlan.hasPicture(hevc, "hevc"))
    }

    @Test fun videoFrameHeader() {
        val f = MirrorPlan.videoFrame(0x0102030405060708L, key = true, params = true, annexB = idr)
        assertEquals(0x03, f[0].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), f.copyOfRange(1, 9))
        assertEquals(3, f[9].toInt())
        assertArrayEquals(idr, f.copyOfRange(10, f.size))
        assertEquals(0, MirrorPlan.videoFrame(1, key = false, params = false, annexB = pSlice)[9].toInt())
    }

    @Test fun flowWindowAndAcks() {
        val f = MirrorPlan.Flow(8_000_000, now = 0)
        for (i in 1..MirrorPlan.Flow.MAX_UNACKED) { assertTrue(f.open); f.onSent(i * 1000L, i.toLong()) }
        assertFalse(f.open) // 12 in flight: pause the encoder
        assertFalse(f.full)
        assertNull(f.onAck(10_000, now = 50)) // 40 ms lag: no cut
        assertEquals(2, f.inFlight)
        assertTrue(f.open)
        repeat(4) { f.onSent(100_000L + it, 60) }
        assertEquals(6, f.inFlight)
    }

    @Test fun flowLowersOnLagAndRecovers() {
        val f = MirrorPlan.Flow(8_000_000, now = 0)
        f.onSent(1, 1000)
        assertEquals(6_000_000, f.onAck(1, now = 1300)) // 300 ms lag → −25 %
        f.onSent(2, 1400)
        assertNull(f.onAck(2, now = 1600)) // within 500 ms of the last change
        f.onSent(3, 2000)
        f.onAck(3, now = 2010) // healthy again
        assertEquals(MirrorPlan.Flow.Tick.Nothing, f.tick(now = 3000))
        f.onAck(3, now = 6000)
        val t = f.tick(now = 6400)
        assertTrue(t is MirrorPlan.Flow.Tick.Bitrate)
        assertEquals(6_600_000, (t as MirrorPlan.Flow.Tick.Bitrate).bps)
    }

    @Test fun flowStopsWhenSinkGoesQuiet() {
        val f = MirrorPlan.Flow(8_000_000, now = 0)
        // Nothing sent yet (encoder starting, consent dialog): never "quiet".
        assertTrue(f.tick(now = 10_000) !is MirrorPlan.Flow.Tick.Stop)
        f.onSent(1, now = 10_000)
        assertEquals(MirrorPlan.Flow.Tick.Nothing, f.tick(now = 12_900))
        assertEquals(MirrorPlan.Flow.Tick.Stop, f.tick(now = 13_100))
        f.onAck(1, now = 13_100)
        assertTrue(f.tick(now = 20_000) !is MirrorPlan.Flow.Tick.Stop) // all acked, a still screen is fine
    }

    @Test fun flowNeverBelowMinimum() {
        val f = MirrorPlan.Flow(MirrorPlan.MIN_BITRATE, now = 0)
        assertNull(f.lower(0.5, now = 1000))
        assertEquals(MirrorPlan.MIN_BITRATE, f.bitrate)
    }
}
