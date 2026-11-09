package com.shazan.airbridge.phone

import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Keeps the phone quiet while a computer rings for the call (§13.4): no sound and no vibration. The ring volume
 * goes to 0 for this call only (the sound), and with notification access a listener hint stops the vibration (as
 * watches do). Both are undone when the call stops ringing. The saved volume is kept in preferences so a restart
 * of the app still restores it. The volume is left alone when the phone is already on vibrate or silent, and a
 * change the user made meanwhile is never overridden.
 */
object RingSilencer {
    private const val TAG = "AirBridge.Ring"
    private const val PREFS = "AirBridgePrefs"
    private const val K_VOL = "CE_RING_SAVED_VOLUME"
    private const val K_MODE = "CE_RING_MODE_AFTER"

    fun silence(ctx: Context): Boolean {
        // Two halves, as a volume-key press does: the notification listener's hint stops the vibration (Android
        // keeps playing the system call screen's ringtone despite it), the ring volume at 0 stops the sound.
        val noVibration = callEffects(off = true)
        return ringVolumeOff(ctx) || noVibration
    }

    private fun ringVolumeOff(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(K_VOL)) return true // already done for this call
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true // already quiet
        val vol = am.getStreamVolume(AudioManager.STREAM_RING)
        if (vol == 0) return true
        return try {
            am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            p.edit().putInt(K_VOL, vol).putInt(K_MODE, am.ringerMode).apply()
            Log.i(TAG, "phone ring silenced for this call")
            true
        } catch (t: SecurityException) {
            // No vibrator, so 0 would mean "silent", which needs Do Not Disturb access: leave the ringer alone.
            Log.i(TAG, "can't silence the ringer: ${t.message}")
            false
        } catch (t: Throwable) { Log.w(TAG, "silence", t); false }
    }

    /** The call stopped ringing (answered, declined, ended) or the app started: put the ring volume back. */
    fun restore(ctx: Context) {
        callEffects(off = false)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(K_VOL)) return
        val vol = p.getInt(K_VOL, 0)
        val modeAfter = p.getInt(K_MODE, -1)
        p.edit().remove(K_VOL).remove(K_MODE).apply()
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            // Only if it is still the way we left it (the user didn't pick another volume or mode meanwhile).
            if (am.getStreamVolume(AudioManager.STREAM_RING) == 0 && am.ringerMode == modeAfter) {
                am.setStreamVolume(AudioManager.STREAM_RING, vol, 0)
                if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (t: Throwable) { Log.w(TAG, "restore", t) }
    }

    @Volatile private var effectsOff = false

    /** Turns call ring + vibration off (or back on) through the listener hint. False when not possible. */
    private fun callEffects(off: Boolean): Boolean {
        if (!off && !effectsOff) return true
        val l = PhoneNotifListener.instance ?: return false
        return try {
            val hints = l.currentListenerHints
            val want = if (off) hints or NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS
                else hints and NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS.inv()
            l.requestListenerHints(want)
            effectsOff = off
            Log.i(TAG, if (off) "phone ring and vibration off for this call" else "phone ring back on")
            true
        } catch (t: Throwable) { Log.w(TAG, "listener hints", t); false }
    }
}
