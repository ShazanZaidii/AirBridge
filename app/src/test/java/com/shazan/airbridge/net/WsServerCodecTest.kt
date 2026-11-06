package com.shazan.airbridge.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WsServerCodecTest {

    @Test fun acceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsServerCodec.accept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun parsesTheUpgradeRequest() {
        val req = "GET /v2 HTTP/1.1\r\nHost: 192.168.1.5:47632\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        val h = WsServerCodec.readRequest(ByteArrayInputStream(req.toByteArray()))!!
        assertEquals("/v2", h[":path"])
        assertEquals("websocket", h["upgrade"])
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", h["sec-websocket-key"])
        assertNull(WsServerCodec.readRequest(ByteArrayInputStream("POST / HTTP/1.1\r\n\r\n".toByteArray())))
    }

    /** A client frame as okhttp sends it: masked. */
    private fun clientFrame(op: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or op)
        val n = payload.size
        when {
            n < 126 -> out.write(0x80 or n)
            n < 65536 -> { out.write(0x80 or 126); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        out.write(mask)
        out.write(ByteArray(n) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, max: Int = 4 shl 20): Triple<List<String>, List<ByteArray>, List<ByteArray>> {
        val t = mutableListOf<String>(); val b = mutableListOf<ByteArray>(); val p = mutableListOf<ByteArray>()
        WsServerCodec.readMessages(ByteArrayInputStream(bytes), max, { t += it }, { b += it }, { p += it })
        return Triple(t, b, p)
    }

    @Test fun readsMaskedTextBinaryPingAndStopsAtClose() {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val stream = clientFrame(0x1, "{\"t\":\"hello\"}".toByteArray()) +
            clientFrame(0x2, big) +
            clientFrame(0x9, byteArrayOf(1, 2)) +
            clientFrame(0x8, byteArrayOf(0x03, 0xE8.toByte())) +
            clientFrame(0x1, "after close".toByteArray())
        val (texts, bins, pings) = read(stream)
        assertEquals(listOf("{\"t\":\"hello\"}"), texts)
        assertEquals(1, bins.size)
        assertArrayEquals(big, bins[0])
        assertArrayEquals(byteArrayOf(1, 2), pings.single())
    }

    @Test fun reassemblesFragments() {
        val stream = clientFrame(0x1, "hel".toByteArray(), fin = false) +
            clientFrame(0x9, byteArrayOf()) + // control frames may interleave
            clientFrame(0x0, "lo".toByteArray(), fin = true)
        assertEquals(listOf("hello"), read(stream).first)
    }

    @Test fun refusesOversizedFrames() {
        val e = runCatching { read(clientFrame(0x2, ByteArray(2000)), max = 1000) }.exceptionOrNull()
        assertTrue(e is IOException)
    }

    @Test fun serverFramesAreUnmaskedWithTheRightLength() {
        val small = WsServerCodec.frame(0x1, "hi".toByteArray())
        assertArrayEquals(byteArrayOf(0x81.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte()), small)
        val mid = WsServerCodec.frame(0x2, ByteArray(1000))
        assertEquals(126, mid[1].toInt())
        assertEquals(1000, ((mid[2].toInt() and 0xFF) shl 8) or (mid[3].toInt() and 0xFF))
        val large = WsServerCodec.frame(0x2, ByteArray(70_000))
        assertEquals(127, large[1].toInt())
        assertEquals(70_000 + 10, large.size)
    }
}
