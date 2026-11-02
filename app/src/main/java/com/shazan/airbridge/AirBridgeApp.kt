package com.shazan.airbridge

import android.app.Application
import android.content.Context
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.Identity
import com.shazan.airbridge.net.Notifs
import com.shazan.airbridge.net.PairStore
import com.shazan.airbridge.net.Saver

class AirBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Identity.init(this)
        PairStore.init(this)
        Notifs.init(this)
        Hub.init(this)
        // v1 pinned ws:// URLs are meaningless in v2 (peers are found by id).
        getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).edit().remove("WS_URLS").apply()
        Thread({ Saver.cleanupStalePending(this) }, "airbridge-cleanup").start()
    }
}
