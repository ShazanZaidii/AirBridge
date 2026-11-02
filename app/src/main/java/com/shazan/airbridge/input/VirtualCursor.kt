package com.shazan.airbridge.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Accessibility overlay that draws the virtual cursor, smoothed with a Choreographer loop.
 * All methods must be called on the main thread. Coordinates are display pixels, the same space
 * gestures are dispatched in; the cursor's hotspot is the centre of the dot.
 */
class VirtualCursor(private val ctx: Context) {

    private companion object {
        const val SMOOTHING = 0.45f // owner-tuned feel
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val sizePx = (13 * density).toInt().coerceAtLeast(24)

    private var container: FrameLayout? = null
    private var dot: DotView? = null
    private var offX = 0 // overlay window origin on screen (normally 0,0)
    private var offY = 0

    var screenW = 1; private set
    var screenH = 1; private set

    private var targetX = 0f
    private var targetY = 0f
    private var curX = 0f
    private var curY = 0f
    var visible = false; private set
    private var framePending = false

    private val choreographer: Choreographer = Choreographer.getInstance()

    private val frame = Choreographer.FrameCallback {
        framePending = false
        if (!visible) return@FrameCallback
        curX += (targetX - curX) * SMOOTHING
        curY += (targetY - curY) * SMOOTHING
        if (abs(targetX - curX) < 0.3f && abs(targetY - curY) < 0.3f) {
            curX = targetX; curY = targetY
            apply()
        } else {
            apply()
            schedule()
        }
    }

    fun attach() {
        if (container != null) return
        updateScreen()
        val c = FrameLayout(ctx)
        val d = DotView(ctx).apply { visibility = View.GONE }
        c.addView(d, FrameLayout.LayoutParams(sizePx, sizePx))
        c.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            offX = loc[0]; offY = loc[1]
            apply()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        try {
            wm.addView(c, lp)
            container = c
            dot = d
        } catch (t: Throwable) {
            android.util.Log.w("AirBridgeCursor", "overlay addView failed", t)
        }
    }

    fun detach() {
        hide()
        container?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        container = null
        dot = null
    }

    fun updateScreen() {
        val m = DisplayMetrics()
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(m)
        if (m.widthPixels > 0 && m.heightPixels > 0) {
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    /** Show at (x, y), jumping there without animation. */
    fun showAt(x: Float, y: Float) {
        targetX = x; targetY = y; curX = x; curY = y
        visible = true
        dot?.visibility = View.VISIBLE
        apply()
    }

    fun moveTo(x: Float, y: Float) {
        targetX = x; targetY = y
        if (visible) schedule()
    }

    fun hide() {
        visible = false
        if (framePending) { choreographer.removeFrameCallback(frame); framePending = false }
        dot?.visibility = View.GONE
    }

    fun setPressed(pressed: Boolean) { dot?.setPressedLook(pressed) }

    // A single pending callback at most: fixes the double render loop after fast re-entry.
    private fun schedule() {
        if (framePending) return
        framePending = true
        choreographer.postFrameCallback(frame)
    }

    private fun apply() {
        val d = dot ?: return
        d.translationX = curX - offX - sizePx / 2f
        d.translationY = curY - offY - sizePx / 2f
    }

    private class DotView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4285F4") }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = 2f * ctx.resources.displayMetrics.density
        }
        private var pressedLook = false

        init { elevation = 20f }

        fun setPressedLook(p: Boolean) {
            if (p == pressedLook) return
            pressedLook = p
            scaleX = if (p) 0.75f else 1f
            scaleY = scaleX
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            fill.color = if (pressedLook) Color.parseColor("#1A5FD0") else Color.parseColor("#4285F4")
            canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
            canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
        }
    }
}
