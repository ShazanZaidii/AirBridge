package com.shazan.airbridge.input

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.net.PeerBus
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Phone → desktop messages for trackpad / keyboard / screen modes (§8.1 direct mode, no ctl_enter).
 * Moves and scrolls are accumulated and flushed at most once per 8 ms (≤ 120 msg/s, §8.2), which
 * in practice is once per input frame. Main thread only.
 */
class TrackpadSender {
    /** Platform of the device being driven ("macos", "windows", "android"…): picks gesture actions. */
    @Volatile var targetPlatform: String = "macos"

    var peerId: String? = null
        set(value) {
            if (value != field) releaseAll() // releases go to the previous peer
            field = value
        }

    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var lastFlush = 0L
    private var absX = Float.NaN // pending ptr_abs (coalesced like moves)
    private var absY = Float.NaN
    /** Primary button currently held on the peer (drag). */
    var buttonDown = false; private set
    private val machines = LinkedHashSet<TrackpadGestureMachine>()
    private val resetHooks = LinkedHashSet<() -> Unit>()

    val connected: Boolean get() = peerId != null
    val hasPending: Boolean
        get() = abs(moveX) >= 0.01f || abs(moveY) >= 0.01f || abs(scrollX) >= 0.01f || abs(scrollY) >= 0.01f ||
            !absX.isNaN()

    /** Called after every pointer batch and on timer wake-ups; rate-limited unless [force]. */
    fun flush(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastFlush < 8) return
        lastFlush = now
        if (!absX.isNaN()) {
            send(JSONObject().put("t", "ptr_abs").put("x", r4(absX)).put("y", r4(absY)))
            absX = Float.NaN; absY = Float.NaN
        }
        if (abs(moveX) >= 0.01f || abs(moveY) >= 0.01f) {
            send(JSONObject().put("t", "ptr_move").put("dx", r2(moveX)).put("dy", r2(moveY)))
            moveX = 0f; moveY = 0f
        }
        if (abs(scrollX) >= 0.01f || abs(scrollY) >= 0.01f) {
            send(JSONObject().put("t", "ptr_scroll").put("dx", r2(scrollX)).put("dy", r2(scrollY)).put("px", true))
            scrollX = 0f; scrollY = 0f
        }
    }

    /** Applies one recogniser intent. */
    fun onIntent(i: GestureIntent) {
        when (i) {
            is GestureIntent.Move -> { moveX += i.dx; moveY += i.dy }
            is GestureIntent.Scroll -> { scrollX += i.dx; scrollY += i.dy }
            is GestureIntent.Click -> click(i.button, i.count)
            GestureIntent.DragStart -> button(true)
            GestureIntent.DragEnd -> button(false)
            is GestureIntent.Swipe -> action(swipeAction(i.dir, targetPlatform))
            is GestureIntent.Pinch -> action(pinchAction(i.spread, targetPlatform))
            is GestureIntent.Zoom -> action(if (i.inward) "zoom_out" else "zoom_in")
            GestureIntent.FirmPress -> { flush(force = true); action(FIRM_PRESS_ACTION) }
            GestureIntent.ResetAll -> { flush(force = true); button(false) }
        }
    }

    fun move(dx: Float, dy: Float) { moveX += dx; moveY += dy }
    fun scroll(dx: Float, dy: Float) { scrollX += dx; scrollY += dy }

    fun click(b: Int, n: Int) {
        flush(force = true)
        send(JSONObject().put("t", "ptr_click").put("b", b).put("n", n))
    }

    /** Primary-button down/up; always paired (§8.2). */
    fun button(down: Boolean) {
        if (down == buttonDown) return
        flush(force = true)
        buttonDown = down
        send(JSONObject().put("t", "ptr_btn").put("b", 0).put("down", down))
    }

    /**
     * Absolute position on the target's (mirrored) display, 0..1 (§8.2 `ptr_abs`). Coalesced: only the
     * latest position is sent at the next flush, and always before a following click/button/key.
     */
    fun pointAt(nx: Float, ny: Float) {
        absX = nx.coerceIn(0f, 1f); absY = ny.coerceIn(0f, 1f)
    }

    /** Diagnostics hook: every action as it goes out, and whether a computer was there to get it. */
    var actionTap: ((name: String, connected: Boolean) -> Unit)? = null

    fun action(name: String) {
        actionTap?.invoke(name, peerId != null)
        send(JSONObject().put("t", "action").put("name", name))
    }

    fun key(hid: Int, down: Boolean, mods: Int, rep: Boolean = false, text: String? = null) {
        flush(force = true)
        val m = JSONObject().put("t", "key").put("hid", hid).put("down", down).put("mods", mods)
        if (rep) m.put("rep", true)
        if (down && !text.isNullOrEmpty()) m.put("text", text)
        send(m)
    }

    fun sendRaw(m: JSONObject) = send(m)

    internal fun attach(m: TrackpadGestureMachine) { machines += m }
    internal fun detach(m: TrackpadGestureMachine) { machines -= m }
    /** Extra reset hooks (the screen-mode recogniser). */
    fun addResetHook(h: () -> Unit) { resetHooks += h }
    fun removeResetHook(h: () -> Unit) { resetHooks -= h }

    /**
     * Release anything held on the peer (drag button) and reset every recogniser. Call on pause,
     * peer change and mode switch. Keys are released by the keyboard controller.
     */
    fun releaseAll() {
        val now = SystemClock.uptimeMillis()
        for (m in machines.toList()) m.reset(now)
        for (h in resetHooks.toList()) h()
        moveX = 0f; moveY = 0f; scrollX = 0f; scrollY = 0f
        absX = Float.NaN; absY = Float.NaN
        if (buttonDown) {
            buttonDown = false
            send(JSONObject().put("t", "ptr_btn").put("b", 0).put("down", false))
        }
    }

    private fun send(m: JSONObject) {
        val p = peerId ?: return
        PeerBus.send(p, m)
    }

    companion object {
        /** What a firm press does (§8.5): force click = Look Up / Quick Look on a Mac. */
        const val FIRM_PRESS_ACTION = "look_up"

        /** Four- / five-finger pinch (§8.5): together = Launchpad (`app_drawer`), apart = `show_desktop`. */
        fun pinchAction(spread: Boolean, platform: String = "macos") =
            if (platform == "android") (if (spread) "home" else "app_drawer")
            else (if (spread) "show_desktop" else "app_drawer")

        /**
         * Four- and five-finger swipes (§8.5), same for both: up = Mission Control, down = App
         * Exposé. Fingers moving right reveal the desktop on the left (macOS convention).
         */
        fun swipeAction(dir: SwipeDir, platform: String = "macos") = if (platform == "android") when (dir) {
            // A phone being driven: up = recent apps, down = notifications, sideways = previous app.
            SwipeDir.UP -> "recents"
            SwipeDir.DOWN -> "notifications"
            SwipeDir.LEFT, SwipeDir.RIGHT -> "last_app"
        } else when (dir) {
            SwipeDir.UP -> "mission_control"
            SwipeDir.DOWN -> "app_expose"
            SwipeDir.RIGHT -> "desktop_left"
            SwipeDir.LEFT -> "desktop_right"
        }

        // Rounded so JSON never uses exponent notation (§9).
        internal fun r2(v: Float): Double = Math.round(v * 100.0) / 100.0
        internal fun r4(v: Float): Double = Math.round(v * 10000.0) / 10000.0
    }
}

