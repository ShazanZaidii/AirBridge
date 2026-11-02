package com.shazan.airbridge.input

import com.shazan.airbridge.input.GestureIntent.Click
import com.shazan.airbridge.input.GestureIntent.DragEnd
import com.shazan.airbridge.input.GestureIntent.DragStart
import com.shazan.airbridge.input.GestureIntent.Move
import com.shazan.airbridge.input.GestureIntent.ResetAll
import com.shazan.airbridge.input.GestureIntent.Scroll
import com.shazan.airbridge.input.GestureIntent.ThreeFingerSwipe
import com.shazan.airbridge.input.TouchAction.CANCEL
import com.shazan.airbridge.input.TouchAction.DOWN
import com.shazan.airbridge.input.TouchAction.MOVE
import com.shazan.airbridge.input.TouchAction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackpadGestureMachineTest {

    private val out = mutableListOf<GestureIntent>()
    private lateinit var m: TrackpadGestureMachine
    private val cfg = TrackpadGestureMachine.Config(slopPx = 16f, swipePx = 160f)

    @Before fun setUp() {
        out.clear()
        m = TrackpadGestureMachine(cfg) { out += it }
    }

    private fun ev(id: Long, a: TouchAction, x: Float, y: Float, t: Long) = m.onTouch(id, a, x, y, t)
    private fun clicks() = out.filterIsInstance<Click>()
    private fun tap(id: Long, t: Long, x: Float = 100f, y: Float = 100f, dur: Long = 60) {
        ev(id, DOWN, x, y, t); ev(id, UP, x, y, t + dur)
    }

    @Test fun singleTapClicksOnce() {
        tap(1, 1000)
        assertEquals(listOf(Click(0, 1)), out)
    }

    @Test fun singleTapWithJitterUnderSlopStillClicks() {
        ev(1, DOWN, 100f, 100f, 0); ev(1, MOVE, 104f, 103f, 30); ev(1, UP, 105f, 104f, 90)
        assertEquals(listOf(Click(0, 1)), out)
    }

    @Test fun slowPressIsNotATap() {
        ev(1, DOWN, 100f, 100f, 0); m.tick(150); ev(1, UP, 100f, 100f, 250)
        assertTrue(out.isEmpty())
    }

    @Test fun doubleTapSendsN1ThenN2() {
        tap(1, 1000)
        tap(2, 1200)
        assertEquals(listOf(Click(0, 1), Click(0, 2)), out)
    }

    @Test fun tapsFarApartInTimeAreSeparateSingles() {
        tap(1, 1000)
        tap(2, 1600)
        assertEquals(listOf(Click(0, 1), Click(0, 1)), out)
    }

    @Test fun shortDragNeverClicks() {
        ev(1, DOWN, 100f, 100f, 0)
        ev(1, MOVE, 110f, 100f, 16)
        ev(1, MOVE, 130f, 100f, 32)
        ev(1, UP, 130f, 100f, 60) // well within the tap time
        assertTrue(clicks().isEmpty())
        val moved = out.filterIsInstance<Move>().sumOf { it.dx.toDouble() }
        assertEquals(30.0, moved, 0.01)
    }

    @Test fun twoFingerTapIsRightClick() {
        ev(1, DOWN, 100f, 100f, 0)
        ev(2, DOWN, 200f, 100f, 20)
        ev(1, UP, 100f, 100f, 110)
        ev(2, UP, 200f, 100f, 130)
        assertEquals(listOf(Click(1, 1)), out)
    }

    @Test fun twoFingerTapNeverProducesALeftClickAsWell() {
        // Root cause of "single click counted as a 2/3-finger click" and vice versa: the first finger
        // must not be classified on its own when a second one joins the same sequence.
        ev(1, DOWN, 100f, 100f, 0)
        ev(2, DOWN, 200f, 100f, 40)
        ev(2, UP, 200f, 100f, 90)
        ev(1, UP, 100f, 100f, 100)
        assertEquals(listOf(Click(1, 1)), out)
    }

    @Test fun twoFingersLiftedFarApartIsNotARightClick() {
        ev(1, DOWN, 100f, 100f, 0)
        ev(2, DOWN, 200f, 100f, 10)
        ev(1, UP, 100f, 100f, 60)
        ev(2, UP, 200f, 100f, 380) // 320 ms spread > 250
        assertTrue(out.isEmpty())
    }

    @Test fun twoFingerScrollThenOneLiftsDoesNotClick() {
        ev(1, DOWN, 100f, 300f, 0)
        ev(2, DOWN, 200f, 300f, 10)
        for (i in 1..10) {
            ev(1, MOVE, 100f, 300f - i * 10, 10L + i * 16)
            ev(2, MOVE, 200f, 300f - i * 10, 10L + i * 16)
        }
        val scrolledBeforeLift = out.filterIsInstance<Scroll>().sumOf { it.dy.toDouble() }
        assertEquals(100.0, scrolledBeforeLift, 0.01) // fingers up 100 px, natural → content up (+)
        ev(1, UP, 100f, 200f, 200)
        // The remaining finger moving or lifting must not click, move or scroll.
        val before = out.size
        ev(2, MOVE, 260f, 200f, 220)
        ev(2, UP, 260f, 200f, 240)
        assertEquals(before, out.size)
        assertTrue(clicks().isEmpty())
        assertTrue(out.filterIsInstance<Move>().isEmpty())
    }

    @Test fun traditionalScrollInvertsDirection() {
        m.naturalScroll = false
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 0)
        ev(1, MOVE, 100f, 260f, 16); ev(2, MOVE, 200f, 260f, 16)
        assertEquals(-40.0, out.filterIsInstance<Scroll>().sumOf { it.dy.toDouble() }, 0.01)
    }

    @Test fun tapRightAfterMultiFingerGestureIsIgnored() {
        ev(1, DOWN, 100f, 100f, 0); ev(2, DOWN, 200f, 100f, 0)
        ev(1, MOVE, 100f, 60f, 16); ev(2, MOVE, 200f, 60f, 16)
        ev(1, UP, 100f, 60f, 100); ev(2, UP, 200f, 60f, 110)
        out.clear()
        tap(3, 150) // 40 ms after the scroll ended
        assertTrue(out.isEmpty())
        tap(4, 600)
        assertEquals(listOf(Click(0, 1)), out)
    }

    @Test fun threeFingerSwipeFiresExactlyOnce() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 5); ev(3, DOWN, 300f, 300f, 10)
        for (i in 1..30) {
            val t = 10L + i * 16
            for (id in 1L..3L) ev(id, MOVE, id * 100f + i * 15f, 300f, t)
        }
        for (id in 1L..3L) ev(id, UP, id * 100f + 450f, 300f, 600)
        assertEquals(listOf(ThreeFingerSwipe(SwipeDir.RIGHT)), out)
    }

    @Test fun threeFingerSwipeUp() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 0); ev(3, DOWN, 300f, 300f, 0)
        for (i in 1..12) for (id in 1L..3L) ev(id, MOVE, id * 100f, 300f - i * 20f, i * 16L)
        assertEquals(listOf(ThreeFingerSwipe(SwipeDir.UP)), out)
    }

    @Test fun threeFingerTapDoesNothing() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 5); ev(3, DOWN, 300f, 300f, 10)
        for (id in 1L..3L) ev(id, UP, id * 100f, 300f, 80)
        assertTrue(out.isEmpty())
    }

    @Test fun longPressDragStartsAndEnds() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(300)
        assertTrue(out.isEmpty())
        m.tick(500)
        assertEquals(listOf(DragStart), out)
        ev(1, MOVE, 150f, 120f, 600)
        ev(1, MOVE, 180f, 120f, 616)
        ev(1, UP, 180f, 120f, 700)
        assertEquals(listOf(DragStart, Move(50f, 20f), Move(30f, 0f), DragEnd), out)
        assertTrue(m.isIdle)
    }

    @Test fun movingBeforeLongPressIsAMoveNotADrag() {
        ev(1, DOWN, 100f, 100f, 0)
        ev(1, MOVE, 140f, 100f, 100)
        m.tick(600)
        assertTrue(out.none { it == DragStart })
    }

    @Test fun cancelMidDragReleasesButton() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(520)
        ev(1, MOVE, 150f, 100f, 600)
        ev(1, CANCEL, 150f, 100f, 650)
        assertEquals(listOf(DragStart, Move(50f, 0f), DragEnd, ResetAll), out)
        assertTrue(m.isIdle)
        // A late UP for the cancelled pointer is ignored (no click).
        ev(1, UP, 150f, 100f, 700)
        assertEquals(4, out.size)
    }

    @Test fun externalResetMidDragReleasesButton() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(520)
        m.reset(800)
        assertEquals(listOf(DragStart, DragEnd, ResetAll), out)
    }

    @Test fun watchdogResetsStuckPointer() {
        ev(1, DOWN, 100f, 100f, 0)
        ev(1, MOVE, 140f, 100f, 50) // now Moving
        m.tick(1000)
        assertEquals(1, out.size)
        m.tick(1550) // 1.5 s without events
        assertEquals(ResetAll, out.last())
        assertTrue(m.isIdle)
        // The finger is still physically there and moves again: the pad keeps working (adopted).
        ev(1, MOVE, 150f, 100f, 2000)
        ev(1, MOVE, 170f, 100f, 2016)
        assertEquals(Move(20f, 0f), out.last())
        ev(1, UP, 170f, 100f, 2030)
        assertTrue(clicks().isEmpty())
    }

    @Test fun watchdogReleasesAStuckDrag() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(500)
        m.tick(2000) // holding still mid-drag is allowed for a while
        assertEquals(listOf(DragStart), out)
        m.tick(500 + cfg.dragWatchdogMs)
        assertEquals(listOf(DragStart, DragEnd, ResetAll), out)
    }

    @Test fun nextDeadlineTracksLongPressThenWatchdog() {
        assertEquals(null, m.nextDeadline())
        ev(1, DOWN, 0f, 0f, 1000)
        assertEquals(1500L, m.nextDeadline())
        ev(1, MOVE, 40f, 0f, 1100)
        assertEquals(2600L, m.nextDeadline())
    }

    @Test fun secondFingerDuringDragIsIgnored() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(500)
        ev(2, DOWN, 300f, 100f, 600)
        ev(2, MOVE, 340f, 100f, 620)
        ev(2, UP, 340f, 100f, 650)
        ev(1, UP, 100f, 100f, 700)
        assertEquals(listOf(DragStart, DragEnd), out)
    }
}
