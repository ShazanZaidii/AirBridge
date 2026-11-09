package com.shazan.airbridge.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloodGateTest {
    @Test fun burstIsCappedPerAppAndTheRestComesLater() {
        val g = FloodGate<String>(perApp = 3, appRefillMs = 1000, global = 100, globalRefillMs = 10)
        val sent = (1..10).filter { g.offer("chatty", "k$it", "n$it", now = 0) }
        assertEquals(listOf(1, 2, 3), sent)
        assertEquals(7, g.parkedCount)
        assertTrue(g.due(0).isEmpty())
        assertNotNull(g.nextDueInMs(0))
        assertEquals(listOf("n4"), g.due(1000)) // one token back after a second
        assertEquals(listOf("n5", "n6"), g.due(3000))
    }

    @Test fun otherAppsAreNotHeldBackByAChattyOne() {
        val g = FloodGate<String>(perApp = 2, appRefillMs = 1000, global = 100, globalRefillMs = 10)
        repeat(5) { g.offer("chatty", "c$it", "c$it", 0) }
        assertTrue(g.offer("quiet", "q", "q", 0))
    }

    @Test fun newestVersionOfAParkedKeyWins() {
        val g = FloodGate<String>(perApp = 1, appRefillMs = 1000, global = 100, globalRefillMs = 10)
        assertTrue(g.offer("a", "k", "v1", 0))
        assertFalse(g.offer("a", "k", "v2", 10))
        assertFalse(g.offer("a", "k", "v3", 20))
        assertEquals(1, g.parkedCount)
        assertEquals(listOf("v3"), g.due(2000))
        assertNull(g.nextDueInMs(2000))
    }

    @Test fun aParkedKeyStaysParkedSoOrderIsKept() {
        val g = FloodGate<String>(perApp = 1, appRefillMs = 1000, global = 100, globalRefillMs = 10)
        g.offer("a", "k", "v1", 0)
        g.offer("a", "k", "v2", 10) // parked
        // A token is back, but k is parked: the new version replaces the parked one instead of jumping ahead.
        assertFalse(g.offer("a", "k", "v3", 1500))
        assertEquals(listOf("v3"), g.due(1500))
    }

    @Test fun removalDropsParkedPost() {
        val g = FloodGate<String>(perApp = 1, appRefillMs = 1000, global = 100, globalRefillMs = 10)
        g.offer("a", "k1", "v1", 0)
        g.offer("a", "k2", "v2", 0)
        g.forget("k2")
        assertEquals(0, g.parkedCount)
        assertTrue(g.due(5000).isEmpty())
    }

    @Test fun globalLimitAndBoundedParking() {
        val g = FloodGate<Int>(perApp = 100, appRefillMs = 10, global = 5, globalRefillMs = 1000, maxParked = 10)
        val sent = (0 until 50).count { g.offer("app$it", "k$it", it, 0) }
        assertEquals(5, sent)
        assertEquals(10, g.parkedCount) // oldest dropped
        assertEquals(listOf(40), g.due(1000)) // the oldest still parked
    }
}
