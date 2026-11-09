package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.CallMachine.NotifKind
import com.shazan.airbridge.phone.CallMachine.Telephony
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §13.4: caller label, photo, presence, beacon and the ring decision message. */
class CallExperienceTest {

    // ---- wire ----

    @Test fun callStateCarriesLabelOnlyWhenKnown() {
        val c = NotifWire.Call("c1", NotifWire.CallState.RINGING, "Asha", "+91 98765 43210", null, can = listOf("answer", "decline"), label = "Mobile")
        val m = NotifWire.call(c)
        assertEquals("Mobile", m["label"])
        assertFalse(NotifWire.call(c.copy(label = null)).containsKey("label"))
        assertFalse(NotifWire.call(c.copy(label = "  ")).containsKey("label"))
        assertEquals(NotifWire.MAX_LABEL, (NotifWire.call(c.copy(label = "x".repeat(99)))["label"] as String).length)
    }

    @Test fun photoMessageAndLimit() {
        assertEquals(mapOf("t" to "call_photo", "id" to "c1", "jpg" to "AAAA"), NotifWire.callPhoto("c1", "AAAA"))
        assertNull(NotifWire.callPhoto("c1", "A".repeat(NotifWire.MAX_PHOTO_B64 + 1)))
    }

    @Test fun presenceShape() {
        val m = NotifWire.presence(NotifWire.Presence(screenOn = true, locked = false, idleMs = 2500, touchKnown = true))
        assertEquals(mapOf("t" to "call_presence", "screen" to true, "locked" to false, "touch" to true, "idle_ms" to 2500L), m)
        assertFalse(NotifWire.presence(NotifWire.Presence(false, true, null, false)).containsKey("idle_ms"))
    }

    @Test fun beaconShape() {
        val on = NotifWire.beacon("a2V5", BeaconId.SERVICE_UUID, 900)
        assertEquals(true, on["on"]); assertEquals("a2V5", on["key"]); assertEquals(900, on["period"])
        assertEquals(mapOf("t" to "call_beacon", "on" to false), NotifWire.beacon(null, BeaconId.SERVICE_UUID, 900))
    }

    @Test fun parsesRingDecision() {
        assertEquals(NotifWire.Command.Ring("c1", true), NotifWire.parse(mapOf("t" to "call_ring", "id" to "c1", "phone" to "silent")))
        assertEquals(NotifWire.Command.Ring("c1", false), NotifWire.parse(mapOf("t" to "call_ring", "id" to "c1", "phone" to "ring")))
        assertNull(NotifWire.parse(mapOf("t" to "call_ring", "id" to "c1", "phone" to "loud")))
        assertNull(NotifWire.parse(mapOf("t" to "call_ring", "phone" to "silent")))
        assertNull(NotifWire.parse(mapOf("t" to "call_ring", "id" to "c".repeat(100), "phone" to "silent")))
    }

    // ---- machine ----

    @Test fun labelFollowsTheCaller() {
        val m = CallMachine { 1000L }
        m.telephonyAuthoritative = true
        m.onTelephony(Telephony.RINGING)
        assertTrue(m.onCallNotification(NotifKind.INCOMING, "Asha", "+91 98765 43210", "Mobile"))
        assertEquals("Mobile", m.current?.label)
        assertFalse(m.onCallNotification(NotifKind.INCOMING, "Asha", "+91 98765 43210", "Mobile"))
        m.onTelephony(Telephony.IDLE); m.clearEnded()
        m.onTelephony(Telephony.RINGING)
        assertNull("a new call doesn't inherit the last label", m.current?.label)
    }

    @Test fun muteAndSpeakerChangesAreReportedOnce() {
        val m = CallMachine { 1000L }
        m.onTelephony(Telephony.OFFHOOK)
        assertTrue(m.setMuted(true)); assertFalse(m.setMuted(true))
        assertTrue(m.setSpeaker(true)); assertFalse(m.setSpeaker(true))
        assertTrue(m.current!!.muted && m.current!!.speaker)
        assertTrue(m.setMuted(false))
        assertEquals(false, NotifWire.call(m.current!!)["muted"])
        assertEquals(true, NotifWire.call(m.current!!)["speaker"])
    }

    // ---- presence ----

    private class Clock(var t: Long = 100_000) { fun now() = t }

    @Test fun screenOnIsNotInteraction() {
        val c = Clock(); val p = PresenceTracker(c::now)
        p.onScreen(true)
        assertNull(p.snapshot(locked = false, touchKnown = false).idleMs)
        p.onUnlock(); c.t += 3_000
        assertEquals(3_000L, p.snapshot(false, false).idleMs)
        p.onTouch(); c.t += 500
        assertEquals(500L, p.snapshot(false, true).idleMs)
    }

    @Test fun unlockRightAfterARingWokeTheScreenIsIgnored() {
        val c = Clock(); val p = PresenceTracker(c::now)
        p.onScreen(false)
        p.onRingStart()
        c.t += 800; p.onScreen(true); p.onUnlock() // phones without a lock screen: "user present" on wake
        assertNull(p.snapshot(false, false).idleMs)
        c.t += 5_000; p.onUnlock() // a real unlock later counts
        assertEquals(0L, p.snapshot(false, false).idleMs)
    }

