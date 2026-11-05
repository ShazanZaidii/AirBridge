package com.shazan.airbridge.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** One raw pointer event fed to the recognisers (no Android/Compose types). */
enum class TouchAction { DOWN, MOVE, UP, CANCEL }

enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

/** What the trackpad recogniser wants the sender to do. Deltas are raw phone pixels. */
sealed interface GestureIntent {
    data class Move(val dx: Float, val dy: Float) : GestureIntent
    /** Content motion (§8.2): positive dy = content moves up. Natural-scroll is already applied. */
    data class Scroll(val dx: Float, val dy: Float) : GestureIntent
    data class Click(val button: Int, val count: Int) : GestureIntent
    data object DragStart : GestureIntent
    data object DragEnd : GestureIntent
    /** Four- or five-finger swipe: direction the fingers moved on screen; same meaning for both counts. */
    data class Swipe(val dir: SwipeDir, val fingers: Int = 4) : GestureIntent
    /** Four- or five-finger pinch: [spread] = fingers moved apart (show desktop), else together (Launchpad). */
    data class Pinch(val spread: Boolean) : GestureIntent
    /** Two-finger pinch: one zoom step each time the fingers have moved [Config.zoomStepPx] apart/together. */
    data class Zoom(val inward: Boolean) : GestureIntent
    /** One finger pressed down firmly without moving (Mac: force click). Replaces the tap. */
    data object FirmPress : GestureIntent
    /** The machine dropped all state (cancel / watchdog / external reset). Release anything held. */
    data object ResetAll : GestureIntent
}

/**
 * The trackpad gesture recogniser as a pure state machine, fed one pointer event at a time and
 * driven by [tick] for time-based transitions (long-press, watchdog). Deterministic and unit-tested
 * (TrackpadGestureMachineTest); [TrackpadSender] adapts Compose pointer events to it.
 *
 * Rules (all thresholds in [Config]):
 * - Finger count is the number of pointers currently down, tracked by id. Classification on release
 *   uses the **maximum** number of fingers that were down at once during the touch sequence.
 * - Tap: max 1 finger, ≤ [Config.tapMaxMs], movement < slop → `Click(0, n)`; n counts taps that start
 *   within [Config.doubleTapMs] of the previous tap's release (1, 2, 3).
 * - Long-press (stationary ≥ [Config.longPressMs]) → `DragStart`, moves, `DragEnd` on release.
 * - Two-finger tap: max 2 fingers, both lifted within [Config.twoFingerLiftSpreadMs] of each other,
 *   whole gesture ≤ [Config.twoFingerTapMaxMs], centroid moved < slop → `Click(1, 1)`.
 * - Two-finger move → `Scroll` of the centroid, unless the distance between the two fingers changes by
 *   [Config.zoomStartPx] and more than the centroid travelled: then it is a pinch and emits one `Zoom`
 *   per [Config.zoomStepPx] of distance change. Once any finger of a scroll/zoom lifts, the sequence is
 *   finished: nothing else happens (no click, no move) until every finger is up.
 * - Firm press: one finger, not moved, touch pressure ≥ [firmPressAt] → `FirmPress` once, and the
 *   touch then does nothing else (no tap, no drag). Off while [firmPressAt] is infinite.
 * - Three fingers do nothing (Android skins take them for screenshots / split screen).
 * - Four or five fingers: `Swipe` or `Pinch` exactly once per touch, see [MultiFingerGesture].
 * - Taps beginning within [Config.multiCooldownMs] of the end of a multi-finger sequence are ignored.
 * - CANCEL, the watchdog (no event for a pointer that is down) and [reset] emit `DragEnd` if a drag
 *   was active, then `ResetAll`, and never click. A pointer the machine has forgotten (after a reset)
 *   that keeps moving is adopted as a fresh, tap-less sequence so the pad never feels dead.
 */
