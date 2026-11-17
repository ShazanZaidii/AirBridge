package com.shazan.airbridge.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledServicesTest {
    private val pkg = "com.shazan.airbridge"
    private val a = "$pkg/$pkg.AirBridgeInputService"
    private val b = "$pkg/.AirBridgeTextService"
    private val other = "com.other/com.other.Reader"

    @Test fun removesOnlyOurEntries() {
        val (next, gone) = EnabledServices.remove("$other:$a:$b", pkg)
        assertEquals(other, next)
        assertEquals(listOf(a, b), gone)
    }

    @Test fun nothingToRemoveLeavesTheListAlone() {
        val (next, gone) = EnabledServices.remove(other, pkg)
        assertEquals(other, next)
        assertTrue(gone.isEmpty())
        assertEquals("" to emptyList<String>(), EnabledServices.remove(null, pkg))
    }

    @Test fun lastServiceGivesAnEmptyString() {
        assertEquals("", EnabledServices.remove(a, pkg).first)
    }

    @Test fun restoreAddsBackAndKeepsTheRest() {
        assertEquals("$other:$a:$b", EnabledServices.restore(other, listOf(a, b)))
        assertEquals("$a:$b", EnabledServices.restore("", listOf(a, b)))
        assertEquals("$a:$b", EnabledServices.restore(null, listOf(a, b)))
    }

    @Test fun restoreDoesNotDuplicateWhatTheUserTurnedOnMeanwhile() {
        // b was switched on again by hand, spelled the long way; only a is missing.
        val now = "$other:$pkg/$pkg.AirBridgeTextService"
        assertEquals("$now:$a", EnabledServices.restore(now, listOf(a, b)))
    }

    @Test fun normalMakesShortNamesLong() {
        assertEquals("$pkg/$pkg.X", EnabledServices.normal("$pkg/.X"))
        assertEquals("$pkg/$pkg.X", EnabledServices.normal("$pkg/$pkg.X"))
    }

    @Test fun oursMatchesByPackageNotPrefix() {
        assertEquals(listOf(a), EnabledServices.ours("$a:${pkg}x/${pkg}x.S", pkg))
    }

    @Test fun onlyUpiAppsPauseControl() {
        assertTrue(PaymentApps.isPayment("net.one97.paytm"))
        assertFalse(PaymentApps.isPayment("com.whatsapp"))
        assertFalse(PaymentApps.isPayment(null))
    }
}
