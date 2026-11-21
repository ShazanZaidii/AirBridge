package com.shazan.airbridge.payment

import com.shazan.airbridge.payment.IdleLogic.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class IdleLogicTest {
    private fun d(
        enabled: Boolean = true, connected: Boolean = false, paused: Boolean = false, idleOff: Boolean = false,
        hasEntries: Boolean = true, noDeviceForMs: Long = 0,
    ) = IdleLogic.decide(enabled, connected, paused, idleOff, hasEntries, noDeviceForMs, idleMs = 60_000)

    @Test fun aConnectedDeviceKeepsTheWatcherActive() {
        assertEquals(Plan.Active, d(connected = true))
        assertEquals(Plan.Active, d(connected = true, idleOff = true)) // and brings the services back
        assertEquals(Plan.Active, d(connected = true, paused = true))
    }

    @Test fun noDeviceWaitsOutTheMinuteThenSwitchesOff() {
        assertEquals(Plan.Wait, d(noDeviceForMs = 0))
        assertEquals(Plan.Wait, d(noDeviceForMs = 59_999))
        assertEquals(Plan.GoIdle, d(noDeviceForMs = 60_000))
        assertEquals(Plan.GoIdle, d(noDeviceForMs = 120_000))
    }

    @Test fun nothingToSwitchOffMeansSleep() {
        assertEquals(Plan.Sleep, d(hasEntries = false, noDeviceForMs = 500_000))
    }

    @Test fun afterSwitchingOffItSleepsUntilADeviceConnects() {
        assertEquals(Plan.Sleep, d(idleOff = true, hasEntries = false, noDeviceForMs = 999_999))
    }

    @Test fun servicesSwitchedOnByHandWhileOffAreLeftAlone() {
        assertEquals(Plan.AdoptManual, d(idleOff = true, hasEntries = true))
    }

    @Test fun aPaymentPauseWithoutADeviceWaitsForThePaymentAppToLeave() {
        assertEquals(Plan.Sleep, d(paused = true, hasEntries = false, noDeviceForMs = 999_999))
    }

    @Test fun theSwitchBeingOffRestoresWhateverWasTakenOut() {
        assertEquals(Plan.Disabled, d(enabled = false))
        assertEquals(Plan.Disabled, d(enabled = false, connected = true, idleOff = true))
    }
}
