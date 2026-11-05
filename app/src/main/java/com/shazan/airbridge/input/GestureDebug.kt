package com.shazan.airbridge.input

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live gesture diagnostics: for every touch, how many fingers were counted, what the recogniser made
 * of it (or why nothing fired) and what was actually sent. Always written to logcat under the tag
 * [TAG] (adb logcat -s AirBridgeGesture); shown on the trackpad only when turned on (long-press the
 * Trackpad tab).
 */
object GestureDebug {
    const val TAG = "AirBridgeGesture"
    /** On-screen overlay (off by default; it sits over the pad). Logcat logging is always on. */
    const val PREF_OVERLAY = "TP_GESTURE_OVERLAY"

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val _fingers = MutableStateFlow(0)
    /** Fingers on the pad right now. */
    val fingers: StateFlow<Int> = _fingers
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setEnabled(on: Boolean) { _enabled.value = on }
    internal fun fingersNow(n: Int) { _fingers.value = n }

    fun line(text: String) {
        Log.i(TAG, text)
        val stamped = "${clock.format(Date())}  $text"
        _lines.value = (_lines.value + stamped).takeLast(6)
    }
}

/**
 * Per-touch bookkeeping for [GestureDebug], fed by the trackpad / screen adapters. Pure Kotlin apart
 * from the final [GestureDebug.line] call, so the wording is easy to check.
 */
internal class GestureTrace(private val mode: String, private val pxPerDp: Float) {
    private val down = HashSet<Long>()
    private var maxDown = 0
    private val got = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private var moves = 0
    private var scrollPx = 0f
    private var zooms = 0

    fun onTouch(id: Long, a: TouchAction) {
        when (a) {
            TouchAction.DOWN -> { if (down.isEmpty()) clear(); down += id; maxDown = maxOf(maxDown, down.size) }
            TouchAction.UP -> down -= id
            TouchAction.CANCEL -> down.clear()
            TouchAction.MOVE -> Unit
        }
        GestureDebug.fingersNow(down.size)
    }

    fun recognised(what: String) { got += what }
    fun moved() { moves++ }
    fun scrolled(dx: Float, dy: Float) { scrollPx += kotlin.math.hypot(dx, dy) }
    fun zoomed() { zooms++ }
    fun sentAction(name: String, connected: Boolean) {
        val what = if (connected) name else "$name (NOT SENT: no computer connected)"
        if (down.isEmpty()) GestureDebug.line("$mode: sent $what") else sent += what
    }

    /** The touch ended normally. [multiTravel] / [multiPinch] are what the 4/5-finger detector measured. */
    fun ended(multiTravel: Float, multiPinch: Float, swipePx: Float, pinchPx: Float, firm: Boolean) {
        val n = maxDown
        val result = when {
            got.isNotEmpty() -> got.joinToString(", ")
            firm -> "firm press"
            n >= 3 -> "nothing: moved ${dp(multiTravel)} dp (needs ${dp(swipePx)}), pinch ${dp(multiPinch)} dp (needs ${dp(pinchPx)})"
            n == 2 && scrollPx > 0f -> "scroll ${dp(scrollPx)} dp"
            n == 2 && zooms > 0 -> "zoom ×$zooms"
            n == 1 && moves > 0 -> "pointer move"
            else -> "no gesture"
        }
        val out = if (sent.isEmpty()) "" else "  →  sent ${sent.joinToString(", ")}"
        GestureDebug.line("$mode: $n finger${if (n == 1) "" else "s"}  →  $result$out")
        clear()
    }

    /** Android cancelled the touch under us (system gesture, another window took it). */
    fun cancelled() {
        val n = maxDown
        val out = if (sent.isEmpty()) "" else "  →  sent ${sent.joinToString(", ")}"
        if (got.isNotEmpty()) {
            GestureDebug.line("$mode: $n fingers  →  taken by Android, recovered as ${got.joinToString(", ")}$out")
        } else {
            GestureDebug.line("$mode: $n fingers  →  CANCELLED by Android before it moved enough to tell the gesture$out")
        }
        clear()
        GestureDebug.fingersNow(0)
    }

    private fun clear() {
        maxDown = down.size; got.clear(); sent.clear(); moves = 0; scrollPx = 0f; zooms = 0
    }

    private fun dp(px: Float) = Math.round(kotlin.math.abs(px) / pxPerDp)
}
