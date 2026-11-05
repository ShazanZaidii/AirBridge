package com.shazan.airbridge.net

import com.shazan.airbridge.net.CapsRefresh.Policy
import com.shazan.airbridge.net.CapsRefresh.Policy.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsRefreshTest {
    private fun peer(id: String, adv: Boolean?, lan: Boolean = true, busy: Boolean = false, last: Long? = null) =
        Peer(id, lan, adv, busy, last)

    @Test fun onlyMismatchedLanSessionsReconnect() {
        val d = Policy.decide(
            listOf(peer("stale", adv = true), peer("fresh", adv = false), peer("nearby", adv = true, lan = false), peer("nohello", adv = null)),
            targetNow = false, now = 100_000,
        )
        assertEquals(listOf("stale"), d.reconnect)
        assertNull(d.recheckInMs)
    }

    @Test fun serviceOnReconnectsSessionsThatSaidNo() {
        val d = Policy.decide(listOf(peer("a", adv = false), peer("b", adv = true)), targetNow = true, now = 0)
        assertEquals(listOf("a"), d.reconnect)
    }

    @Test fun busySessionIsDeferredNotCut() {
        val d = Policy.decide(listOf(peer("a", adv = true, busy = true)), targetNow = false, now = 0)
        assertTrue(d.reconnect.isEmpty())
        assertEquals(Policy.BUSY_RETRY_MS, d.recheckInMs)
    }

    @Test fun atMostOneRefreshPerPeerPerGap() {
        // Service flapping: the peer was just refreshed 3 s ago, wait out the rest of the gap.
        val d = Policy.decide(listOf(peer("a", adv = true, last = 7_000)), targetNow = false, now = 10_000)
        assertTrue(d.reconnect.isEmpty())
        assertEquals(Policy.MIN_GAP_MS - 3_000, d.recheckInMs)
        val later = Policy.decide(listOf(peer("a", adv = true, last = 7_000)), targetNow = false, now = 17_000)
        assertEquals(listOf("a"), later.reconnect)
    }

    @Test fun nothingToDoWhenAllCurrent() {
        val d = Policy.decide(listOf(peer("a", adv = true)), targetNow = true, now = 0)
        assertTrue(d.reconnect.isEmpty())
        assertNull(d.recheckInMs)
    }

    @Test fun byeReasonIsNeverReplaced() {
        assertFalse(CapsRefresh.BYE_REASON == "replaced" || CapsRefresh.BYE_REASON == "user")
    }

    @Test fun ctlTargetOnlyWhenAvailable() {
        assertTrue(Protocol.CAP_TARGET in Protocol.capsFor(true))
        assertFalse(Protocol.CAP_TARGET in Protocol.capsFor(false))
        assertEquals(Protocol.capsFor(true) - Protocol.CAP_TARGET, Protocol.capsFor(false))
    }
}
