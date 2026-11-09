package com.shazan.airbridge.phone

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The proximity beacon's rotating id (§13.4): the first [LEN] bytes of
 * HMAC-SHA256(key, "airbridge-beacon" ‖ epoch as 8 bytes big-endian), epoch = unix seconds / [PERIOD_S].
 * The key is random per phone and only ever sent to its paired computers inside the encrypted session, so
 * nobody else can tell two ids apart from random ones or link them over time.
 */
object BeaconId {
    const val SERVICE_UUID = "754338F5-9682-4D7A-9A60-84968B058DDE"
    const val PERIOD_S = 900
    const val LEN = 8
    private val LABEL = "airbridge-beacon".toByteArray(Charsets.US_ASCII)

    fun epoch(unixMs: Long): Long = Math.floorDiv(unixMs / 1000, PERIOD_S.toLong())

    fun derive(key: ByteArray, epoch: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(LABEL)
        val e = ByteArray(8) { i -> (epoch ushr (56 - 8 * i)).toByte() }
        return mac.doFinal(e).copyOf(LEN)
    }

    /** Milliseconds until the next rotation. */
    fun msToNext(unixMs: Long): Long = (epoch(unixMs) + 1) * PERIOD_S * 1000L - unixMs
}