/** Settings read at gesture time. */
data class TrackpadPrefs(val naturalScroll: Boolean = true, val firmPress: Boolean = true)

/** One pointer event for the recognisers: id, action, position, time, pressure (NaN = unknown). */
internal fun interface TouchSink {
    fun touch(id: Long, action: TouchAction, x: Float, y: Float, t: Long, pressure: Float)
}

/**
 * Feeds one Compose [PointerEvent] into a recogniser as (id, DOWN|MOVE|UP|CANCEL, x, y, t, pressure).
 *
 * - Finger identity is the pointer id; nothing is inferred from `changes.size` (a change list also
 *   contains pointers that went up in this very event).
 * - Compose's synthetic cancel (ACTION_CANCEL, node detached, hit path lost) arrives as pointer-ups
 *   that are *already consumed*; those are reported as CANCEL so they can never become a click.
 */
internal fun feedPointerEvent(event: PointerEvent, sink: TouchSink) {
    var cancelled = false
    for (c in event.changes) {
        val id = c.id.value
        val p = c.position
        val pr = c.pressure
        when {
            c.pressed && !c.previousPressed -> sink.touch(id, TouchAction.DOWN, p.x, p.y, c.uptimeMillis, pr)
            !c.pressed && c.previousPressed -> {
                if (c.isConsumed) cancelled = true
                else sink.touch(id, TouchAction.UP, p.x, p.y, c.uptimeMillis, pr)
            }
            // Every held finger is reported, moved or not: a firm press is a finger that does not move,
            // only its pressure changes. The recognisers ignore zero-length moves otherwise.
            c.pressed -> sink.touch(id, TouchAction.MOVE, p.x, p.y, c.uptimeMillis, pr)
        }
    }
    if (cancelled) {
        val t = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis()
        sink.touch(-1, TouchAction.CANCEL, 0f, 0f, t, Float.NaN)
    }
    event.changes.forEach { it.consume() }
}

/**
 * The trackpad surface: a thin adapter from Compose pointer events to [TrackpadGestureMachine]
 * (see its KDoc for the gesture rules). Wakes up for long-press / watchdog deadlines and to flush
 * coalesced motion; resets (releasing any drag) when the pointer input is cancelled.
 */
