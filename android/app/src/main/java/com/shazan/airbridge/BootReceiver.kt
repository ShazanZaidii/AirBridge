package com.shazan.airbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shazan.airbridge.net.WsClient

/**
 * Starts the connectedDevice foreground service after boot / app update. connectedDevice (unlike
 * dataSync) may be started from BOOT_COMPLETED on Android 15+.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val autoConnect = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).getBoolean(WsClient.PREF_AUTO_CONNECT, true)
        if (autoConnect && AirBridgeService.shouldRun(context)) AirBridgeService.start(context)
    }
}
