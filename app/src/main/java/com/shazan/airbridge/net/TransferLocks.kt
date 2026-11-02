package com.shazan.airbridge.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the Wi-Fi radio out of power-save and the CPU awake while any file transfer is running.
 * Reference-counted across transfers; released when the last one ends. Both locks are best-effort.
 */
object TransferLocks {
    private const val TAG = "AirBridge.Locks"
    private const val MAX_HOLD_MS = 2 * 60 * 60 * 1000L // safety net if a release is ever missed

    private var holders = 0
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        if (holders++ > 0) return
        val app = ctx.applicationContext
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(wifiMode(), "AirBridge:xfer").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "wifi lock: ${e.message}") }
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirBridge:xfer").apply {
                setReferenceCounted(false); acquire(MAX_HOLD_MS)
            }
        } catch (e: Exception) { Log.w(TAG, "wake lock: ${e.message}") }
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        if (--holders > 0) return
        try { wifi?.takeIf { it.isHeld }?.release() } catch (e: Exception) { Log.w(TAG, "wifi release: ${e.message}") }
        try { cpu?.takeIf { it.isHeld }?.release() } catch (e: Exception) { Log.w(TAG, "cpu release: ${e.message}") }
        wifi = null
        cpu = null
    }

    // LOW_LATENCY (API 29+) replaces the deprecated HIGH_PERF, which API 34 silently maps to it anyway.
    @Suppress("DEPRECATION")
    private fun wifiMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
}
