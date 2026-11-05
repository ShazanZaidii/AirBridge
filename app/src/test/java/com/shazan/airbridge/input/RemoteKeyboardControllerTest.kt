package com.shazan.airbridge.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteKeyboardControllerTest {

    data class Sent(val hid: Int, val down: Boolean, val mods: Int, val rep: Boolean, val text: String?)

    /** Manual clock + timer: [advance] runs whatever came due. */
    private class FakeTimer : KeyTimer {
        var now = 1_000L
        private val queue = mutableListOf<Pair<Long, Runnable>>()
        override fun post(delayMs: Long, r: Runnable) { queue += (now + delayMs) to r }
        override fun cancel(r: Runnable) { queue.removeAll { it.second === r } }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = queue.filter { it.first <= until }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = next.first
                next.second.run()
            }
            now = until
        }
    }

    private val sent = mutableListOf<Sent>()
    private val timer = FakeTimer()
    private lateinit var c: RemoteKeyboardController
    private val toggles = mutableListOf<Boolean>()

    @Before fun setUp() {
        sent.clear(); toggles.clear()
        c = RemoteKeyboardController(
            out = { hid, down, mods, rep, text -> sent += Sent(hid, down, mods, rep, text) },
            clock = { timer.now },
            timer = timer,
        )
        c.onCapsToggled = { toggles += it }
    }

    private fun tapKey(hid: Int) { val h = c.press(hid); c.release(h) }
    private fun downOf(hid: Int) = sent.last { it.hid == hid && it.down }

    // ---------------------------------------------------------------- caps lock

    @Test fun capsTapIsOptimisticAndSendsCapsHid() {
        c.capsLock()
        assertTrue(c.capsOn)
        assertEquals(listOf(true), toggles)
        assertEquals(
            listOf(Sent(Hid.CAPS_LOCK, true, Mods.CAPS, false, null), Sent(Hid.CAPS_LOCK, false, Mods.CAPS, false, null)),
            sent,
        )
    }

    @Test fun peerThatNeverReportsKeepsLocalToggle() {
        c.onPeerCaps(null)
        c.capsLock()
        timer.advance(5_000)
        c.onPeerCaps(null)
        assertTrue(c.capsOn)
        c.capsLock()
        timer.advance(5_000)
        assertFalse(c.capsOn)
    }

    @Test fun matchingPeerReportAfterOptimisticChangeDoesNotFlicker() {
        c.onPeerCaps(false)
        val seen = mutableListOf<Boolean>()
        c.capsLock(); seen += c.capsOn
        // The router mirrors the optimistic value, then the Mac confirms it.
        c.onPeerCaps(true); seen += c.capsOn
        timer.advance(50)
        c.onPeerCaps(true); seen += c.capsOn
        timer.advance(1_000); seen += c.capsOn
        assertEquals(listOf(true, true, true, true), seen)
    }

    @Test fun differingPeerReportIsAdoptedAfterSettleWindow() {
        c.onPeerCaps(false)
        c.capsLock()
        c.onPeerCaps(true) // router mirrors optimistic value
        timer.advance(60)
        c.onPeerCaps(false) // the Mac says the toggle did not take
        assertTrue("held until the settle window ends", c.capsOn)
        timer.advance(RemoteKeyboardController.CAPS_SETTLE_MS)
        assertFalse(c.capsOn)
    }

    @Test fun staleReportDuringRapidDoubleTapDoesNotFlicker() {
        c.onPeerCaps(false)
        c.capsLock(); c.onPeerCaps(true)
        timer.advance(80)
        c.capsLock(); c.onPeerCaps(false)
        assertFalse(c.capsOn)
        timer.advance(40)
        c.onPeerCaps(true) // report for the first tap arrives late
        assertFalse(c.capsOn)
        timer.advance(30)
        c.onPeerCaps(false) // report for the second tap
        assertFalse(c.capsOn)
        timer.advance(1_000)
        assertFalse(c.capsOn)
    }

    @Test fun unsolicitedPeerReportIsAdoptedImmediately() {
        c.onPeerCaps(false)
        timer.advance(1_000)
        c.onPeerCaps(true) // physical Caps Lock pressed on the Mac
        assertTrue(c.capsOn)
        c.onPeerCaps(true) // same value again on recomposition: no-op
        assertTrue(c.capsOn)
        c.onPeerCaps(false)
        assertFalse(c.capsOn)
    }

    @Test fun repeatedSameReportDoesNotUndoALaterLocalTap() {
        c.onPeerCaps(false)
        timer.advance(1_000)
        c.capsLock() // no router mirroring: peer value stays false
        timer.advance(10)
        c.onPeerCaps(false) // unchanged value from recomposition: ignored
        assertTrue(c.capsOn)
    }

    // ---------------------------------------------------------------- letters, shift xor caps

    @Test fun lettersAreLowercaseByDefault() {
        assertEquals("a", c.faceLabel(Hid.A))
        assertEquals("1", c.faceLabel(Hid.ONE))
        tapKey(Hid.A)
        assertEquals(Sent(Hid.A, true, 0, false, "a"), downOf(Hid.A))
    }

    @Test fun capsMakesLettersUppercaseAndSendsCapsBit() {
        c.capsLock(); sent.clear()
        assertEquals("A", c.faceLabel(Hid.A))
        assertEquals("1", c.faceLabel(Hid.ONE)) // caps does not shift symbols
        tapKey(Hid.A)
        assertEquals(Sent(Hid.A, true, Mods.CAPS, false, "A"), downOf(Hid.A))
        tapKey(Hid.ONE)
        assertEquals("1", downOf(Hid.ONE).text)
    }

    @Test fun shiftShowsUppercaseAndShiftedSymbols() {
        c.tapModifier(ModKey.Shift)
        assertTrue(c.upperLetters)
        assertEquals("Q", c.faceLabel(Hid.A + ('Q' - 'A')))
        assertEquals("!", c.faceLabel(Hid.ONE))
        assertEquals("?", c.faceLabel(Hid.SLASH))
        tapKey(Hid.A + ('Q' - 'A'))
        assertEquals("Q", sent.first { it.hid == Hid.A + ('Q' - 'A') && it.down }.text)
        // one-shot: back to lower case after the key
        assertEquals(ModState.Off, c.state(ModKey.Shift))
        assertEquals("q", c.faceLabel(Hid.A + ('Q' - 'A')))
        assertEquals("1", c.faceLabel(Hid.ONE))
    }

    @Test fun shiftWithCapsGivesLowercase() {
        c.capsLock()
        c.tapModifier(ModKey.Shift)
        assertFalse(c.upperLetters)
        assertEquals("a", c.faceLabel(Hid.A))
        assertEquals("@", c.faceLabel(Hid.ONE + 1))
        sent.clear()
        tapKey(Hid.A)
        assertEquals(Sent(Hid.A, true, Mods.CAPS or Mods.SHIFT, false, "a"), downOf(Hid.A))
    }

    // ---------------------------------------------------------------- sticky modifiers

    @Test fun oneShotWrapsNextKeyThenClears() {
        c.tapModifier(ModKey.Gui)
        assertEquals(ModState.OneShot, c.state(ModKey.Gui))
        assertTrue(sent.isEmpty()) // nothing held on the peer until a key is pressed
        timer.advance(1_000)
        tapKey(Hid.C)
        assertEquals(
            listOf(
                Sent(Hid.LGUI, true, Mods.GUI, false, null),
                Sent(Hid.C, true, Mods.GUI, false, null),
                Sent(Hid.C, false, Mods.GUI, false, null),
                Sent(Hid.LGUI, false, 0, false, null),
            ),
            sent,
        )
        assertEquals(ModState.Off, c.state(ModKey.Gui))
    }

    @Test fun doubleTapLocksAndThirdTapUnlocks() {
        c.tapModifier(ModKey.Shift)
        timer.advance(100)
        c.tapModifier(ModKey.Shift)
        assertEquals(ModState.Locked, c.state(ModKey.Shift))
        assertEquals(Sent(Hid.LSHIFT, true, Mods.SHIFT, false, null), sent.single())
        tapKey(Hid.A); tapKey(Hid.A + 1)
        assertEquals(ModState.Locked, c.state(ModKey.Shift))
        assertEquals("B", downOf(Hid.A + 1).text)
        c.tapModifier(ModKey.Shift)
        assertEquals(ModState.Off, c.state(ModKey.Shift))
        assertEquals(Sent(Hid.LSHIFT, false, 0, false, null), sent.last())
    }

    @Test fun slowSecondTapTurnsOneShotOff() {
        c.tapModifier(ModKey.Alt)
        timer.advance(RemoteKeyboardController.LOCK_WINDOW_MS + 50)
        c.tapModifier(ModKey.Alt)
        assertEquals(ModState.Off, c.state(ModKey.Alt))
        assertTrue(sent.isEmpty())
    }

    @Test fun fnLayerMapsArrowsAndClears() {
        c.tapModifier(ModKey.Fn)
        tapKey(Hid.LEFT)
        assertEquals(Hid.HOME, sent.first().hid)
        assertEquals(ModState.Off, c.state(ModKey.Fn))
        tapKey(Hid.LEFT)
        assertEquals(Hid.LEFT, sent.last().hid)
    }

    @Test fun holdRepeatsAndReleaseStops() {
        val h = c.press(Hid.A)
        timer.advance(RemoteKeyboardController.REPEAT_DELAY_MS + RemoteKeyboardController.REPEAT_EVERY_MS * 2)
        assertEquals(3, sent.count { it.hid == Hid.A && it.down && it.rep })
        c.release(h)
        val n = sent.size
        timer.advance(1_000)
        assertEquals(n, sent.size)
    }

    @Test fun releaseAllLiftsHeldKeysAndLockedModifiersButKeepsCaps() {
        c.capsLock()
        c.tapModifier(ModKey.Ctrl); timer.advance(50); c.tapModifier(ModKey.Ctrl) // locked
        c.tapModifier(ModKey.Gui) // one-shot
        c.press(Hid.X)
        sent.clear()
        c.releaseAll()
        assertTrue(sent.any { it.hid == Hid.X && !it.down })
        assertTrue(sent.any { it.hid == Hid.LGUI && !it.down })
        assertTrue(sent.any { it.hid == Hid.LCTRL && !it.down })
        assertTrue(sent.none { it.down })
        for (m in ModKey.entries) assertEquals(ModState.Off, c.state(m))
        assertTrue(c.capsOn)
        timer.advance(1_000)
        assertNull(sent.firstOrNull { it.rep })
    }
}
