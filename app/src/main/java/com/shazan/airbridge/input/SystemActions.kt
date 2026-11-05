package com.shazan.airbridge.input

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

/** `action` messages on the target (§8.5). Main thread only; unknown names are ignored. */
class SystemActions(private val host: TargetController) {

    private val svc: AccessibilityService get() = host.service
    private var lastVolumePanel = 0L
    private var lastVolumeStep = 0L
    private var lastMedia = 0L
    private val toastedOnce = HashSet<String>()

    fun perform(name: String) {
        try {
            when (name) {
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                // A Mac's own keys / gestures aimed at a phone: their phone meaning.
                "mission_control", "app_expose" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "spotlight" -> host.appDrawerSwipe()
                "show_desktop" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                "desktop_left", "desktop_right" -> host.keys.lastApp()
                "last_app" -> host.keys.lastApp()
                "app_drawer" -> host.appDrawerSwipe()
                "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                "vol_up" -> volume(AudioManager.ADJUST_RAISE)
                "vol_down" -> volume(AudioManager.ADJUST_LOWER)
                "vol_mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
                "brightness_up" -> brightness(+1)
                "brightness_down" -> brightness(-1)
                "media_play_pause" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "media_next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
                "media_prev" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "assistant" -> assistant()
                "dnd_toggle" -> dnd()
                "swipe_up" -> host.swipe("up")
                "swipe_down" -> host.swipe("down")
                "swipe_left" -> host.swipe("left")
                "swipe_right" -> host.swipe("right")
                "copy" -> host.keys.copy()
                "paste" -> host.keys.paste()
            }
        } catch (t: Throwable) {
            Log.w("AirBridgeActions", "action $name failed", t)
        }
    }

    private fun global(a: Int) { svc.performGlobalAction(a) }

    private fun volume(direction: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastVolumeStep < 40) return // debounce double-fired keys
        lastVolumeStep = now
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val apply = {
            if (direction == AudioManager.ADJUST_TOGGLE_MUTE) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            } else {
                // Absolute set bypasses OEM step logic.
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val v = if (direction == AudioManager.ADJUST_RAISE) minOf(max, cur + 1) else maxOf(0, cur - 1)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && now - lastVolumePanel > 2000) {
            // Open the volume panel first so the change is visible and not blocked in background.
            try {
                svc.startActivity(Intent(Settings.Panel.ACTION_VOLUME).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ))
                lastVolumePanel = now
                host.main.postDelayed({ try { apply() } catch (_: Throwable) {} }, 200)
                return
            } catch (_: Throwable) { /* fall through */ }
        }
        lastVolumePanel = now
        apply()
    }

    /** Only adjusts when manual brightness is already active; never flips auto-brightness off. */
    private fun brightness(dir: Int) {
        if (!Settings.System.canWrite(svc)) {
            toastOnce("perm", "Grant 'Modify system settings' to AirBridge to change brightness")
            return
        }
        val cr = svc.contentResolver
        val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            toastOnce("auto", "Auto-brightness is on — AirBridge only adjusts manual brightness")
            return
        }
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (cur + dir * 16).coerceIn(1, 255))
    }

    private fun media(code: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMedia < 300) return // a double-fire would read as "skip"
        lastMedia = now
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun assistant() {
        try {
            svc.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Throwable) {
            Toast.makeText(svc, "No assistant app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dnd() {
        val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(svc, "Grant 'Do Not Disturb access' to AirBridge in Android Settings", Toast.LENGTH_LONG).show()
            return
        }
        val on = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        Toast.makeText(svc, if (on) "DND: ON" else "DND: OFF", Toast.LENGTH_SHORT).show()
    }

    private fun toastOnce(key: String, text: String) {
        if (toastedOnce.add(key)) Toast.makeText(svc, text, Toast.LENGTH_LONG).show()
    }
}
