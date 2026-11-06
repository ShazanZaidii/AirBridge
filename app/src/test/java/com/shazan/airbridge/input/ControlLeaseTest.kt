package com.shazan.airbridge.input

import com.shazan.airbridge.input.ControlLease.Decision.Accept
import com.shazan.airbridge.input.ControlLease.Decision.Drop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlLeaseTest {
    private val l = ControlLease()

    @Test fun enterTakesLease() {
        assertNull(l.onEnter("A", 0))
        assertEquals("A", l.holder)
        assertTrue(l.explicit)
    }

    @Test fun enterPreemptsPreviousHolder() {
        l.onEnter("A", 0)
        assertEquals("A", l.onEnter("B", 100))
        assertEquals("B", l.holder)
    }

    @Test fun reEnterBySameHolderPreemptsNobody() {
        l.onEnter("A", 0)
        assertNull(l.onEnter("A", 10))
    }

    @Test fun inputFromNonHolderIsDroppedWithThrottledBusy() {
        l.onEnter("A", 0)
        assertEquals(Drop("B"), l.onInput("B", 100))
        assertEquals(Drop(null), l.onInput("B", 1500))
        assertEquals(Drop("B"), l.onInput("B", 2100))
        assertEquals(Accept(false), l.onInput("A", 2200))
    }

    @Test fun implicitLeaseExpiresAfter1500ms() {
        assertEquals(Accept(true), l.onInput("A", 0))
        assertFalse(l.explicit)
        assertEquals(Drop("B"), l.onInput("B", 1000))
        assertEquals(Accept(false), l.onInput("A", 1400)) // refreshes
        assertEquals(1400L + 1500L, l.nextExpiry())
        assertNull(l.expire(2800))
        assertEquals("A", l.expire(2900))
        assertEquals(Accept(true), l.onInput("B", 2950))
    }

    @Test fun explicitLeaseNeverExpires() {
        l.onEnter("A", 0)
        assertNull(l.expire(60_000))
        assertNull(l.nextExpiry())
    }

    @Test fun enterPreemptsImplicitHolder() {
        l.onInput("A", 0)
        assertEquals("A", l.onEnter("B", 100))
        assertTrue(l.explicit)
    }

    @Test fun leaveOnlyFromHolder() {
        l.onEnter("A", 0)
        assertFalse(l.onLeave("B"))
        assertTrue(l.onLeave("A"))
        assertNull(l.holder)
    }

    @Test fun holderDisconnectReleases() {
        l.onInput("A", 0)
        assertTrue(l.onPeerGone("A"))
        assertNull(l.holder)
    }

    // ---- §8.6 No loops ----

    @Test fun enterFromThePeerWeDriveIsRefused() {
        l.driving = "A"
        assertFalse(l.enterAllowed("A"))
        assertTrue(l.enterAllowed("B"))
        assertNull(l.holder) // a refused enter never touches the lease
    }

    @Test fun inputFromThePeerWeDriveGetsLoopBusyEvery2s() {
        l.driving = "A"
        assertEquals(Drop("A", loop = true), l.onInput("A", 0))
        assertEquals(Drop(null, loop = true), l.onInput("A", 1999))
        assertEquals(Drop("A", loop = true), l.onInput("A", 2000))
        assertNull(l.holder)
        l.driving = null
        assertEquals(Accept(true), l.onInput("A", 2100))
    }

    @Test fun drivingDoesNotAffectOtherPeers() {
        l.driving = "A"
        assertEquals(Accept(true), l.onInput("B", 0))
    }

    // ---- §8.6 No stray state ----

    @Test fun lateMovesAfterHandBackDoNotTakeALease() {
        l.onEnter("A", 0)
        assertTrue(l.onLeave("A"))
        l.onHandBack("A", 1000)
        assertEquals(Drop(null), l.onInput("A", 1100))
        assertNull(l.holder)
        assertEquals(Accept(true), l.onInput("A", 1800)) // after the grace: direct mode again
    }

    @Test fun reEnterEndsTheHandBackGrace() {
        l.onHandBack("A", 1000)
        l.onEnter("A", 1050)
        assertEquals(Accept(false), l.onInput("A", 1060))
    }

    @Test fun silentHolderIsDetectedAfter10s() {
        l.onEnter("A", 0)
        assertFalse(l.isSilent(now = 9_999, lastHeard = 0))
        assertTrue(l.isSilent(now = 10_000, lastHeard = 0))
        assertFalse(l.isSilent(now = 15_000, lastHeard = 6_000)) // a ping at 6 s keeps it alive
    }

    @Test fun silenceNeedsAHolder() {
        assertFalse(l.isSilent(now = 60_000, lastHeard = 0))
    }
}
