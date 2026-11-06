package com.shazan.airbridge.net.link

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §10.1 credential vectors, computed independently in Python 3 (`cryptography` HKDF, cross-checked
 * against a stdlib-hmac RFC 5869 implementation). Same data as docs/test-vectors/wfd-creds.json
 * (generator: docs/test-vectors/wfd_creds.py) so the Windows side can reuse them.
 */
class LinkCredsTest {
    private class V(
        val name: String, val k: String, val hostId: String, val clientId: String, val gen: Int,
        val wk: String, val ssid: String, val psk: String,
    )

    private val vectors = listOf(
        V("k_seq_gen0", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111", 0,
          "e1fec81f9370a81f6761ce869ae9d61a66939d65fae55895e71bbbfe664d0293", "DIRECT-AB-AJK2GU36", "_YplE0mmh49Xt0dAZrqDRrs7RVPKKaRc"),
        V("k_seq_gen1", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111", 1,
          "23434178b4a6418b30cfe7607ca7a66cc9f8a32a2defd1571a238b42b20b0eda", "DIRECT-AB-TYBQYOLR", "OdRMcGuLvG0xtGATePcPKL09SPkehPSJ"),
        V("k_seq_roles_swapped", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", 0,
          "5a304f5e53a5cd156acbb4175fa3f0a5a09072df77deabd748501f5ba74a0ba5", "DIRECT-AB-BRC7W474", "Sm0OyDVCPfjLAcTbpMI6CEvch1bGpc6X"),
        V("k_a5_gen255", "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5", "22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111", 255,
          "d54a4cb6dd71a40eb63e0076d1e8ec2798942e97fea0508c2a3764d9169052f0", "DIRECT-AB-NEEOPKA6", "HDk84bSOp0Ecdlf7HL20csQRCX-ROgFi"),
        V("k_ff_real_ids", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "0f9c2d4e-8b1a-4c3d-9e7f-1a2b3c4d5e6f", "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", 7,
          "6e10b613ca98e6095ce19c84a9b176e5291ccface312f60a263088183bfd9283", "DIRECT-AB-NQKA2EFD", "rISmckKd1WboIahd-n4A0vOgyXjiiB3P"),
    )

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun vectors() {
        for (v in vectors) {
            val k = unhex(v.k)
            assertEquals(v.name, v.wk, hex(LinkCreds.wk(k, v.hostId, v.clientId, v.gen)))
            val c = LinkCreds.wfd(k, v.hostId, v.clientId, v.gen)
            assertEquals(v.name, v.ssid, c.ssid)
            assertEquals(v.name, v.psk, c.psk)
            assertEquals(18, c.ssid.length)
            assertEquals(32, c.psk.length)
        }
    }

    @Test fun base32Rfc4648() {
        // RFC 4648 §10 vectors, padding stripped.
        val cases = listOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        for ((plain, enc) in cases) assertEquals(enc, LinkCreds.base32(plain.toByteArray()))
    }

    @Test fun pskNeverLogged() {
        val c = LinkCreds.wfd(ByteArray(32), "h", "c", 0)
        assert(!c.toString().contains(c.psk))
    }
}
