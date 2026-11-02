package com.shazan.airbridge.input

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.net.PeerBus
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phone → desktop messages for trackpad/keyboard mode (§8.1 direct mode, no ctl_enter).
 * Moves and scrolls are accumulated and sent once per frame by [flush]. Main thread only.
 */
class TrackpadSender {
    var peerId: String? = null
        set(value) {
            if (value != field) releaseAll()
            field = value
        }

    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var lastFlush = 0L
    var buttonDown = false; private set

    val connected: Boolean get() = peerId != null

    fun move(dx: Float, dy: Float) { moveX += dx; moveY += dy }
    fun scroll(dx: Float, dy: Float) { scrollX += dx; scrollY += dy }

    /** Called every frame; caps the rate at ~120 msg/s per type (§8.2). */
    fun flush() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFlush < 8) return
        lastFlush = now
        if (abs(moveX) >= 0.01f || abs(moveY) >= 0.01f) {
            send(JSONObject().put("t", "ptr_move").put("dx", r(moveX)).put("dy", r(moveY)))
            moveX = 0f; moveY = 0f
        }
        if (abs(scrollX) >= 0.01f || abs(scrollY) >= 0.01f) {
            send(JSONObject().put("t", "ptr_scroll").put("dx", r(scrollX)).put("dy", r(scrollY)).put("px", true))
            scrollX = 0f; scrollY = 0f
        }
    }

    private fun forceFlush() { lastFlush = 0L; flush() }

    fun click(b: Int, n: Int) {
        forceFlush()
        send(JSONObject().put("t", "ptr_click").put("b", b).put("n", n))
    }

    fun button(down: Boolean) {
        if (down == buttonDown) return
        forceFlush()
        buttonDown = down
        send(JSONObject().put("t", "ptr_btn").put("b", 0).put("down", down))
    }

    fun action(name: String) = send(JSONObject().put("t", "action").put("name", name))

    fun key(hid: Int, down: Boolean, mods: Int, rep: Boolean = false, text: String? = null) {
        forceFlush()
        val m = JSONObject().put("t", "key").put("hid", hid).put("down", down).put("mods", mods)
        if (rep) m.put("rep", true)
        if (down && !text.isNullOrEmpty()) m.put("text", text)
        send(m)
    }

    /** Release anything held on the peer (drag button). Keys are released by the keyboard. */
    fun releaseAll() {
        moveX = 0f; moveY = 0f; scrollX = 0f; scrollY = 0f
        if (buttonDown) {
            buttonDown = false
            send(JSONObject().put("t", "ptr_btn").put("b", 0).put("down", false))
        }
    }

    private fun send(m: JSONObject) {
        val p = peerId ?: return
        PeerBus.send(p, m)
    }

    // Two decimals: avoids exponent notation (§9) and pointless precision.
    private fun r(v: Float): Double = Math.round(v * 100.0) / 100.0
}

/** Settings read at gesture time. */
data class TrackpadPrefs(val naturalScroll: Boolean = true)

private enum class Mode { Pending, Move, Drag, Scroll, Swipe }

/**
 * The single trackpad gesture recogniser:
 * - tap (≤180 ms, under touch slop, exactly one finger) → left click; repeated taps → n:2, n:3
 * - two-finger tap → right click; one-finger move → ptr_move; two-finger move → ptr_scroll
 * - long-press then move → drag (ptr_btn down … up)
 * - three-finger swipe → mission_control / app_expose / desktop_left / desktop_right, once
 * - taps starting within 150 ms of a multi-finger gesture are ignored
 * Deltas are window pixels on the natural axes of the current window.
 */
