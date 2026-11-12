package com.shazan.airbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrKindTest {
    private val token = "7ky5Rro0bAAAAAAAAAAAAAAAAA"

    @Test fun pairingCodeIsRecognised() {
        assertTrue(QrKind.classify("airbridge://pair?v=2&id=x&k=y") is QrKind.Pair)
        assertTrue(QrKind.classify("  AIRBRIDGE://PAIR?v=2 ") is QrKind.Pair)
    }

    @Test fun otherAirBridgeLinks() {
        assertTrue(QrKind.classify("airbridge://something") is QrKind.OtherAirBridge)
    }

    @Test fun airWebFullAndShortLinks() {
        val full = QrKind.classify("http://192.168.1.4:47650/?t=$token") as QrKind.AirWeb
        assertEquals("192.168.1.4:47650", full.host)
        assertTrue(QrKind.classify("http://10.0.0.7:47651/j/K3X9QP") is QrKind.AirWeb)
        assertTrue(QrKind.classify("http://my-mac.local:47650/?t=$token") is QrKind.AirWeb)
    }

    @Test fun aWebAddressOutsideTheNetworkIsJustAWebLink() {
        // A public host, a plain https page, a short token or a wrong path are never offered as AirWeb.
        assertTrue(QrKind.classify("http://8.8.8.8:47650/?t=$token") is QrKind.Web)
        assertTrue(QrKind.classify("https://192.168.1.4:47650/?t=$token") is QrKind.Web)
        assertTrue(QrKind.classify("http://192.168.1.4:47650/?t=abc") is QrKind.Web)
        assertTrue(QrKind.classify("http://192.168.1.4:47650/j/ABC") is QrKind.Web)
        assertTrue(QrKind.classify("https://example.com/path") is QrKind.Web)
    }

    @Test fun wifiAndText() {
        assertEquals("Home Net", (QrKind.classify("WIFI:T:WPA;S:Home Net;P:secret;;") as QrKind.Wifi).ssid)
        assertEquals("a;b", (QrKind.classify("WIFI:S:a\\;b;T:WPA;;") as QrKind.Wifi).ssid)
        assertTrue(QrKind.classify("hello") is QrKind.Other)
        assertTrue(QrKind.classify("http://") is QrKind.Other)
    }

    @Test fun bestPrefersPairingThenAirWeb() {
        val list = listOf("https://example.com", "http://10.0.0.7:47651/j/K3X9QP", "airbridge://pair?v=2", "text")
        assertTrue(QrKind.best(list) is QrKind.Pair)
        assertTrue(QrKind.best(list.drop(3).take(0) + list.take(2)) is QrKind.AirWeb)
        assertEquals(null, QrKind.best(emptyList()))
    }

    @Test fun localHosts() {
        assertTrue(QrKind.isLocalHost("172.20.1.1"))
        assertTrue(!QrKind.isLocalHost("172.32.1.1"))
        assertTrue(!QrKind.isLocalHost("1.2.3"))
        assertTrue(!QrKind.isLocalHost("300.1.1.1"))
    }
}
