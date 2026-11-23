package com.shazan.airbridge.control.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yKeeperTest {
    private val core = "com.shazan.airbridge/com.shazan.airbridge.control.accessibility.ControlAccessibilityService"
    private val text = "com.shazan.airbridge/.control.accessibility.TypingAccessibilityService"

    @Test fun bothDroppedByTheUpdateAreBothMissing() =
        assertEquals(listOf(core, text), A11yKeeper.missing(listOf(core, text), emptyList(), emptyList()))

    @Test fun nothingMissingWhenBothAreStillOn() =
        assertEquals(emptyList<String>(), A11yKeeper.missing(listOf(core, text), listOf(core, text), emptyList()))

    @Test fun shortAndLongNamesOfTheSameServiceMatch() =
        assertEquals(emptyList<String>(), A11yKeeper.missing(listOf(text),
            listOf("com.shazan.airbridge/com.shazan.airbridge.control.accessibility.TypingAccessibilityService"), emptyList()))

    @Test fun servicesAirBridgeTookOutForAPaymentAppAreLeftAlone() =
        assertEquals(emptyList<String>(), A11yKeeper.missing(listOf(core, text), emptyList(), listOf(core, text)))

    @Test fun onlyTheOneThatWentIsMissing() =
        assertEquals(listOf(text), A11yKeeper.missing(listOf(core, text), listOf(core), emptyList()))

    @Test fun aServiceNeverTurnedOnIsNeverAdded() =
        assertEquals(emptyList<String>(), A11yKeeper.missing(listOf(core), listOf(core), emptyList()))

}
