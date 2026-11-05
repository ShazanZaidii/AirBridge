package com.shazan.airbridge.net

import com.shazan.airbridge.net.CapsRefresh.Policy
import com.shazan.airbridge.net.CapsRefresh.Policy.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsRefreshTest {
    private val ON = setOf(Protocol.CAP_TARGET)
    private val OFF = emptySet<String>()
    private val TYPING = setOf(Protocol.CAP_TARGET, Protocol.CAP_TYPE)

    private fun peer(id: String, adv: Boolean?, lan: Boolean = true, busy: Boolean = false, last: Long? = null) =
        Peer(id, lan, adv?.let { if (it) ON else OFF }, busy, last)

    @Test fun onlyMismatchedLanSessionsReconnect() {
        val d = Policy.decide(
            listOf(peer("stale", adv = true), peer("fresh", adv = false), peer("nearby", adv = true, lan = false), peer("nohello", adv = null)),
            capsNow = OFF, now = 100_000,
        )
        assertEquals(listOf("stale"), d.reconnect)
        assertNull(d.recheckInMs)
    }

    @Test fun serviceOnReconnectsSessionsThatSaidNo() {
        val d = Policy.decide(listOf(peer("a", adv = false), peer("b", adv = true)), capsNow = ON, now = 0)
        assertEquals(listOf("a"), d.reconnect)
    }

    @Test fun busySessionIsDeferredNotCut() {
        val d = Policy.decide(listOf(peer("a", adv = true, busy = true)), capsNow = OFF, now = 0)
        assertTrue(d.reconnect.isEmpty())
        assertEquals(Policy.BUSY_RETRY_MS, d.recheckInMs)
    }

    @Test fun atMostOneRefreshPerPeerPerGap() {
        // Service flapping: the peer was just refreshed 3 s ago, wait out the rest of the gap.
        val d = Policy.decide(listOf(peer("a", adv = true, last = 7_000)), capsNow = OFF, now = 10_000)
        assertTrue(d.reconnect.isEmpty())
        assertEquals(Policy.MIN_GAP_MS - 3_000, d.recheckInMs)
        val later = Policy.decide(listOf(peer("a", adv = true, last = 7_000)), capsNow = OFF, now = 17_000)
        assertEquals(listOf("a"), later.reconnect)
    }

    @Test fun nothingToDoWhenAllCurrent() {
        val d = Policy.decide(listOf(peer("a", adv = true)), capsNow = ON, now = 0)
        assertTrue(d.reconnect.isEmpty())
        assertNull(d.recheckInMs)
    }

    @Test fun byeReasonIsNeverReplaced() {
        assertFalse(CapsRefresh.BYE_REASON == "replaced" || CapsRefresh.BYE_REASON == "user")
    }

    @Test fun typingServiceChangeReconnects() {
        val on = Policy.decide(listOf(Peer("a", true, ON, false, null)), capsNow = TYPING, now = 0)
        assertEquals(listOf("a"), on.reconnect)
        val off = Policy.decide(listOf(Peer("a", true, TYPING, false, null)), capsNow = ON, now = 0)
        assertEquals(listOf("a"), off.reconnect)
        val same = Policy.decide(listOf(Peer("a", true, TYPING, false, null)), capsNow = TYPING, now = 0)
        assertTrue(same.reconnect.isEmpty())
    }

    @Test fun typeTargetOnlyWithBothServices() {
        assertTrue(Protocol.CAP_TYPE in Protocol.capsFor(true, typing = true))
        assertFalse(Protocol.CAP_TYPE in Protocol.capsFor(true, typing = false))
        assertFalse(Protocol.CAP_TYPE in Protocol.capsFor(false, typing = true)) // no target, no key messages
        assertEquals(Protocol.capsFor(true, true) - Protocol.CAP_TYPE, Protocol.capsFor(true, false))
    }

    @Test fun ctlTargetOnlyWhenAvailable() {
        assertTrue(Protocol.CAP_TARGET in Protocol.capsFor(true))
        assertFalse(Protocol.CAP_TARGET in Protocol.capsFor(false))
        assertEquals(Protocol.capsFor(true) - Protocol.CAP_TARGET, Protocol.capsFor(false))
    }
}
