package com.shazan.airbridge.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Screen-mode (mirror sink) intents. Positions/deltas are in view pixels; the adapter normalises. */
sealed interface ScreenIntent {
    /** Pointer follows the finger, no button (one-finger move without a long-press). */
    data class Hover(val x: Float, val y: Float) : ScreenIntent
    /** `ptr_abs` to (x, y) then `ptr_click`. */
    data class Tap(val x: Float, val y: Float, val button: Int, val count: Int) : ScreenIntent
    /** `ptr_abs` + `ptr_btn down` (long-press). */
    data class Press(val x: Float, val y: Float) : ScreenIntent
    /** `ptr_abs` while the button is held. */
    data class DragTo(val x: Float, val y: Float) : ScreenIntent
    /** `ptr_abs` + `ptr_btn up`; x/y are NaN when the position is unknown (cancel): button up only. */
    data class Release(val x: Float, val y: Float) : ScreenIntent
    /** Content motion in view px (positive dy = content moves up). Content follows the fingers. */
    data class Scroll(val dx: Float, val dy: Float) : ScreenIntent
    data object ResetAll : ScreenIntent
}

/**
 * Touch-screen semantics over a mirrored desktop (§8.7): tap = click at that point, double tap =
 * n:1 then n:2, long-press-then-move = drag, one-finger move = pointer follows, two-finger move =
 * scroll, two-finger tap = right click, pinch and 3+ fingers are ignored. Same freeze guards as
 * [TrackpadGestureMachine]: CANCEL / watchdog / [reset] release a held button and never click.
 */
