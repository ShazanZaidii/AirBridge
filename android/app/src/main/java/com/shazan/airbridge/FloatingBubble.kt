package com.shazan.airbridge

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.ui.theme.Palette
import kotlin.math.abs

/**
 * The floating clipboard bubble: tap = push clipboard, long-press = menu (trackpad, unlock to move).
 * Dropping it on the close target hides it (re-enable in Settings). A small amber dot means the latest
 * copy could not be sent automatically ([ClipboardSync.pending]); tapping sends it.
 */
class FloatingBubble(private val ctx: Context) {
    companion object {
        const val PREF_ENABLED = "BUBBLE_ENABLED"
        /** Bubble diameter in dp: an icon, not text, so it stays small. */
        private const val SIZE_DP = 36f
        private const val IDLE_ALPHA = 0.3f
        /** More visible while a copy is waiting to be sent (the dot must be noticeable). */
        private const val PENDING_ALPHA = 0.85f
        private const val DOT_DP = 10f
    }

    /**
     * dp → px at the phone's built-in density, ignoring the user's "Display size" zoom (and font size, which
     * never applies to an icon). Raw pixels made the bubble huge on lower-density phones, and the zoom made it
     * huge again on phones set to a larger display size; this keeps it the same physical size everywhere.
     */
    private val stableDensity = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
    private fun px(dp: Float): Int = (dp * stableDensity + 0.5f).toInt()
    private val SIZE = px(SIZE_DP)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var bubble: FrameLayout? = null
    private var menu: View? = null
    private var closeZone: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var locked = true
    private var hoveringClose = false
    private var pending = false
    private var dot: View? = null

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val autoLock = Runnable {
        locked = true
        toast(ctx.getString(R.string.bubble_locked))
        tint(Palette.argb(Palette.ON_SURFACE_MUTED)); bubble?.alpha = idleAlpha()
    }

    private fun idleAlpha() = if (pending) PENDING_ALPHA else IDLE_ALPHA

