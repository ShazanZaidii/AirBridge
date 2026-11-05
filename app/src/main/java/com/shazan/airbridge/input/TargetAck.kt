package com.shazan.airbridge.input

/**
 * §8.1 acknowledgement: a target answers every `ctl_enter` at once, either `ctl_ack` (cursor shown,
 * input can be injected) or `ctl_leave reason=unavailable`. Pure, so it is unit-tested (TargetAckTest).
 */
object TargetAck {
    const val ACK = "ctl_ack"
    const val UNAVAILABLE = "unavailable"

    enum class Reply { ACK, UNAVAILABLE }

    /** Before taking the lease: can this phone be a target at all right now? */
    fun canAccept(serviceRunning: Boolean, overlayAttached: Boolean, canInject: Boolean, screenW: Int, screenH: Int): Boolean =
        serviceRunning && overlayAttached && canInject && screenW > 1 && screenH > 1

    /** After entering: the cursor really is on screen. */
    fun reply(accepted: Boolean, cursorShown: Boolean): Reply =
        if (accepted && cursorShown) Reply.ACK else Reply.UNAVAILABLE
}

/** Lets one event through per [intervalMs] (first one always passes). Thread-safe. */
class OncePer(private val intervalMs: Long) {
    private var last: Long? = null

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        val l = last
        if (l != null && now - l in 0 until intervalMs) return false
        last = now
        return true
    }
}
