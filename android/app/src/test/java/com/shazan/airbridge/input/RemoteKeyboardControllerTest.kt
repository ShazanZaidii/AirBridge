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
    private val actions = mutableListOf<String>()
    private val timer = FakeTimer()
    private lateinit var c: RemoteKeyboardController
    private var seq = 0L

    @Before fun setUp() {
        sent.clear(); actions.clear()
        c = RemoteKeyboardController(
            out = { hid, down, mods, rep, text -> sent += Sent(hid, down, mods, rep, text) },
            clock = { timer.now },
            timer = timer,
            actionOut = { actions += it },
        )
    }

    private fun tapKey(hid: Int) { val h = c.press(hid); c.release(h) }
    private fun downOf(hid: Int) = sent.last { it.hid == hid && it.down }
    /** A fresh `key_state` message from the peer. */
    private fun report(on: Boolean) = c.onPeerCaps(on, ++seq)
    /** The same message seen again (recomposition). */
    private fun repeatLast(on: Boolean) = c.onPeerCaps(on, seq)

    // ---------------------------------------------------------------- caps lock

    @Test fun capsTapIsOptimisticAndSendsCapsHid() {
        c.capsLock()
        assertTrue(c.capsOn)
        assertEquals(
            listOf(Sent(Hid.CAPS_LOCK, true, Mods.CAPS, false, null), Sent(Hid.CAPS_LOCK, false, Mods.CAPS, false, null)),
            sent,
        )
    }

    @Test fun peerThatNeverReportsKeepsLocalToggle() {
        c.onPeerCaps(null, null)
        c.capsLock()
        timer.advance(5_000)
        c.onPeerCaps(null, null)
        assertTrue(c.capsOn)
        c.capsLock()
        timer.advance(5_000)
        assertFalse(c.capsOn)
    }

    @Test fun peerThatStoppedReportingKeepsLocalToggle() {
        report(false)
        timer.advance(1_000)
        c.capsLock()
        timer.advance(5_000)
        repeatLast(false) // only the old report, seen again on recomposition
        assertTrue(c.capsOn)
    }

    @Test fun staleDisagreeingReportThenConfirmationStaysLit() {
        report(false)
        timer.advance(1_000)
        val seen = mutableListOf<Boolean>()
        c.capsLock(); seen += c.capsOn
        timer.advance(150)
        report(false); seen += c.capsOn // stale read-back of the state before the tap
        timer.advance(50)
        report(true); seen += c.capsOn // the Mac confirms
        timer.advance(2_000); seen += c.capsOn
        repeatLast(true); seen += c.capsOn
        assertEquals(listOf(true, true, true, true, true), seen)
    }

    @Test fun confirmingReportClosesTheWindowSoACorrectionAppliesAtOnce() {
        report(false)
        timer.advance(1_000)
        c.capsLock()
        timer.advance(20)
        report(true) // confirmed
        timer.advance(200)
        report(false) // the Mac corrects itself: it really is off
        assertFalse(c.capsOn)
    }

    @Test fun peerThatEndsElsewhereIsAdoptedAfterTheWindow() {
        report(false)
        timer.advance(1_000)
        c.capsLock()
        timer.advance(100)
        report(false) // the toggle did not take
        assertTrue("held during the window", c.capsOn)
        timer.advance(RemoteKeyboardController.CAPS_GUARD_MS - 101)
        assertTrue(c.capsOn)
        timer.advance(1)
        assertFalse("adopted when the window ends", c.capsOn)
    }

    @Test fun disagreeingReportAfterTheWindowIsAdoptedImmediately() {
        report(false)
        timer.advance(1_000)
        c.capsLock()
        timer.advance(RemoteKeyboardController.CAPS_GUARD_MS + 50)
        assertTrue(c.capsOn) // no report during the window: local state stays
        report(false)
        assertFalse(c.capsOn)
    }

    @Test fun capsToggledOnTheComputerIsAdoptedAtOnce() {
        report(false)
        timer.advance(1_000)
        report(true) // physical Caps Lock pressed on the Mac
        assertTrue(c.capsOn)
        repeatLast(true) // same message again on recomposition: no-op
        assertTrue(c.capsOn)
        report(false)
        assertFalse(c.capsOn)
    }

    @Test fun rapidDoubleTapIgnoresTheFirstTapsLateReport() {
        report(false)
        timer.advance(1_000)
        c.capsLock()
        timer.advance(80)
        c.capsLock() // back off; the window restarts
        timer.advance(40)
        report(true) // late report for the first tap
        assertFalse(c.capsOn)
        timer.advance(30)
        report(false) // report for the second tap confirms
        assertFalse(c.capsOn)
        timer.advance(1_000)
        assertFalse(c.capsOn)
    }

    @Test fun repeatedSameReportDoesNotUndoALaterLocalTap() {
        report(false)
        timer.advance(1_000)
        c.capsLock()
        timer.advance(10)
        repeatLast(false) // unchanged message from recomposition: ignored
        timer.advance(2_000)
        assertTrue(c.capsOn)
    }

    // ---------------------------------------------------------------- F-row

    @Test fun fRowSendsMediaActionsByDefault() {
        assertFalse(c.fRowIsFKeys)
        assertNull(c.pressFunctionRow(7))
        assertNull(c.pressFunctionRow(11))
        assertEquals(listOf("media_play_pause", "vol_up"), actions)
        assertTrue(sent.isEmpty())
    }

    @Test fun fKeysModeSendsHidFKeys() {
        c.fKeysMode = true
        val h = c.pressFunctionRow(0)
        assertEquals(Hid.F1, h)
        c.release(h!!)
        assertEquals(listOf(Sent(Hid.F1, true, 0, false, null), Sent(Hid.F1, false, 0, false, null)), sent)
        assertTrue(actions.isEmpty())
    }

    @Test fun heldFnInvertsTheFRowWhileHeld() {
        c.modifierDown(ModKey.Fn)
        assertTrue(c.fRowIsFKeys)
        val h = c.pressFunctionRow(4)
        assertEquals(Hid.F1 + 4, h)
        c.release(h!!)
        assertTrue("still held", c.fRowIsFKeys)
        c.modifierUp(ModKey.Fn)
        assertFalse(c.fRowIsFKeys)
        c.fKeysMode = true
        c.modifierDown(ModKey.Fn)
        assertNull(c.pressFunctionRow(9))
        c.modifierUp(ModKey.Fn)
        assertEquals(listOf("vol_mute"), actions)
        assertTrue(sent.none { it.hid == 0 }) // fn has no HID usage
    }

    @Test fun lockKeySendsLockAction() {
        c.action("lock")
        assertEquals(listOf("lock"), actions)
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

    @Test fun heldShiftShowsUppercaseAndShiftedSymbols() {
        c.modifierDown(ModKey.Shift)
        assertTrue(c.upperLetters)
        assertEquals("Q", c.faceLabel(Hid.A + ('Q' - 'A')))
        assertEquals("!", c.faceLabel(Hid.ONE))
        assertEquals("?", c.faceLabel(Hid.SLASH))
        tapKey(Hid.A + ('Q' - 'A'))
        assertEquals("Q", sent.first { it.hid == Hid.A + ('Q' - 'A') && it.down }.text)
        tapKey(Hid.A + ('W' - 'A'))
        assertEquals("W", sent.first { it.hid == Hid.A + ('W' - 'A') && it.down }.text)
        c.modifierUp(ModKey.Shift)
        assertEquals("q", c.faceLabel(Hid.A + ('Q' - 'A')))
        assertEquals("1", c.faceLabel(Hid.ONE))
    }

    @Test fun shiftWithCapsGivesLowercase() {
        c.capsLock()
        c.modifierDown(ModKey.Shift)
        assertFalse(c.upperLetters)
        assertEquals("a", c.faceLabel(Hid.A))
        assertEquals("@", c.faceLabel(Hid.ONE + 1))
        sent.clear()
        tapKey(Hid.A)
        assertEquals(Sent(Hid.A, true, Mods.CAPS or Mods.SHIFT, false, "a"), downOf(Hid.A))
    }

    // ---------------------------------------------------------------- modifiers behave like hardware keys

    @Test fun tappingAModifierAloneIsJustDownThenUp() {
        c.modifierDown(ModKey.Gui)
        c.modifierUp(ModKey.Gui)
        assertEquals(listOf(Sent(Hid.LGUI, true, Mods.GUI, false, null), Sent(Hid.LGUI, false, 0, false, null)), sent)
        assertFalse(c.isDown(ModKey.Gui))
        sent.clear()
        tapKey(Hid.C) // nothing latched: a plain c
        assertEquals(listOf(Sent(Hid.C, true, 0, false, "c"), Sent(Hid.C, false, 0, false, null)), sent)
    }

    @Test fun commandHeldAcrossSeveralTabsIsTheAppSwitcher() {
        c.modifierDown(ModKey.Gui)
        tapKey(Hid.TAB); tapKey(Hid.TAB); tapKey(Hid.TAB)
        assertTrue("⌘ stays down between tabs", c.isDown(ModKey.Gui))
        assertEquals(1, sent.count { it.hid == Hid.LGUI && it.down })
        assertTrue(sent.none { it.hid == Hid.LGUI && !it.down })
        assertTrue(sent.filter { it.hid == Hid.TAB }.all { it.mods == Mods.GUI })
        c.modifierUp(ModKey.Gui) // lifting ⌘ picks the app
        assertEquals(Sent(Hid.LGUI, false, 0, false, null), sent.last())
    }

    @Test fun twoFingersOnBothShiftKeysReleaseOnlyWhenBothLift() {
        c.modifierDown(ModKey.Shift)
        c.modifierDown(ModKey.Shift)
        c.modifierUp(ModKey.Shift)
        assertTrue(c.isDown(ModKey.Shift))
        assertEquals(1, sent.size)
        c.modifierUp(ModKey.Shift)
        assertFalse(c.isDown(ModKey.Shift))
        assertEquals(Sent(Hid.LSHIFT, false, 0, false, null), sent.last())
        c.modifierUp(ModKey.Shift) // extra up: ignored
        assertEquals(2, sent.size)
    }

    @Test fun chordsCarryEveryHeldModifier() {
        c.modifierDown(ModKey.Ctrl)
        c.modifierDown(ModKey.Gui)
        tapKey(Hid.A + ('Q' - 'A'))
        assertEquals(Mods.CTRL or Mods.GUI, downOf(Hid.A + ('Q' - 'A')).mods)
    }

    @Test fun fnLayerMapsArrowsOnlyWhileHeld() {
        c.modifierDown(ModKey.Fn)
        tapKey(Hid.LEFT)
        assertEquals(Hid.HOME, sent.first().hid)
        c.modifierUp(ModKey.Fn)
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

    @Test fun releaseAllLiftsHeldKeysAndModifiersButKeepsCaps() {
        c.capsLock()
        c.modifierDown(ModKey.Ctrl)
        c.modifierDown(ModKey.Gui)
        c.press(Hid.X)
        sent.clear()
        c.releaseAll()
        assertTrue(sent.any { it.hid == Hid.X && !it.down })
        assertTrue(sent.any { it.hid == Hid.LGUI && !it.down })
        assertTrue(sent.any { it.hid == Hid.LCTRL && !it.down })
        assertTrue(sent.none { it.down })
        for (m in ModKey.entries) assertFalse(c.isDown(m))
        assertTrue(c.capsOn)
        val n = sent.size
        c.modifierUp(ModKey.Gui) // the finger lifting later changes nothing
        assertEquals(n, sent.size)
        timer.advance(1_000)
        assertNull(sent.firstOrNull { it.rep })
    }
}
