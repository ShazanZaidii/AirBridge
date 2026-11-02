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
}
