package com.shazan.airbridge.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shazan.airbridge.net.PeerBus
import com.shazan.airbridge.net.PeerInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Target role (PROTOCOL.md §8): a controller's pointer/keyboard drives this phone through the
 * accessibility service. Entry points may be called from any thread; all work runs on main.
 */
class TargetController(internal val service: AccessibilityService) {

    companion object {
        private const val TAG = "AirBridgeTarget"
        const val GAIN = 1.6f // owner-tuned feel
        private const val LEAVE_PX = 20f
        private const val HOLD_START_MS = 150L // finger goes down for real after this (long-press ≈ 550 ms)
        private const val SEGMENT_MS = 12L
        private const val DIRECT_IDLE_HIDE_MS = 10_000L
        private const val DOUBLE_TAP_WINDOW_MS = 500L
        private const val FLICK_MS = 80L
        private const val SILENCE_CHECK_MS = 2_000L
        /** After a hand-back the cursor keeps following in-flight moves while it fades. */
        private const val FADE_FOLLOW_MS = 160L
    }

    internal val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val cursor = VirtualCursor(service)
    internal val keys = TargetKeys(this)
    private val actions = SystemActions(this)

    /** Logical (unsmoothed) pointer position in display pixels. */
    internal var x = 0f; private set
    internal var y = 0f; private set
    internal val screenW get() = cursor.screenW
    internal val screenH get() = cursor.screenH

    private var controllerId: String? = null
    private var entryEdge: String? = null
    private var overshoot = 0f

    private var btnDown = false
    private var downX = 0f
    private var downY = 0f
    private var drag: DragStroke? = null
    private val holdStart = Runnable { if (btnDown && drag == null) startDrag() }

    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var pendScrollX = 0f
    private var pendScrollY = 0f
    private var flicking = false

    private val idleHide = Runnable { if (controllerId == null) cursor.fadeOut() }

    /** §8.8 carried drag: the ghost that follows our cursor while a controller drags files over. */
    private val carry = DragCarryTarget()

