package com.shazan.airbridge.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cross-platform test vectors (computed independently with Python's hmac module). */
class ProtocolTest {
    private val client = "11111111-1111-4111-8111-111111111111"
    private val host = "22222222-2222-4222-8222-222222222222"
    private val cNonce = ByteArray(16) { 1 }
    private val hNonce = ByteArray(16) { 2 }
    private val k = Protocol.deriveKey("483920".toByteArray(), client, host)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun mac(label: String, a: ByteArray, b: ByteArray, id: String) =
        Protocol.b64(Protocol.hmac(k, Protocol.macInput(label, a, b, id)))

    @Test fun keyDerivation() =
        assertEquals("94d403a4e834208983b5f68362e38e1c4e520aa876480d26281985bd8fc62a47", hex(k))

    @Test fun handshakeMacs() {
        assertEquals("2fXruP1LqnN3gEpYjLxjYobrYSFi99XBcypxxvcmJVw=", mac("pair|", cNonce, hNonce, client))
        assertEquals("7Ra512ABoh425FzoVQJ/1C39LGjB+9OarJl3QMnwvRQ=", mac("pair_ack|", hNonce, cNonce, host))
        assertEquals("/aZUY7u1+dnzHjmRJpzwxkfMuJhvP17gbb/id+RfU/w=", mac("auth|", cNonce, hNonce, client))
        assertEquals("vS1ypNxrRLZMDjtlHqFW35ws1ycEbqG9YFQ6XrzK8Os=", mac("auth_ack|", hNonce, cNonce, host))
    }

    @Test fun base64Variants() {
        val raw = ByteArray(32) { (it * 7).toByte() }
        val url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertArrayEquals(raw, Protocol.unb64url(url))
        assertArrayEquals(raw, Protocol.unb64(Protocol.b64(raw)))
        assertNull(Protocol.unb64("not base64!"))
    }

    @Test fun chunkRoundTrip() {
        val payload = ByteArray(10) { it.toByte() }
        val frame = Protocol.chunkFrame(7, 2, 5_000_000_000L, payload, payload.size)
        assertEquals(17 + 10, frame.size)
        assertEquals(1, frame[0].toInt())
        val c = Protocol.parseChunk(frame)!!
        assertEquals(7, c.xferId); assertEquals(2, c.fileIndex); assertEquals(5_000_000_000L, c.offset)
        assertArrayEquals(payload, c.data.copyOfRange(c.dataOff, c.dataOff + c.len))
    }

    @Test fun sanitize() {
        assertEquals("passwd", Saver.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", Saver.sanitize("C:\\Users\\x\\evil.txt"))
        assertEquals("hidden", Saver.sanitize("...hidden"))
        assertEquals("_con.txt", Saver.sanitize("con.txt"))
        assertEquals("file", Saver.sanitize("\u0000\u0001"))
        assertEquals("ab.jpg", Saver.sanitize("a\nb.jpg"))
        assertEquals("ab.txt", Saver.sanitize("a<b>:\"|?*.txt"))
        assertEquals("name", Saver.sanitize(" name. . "))
        assertEquals("_LPT1", Saver.sanitize("LPT1"))
        assertEquals("_nul.tar.gz", Saver.sanitize("nul.tar.gz"))
        val long = "é".repeat(300) + ".jpeg"
        val out = Saver.sanitize(long)
        assert(out.toByteArray().size <= 200 && out.endsWith(".jpeg"))
    }
}
