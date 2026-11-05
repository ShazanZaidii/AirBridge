package com.shazan.airbridge.input

import com.shazan.airbridge.input.TouchAction.CANCEL
import com.shazan.airbridge.input.TouchAction.DOWN
import com.shazan.airbridge.input.TouchAction.MOVE
import com.shazan.airbridge.input.TouchAction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsGestureGuardTest {

    /** Fake settings provider; [refuse] keys reject writes like the stock provider does for private keys. */
    private class FakeSettings(vararg initial: Pair<String, String>) : SettingsStore {
        val values = HashMap<String, String>().apply { initial.forEach { put(it.first, it.second) } }
        val refuse = HashSet<String>()
        val writes = mutableListOf<Pair<String, String>>()
        override fun get(ns: SettingsNs, key: String) = values["${ns.name}/$key"]
        override fun put(ns: SettingsNs, key: String, value: String): Boolean {
            if (key in refuse) return false
            values["${ns.name}/$key"] = value
            writes += key to value
            return true
        }
    }

    /** Fake prefs; [log] records the order of backup writes vs settings writes. */
    private class FakeBackup(private val log: MutableList<String>) : BackupStore {
        val map = LinkedHashMap<String, String>()
        override fun all(): Map<String, String> = LinkedHashMap(map)
        override fun save(id: String, value: String): Boolean { map[id] = value; log += "backup:$id"; return true }
        override fun remove(id: String) { map.remove(id) }
    }

    private val a = GestureSwitch(SettingsNs.SYSTEM, "three_finger_shot", "0")
    private val b = GestureSwitch(SettingsNs.SECURE, "split_gesture", "off")
    private val missing = GestureSwitch(SettingsNs.SYSTEM, "not_on_this_phone", "0")

    @Test fun engageSavesBeforeChangingAndRestorePutsBack() {
        val log = mutableListOf<String>()
        val settings = object : SettingsStore {
            val inner = FakeSettings("SYSTEM/three_finger_shot" to "1", "SECURE/split_gesture" to "on")
            override fun get(ns: SettingsNs, key: String) = inner.get(ns, key)
            override fun put(ns: SettingsNs, key: String, value: String): Boolean { log += "put:$key=$value"; return inner.put(ns, key, value) }
        }
        val backup = FakeBackup(log)
        val g = GestureSwitchGuard(settings, backup)

        assertEquals(2, g.engage(listOf(a, b)))
        assertEquals("0", settings.get(SettingsNs.SYSTEM, "three_finger_shot"))
        assertEquals("off", settings.get(SettingsNs.SECURE, "split_gesture"))
        assertEquals(
            listOf("backup:SYSTEM|three_finger_shot", "put:three_finger_shot=0", "backup:SECURE|split_gesture", "put:split_gesture=off"),
            log,
        )

        assertEquals(2, g.restore())
        assertEquals("1", settings.get(SettingsNs.SYSTEM, "three_finger_shot"))
        assertEquals("on", settings.get(SettingsNs.SECURE, "split_gesture"))
        assertFalse(g.hasPending)
        assertEquals(0, g.restore()) // idempotent
    }

    @Test fun keysThatDoNotExistAreNeverCreated() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "1")
        val g = GestureSwitchGuard(s, FakeBackup(mutableListOf()))
        assertEquals(1, g.engage(listOf(a, missing)))
        assertNull(s.get(SettingsNs.SYSTEM, "not_on_this_phone"))
        assertTrue(s.writes.none { it.first == "not_on_this_phone" })
    }

    @Test fun alreadyOffIsLeftAloneAndNotRestoredToOn() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "0")
        val backup = FakeBackup(mutableListOf())
        val g = GestureSwitchGuard(s, backup)
        assertEquals(0, g.engage(listOf(a)))
        assertTrue(backup.map.isEmpty())
        g.restore()
        assertEquals("0", s.get(SettingsNs.SYSTEM, "three_finger_shot"))
        assertTrue(s.writes.isEmpty())
    }

    @Test fun refusedWriteLeavesNoBackup() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "1").apply { refuse += "three_finger_shot" }
        val backup = FakeBackup(mutableListOf())
        val g = GestureSwitchGuard(s, backup)
        assertEquals(0, g.engage(listOf(a)))
        assertTrue(backup.map.isEmpty())
        assertEquals("1", s.get(SettingsNs.SYSTEM, "three_finger_shot"))
    }

    @Test fun crashLeftoverIsRestoredAtNextStart() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "1")
        val backup = FakeBackup(mutableListOf())
        GestureSwitchGuard(s, backup).engage(listOf(a))
        // Process dies here: no restore. Next start, a fresh guard over the same stores:
        val next = GestureSwitchGuard(s, backup)
        assertTrue(next.hasPending)
        assertEquals(1, next.restore())
        assertEquals("1", s.get(SettingsNs.SYSTEM, "three_finger_shot"))
    }

    @Test fun reEngagingWhileStillOffKeepsTheOriginalBackup() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "1")
        val backup = FakeBackup(mutableListOf())
        val g = GestureSwitchGuard(s, backup)
        g.engage(listOf(a))
        g.engage(listOf(a)) // resumed twice without a pause in between
        g.restore()
        assertEquals("1", s.get(SettingsNs.SYSTEM, "three_finger_shot"))
    }

    @Test fun valueChangedByTheUserMeanwhileIsNotOverwritten() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "1")
        val backup = FakeBackup(mutableListOf())
        val g = GestureSwitchGuard(s, backup)
        g.engage(listOf(a))
        s.values["SYSTEM/three_finger_shot"] = "2" // changed in Settings while we were away
        assertEquals(0, g.restore())
        assertEquals("2", s.get(SettingsNs.SYSTEM, "three_finger_shot"))
        assertFalse(g.hasPending)
    }

    @Test fun restoreFailureIsRetriedLater() {
        val s = FakeSettings("SYSTEM/three_finger_shot" to "1")
        val backup = FakeBackup(mutableListOf())
        val g = GestureSwitchGuard(s, backup)
        g.engage(listOf(a))
        s.refuse += "three_finger_shot" // permission revoked meanwhile
        assertEquals(0, g.restore())
        assertTrue(g.hasPending)
        s.refuse.clear()
        assertEquals(1, g.restore())
        assertEquals("1", s.get(SettingsNs.SYSTEM, "three_finger_shot"))
    }

    @Test fun originalValuesWithSeparatorsRoundTrip() {
        val odd = GestureSwitch(SettingsNs.SYSTEM, "k", "0")
        val s = FakeSettings("SYSTEM/k" to "12:3|x:")
        val g = GestureSwitchGuard(s, FakeBackup(mutableListOf()))
        g.engage(listOf(odd))
        g.restore()
        assertEquals("12:3|x:", s.get(SettingsNs.SYSTEM, "k"))
    }

    @Test fun tableHasNoActiveEntriesYet() {
        // vivo keys are pending verification on a real device; nothing may be switched until then.
        assertTrue(OemGestureSwitches.forManufacturer("vivo").isEmpty())
        assertTrue(OemGestureSwitches.forManufacturer(null).isEmpty())
    }

    // ---------------------------------------------------------------- loss detector

    private val losses = mutableListOf<Int>()
    private val d = MultiFingerLossDetector(silenceMs = 1500) { losses += it }

    @Test fun cancelWithThreeFingersAndNoGestureIsALoss() {
        d.onTouch(1, DOWN, 0); d.onTouch(2, DOWN, 5); d.onTouch(3, DOWN, 10)
        d.onTouch(1, MOVE, 30)
        d.onTouch(-1, CANCEL, 40)
        assertEquals(listOf(3), losses)
    }

    @Test fun cancelAfterARecognisedSwipeIsNotALoss() {
        d.onTouch(1, DOWN, 0); d.onTouch(2, DOWN, 5); d.onTouch(3, DOWN, 10)
        d.markHandled()
        d.onTouch(-1, CANCEL, 40)
        assertTrue(losses.isEmpty())
    }

    @Test fun twoFingerCancelAndNormalLiftsAreNotLosses() {
        d.onTouch(1, DOWN, 0); d.onTouch(2, DOWN, 5); d.onTouch(-1, CANCEL, 40)
        d.onTouch(1, DOWN, 100); d.onTouch(2, DOWN, 105); d.onTouch(3, DOWN, 110)
        d.onTouch(1, UP, 200); d.onTouch(2, UP, 205); d.onTouch(3, UP, 210)
        assertTrue(losses.isEmpty())
    }

    @Test fun silentPointersCountAsLossButResetDoesNot() {
        d.onTouch(1, DOWN, 0); d.onTouch(2, DOWN, 5); d.onTouch(3, DOWN, 10)
        d.tick(1000)
        assertTrue(losses.isEmpty())
        d.tick(1510)
        assertEquals(listOf(3), losses)
        d.onTouch(1, DOWN, 2000); d.onTouch(2, DOWN, 2005); d.onTouch(3, DOWN, 2010); d.onTouch(4, DOWN, 2015)
        d.reset()
        d.tick(9000)
        assertEquals(listOf(3), losses)
    }
}
