package com.shazan.airbridge.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.PeerInfo
import org.json.JSONObject

/**
 * Tells connected computers how full this phone's battery is, so a Mac can list the phone next to its other
 * devices in the Batteries widget. Sends `dev_battery` (`pct` 0..100, `charging`) only to computers that
 * advertise [CAP_SINK], when the level or the charging state changes, and once when a computer connects.
 * Nothing else about the phone is sent.
 */
object DeviceBattery {
    private const val TAG = "AirBridge.Battery"
    const val CAP_SINK = "battery_sink"
    const val TYPE = "dev_battery"

    private val worker: Handler by lazy { Handler(HandlerThread("device-battery").apply { start() }.looper) }
    private lateinit var app: Context
    private var receiver: BroadcastReceiver? = null
    private var last: Level? = null

    data class Level(val pct: Int, val charging: Boolean)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        worker.post { register() }
    }

    private fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { i?.let { worker.post { onChanged(read(it)) } } }
        }
        try {
            val sticky = ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
            sticky?.let { last = read(it) }
        } catch (t: Throwable) { Log.w(TAG, "receiver", t) }
    }

    /** What the battery broadcast says, or null when it carries no usable level. */
    fun read(i: Intent): Level? {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        return Level((level * 100f / scale).toInt().coerceIn(0, 100), charging)
    }

    private fun onChanged(now: Level?) {
        if (now == null || now == last) return // the broadcast also fires for voltage and temperature
        last = now
        sinks().forEach { send(it.id, now) }
    }

    /** A computer finished connecting: it gets the current level at once. */
    fun onPeerReady(peerId: String) = worker.post {
        if (ConnectionHub.readySession(peerId)?.info()?.caps?.contains(CAP_SINK) != true) return@post
        val now = last ?: app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { read(it) } ?: return@post
        last = now
        send(peerId, now)
    }

    private fun sinks(): List<PeerInfo> = ConnectionHub.peers().filter { CAP_SINK in it.caps }

    private fun send(peerId: String, l: Level) {
        try { ConnectionHub.send(peerId, message(l)) } catch (t: Throwable) { Log.w(TAG, "send", t) }
    }

    fun message(l: Level): JSONObject = JSONObject().put("t", TYPE).put("pct", l.pct).put("charging", l.charging)
}
