package com.shazan.airbridge.input

import com.shazan.airbridge.input.DragCarryTarget.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragCarryTargetTest {
    private fun info(id: Long, from: String = "mac", n: Int = 1) =
        DragCarryTarget.info(from, id, (1..n).map { "f$it.jpg" }, (1..n).map { "image" }, n, 1000L * n)!!

    @Test fun carryThenDrop() {
        val c = DragCarryTarget()
        assertEquals(Effect.Show(info(1)), c.onCarry("mac", "mac", info(1)))
        assertEquals(info(1), c.shown)
        assertEquals(Effect.Dropped(info(1), ghostWasShown = true), c.onDrop("mac", 1))
        assertNull(c.shown)
        assertEquals(Effect.None, c.onDrop("mac", 1)) // a duplicate drop does nothing
    }

    @Test fun carryThenCancel() {
        val c = DragCarryTarget()
        c.onCarry("mac", "mac", info(1))
        assertEquals(Effect.None, c.onCancel("mac", 2)) // other id
        assertEquals(Effect.Hide, c.onCancel("mac", 1))
        assertNull(c.shown)
        assertEquals(Effect.None, c.onDrop("mac", 1)) // cancelled: no "Receiving"
    }

    @Test fun onlyTheExplicitHolderMayCarry() {
        val c = DragCarryTarget()
        assertEquals(Effect.None, c.onCarry("pc", "mac", info(1, from = "pc")))
        assertEquals(Effect.None, c.onCarry("mac", null, info(1)))
        assertNull(c.shown)
        c.onCarry("mac", "mac", info(1))
        assertEquals(Effect.None, c.onDrop("pc", 1))
        assertEquals(Effect.None, c.onCancel("pc", 1))
        assertEquals(info(1), c.shown)
    }

    @Test fun leaveHidesButALateDropStillConfirms() {
        val c = DragCarryTarget()
        c.onCarry("mac", "mac", info(4))
        assertEquals(Effect.Hide, c.onLeave("mac"))
        assertNull(c.shown)
        // The user let go just as our ctl_leave crossed: the files still come.
        assertEquals(Effect.Dropped(info(4), ghostWasShown = false), c.onDrop("mac", 4))
        assertEquals(Effect.None, c.onDrop("mac", 4))
    }

    @Test fun leaveOfAnotherPeerKeepsTheGhost() {
        val c = DragCarryTarget()
        c.onCarry("mac", "mac", info(1))
        assertEquals(Effect.None, c.onLeave("pc"))
        assertEquals(Effect.Hide, c.onLeave(null))
    }

    @Test fun disconnectMidCarryForgetsEverything() {
        val c = DragCarryTarget()
        c.onCarry("mac", "mac", info(1))
        assertEquals(Effect.Hide, c.onPeerGone("mac"))
        assertNull(c.shown)
        assertEquals(Effect.None, c.onDrop("mac", 1))
        assertEquals(Effect.None, c.onPeerGone("mac"))
    }

    @Test fun newCarryReplacesTheOld() {
        val c = DragCarryTarget()
        c.onCarry("mac", "mac", info(1))
        assertEquals(Effect.Show(info(2, n = 3)), c.onCarry("mac", "mac", info(2, n = 3)))
        assertEquals(Effect.None, c.onDrop("mac", 1))
        val d = c.onDrop("mac", 2)
        assertTrue(d is Effect.Dropped && d.info.count == 3)
    }

    @Test fun infoIsSanitised() {
        assertNull(DragCarryTarget.info("mac", null, listOf("a"), emptyList(), 1, 1))
        assertNull(DragCarryTarget.info("mac", -1, listOf("a"), emptyList(), 1, 1))
        val i = DragCarryTarget.info("mac", 7, listOf(" a.txt ", "", "Photos", "b", "c"), listOf("file", "folder", "weird"), 2, -5)!!
        assertEquals(listOf("a.txt", "Photos", "b"), i.names) // blanks dropped, at most 3
        assertEquals(listOf("file", "folder", "file"), i.kinds) // aligned, unknown kinds → file
        assertEquals(3, i.count) // never fewer than the names shown
        assertEquals(0L, i.totalBytes)
        val folder = DragCarryTarget.info("mac", 8, listOf("Photos"), listOf("folder"), 1, 0)!!
        assertEquals("folder", folder.kind)
        val empty = DragCarryTarget.info("mac", 9, emptyList(), emptyList(), null, null)!!
        assertEquals(1, empty.count)
        assertEquals("", empty.firstName)
    }
}
