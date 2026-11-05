package com.shazan.airbridge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.shazan.airbridge.input.InputRouter
import com.shazan.airbridge.input.NetHooks
import com.shazan.airbridge.net.ClipboardSync
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
        RemoteControlSetup.init(this)
        Hub.init(this)
        // Input half: routes control messages from PeerBus even before the accessibility service is on.
        InputRouter.install(this)
        // OEM gesture switches the trackpad turned off and could not put back (crash / kill).
        com.shazan.airbridge.input.OsGestureGuard.restore(this)
        NetHooks.clipboardPush = { reason -> ClipboardSync.pushNow(reason) }
        // v1 pinned ws:// URLs are meaningless in v2 (peers are found by id).
        getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).edit().remove("WS_URLS").apply()
        Thread({ Saver.cleanupStalePending(this) }, "airbridge-cleanup").start()
        registerActivityLifecycleCallbacks(BubbleSuppressor)
    }

    /**
     * The bubble must never cover the trackpad or mirror screens (§5). The input half also reports
     * these via [BubbleController.setSuppressed]; this is a lifecycle-based backstop with its own
     * reason key so the two never undo each other.
     */
    private object BubbleSuppressor : ActivityLifecycleCallbacks {
        private fun reason(a: Activity): String? {
            val n = a.javaClass.simpleName
            return if (n == "TrackpadActivity" || n.contains("Mirror")) "ui:$n" else null
        }
        override fun onActivityStarted(a: Activity) { reason(a)?.let { BubbleController.setSuppressed(it, true) } }
        override fun onActivityStopped(a: Activity) { reason(a)?.let { BubbleController.setSuppressed(it, false) } }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
