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
    /** Two-finger pinch: one zoom step (out = fingers together). */
    data class Zoom(val inward: Boolean) : ScreenIntent
    /** Four- or five-finger swipe, same meaning as on the trackpad. */
    data class Swipe(val dir: SwipeDir, val fingers: Int) : ScreenIntent
    /** Four- or five-finger pinch: [spread] = apart (show desktop), else together (Launchpad). */
    data class Pinch(val spread: Boolean) : ScreenIntent
    /** One finger pressed down firmly at (x, y) without moving: force click there. */
    data class FirmPress(val x: Float, val y: Float) : ScreenIntent
    data object ResetAll : ScreenIntent
}

/**
 * Touch-screen semantics over a mirrored desktop (§8.7): tap = click at that point, double tap =
 * n:1 then n:2, long-press-then-move = drag, one-finger move = pointer follows, two-finger move =
 * scroll, two-finger pinch = zoom steps, two-finger tap = right click, firm press = force click,
 * four / five fingers = the same swipes and pinches as the trackpad ([MultiFingerGesture]); three
 * fingers do nothing. Same freeze guards as [TrackpadGestureMachine]: CANCEL / watchdog / [reset]
 * release a held button and never click.
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
        val swipePx: Float = 120f,
        val pinchPx: Float = 80f,
        val zoomStartPx: Float = 40f,
        val zoomStepPx: Float = 56f,
        val watchdogMs: Long = 1500,
        val dragWatchdogMs: Long = 6000,
    )

    enum class State { Idle, Pending, Hovering, Dragging, MultiPending, Scrolling, Zooming, Multi, Finished }

    private class Ptr(override var x: Float, override var y: Float, val x0: Float, val y0: Float) : FingerPos

    /** Touch pressure at which a still finger counts as a firm press; infinite = off. */
    var firmPressAt: Float = Float.POSITIVE_INFINITY
    /** Highest pressure seen while one finger was down in the current / last touch. */
    var peakPressure: Float = 0f; private set
    var lastWasFirm: Boolean = false; private set
    /** Most fingers down at once in the last finished touch. */
    var lastTouchFingers: Int = 0; private set

    private val multi = MultiFingerGesture(config.swipePx, config.pinchPx)
    /** Diagnostics for the last touch (see [GestureTrace]). */
    val multiDiag: MultiFingerGesture get() = multi
    private var zoomBase = 0f

    var state = State.Idle; private set
    private val ptrs = LinkedHashMap<Long, Ptr>()
    private var primary = -1L
    private var maxFingers = 0
    private var seqStart = 0L
    private var lastEvent = 0L
    private var multiX = 0f
    private var multiY = 0f
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

    fun onTouch(id: Long, action: TouchAction, x: Float, y: Float, t: Long, pressure: Float = Float.NaN) {
        when (action) {
            TouchAction.DOWN -> down(id, x, y, t)
            TouchAction.MOVE -> move(id, x, y, t)
            TouchAction.UP -> up(id, x, y, t)
            TouchAction.CANCEL -> cancel()
        }
        if (action == TouchAction.DOWN || action == TouchAction.MOVE) pressed(id, pressure, t)
    }

    /** Pressure of the only finger down, from the raw event stream ([PressureFeed]); [t] = its event time. */
    fun onPressure(pressure: Float, t: Long = lastEvent) {
        if (ptrs.size == 1) pressed(primary, pressure, t)
    }

    private var downPressure = Float.NaN

    /**
     * Firm press = the finger stays put for at least [FIRM_MIN_MS] and its pressure *rises* clearly
     * after landing ([FIRM_RISE] × the landing pressure, and at least [firmPressAt]). A hard landing
     * alone is never a firm press, so taps, drags and swipes are never eaten.
     */
    private fun pressed(id: Long, pressure: Float, t: Long = lastEvent) {
        if (pressure.isNaN() || id != primary || ptrs.size != 1) return
        if (downPressure.isNaN()) downPressure = pressure
        if (pressure > peakPressure) peakPressure = pressure
        if (t - seqStart < FIRM_MIN_MS || pressure < downPressure * FIRM_RISE) return
        val p = ptrs[id] ?: return
        if (state == State.Pending && pressure >= firmPressAt && hypot(p.x - p.x0, p.y - p.y0) < config.slopPx) {
            state = State.Finished
            tapCount = 0
            lastWasFirm = true
            emit(ScreenIntent.FirmPress(p.x0, p.y0))
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
            multi.reset()
            peakPressure = 0f
            downPressure = Float.NaN
            lastWasFirm = false
            state = State.Pending
            return
        }
        ptrs[id] = Ptr(x, y, x, y)
        maxFingers = maxOf(maxFingers, ptrs.size)
        when (state) {
            State.Pending, State.Hovering -> {
                state = State.MultiPending
                multiX = 0f; multiY = 0f
                firstLift = -1L; lastLift = -1L
                zoomBase = pairDistance()
            }
            State.MultiPending, State.Scrolling, State.Zooming -> if (ptrs.size >= 3) {
                state = State.Multi
                multi.fingersChanged(ptrs.values)
            }
            State.Multi -> multi.fingersChanged(ptrs.values)
            State.Finished -> if (ptrs.size >= 3 && !multi.fired && !lastWasFirm) {
                state = State.Multi
                multi.fingersChanged(ptrs.values)
            }
            else -> Unit // Dragging keeps following the primary finger
        }
    }

    private fun pairDistance(): Float {
        if (ptrs.size != 2) return 0f
        val (a, b) = ptrs.values.toList()
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun zoomSteps() {
        val d = pairDistance()
        while (d - zoomBase >= config.zoomStepPx) { emit(ScreenIntent.Zoom(inward = false)); zoomBase += config.zoomStepPx }
        while (zoomBase - d >= config.zoomStepPx) { emit(ScreenIntent.Zoom(inward = true)); zoomBase -= config.zoomStepPx }
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
                val travel = hypot(multiX, multiY)
                val pinch = if (ptrs.size == 2) pairDistance() - zoomBase else 0f
                when {
                    ptrs.size == 2 && abs(pinch) >= config.zoomStartPx && abs(pinch) > travel -> {
                        state = State.Zooming
                        zoomSteps()
                    }
                    travel >= config.slopPx -> if (ptrs.size >= 2) {
                        state = State.Scrolling
                        emit(ScreenIntent.Scroll(-multiX, -multiY))
                    } else state = State.Finished
                }
            }
            State.Scrolling -> emit(ScreenIntent.Scroll(-dx / n, -dy / n))
            State.Zooming -> if (ptrs.size == 2) zoomSteps()
            State.Multi -> when (val r = multi.onMove(dx, dy, ptrs.values)) {
                is MultiFingerResult.Swipe -> { state = State.Finished; emit(ScreenIntent.Swipe(r.dir, r.fingers.coerceAtMost(5))) }
                is MultiFingerResult.Pinch -> { state = State.Finished; emit(ScreenIntent.Pinch(r.spread)) }
                null -> Unit
            }
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
            State.Scrolling, State.Zooming -> state = State.Finished
            State.Multi -> multi.fingersChanged(ptrs.values)
            else -> Unit
        }
        if (remaining == 0) {
            lastTouchFingers = maxFingers
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

    private companion object {
        const val FIRM_MIN_MS = 120L
        const val FIRM_RISE = 1.25f
    }
}
