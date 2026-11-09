package com.shazan.airbridge.mirror

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Pure decisions for the phone-as-mirror-source direction (PROTOCOL.md §8.7): reading `mirror_start`,
 * picking a codec, fitting the stream size, Annex-B inspection and ack-based flow control. No Android
 * framework types, so everything here is unit tested (MirrorPlanTest).
 */
object MirrorPlan {
    const val DEFAULT_FPS = 60
    const val DEFAULT_BITRATE = 8_000_000
    const val MIN_BITRATE = 600_000
    const val MAX_BITRATE = 40_000_000
    const val MIN_SIDE = 160

    /** `mirror_start` from a sink, clamped to sane values. */
    data class Request(
        val codecs: List<String>,
        val maxW: Int,
        val maxH: Int,
        val fps: Int,
        val bitrate: Int,
        /** How often the sink acks (frames). Sinks that ack every frame let us keep far fewer frames in flight. */
        val ackEvery: Int = 10,
    )

    fun parseStart(m: JSONObject): Request {
        val arr = m.optJSONArray("codecs")
        fun num(k: String): Double? = if (m.has(k)) m.optDouble(k) else null
        return request(arr?.let { a -> (0 until a.length()).map { a.optString(it) } }, num("maxW"), num("maxH"), num("fps"), num("bitrate"))
            .copy(ackEvery = (num("ackEvery")?.takeIf { !it.isNaN() }?.toInt() ?: 10).coerceIn(1, 30))
    }

    /** Clamp the fields of a `mirror_start`; missing or non-finite numbers take the defaults. */
    fun request(codecs: List<String>?, maxW: Double?, maxH: Double?, fps: Double?, bitrate: Double?): Request {
        fun int(v: Double?, def: Int): Int = if (v == null || v.isNaN() || v.isInfinite()) def else v.toInt()
        return Request(
            codecs = codecs?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf("h264"),
            maxW = int(maxW, 1920).coerceIn(MIN_SIDE, 4096),
            maxH = int(maxH, 1920).coerceIn(MIN_SIDE, 4096),
            fps = int(fps, DEFAULT_FPS).coerceIn(10, 120),
            bitrate = int(bitrate, DEFAULT_BITRATE).coerceIn(MIN_BITRATE, MAX_BITRATE),
        )
    }

    /**
     * Codec to stream with. HEVC only when the sink lists it **first** and this phone has a hardware HEVC
     * encoder (it sharpens text at the same bitrate, but H.264 is the safe default); otherwise H.264 when the
     * sink accepts it. A sink that lists only HEVC gets HEVC if possible. Null = nothing in common.
     */
    fun chooseCodec(sinkOrder: List<String>, hevcEncoder: Boolean, avcEncoder: Boolean): String? {
        val wants = sinkOrder.filter { it == "h264" || it == "hevc" }
        if (wants.isEmpty()) return null
        if (wants.first() == "hevc" && hevcEncoder) return "hevc"
        if ("h264" in wants && avcEncoder) return "h264"
        if ("hevc" in wants && hevcEncoder) return "hevc"
        return null
    }

    data class Size(val w: Int, val h: Int)

    /**
     * Stream size for a display of [displayW]×[displayH] pixels: fit inside [maxW]×[maxH] keeping the aspect,
     * never upscale, round down to [align] (≥ 2). If the encoder rejects that size ([supports]), shrink in
     * 10 % steps until it accepts one (or the short side reaches [MIN_SIDE]).
     */
    fun fit(displayW: Int, displayH: Int, maxW: Int, maxH: Int, align: Int = 2, supports: (Int, Int) -> Boolean = { _, _ -> true }): Size {
        val a = max(2, align)
        val dw = max(1, displayW); val dh = max(1, displayH)
        var scale = min(1.0, min(maxW.toDouble() / dw, maxH.toDouble() / dh))
        fun at(s: Double) = Size(max(a, (dw * s).toInt() / a * a), max(a, (dh * s).toInt() / a * a))
        var size = at(scale)
        var guard = 0
        while (!supports(size.w, size.h) && min(size.w, size.h) > MIN_SIDE && guard++ < 40) {
            scale *= 0.9
            size = at(scale)
        }
        return size
    }

    // ------------------------------------------------------------------ Annex-B

