package com.shazan.airbridge.net

import com.shazan.airbridge.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Constants, message builders and crypto helpers for AirBridge Protocol v2 (docs/PROTOCOL.md). */
object Protocol {
    const val VERSION = 2
    const val SERVICE_TYPE = "_airbridge._tcp."
    const val WS_PATH = "/v2"
    const val PLATFORM = "android"
    val APP_VERSION: String = BuildConfig.VERSION_NAME
    /**
     * §3.1 caps. `mirror_sink` (input half, §8.7) only if the device can decode H.264; `wfd_join`
     * (§10.1) only on API ≥ 29 with Wi-Fi hardware.
     */
    val CAPS: List<String> by lazy {
        listOf("clip", "xfer", "ctl_target", "trackpad") +
            (if (hasH264Decoder()) listOf("mirror_sink") else emptyList()) +
            (if (canJoinWfd()) listOf(com.shazan.airbridge.net.link.LinkPolicy.CAP_JOIN) else emptyList())
    }

    private fun canJoinWfd(): Boolean = try {
        com.shazan.airbridge.net.link.WfdJoiner.supported(Hub.app)
    } catch (_: Throwable) { false }

    private fun hasH264Decoder(): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }
    } catch (_: Throwable) { true } // can't tell: assume yes (every Android device ships one)

    const val HANDSHAKE_TIMEOUT_MS = 10_000L
    /** Whole `confirm`/`qr` pairing must finish within this long after `pair_start` (§3.3). */
    const val PAIR_TIMEOUT_MS = 60_000L
    const val PING_IDLE_MS = 5_000L
    const val DEAD_MS = 15_000L

    const val MAX_CLIP_BYTES = 512 * 1024
    const val CHUNK_SIZE = 512 * 1024
    const val RECV_WINDOW = 8L * 1024 * 1024
    const val ACK_EVERY = 1L * 1024 * 1024

    const val KIND_FILE_CHUNK: Int = 0x01
    const val CHUNK_HEADER = 17
    const val MAX_TEXT_FRAME = 1 shl 20
    const val MAX_BINARY_FRAME = (1 shl 20) + CHUNK_HEADER
    /** Largest frame on the wire: an envelope around the largest inner frame (§9). */
    const val MAX_WIRE_FRAME = MAX_BINARY_FRAME + Envelope.OVERHEAD
    const val KIND_VIDEO: Int = 0x03

    /** Framing for byte-stream transports (§10): u8 kind | u32 len | payload. */
    const val FRAME_TEXT = 0
    const val FRAME_BINARY = 1

    private val rng = SecureRandom()

    // ---- message builders ----

    fun msg(t: String): JSONObject = JSONObject().put("t", t)

    fun hello(
        id: String, name: String, nonce: ByteArray, pk: ByteArray, screenW: Int, screenH: Int, paired: Boolean?,
    ): JSONObject = msg("hello")
        .put("v", VERSION)
        .put("id", id)
        .put("name", name)
        .put("plat", PLATFORM)
        .put("app", APP_VERSION)
        .put("caps", JSONArray(CAPS))
        .put("nonce", b64(nonce))
        .put("pk", b64(pk))
        .apply {
            if (screenW > 0 && screenH > 0) put("screen", JSONObject().put("w", screenW).put("h", screenH))
            if (paired != null) put("paired", paired)
        }

    fun withReason(t: String, reason: String): JSONObject = msg(t).put("reason", reason)
    fun ping(): JSONObject = msg("ping").put("ts", System.currentTimeMillis())
    fun clip(text: String): JSONObject =
        msg("clip").put("kind", "text").put("text", text).put("ts", System.currentTimeMillis())

    // ---- crypto ----

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun constEq(a: ByteArray?, b: ByteArray?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a, b)

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String?): ByteArray? = try {
        if (s == null) null else Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) { null }

    fun unb64url(s: String?): ByteArray? = try {
        if (s == null) null else Base64.getUrlDecoder().decode(s.trimEnd('='))
    } catch (_: IllegalArgumentException) { null }

    // ---- binary chunk (§6.2) ----

    fun chunkFrame(xferId: Int, fileIndex: Int, offset: Long, payload: ByteArray, len: Int): ByteArray {
        val out = ByteArray(CHUNK_HEADER + len)
        ByteBuffer.wrap(out).apply {
            put(KIND_FILE_CHUNK.toByte())
            putInt(xferId); putInt(fileIndex); putLong(offset)
        }
        System.arraycopy(payload, 0, out, CHUNK_HEADER, len)
        return out
    }

    class Chunk(val xferId: Int, val fileIndex: Int, val offset: Long, val data: ByteArray, val dataOff: Int, val len: Int)

    fun parseChunk(frame: ByteArray): Chunk? {
        if (frame.size <= CHUNK_HEADER || (frame[0].toInt() and 0xFF) != KIND_FILE_CHUNK) return null
        val bb = ByteBuffer.wrap(frame)
        bb.get()
        val id = bb.int
        val idx = bb.int
        val off = bb.long
        if (off < 0) return null
        return Chunk(id, idx, off, frame, CHUNK_HEADER, frame.size - CHUNK_HEADER)
    }
}

/** A number that serialises plainly (no exponent) through org.json, which calls toString(). */
private class PlainNumber(private val v: java.math.BigDecimal) : Number() {
    override fun toDouble() = v.toDouble()
    override fun toFloat() = v.toFloat()
    override fun toLong() = v.toLong()
    override fun toInt() = v.toInt()
    override fun toShort() = v.toShort()
    override fun toByte() = v.toByte()
    override fun toString(): String = v.toPlainString()
    override fun equals(other: Any?) = other is PlainNumber && other.v == v
    override fun hashCode() = v.hashCode()
}

/**
 * Round every float in [o] (recursively) to ≤ 4 decimals so it is written as `0.0001`, never
 * `1.0E-4` (§9). Mutates and returns [o]; idempotent.
 */
fun normalizeNumbers(o: JSONObject): JSONObject {
    for (k in o.keys().asSequence().toList()) o.put(k, normalizeValue(o.opt(k)))
    return o
}

private fun normalizeValue(v: Any?): Any? = when (v) {
    is Double, is Float -> {
        val d = (v as Number).toDouble()
        if (d.isNaN() || d.isInfinite()) 0
        else java.math.BigDecimal(d).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
            .let { if (it.signum() == 0) 0 else PlainNumber(it) }
    }
    is JSONObject -> normalizeNumbers(v)
    is JSONArray -> { for (i in 0 until v.length()) v.put(i, normalizeValue(v.opt(i))); v }
    else -> v
}

/** Defensive JSON accessors: never throw on malformed input. */
fun parseJson(text: String): JSONObject? = try { JSONObject(text) } catch (_: Exception) { null }
fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
