package com.shazan.airbridge.input

import com.shazan.airbridge.input.GestureIntent.Click
import com.shazan.airbridge.input.GestureIntent.DragEnd
import com.shazan.airbridge.input.GestureIntent.DragStart
import com.shazan.airbridge.input.GestureIntent.FirmPress
import com.shazan.airbridge.input.GestureIntent.Move
import com.shazan.airbridge.input.GestureIntent.Zoom
import com.shazan.airbridge.input.GestureIntent.Pinch
import com.shazan.airbridge.input.GestureIntent.ResetAll
import com.shazan.airbridge.input.GestureIntent.Scroll
import com.shazan.airbridge.input.GestureIntent.Swipe
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

    private fun ev(id: Long, a: TouchAction, x: Float, y: Float, t: Long, p: Float = Float.NaN) = m.onTouch(id, a, x, y, t, p)
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

    @Test fun threeFingersDoNothingAtAll() {
        // Swipe, tap and pinch with three fingers: Android skins own those, so the pad ignores them.
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 5); ev(3, DOWN, 300f, 300f, 10)
        for (i in 1..30) for (id in 1L..3L) ev(id, MOVE, id * 100f + i * 15f, 300f, 10L + i * 16)
        for (id in 1L..3L) ev(id, UP, id * 100f + 450f, 300f, 600)
        ev(1, DOWN, 100f, 300f, 800); ev(2, DOWN, 200f, 300f, 805); ev(3, DOWN, 300f, 300f, 810)
        for (id in 1L..3L) ev(id, UP, id * 100f, 300f, 880)
        assertTrue("got $out", out.isEmpty())
    }

    @Test fun firmPressIsAForceClickInsteadOfATap() {
        m.firmPressAt = 0.6f
        ev(1, DOWN, 100f, 100f, 0, p = 0.3f)
        ev(1, MOVE, 100f, 100f, 40, p = 0.45f)
        ev(1, MOVE, 101f, 100f, 80, p = 0.7f) // hard already, but too soon: could still become a tap or drag
        assertTrue(out.isEmpty())
        ev(1, MOVE, 101f, 100f, 150, p = 0.75f)
        ev(1, MOVE, 101f, 100f, 170, p = 0.8f) // once only
        ev(1, UP, 101f, 100f, 200, p = 0.2f)
        m.tick(2_000)
        assertEquals(listOf(FirmPress), out)
        assertTrue(m.lastWasFirm)
    }

    @Test fun hardLandingIsStillAClickAndADragStillDrags() {
        m.firmPressAt = 0.6f
        ev(1, DOWN, 100f, 100f, 0, p = 0.95f) // lands hard, pressure never rises after that
        ev(1, MOVE, 100f, 100f, 60, p = 0.95f)
        ev(1, UP, 100f, 100f, 100, p = 0.5f)
        assertEquals(listOf(Click(0, 1)), out)
        out.clear()
        ev(2, DOWN, 100f, 100f, 1000, p = 0.95f)
        ev(2, MOVE, 100f, 100f, 1200, p = 0.95f)
        m.tick(1500)
        assertEquals(listOf(DragStart), out)
    }

    @Test fun lightTapStaysATapAndRecordsItsPeak() {
        m.firmPressAt = 0.6f
        ev(1, DOWN, 100f, 100f, 0, p = 0.3f)
        ev(1, MOVE, 100f, 100f, 30, p = 0.4f)
        ev(1, UP, 100f, 100f, 60, p = 0.1f)
        assertEquals(listOf(Click(0, 1)), out)
        assertEquals(0.4f, m.peakPressure, 0.0001f)
        assertEquals(1, m.lastTouchFingers)
    }

    @Test fun firmPressWhileMovingIsJustAMove() {
        m.firmPressAt = 0.6f
        ev(1, DOWN, 100f, 100f, 0, p = 0.3f)
        ev(1, MOVE, 140f, 100f, 30, p = 0.3f)
        ev(1, MOVE, 160f, 100f, 60, p = 0.9f)
        assertTrue(out.none { it == FirmPress })
    }

    @Test fun twoFingerSpreadZoomsInStepByStep() {
        ev(1, DOWN, 300f, 400f, 0); ev(2, DOWN, 500f, 400f, 10) // 200 px apart
        // spread 40 px per side in 8 moves => distance 200 -> 360 (>= 2 steps of 56 after the 40 px start)
        for (i in 1..8) {
            ev(1, MOVE, 300f - 10f * i, 400f, 20L + i * 16); ev(2, MOVE, 500f + 10f * i, 400f, 20L + i * 16)
        }
        ev(1, UP, 220f, 400f, 300); ev(2, UP, 580f, 400f, 300)
        val zooms = out.filterIsInstance<Zoom>()
        assertTrue("expected zoom steps, got $out", zooms.size >= 2)
        assertTrue(zooms.all { !it.inward })
        assertTrue(out.none { it is Scroll })
    }

    @Test fun twoFingerPinchInZoomsOut() {
        ev(1, DOWN, 200f, 400f, 0); ev(2, DOWN, 600f, 400f, 10) // 400 px apart
        for (i in 1..10) {
            ev(1, MOVE, 200f + 12f * i, 400f, 20L + i * 16); ev(2, MOVE, 600f - 12f * i, 400f, 20L + i * 16)
        }
        ev(1, UP, 320f, 400f, 300); ev(2, UP, 480f, 400f, 300)
        val zooms = out.filterIsInstance<Zoom>()
        assertTrue("expected zoom steps, got $out", zooms.isNotEmpty())
        assertTrue(zooms.all { it.inward })
    }

    @Test fun twoFingerScrollWithSlightSpreadStaysAScroll() {
        ev(1, DOWN, 300f, 400f, 0); ev(2, DOWN, 500f, 400f, 10)
        for (i in 1..12) {
            ev(1, MOVE, 300f - 0.5f * i, 400f + 12f * i, 20L + i * 16); ev(2, MOVE, 500f + 0.5f * i, 400f + 12f * i, 20L + i * 16)
        }
        ev(1, UP, 294f, 544f, 300); ev(2, UP, 506f, 544f, 300)
        assertTrue(out.any { it is Scroll })
        assertTrue(out.none { it is Zoom })
    }

    @Test fun fiveFingerPinchInIsLaunchpad() {
        val pos = fingersDown(5)
        moveAll(pos, 10, 0f, 0f, dr = -10f)
        liftAll(pos, 400)
        assertEquals(listOf(Pinch(spread = false)), out)
    }

    @Test fun fiveFingerSpreadIsShowDesktop() {
        val pos = fingersDown(5, r = 60f)
        moveAll(pos, 10, 0f, 0f, dr = 10f)
        liftAll(pos, 400)
        assertEquals(listOf(Pinch(spread = true)), out)
    }

    @Test fun fiveFingerSwipeUpIsMissionControlOnce() {
        val pos = fingersDown(5)
        moveAll(pos, 20, 0f, -20f)
        liftAll(pos, 600)
        assertEquals(1, out.filterIsInstance<Swipe>().size)
        assertEquals(SwipeDir.UP, out.filterIsInstance<Swipe>().first().dir)
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

    // ---------------------------------------------------------------- 3 / 4 fingers

    private fun fingersDown(n: Int, t: Long = 0, cx: Float = 400f, cy: Float = 400f, r: Float = 150f): Map<Long, Pair<Float, Float>> {
        val pos = LinkedHashMap<Long, Pair<Float, Float>>()
        for (k in 0 until n) {
            val a = Math.PI * 2 * k / n
            val p = (cx + r * Math.cos(a).toFloat()) to (cy + r * Math.sin(a).toFloat())
            pos[k + 1L] = p
            ev(k + 1L, DOWN, p.first, p.second, t + k * 5)
        }
        return pos
    }

    /** Every finger moves by (dx, dy) per step and towards / away from the centre by dr per step. */
    private fun moveAll(pos: Map<Long, Pair<Float, Float>>, steps: Int, dx: Float, dy: Float, dr: Float = 0f,
                        t0: Long = 50, cx: Float = 400f, cy: Float = 400f) {
        for (i in 1..steps) {
            for ((id, p) in pos) {
                val ox = p.first - cx; val oy = p.second - cy
                val len = Math.hypot(ox.toDouble(), oy.toDouble()).toFloat()
                val k = if (len == 0f) 0f else (len + dr * i) / len
                ev(id, MOVE, cx + ox * k + dx * i, cy + oy * k + dy * i, t0 + i * 16L)
            }
        }
    }

    private fun liftAll(pos: Map<Long, Pair<Float, Float>>, t: Long) { for (id in pos.keys) ev(id, UP, 0f, 0f, t) }

    @Test fun fourFingerSwipeUpIsMissionControlOnce() {
        val pos = fingersDown(4)
        moveAll(pos, 20, 0f, -20f)
        liftAll(pos, 600)
        assertEquals(listOf(Swipe(SwipeDir.UP, 4)), out)
        assertEquals("mission_control", TrackpadSender.swipeAction(SwipeDir.UP))
    }

    @Test fun fourAndFiveFingerSwipesMapToTheSameActions() {
        for (n in listOf(4, 5)) for (d in SwipeDir.entries) {
            out.clear()
            m = TrackpadGestureMachine(cfg) { out += it }
            val pos = fingersDown(n)
            val (dx, dy) = when (d) { SwipeDir.UP -> 0f to -20f; SwipeDir.DOWN -> 0f to 20f; SwipeDir.LEFT -> -20f to 0f; SwipeDir.RIGHT -> 20f to 0f }
            moveAll(pos, 15, dx, dy)
            liftAll(pos, 600)
            assertEquals("$n fingers $d", listOf(Swipe(d, n)), out)
        }
        assertEquals("app_expose", TrackpadSender.swipeAction(SwipeDir.DOWN))
        assertEquals("desktop_left", TrackpadSender.swipeAction(SwipeDir.RIGHT))
        assertEquals("desktop_right", TrackpadSender.swipeAction(SwipeDir.LEFT))
    }

    @Test fun shortFourFingerMoveDoesNothing() {
        val pos = fingersDown(4)
        moveAll(pos, 5, 0f, -20f) // 100 px < 160
        liftAll(pos, 400)
        assertTrue(out.isEmpty())
    }

    @Test fun fourFingerPinchInIsLaunchpad() {
        val pos = fingersDown(4)
        moveAll(pos, 10, 0f, 0f, dr = -10f) // radius 150 → 50
        liftAll(pos, 400)
        assertEquals(listOf(Pinch(spread = false)), out)
        assertEquals("app_drawer", TrackpadSender.pinchAction(false))
    }

    @Test fun fourFingerSpreadIsShowDesktop() {
        val pos = fingersDown(4, r = 60f)
        moveAll(pos, 10, 0f, 0f, dr = 10f)
        liftAll(pos, 400)
        assertEquals(listOf(Pinch(spread = true)), out)
        assertEquals("show_desktop", TrackpadSender.pinchAction(true))
    }

    @Test fun threeFingerPinchIsNotAPinch() {
        val pos = fingersDown(3)
        moveAll(pos, 10, 0f, 0f, dr = -10f)
        liftAll(pos, 400)
        assertTrue(out.none { it is Pinch })
    }

    @Test fun fourthFingerJoiningAThreeFingerTouchCountsAsFour() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 5); ev(3, DOWN, 300f, 300f, 10)
        ev(4, DOWN, 400f, 300f, 30)
        ev(4, DOWN, 400f, 300f, 31) // duplicate DOWN for the same id is not a fifth finger
        for (i in 1..12) for (id in 1L..4L) ev(id, MOVE, id * 100f, 300f + i * 20f, 40 + i * 16L)
        assertEquals(listOf(Swipe(SwipeDir.DOWN, 4)), out)
    }

    @Test fun fingersLandingOneByOneStillGiveAFourFingerSwipe() {
        // First finger moves the cursor a bit, then two, three, four land: the swipe still fires.
        ev(1, DOWN, 100f, 300f, 0); ev(1, MOVE, 130f, 300f, 20)
        ev(2, DOWN, 200f, 300f, 40); ev(3, DOWN, 300f, 300f, 60); ev(4, DOWN, 400f, 300f, 80)
        for (i in 1..12) {
            ev(1, MOVE, 130f - i * 20f, 300f, 90 + i * 16L)
            for (id in 2L..4L) ev(id, MOVE, id * 100f - i * 20f, 300f, 90 + i * 16L)
        }
        assertEquals(listOf(Swipe(SwipeDir.LEFT, 4)), out.filterNot { it is Move })
        assertEquals("desktop_right", TrackpadSender.swipeAction(SwipeDir.LEFT))
    }

    @Test fun scrollThatGainsTwoMoreFingersBecomesAFourFingerSwipe() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 5)
        for (i in 1..4) { ev(1, MOVE, 100f, 300f - i * 10f, i * 16L); ev(2, MOVE, 200f, 300f - i * 10f, i * 16L) }
        assertTrue(out.any { it is Scroll })
        out.clear()
        ev(3, DOWN, 300f, 260f, 100); ev(4, DOWN, 400f, 260f, 110)
        for (i in 1..12) for (id in 1L..4L) ev(id, MOVE, id * 100f, 260f - i * 20f, 120 + i * 16L)
        assertEquals(listOf(Swipe(SwipeDir.UP, 4)), out)
    }
}
