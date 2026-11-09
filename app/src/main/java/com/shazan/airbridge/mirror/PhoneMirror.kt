package com.shazan.airbridge.mirror

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.RoundedCorner
import androidx.core.app.NotificationCompat
import com.shazan.airbridge.R
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.Notifs
import com.shazan.airbridge.net.PeerBus
import com.shazan.airbridge.net.Protocol
import org.json.JSONObject

/**
 * This phone as a `mirror_source` (PROTOCOL.md §8.7, "Phone as source"): a computer's `mirror_start`
 * asks the user for Android's screen-capture consent (every session), then streams the screen through a
 * VirtualDisplay into a hardware [ScreenEncoder] while [MirrorService] holds the `mediaProjection`
 * foreground service. Input from the computer comes back as ordinary `ptr_*`/`key`/`action` messages and
 * goes through the accessibility target path (needs AirBridge Universal Control; without it the stream is
 * view-only and `mirror_state.control` says so).
 *
 * One sink at a time. Network entry points may be called from any thread; all state lives on [h].
 */
object PhoneMirror {
    private const val TAG = "AirBridgeMirrorSrc"
    const val CONSENT_TIMEOUT_MS = 60_000L
    const val NOTIF_REQUEST = 4721
    const val NOTIF_ONGOING = 4722
    const val CH_REQUEST = "airbridge_mirror_request"
    const val CH_ONGOING = "airbridge_mirror"
    /** Transport backlog above which the encoder pauses (a slow link must not build up seconds of video). */
    /**
     * Bytes allowed to wait in the socket queue before the encoder pauses. Small on purpose: 1.5 MB here meant
     * up to a second of picture queued behind a slow Wi-Fi link (visible lag). ~256 KB is a keyframe or a few
     * frames; past it the encoder skips frames, so what arrives is always current.
     */
    private const val MAX_QUEUED_BYTES = 256_000L
    private const val TICK_MS = 100L

    private val thread by lazy { HandlerThread("airbridge-mirror-src").apply { start() } }
    private val h by lazy { Handler(thread.looper) }
    private val app: Context get() = Hub.app