    @Test fun theRingIsNotedWhenItArrivesNotWhenTheCallIsSent() {
        val c = Clock(); val p = PresenceTracker(c::now)
        p.onScreen(false)
        p.onRingStart() // telephony: ringing, screen still dark
        c.t += 300; p.onScreen(true); p.onUnlock() // the call wakes a phone without a lock screen
        c.t += 500; p.onRingStart() // the call goes out to the computer: must not re-note a lit screen
        assertNull(p.snapshot(false, false).idleMs)
        p.onRingEnd(); p.onScreen(true); p.onRingStart(); c.t += 200; p.onUnlock() // next call on a lit screen
        assertEquals(0L, p.snapshot(false, false).idleMs)
    }

    @Test fun unlockDuringARingOnALitScreenCounts() {
        val c = Clock(); val p = PresenceTracker(c::now)
        p.onScreen(true); p.onRingStart(); c.t += 500; p.onUnlock()
        assertEquals(0L, p.snapshot(false, false).idleMs)
    }

    @Test fun sendingIsThrottledForTapsButNotForStateChanges() {
        val c = Clock(); val p = PresenceTracker(c::now)
        p.onScreen(true); p.onTouch()
        val first = p.snapshot(false, true)
        assertTrue(p.shouldSend(first)); p.markSent(first)
        c.t += 1_000; p.onTouch()
        assertFalse("tap 1 s later: wait", p.shouldSend(p.snapshot(false, true)))
        c.t += 3_500; p.onTouch()
        assertTrue("tap 4.5 s later: send", p.shouldSend(p.snapshot(false, true))); p.markSent(p.snapshot(false, true))
        c.t += 6_000
        assertFalse("no new tap: nothing to say", p.shouldSend(p.snapshot(false, true)))
        p.onScreen(false)
        assertTrue("screen off always", p.shouldSend(p.snapshot(false, true)))
        assertTrue(p.shouldSend(p.snapshot(false, true), force = true))
    }

    // ---- beacon id ----

    @Test fun beaconIdVector() {
        val key = ByteArray(16) { it.toByte() }
        // Shared with the Mac and Windows checks (docs/test-vectors/call-experience.jsonl).
        assertEquals(1_957_867L, BeaconId.epoch(1_762_080_300_000L))
        val id = BeaconId.derive(key, 1_957_867L)
        assertEquals(BeaconId.LEN, id.size)
        assertEquals(VECTOR_ID, id.joinToString("") { "%02x".format(it) })
        assertFalse(id.contentEquals(BeaconId.derive(key, 1_957_868L)))
        assertArrayEquals(id, BeaconId.derive(key.copyOf(), 1_957_867L))
    }

    @Test fun beaconRotation() {
        assertEquals(900_000L, BeaconId.msToNext(0))
        assertEquals(1L, BeaconId.msToNext(899_999))
        assertEquals(BeaconId.epoch(899_999) + 1, BeaconId.epoch(900_000))
    }

    // ---- shared vectors (docs/test-vectors/call-experience.jsonl, also checked on the Mac and Windows) ----

    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> json(k.toString()) + ":" + json(x) }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> error("unexpected ${v::class}")
    }

    @Test fun matchesSharedVectors() {
        val f = listOf(java.io.File("../docs/test-vectors/call-experience.jsonl"), java.io.File("docs/test-vectors/call-experience.jsonl")).first { it.exists() }
        val lines = f.readLines().filter { it.isNotBlank() }
        val ringing = NotifWire.Call("c4-mxk2b0", NotifWire.CallState.RINGING, "Asha Kapoor", "+91 98765 43210", null,
            can = listOf("answer", "decline"), label = "Mobile")
        val active = ringing.copy(state = NotifWire.CallState.ACTIVE, since = 1_762_070_412_000, muted = true, speaker = true,
            can = listOf("end", "mute", "speaker"))
        val photoB64 = Regex("\"jpg\":\"([^\"]+)\"").find(lines[2])!!.groupValues[1]
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val built = listOf(
            NotifWire.call(ringing), NotifWire.call(active), NotifWire.callPhoto("c4-mxk2b0", photoB64)!!,
            NotifWire.presence(NotifWire.Presence(true, false, 2500, true)),
            NotifWire.beacon(key, BeaconId.SERVICE_UUID, BeaconId.PERIOD_S), NotifWire.beacon(null, BeaconId.SERVICE_UUID, BeaconId.PERIOD_S),
        ).map(::json)
        built.forEachIndexed { i, b -> assertEquals(lines[i], b) }
        assertEquals(NotifWire.Command.Ring("c4-mxk2b0", true), NotifWire.parse(mapOf("t" to "call_ring", "id" to "c4-mxk2b0", "phone" to "silent")))
        assertTrue(lines[6].contains("\"phone\":\"silent\""))
        assertTrue(lines[7].contains("\"id\":\"$VECTOR_ID\"") && lines[7].contains("\"key\":\"$key\""))
    }

    companion object { const val VECTOR_ID = "48dfa4903066889a" }
}
