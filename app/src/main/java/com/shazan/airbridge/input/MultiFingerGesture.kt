package com.shazan.airbridge.input

import kotlin.math.abs
import kotlin.math.hypot

/** A finger's current position, in the window's own coordinates (what the user sees). */
interface FingerPos {
    val x: Float
    val y: Float
}

/** Outcome of a 4- or 5-finger touch: fires at most once per touch. */
sealed interface MultiFingerResult {
    /** [dir] = where the fingers moved, as seen on screen (already in the window's orientation). */
    data class Swipe(val dir: SwipeDir, val fingers: Int) : MultiFingerResult
    /** [spread] = fingers moved apart, else together. */
    data class Pinch(val spread: Boolean, val fingers: Int) : MultiFingerResult
}

/**
 * Four- and five-finger swipes and pinches, shared by the trackpad and the screen mode so both
 * behave the same. Three fingers never do anything: Android skins (vivo, Xiaomi, Oppo…) take
 * three-finger touches for screenshots and split screen and cancel them under the app.
 *
 * - Only motion made while at least [minFingers] are down counts. Travel is the mean of the
 *   fingers' own deltas, so a finger landing or lifting never makes the centroid jump.
 * - Pinch: the mean distance from the centroid changed by [pinchPx] and more than the centroid
 *   travelled. Swipe: the centroid travelled [swipePx] and more than the pinch changed; the
 *   direction is the dominant axis.
 * - Each finger landing or lifting re-baselines the spread (the hand shape changed, not a pinch).
 *
 * Coordinates are window coordinates, so directions always match the screen as the user holds it,
 * in portrait or landscape.
 */
class MultiFingerGesture(
    private val swipePx: Float,
    private val pinchPx: Float,
    private val minFingers: Int = 4,
) {
    var fired = false; private set
    private var tx = 0f
    private var ty = 0f
    private var spread0 = -1f
    private var maxFingers = 0

    fun reset() {
        fired = false
        tx = 0f; ty = 0f
        spread0 = -1f
        maxFingers = 0
    }

    /** Call after a finger landed or lifted, with the fingers now down. */
    fun fingersChanged(fingers: Collection<FingerPos>) {
        if (fingers.size > maxFingers) maxFingers = fingers.size
        if (fingers.size >= minFingers) {
            spread0 = spread(fingers)
        } else {
            spread0 = -1f
            tx = 0f; ty = 0f // too few fingers: start counting again when the hand is back
        }
    }

    /**
     * One finger moved by ([dx], [dy]); [fingers] are all fingers now down (positions updated).
     * Returns the gesture once, when it is recognised.
     */
    fun onMove(dx: Float, dy: Float, fingers: Collection<FingerPos>): MultiFingerResult? {
        if (fired || fingers.size < minFingers || spread0 < 0f) return null
        val n = fingers.size
        tx += dx / n; ty += dy / n
        val travel = hypot(tx, ty)
        val pinch = spread(fingers) - spread0
        val result = when {
            abs(pinch) >= pinchPx && abs(pinch) > travel -> MultiFingerResult.Pinch(pinch > 0f, maxFingers)
            travel >= swipePx && travel > abs(pinch) -> MultiFingerResult.Swipe(
                if (abs(tx) > abs(ty)) (if (tx > 0) SwipeDir.RIGHT else SwipeDir.LEFT)
                else (if (ty > 0) SwipeDir.DOWN else SwipeDir.UP),
                maxFingers,
            )
            else -> null
        }
        if (result != null) fired = true
        return result
    }

    companion object {
        /** Mean distance of the fingers from their centroid. */
        fun spread(fingers: Collection<FingerPos>): Float {
            if (fingers.isEmpty()) return 0f
            var cx = 0f; var cy = 0f
            for (p in fingers) { cx += p.x; cy += p.y }
            cx /= fingers.size; cy /= fingers.size
            var d = 0f
            for (p in fingers) d += hypot(p.x - cx, p.y - cy)
            return d / fingers.size
        }
    }
}
