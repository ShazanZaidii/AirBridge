package com.shazan.airbridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNamesTest {
    @Test fun systemDeviceNameWins() {
        assertEquals("Shazan's phone", DeviceNames.defaultName("Shazan's phone", "vivo", "V2404"))
    }

    @Test fun blankSystemNameFallsBackToMakeAndModel() {
        assertEquals("Vivo V2404", DeviceNames.defaultName(null, "vivo", "V2404"))
        assertEquals("Vivo V2404", DeviceNames.defaultName("   ", "vivo", "V2404"))
    }

    @Test fun makeIsNotRepeated() {
        assertEquals("Google Pixel 9", DeviceNames.makeAndModel("Google", "Pixel 9"))
        assertEquals("OnePlus 12", DeviceNames.makeAndModel("OnePlus", "OnePlus 12"))
        assertEquals("samsung SM-S918B".replaceFirstChar { it.uppercase() }, DeviceNames.makeAndModel("samsung", "SM-S918B"))
    }

    @Test fun missingPartsAndWhitespace() {
        assertEquals("V2404", DeviceNames.makeAndModel(null, "V2404"))
        assertEquals("V2404", DeviceNames.makeAndModel("unknown", " V2404 "))
        assertEquals("Vivo", DeviceNames.makeAndModel("vivo", null))
        assertEquals("Android phone", DeviceNames.makeAndModel(null, null))
        assertEquals("My  phone".replace("  ", " "), DeviceNames.defaultName("My \n phone", "x", "y"))
    }

    @Test fun rawModelOrBlankCountsAsNeverSet() {
        assertTrue(DeviceNames.isUnsetName("V2404", "V2404"))
        assertTrue(DeviceNames.isUnsetName(" V2404 ", "V2404"))
        assertTrue(DeviceNames.isUnsetName("", "V2404"))
        assertTrue(DeviceNames.isUnsetName(null, "V2404"))
        assertFalse(DeviceNames.isUnsetName("Work phone", "V2404"))
        assertFalse(DeviceNames.isUnsetName("v2404 mine", "V2404"))
    }
}
