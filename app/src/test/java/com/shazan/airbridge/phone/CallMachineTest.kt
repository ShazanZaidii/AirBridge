package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.CallMachine.NotifKind
import com.shazan.airbridge.phone.CallMachine.Telephony
import com.shazan.airbridge.phone.NotifWire.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMachineTest {
    private var now = 1_000_000L
    private fun machine(telephony: Boolean = true) = CallMachine { now }.also { it.telephonyAuthoritative = telephony }

    @Test fun incomingAnsweredAndHungUp() {
        val m = machine()
        assertFalse(m.onTelephony(Telephony.IDLE))
        assertTrue(m.onTelephony(Telephony.RINGING))
        assertEquals(CallState.RINGING, m.current!!.state)
        assertEquals(listOf("answer", "decline"), m.current!!.can)
        assertNull(m.current!!.since)
        // The dialer's notification adds the caller.
        assertTrue(m.onCallNotification(NotifKind.INCOMING, "Asha", null))
        assertEquals("Asha", m.current!!.name)
        val id = m.current!!.id
        now += 4000
        assertTrue(m.onTelephony(Telephony.OFFHOOK))
        assertEquals(CallState.ACTIVE, m.current!!.state)
        assertEquals(id, m.current!!.id)
        assertEquals(now, m.current!!.since)
        assertEquals(listOf("end", "mute", "speaker", "route"), m.current!!.can)
        assertFalse(m.onTelephony(Telephony.OFFHOOK)) // repeated callback
        assertTrue(m.onTelephony(Telephony.IDLE))
        assertEquals(CallState.ENDED, m.current!!.state)
        assertTrue(m.current!!.can.isEmpty())
        m.clearEnded()
        assertNull(m.current)
    }

    @Test fun missedCallEnds() {
        val m = machine()
        m.onTelephony(Telephony.RINGING)
        assertTrue(m.onTelephony(Telephony.IDLE))
        assertEquals(CallState.ENDED, m.current!!.state)
    }

    @Test fun outgoingCallIsActiveAtOnce() {
        val m = machine()
        assertTrue(m.onTelephony(Telephony.OFFHOOK))
        assertEquals(CallState.ACTIVE, m.current!!.state)
        assertEquals(now, m.current!!.since)
    }

    @Test fun nextCallGetsANewIdAndNoOldName() {
        val m = machine()
        m.onTelephony(Telephony.RINGING); m.onCallNotification(NotifKind.INCOMING, "Asha", null)
        val first = m.current!!.id
        m.onTelephony(Telephony.IDLE); m.clearEnded()
        m.onTelephony(Telephony.RINGING)
        assertNotEquals(first, m.current!!.id)
        assertNull(m.current!!.name)
    }

    @Test fun withoutTelephonyTheNotificationDrivesTheState() {
        val m = machine(telephony = false)
        assertTrue(m.onCallNotification(NotifKind.INCOMING, "Ravi", "+91 98765 43210"))
        assertEquals(CallState.RINGING, m.current!!.state)
        assertEquals("Ravi", m.current!!.name); assertEquals("+91 98765 43210", m.current!!.number)
        assertTrue(m.onCallNotification(NotifKind.ONGOING, null, null))
        assertEquals(CallState.ACTIVE, m.current!!.state)
        assertEquals("Ravi", m.current!!.name) // kept
        assertFalse(m.onCallNotification(NotifKind.ONGOING, null, null))
        assertTrue(m.onCallNotification(null, null, null))
        assertEquals(CallState.ENDED, m.current!!.state)
    }

    @Test fun withTelephonyANotificationAloneStartsNothing() {
        val m = machine()
        assertFalse(m.onCallNotification(NotifKind.INCOMING, "Asha", null))
        assertNull(m.current)
        // …but its name is used once telephony reports the call.
        m.onTelephony(Telephony.RINGING)
        assertEquals("Asha", m.current!!.name)
        // Removal of the notification doesn't end a call telephony still has.
        assertFalse(m.onCallNotification(null, null, null))
        assertEquals(CallState.RINGING, m.current!!.state)
    }

    @Test fun muteAndSpeakerOnlyDuringACall() {
        val m = machine()
        assertFalse(m.setMuted(true))
        m.onTelephony(Telephony.OFFHOOK)
        assertTrue(m.setMuted(true)); assertTrue(m.current!!.muted)
        assertFalse(m.setMuted(true))
        assertTrue(m.setSpeaker(true)); assertTrue(m.current!!.speaker)
        m.onTelephony(Telephony.IDLE)
        assertFalse(m.setMuted(false)) // ended
    }

    @Test fun actionsOnlyForTheCurrentCallAndState() {
        val m = machine()
        assertFalse(m.accepts("c1", "answer"))
        m.onTelephony(Telephony.RINGING)
        val id = m.current!!.id
        assertTrue(m.accepts(id, "answer")); assertTrue(m.accepts(id, "decline")); assertTrue(m.accepts(id, "end"))
        assertFalse(m.accepts(id, "mute"))
        assertFalse(m.accepts("other", "answer")) // stale id
        m.onTelephony(Telephony.OFFHOOK)
        assertFalse(m.accepts(id, "answer")) // double click on Answer
        assertTrue(m.accepts(id, "end")); assertTrue(m.accepts(id, "mute")); assertTrue(m.accepts(id, "speaker_on"))
        m.onTelephony(Telephony.IDLE)
        assertFalse(m.accepts(id, "end"))
        assertFalse(m.accepts(id, "bogus"))
    }
}
