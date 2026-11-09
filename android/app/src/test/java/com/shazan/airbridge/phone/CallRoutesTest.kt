package com.shazan.airbridge.phone

import android.media.AudioDeviceInfo
import com.shazan.airbridge.phone.CallRoutes.Dev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §13.5: the phone's call audio devices as reported to computers, and picking one back. */
class CallRoutesTest {
    private val devs = listOf(
        Dev(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Galaxy Buds2", "AA:BB:CC:DD:EE:01"),
        Dev(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "V2404", ""),
        Dev(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "V2404", ""),
        Dev(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Shazan's MacBook Pro", "AA:BB:CC:DD:EE:02"),
        Dev(AudioDeviceInfo.TYPE_TELEPHONY, null, null),
        Dev(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "V2404", ""), // duplicate
        Dev(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Headphones", ""),
    )

    @Test fun routesAreOrderedNamedAndDeduplicated() {
        val r = CallRoutes.routes(devs) { it.name == "Shazan's MacBook Pro" }
        assertEquals(listOf("phone:earpiece", "phone:speaker", "phone:wired", "phone:bt:AA:BB:CC:DD:EE:01", "phone:bt:AA:BB:CC:DD:EE:02"), r.map { it.id })
        assertNull("built-in devices carry no product name", r[0].name)
        assertEquals("Galaxy Buds2", r[3].name)
        assertFalse(r[3].computer)
        assertTrue("the computer's own hands-free link is marked", r[4].computer)
    }

    @Test fun picks() {
        assertEquals(CallRoutes.Pick.Speaker, CallRoutes.parse("phone:speaker"))
        assertEquals(CallRoutes.Pick.Earpiece, CallRoutes.parse("phone:earpiece"))
        assertEquals(CallRoutes.Pick.Device("bt", "AA:BB:CC:DD:EE:01"), CallRoutes.parse("phone:bt:AA:BB:CC:DD:EE:01"))
        assertEquals(CallRoutes.Pick.Device("wired", null), CallRoutes.parse("phone:wired"))
        assertNull(CallRoutes.parse("mac:speaker"))
        assertNull(CallRoutes.parse("phone:telephony"))
    }

    @Test fun wireShape() {
        val routes = CallRoutes.routes(devs.take(3)) { false }
        val c = NotifWire.Call("c1", NotifWire.CallState.ACTIVE, "Asha", null, 5L, can = listOf("end"), routes = routes, route = "phone:speaker")
        val m = NotifWire.call(c)
        @Suppress("UNCHECKED_CAST") val list = m["routes"] as List<Map<String, Any?>>
        assertEquals(mapOf("id" to "phone:earpiece", "kind" to "earpiece"), list[0])
        assertEquals(mapOf("id" to "phone:bt:AA:BB:CC:DD:EE:01", "kind" to "bt", "name" to "Galaxy Buds2"), list[2])
        assertEquals("phone:speaker", m["route"])
        assertFalse(NotifWire.call(c.copy(routes = emptyList(), route = null)).containsKey("routes"))
    }

    @Test fun routeActionNeedsADevice() {
        assertEquals(NotifWire.Command.CallAction("c1", "route", "phone:speaker"),
            NotifWire.parse(mapOf("t" to "call_action", "id" to "c1", "op" to "route", "dev" to "phone:speaker")))
        assertNull(NotifWire.parse(mapOf("t" to "call_action", "id" to "c1", "op" to "route")))
        assertNull(NotifWire.parse(mapOf("t" to "call_action", "id" to "c1", "op" to "route", "dev" to "x".repeat(81))))
    }

    @Test fun machineReportsRouteChangesOnce() {
        val m = CallMachine { 1000L }
        m.onTelephony(CallMachine.Telephony.OFFHOOK)
        val r = CallRoutes.routes(devs.take(3)) { false }
        assertTrue(m.setRoutes(r, "phone:earpiece"))
        assertFalse(m.setRoutes(r, "phone:earpiece"))
        assertTrue(m.setRoutes(r, "phone:bt:AA:BB:CC:DD:EE:01"))
        assertTrue(m.accepts(m.current!!.id, "route"))
    }
}