class ScreenGestureMachine(
    private val config: Config,
    private val emit: (ScreenIntent) -> Unit,
) {
    data class Config(
        val slopPx: Float = 16f,
        val longPressMs: Long = 500,
        val doubleTapMs: Long = 300,
        val doubleTapSlopPx: Float = 60f,
        val twoFingerLiftSpreadMs: Long = 250,
        val twoFingerTapMaxMs: Long = 400,
        val watchdogMs: Long = 1500,
        val dragWatchdogMs: Long = 6000,
    )

    enum class State { Idle, Pending, Hovering, Dragging, MultiPending, Scrolling, Finished }

    private class Ptr(var x: Float, var y: Float, val x0: Float, val y0: Float)

    var state = State.Idle; private set
    private val ptrs = LinkedHashMap<Long, Ptr>()
    private var primary = -1L
    private var maxFingers = 0
    private var seqStart = 0L
    private var lastEvent = 0L
    private var multiX = 0f
    private var multiY = 0f
    private var spread0 = 0f
    private var firstLift = -1L
    private var lastLift = -1L
    private var lastTapUp = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapCount = 0
    private var primX0 = 0f
    private var primY0 = 0f

    val isIdle: Boolean get() = state == State.Idle && ptrs.isEmpty()

    fun nextDeadline(): Long? {
        if (ptrs.isEmpty()) return null
        val wd = lastEvent + if (state == State.Dragging) config.dragWatchdogMs else config.watchdogMs
        return if (state == State.Pending) min(seqStart + config.longPressMs, wd) else wd
    }

    fun onTouch(id: Long, action: TouchAction, x: Float, y: Float, t: Long) {
        when (action) {
            TouchAction.DOWN -> down(id, x, y, t)
            TouchAction.MOVE -> move(id, x, y, t)
            TouchAction.UP -> up(id, x, y, t)
            TouchAction.CANCEL -> cancel()
        }
    }

    fun tick(t: Long) {
        if (ptrs.isEmpty()) return
        val limit = if (state == State.Dragging) config.dragWatchdogMs else config.watchdogMs
        if (t - lastEvent >= limit) { cancel(); return }
        if (state == State.Pending && t - seqStart >= config.longPressMs) {
            val p = ptrs[primary] ?: return
            state = State.Dragging
            tapCount = 0
            emit(ScreenIntent.Press(p.x0, p.y0))
        }
    }

    fun reset() = cancel()

    private fun down(id: Long, x: Float, y: Float, t: Long) {
        lastEvent = t
        if (ptrs.containsKey(id)) return
        if (ptrs.isEmpty()) {
            ptrs[id] = Ptr(x, y, x, y)
            primary = id
            primX0 = x; primY0 = y
            maxFingers = 1
            seqStart = t
            state = State.Pending
            return
        }
        ptrs[id] = Ptr(x, y, x, y)
        maxFingers = maxOf(maxFingers, ptrs.size)
        when {
            ptrs.size >= 3 -> if (state != State.Dragging) state = State.Finished
            state == State.Pending || state == State.Hovering -> {
                state = State.MultiPending
                multiX = 0f; multiY = 0f
                firstLift = -1L; lastLift = -1L
                spread0 = spread()
            }
        }
    }

    private fun spread(): Float {
        val v = ptrs.values.take(2)
        return if (v.size < 2) 0f else hypot(v[0].x - v[1].x, v[0].y - v[1].y)
    }

    private fun move(id: Long, x: Float, y: Float, t: Long) {
        val p = ptrs[id] ?: return
        lastEvent = t
        val dx = x - p.x
        val dy = y - p.y
        p.x = x; p.y = y
        if (dx == 0f && dy == 0f) return
        val n = ptrs.size.toFloat()
        when (state) {
            State.Pending -> if (id == primary && hypot(x - p.x0, y - p.y0) >= config.slopPx) {
                state = State.Hovering
                tapCount = 0
                emit(ScreenIntent.Hover(x, y))
            }
            State.Hovering -> if (id == primary) emit(ScreenIntent.Hover(x, y))
            State.Dragging -> if (id == primary) emit(ScreenIntent.DragTo(x, y))
            State.MultiPending -> {
                multiX += dx / n; multiY += dy / n
                val pinch = abs(spread() - spread0)
                when {
                    ptrs.size >= 2 && pinch >= 2 * config.slopPx && pinch > hypot(multiX, multiY) ->
                        state = State.Finished // pinch: ignored for now
                    hypot(multiX, multiY) >= config.slopPx -> if (ptrs.size >= 2) {
                        state = State.Scrolling
                        emit(ScreenIntent.Scroll(-multiX, -multiY))
                    } else state = State.Finished
                }
            }
            State.Scrolling -> emit(ScreenIntent.Scroll(-dx / n, -dy / n))
            State.Finished, State.Idle -> Unit
        }
    }

    private fun up(id: Long, x: Float, y: Float, t: Long) {
        val p = ptrs.remove(id) ?: return
        lastEvent = t
        p.x = x; p.y = y
        val remaining = ptrs.size
        when (state) {
            State.Pending -> if (remaining == 0) {
                tapCount = if (seqStart - lastTapUp <= config.doubleTapMs &&
                    hypot(p.x0 - lastTapX, p.y0 - lastTapY) < config.doubleTapSlopPx
                ) min(tapCount + 1, 3) else 1
                lastTapUp = t; lastTapX = p.x0; lastTapY = p.y0
                emit(ScreenIntent.Tap(p.x0, p.y0, 0, tapCount))
            }
            State.Dragging -> if (id == primary) {
                emit(ScreenIntent.Release(x, y))
                state = State.Finished
            }
            State.MultiPending -> {
                if (firstLift < 0) firstLift = t
                lastLift = t
                if (remaining == 0 && maxFingers == 2 && lastLift - firstLift <= config.twoFingerLiftSpreadMs &&
                    t - seqStart <= config.twoFingerTapMaxMs && hypot(multiX, multiY) < config.slopPx
                ) {
                    tapCount = 0
                    emit(ScreenIntent.Tap(primX0, primY0, 1, 1))
                }
            }
            State.Scrolling -> state = State.Finished
            else -> Unit
        }
        if (remaining == 0) {
            if (maxFingers >= 2) tapCount = 0
            state = State.Idle
            primary = -1
            maxFingers = 0
        }
    }

    private fun cancel() {
        val wasActive = ptrs.isNotEmpty() || state != State.Idle
        if (state == State.Dragging) {
            val p = ptrs[primary]
            emit(ScreenIntent.Release(p?.x ?: Float.NaN, p?.y ?: Float.NaN))
        }
        ptrs.clear()
        state = State.Idle
        primary = -1
        maxFingers = 0
        tapCount = 0
        if (wasActive) emit(ScreenIntent.ResetAll)
    }
}
