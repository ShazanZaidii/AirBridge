package com.shazan.airbridge.connection.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class StaleDevicesTest {
    private fun peer(id: String, name: String = "V2404", hw: String? = null) =
        PairedPeer(id = id, name = name, plat = "android", via = "lan", lastHost = null, lastPort = 0, hw = hw)

    @Test fun aReinstalledDeviceReplacesItsEarlierEntries() {
        val paired = listOf(peer("old1", hw = "H"), peer("old2", hw = "H"), peer("new", hw = "H"))
        assertEquals(listOf("old1", "old2"), StaleDevices.superseded("new", "H", paired, emptySet()))
    }

    @Test fun twoDevicesWithTheSameNameAreBothKept() {
        val paired = listOf(peer("a", "My phone", hw = "H1"), peer("b", "My phone", hw = "H2"))
        assertEquals(emptyList<String>(), StaleDevices.superseded("b", "H2", paired, emptySet()))
    }

    @Test fun entriesWithoutAFingerprintAreNeverGuessedAt() {
        val paired = listOf(peer("legacy"), peer("new", hw = "H"))
        assertEquals(emptyList<String>(), StaleDevices.superseded("new", "H", paired, emptySet()))
        assertEquals(emptyList<String>(), StaleDevices.superseded("new", null, paired, emptySet()))
    }

    @Test fun anEntryThatIsConnectedRightNowIsLeftAlone() {
        val paired = listOf(peer("other-install", hw = "H"), peer("new", hw = "H"))
        assertEquals(emptyList<String>(), StaleDevices.superseded("new", "H", paired, setOf("other-install")))
    }
}
