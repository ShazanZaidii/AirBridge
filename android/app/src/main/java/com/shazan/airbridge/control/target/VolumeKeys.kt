package com.shazan.airbridge.control.target

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * A computer's volume keys acting as the phone's own volume buttons: one step up or down on the stream the buttons
 * would change (the call during a call, media otherwise, as on Android 9+), with the system's small volume slider. It
 * works over any app, the share sheet included, and never opens the volume settings sheet.
 *
 * Measured on a vivo V2404 (Android 15): `adjustVolume`, `adjustSuggestedStreamVolume` and `adjustStreamVolume` from
 * an app are logged by the system but change nothing; `setStreamVolume` is applied. So the step is computed here.
 */
object VolumeKeys {
    private const val TAG = "AirBridgeVolume"
    /** The level before a mute, to come back to. */
    private var beforeMute = -1

    fun press(ctx: Context, direction: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = when (am.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        try {
            val max = am.getStreamMaxVolume(stream)
            val min = if (android.os.Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
            val cur = am.getStreamVolume(stream)
            val target = when (direction) {
                AudioManager.ADJUST_RAISE -> (cur + 1).coerceAtMost(max)
                AudioManager.ADJUST_LOWER -> (cur - 1).coerceAtLeast(min)
                AudioManager.ADJUST_TOGGLE_MUTE -> if (cur > min) { beforeMute = cur; min } else beforeMute.takeIf { it > min } ?: (max / 2)
                else -> return
            }
            // Same level (already at the end): still show the slider, so the press visibly did something.
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        } catch (t: Throwable) {
            Log.w(TAG, "volume", t)
        }
    }
}
