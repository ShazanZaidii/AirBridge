package com.shazan.airbridge.net

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Takes window focus for a moment without starting an activity, so the clipboard can be read
 * (Android 10+ only lets the focused app read it). A 1×1 transparent, untouchable overlay window
 * (needs "Display over other apps"; it draws nothing) is added focusable; when it gains focus,
 * [onFocus] runs and the window is removed. Taking focus hides the keyboard of the app in front.
 * Apps that set HIDE_NON_SYSTEM_OVERLAY_WINDOWS (e.g. Google Contacts) hide it, so it never gets
 * focus there; the caller then falls back to the activity. Overlay windows are not subject to background-activity-start rules, nor to OEM
 * "pop-ups in the background" switches (vivo/MIUI), unlike [com.shazan.airbridge.SyncActivity].
 *
 * Main thread only.
 */
internal class ClipFocusWindow(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShown: Boolean get() = view != null

    /** @return false if the window could not be added (no overlay permission, or the system refused). */
    fun show(onFocus: () -> Unit): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(ctx)) return false
        val v = object : View(ctx) {
            private var fired = false
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus || fired) return
                fired = true
                try { onFocus() } finally { post { if (view === this) this@ClipFocusWindow.hide() } }
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) but never touchable; ALT_FOCUSABLE_IM keeps the keyboard out of it.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Not alpha = 0: input treats fully transparent layers as invisible and never delivers focus.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            title = "AirBridge clipboard"
        }
        return try {
            wm.addView(v, lp)
            view = v
            true
        } catch (e: Exception) {
            Log.w("AirBridge.Clip", "focus window refused: ${e.javaClass.simpleName}")
            false
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }
}
