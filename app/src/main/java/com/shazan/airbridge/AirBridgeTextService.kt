package com.shazan.airbridge

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.shazan.airbridge.net.CapsRefresh
import com.shazan.airbridge.net.ClipboardSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional accessibility service "AirBridge Typing & Clipboard": the only part of AirBridge that can
 * read screen content (res/xml/text_service_config.xml). It hosts no logic of its own:
 *  - [com.shazan.airbridge.input.TargetKeys] uses [instance] to find the focused field, type into it
 *    (accessibility input method on API 33+) and run copy/cut/paste/select-all;
 *  - its events feed [ClipboardSync.onAccessibilityEvent] (copy detection for automatic sync).
 * Everything degrades gracefully while it is off; the core [AirBridgeInputService] never needs it.
 * Kept separate so the core service stays a gestures-only profile that banking/UPI apps don't flag.
 */
class AirBridgeTextService : AccessibilityService() {

    companion object {
        private const val TAG = "AirBridgeText"
        @Volatile private var running: AirBridgeTextService? = null
        private val _runningFlow = MutableStateFlow(false)

        /** The connected service, or null while it is off. Main-thread use only. */
        val instance: AirBridgeTextService? get() = running

        /** True while the service is connected. */
        val isRunning: Boolean get() = running != null

        /** [isRunning] as a flow, for the UI. */
        val runningFlow: StateFlow<Boolean> = _runningFlow
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        changed(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try { ClipboardSync.onAccessibilityEvent(event) } catch (t: Throwable) { Log.w(TAG, "clipboard signal", t) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (running === this) running = null
        changed(running != null)
    }

    /** `type_target` in hello.caps follows this (§3.1): peers get a fresh hello. */
    private fun changed(on: Boolean) {
        if (_runningFlow.value == on) return
        _runningFlow.value = on
        Log.i(TAG, "typing & clipboard service ${if (on) "connected" else "stopped"}")
        try { CapsRefresh.poke() } catch (t: Throwable) { Log.w(TAG, "caps refresh", t) }
    }
}
