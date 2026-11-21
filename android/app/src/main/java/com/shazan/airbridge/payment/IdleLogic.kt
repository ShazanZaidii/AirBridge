package com.shazan.airbridge.payment

/**
 * What the coordinator does next, from the facts. Pure and unit-tested: the watcher sleeps (no timers) except
 * for the one-minute wait after the last device went away.
 */
object IdleLogic {
    sealed class Plan {
        /** The switch is off: put back whatever we took out. */
        data object Disabled : Plan()
        /** A device is connected: services on (restore if they were off for lack of one) and watch for payment apps. */
        data object Active : Plan()
        /** Services were off for lack of a device, and the user switched them on by hand: leave them. */
        data object AdoptManual : Plan()
        /** Nothing to do until something changes. */
        data object Sleep : Plan()
        /** No device and services on: wait out the rest of the minute (woken early by a change). */
        data object Wait : Plan()
        /** The minute is over with no device: switch the services off. */
        data object GoIdle : Plan()
    }

    fun decide(
        enabled: Boolean, connected: Boolean, paused: Boolean, idleOff: Boolean, hasEntries: Boolean, noDeviceForMs: Long,
        idleMs: Long = PaymentPause.IDLE_MS,
    ): Plan = when {
        !enabled -> Plan.Disabled
        connected -> Plan.Active
        idleOff -> if (hasEntries) Plan.AdoptManual else Plan.Sleep
        // A payment app is in front: its end (resume) decides, it keeps them off if there is still no device.
        paused -> Plan.Sleep
        !hasEntries -> Plan.Sleep
        noDeviceForMs >= idleMs -> Plan.GoIdle
        else -> Plan.Wait
    }
}
