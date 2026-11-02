package com.shazan.airbridge.input

import android.accessibilityservice.AccessibilityService
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

    private val idleHide = Runnable { if (controllerId == null) cursor.hide() }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        cursor.attach()
        x = screenW / 2f
        y = screenH / 2f
    }

    fun stop() {
        releaseAll()
        cursor.detach()
        main.removeCallbacksAndMessages(null)
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
            val c = controllerId ?: return@post
            if (peers.none { it.id == c }) leaveLocal()
        }
    }

    private fun handle(peerId: String, msg: JSONObject) {
        when (msg.optString("t")) {
            "ctl_enter" -> enter(peerId, msg.optString("edge", "left"), num(msg, "rel", 0.5f).coerceIn(0f, 1f))
            "ctl_leave" -> if (controllerId == null || controllerId == peerId) leaveLocal()
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

    private fun num(m: JSONObject, k: String, def: Float = 0f): Float {
        val v = m.optDouble(k, def.toDouble())
        return if (v.isNaN() || v.isInfinite()) def else v.toFloat()
    }

    // ---------------------------------------------------------------- enter / leave

    private fun enter(peerId: String, edgeIn: String, rel: Float) {
        val prev = controllerId
        if (prev != null && prev != peerId) {
            PeerBus.send(prev, leaveMsg(entryEdge ?: "left", relAlong(entryEdge ?: "left")))
        }
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
        cursor.showAt(x, y)
    }

    /** Our cursor went back through the entry edge: hand control back to the controller. */
    private fun leaveToController() {
        val c = controllerId ?: return
        val edge = entryEdge ?: "left"
        val rel = relAlong(edge)
        leaveLocal()
        PeerBus.send(c, leaveMsg(edge, rel))
    }

    private fun leaveLocal() {
        releaseAll()
        cursor.hide()
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
        if (controllerId == null) {
            if (!cursor.visible) cursor.showAt(x, y)
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
            if (overshoot > LEAVE_PX) { leaveToController(); return }
        }
        x = clampX(nx)
        y = clampY(ny)
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
