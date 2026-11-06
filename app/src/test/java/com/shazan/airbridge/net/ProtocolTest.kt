package com.shazan.airbridge.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cross-platform test vectors. Values were computed independently in Python 3 with the
 * `cryptography` package (EC P-256 ECDH, HKDF-SHA256, AES-GCM) and the stdlib `hmac` module
 * (derive_private_key(scalar) → exchange(ECDH) → T → HKDF/HMAC → AESGCM.encrypt(nonce, inner, aad)).
 *
 * Inputs: clientId/hostId below, cNonce = 16×0x01, hNonce = 16×0x02,
 * client ephemeral private scalar = 32×0x11, host ephemeral private scalar = 32×0x22.
 */
class ProtocolTest {
    private val client = "11111111-1111-4111-8111-111111111111"
    private val host = "22222222-2222-4222-8222-222222222222"
    private val cNonce = ByteArray(16) { 1 }
    private val hNonce = ByteArray(16) { 2 }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- rev 2: ECDH → transcript → keys → digits → envelope ----

    private val cPk = unhex("040217e617f0b6443928278f96999e69a23a4f2c152bdf6d6cdf66e5b80282d4ed194a7debcb97712d2dda3ca85aa8765a56f45fc758599652f2897c65306e5794")
    private val hPk = unhex("04d65a93977caa3d1b081852ff57a79e465f1660577304baead505dd3a48589cf350185e895372df6221ea3a137557e473fddb6755f05bd507c3c533fce9c91285")
    private val zHex = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6"
    private val t = Crypto.transcript(cNonce, hNonce, client, host, cPk, hPk)

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun priv(fill: Int): java.security.PrivateKey {
        val s = java.math.BigInteger(1, ByteArray(32) { fill.toByte() })
        return java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.ECPrivateKeySpec(s, Crypto.p256))
    }

    @Test fun pointCodec() {
        val c = Crypto.decodePoint(cPk)!!
        assertArrayEquals(cPk, Crypto.encodePoint(c))
        val bad = cPk.copyOf().also { it[64] = (it[64] + 1).toByte() } // off the curve
        assertNull(Crypto.decodePoint(bad))
        assertNull(Crypto.decodePoint(cPk.copyOf(64)))
        assertNull(Crypto.decodePoint(ByteArray(65)))
        val e = Crypto.ephemeral()
        assertEquals(65, e.publicRaw.size)
        assertEquals(4, e.publicRaw[0].toInt())
        assert(Crypto.decodePoint(e.publicRaw) != null)
    }

    @Test fun ecdhBothSides() {
        assertEquals(zHex, hex(Crypto.ecdh(priv(0x11), Crypto.decodePoint(hPk)!!)))
        assertEquals(zHex, hex(Crypto.ecdh(priv(0x22), Crypto.decodePoint(cPk)!!)))
        // Two fresh ephemerals agree with each other.
        val a = Crypto.ephemeral(); val b = Crypto.ephemeral()
        assertArrayEquals(a.agree(Crypto.decodePoint(b.publicRaw)!!), b.agree(Crypto.decodePoint(a.publicRaw)!!))
    }

    @Test fun confirmModeVectors() {
        val z = unhex(zHex)
        assertEquals("347036", Crypto.confirmDigits(z, t))
        val kc = Crypto.pairKeyConfirm(z, cNonce, hNonce, client, host)
        assertEquals("d73d244757141cd8eaa0d62d223eb784bdbe368de9c53bafff375089a7efa167", hex(kc))
        assertEquals("/7cyw0NyNmQUlVrD/eecxYPthiKlYLZ3PiTNjHIssrQ=", Protocol.b64(Crypto.mac(kc, "pair|", t)))
        assertEquals("iMql/8RBDyNjprdXSNuXfag1ErGazdOQkalV4YlwKdI=", Protocol.b64(Crypto.mac(kc, "pair_ack|", t)))
    }

    @Test fun qrModeAndAuthVectors() {
        val kq = Crypto.pairKeyQr("483920".toByteArray(), client, host)
        assertEquals("94d403a4e834208983b5f68362e38e1c4e520aa876480d26281985bd8fc62a47", hex(kq))
        assertEquals("H5fLrry2qW5lvyvwp3hHzrxskDyqfdNe2fejOjHF0BY=", Protocol.b64(Crypto.mac(kq, "auth|", t)))
        assertEquals("RKWPaAc1NaxrtgraC5HMbGhIJa1fqtlxMXJlPIjEi0Q=", Protocol.b64(Crypto.mac(kq, "auth_ack|", t)))
    }

    @Test fun sessionKeysAndEnvelope() {
        val (c2h, h2c) = Crypto.sessionKeys(unhex(zHex), cNonce, hNonce, t)
        assertEquals("35ba54610f2f472caab1d05e9638fd7fdfb3c3d3e66e35cbe45616fa4678d277", hex(c2h))
        assertEquals("0b9f111f0a52aaa7480c48d3f94bb5f8e6e11a182cba356b2b8b49b91618e0d4", hex(h2c))
        val clientEnv = Envelope(c2h.copyOf(), h2c.copyOf())
        val hostEnv = Envelope(h2c.copyOf(), c2h.copyOf())
        val ping = "{\"t\":\"ping\",\"ts\":1}".toByteArray()
        val video = byteArrayOf(3) + ByteArray(12) { it.toByte() }
        val f0 = clientEnv.seal(Envelope.INNER_TEXT, ping)
        val f1 = clientEnv.seal(Envelope.INNER_BINARY, video)
        assertEquals("02000000000000000056924c12ceca79375e2e9d150090e286bf414221605166329f998ddd9219cebf2625f20d", hex(f0))
        assertEquals("02000000000000000189f25e63a516ca326bae4eb01a1ec6ddb8c9ba394b87d3a1c946f4065c43", hex(f1))
        // Out of order → rejected; in order → accepted.
        assertNull(hostEnv.open(f1))
        val o0 = hostEnv.open(f0)!!
        assertEquals(Envelope.INNER_TEXT, o0.innerKind); assertArrayEquals(ping, o0.payload)
        val o1 = hostEnv.open(f1)!!
        assertEquals(Envelope.INNER_BINARY, o1.innerKind); assertArrayEquals(video, o1.payload)
        // Replay → rejected.
        assertNull(hostEnv.open(f1))
        // Tampered ciphertext or header → rejected.
        val f2 = clientEnv.seal(Envelope.INNER_TEXT, ping)
        assertNull(hostEnv.open(f2.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }))
        // Direction keys differ: a client→host frame never opens with the host→client key.
        val wrongDir = Envelope(c2h.copyOf(), h2c.copyOf()) // recv key = h2c
        assertNull(wrongDir.open(Envelope(c2h.copyOf(), h2c.copyOf()).seal(0, ping)))
    }

    @Test fun envelopeLargePayload() {
        val k1 = ByteArray(32) { 7 }; val k2 = ByteArray(32) { 9 }
        val a = Envelope(k1.copyOf(), k2.copyOf()); val b = Envelope(k2.copyOf(), k1.copyOf())
        val big = ByteArray((1 shl 20) + 17) { (it * 31).toByte() }
        val f = a.seal(Envelope.INNER_BINARY, big)
        assertEquals(big.size + Envelope.OVERHEAD, f.size)
        assertArrayEquals(big, b.open(f)!!.payload)
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
