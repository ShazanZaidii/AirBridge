package com.shazan.airbridge.net

import com.shazan.airbridge.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Constants, message builders and crypto helpers for AirBridge Protocol v2 (docs/PROTOCOL.md). */
object Protocol {
    const val VERSION = 2
    const val SERVICE_TYPE = "_airbridge._tcp."
    const val WS_PATH = "/v2"
    const val PLATFORM = "android"
    val APP_VERSION: String = BuildConfig.VERSION_NAME
    val CAPS = listOf("clip", "xfer", "ctl_target", "trackpad")

    const val HANDSHAKE_TIMEOUT_MS = 10_000L
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

    /** Framing for byte-stream transports (§10): u8 kind | u32 len | payload. */
    const val FRAME_TEXT = 0
    const val FRAME_BINARY = 1

    private val rng = SecureRandom()

    // ---- message builders ----

    fun msg(t: String): JSONObject = JSONObject().put("t", t)

    fun hello(
        id: String, name: String, nonce: ByteArray, screenW: Int, screenH: Int, paired: Boolean?,
    ): JSONObject = msg("hello")
        .put("v", VERSION)
        .put("id", id)
        .put("name", name)
        .put("plat", PLATFORM)
        .put("app", APP_VERSION)
        .put("caps", JSONArray(CAPS))
        .put("nonce", b64(nonce))
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

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** `label | a | "|" | b | "|" | id` as raw bytes (§3.2/§3.3). */
    fun macInput(label: String, a: ByteArray, b: ByteArray, id: String): ByteArray =
        ByteArrayOutputStream().apply {
            write(label.toByteArray(Charsets.UTF_8))
            write(a); write('|'.code); write(b); write('|'.code)
            write(id.toByteArray(Charsets.UTF_8))
        }.toByteArray()

    /** K = HMAC(S, "airbridge/v2/pair|" | clientId | "|" | hostId). */
    fun deriveKey(secret: ByteArray, clientId: String, hostId: String): ByteArray =
        hmac(secret, "airbridge/v2/pair|$clientId|$hostId".toByteArray(Charsets.UTF_8))

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

/** Defensive JSON accessors: never throw on malformed input. */
fun parseJson(text: String): JSONObject? = try { JSONObject(text) } catch (_: Exception) { null }
fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
