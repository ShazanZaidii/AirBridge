package com.shazan.airbridge.promirror

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbWifiPolicyTest {
    @Test fun opensDirectlyWhenAndroidAllowsIt() {
        assertEquals(AdbWifiPolicy.Open.DIRECT, AdbWifiPolicy.openPlan(canDrawOverlays = true, accessibilityRunning = false))
        assertEquals(AdbWifiPolicy.Open.DIRECT, AdbWifiPolicy.openPlan(canDrawOverlays = false, accessibilityRunning = true))
    }

    @Test fun otherwiseAsksWithANotification() {
        assertEquals(AdbWifiPolicy.Open.NOTIFICATION, AdbWifiPolicy.openPlan(canDrawOverlays = false, accessibilityRunning = false))
    }

    @Test fun capIsNotAdvertisedWithoutWirelessDebugging() {
        // Unit tests run with SDK_INT = 0: no Wireless debugging, so the cap stays out of hello.
        assert(AdbWifi.CAP !in com.shazan.airbridge.net.Protocol.capsFor(target = false))
    }
}
