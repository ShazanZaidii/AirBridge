package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.CallAudioInfo.Computer
import com.shazan.airbridge.phone.CallAudioInfo.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallAudioInfoTest {
    private val sco = 7          // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val ble = 26         // TYPE_BLE_HEADSET
    private val earpiece = 1     // TYPE_BUILTIN_EARPIECE
    private val speaker = 2      // TYPE_BUILTIN_SPEAKER
    private val a2dp = 8         // TYPE_BLUETOOTH_A2DP (music only, not calls)

    private val mac = Computer("Shazan's MacBook Pro", "F0:18:98:11:22:33")
    private val macAsHeadset = Output(sco, "Shazan's MacBook Pro", "F0:18:98:11:22:33")
    private val buds = Output(sco, "Galaxy Buds2", "AA:BB:CC:DD:EE:FF")

    @Test fun noHeadset() {
        assertNull(CallAudioInfo.pickHeadset(listOf(Output(earpiece, null, ""), Output(speaker, null, "")), null, mac))
    }

    @Test fun musicOnlyBluetoothDoesNotCount() {
        assertNull(CallAudioInfo.pickHeadset(listOf(Output(a2dp, "Car stereo", "11:22:33:44:55:66")), null, mac))
    }

    @Test fun connectedHeadsetCounts() {
        assertEquals(buds, CallAudioInfo.pickHeadset(listOf(Output(speaker, null, ""), buds), null, mac))
    }

    @Test fun leAudioHeadsetCounts() {
        val le = Output(ble, "Pixel Buds Pro", "12:34:56:78:9A:BC")
        assertEquals(le, CallAudioInfo.pickHeadset(listOf(le), null, mac))
    }

    @Test fun theMacItselfIsLeftOut() {
        assertNull(CallAudioInfo.pickHeadset(listOf(macAsHeadset), macAsHeadset, mac))
    }

    @Test fun theMacIsLeftOutByNameWhenTheAddressIsHidden() {
        val hidden = Output(sco, "Shazan's MacBook Pro", "")
        assertNull(CallAudioInfo.pickHeadset(listOf(hidden), null, mac))
    }

    @Test fun theMacIsLeftOutByAnonymizedAddress() {
        val anon = Output(sco, "Mac", "XX:XX:XX:XX:22:33")
        assertNull(CallAudioInfo.pickHeadset(listOf(anon), null, mac))
    }

    @Test fun headsetNextToTheMacStillCounts() {
        assertEquals(buds, CallAudioInfo.pickHeadset(listOf(macAsHeadset, buds), macAsHeadset, mac))
    }

    @Test fun deviceInUseForCallsWins() {
        val other = Output(sco, "Car", "01:02:03:04:05:06")
        assertEquals(other, CallAudioInfo.pickHeadset(listOf(buds, other), other, mac))
    }

    @Test fun withoutComputerInfoEveryHeadsetCounts() {
        assertEquals(macAsHeadset, CallAudioInfo.pickHeadset(listOf(macAsHeadset), null, null))
    }
}
