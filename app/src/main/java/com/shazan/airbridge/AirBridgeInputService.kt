package com.shazan.airbridge

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import com.shazan.airbridge.input.InputRouter
import com.shazan.airbridge.input.TargetController

/**
 * Accessibility service hosting the target role (virtual cursor + input injection). All protocol
 * logic lives in [TargetController]; messages arrive via [InputRouter] / PeerBus.
 */
class AirBridgeInputService : AccessibilityService() {

    companion object {
        @Volatile private var running: AirBridgeInputService? = null

        /** True while the accessibility service is connected. */
        val isRunning: Boolean get() = running != null

        @Deprecated("v1 shim, removed at merge. Use isRunning.")
        val instance: AirBridgeInputService? get() = running

        @Deprecated("v1 shim, removed at merge. v2 control messages arrive through PeerBus.")
        @Suppress("UNUSED_PARAMETER")
        fun processCommand(command: String) {}
    }

    private var controller: TargetController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val c = TargetController(this)
        c.start()
        controller = c
        InputRouter.target = c
        InputRouter.install(this)
        running = this
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

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
        running = null
    }
}
