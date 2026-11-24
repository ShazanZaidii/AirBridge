package com.shazan.airbridge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.shazan.airbridge.app.Notifs
import com.shazan.airbridge.clipboard.ClipboardSync
import com.shazan.airbridge.clipboard.bubble.BubbleController
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.Identity
import com.shazan.airbridge.connection.pairing.PairStore
import com.shazan.airbridge.control.InputRouter
import com.shazan.airbridge.control.NetHooks
import com.shazan.airbridge.control.RemoteControlSetup
import com.shazan.airbridge.transfer.Saver

class AirBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Identity.init(this)
        PairStore.init(this)
        Notifs.init(this)
        RemoteControlSetup.init(this)
        ConnectionHub.init(this)
        // Phone notifications / calls on the computer (§13): off until turned on in Settings.
        com.shazan.airbridge.phone.PhoneRelay.init(this)
        // Input half: routes control messages from PeerBus even before the accessibility service is on.
        InputRouter.install(this)
        // Accessibility services that an update dropped: put back, or one tap to turn on.
        com.shazan.airbridge.control.accessibility.A11yKeeper.onAppStart(this)
        if (BuildConfig.DEBUG) com.shazan.airbridge.debug.DebugSend.register(this)
        // OEM gesture switches the trackpad turned off and could not put back (crash / kill).
        com.shazan.airbridge.control.trackpad.OsGestureGuard.restore(this)
        // Accessibility services off while a UPI app is in front (Paytm refuses to pay otherwise); repairs a pause a kill left.
        com.shazan.airbridge.payment.PaymentPause.ensureRunning(this)
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