    /** §8.6 lease table: who may drive this phone right now. */
    private val lease = ControlLease()
    private var leaseBubbleOn = false
    private val peerNames = HashMap<String, String>()
    private val leaseExpiry = Runnable { checkLeaseExpiry() }
    private var reportedHolder: String? = null
    private val silenceCheck = object : Runnable {
        override fun run() {
            val h = lease.holder ?: return
            if (lease.isSilent(SystemClock.uptimeMillis(), PeerBus.lastHeard(h))) {
                Log.i(TAG, "no message from lease holder $h for 10 s: releasing")
                dropHolder(h)
            } else main.postDelayed(this, SILENCE_CHECK_MS)
        }
    }
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            if (i?.action == android.content.Intent.ACTION_SCREEN_OFF) main.post { releaseControl("screen off") }
        }
    }
    private var screenOffRegistered = false

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        cursor.attach()
        x = screenW / 2f
        y = screenH / 2f
        try {
            service.registerReceiver(screenOff, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
            screenOffRegistered = true
        } catch (t: Throwable) { Log.w(TAG, "screen-off receiver", t) }
    }

    /** Service stopping (disabled, unbound): hand the cursor back first so the controller isn't stuck. */
    fun stop() {
        val c = controllerId
        if (c != null) try {
            val edge = entryEdge ?: "left"
            PeerBus.send(c, leaveMsg(edge, relAlong(edge)).put("reason", TargetAck.UNAVAILABLE))
        } catch (_: Throwable) {}
        lease.clear()
        controllerId = null
        syncLeaseBubble()
        releaseAll()
        cursor.detach()
        main.removeCallbacksAndMessages(null)
        if (screenOffRegistered) {
            try { service.unregisterReceiver(screenOff) } catch (_: Throwable) {}
            screenOffRegistered = false
        }
    }

    /**
     * Stop being controlled (screen off, "Stop being controlled" in the trackpad screen): the holder
     * gets `ctl_leave` (with our edge and rel if it entered through one), the cursor goes away.
     */
    fun releaseControl(why: String) {
        val h = lease.holder ?: controllerId ?: run { if (cursor.visible) cursor.hide(); return }
        Log.i(TAG, "releasing control of $h: $why")
        if (h == controllerId) { leaveToController(); cursor.hide(); return }
        lease.clear()
        syncLeaseBubble()
        PeerBus.send(h, JSONObject().put("t", "ctl_leave"))
        leaveLocal()
    }

    /** The holder is gone (session ended, silent for 10 s): hide everything, release everything. */
    private fun dropHolder(h: String) {
        applyCarry(carry.onPeerGone(h))
        lease.onPeerGone(h)
        if (controllerId == h || controllerId == null) leaveLocal() else releaseAll()
        syncLeaseBubble()
    }

    fun onConfigurationChanged() = main.post {
        cursor.updateScreen()
        x = clampX(x); y = clampY(y)
        if (cursor.visible) cursor.showAt(x, y)
    }

    // ---------------------------------------------------------------- bus entry points

    fun onControl(peerId: String, msg: JSONObject) {
        main.post {
            try { handle(peerId, msg) } catch (t: Throwable) { Log.w(TAG, "Bad control message ${msg.optString("t")}", t) }
        }
    }

    fun onPeers(peers: List<PeerInfo>) {
        main.post {
            for (p in peers) peerNames[p.id] = p.name
            val h = lease.holder
            // Explicit or implicit holder: its cursor must not outlive its session (§8.6 "No stray state").
            if (h != null && peers.none { it.id == h }) dropHolder(h)
            val c = controllerId
            if (c != null && peers.none { it.id == c }) leaveLocal()
            if (lease.holder == null && controllerId == null && cursor.visible && !cursor.fadingOut) cursor.hide()
        }
    }

    private fun handle(peerId: String, msg: JSONObject) {
        val type = msg.optString("t")
        lease.driving = InputRouter.driving
        when (type) {
            "ctl_enter" -> {
                if (!lease.enterAllowed(peerId)) {
                    // §8.6 "No loops": we are driving this peer right now.
                    Log.i(TAG, "ctl_enter from $peerId -> ctl_leave loop")
                    PeerBus.send(peerId, JSONObject().put("t", "ctl_leave").put("reason", "loop"))
                    return
                }
                val ok = try {
                    enter(peerId, msg.optString("edge", "left"), num(msg, "rel", 0.5f).coerceIn(0f, 1f))
                } catch (t: Throwable) {
                    Log.w(TAG, "ctl_enter failed", t)
                    if (lease.onLeave(peerId)) leaveLocal()
                    false
                }
                syncLeaseBubble()
                // §8.1: answer every ctl_enter at once so the controller never stays captured.
                val reply = if (TargetAck.reply(ok, cursor.shown) == TargetAck.Reply.ACK) {
                    JSONObject().put("t", TargetAck.ACK)
                } else {
                    if (ok && lease.onLeave(peerId)) { leaveLocal(); syncLeaseBubble() }
                    JSONObject().put("t", "ctl_leave").put("reason", TargetAck.UNAVAILABLE)
                }
                Log.i(TAG, "ctl_enter from $peerId -> ${reply.optString("t")} ${reply.optString("reason")}".trimEnd())
                PeerBus.send(peerId, reply)
                return
            }
            "ctl_leave" -> {
                val wasController = controllerId == peerId
                if (lease.onLeave(peerId) || wasController) {
                    lease.onHandBack(peerId, SystemClock.uptimeMillis())
                    leaveLocal()
                }
                syncLeaseBubble()
                return
            }
            // §8.8: only from the explicit (ctl_enter) holder; never lease input themselves.
            "drag_carry", "drag_drop", "drag_cancel" -> { onDrag(peerId, type, msg); return }
        }
        // ptr_* / key / action: lease rules (§8.6).
        when (val d = lease.onInput(peerId, SystemClock.uptimeMillis())) {
            is ControlLease.Decision.Drop -> {
                // Late moves from the controller we just handed back to: the fading cursor follows them.
                if (type == "ptr_move" && peerId == fadingFrom && SystemClock.uptimeMillis() - fadingAt < FADE_FOLLOW_MS) {
                    followFade(num(msg, "dx"), num(msg, "dy"))
                }
                d.busyTo?.let { to ->
                    val holderName = if (d.loop) com.shazan.airbridge.net.Identity.name.ifBlank { com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.this_phone_lc) }
                    else lease.holder?.let { peerNames[it] ?: lookupName(it) } ?: com.shazan.airbridge.net.Hub.app.getString(com.shazan.airbridge.R.string.another_device)
                    PeerBus.send(to, JSONObject().put("t", "ctl_busy").put("holder", holderName))
                }
                return
            }
            is ControlLease.Decision.Accept -> {
                if (d.gained) syncLeaseBubble()
                scheduleLeaseExpiry()
                scheduleSilenceCheck()
            }
        }
        when (type) {
            "ptr_abs" -> absolute(peerId, num(msg, "x", 0.5f), num(msg, "y", 0.5f))
            "ptr_move" -> move(peerId, num(msg, "dx"), num(msg, "dy"))
            "ptr_scroll" -> scroll(peerId, num(msg, "dx"), num(msg, "dy"), msg.optBoolean("px", false))
            "ptr_btn" -> button(peerId, msg.optInt("b", 0), msg.optBoolean("down", false))
            "ptr_click" -> click(peerId, msg.optInt("b", 0), msg.optInt("n", 1))
            "key" -> keys.onKey(
                msg.optInt("hid", 0), msg.optBoolean("down", false), msg.optInt("mods", 0),
                msg.optBoolean("rep", false), msg.optString("text", "").ifEmpty { null },
            )
            "action" -> actions.perform(msg.optString("name"))
        }
    }

    // ---------------------------------------------------------------- carried drag (§8.8)

    private fun onDrag(peerId: String, type: String, msg: JSONObject) {
        val id = if (msg.has("id")) msg.optLong("id", -1L) else -1L
        val effect = when (type) {
            "drag_carry" -> {
                val names = msg.optJSONArray("names").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it, "") } }
                val kinds = msg.optJSONArray("kinds").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it, "") } }
                val info = DragCarryTarget.info(
                    peerId, id.takeIf { it >= 0 }, names, kinds,
                    if (msg.has("count")) msg.optInt("count", 0) else null,
                    if (msg.has("totalBytes")) msg.optLong("totalBytes", 0L) else null,
                ) ?: return
                carry.onCarry(peerId, controllerId, info)
            }
            "drag_drop" -> carry.onDrop(peerId, id)
            else -> carry.onCancel(peerId, id)
        }
        applyCarry(effect)
    }

    private fun applyCarry(e: DragCarryTarget.Effect) {
        val res = service.resources
        when (e) {
            is DragCarryTarget.Effect.Show -> {
                val i = e.info
                val size = if (i.totalBytes > 0) android.text.format.Formatter.formatShortFileSize(service, i.totalBytes) else ""
                if (i.count <= 1) cursor.showGhost(i.firstName, size, i.kind, 1)
                else cursor.showGhost(res.getQuantityString(com.shazan.airbridge.R.plurals.drag_items, i.count, i.count),
                    size.ifEmpty { res.getString(com.shazan.airbridge.R.string.drag_and_more, i.firstName) }, i.kind, i.count)
                Log.i(TAG, "carry ${i.id} from ${i.from}: ${i.count} item(s)")
            }
            is DragCarryTarget.Effect.Dropped -> {
                val i = e.info
                cursor.hideGhost(dropped = true)
                val from = peerNames[i.from] ?: lookupName(i.from) ?: res.getString(com.shazan.airbridge.R.string.another_device)
                val text = if (i.count <= 1 && i.firstName.isNotEmpty()) res.getString(com.shazan.airbridge.R.string.drag_receiving_one, i.firstName, from)
                else res.getQuantityString(com.shazan.airbridge.R.plurals.drag_receiving_many, i.count, i.count, from)
                // The files themselves come as a normal §6 transfer right behind this, auto-accepted.
                if (cursor.visible) cursor.showNote(text) else com.shazan.airbridge.net.Hub.toast(text)
                Log.i(TAG, "carry ${i.id} dropped by ${i.from}")
            }
            DragCarryTarget.Effect.Hide -> cursor.hideGhost(dropped = false)
            DragCarryTarget.Effect.None -> {}
        }
    }

    private fun lookupName(id: String): String? =
        try { PeerBus.peers().firstOrNull { it.id == id }?.name?.also { peerNames[id] = it } } catch (_: Throwable) { null }

    private fun scheduleLeaseExpiry() {
        val at = lease.nextExpiry() ?: return
        main.removeCallbacks(leaseExpiry)
        main.postAtTime(leaseExpiry, at + 1)
    }

    private fun checkLeaseExpiry() {
        val released = lease.expire(SystemClock.uptimeMillis())
        if (released != null) {
            releaseAll() // implicit lease expired: release the holder's keys/buttons
            syncLeaseBubble()
        } else scheduleLeaseExpiry()
    }

    private fun scheduleSilenceCheck() {
        main.removeCallbacks(silenceCheck)
        if (lease.holder != null) main.postDelayed(silenceCheck, SILENCE_CHECK_MS)
    }

    /**
     * §5: hide the clipboard bubble while this phone is under a control lease; publish the holder
     * ("Controlling you", trackpad loop guard). Call after every lease change.
     */
    private fun syncLeaseBubble() {
        val h = lease.holder
        if (h != reportedHolder) {
            reportedHolder = h
            InputRouter.setControlledBy(h)
            if (h != null) scheduleSilenceCheck() else main.removeCallbacks(silenceCheck)
        }
        val on = h != null
        if (on == leaseBubbleOn) return
        leaseBubbleOn = on
        NetHooks.setBubbleSuppressed("lease", on)
    }

    private fun num(m: JSONObject, k: String, def: Float = 0f): Float {
        val v = m.optDouble(k, def.toDouble())
        return if (v.isNaN() || v.isInfinite()) def else v.toFloat()
    }

    // ---------------------------------------------------------------- enter / leave

    /** Can we show a cursor and inject input right now? Re-tries the overlay once if it failed before. */
    private fun canBeTarget(): Boolean {
        cursor.updateScreen() // true size/orientation now, so `rel` maps to the matching edge point
        if (!cursor.attached) {
            cursor.attach()
            x = clampX(x); y = clampY(y)
        }
        val gestures = try {
            (service.serviceInfo?.capabilities ?: AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) and
                AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        } catch (_: Throwable) { true }
        return TargetAck.canAccept(com.shazan.airbridge.AirBridgeInputService.isRunning, cursor.attached, gestures, screenW, screenH)
    }

    /** Returns false (lease untouched) when this phone can't be a target right now. */
    private fun enter(peerId: String, edgeIn: String, rel: Float): Boolean {
        if (!canBeTarget()) {
            Log.w(TAG, "ctl_enter from $peerId: cannot show the cursor / inject input")
            return false
        }
        val prev = lease.onEnter(peerId, SystemClock.uptimeMillis())
        main.removeCallbacks(leaseExpiry)
        if (prev != null) {
            // Pre-empted: an edge controller gets its entry edge + current rel; an implicit
            // (trackpad/mirror) holder never entered through an edge and gets a bare ctl_leave.
            val msg = if (prev == controllerId) leaveMsg(entryEdge ?: "left", relAlong(entryEdge ?: "left"))
            else JSONObject().put("t", "ctl_leave")
            PeerBus.send(prev, msg)
        }
        applyCarry(carry.onLeave(null)) // a new controller: any earlier carry is over
        releaseAll()
        main.removeCallbacks(idleHide)
        val edge = if (edgeIn in setOf("left", "right", "top", "bottom")) edgeIn else "left"
        controllerId = peerId
        entryEdge = edge
        overshoot = 0f
        val w = screenW - 1f
        val h = screenH - 1f
        when (edge) {
            "left" -> { x = 0f; y = rel * h }
            "right" -> { x = w; y = rel * h }
            "top" -> { x = rel * w; y = 0f }
            "bottom" -> { x = rel * w; y = h }
        }
        fadingFrom = null
        cursor.showAt(x, y, fade = true) // §8.6: fade in; ctl_ack goes out as the fade starts
        return true
    }

    /**
     * Our cursor went back through the entry edge: hand control back to the controller at once
     * (§8.6), then fade the cursor out while it keeps following the in-flight motion.
     */
    private fun leaveToController(visX: Float = x, visY: Float = y) {
        val c = controllerId ?: return
        val edge = entryEdge ?: "left"
        val rel = relAlong(edge)
        applyCarry(carry.onLeave(c))
        PeerBus.send(c, leaveMsg(edge, rel))
        lease.clear()
        lease.onHandBack(c, SystemClock.uptimeMillis())
        syncLeaseBubble()
        releaseAll()
        controllerId = null
        entryEdge = null
        overshoot = 0f
        fadeX = visX; fadeY = visY
        fadingFrom = c
        fadingAt = SystemClock.uptimeMillis()
        cursor.moveTo(visX, visY)
        cursor.fadeOut()
    }

    private var fadingFrom: String? = null
    private var fadingAt = 0L
    private var fadeX = 0f
    private var fadeY = 0f

    /** Visual only (nothing injected): the fading cursor follows past the edge, no clamp jump. */
    private fun followFade(dx: Float, dy: Float) {
        if (!cursor.fadingOut) return
        fadeX += dx * GAIN; fadeY += dy * GAIN
        cursor.moveTo(fadeX, fadeY)
    }

    private fun leaveLocal() {
        applyCarry(carry.onLeave(null))
        releaseAll()
        cursor.hide()
        fadingFrom = null
        controllerId = null
        entryEdge = null
        overshoot = 0f
    }

    private fun leaveMsg(edge: String, rel: Float) = JSONObject()
        .put("t", "ctl_leave").put("edge", edge).put("rel", Math.round(rel * 10000) / 10000.0)

    private fun relAlong(edge: String): Float = when (edge) {
        "left", "right" -> y / max(1f, screenH - 1f)
        else -> x / max(1f, screenW - 1f)
    }.coerceIn(0f, 1f)

    /** Release everything held (button, gesture, keys, scroll). */
    private fun releaseAll() {
        main.removeCallbacks(holdStart)
        drag?.let { it.moveTo(x, y); it.end() }
        drag = null
        btnDown = false
        cursor.setPressed(false)
        pendScrollX = 0f; pendScrollY = 0f
        keys.releaseAll()
    }

    /** Pointer input from a peer that is not the edge controller = trackpad/direct mode (§8.1). */
    private fun touch(peerId: String) {
        if (controllerId == null && com.shazan.airbridge.mirror.PhoneMirror.isStreamingTo(peerId)) {
            // That computer shows this screen in a window and draws its own pointer there; a second
            // cursor inside the picture would only lag behind it.
            if (cursor.visible) cursor.hide()
            return
        }
        if (controllerId == null) {
            if (!cursor.visible || cursor.fadingOut) cursor.showAt(x, y, fade = true)
            main.removeCallbacks(idleHide)
            main.postDelayed(idleHide, DIRECT_IDLE_HIDE_MS)
        }
    }

    // ---------------------------------------------------------------- pointer

    private fun move(peerId: String, dx: Float, dy: Float) {
        touch(peerId)
        val nx = x + dx * GAIN
        val ny = y + dy * GAIN
        val edge = entryEdge
        if (peerId == controllerId && edge != null && !btnDown) {
            val w = screenW - 1f
            val h = screenH - 1f
            val beyond = when (edge) { "left" -> -nx; "right" -> nx - w; "top" -> -ny; else -> ny - h }
            val towards = when (edge) { "left" -> -dx; "right" -> dx; "top" -> -dy; else -> dy }
            if (beyond > 0f) overshoot += beyond else if (towards < 0f) overshoot = 0f
            if (overshoot > LEAVE_PX) {
                // Visual position continues past the edge (unclamped) so there is no snap.
                leaveToController(clampX(x) + (nx - x).coerceIn(-LEAVE_PX * 4, LEAVE_PX * 4),
                    clampY(y) + (ny - y).coerceIn(-LEAVE_PX * 4, LEAVE_PX * 4))
                return
            }
        }
        x = clampX(nx)
        y = clampY(ny)
        cursor.moveTo(x, y)
        if (btnDown) {
            if (drag == null && hypot(x - downX, y - downY) > 8f * density) startDrag()
            drag?.moveTo(x, y)
        }
    }

    /** `ptr_abs`: normalised position on this display, no gain (§8.2). */
    private fun absolute(peerId: String, nx: Float, ny: Float) {
        touch(peerId)
        x = clampX(nx.coerceIn(0f, 1f) * (screenW - 1f))
        y = clampY(ny.coerceIn(0f, 1f) * (screenH - 1f))
        cursor.moveTo(x, y)
        if (btnDown) {
            if (drag == null && hypot(x - downX, y - downY) > 8f * density) startDrag()
            drag?.moveTo(x, y)
        }
    }

    private fun button(peerId: String, b: Int, down: Boolean) {
        touch(peerId)
        when (b) {
            0 -> if (down) leftDown() else leftUp()
            1 -> if (down) longPress(x, y)
        }
    }

    /** v1 "hold D": the primary button held from the keyboard at the cursor (moves drag). */
    internal fun keyButton(down: Boolean) { if (down) leftDown() else leftUp() }

    private fun leftDown() {
        if (btnDown) return
        btnDown = true
        downX = x; downY = y
        cursor.setPressed(true)
        main.postDelayed(holdStart, HOLD_START_MS)
    }

    private fun leftUp() {
        if (!btnDown) return
        btnDown = false
        cursor.setPressed(false)
        main.removeCallbacks(holdStart)
        val d = drag
        if (d != null) {
            d.moveTo(x, y)
            d.end()
            drag = null
        } else {
            tap(downX, downY)
        }
    }

    private fun startDrag() {
        main.removeCallbacks(holdStart)
        pendScrollX = 0f; pendScrollY = 0f
        drag = DragStroke(clampX(downX), clampY(downY)).also { it.begin(); it.moveTo(x, y) }
    }

    private fun click(peerId: String, b: Int, n: Int) {
        touch(peerId)
        when (b) {
            0 -> if (n <= 1) tap(x, y)
            else {
                // n:2 right after our own single tap = second click of a double click.
                val recent = SystemClock.uptimeMillis() - lastTapAt < DOUBLE_TAP_WINDOW_MS &&
                    hypot(x - lastTapX, y - lastTapY) < 30f * density
                if (recent) tap(x, y) else doubleTap(x, y)
            }
            1 -> longPress(x, y)
        }
    }

    private fun scroll(peerId: String, dx: Float, dy: Float, px: Boolean) {
        touch(peerId)
        if (btnDown) return
        // Positive dy = content moves up = finger moves up (§8.2); dx by analogy.
        val k = if (px) GAIN else 32f * density
        pendScrollX -= dx * k
        pendScrollY -= dy * k
        if (!flicking && (abs(pendScrollX) > 2f || abs(pendScrollY) > 2f)) nextFlick()
    }

    private fun nextFlick() {
        flicking = true
        val sx = clampX(x).coerceIn(1f, screenW - 2f)
        val sy = clampY(y).coerceIn(1f, screenH - 2f)
        var ex = (sx + pendScrollX).coerceIn(1f, screenW - 2f)
        var ey = (sy + pendScrollY).coerceIn(1f, screenH - 2f)
        pendScrollX = 0f; pendScrollY = 0f
        if (ex == sx && ey == sy) { ex += 0.1f; ey += 0.1f }
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val ok = dispatch(GestureDescription.StrokeDescription(path, 0, FLICK_MS), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (abs(pendScrollX) > 2f || abs(pendScrollY) > 2f) nextFlick() else flicking = false
            }
            override fun onCancelled(g: GestureDescription?) {
                pendScrollX = 0f; pendScrollY = 0f; flicking = false
            }
        })
        if (!ok) flicking = false
    }

    // ---------------------------------------------------------------- gestures

    private fun point(x: Float, y: Float): Path {
        val cx = clampX(x); val cy = clampY(y)
        return Path().apply { moveTo(cx, cy); lineTo(if (cx > 1f) cx - 0.1f else cx + 0.1f, cy) }
    }

    internal fun tap(x: Float, y: Float) {
        dispatch(GestureDescription.StrokeDescription(point(x, y), 0, 30))
        lastTapAt = SystemClock.uptimeMillis(); lastTapX = x; lastTapY = y
    }

    private fun doubleTap(x: Float, y: Float) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(point(x, y), 0, 30))
            .addStroke(GestureDescription.StrokeDescription(point(x, y), 110, 30))
            .build()
        dispatchGesture(g, null)
        lastTapAt = SystemClock.uptimeMillis(); lastTapX = x; lastTapY = y
    }

    internal fun longPress(x: Float, y: Float) {
        dispatch(GestureDescription.StrokeDescription(point(x, y), 0, 650))
    }

    /** Directional swipe at the cursor; direction = finger movement. */
    internal fun swipe(dir: String) {
        val sx = clampX(x); val sy = clampY(y)
        val dist = min(screenW, screenH) * 0.5f
        var ex = sx; var ey = sy
        when (dir) {
            "up" -> ey = max(0f, sy - dist)
            "down" -> ey = min(screenH - 1f, sy + dist)
            "left" -> ex = max(0f, sx - dist)
            "right" -> ex = min(screenW - 1f, sx + dist)
            else -> return
        }
        if (ex == sx && ey == sy) return
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(sx, sy); lineTo(ex, ey) }, 0, 150))
    }

    internal fun appDrawerSwipe() {
        val cx = screenW / 2f
        dispatch(GestureDescription.StrokeDescription(
            Path().apply { moveTo(cx, screenH * 0.9f); lineTo(cx, screenH * 0.1f) }, 0, 200,
        ))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription, cb: AccessibilityService.GestureResultCallback? = null): Boolean =
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb)

    private fun dispatchGesture(g: GestureDescription, cb: AccessibilityService.GestureResultCallback?): Boolean = try {
        service.dispatchGesture(g, cb, main)
    } catch (t: Throwable) {
        Log.w(TAG, "dispatchGesture failed", t); false
    }

    internal fun clampX(v: Float) = v.coerceIn(0f, max(0f, screenW - 1f))
    internal fun clampY(v: Float) = v.coerceIn(0f, max(0f, screenH - 1f))

    /**
     * A real-time finger: one open stroke continued segment by segment (`willContinue`), each new
     * segment dispatched only after the previous completes so continuations never cancel each other.
     */
    private inner class DragStroke(private var lastX: Float, private var lastY: Float) {
        private var stroke: GestureDescription.StrokeDescription? = null
        private var wantX = lastX
        private var wantY = lastY
        private var inFlight = false
        private var ending = false
        private var dead = false

        fun begin() {
            val sx = lastX
            lastX = if (sx > 1f) sx - 0.1f else sx + 0.1f
            send(GestureDescription.StrokeDescription(Path().apply { moveTo(sx, lastY); lineTo(lastX, lastY) }, 0, SEGMENT_MS, true))
        }

        fun moveTo(x: Float, y: Float) { wantX = clampX(x); wantY = clampY(y); if (!inFlight) pump() }

        fun end() { ending = true; if (!inFlight) pump() }

        private fun pump() {
            val prev = stroke ?: return
            if (dead) return
            if (!ending && wantX == lastX && wantY == lastY) return
            var tx = wantX; var ty = wantY
            if (tx == lastX && ty == lastY) tx = if (lastX > 1f) lastX - 0.1f else lastX + 0.1f
            val path = Path().apply { moveTo(lastX, lastY); lineTo(tx, ty) }
            lastX = tx; lastY = ty
            val s = try { prev.continueStroke(path, 0, SEGMENT_MS, !ending) } catch (t: Throwable) { dead = true; return }
            send(s)
        }

        private fun send(s: GestureDescription.StrokeDescription) {
            stroke = s
            inFlight = true
            val ok = dispatch(s, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    inFlight = false
                    if (!s.willContinue()) dead = true else pump()
                }
                override fun onCancelled(g: GestureDescription?) { inFlight = false; dead = true }
            })
            if (!ok) { inFlight = false; dead = true }
        }
    }
}
