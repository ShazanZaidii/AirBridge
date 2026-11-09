package com.shazan.airbridge.input

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shazan.airbridge.net.PeerBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `mirror_info` (§8.7). */
data class MirrorInfo(val codec: String, val w: Int, val h: Int, val fps: Int, val displayW: Int, val displayH: Int)

/** Live decoder statistics for the optional overlay. */
data class MirrorStats(val fps: Int = 0, val lastFrameAt: Long = 0L, val decoder: String = "")

/**
 * Screen-mirror sink (§8.7): asks a `mirror_source` for a stream, decodes `0x03` video frames with
 * [MediaCodec] (async, low-latency) straight to a [Surface], renders only the newest decoded frame,
 * acks every 10 received frames or 500 ms and asks for keyframes on decoder start / loss / reset.
 * Annex-B input is fed whole (SEI and other non-VCL units included); parameter sets are also
 * extracted from the first keyframe into csd-0/csd-1 for decoders that want them at configure.
 *
 * Network callbacks ([onControl], [onFrame]) may come from any thread; all codec work runs on a
 * private decoder thread. [start]/[stop]/[setSurface] may be called from main.
 */
class MirrorSink {
    enum class Phase { Idle, Waiting, Streaming, Stopped, Failed }
    data class Status(val phase: Phase, val message: String? = null)

    companion object {
        private const val TAG = "AirBridgeMirror"
        const val MAX_W = 1920
        const val MAX_H = 1200
        const val FPS = 60
        const val BITRATE = 8_000_000
        private const val ACK_EVERY = 10
        private const val ACK_MAX_GAP_MS = 500L
        private const val KEY_THROTTLE_MS = 500L
        private const val MAX_BACKLOG = 6
        private const val INFO_TIMEOUT_MS = 5000L
        private const val CONSENT_WAIT_MS = 65_000L

        private fun mime(codec: String) = when (codec) {
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> null
        }

        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

        /** Best decoder for [mime], hardware first; null if none. */
        fun decoderFor(mime: String, requireHardware: Boolean): MediaCodecInfo? = try {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            all.firstOrNull { isHardware(it) } ?: if (requireHardware) null else all.firstOrNull()
        } catch (t: Throwable) { null }

        /**
         * Codecs this phone can decode, in preference order. HEVC only with a hardware decoder (a
         * software HEVC decoder cannot keep up with 60 fps); H.264 with any decoder.
         */
        fun decodableCodecs(): List<String> = decodable

        private val decodable: List<String> by lazy {
            val out = ArrayList<String>(2)
            if (decoderFor(MediaFormat.MIMETYPE_VIDEO_HEVC, requireHardware = true) != null) out += "hevc"
            if (decoderFor(MediaFormat.MIMETYPE_VIDEO_AVC, requireHardware = false) != null) out += "h264"
            out
        }
    }

    private val thread = HandlerThread("airbridge-mirror").apply { start() }
    private val h = Handler(thread.looper)

    private val _status = MutableStateFlow(Status(Phase.Idle))
    val status: StateFlow<Status> = _status
    private val _info = MutableStateFlow<MirrorInfo?>(null)
    val info: StateFlow<MirrorInfo?> = _info
    private val _stats = MutableStateFlow(MirrorStats())
    val stats: StateFlow<MirrorStats> = _stats

    // ---- decoder-thread state
    @Volatile private var peerId: String? = null
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecName = ""
    private var csd: ByteArray? = null
    private var waitKey = true
    private val frames = ArrayDeque<Frame>()
    private val inputs = ArrayDeque<Int>()
    private val outputs = ArrayList<Pair<Int, Long>>()
    private var drainPosted = false
    private var rendered = 0L
    private var received = 0L
    private var computer = "computer"
    /** The source is another phone (§8.7 phone as source): its own consent and wording. */
    private var phoneSource = false
    private var stoppedBy: String? = null
    private var lastKeyReq = 0L
    private var lastAckAt = 0L
    private var lastArrivalPts = 0L
    private var fpsWindowStart = 0L
    private var fpsCount = 0
    private var generation = 0

    private class Frame(val pts: Long, val key: Boolean, val params: Boolean, val data: ByteArray, val off: Int)