class TrackpadGestureMachine(
    private val config: Config,
    private val emit: (GestureIntent) -> Unit,
) {
    data class Config(
        val slopPx: Float = 16f,
        val swipePx: Float = 120f,
        val pinchPx: Float = 80f,
        /** Two-finger pinch: distance change that starts zooming, and the change per zoom step. */
        val zoomStartPx: Float = 40f,
        val zoomStepPx: Float = 56f,
        val tapMaxMs: Long = 180,
        val doubleTapMs: Long = 300,
        val doubleTapSlopPx: Float = 100f,
        val longPressMs: Long = 500,
        val twoFingerLiftSpreadMs: Long = 250,
        val twoFingerTapMaxMs: Long = 400,
        val multiCooldownMs: Long = 150,
        /** Reset if a pointer is down and nothing arrives for this long. */
        val watchdogMs: Long = 1500,
        /** Same while a drag holds the button: users legitimately hold still mid-drag. */
        val dragWatchdogMs: Long = 6000,
        val naturalScroll: Boolean = true,
    )

    enum class State { Idle, Pending, Moving, Dragging, MultiPending, Scrolling, Zooming, Multi, Finished }

    private class Ptr(override var x: Float, override var y: Float) : FingerPos

    var naturalScroll: Boolean = config.naturalScroll

    /** Touch pressure at which a still finger counts as a firm press; infinite = off. */
    var firmPressAt: Float = Float.POSITIVE_INFINITY

    /** Highest pressure seen on the primary finger of the current / last one-finger touch. */
    var peakPressure: Float = 0f; private set

    /** The last touch ended in a firm press (the calibrator must not learn from it). */
    var lastWasFirm: Boolean = false; private set

    /** Most fingers down at once in the last finished touch. */
    var lastTouchFingers: Int = 0; private set

    private val multi = MultiFingerGesture(config.swipePx, config.pinchPx)
    /** Diagnostics for the last touch (see [GestureTrace]). */
    val multiDiag: MultiFingerGesture get() = multi

    var state: State = State.Idle; private set
    private val ptrs = LinkedHashMap<Long, Ptr>()
    private var primary: Long = -1
    private var maxFingers = 0
    private var seqStart = 0L
    private var lastEvent = 0L
    private var startX = 0f
    private var startY = 0f
    private var travelX = 0f // primary finger travel while Pending
    private var travelY = 0f
    private var multiX = 0f // accumulated centroid travel (two/three fingers)
    private var multiY = 0f
    private var zoomBase = 0f     // distance between the two fingers at the last zoom step (or when they landed)
    private var tapAllowed = true
    private var firstLiftAt = -1L
    private var lastLiftAt = -1L
    private var lastTapUp = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapCount = 0
    private var lastMultiEnd = Long.MIN_VALUE / 2

    val fingersDown: Int get() = ptrs.size
    val isIdle: Boolean get() = state == State.Idle && ptrs.isEmpty()

    /**
     * Earliest time at which [tick] could change something (long-press or watchdog), or null when
     * idle. The adapter uses it to schedule its next wake-up.
     */
    fun nextDeadline(): Long? {
        if (ptrs.isEmpty()) return null
        val wd = lastEvent + if (state == State.Dragging) config.dragWatchdogMs else config.watchdogMs
        return if (state == State.Pending) min(seqStart + config.longPressMs, wd) else wd
    }

    /** [pressure]: the touch's reported pressure (NaN when unknown). */
    fun onTouch(id: Long, action: TouchAction, x: Float, y: Float, t: Long, pressure: Float = Float.NaN) {
        when (action) {
            TouchAction.DOWN -> down(id, x, y, t)
            TouchAction.MOVE -> move(id, x, y, t)
            TouchAction.UP -> up(id, x, y, t)
            TouchAction.CANCEL -> cancel(t)
        }
        if (action != TouchAction.CANCEL && action != TouchAction.UP) pressed(id, pressure, t)
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
        if (state == State.Pending && pressure >= firmPressAt && hypot(travelX, travelY) < config.slopPx) {
            state = State.Finished
            tapCount = 0
            lastWasFirm = true
            emit(GestureIntent.FirmPress)
        }
    }

    /** Time-based transitions. Call whenever [nextDeadline] passes (calling more often is harmless). */
    fun tick(t: Long) {
        if (ptrs.isEmpty()) return
        val limit = if (state == State.Dragging) config.dragWatchdogMs else config.watchdogMs
        if (t - lastEvent >= limit) { cancel(t); return }
        if (state == State.Pending && t - seqStart >= config.longPressMs &&
            hypot(travelX, travelY) < config.slopPx
        ) {
            state = State.Dragging
            tapCount = 0
            emit(GestureIntent.DragStart)
        }
    }

    /** External reset (activity paused, target peer changed, mode switch). */
    fun reset(t: Long) = cancel(t)

    // ------------------------------------------------------------------------------------------

    private fun down(id: Long, x: Float, y: Float, t: Long) {
        lastEvent = t
        if (ptrs.containsKey(id)) { ptrs[id]!!.x = x; ptrs[id]!!.y = y; return } // duplicate DOWN
        if (ptrs.isEmpty()) {
            startSequence(id, x, y, t, adopted = false)
            return
        }
        ptrs[id] = Ptr(x, y)
        maxFingers = maxOf(maxFingers, ptrs.size)
        when (state) {
            State.Pending, State.Moving -> {
                state = State.MultiPending
                multiX = 0f; multiY = 0f
                firstLiftAt = -1L; lastLiftAt = -1L
                zoomBase = pairDistance()
            }
            // A third finger ends scroll / zoom; from then on only 4- and 5-finger gestures count.
            State.MultiPending, State.Scrolling, State.Zooming -> if (ptrs.size >= 3) {
                state = State.Multi
                multi.fingersChanged(ptrs.values)
            }
            State.Multi -> multi.fingersChanged(ptrs.values)
            State.Finished -> if (ptrs.size >= 3 && !multi.fired && !lastWasFirm) {
                state = State.Multi
                multi.fingersChanged(ptrs.values)
            }
            // Dragging keeps following the primary finger.
            else -> Unit
        }
    }

    private fun startSequence(id: Long, x: Float, y: Float, t: Long, adopted: Boolean) {
        ptrs.clear()
        ptrs[id] = Ptr(x, y)
        primary = id
        maxFingers = 1
        seqStart = t
        lastEvent = t
        startX = x; startY = y
        travelX = 0f; travelY = 0f
        multiX = 0f; multiY = 0f
        firstLiftAt = -1L; lastLiftAt = -1L
        tapAllowed = !adopted && t - lastMultiEnd >= config.multiCooldownMs
        multi.reset()
        peakPressure = 0f
        downPressure = Float.NaN
        lastWasFirm = false
        state = if (adopted) State.Moving else State.Pending
    }

    private fun move(id: Long, x: Float, y: Float, t: Long) {
        val p = ptrs[id]
        if (p == null) {
            // A finger we dropped (watchdog/reset) is still moving: adopt it, without tap rights.
            if (ptrs.isEmpty()) startSequence(id, x, y, t, adopted = true)
            return
        }
        lastEvent = t
        val dx = x - p.x
        val dy = y - p.y
        p.x = x; p.y = y
        if (dx == 0f && dy == 0f) return
        val n = ptrs.size.toFloat()
        when (state) {
            State.Pending -> if (id == primary) {
                travelX += dx; travelY += dy
                if (hypot(travelX, travelY) >= config.slopPx) {
                    state = State.Moving
                    tapCount = 0
                    emit(GestureIntent.Move(travelX, travelY))
                }
            }
            State.Moving, State.Dragging -> if (id == primary) emit(GestureIntent.Move(dx, dy))
            State.MultiPending -> {
                multiX += dx / n; multiY += dy / n
                val travel = hypot(multiX, multiY)
                val pinchDelta = if (ptrs.size == 2) pairDistance() - zoomBase else 0f
                if (ptrs.size == 2 && abs(pinchDelta) >= config.zoomStartPx && abs(pinchDelta) > travel) {
                    state = State.Zooming
                    zoomSteps()
                } else if (travel >= config.slopPx) {
                    if (ptrs.size >= 2) {
                        state = State.Scrolling
                        scroll(multiX, multiY)
                    } else {
                        state = State.Finished // one finger of a two-finger touch wandering off
                    }
                }
            }
            State.Scrolling -> scroll(dx / n, dy / n)
            State.Zooming -> if (ptrs.size == 2) zoomSteps()
            State.Multi -> when (val r = multi.onMove(dx, dy, ptrs.values)) {
                is MultiFingerResult.Swipe -> { state = State.Finished; emit(GestureIntent.Swipe(r.dir, r.fingers.coerceAtMost(5))) }
                is MultiFingerResult.Pinch -> { state = State.Finished; emit(GestureIntent.Pinch(r.spread)) }
                null -> Unit
            }
            State.Finished, State.Idle -> Unit
        }
    }

    private fun up(id: Long, x: Float, y: Float, t: Long) {
        val p = ptrs.remove(id) ?: return // unknown pointer (already reset): ignore
        lastEvent = t
        p.x = x; p.y = y
        val remaining = ptrs.size

        when (state) {
            State.Pending -> if (remaining == 0) {
                if (tapAllowed && t - seqStart <= config.tapMaxMs && hypot(travelX, travelY) < config.slopPx) {
                    tapCount = if (seqStart - lastTapUp <= config.doubleTapMs &&
                        hypot(startX - lastTapX, startY - lastTapY) < config.doubleTapSlopPx
                    ) min(tapCount + 1, 3) else 1
                    lastTapUp = t
                    lastTapX = startX; lastTapY = startY
                    emit(GestureIntent.Click(0, tapCount))
                } else tapCount = 0
            }
            State.Moving -> if (id == primary) tapCount = 0
            State.Dragging -> if (id == primary) {
                emit(GestureIntent.DragEnd)
                state = State.Finished
            }
            State.MultiPending -> {
                if (firstLiftAt < 0) firstLiftAt = t
                lastLiftAt = t
                if (remaining == 0 && maxFingers == 2 && tapAllowed &&
                    lastLiftAt - firstLiftAt <= config.twoFingerLiftSpreadMs &&
                    t - seqStart <= config.twoFingerTapMaxMs &&
                    hypot(multiX, multiY) < config.slopPx
                ) {
                    emit(GestureIntent.Click(1, 1))
                }
            }
            State.Scrolling, State.Zooming -> state = State.Finished // lifting one finger never clicks or moves
            State.Multi -> multi.fingersChanged(ptrs.values)
            State.Finished, State.Idle -> Unit
        }

        if (remaining == 0) endSequence(t)
    }

    private fun endSequence(t: Long) {
        lastTouchFingers = maxFingers
        if (maxFingers >= 2) {
            lastMultiEnd = t
            tapCount = 0
        }
        state = State.Idle
        primary = -1
        maxFingers = 0
    }

    private fun cancel(t: Long) {
        val wasActive = ptrs.isNotEmpty() || state != State.Idle
        if (state == State.Dragging) emit(GestureIntent.DragEnd)
        ptrs.clear()
        state = State.Idle
        primary = -1
        maxFingers = 0
        tapCount = 0
        lastMultiEnd = t // a cancelled sequence is treated like a multi-finger one: no stray tap after it
        if (wasActive) emit(GestureIntent.ResetAll)
    }

    private fun pairDistance(): Float {
        if (ptrs.size != 2) return 0f
        val (a, b) = ptrs.values.toList()
        return hypot(a.x - b.x, a.y - b.y)
    }

    /** Emits one [GestureIntent.Zoom] per [Config.zoomStepPx] of change in the two-finger distance. */
    private fun zoomSteps() {
        val d = pairDistance()
        while (d - zoomBase >= config.zoomStepPx) { emit(GestureIntent.Zoom(inward = false)); zoomBase += config.zoomStepPx }
        while (zoomBase - d >= config.zoomStepPx) { emit(GestureIntent.Zoom(inward = true)); zoomBase -= config.zoomStepPx }
    }

    private fun scroll(fingerDx: Float, fingerDy: Float) {
        // §8.2: positive dy = content moves up. Natural scrolling: content follows the fingers.
        val s = if (naturalScroll) -1f else 1f
        emit(GestureIntent.Scroll(fingerDx * s, fingerDy * s))
    }

    private companion object {
        const val FIRM_MIN_MS = 120L
        const val FIRM_RISE = 1.25f
    }
}
