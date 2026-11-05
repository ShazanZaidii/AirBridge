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
        const val FADE_MS = 120L // §8.6 smooth hand-off
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
    /** Fading out after a hand-back: still follows [moveTo], gone when the fade ends. */
    var fadingOut = false; private set
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

    /** The overlay window exists (addView succeeded). */
    val attached: Boolean get() = container != null && dot != null

    /** The cursor is on screen (or fading in): overlay attached, dot visible, not on its way out. */
    val shown: Boolean get() = visible && !fadingOut && attached && dot?.visibility == View.VISIBLE && container?.isAttachedToWindow != false

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

    /** True display size in the current orientation (the overlay covers all of it, bars and cutout included). */
    fun updateScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = try { wm.maximumWindowMetrics.bounds } catch (_: Throwable) { null }
            if (b != null && b.width() > 0 && b.height() > 0) {
                screenW = b.width()
                screenH = b.height()
                return
            }
        }
        val m = DisplayMetrics()
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(m)
        if (m.widthPixels > 0 && m.heightPixels > 0) {
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    /** Show at (x, y), jumping there; fades in over [FADE_MS] when it wasn't already on screen. */
    fun showAt(x: Float, y: Float, fade: Boolean = false) {
        targetX = x; targetY = y; curX = x; curY = y
        val d = dot
        val wasShown = visible && !fadingOut
        val wasFading = fadingOut
        fadingOut = false
        visible = true
        if (d != null) {
            d.animate().cancel()
            d.visibility = View.VISIBLE
            if (fade && !wasShown && animationsOn()) {
                if (!wasFading) d.alpha = 0f // re-entry mid fade-out: continue from the current alpha
                d.animate().alpha(1f).setDuration(FADE_MS).start()
            } else d.alpha = 1f
        }
        apply()
    }

    /** Fade out over [FADE_MS] while still following [moveTo]; then hidden for good. */
    fun fadeOut() {
        val d = dot
        if (!visible || fadingOut) return
        if (d == null || !animationsOn()) { hide(); return }
        fadingOut = true
        d.animate().cancel()
        d.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (fadingOut) hide() }.start()
        // Backstop: the overlay never stays visible if the animation is skipped or cancelled.
        d.postDelayed({ if (fadingOut) hide() }, FADE_MS + 80)
    }

    private fun animationsOn(): Boolean = try { android.animation.ValueAnimator.areAnimatorsEnabled() } catch (_: Throwable) { true }

    fun moveTo(x: Float, y: Float) {
        targetX = x; targetY = y
        if (visible) schedule()
    }

    fun hide() {
        visible = false
        fadingOut = false
        if (framePending) { choreographer.removeFrameCallback(frame); framePending = false }
        dot?.let { it.animate().cancel(); it.alpha = 1f; it.visibility = View.GONE }
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
