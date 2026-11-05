package com.shazan.airbridge.net

import com.shazan.airbridge.net.ClipAutoPolicy.Decision
import com.shazan.airbridge.net.ClipAutoPolicy.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAutoPolicyTest {
    private val max = 512 * 1024
    private fun policy() = ClipAutoPolicy(debounceMs = 400, echoWindowMs = 2_000, readTimeoutMs = 4_000)

    /** Drive one change through debounce to the read decision. */
    private fun ClipAutoPolicy.changeAndFire(at: Long, meta: ClipMeta? = null): Decision {
        assertEquals(Decision.Wait(400), onChange(at, meta, enabled = true, hasPeers = true))
        return onTimer(at + 400, enabled = true, hasPeers = true)
    }

    @Test fun backgroundChangeIsDebouncedThenRead() {
        val p = policy()
        assertEquals(Decision.Read, p.changeAndFire(1_000))
        assertTrue(p.inFlight)
        assertEquals(Decision.Send, p.onRead(ClipMeta(), "h1", 5, max))
        assertFalse(p.inFlight)
    }

    @Test fun changesWithinDebounceAreCoalesced() {
        val p = policy()
        assertEquals(Decision.Wait(400), p.onChange(0, null, true, true))
        assertEquals(Decision.Coalesced, p.onChange(150, null, true, true))
        assertEquals(Decision.Coalesced, p.onChange(300, null, true, true))
        // Timer armed at 0 fires at 400, but the last change was at 300: wait the remaining 300 ms.
        assertEquals(Decision.Wait(300), p.onTimer(400, true, true))
        assertEquals(Decision.Read, p.onTimer(700, true, true))
        // Only one read for three changes.
        assertEquals(Decision.Skipped(Skip.IN_FLIGHT), p.onTimer(800, true, true))
    }

    @Test fun secondChangeWhileReadInFlightIsIgnored() {
        val p = policy()
        assertEquals(Decision.Read, p.changeAndFire(0))
        assertEquals(Decision.Skipped(Skip.IN_FLIGHT), p.onChange(500, null, true, true))
        p.onRead(null, "h", 1, max)
        assertEquals(Decision.Wait(400), p.onChange(900, null, true, true))
    }

    @Test fun staleInFlightReadDoesNotWedge() {
        val p = policy()
        assertEquals(Decision.Read, p.changeAndFire(0))
        // The read never reported back and its timeout was lost: after readTimeoutMs a change works again.
        assertEquals(Decision.Wait(400), p.onChange(10_000, null, true, true))
    }

    @Test fun noPeersDoesNotWakeAnything() {
        val p = policy()
        assertEquals(Decision.Skipped(Skip.NO_PEERS), p.onChange(0, null, enabled = true, hasPeers = false))
        assertFalse(p.pending)
        // Peers went away during the debounce: no read either.
        assertEquals(Decision.Wait(400), p.onChange(1_000, null, true, true))
        assertEquals(Decision.Skipped(Skip.NO_PEERS), p.onTimer(1_400, true, hasPeers = false))
        assertFalse(p.inFlight)
    }

    @Test fun disabledNeverReads() {
        val p = policy()
        assertEquals(Decision.Skipped(Skip.DISABLED), p.onChange(0, null, enabled = false, hasPeers = true))
        assertEquals(Decision.Wait(400), p.onChange(1_000, null, true, true))
        assertEquals(Decision.Skipped(Skip.DISABLED), p.onTimer(1_400, enabled = false, hasPeers = true))
    }

    @Test fun skipRulesFromDescriptionBeforeReading() {
        val p = policy()
        assertEquals(Decision.Skipped(Skip.SENSITIVE), p.onChange(0, ClipMeta(sensitive = true), true, true))
        assertEquals(Decision.Skipped(Skip.FILES), p.onChange(10, ClipMeta(fileList = true), true, true))
        assertEquals(Decision.Skipped(Skip.REMOTE_DEVICE), p.onChange(20, ClipMeta(remoteDevice = true), true, true))
        assertEquals(Decision.Skipped(Skip.OWN_CLIP), p.onChange(30, ClipMeta(ownClip = true), true, true))
        // Unknown description (background) still proceeds.
        assertEquals(Decision.Wait(400), p.onChange(40, null, true, true))
    }

    @Test fun skipRulesAfterFocusedRead() {
        val p = policy()
        p.changeAndFire(0)
        assertEquals(Decision.Skipped(Skip.SENSITIVE), p.onRead(ClipMeta(sensitive = true), "h", 3, max))
        p.changeAndFire(1_000)
        assertEquals(Decision.Skipped(Skip.FILES), p.onRead(ClipMeta(fileList = true), null, 0, max))
        p.changeAndFire(2_000)
        assertEquals(Decision.Skipped(Skip.EMPTY), p.onRead(ClipMeta(), null, 0, max))
        p.changeAndFire(3_000)
        assertEquals(Decision.Skipped(Skip.TOO_LARGE), p.onRead(ClipMeta(), "big", max + 1, max))
    }

    @Test fun remoteSetClipIsNotEchoedBack() {
        val p = policy()
        p.onRemoteSet(now = 5_000, hash = "remote")
        // The change signal our own setPrimaryClip causes, within the echo window.
        assertEquals(Decision.Skipped(Skip.ECHO), p.onChange(5_050, null, true, true))
        // Even if a signal slips through later, the content hash still matches what we received.
        assertEquals(Decision.Read, p.changeAndFire(9_000))
        assertEquals(Decision.Skipped(Skip.ECHO), p.onRead(ClipMeta(), "remote", 6, max))
        assertTrue(p.isDuplicate("remote"))
    }

    @Test fun alreadySentContentIsNotResent() {
        val p = policy()
        p.onPushed("h1")
        p.changeAndFire(0)
        assertEquals(Decision.Skipped(Skip.UNCHANGED), p.onRead(ClipMeta(), "h1", 2, max))
        p.changeAndFire(1_000)
        assertEquals(Decision.Send, p.onRead(ClipMeta(), "h2", 2, max))
    }

    @Test fun blockedReadMarksPendingUntilPushOrNextChange() {
        val p = policy()
        assertEquals(Decision.Read, p.changeAndFire(0))
        p.onReadFailed()
        assertTrue(p.pending)
        assertFalse(p.inFlight)
        // The bubble tap sends it.
        p.onPushed("h")
        assertFalse(p.pending)

        p.changeAndFire(1_000); p.onReadFailed()
        assertTrue(p.pending)
        // A newer copy replaces the unsent one (it gets its own attempt).
        p.onChange(2_000, null, true, true)
        assertFalse(p.pending)

        p.markPending()
        assertTrue(p.pending)
        p.clearPending()
        assertFalse(p.pending)
    }

    @Test fun checkIsPure() {
        val p = policy()
        assertNull(p.check(ClipMeta(), "x", 1, max))
        assertEquals(Skip.SENSITIVE, p.check(ClipMeta(sensitive = true), "x", 1, max))
        assertFalse(p.inFlight); assertFalse(p.pending)
    }
}