suspend fun PointerInputScope.trackpadGestures(
    sender: TrackpadSender,
    prefs: () -> TrackpadPrefs,
    haptic: (Int) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val swipeDistance = 80.dp.toPx()
    val tapRadius = 40.dp.toPx()
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val multiTapMs = viewConfiguration.doubleTapTimeoutMillis
    var lastTapUp = 0L
    var lastTapPos = Offset.Zero
    var tapCount = 0
    var lastMultiEnd = 0L

    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        first.consume()
        val t0 = SystemClock.uptimeMillis()
        val primary: PointerId = first.id
        val start = first.position
        var maxPointers = 1
        var mode = Mode.Pending
        var primaryTravel = Offset.Zero
        var multiTravel = Offset.Zero
        var swipeFired = false

        try {
            while (true) {
                val waitForLongPress = mode == Mode.Pending && maxPointers == 1
                val event = if (waitForLongPress) {
                    val remaining = max(1L, longPressMs - (SystemClock.uptimeMillis() - t0))
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else awaitPointerEvent()

                if (event == null) { // long-press: start a drag
                    mode = Mode.Drag
                    sender.button(true)
                    haptic(HapticFeedbackConstants.LONG_PRESS)
                    continue
                }

                val down = event.changes.count { it.pressed }
                maxPointers = max(maxPointers, down)
                // Mean motion of fingers that were down in both samples: immune to fingers landing/lifting.
                val moving = event.changes.filter { it.pressed && it.previousPressed }
                val centroidDelta = if (moving.isEmpty()) Offset.Zero
                else moving.fold(Offset.Zero) { acc, c -> acc + (c.position - c.previousPosition) } / moving.size.toFloat()
                event.changes.forEach { it.consume() }

                when {
                    mode == Mode.Drag -> event.changes.firstOrNull { it.id == primary && it.pressed && it.previousPressed }
                        ?.let { val d = it.position - it.previousPosition; sender.move(d.x, d.y) }

                    maxPointers >= 3 -> {
                        mode = Mode.Swipe
                        if (!swipeFired) {
                            multiTravel += centroidDelta
                            if (multiTravel.getDistance() > swipeDistance) {
                                swipeFired = true
                                val name = if (abs(multiTravel.x) > abs(multiTravel.y)) {
                                    // macOS convention: fingers right reveal the desktop on the left
                                    if (multiTravel.x > 0) "desktop_left" else "desktop_right"
                                } else {
                                    if (multiTravel.y < 0) "mission_control" else "app_expose"
                                }
                                sender.action(name)
                                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                        }
                    }

                    maxPointers == 2 -> {
                        if (mode != Mode.Scroll) {
                            multiTravel += centroidDelta
                            if (multiTravel.getDistance() > slop) {
                                mode = Mode.Scroll
                                scrollBy(sender, multiTravel, prefs().naturalScroll)
                            }
                        } else scrollBy(sender, centroidDelta, prefs().naturalScroll)
                    }

                    else -> event.changes.firstOrNull { it.id == primary && it.pressed && it.previousPressed }?.let {
                        val d = it.position - it.previousPosition
                        if (mode == Mode.Pending) {
                            primaryTravel += d
                            if (primaryTravel.getDistance() > slop) {
                                mode = Mode.Move
                                sender.move(primaryTravel.x, primaryTravel.y)
                            }
                        } else sender.move(d.x, d.y)
                    }
                }
                if (down == 0) break
            }
        } finally {
            // Also runs on cancellation (activity paused, composable removed): never leave a button held.
            if (mode == Mode.Drag) sender.button(false)
        }

        val upAt = SystemClock.uptimeMillis()
        val duration = upAt - t0
        if (maxPointers >= 2) {
            if (maxPointers == 2 && mode != Mode.Scroll && duration <= 250 && t0 - lastMultiEnd > 150) {
                sender.click(1, 1)
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            lastMultiEnd = upAt
            tapCount = 0
        } else if (mode == Mode.Pending && duration <= 180 && t0 - lastMultiEnd > 150) {
            tapCount = if (t0 - lastTapUp <= multiTapMs && (start - lastTapPos).getDistance() < tapRadius) tapCount + 1 else 1
            sender.click(0, min(tapCount, 3))
            haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            lastTapUp = upAt
            lastTapPos = start
        } else {
            tapCount = 0
        }
    }
}

private fun scrollBy(sender: TrackpadSender, fingerDelta: Offset, natural: Boolean) {
    // Positive dy = content moves up (§8.2). Natural: content follows the fingers.
    val s = if (natural) -1f else 1f
    sender.scroll(fingerDelta.x * s, fingerDelta.y * s)
}
