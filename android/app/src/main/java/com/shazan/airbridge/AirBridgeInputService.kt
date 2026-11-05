package com.shazan.airbridge

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.shazan.airbridge.input.InputRouter
import com.shazan.airbridge.input.TargetController
import com.shazan.airbridge.net.CapsRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core accessibility service "AirBridge Universal Control": hosts the target role (virtual cursor,
 * gestures, global actions). All protocol logic lives in [TargetController]; messages arrive via
 * [InputRouter] / PeerBus.
 *
 * Gestures-only profile (res/xml/accessibility_service_config.xml): it cannot read the screen and
 * receives no events. Typing, copy/paste and copy detection need screen content and live in the
 * optional [AirBridgeTextService].
 */
class AirBridgeInputService : AccessibilityService() {

    companion object {
        @Volatile private var running: AirBridgeInputService? = null
        private val _runningFlow = MutableStateFlow(false)

        /** True while the accessibility service is connected. */
        val isRunning: Boolean get() = running != null

        /** [isRunning] as a flow, for the UI. */
        val runningFlow: StateFlow<Boolean> = _runningFlow
    }

    private var controller: TargetController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // A re-connect without unbind must not leave the old overlay (and its cursor) behind.
        controller?.let { old ->
            if (InputRouter.target === old) InputRouter.target = null
            try { old.stop() } catch (t: Throwable) { Log.w("AirBridgeInput", "old controller stop", t) }
        }
        val c = TargetController(this)
        c.start()
        controller = c
        InputRouter.target = c
        InputRouter.install(this)
        running = this
        changed(true)
        RemoteControlSetup.onServiceOn()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    /** No event types are declared, so nothing arrives here. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    private fun shutdown() {
        val c = controller ?: return
        if (InputRouter.target === c) InputRouter.target = null
        c.stop()
        controller = null
        if (running === this) running = null
        changed(false)
    }

    /** `ctl_target` in hello.caps follows this (§3.1): peers get a fresh hello. */
    private fun changed(on: Boolean) {
        if (_runningFlow.value == on) return
        _runningFlow.value = on
        Log.i("AirBridgeInput", "accessibility service ${if (on) "connected" else "stopped"}")
        try { CapsRefresh.poke() } catch (t: Throwable) { Log.w("AirBridgeInput", "caps refresh", t) }
    }
}