suspend fun PointerInputScope.trackpadGestures(
    sender: TrackpadSender,
    prefs: () -> TrackpadPrefs,
    haptic: (Int) -> Unit,
    onSystemTookGesture: (fingers: Int) -> Unit = {},
    pressure: PressureFeed? = null,
) {
    val cfg = TrackpadGestureMachine.Config(
        slopPx = viewConfiguration.touchSlop,
        swipePx = 56.dp.toPx(),
        longPressMs = viewConfiguration.longPressTimeoutMillis,
        doubleTapMs = viewConfiguration.doubleTapTimeoutMillis,
        doubleTapSlopPx = 48.dp.toPx(),
        pinchPx = 40.dp.toPx(),
        zoomStartPx = 16.dp.toPx(),
        zoomStepPx = 24.dp.toPx(),
        naturalScroll = prefs().naturalScroll,
    )
    // Sees the same touches: a 3+ finger touch cancelled before we recognised anything = the system took it.
    val loss = MultiFingerLossDetector(cfg.watchdogMs, onSystemTookGesture)
    val trace = GestureTrace("Trackpad", density)
    val machine = TrackpadGestureMachine(cfg) { intent ->
        when (intent) {
            is GestureIntent.Swipe -> trace.recognised("swipe ${intent.dir.name.lowercase()} (${intent.fingers} fingers)")
            is GestureIntent.Pinch -> trace.recognised(if (intent.spread) "spread" else "pinch")
            is GestureIntent.Click -> trace.recognised(if (intent.button == 1) "right click" else "click ×${intent.count}")
            GestureIntent.DragStart -> trace.recognised("drag")
            GestureIntent.FirmPress -> trace.recognised("firm press")
            is GestureIntent.Zoom -> trace.zoomed()
            is GestureIntent.Move -> trace.moved()
            is GestureIntent.Scroll -> trace.scrolled(intent.dx, intent.dy)
            else -> Unit
        }
        sender.onIntent(intent)
        when (intent) {
            is GestureIntent.Click -> haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            GestureIntent.DragStart -> haptic(HapticFeedbackConstants.LONG_PRESS)
            is GestureIntent.Swipe, is GestureIntent.Pinch -> {
                loss.markHandled()
                haptic(HapticFeedbackConstants.CONFIRM)
            }
            GestureIntent.FirmPress -> haptic(HapticFeedbackConstants.LONG_PRESS)
            is GestureIntent.Zoom -> haptic(HapticFeedbackConstants.CLOCK_TICK)
            else -> Unit
        }
    }
    val lossReset: () -> Unit = { loss.reset() }
    pressure?.listener = { p, t -> machine.onPressure(p, t); sender.flush() }
    val tap: (String, Boolean) -> Unit = trace::sentAction
    sender.actionTap = tap
    sender.attach(machine)
    sender.addResetHook(lossReset)
    try {
        awaitPointerEventScope {
            while (true) {
                val p = prefs()
                machine.naturalScroll = p.naturalScroll
                FirmPress.enabled = p.firmPress
                if (machine.isIdle) machine.firmPressAt = FirmPress.threshold()
                val now = SystemClock.uptimeMillis()
                val deadline = machine.nextDeadline()
                val wait: Long? = when {
                    sender.hasPending -> 8L
                    deadline != null -> max(1L, deadline - now)
                    else -> null
                }
                val event = if (wait == null) awaitPointerEvent()
                else withTimeoutOrNull(wait) { awaitPointerEvent() }
                if (event != null) feedPointerEvent(event) { id, a, x, y, t, pr ->
                    // The recogniser first: a touch the phone took away may still be recovered (and then
                    // marked handled) before the loss detector counts it.
                    trace.onTouch(id, a)
                    machine.onTouch(id, a, x, y, t, pr)
                    loss.onTouch(id, a, t)
                    if (a == TouchAction.CANCEL) trace.cancelled()
                    if (a == TouchAction.UP && machine.isIdle) {
                        val m = machine.multiDiag
                        trace.ended(m.maxTravel, m.maxPinch, m.swipeThreshold, m.pinchThreshold, machine.lastWasFirm)
                        FirmPress.touchEnded(machine.lastTouchFingers, machine.peakPressure, machine.lastWasFirm)
                        if (com.shazan.airbridge.BuildConfig.DEBUG) android.util.Log.d(
                            "AirBridgeTouch",
                            "touch end fingers=${machine.lastTouchFingers} peak=${machine.peakPressure} firm=${machine.lastWasFirm} " +
                                "threshold=${FirmPress.threshold()} samples=${FirmPress.calibrator.sampleCount}",
                        )
                    }
                }
                // Time runs on the touch events' own clock while events are flowing; the wall clock only
                // when none arrived (timeout). A busy main thread that falls behind must never turn queued
                // touches into a long-press or a watchdog reset.
                val t = event?.changes?.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()
                loss.tick(t)
                machine.tick(t)
                sender.flush()
            }
        }
    } finally {
        // Composable left / activity paused / pointer input restarted: never leave a button held.
        loss.reset()
        pressure?.listener = null
        if (sender.actionTap === tap) sender.actionTap = null
        sender.removeResetHook(lossReset)
        machine.reset(SystemClock.uptimeMillis())
        sender.detach(machine)
        sender.flush(force = true)
    }
}
