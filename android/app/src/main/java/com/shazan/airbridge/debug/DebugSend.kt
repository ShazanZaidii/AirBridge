package com.shazan.airbridge.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.shazan.airbridge.connection.ConnectionHub
import java.io.File

/**
 * Debug builds only (registered when BuildConfig.DEBUG): start a file send from the command line, the same way the share
 * sheet does, to measure transfer speed.
 *   adb shell am broadcast -a com.shazan.airbridge.DEBUG_SEND --es path <file in this app's external files dir> [--es to <peer id>]
 */
object DebugSend {
    private const val TAG = "AirBridge.DebugSend"
    const val ACTION = "com.shazan.airbridge.DEBUG_SEND"

    fun register(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val path = i.getStringExtra("path") ?: return
                val s = i.getStringExtra("to")?.let { ConnectionHub.readySession(it) } ?: ConnectionHub.readySessions().firstOrNull()
                if (s == null) { Log.w(TAG, "no connected device"); return }
                Log.i(TAG, "sending $path (${File(path).length()} bytes)")
                s.xfer.send(listOf(Uri.fromFile(File(path))))
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }
}
