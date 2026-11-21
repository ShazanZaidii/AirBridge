package com.shazan.airbridge.mirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification actions: "Not now" on a mirror request, "Stop" while the screen is shown. Not exported. */
class MirrorActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DECLINE = "com.shazan.airbridge.mirror.DECLINE"
        const val ACTION_STOP = "com.shazan.airbridge.mirror.STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> PhoneMirror.onConsentDenied()
            ACTION_STOP -> PhoneMirror.stopFromUser()
        }
    }
}
