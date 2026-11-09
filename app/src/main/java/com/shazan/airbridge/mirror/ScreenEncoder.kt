package com.shazan.airbridge.mirror

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi

/**
 * Real-time surface-input H.264/HEVC encoder for the phone-as-source mirror (§8.7). The virtual display
 * renders into [inputSurface]; encoded access units come back through [Listener.onFrame] as Annex-B with
 * the parameter sets in front of every keyframe. All callbacks run on the handler passed in.
 */
@RequiresApi(29)
class ScreenEncoder private constructor(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    private val mc: MediaCodec,
    val inputSurface: Surface,
) {
    interface Listener {
        fun onFrame(enc: ScreenEncoder, ptsUs: Long, key: Boolean, params: Boolean, annexB: ByteArray)
        fun onError(enc: ScreenEncoder, e: Throwable)
    }

    @Volatile private var released = false
    private var csd: ByteArray? = null
    private var suspended = false

    companion object {
        private const val TAG = "AirBridgeMirrorEnc"

        fun mime(codec: String): String = if (codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Best encoder for [codec]: a hardware one when the phone has it (every real phone does for H.264),
         * otherwise the software encoder (emulators, rare low-end phones) so mirroring still works, just slower.
         * HEVC is hardware-only: a software HEVC encoder is far too slow for a live screen.
         */
        fun encoderFor(codec: String): MediaCodecInfo? = try {
            val mime = mime(codec)
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && !info.isAlias && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            all.firstOrNull { it.isHardwareAccelerated } ?: if (codec == "hevc") null else all.firstOrNull()
        } catch (t: Throwable) { null }

        val hasAvc: Boolean by lazy { encoderFor("h264") != null }
        val hasHevc: Boolean by lazy { encoderFor("hevc") != null }

        /** Size the encoder accepts for a display of [dw]×[dh] within the sink's limits. */
        fun fitFor(codec: String, dw: Int, dh: Int, maxW: Int, maxH: Int, fps: Int): MirrorPlan.Size {
            val vc = try { encoderFor(codec)?.getCapabilitiesForType(mime(codec))?.videoCapabilities } catch (_: Throwable) { null }
            val align = maxOf(2, vc?.widthAlignment ?: 2, vc?.heightAlignment ?: 2)
            return MirrorPlan.fit(dw, dh, maxW, maxH, align) { w, h ->
                vc == null || try { vc.isSizeSupported(w, h) } catch (_: Throwable) { true }
            }
        }

        /**
         * Configure and start an encoder. Tries the low-latency format first and falls back to a minimal one
         * (some vendor encoders refuse unknown keys). Null if neither works.
         */
        fun create(codec: String, width: Int, height: Int, fps: Int, bitrate: Int, handler: Handler, listener: Listener): ScreenEncoder? {
            val info = encoderFor(codec) ?: return null
            val mime = mime(codec)
            val caps = try { info.getCapabilitiesForType(mime) } catch (_: Throwable) { null }
            val rate = caps?.videoCapabilities?.let { vc ->
                try { if (vc.areSizeAndRateSupported(width, height, fps.toDouble())) fps else minOf(fps, 30) } catch (_: Throwable) { fps }
            } ?: fps
            for (full in listOf(true, false)) {
                var mc: MediaCodec? = null
                try {
                    mc = MediaCodec.createByCodecName(info.name)
                    val fmt = format(codec, width, height, rate, bitrate, caps, full)
                    var enc: ScreenEncoder? = null
                    mc.setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {} // surface input
                        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, bi: MediaCodec.BufferInfo) {
                            enc?.output(index, bi)
                        }
                        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                            val en = enc ?: return
                            if (!en.released) listener.onError(en, e)
                        }
                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
                    }, handler)
                    mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = mc.createInputSurface()
                    val e = ScreenEncoder(codec, width, height, rate, mc, surface)
                    e.listener = listener
                    enc = e
                    mc.start()
                    Log.i(TAG, "${info.name} $codec ${width}x$height@$rate ${bitrate / 1000} kbps (${if (full) "low-latency" else "basic"} format)")
                    return e
                } catch (t: Throwable) {
                    Log.w(TAG, "encoder setup failed (${if (full) "low-latency" else "basic"} format)", t)
                    try { mc?.release() } catch (_: Throwable) {}
                }
            }
            return null
        }

        private fun format(codec: String, w: Int, h: Int, fps: Int, bitrate: Int, caps: MediaCodecInfo.CodecCapabilities?, full: Boolean): MediaFormat {
            val fmt = MediaFormat.createVideoFormat(mime(codec), w, h)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A static screen produces no frames: repeat the last one at 10 fps so the picture sharpens and the
            // sink keeps acking (§8.7).
            fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (!full) return fmt
            val enc = caps?.encoderCapabilities
            if (enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } else if (enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            fmt.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            fmt.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            fmt.setInteger(MediaFormat.KEY_LATENCY, 1)
            // Encoder's own low-latency mode (no look-ahead / frame buffering) where the codec supports it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true) {
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            profile(codec, caps)?.let { (p, l) ->
                fmt.setInteger(MediaFormat.KEY_PROFILE, p)
                fmt.setInteger(MediaFormat.KEY_LEVEL, l)
            }
            return fmt
        }

        /**
         * H.264: Constrained Baseline (no B-frames by construction, every decoder takes it), else Baseline.
         * HEVC: Main. Paired with the highest level the encoder lists for it.
         */
        private fun profile(codec: String, caps: MediaCodecInfo.CodecCapabilities?): Pair<Int, Int>? {
            val levels = caps?.profileLevels ?: return null
            val want = if (codec == "hevc") listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            else listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            for (p in want) {
                val l = levels.filter { it.profile == p }.maxOfOrNull { it.level } ?: continue
                return p to l
            }
            return null
        }
    }

    private var listener: Listener? = null
    private var lastPts = -1L

    private fun output(index: Int, bi: MediaCodec.BufferInfo) {
        if (released) return
        val bytes: ByteArray
        try {
            val buf = mc.getOutputBuffer(index)
            bytes = if (buf != null && bi.size > 0) {
                ByteArray(bi.size).also { buf.position(bi.offset); buf.limit(bi.offset + bi.size); buf.get(it) }
            } else ByteArray(0)
            mc.releaseOutputBuffer(index, false)
        } catch (t: Throwable) {
            Log.w(TAG, "output buffer", t)
            return
        }
        if (bytes.isEmpty()) return
        val config = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (config && !MirrorPlan.hasPicture(bytes, codec)) { csd = bytes; return }
        val key = bi.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        var data = bytes
        var params = MirrorPlan.hasParamSets(data, codec)
        if (key && !params) {
            val c = csd
            if (c != null) { data = c + data; params = true }
        }
        var pts = bi.presentationTimeUs
        if (pts <= lastPts) pts = lastPts + 1
        lastPts = pts
        listener?.onFrame(this, pts, key, params, data)
    }

    fun requestKeyframe() = param { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }

    fun setBitrate(bps: Int) = param { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }

    /** Pause/resume consuming frames from the surface (flow control: nothing encoded = nothing dropped). */
    fun suspend(on: Boolean) {
        if (on == suspended) return
        suspended = on
        param { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (on) 1 else 0) }
    }

    private inline fun param(fill: Bundle.() -> Unit) {
        if (released) return
        try { mc.setParameters(Bundle().apply(fill)) } catch (t: Throwable) { Log.w(TAG, "setParameters", t) }
    }

    fun release() {
        if (released) return
        released = true
        try { mc.stop() } catch (_: Throwable) {}
        try { mc.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
    }
}
