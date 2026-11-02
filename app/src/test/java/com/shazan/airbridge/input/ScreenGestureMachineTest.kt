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

    @Test fun pinchIsIgnored() {
        ev(1, DOWN, 100f, 300f, 0); ev(2, DOWN, 200f, 300f, 0)
        for (i in 1..5) { ev(1, MOVE, 100f - i * 10, 300f, i * 16L); ev(2, MOVE, 200f + i * 10, 300f, i * 16L) }
        ev(1, UP, 50f, 300f, 200); ev(2, UP, 250f, 300f, 210)
        assertTrue(out.isEmpty())
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