    private val infoTimeout = Runnable {
        if (_status.value.phase == Phase.Waiting) _status.value = Status(Phase.Failed, com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.mi_no_picture))
    }

    // ------------------------------------------------------------------------------ public API

    /** Ask [peer] to start streaming. Safe to call again (restarts). */
    fun start(peer: String, maxW: Int, maxH: Int, computerLabel: String = "computer", sourceIsPhone: Boolean = false) {
        val codecs = decodableCodecs()
        h.post {
            computer = computerLabel
            phoneSource = sourceIsPhone
            stoppedBy = null
            if (peerId != null && peerId != peer) PeerBus.send(peerId!!, JSONObject().put("t", "mirror_stop"))
            peerId = peer
            resetDecoder()
            _info.value = null
            if (codecs.isEmpty()) {
                _status.value = Status(Phase.Failed, com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.mi_no_decoder))
                return@post
            }
            _status.value = Status(Phase.Waiting)
            val msg = JSONObject().put("t", "mirror_start")
                .put("codecs", JSONArray(codecs))
                .put("maxW", maxW.coerceIn(160, MAX_W))
                .put("maxH", maxH.coerceIn(160, MAX_H))
                .put("fps", FPS)
                .put("bitrate", BITRATE)
            PeerBus.send(peer, msg)
            h.removeCallbacks(infoTimeout)
            h.postDelayed(infoTimeout, INFO_TIMEOUT_MS)
        }
    }

    /** Stop streaming; sends `mirror_stop` unless the source already stopped. */
    fun stop(sendStop: Boolean = true) {
        h.post {
            val p = peerId
            if (sendStop && p != null) PeerBus.send(p, JSONObject().put("t", "mirror_stop"))
            peerId = null
            h.removeCallbacks(infoTimeout)
            resetDecoder()
            _info.value = null
            _status.value = Status(Phase.Idle)
        }
    }

    /** Surface to render into, or null when it is destroyed. Blocks briefly so the codec lets go first. */
    fun setSurface(s: Surface?) {
        val latch = CountDownLatch(1)
        h.post {
            try {
                if (s == null || s !== surface) releaseCodec()
                surface = s
                if (s != null) requestKey(force = true) // decoder starts at the next keyframe
            } finally { latch.countDown() }
        }
        try { latch.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    fun release() {
        stop()
        h.post { releaseCodec(); thread.quitSafely() }
    }

    /** `mirror_info` / `mirror_stop` from a source (any thread). */
    fun onControl(from: String, msg: JSONObject) {
        h.post {
            val type = msg.optString("t")
            // An `error` may follow the source's own mirror_stop: still attribute it.
            if (from != peerId && !(type == "error" && from == stoppedBy)) return@post
            when (type) {
                "mirror_info" -> {
                    val d = msg.optJSONObject("display")
                    val info = MirrorInfo(
                        codec = msg.optString("codec"),
                        w = msg.optInt("w"), h = msg.optInt("h"), fps = msg.optInt("fps", FPS),
                        displayW = d?.optInt("w") ?: msg.optInt("w"), displayH = d?.optInt("h") ?: msg.optInt("h"),
                    )
                    if (mime(info.codec) == null || info.w <= 0 || info.h <= 0) {
                        _status.value = Status(Phase.Failed, com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.mi_unsupported, info.codec))
                        return@post
                    }
                    val changed = _info.value?.let { it.codec != info.codec || it.w != info.w || it.h != info.h } ?: true
                    _info.value = info
                    if (_status.value.phase == Phase.Waiting && _status.value.message != null) _status.value = Status(Phase.Waiting)
                    if (changed) resetDecoder()
                    h.removeCallbacks(infoTimeout)
                    h.postDelayed(infoTimeout, INFO_TIMEOUT_MS)
                    requestKey(force = true)
                }
                "mirror_wait" -> {
                    // A phone source asks its user first (§8.7): wait for that, not the usual 5 s.
                    h.removeCallbacks(infoTimeout)
                    h.postDelayed(infoTimeout, CONSENT_WAIT_MS)
                    _status.value = Status(Phase.Waiting, com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.pm_sink_allow, computer))
                }
                "mirror_stop" -> {
                    stoppedBy = from
                    peerId = null
                    h.removeCallbacks(infoTimeout)
                    resetDecoder()
                    // Keep a more specific reason if an `error` already explained it.
                    if (_status.value.phase != Phase.Failed) {
                        _status.value = Status(
                            Phase.Stopped,
                            com.shazan.airbridge.net.Hub.app.getString(
                                if (phoneSource) com.shazan.airbridge.R.string.pm_sink_stopped else com.shazan.airbridge.R.string.mi_stopped, computer),
                        )
                    }
                }
                "error" -> {
                    val text = when (msg.optString("code")) {
                        "mirror_permission" -> com.shazan.airbridge.net.Hub.app.getString(
                            if (phoneSource) com.shazan.airbridge.R.string.pm_sink_denied else com.shazan.airbridge.R.string.mi_permission, computer)
                        "mirror_codec" -> com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.mi_codec, computer)
                        else -> return@post
                    }
                    h.removeCallbacks(infoTimeout)
                    _status.value = Status(Phase.Failed, text)
                }
            }
        }
    }

    /** Inner binary frame `0x03 | u64 pts_us | u8 flags | Annex-B` (any thread). */
    fun onFrame(from: String, frame: ByteArray) {
        if (frame.size < 11 || from != peerId) return
        var pts = 0L
        for (i in 1..8) pts = (pts shl 8) or (frame[i].toLong() and 0xFF)
        val flags = frame[9].toInt()
        val f = Frame(pts, key = flags and 1 != 0, params = flags and 2 != 0, data = frame, off = 10)
        h.post { if (from == peerId) enqueue(f) }
    }

    // ------------------------------------------------------------------------------ decoder thread

    private fun enqueue(f: Frame) {
        lastArrivalPts = f.pts
        // Flow control (§8.7, agreed with the Mac source): ack every 10 *received* frames or every
        // 500 ms, whichever comes first, independent of decoding.
        received++
        if (received % ACK_EVERY == 0L || SystemClock.uptimeMillis() - lastAckAt >= ACK_MAX_GAP_MS) ack(f.pts)
        val info = _info.value ?: return
        if (surface == null) return
        if (f.key) {
            // A keyframe supersedes every older undecoded frame: drop the backlog (latency < 1 frame).
            frames.clear()
            if (f.params) {
                val ps = parameterSets(f, info.codec)
                if (ps != null && !ps.contentEquals(csd)) { releaseCodec(); csd = ps }
            }
            if (codec == null && csd != null && !configure(info)) return
            if (codec != null) waitKey = false
        }
        if (codec == null || waitKey) {
            requestKey()
            return
        }
        frames.addLast(f)
        if (frames.size > MAX_BACKLOG) {
            // Decoder can't keep up: skip to the next keyframe rather than lag behind.
            frames.clear()
            waitKey = true
            requestKey()
        }
        pump()
    }

    private fun configure(info: MirrorInfo): Boolean {
        val mime = mime(info.codec) ?: return false
        val s = surface ?: return false
        val ci = decoderFor(mime, requireHardware = false)
        val gen = ++generation
        return try {
            val c = if (ci != null) MediaCodec.createByCodecName(ci.name) else MediaCodec.createDecoderByType(mime)
            val fmt = MediaFormat.createVideoFormat(mime, info.w, info.h)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf((1 shl 20) + 4096, info.w * info.h))
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            if (Build.VERSION.SDK_INT >= 30) {
                val lowLatency = try {
                    c.codecInfo.getCapabilitiesForType(mime)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                } catch (_: Throwable) { false }
                if (lowLatency) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            csd?.let { applyCsd(fmt, it, info.codec) }
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (gen != generation) return
                    inputs.addLast(index); pump()
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, bi: MediaCodec.BufferInfo) {
                    if (gen != generation) return
                    outputs += index to bi.presentationTimeUs
                    if (!drainPosted) { drainPosted = true; h.post { drain(gen) } }
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    if (gen != generation) return
                    Log.w(TAG, "Decoder error (transient=${e.isTransient})", e)
                    if (!e.isTransient) { releaseCodec(); requestKey(force = true) }
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, h)
            c.configure(fmt, s, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
            codec = c
            codecName = c.name
            waitKey = false
            _status.value = Status(Phase.Streaming)
            requestKey() // decoder (re)started: ask for a fresh keyframe (throttled)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Decoder configure failed", t)
            releaseCodec()
            _status.value = Status(Phase.Failed, com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.mi_decoder_failed))
            false
        }
    }

    private fun pump() {
        val c = codec ?: return
        while (frames.isNotEmpty() && inputs.isNotEmpty()) {
            val f = frames.removeFirst()
            val idx = inputs.removeFirst()
            try {
                val buf: ByteBuffer = c.getInputBuffer(idx) ?: continue
                val len = f.data.size - f.off
                if (len > buf.capacity()) {
                    c.queueInputBuffer(idx, 0, 0, f.pts, 0)
                    waitKey = true; frames.clear(); requestKey()
                    return
                }
                buf.clear()
                buf.put(f.data, f.off, len)
                c.queueInputBuffer(idx, 0, len, f.pts, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (t: Throwable) {
                Log.w(TAG, "queueInputBuffer failed", t)
                releaseCodec(); requestKey(force = true)
                return
            }
        }
    }

    /** Render only the newest decoded frame; older ones are released unrendered. */
    private fun drain(gen: Int) {
        drainPosted = false
        if (gen != generation) { outputs.clear(); return }
        val c = codec ?: run { outputs.clear(); return }
        if (outputs.isEmpty()) return
        val last = outputs.size - 1
        try {
            for ((i, o) in outputs.withIndex()) c.releaseOutputBuffer(o.first, i == last)
        } catch (t: Throwable) {
            Log.w(TAG, "releaseOutputBuffer failed", t)
        }
        val pts = outputs[last].second
        val n = outputs.size
        outputs.clear()
        val now = SystemClock.uptimeMillis()
        rendered += n
        fpsCount++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000) {
            _stats.value = MirrorStats(fpsCount * 1000 / (now - fpsWindowStart).toInt(), now, codecName)
            fpsWindowStart = now; fpsCount = 0
        } else _stats.value = _stats.value.copy(lastFrameAt = now, decoder = codecName)
        if (_status.value.phase != Phase.Streaming) _status.value = Status(Phase.Streaming)
        h.removeCallbacks(infoTimeout)
    }

    private fun ack(pts: Long) {
        val p = peerId ?: return
        lastAckAt = SystemClock.uptimeMillis()
        PeerBus.send(p, JSONObject().put("t", "mirror_ack").put("pts", pts))
    }

    private fun requestKey(force: Boolean = false) {
        val p = peerId ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastKeyReq < KEY_THROTTLE_MS) return
        lastKeyReq = now
        PeerBus.send(p, JSONObject().put("t", "mirror_key"))
    }

    private fun resetDecoder() {
        releaseCodec()
        csd = null
        rendered = 0
        received = 0
        fpsCount = 0; fpsWindowStart = 0
        _stats.value = MirrorStats()
    }

    private fun releaseCodec() {
        generation++
        frames.clear(); inputs.clear(); outputs.clear()
        waitKey = true
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Throwable) {}
        try { c.release() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------------------ Annex-B

    /** Parameter-set NAL units (with start codes) of a keyframe, concatenated; null if none. */
    private fun parameterSets(f: Frame, codec: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        for ((s, e) in nalUnits(f.data, f.off)) {
            val b = f.data[s].toInt() and 0xFF
            val isParam = if (codec == "hevc") ((b shr 1) and 0x3F) in 32..34 else (b and 0x1F) in 7..8
            if (isParam) { out.write(START); out.write(f.data, s, e - s) }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun applyCsd(fmt: MediaFormat, ps: ByteArray, codec: String) {
        if (codec == "hevc") {
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(ps)) // VPS+SPS+PPS
            return
        }
        // H.264: csd-0 = SPS, csd-1 = PPS.
        val sps = java.io.ByteArrayOutputStream()
        val pps = java.io.ByteArrayOutputStream()
        for ((s, e) in nalUnits(ps, 0)) {
            val type = ps[s].toInt() and 0x1F
            val target = if (type == 7) sps else if (type == 8) pps else continue
            target.write(START); target.write(ps, s, e - s)
        }
        if (sps.size() > 0) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps.toByteArray()))
        if (pps.size() > 0) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps.toByteArray()))
    }

    private val START = byteArrayOf(0, 0, 0, 1)
}

/** NAL unit payload ranges [start, end) (start code excluded) in an Annex-B buffer from [off]. */
internal fun nalUnits(b: ByteArray, off: Int): List<Pair<Int, Int>> {
    val starts = ArrayList<Int>() // index of first payload byte
    val codeAt = ArrayList<Int>() // index where the start code begins
    var i = off
    while (i + 2 < b.size) {
        if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
            val begin = if (i > off && b[i - 1].toInt() == 0) i - 1 else i
            codeAt += begin
            starts += i + 3
            i += 3
        } else i++
    }
    val out = ArrayList<Pair<Int, Int>>(starts.size)
    for (k in starts.indices) {
        val end = if (k + 1 < starts.size) codeAt[k + 1] else b.size
        if (end > starts[k]) out += starts[k] to end
    }
    return out
}
