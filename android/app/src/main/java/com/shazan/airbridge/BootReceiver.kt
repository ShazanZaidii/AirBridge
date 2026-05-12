package com.shazan.airbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("AUTO_CONNECT", true)) {
                val urls = prefs.getStringSet("WS_URLS", emptySet()) ?: emptySet()
                if (urls.isNotEmpty()) {
                    val serviceIntent = Intent(context, AirBridgeService::class.java).apply {
                        putStringArrayListExtra("WS_URLS", ArrayList(urls))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}