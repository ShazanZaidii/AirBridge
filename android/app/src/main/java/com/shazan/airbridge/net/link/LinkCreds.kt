package com.shazan.airbridge.net.link

import com.shazan.airbridge.net.Crypto
import java.util.Arrays
import java.util.Base64

/** Wi-Fi Direct legacy-group credentials for one pairing (PROTOCOL §10.1). */
data class WfdCreds(val ssid: String, val psk: String, val gen: Int) {
    override fun toString() = "WfdCreds(ssid=$ssid, gen=$gen)" // never log the passphrase
}

/**
 * Credential derivation, fixed per pairing (§10.1):
 * ```
 * wk   = HKDF-SHA256(ikm = K, salt = "airbridge/v2/wfd", info = hostId | "|" | clientId | "|" | u8(gen), 32)
 * ssid = "DIRECT-AB-" + base32(HMAC-SHA256(wk, "ssid")[0..5))   // RFC 4648, no padding → 8 chars
 * psk  = base64url(HMAC-SHA256(wk, "psk")[0..24))               // no padding → 32 chars
 * ```
 * `hostId` = the WebSocket host (Windows for `wfd_legacy`), `clientId` = the dialer (this phone).
 * Pure JVM; vectors in docs/test-vectors/wfd-creds.json.
 */
object LinkCreds {
    private val SALT = "airbridge/v2/wfd".toByteArray(Charsets.UTF_8)
    const val SSID_PREFIX = "DIRECT-AB-"

    fun wfd(k: ByteArray, hostId: String, clientId: String, gen: Int): WfdCreds {
        require(gen in 0..255) { "gen must fit one byte" }
        val info = "$hostId|$clientId|".toByteArray(Charsets.UTF_8) + byteArrayOf(gen.toByte())
        val wk = Crypto.hkdf(k, SALT, info, 32)
        try {
            val ssidMac = Crypto.hmac(wk, "ssid".toByteArray(Charsets.UTF_8))
            val pskMac = Crypto.hmac(wk, "psk".toByteArray(Charsets.UTF_8))
            return WfdCreds(
                ssid = SSID_PREFIX + base32(ssidMac.copyOfRange(0, 5)),
                psk = Base64.getUrlEncoder().withoutPadding().encodeToString(pskMac.copyOfRange(0, 24)),
                gen = gen,
            )
        } finally {
            Arrays.fill(wk, 0)
        }
    }

    /** Exposed for tests: the intermediate `wk`. */
    internal fun wk(k: ByteArray, hostId: String, clientId: String, gen: Int): ByteArray =
        Crypto.hkdf(k, SALT, "$hostId|$clientId|".toByteArray(Charsets.UTF_8) + byteArrayOf(gen.toByte()), 32)

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32, upper case, no padding. */
    fun base32(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buf = 0
        var bits = 0
        for (b in data) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buf shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
}
