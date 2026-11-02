package com.shazan.airbridge

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import kotlin.math.abs

/**
 * The floating clipboard bubble: tap = push clipboard, long-press = menu (trackpad, unlock to move).
 * Dropping it on the close target hides it (re-enable in Settings).
 */
class FloatingBubble(private val ctx: Context) {
    companion object {
        const val PREF_ENABLED = "BUBBLE_ENABLED"
        private const val SIZE = 130
        private const val IDLE_ALPHA = 0.3f
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var bubble: FrameLayout? = null
    private var menu: View? = null
    private var closeZone: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var locked = true
    private var hoveringClose = false

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val autoLock = Runnable {
        locked = true
        toast("Bubble position locked")
        tint("#B0B0B0"); bubble?.alpha = IDLE_ALPHA
    }

    val isShown: Boolean get() = bubble != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubble != null) return
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_stat_name)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#B0B0B0"))
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#801A1A1A")) }
        }
        val container = FrameLayout(ctx).apply { alpha = IDLE_ALPHA; addView(icon, FrameLayout.LayoutParams(SIZE, SIZE)) }
        val lp = WindowManager.LayoutParams(
            SIZE, SIZE, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

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
                    if (abs(dx) > 10 || abs(dy) > 10) { isClick = false; main.removeCallbacks(longPress) }
                    if (!locked) {
                        lp.x = startX + dx; lp.y = startY + dy
                        safeUpdate(container, lp)
                        hoveringClose = e.rawY > screenH - 400 && abs(e.rawX - screenW / 2) < 250
                        closeZone?.alpha = if (hoveringClose) 1f else 0.5f
                        (closeZone?.background as? GradientDrawable)?.setColor(Color.parseColor(if (hoveringClose) "#E53935" else "#80000000"))
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
                            toast("Bubble hidden. Re-enable it in AirBridge settings.")
                        }
                        isClick && e.action == MotionEvent.ACTION_UP -> {
                            hideMenu()
                            v.alpha = IDLE_ALPHA
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
                        else -> v.alpha = IDLE_ALPHA
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
        } catch (_: Exception) {
            // Overlay permission revoked meanwhile.
        }
    }

    fun hide() {
        main.removeCallbacks(autoLock)
        hideMenu()
        hideCloseZone()
        bubble?.let { safeRemove(it) }
        bubble = null
    }

    private fun showMenu() {
        if (menu != null) return
        val lp = params ?: return
        vibrate()
        val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 60f; setStroke(2, Color.parseColor("#333333"))
            }
            setPadding(dp(12), dp(16), dp(12), dp(16))
            elevation = 20f
        }
        container.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_mac)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(0, 0, 0, dp(16)) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Trackpad"
            setOnClickListener {
                hideMenu()
                ctx.startActivity(Intent(ctx, TrackpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        })
        container.addView(ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#FFD700"))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Move bubble"
            setOnClickListener {
                hideMenu()
                locked = false
                toast("Position unlocked (60s)")
                bubble?.alpha = 0.8f; tint("#4285F4")
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
            x = if (left) lp.x + SIZE + 20 else lp.x - 160
            y = lp.y - 40
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
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#80000000")) }
            addView(ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(80, 80, Gravity.CENTER)
            })
        }
        val lp = WindowManager.LayoutParams(180, 180, overlayType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 150 }
        try { wm.addView(container, lp); closeZone = container } catch (_: Exception) {}
    }

    private fun hideCloseZone() { closeZone?.let { safeRemove(it) }; closeZone = null }

    private fun tint(hex: String) {
        (bubble?.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(Color.parseColor(hex))
    }

    private fun vibrate() {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) = try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    private fun safeRemove(v: View) = try { wm.removeView(v) } catch (_: Exception) {}
    private fun toast(msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