    /** API 29+ (FGS type `mediaProjection`, `MediaCodecInfo.isHardwareAccelerated`) with a hardware H.264 encoder. */
    val supported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 29 && try { ScreenEncoder.hasAvc } catch (_: Throwable) { false }
    }

    /** Peer this phone is streaming to right now (any thread). */
    @Volatile var streamingTo: String? = null; private set
    /** Peer waiting for the user's consent (any thread). */
    @Volatile private var pendingSink: String? = null
    @Volatile private var pendingName: String? = null

    fun isStreamingTo(peerId: String): Boolean = streamingTo == peerId

    /** Computer waiting for consent, for the consent screen; null when nothing is pending. */
    fun pendingComputer(): String? = if (pendingSink != null) pendingName else null

    private class Pending(val sink: String, val name: String, var req: MirrorPlan.Request, var codec: String)
    private var pending: Pending? = null
    private var stream: Stream? = null

    private val consentTimeout = Runnable {
        val p = pending ?: return@Runnable
        Log.i(TAG, "no answer to the screen-capture prompt for ${p.name}")
        refuse(p, "timeout")
    }

    // ------------------------------------------------------------------ network (any thread)

    /** Sink → source messages. Returns true when consumed here (never routed to the input half). */
    fun onControl(peerId: String, msg: JSONObject): Boolean {
        when (msg.optString("t")) {
            "mirror_start" -> { h.post { start(peerId, msg) }; return true }
            "mirror_key" -> { h.post { stream?.takeIf { it.sink == peerId }?.keyframe() }; return true }
            "mirror_ack" -> {
                val pts = msg.optLong("pts", -1)
                h.post { stream?.takeIf { it.sink == peerId }?.ack(pts) }
                return true
            }
            "mirror_stop" -> {
                // Only ours when that peer is our sink; otherwise it is a source stopping (our sink role).
                if (streamingTo != peerId && pendingSink != peerId) return false
                h.post { stopFor(peerId, "sink stopped") }
                return true
            }
        }
        return false
    }

    /** A peer's session ended. */
    fun onPeerGone(peerId: String) {
        if (streamingTo != peerId && pendingSink != peerId) return
        h.post { stopFor(peerId, null) }
    }

    // ------------------------------------------------------------------ consent (main thread)

    /** The consent screen got a projection token: start the foreground service from the visible activity. */
    fun onConsent(ctx: Context, resultCode: Int, data: Intent) {
        val name = pendingName
        if (pendingSink == null || name == null) { Log.i(TAG, "consent arrived after the request ended"); return }
        try {
            MirrorService.start(ctx, resultCode, data, name)
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the mirror service", t)
            h.post { pending?.let { refuse(it, "denied") } }
        }
    }

    fun onConsentDenied() { h.post { pending?.let { refuse(it, "denied") } } }

    /** User tapped Stop in the ongoing notification. */
    fun stopFromUser() { h.post { endStream(notifySink = true, reason = "user") } }

    // ------------------------------------------------------------------ service

    fun onProjection(mp: MediaProjection) {
        h.post {
            val p = pending
            if (p == null) {
                try { mp.stop() } catch (_: Throwable) {}
                MirrorService.stop(app)
                return@post
            }
            clearPending()
            val s = Stream(p.sink, p.name, p.req, p.codec, mp)
            stream = s
            // Wi-Fi out of power save while streaming: power-save sleeps add 100 ms or more of lag per frame.
            com.shazan.airbridge.net.TransferLocks.acquire(app)
            streamingTo = p.sink
            if (!s.start()) {
                Log.w(TAG, "stream setup failed")
                send(p.sink, JSONObject().put("t", "error").put("code", "mirror_codec").put("msg", "The phone's video encoder did not start."))
                endStream(notifySink = true, reason = "encoder")
            }
        }
    }

    fun onProjectionFailed() { h.post { pending?.let { refuse(it, "denied") } } }

    fun onServiceDestroyed() { h.post { if (stream != null) endStream(notifySink = true, reason = "ended") } }

    // ------------------------------------------------------------------ state machine (h)

    private fun send(peer: String, m: JSONObject) { try { PeerBus.send(peer, m) } catch (t: Throwable) { Log.w(TAG, "send", t) } }

    private fun stopMsg(reason: String?) = JSONObject().put("t", "mirror_stop").apply { if (reason != null) put("reason", reason) }

    private fun start(peer: String, msg: JSONObject) {
        val info = PeerBus.peers().firstOrNull { it.id == peer } ?: return
        if ("mirror_sink" !in info.caps) { Log.i(TAG, "ignoring mirror_start from non-sink ${info.name}"); return }
        val req = MirrorPlan.parseStart(msg)
        val codec = if (supported) MirrorPlan.chooseCodec(req.codecs, ScreenEncoder.hasHevc, ScreenEncoder.hasAvc) else null
        if (codec == null) {
            send(peer, JSONObject().put("t", "error").put("code", "mirror_codec").put("msg", "This phone has no hardware encoder for the requested codecs."))
            send(peer, stopMsg("codec"))
            return
        }
        stream?.let { s ->
            if (s.sink == peer) { s.update(req, codec); return } // same computer asks again: no new prompt
            endStream(notifySink = true, reason = "replaced")
        }
        pending?.let { old ->
            if (old.sink == peer) {
                old.req = req; old.codec = codec
                send(peer, JSONObject().put("t", "mirror_wait").put("reason", "consent"))
                return
            }
            send(old.sink, stopMsg("replaced"))
            clearPending()
        }
        val p = Pending(peer, info.name, req, codec)
        pending = p
        pendingName = info.name
        pendingSink = peer
        send(peer, JSONObject().put("t", "mirror_wait").put("reason", "consent"))
        h.removeCallbacks(consentTimeout)
        h.postDelayed(consentTimeout, CONSENT_TIMEOUT_MS)
        Hub.main.post { askConsent(info.name) }
        Log.i(TAG, "${info.name} asks to show this screen ($codec, max ${req.maxW}x${req.maxH}@${req.fps})")
    }

    private fun refuse(p: Pending, reason: String) {
        if (pending !== p) return
        clearPending()
        val text = if (reason == "timeout") "Nobody allowed it on the phone." else "Not allowed on the phone."
        send(p.sink, JSONObject().put("t", "error").put("code", "mirror_permission").put("reason", reason).put("msg", text))
        send(p.sink, stopMsg(reason))
    }

    private fun clearPending() {
        pending = null
        pendingSink = null
        h.removeCallbacks(consentTimeout)
        Hub.main.post { try { Notifs.cancel(NOTIF_REQUEST) } catch (_: Throwable) {} }
    }

    private fun stopFor(peer: String, reason: String?) {
        pending?.takeIf { it.sink == peer }?.let { clearPending() }
        if (stream?.sink == peer) endStream(notifySink = false, reason = reason ?: "sink gone")
    }

    private fun endStream(notifySink: Boolean, reason: String) {
        val s = stream ?: return
        stream = null
        streamingTo = null
        s.release()
        com.shazan.airbridge.net.TransferLocks.release()
        if (notifySink) send(s.sink, stopMsg(reason))
        Log.i(TAG, "stopped showing the screen to ${s.name} ($reason)")
        try { MirrorService.stop(app) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ UI (main)

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_REQUEST, ctx.getString(R.string.pm_channel_request), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_ONGOING, ctx.getString(R.string.pm_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
        ))
    }

    /** Heads-up "Shazan's Mac wants to show this phone's screen · Tap to allow", plus a direct launch when allowed. */
    private fun askConsent(computer: String) {
        val ctx = app
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, NOTIF_REQUEST, MirrorConsentActivity.intent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(ctx, NOTIF_REQUEST,
            Intent(ctx, MirrorActionReceiver::class.java).setAction(MirrorActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_REQUEST)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(ctx.getString(R.string.pm_request_title, computer))
            .setContentText(ctx.getString(R.string.pm_request_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(CONSENT_TIMEOUT_MS)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.pm_request_decline), decline)
            .build()
        Notifs.notify(NOTIF_REQUEST, n)
        // Allowed while an AirBridge screen is open or Universal Control is on (a system-bound accessibility
        // service may start activities); otherwise Android blocks it quietly and the notification stays.
        try { ctx.startActivity(MirrorConsentActivity.intent(ctx)) } catch (t: Throwable) { Log.i(TAG, "consent screen not started directly: $t") }
    }

    fun ongoingNotification(ctx: Context, computer: String): android.app.Notification {
        ensureChannels(ctx)
        val stop = PendingIntent.getBroadcast(ctx, NOTIF_ONGOING,
            Intent(ctx, MirrorActionReceiver::class.java).setAction(MirrorActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(ctx.getString(R.string.pm_ongoing_title, computer))
            .setContentText(ctx.getString(R.string.pm_ongoing_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(Notifs.openAppIntent(ctx))
            .addAction(0, ctx.getString(R.string.pm_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ------------------------------------------------------------------ one stream (h)

    private class Screen(val w: Int, val h: Int, val dpi: Int, val corner: Int)

    @SuppressLint("NewApi") // only constructed when [supported] (API 29+)
    private class Stream(
        val sink: String,
        val name: String,
        var req: MirrorPlan.Request,
        var codec: String,
        val projection: MediaProjection,
    ) : ScreenEncoder.Listener {
        private val dm = app.getSystemService(DisplayManager::class.java)
        private val power = app.getSystemService(PowerManager::class.java)
        private val keyguard = app.getSystemService(KeyguardManager::class.java)
        private var enc: ScreenEncoder? = null
        private var vd: VirtualDisplay? = null
        private var screen: Screen? = null
        private var flow = MirrorPlan.Flow(req.bitrate, SystemClock.uptimeMillis(), req.ackEvery)
        private var needKey = true
        private var released = false
        private var lastState: String? = null
        private var ticks = 0
        private var lastRestart = 0L

        private val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                h.post {
                    if (stream !== this@Stream) return@post
                    val locked = isLocked()
                    PhoneMirror.endStream(notifySink = true, reason = if (locked) "locked" else "ended")
                }
            }
        }

        private val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) checkDisplay() }
        }

        private val ticker = object : Runnable {
            override fun run() {
                if (released) return
                tick()
                h.postDelayed(this, TICK_MS)
            }
        }

        fun start(): Boolean {
            try {
                projection.registerCallback(projectionCallback, h) // API 34+: before createVirtualDisplay
            } catch (t: Throwable) { Log.w(TAG, "projection callback", t) }
            val sc = measure() ?: return false
            val e = newEncoder(sc) ?: return false
            vd = try {
                projection.createVirtualDisplay("AirBridge mirror", e.width, e.height, sc.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, e.inputSurface, null, h)
            } catch (t: Throwable) {
                Log.w(TAG, "createVirtualDisplay failed", t)
                null
            } ?: return false
            screen = sc
            try { dm?.registerDisplayListener(displayListener, h) } catch (_: Throwable) {}
            sendInfo()
            sendState(force = true)
            h.postDelayed(ticker, TICK_MS)
            return true
        }

        private fun newEncoder(sc: Screen): ScreenEncoder? {
            val size = ScreenEncoder.fitFor(codec, sc.w, sc.h, req.maxW, req.maxH, req.fps)
            val e = ScreenEncoder.create(codec, size.w, size.h, req.fps, flow.bitrate, h, this) ?: return null
            enc = e
            needKey = true
            return e
        }

        @Suppress("DEPRECATION")
        private fun measure(): Screen? {
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val p = Point()
            d.getRealSize(p)
            val m = DisplayMetrics()
            d.getRealMetrics(m)
            val corner = if (Build.VERSION.SDK_INT >= 31) try {
                d.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            } catch (_: Throwable) { 0 } else 0
            if (p.x <= 0 || p.y <= 0) return null
            return Screen(p.x, p.y, m.densityDpi, corner)
        }

        /** Rotation / resolution change: new encoder at the new size, same virtual display and projection. */
        fun checkDisplay(force: Boolean = false) {
            if (released) return
            val sc = measure() ?: return
            val old = screen
            if (!force && old != null && old.w == sc.w && old.h == sc.h && old.dpi == sc.dpi) return
            reconfigure(sc)
        }

        private fun reconfigure(sc: Screen) {
            val v = vd ?: return
            try { v.surface = null } catch (_: Throwable) {}
            enc?.release()
            enc = null
            val e = newEncoder(sc)
            if (e == null) {
                send(sink, JSONObject().put("t", "error").put("code", "mirror_codec").put("msg", "The phone's video encoder stopped."))
                PhoneMirror.endStream(notifySink = true, reason = "encoder")
                return
            }
            try {
                v.resize(e.width, e.height, sc.dpi)
                v.surface = e.inputSurface
            } catch (t: Throwable) {
                Log.w(TAG, "virtual display resize failed", t)
                PhoneMirror.endStream(notifySink = true, reason = "display")
                return
            }
            screen = sc
            flow.reset(SystemClock.uptimeMillis())
            sendInfo()
            Log.i(TAG, "screen now ${sc.w}x${sc.h}, stream ${e.width}x${e.height}")
        }

        /** Same sink sent `mirror_start` again (e.g. after its decoder reset). */
        fun update(r: MirrorPlan.Request, c: String) {
            val changed = r.maxW != req.maxW || r.maxH != req.maxH || c != codec || r.fps != req.fps
            req = r; codec = c
            if (r.bitrate != flow.bitrate && changed) flow = MirrorPlan.Flow(r.bitrate, SystemClock.uptimeMillis(), r.ackEvery)
            if (changed) { screen?.let { reconfigure(measure() ?: it) }; return }
            flow.reset(SystemClock.uptimeMillis())
            sendInfo()
            sendState(force = true)
            keyframe()
        }

        fun keyframe() { enc?.requestKeyframe() }

        fun ack(pts: Long) {
            if (pts < 0) return
            flow.onAck(pts, SystemClock.uptimeMillis())?.let { enc?.setBitrate(it) }
            updateSuspend()
        }

        private fun queued(): Long = try { Hub.readySession(sink)?.link?.queuedBytes() ?: 0L } catch (_: Throwable) { 0L }

        private fun updateSuspend() { enc?.suspend(!flow.open || queued() > MAX_QUEUED_BYTES) }

        override fun onFrame(enc: ScreenEncoder, ptsUs: Long, key: Boolean, params: Boolean, annexB: ByteArray) {
            if (released || enc !== this.enc) return
            if (flow.full || queued() > MAX_QUEUED_BYTES * 2) {
                // Already encoded but nowhere to go: drop it and restart the chain at the next keyframe.
                needKey = true
                enc.requestKeyframe()
                updateSuspend()
                return
            }
            if (needKey && !key) { enc.requestKeyframe(); return }
            val frame = MirrorPlan.videoFrame(ptsUs, key, params, annexB)
            if (frame.size > Protocol.MAX_BINARY_FRAME) {
                Log.w(TAG, "frame too large (${frame.size} bytes), dropping")
                needKey = true
                enc.requestKeyframe()
                flow.lower(0.5, SystemClock.uptimeMillis())?.let { enc.setBitrate(it) }
                return
            }
            if (!PeerBus.sendBinary(sink, frame)) return
            if (key) needKey = false
            flow.onSent(ptsUs, SystemClock.uptimeMillis())
            updateSuspend()
        }

        override fun onError(enc: ScreenEncoder, e: Throwable) {
            if (released || enc !== this.enc) return
            Log.w(TAG, "encoder error", e)
            val now = SystemClock.uptimeMillis()
            if (now - lastRestart > 5_000) {
                lastRestart = now
                screen?.let { reconfigure(measure() ?: it) }
            } else {
                send(sink, JSONObject().put("t", "error").put("code", "mirror_codec").put("msg", "The phone's video encoder stopped."))
                PhoneMirror.endStream(notifySink = true, reason = "encoder")
            }
        }

        private fun tick() {
            val now = SystemClock.uptimeMillis()
            when (val t = flow.tick(now)) {
                MirrorPlan.Flow.Tick.Stop -> { PhoneMirror.endStream(notifySink = true, reason = "no ack"); return }
                is MirrorPlan.Flow.Tick.Bitrate -> enc?.setBitrate(t.bps)
                MirrorPlan.Flow.Tick.Nothing -> Unit
            }
            updateSuspend()
            if (++ticks % 5 == 0) {
                sendState(force = false)
                checkDisplay() // safety net for skins that don't report rotation through DisplayListener
            }
        }

        private fun isLocked(): Boolean =
            (keyguard?.isKeyguardLocked ?: false) || !(power?.isInteractive ?: true)

        private fun sendInfo() {
            val e = enc ?: return
            val sc = screen ?: measure() ?: return
            val display = JSONObject().put("w", sc.w).put("h", sc.h)
            if (sc.corner > 0) display.put("corner", sc.corner)
            send(sink, JSONObject().put("t", "mirror_info").put("codec", e.codec).put("w", e.width).put("h", e.height)
                .put("fps", e.fps).put("display", display))
        }

        /** `mirror_state`: can the computer control us, type, and is the phone locked. Sent on change. */
        private fun sendState(force: Boolean) {
            val control = Protocol.targetAvailable()
            val typing = Protocol.typingAvailable()
            val locked = isLocked()
            val key = "$control/$typing/$locked"
            if (!force && key == lastState) return
            lastState = key
            send(sink, JSONObject().put("t", "mirror_state").put("control", control).put("typing", typing).put("locked", locked))
        }

        fun release() {
            if (released) return
            released = true
            h.removeCallbacks(ticker)
            try { dm?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
            try { vd?.release() } catch (_: Throwable) {}
            vd = null
            enc?.release()
            enc = null
            try { projection.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
            try { projection.stop() } catch (_: Throwable) {}
        }
    }
}
