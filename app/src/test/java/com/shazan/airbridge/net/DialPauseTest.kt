package com.shazan.airbridge.net

import com.shazan.airbridge.net.DialPause.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialPauseTest {
    /** Stands in for PairStore's persisted record (survives a "restart" = a new DialPause). */
    private class FakeStore : PauseStore {
        val map = HashMap<String, DisconnectBy>()
        override fun userPause(peerId: String) = map[peerId]
        override fun setUserPause(peerId: String, by: DisconnectBy?) { if (by == null) map.remove(peerId) else map[peerId] = by }
    }

    private val store = FakeStore()

    @Test fun userDisconnectIsPersistedAndSurvivesRestart() {
        DialPause("mac", store).userDisconnect()
        assertEquals(DisconnectBy.SELF, store.map["mac"])
        val afterRestart = DialPause("mac", store)
        assertTrue(afterRestart.paused)
        assertEquals(DisconnectBy.SELF, afterRestart.user)
    }

    @Test fun appOpenDoesNotUnpauseUserDisconnect() {
        val p = DialPause("mac", store)
        p.userDisconnect()
        assertFalse(p.apply(Trigger.APP_OPEN))
        assertFalse(DialPause("mac", store).apply(Trigger.APP_OPEN)) // after a reboot too
        assertFalse(p.apply(Trigger.NETWORK_CHANGE))
        assertFalse(p.apply(Trigger.LINK_ENDED))
        assertTrue(p.paused)
    }

    @Test fun connectClearsUserDisconnect() {
        val p = DialPause("mac", store)
        p.userDisconnect()
        assertTrue(p.apply(Trigger.USER_CONNECT))
        assertNull(store.map["mac"])
        assertFalse(DialPause("mac", store).paused)
    }

    @Test fun newPairingClearsUserDisconnect() {
        val p = DialPause("mac", store)
        p.userDisconnect()
        assertTrue(p.apply(Trigger.NEW_PAIRING))
    }

    @Test fun peerByeUserIsPersistedAsPeer() {
        val p = DialPause("mac", store)
        p.onEnded(Outcome.Ended(DialPause.BYE_USER))
        assertEquals(DisconnectBy.PEER, store.map["mac"])
        assertFalse(p.apply(Trigger.APP_OPEN))
        assertTrue(DialPause("mac", store).paused)
        assertTrue(p.apply(Trigger.USER_CONNECT))
    }

    @Test fun byeReplacedIsTransientAndClearedByNetworkChange() {
        val p = DialPause("mac", store)
        p.onEnded(Outcome.Ended("bye replaced"))
        assertTrue(p.paused)
        assertNull(store.map["mac"])
        assertTrue(p.apply(Trigger.NETWORK_CHANGE))
    }

    @Test fun otherEndingsAreTransientAndClearedByAppOpenOnly() {
        val p = DialPause("mac", store)
        p.onEnded(Outcome.Ended("shutdown"))
        assertFalse(p.apply(Trigger.NETWORK_CHANGE))
        assertTrue(p.apply(Trigger.APP_OPEN))
        assertFalse(DialPause("mac", store).paused) // not persisted
    }

    @Test fun reconnectableEndingsDoNotPause() {
        val p = DialPause("mac", store)
        p.onEnded(Outcome.Lost("caps changed"))
        p.onEnded(Outcome.Lost("bye shutdown"))
        assertFalse(p.paused)
    }
}
