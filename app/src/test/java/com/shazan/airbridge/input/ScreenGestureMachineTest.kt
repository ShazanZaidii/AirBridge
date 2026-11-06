package com.shazan.airbridge.input

import com.shazan.airbridge.input.ScreenIntent.DragTo
import com.shazan.airbridge.input.ScreenIntent.Hover
import com.shazan.airbridge.input.ScreenIntent.Press
import com.shazan.airbridge.input.ScreenIntent.Release
import com.shazan.airbridge.input.ScreenIntent.ResetAll
import com.shazan.airbridge.input.ScreenIntent.Scroll
import com.shazan.airbridge.input.ScreenIntent.Tap
import com.shazan.airbridge.input.TouchAction.CANCEL
import com.shazan.airbridge.input.TouchAction.DOWN
import com.shazan.airbridge.input.TouchAction.MOVE
import com.shazan.airbridge.input.TouchAction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGestureMachineTest {
    private val out = mutableListOf<ScreenIntent>()
    private val m = ScreenGestureMachine(ScreenGestureMachine.Config(slopPx = 16f)) { out += it }
    private fun ev(id: Long, a: TouchAction, x: Float, y: Float, t: Long) = m.onTouch(id, a, x, y, t)

    @Test fun tapClicksAtTouchPoint() {
        ev(1, DOWN, 300f, 200f, 0); ev(1, UP, 302f, 201f, 120)
        assertEquals(listOf(Tap(300f, 200f, 0, 1)), out)
    }

    @Test fun doubleTapIsN1ThenN2() {
        ev(1, DOWN, 300f, 200f, 0); ev(1, UP, 300f, 200f, 80)
        ev(2, DOWN, 305f, 203f, 200); ev(2, UP, 305f, 203f, 260)
        assertEquals(listOf(Tap(300f, 200f, 0, 1), Tap(305f, 203f, 0, 2)), out)
    }

    @Test fun oneFingerMoveHoversWithoutClicking() {
        ev(1, DOWN, 100f, 100f, 0); ev(1, MOVE, 140f, 100f, 50); ev(1, UP, 140f, 100f, 90)
        assertEquals(listOf(Hover(140f, 100f)), out)
    }

    @Test fun longPressThenMoveDrags() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(500)
        ev(1, MOVE, 150f, 100f, 600)
        ev(1, UP, 150f, 100f, 700)
        assertEquals(listOf(Press(100f, 100f), DragTo(150f, 100f), Release(150f, 100f)), out)
    }

    @Test fun twoFingerDragScrollsContentWithFingers() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 0)
        ev(1, MOVE, 100f, 260f, 16); ev(2, MOVE, 200f, 260f, 16)
        assertEquals(40.0, out.filterIsInstance<Scroll>().sumOf { it.dy.toDouble() }, 0.01)
        ev(1, UP, 100f, 260f, 50); ev(2, UP, 200f, 260f, 60)
        assertTrue(out.none { it is Tap })
    }

    @Test fun twoFingerSpreadZoomsIn() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 0)
        for (i in 1..8) { ev(1, MOVE, 100f - i * 10, 300f, i * 16L); ev(2, MOVE, 200f + i * 10, 300f, i * 16L) }
        ev(1, UP, 20f, 300f, 200); ev(2, UP, 280f, 300f, 210)
        val zooms = out.filterIsInstance<ScreenIntent.Zoom>()
        assertTrue("got $out", zooms.isNotEmpty() && zooms.all { !it.inward })
        assertTrue(out.none { it is Tap || it is Scroll })
    }

    private fun hand(n: Int, cx: Float = 500f, cy: Float = 400f, r: Float = 150f): List<Pair<Float, Float>> =
        (0 until n).map { k -> val a = Math.PI * 2 * k / n; (cx + r * Math.cos(a).toFloat()) to (cy + r * Math.sin(a).toFloat()) }

    @Test fun fourFingerSwipesWorkInScreenModeToo() {
        for (d in SwipeDir.entries) {
            out.clear()
            val mm = ScreenGestureMachine(ScreenGestureMachine.Config(slopPx = 16f)) { out += it }
            val h = hand(4)
            h.forEachIndexed { i, p -> mm.onTouch(i + 1L, DOWN, p.first, p.second, i * 5L) }
            val (dx, dy) = when (d) { SwipeDir.UP -> 0f to -20f; SwipeDir.DOWN -> 0f to 20f; SwipeDir.LEFT -> -20f to 0f; SwipeDir.RIGHT -> 20f to 0f }
            for (s in 1..10) h.forEachIndexed { i, p -> mm.onTouch(i + 1L, MOVE, p.first + dx * s, p.second + dy * s, 30 + s * 16L) }
            h.forEachIndexed { i, _ -> mm.onTouch(i + 1L, UP, 0f, 0f, 400) }
            assertEquals("$d", listOf(ScreenIntent.Swipe(d, 4)), out.filterNot { it is Hover })
        }
    }

    @Test fun fiveFingerPinchInScreenModeIsLaunchpad() {
        val h = hand(5)
        h.forEachIndexed { i, p -> ev(i + 1L, DOWN, p.first, p.second, i * 5L) }
        for (s in 1..10) h.forEachIndexed { i, p ->
            val k = (150f - s * 10f) / 150f
            ev(i + 1L, MOVE, 500f + (p.first - 500f) * k, 400f + (p.second - 400f) * k, 30 + s * 16L)
        }
        h.forEachIndexed { i, _ -> ev(i + 1L, UP, 0f, 0f, 400) }
        assertEquals(listOf(ScreenIntent.Pinch(spread = false)), out.filterNot { it is Hover })
    }

    @Test fun threeFingerSwipeWorksInScreenModeAndATakenTouchIsRecovered() {
        val h = hand(3)
        h.forEachIndexed { i, p -> ev(i + 1L, DOWN, p.first, p.second, i * 5L) }
        for (s in 1..15) h.forEachIndexed { i, p -> ev(i + 1L, MOVE, p.first, p.second - s * 20f, 30 + s * 16L) }
        h.forEachIndexed { i, _ -> ev(i + 1L, UP, 0f, 0f, 400) }
        assertEquals(listOf(ScreenIntent.Swipe(SwipeDir.UP, 3)), out.filterNot { it is Hover })
        out.clear()
        val g = hand(4)
        g.forEachIndexed { i, p -> ev(i + 10L, DOWN, p.first, p.second, 1000 + i * 5L) }
        for (s in 1..3) g.forEachIndexed { i, p -> ev(i + 10L, MOVE, p.first + s * 20f, p.second, 1030 + s * 16L) }
        ev(-1, CANCEL, 0f, 0f, 1200)
        assertEquals(listOf(ScreenIntent.Swipe(SwipeDir.RIGHT, 4), ResetAll), out.filterNot { it is Hover })
    }

    @Test fun firmPressForceClicksAtTheTouchPoint() {
        m.firmPressAt = 0.6f
        m.onTouch(1, DOWN, 300f, 200f, 0, 0.3f)
        m.onTouch(1, MOVE, 301f, 200f, 50, 0.75f) // too soon
        m.onTouch(1, MOVE, 301f, 200f, 160, 0.8f)
        m.onTouch(1, UP, 301f, 200f, 220, 0.2f)
        assertEquals(listOf(ScreenIntent.FirmPress(300f, 200f)), out)
    }

    @Test fun twoFingerTapIsRightClick() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 20)
        ev(1, UP, 100f, 300f, 100); ev(2, UP, 200f, 300f, 120)
        assertEquals(listOf(Tap(100f, 300f, 1, 1)), out)
    }

    @Test fun cancelMidDragReleases() {
        ev(1, DOWN, 100f, 100f, 0)
        m.tick(500)
        ev(1, MOVE, 120f, 100f, 550)
        ev(1, CANCEL, 0f, 0f, 560)
        assertEquals(listOf(Press(100f, 100f), DragTo(120f, 100f), Release(120f, 100f), ResetAll), out)
        ev(1, UP, 120f, 100f, 600)
        assertEquals(4, out.size)
    }
}