    val isShown: Boolean get() = bubble != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubble != null) return
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_stat_name)
            imageTintList = ColorStateList.valueOf(Palette.argb(Palette.ON_SURFACE_MUTED))
            px(9f).let { setPadding(it, it, it, it) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((Palette.argb(Palette.SURFACE) and 0x00FFFFFF) or 0xCC000000.toInt()) }
        }
        // "Your latest copy hasn't been sent — tap to send" badge (ClipboardSync.pending).
        val badge = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Palette.argb(Palette.WARNING)); setStroke(px(1.5f), Palette.argb(Palette.SURFACE))
            }
            visibility = View.GONE
        }
        val container = FrameLayout(ctx).apply {
            alpha = idleAlpha()
            addView(icon, FrameLayout.LayoutParams(SIZE, SIZE))
            addView(badge, FrameLayout.LayoutParams(px(DOT_DP), px(DOT_DP), Gravity.TOP or Gravity.END).apply { setMargins(0, px(3f), px(3f), 0) })
        }
        dot = badge
        val lp = WindowManager.LayoutParams(
            SIZE, SIZE, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = px(110f) }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var isClick = false
        val longPress = Runnable { isClick = false; showMenu() }

        container.setOnTouchListener { v, e ->
            val screenW = ctx.resources.displayMetrics.widthPixels
            val screenH = ctx.resources.displayMetrics.heightPixels
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY
                    isClick = true
                    v.alpha = if (locked) 1f else 0.8f
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()
                    main.postDelayed(longPress, 400)
                    if (!locked) showCloseZone()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > px(4f) || abs(dy) > px(4f)) { isClick = false; main.removeCallbacks(longPress) }
                    if (!locked) {
                        lp.x = startX + dx; lp.y = startY + dy
                        safeUpdate(container, lp)
                        hoveringClose = e.rawY > screenH - px(140f) && abs(e.rawX - screenW / 2) < px(80f)
                        closeZone?.alpha = if (hoveringClose) 1f else 0.5f
                        (closeZone?.background as? GradientDrawable)?.setColor(if (hoveringClose) Palette.argb(Palette.DANGER_FILL) else Palette.argb(Palette.SCRIM))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    hideCloseZone()
                    when {
                        hoveringClose && !locked -> {
                            hoveringClose = false
                            ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply()
                            hide()
                            toast(ctx.getString(R.string.bubble_hidden))
                        }
                        isClick && e.action == MotionEvent.ACTION_UP -> {
                            hideMenu()
                            v.alpha = idleAlpha()
                            ClipboardSync.pushNow("bubble")
                        }
                        !locked -> {
                            val left = lp.x + SIZE / 2 < screenW / 2
                            ValueAnimator.ofInt(lp.x, if (left) -(SIZE / 2) else screenW - SIZE / 2).apply {
                                duration = 250
                                interpolator = DecelerateInterpolator()
                                addUpdateListener { lp.x = it.animatedValue as Int; safeUpdate(container, lp) }
                                start()
                            }
                        }
                        else -> v.alpha = idleAlpha()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(container, lp)
            bubble = container
            params = lp
            renderPending(animate = false)
        } catch (_: Exception) {
            // Overlay permission revoked meanwhile.
        }
    }

    /** Show or clear the "not sent yet" dot. Main thread. */
    fun setPending(on: Boolean) {
        if (pending == on) return
        pending = on
        renderPending(animate = on)
    }

    private fun renderPending(animate: Boolean) {
        val b = bubble ?: return
        val d = dot ?: return
        d.animate().cancel()
        d.visibility = if (pending) View.VISIBLE else View.GONE
        b.contentDescription = ctx.getString(if (pending) R.string.bubble_pending else R.string.bubble_send)
        if (locked) b.alpha = idleAlpha()
        if (pending && animate) pulse(d, 3)
    }

    /** A few gentle pulses, then steady (no endless animation). */
    private fun pulse(v: View, times: Int) {
        if (times == 0 || !pending) { v.scaleX = 1f; v.scaleY = 1f; return }
        v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(260).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).withEndAction { pulse(v, times - 1) }.start()
        }.start()
    }

    fun hide() {
        main.removeCallbacks(autoLock)
        hideMenu()
        hideCloseZone()
        dot?.animate()?.cancel()
        bubble?.let { safeRemove(it) }
        bubble = null
        dot = null
    }

    private fun showMenu() {
        if (menu != null) return
        val lp = params ?: return
        vibrate()
        val dp = { v: Int -> px(v.toFloat()) }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Palette.argb(Palette.SURFACE)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Palette.argb(Palette.OUTLINE_VARIANT))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = 20f
        }
        container.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_remote_control) // same hand icon as the app's Trackpad control
            imageTintList = ColorStateList.valueOf(Palette.argb(Palette.ON_SURFACE))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, 0, dp(8)) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = ctx.getString(R.string.bubble_control)
            setOnClickListener {
                hideMenu()
                ctx.startActivity(Intent(ctx, TrackpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        })
        container.addView(ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            imageTintList = ColorStateList.valueOf(Palette.argb(Palette.WARNING))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = ctx.getString(R.string.bubble_move)
            setOnClickListener {
                hideMenu()
                locked = false
                toast(ctx.getString(R.string.bubble_unlocked))
                bubble?.alpha = 0.8f; tint(Palette.argb(Palette.ACCENT))
                main.removeCallbacks(autoLock)
                main.postDelayed(autoLock, 60_000)
            }
        })
        val left = lp.x + SIZE / 2 < ctx.resources.displayMetrics.widthPixels / 2
        val menuLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // The menu is 48 + 2 × 10 dp wide (see the padding above).
            x = if (left) lp.x + SIZE + dp(6) else lp.x - dp(68) - dp(6)
            y = lp.y - dp(12)
        }
        container.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false }
        try { wm.addView(container, menuLp); menu = container } catch (_: Exception) {}
        main.postDelayed({ hideMenu() }, 4000)
    }

    private fun hideMenu() { menu?.let { safeRemove(it) }; menu = null }

    private fun showCloseZone() {
        if (closeZone != null) return
        val container = FrameLayout(ctx).apply {
            alpha = 0.5f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.argb(Palette.SCRIM)) }
            addView(ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList = ColorStateList.valueOf(Palette.argb(Palette.ON_SURFACE))
                layoutParams = FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER)
            })
        }
        val lp = WindowManager.LayoutParams(px(56f), px(56f), overlayType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = px(48f) }
        try { wm.addView(container, lp); closeZone = container } catch (_: Exception) {}
    }

    private fun hideCloseZone() { closeZone?.let { safeRemove(it) }; closeZone = null }

    private fun tint(color: Int) {
        (bubble?.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(color)
    }

    private fun vibrate() {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) = try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    private fun safeRemove(v: View) = try { wm.removeView(v) } catch (_: Exception) {}
    private fun toast(msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
