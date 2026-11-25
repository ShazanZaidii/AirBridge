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
 * and a raw TCP upload from memory over [conns] parallel sockets (to tools on the computer that count the bytes):
 *   adb shell am broadcast -a com.shazan.airbridge.DEBUG_TCP --es host <ip> --ei port <first port> --ei conns 2 --ei mb 400
 */
object DebugSend {
    private const val TAG = "AirBridge.DebugSend"
    const val ACTION = "com.shazan.airbridge.DEBUG_SEND"
    const val ACTION_TCP = "com.shazan.airbridge.DEBUG_TCP"
    const val ACTION_VOLUME = "com.shazan.airbridge.DEBUG_VOLUME"

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
        val tcp = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val host = i.getStringExtra("host") ?: return
                val port = i.getIntExtra("port", 5001)
                val conns = i.getIntExtra("conns", 1).coerceIn(1, 8)
                val mb = i.getIntExtra("mb", 400)
                Thread({ tcpUpload(host, port, conns, mb) }, "airbridge-debug-tcp").start()
            }
        }
        ContextCompat.registerReceiver(ctx, tcp, IntentFilter(ACTION_TCP), ContextCompat.RECEIVER_EXPORTED)
        // A computer's volume key, from adb (checks it over other apps and the share sheet):
        //   adb shell am broadcast -a com.shazan.airbridge.DEBUG_VOLUME --es dir up|down|mute
        val vol = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dir = when (i.getStringExtra("dir")) {
                    "down" -> android.media.AudioManager.ADJUST_LOWER
                    "mute" -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                    else -> android.media.AudioManager.ADJUST_RAISE
                }
                com.shazan.airbridge.control.target.VolumeKeys.press(c, dir)
            }
        }
        ContextCompat.registerReceiver(ctx, vol, IntentFilter(ACTION_VOLUME), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun tcpUpload(host: String, port: Int, conns: Int, mb: Int) {
        val buf = ByteArray(1 shl 20)
        val per = mb / conns
        val t0 = System.nanoTime()
        val threads = (0 until conns).map { k ->
            Thread {
                try {
                    java.net.Socket().use { s ->
                        s.tcpNoDelay = true
                        s.connect(java.net.InetSocketAddress(host, port + k), 3000)
                        val out = s.getOutputStream()
                        repeat(per) { out.write(buf) }
                        out.flush()
                    }
                } catch (e: Exception) { Log.w(TAG, "tcp $k: ${e.message}") }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val secs = (System.nanoTime() - t0) / 1e9
        Log.i(TAG, String.format(java.util.Locale.US, "tcp upload %d conn: %d MiB in %.2f s = %.1f MiB/s", conns, per * conns, secs, per * conns / secs))
    }
}