    /** NAL unit types (codec-specific numbering) in an Annex-B buffer, in order. */
    fun nalTypes(b: ByteArray, codec: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val h = b[i + 3].toInt() and 0xFF
                out += if (codec == "hevc") (h shr 1) and 0x3F else h and 0x1F
                i += 3
            } else i++
        }
        return out
    }

    fun isParamSet(type: Int, codec: String): Boolean =
        if (codec == "hevc") type in 32..34 else type == 7 || type == 8

    /** VCL (picture data) NAL types: H.264 1..5, HEVC 0..31. */
    fun isVcl(type: Int, codec: String): Boolean = if (codec == "hevc") type in 0..31 else type in 1..5

    fun hasParamSets(b: ByteArray, codec: String): Boolean = nalTypes(b, codec).any { isParamSet(it, codec) }
    fun hasPicture(b: ByteArray, codec: String): Boolean = nalTypes(b, codec).any { isVcl(it, codec) }

    /** Inner binary frame `0x03 | u64 BE pts_us | flags | Annex-B` (§8.7). */
    fun videoFrame(ptsUs: Long, key: Boolean, params: Boolean, annexB: ByteArray, off: Int = 0, len: Int = annexB.size - off): ByteArray {
        val out = ByteArray(10 + len)
        out[0] = 0x03
        for (i in 0 until 8) out[1 + i] = (ptsUs ushr (56 - 8 * i)).toByte()
        out[9] = ((if (key) 1 else 0) or (if (params) 2 else 0)).toByte()
        System.arraycopy(annexB, off, out, 10, len)
        return out
    }

    // ------------------------------------------------------------------ flow control

    /**
     * §8.7 flow control on the source: at most [MAX_UNACKED] frames in flight beyond the last `mirror_ack`;
     * bitrate falls 25 % when the ack lag passes 150 ms (at most every 500 ms) and climbs 10 % every 5 s while
     * healthy, never above the sink's request. Stop when the sink is silent for 3 s.
     */
    class Flow(private val maxBitrate: Int, now: Long, ackEvery: Int = 10) {
        /**
         * Frames allowed in flight. Low latency: a sink that acks every frame gets ~6 (≈100 ms at 60 fps), so a
         * slow link never builds a backlog — the encoder skips frames instead and the picture stays live.
         */
        private val maxUnacked = maxOf(6, ackEvery + 2)
        private val dropAt = maxUnacked + 3
        companion object {
            const val ACK_WINDOW = 10
            const val MAX_UNACKED = ACK_WINDOW + 2
            /** Hard limit: past this, encoded output is dropped and a keyframe requested. */
            const val DROP_AT = MAX_UNACKED + 4
            const val ACK_TIMEOUT_MS = 3_000L
            // Local network: anything past ~80 ms of ack lag means the link can't keep up at this bitrate.
            const val LAG_HIGH_MS = 80.0
            const val LAG_OK_MS = 40.0
        }

        private val sent = ArrayDeque<Pair<Long, Long>>() // pts, sentAt
        var bitrate = maxBitrate; private set
        var lagMs = 0.0; private set
        private var lastAck = now
        private var lastChange = now

        val inFlight: Int get() = sent.size
        /** True while the encoder should keep producing frames. */
        val open: Boolean get() = sent.size < maxUnacked
        /** True when even an already-encoded frame should be dropped. */
        val full: Boolean get() = sent.size >= dropAt

        fun onSent(pts: Long, now: Long) { sent.addLast(pts to now) }

        /** Returns the new bitrate when it was lowered, else null. */
        fun onAck(pts: Long, now: Long): Int? {
            lastAck = now
            var hit: Pair<Long, Long>? = null
            while (sent.isNotEmpty() && sent.first().first <= pts) hit = sent.removeFirst()
            if (hit != null) lagMs = (now - hit.second).toDouble()
            return if (lagMs > LAG_HIGH_MS) lower(0.25, now) else null
        }

        fun lower(fraction: Double, now: Long): Int? {
            if (now - lastChange < 500) return null
            val next = max(MIN_BITRATE, (bitrate * (1 - fraction)).toInt())
            lastChange = now
            if (next == bitrate) return null
            bitrate = next
            return next
        }

        sealed class Tick {
            object Nothing : Tick()
            object Stop : Tick()
            data class Bitrate(val bps: Int) : Tick()
        }

        fun tick(now: Long): Tick {
            // Gone quiet: a frame has waited ACK_TIMEOUT_MS for an ack (counted from the later of the last ack
            // and that frame, so encoder start-up and a still screen never count as silence).
            val oldest = sent.firstOrNull()?.second
            if (oldest != null && now - maxOf(lastAck, oldest) > ACK_TIMEOUT_MS) return Tick.Stop
            if (lagMs < LAG_OK_MS && sent.size < ACK_WINDOW && now - lastChange >= 5_000 && bitrate < maxBitrate) {
                bitrate = min(maxBitrate, (bitrate * 1.1).toInt())
                lastChange = now
                return Tick.Bitrate(bitrate)
            }
            return Tick.Nothing
        }

        /** New encoder (rotation): nothing old is in flight any more, but keep the learned bitrate. */
        fun reset(now: Long) { sent.clear(); lastAck = now }
    }
}
