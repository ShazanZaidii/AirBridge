package com.shazan.airbridge.phone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.shazan.airbridge.AirBridgeTextService

/**
 * "Is the user on the phone?" for the computers' ring decision (§13.4). Screen on/off and unlocks come from
 * broadcasts; taps and window changes from the optional "Typing & Clipboard" accessibility service when it is
 * on (nothing is read: only that an event happened). Sends `call_presence` to computers with `call_ring`
 * while calls are on. Runs on [PhoneRelay.worker].
 */
object PhonePresence {
    private const val TAG = "AirBridge.Presence"
    private val tracker = PresenceTracker { SystemClock.elapsedRealtime() }
    private var receiver: BroadcastReceiver? = null
    private lateinit var app: Context
    private val worker get() = PhoneRelay.worker

    /** Packages whose windows appear by themselves when a call comes in (not the user's doing). */
    private val SELF_SHOWING = setOf("com.android.systemui", "com.android.server.telecom", "com.google.android.dialer",
        "com.android.dialer", "com.android.incallui", "com.samsung.android.incallui", "com.vivo.incallui")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        worker.post {
            tracker.onScreen(try { app.getSystemService(PowerManager::class.java)?.isInteractive == true } catch (_: Throwable) { true })
            register()
        }
    }

    private fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val action = i?.action ?: return
                worker.post {
                    when (action) {
                        Intent.ACTION_SCREEN_ON -> tracker.onScreen(true)
                        Intent.ACTION_SCREEN_OFF -> tracker.onScreen(false)
                        Intent.ACTION_USER_PRESENT -> tracker.onUnlock()
                    }
                    publish()
                }
            }
        }
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (t: Throwable) { Log.w(TAG, "receiver", t) }
    }

    /** From the accessibility service (main thread): the user tapped something or switched windows. */
    fun onAccessibilityEvent(type: Int, pkg: CharSequence?) {
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = pkg?.toString()
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && (p == null || p in SELF_SHOWING || p == app.packageName)) return
        worker.post {
            if (!tracker.screenOn) return@post
            tracker.onTouch()
            publish()
        }
    }

    /** A call started ringing: remember the screen state, and send a fresh snapshot before the call itself. */
    fun onRingStart() { tracker.onRingStart(); publish(force = true) }
    /** Telephony says it rings: note the screen state now, before the call turns the screen on. */
    fun markRing() = tracker.onRingStart()
    fun onRingEnd() = tracker.onRingEnd()

    private fun locked(): Boolean = try { app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Throwable) { false }

    fun snapshot(): NotifWire.Presence = tracker.snapshot(locked(), AirBridgeTextService.isRunning)

    /** Sends the current state to the computers that decide where calls ring, when it is worth it. */
    fun publish(force: Boolean = false) {
        if (!PhoneRelaySettings.callsEnabled) return
        val peers = PhoneRelay.ringPeers()
        if (peers.isEmpty()) return
        val p = snapshot()
        if (!tracker.shouldSend(p, force)) return
        tracker.markSent(p)
        val json = JsonMaps.toJson(NotifWire.presence(p))
        peers.forEach { PhoneRelay.sendRaw(it.id, json) }
    }

    /** One computer just connected: it gets the current state at once. */
    fun sendTo(peerId: String) {
        if (!PhoneRelaySettings.callsEnabled) return
        PhoneRelay.sendRaw(peerId, JsonMaps.toJson(NotifWire.presence(snapshot())))
    }
}
