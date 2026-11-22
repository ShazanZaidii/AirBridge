package com.shazan.airbridge.phone.screentime

import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.Event
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.KEYGUARD_SHOWN
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.PAUSED
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.RESUMED
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.SCREEN_NON_INTERACTIVE
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScreenTimeComputeTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun t(day: Int, h: Int, m: Int = 0, s: Int = 0) = LocalDateTime.of(2026, 11, day, h, m, s).atZone(zone).toInstant().toEpochMilli()
    private val home = setOf("com.android.launcher3", "com.android.systemui")
    private fun compute(vararg e: Event, now: Long = t(22, 23)) = ScreenTimeCompute.compute(e.toList(), now, zone, home)

    @Test fun anAppCountsFromFrontToLeaving() {
        val d = compute(Event(t(22, 10), "com.whatsapp", RESUMED), Event(t(22, 10, 30), "com.whatsapp", PAUSED))
        assertEquals(1800.0, d.getValue("2026-11-22").seconds, 0.01)
        assertEquals(1800.0, d.getValue("2026-11-22").apps.getValue("com.whatsapp"), 0.01)
    }

    @Test fun timeIsSplitAtTheHour() {
        val d = compute(Event(t(22, 9, 50), "com.whatsapp", RESUMED), Event(t(22, 10, 20), "com.whatsapp", PAUSED)).getValue("2026-11-22")
        assertEquals(600.0, d.hours[9], 0.01)
        assertEquals(1200.0, d.hours[10], 0.01)
    }

    @Test fun aStretchOverMidnightGoesToBothDays() {
        val d = compute(Event(t(21, 23, 50), "com.youtube", RESUMED), Event(t(22, 0, 15), "com.youtube", STOPPED))
        assertEquals(600.0, d.getValue("2026-11-21").hours[23], 0.01)
        assertEquals(900.0, d.getValue("2026-11-22").hours[0], 0.01)
    }

    @Test fun switchingAppsClosesThePreviousOne() {
        val d = compute(Event(t(22, 10), "com.whatsapp", RESUMED), Event(t(22, 10, 10), "com.youtube", RESUMED), Event(t(22, 10, 40), "com.youtube", PAUSED)).getValue("2026-11-22")
        assertEquals(600.0, d.apps.getValue("com.whatsapp"), 0.01)
        assertEquals(1800.0, d.apps.getValue("com.youtube"), 0.01)
    }

    @Test fun anotherActivityOfTheSameAppDoesNotRestartIt() {
        val d = compute(Event(t(22, 10), "com.whatsapp", RESUMED), Event(t(22, 10, 5), "com.whatsapp", RESUMED), Event(t(22, 10, 20), "com.whatsapp", PAUSED)).getValue("2026-11-22")
        assertEquals(1200.0, d.seconds, 0.01)
    }

    @Test fun theScreenGoingOffOrTheLockScreenStopsCounting() {
        val off = compute(Event(t(22, 10), "com.whatsapp", RESUMED), Event(t(22, 10, 5), "", SCREEN_NON_INTERACTIVE)).getValue("2026-11-22")
        assertEquals(300.0, off.seconds, 0.01)
        val lock = compute(Event(t(22, 11), "com.whatsapp", RESUMED), Event(t(22, 11, 7), "", KEYGUARD_SHOWN)).getValue("2026-11-22")
        assertEquals(420.0, lock.seconds, 0.01)
    }

    @Test fun theHomeScreenAndSystemUiAreNotScreenTime() {
        val d = compute(Event(t(22, 10), "com.android.launcher3", RESUMED), Event(t(22, 10, 30), "com.whatsapp", RESUMED), Event(t(22, 10, 40), "com.android.launcher3", RESUMED))
        val day = d.getValue("2026-11-22")
        assertEquals(600.0, day.seconds, 0.01)
        assertNull(day.apps["com.android.launcher3"])
    }

    @Test fun anAppStillInFrontCountsUpToNow() {
        val d = compute(Event(t(22, 20), "com.instagram.android", RESUMED), now = t(22, 20, 25))
        assertEquals(1500.0, d.getValue("2026-11-22").seconds, 0.01)
    }

    @Test fun aMissedScreenOffNeverCountsAWholeNight() {
        val d = compute(Event(t(22, 0), "com.whatsapp", RESUMED), now = t(22, 12))
        assertEquals(4 * 3600.0, d.getValue("2026-11-22").seconds, 0.01)
    }

    @Test fun eventsOutOfOrderAreSortedFirst() {
        val d = compute(Event(t(22, 10, 30), "com.whatsapp", PAUSED), Event(t(22, 10), "com.whatsapp", RESUMED))
        assertEquals(1800.0, d.getValue("2026-11-22").seconds, 0.01)
    }

    @Test fun aPauseOfAnotherAppIsIgnored() {
        val d = compute(Event(t(22, 10), "com.whatsapp", RESUMED), Event(t(22, 10, 5), "com.youtube", PAUSED), Event(t(22, 10, 10), "com.whatsapp", PAUSED))
        assertEquals(600.0, d.getValue("2026-11-22").seconds, 0.01)
    }

    @Test fun nothingInNothingOut() {
        assertTrue(compute().isEmpty())
    }

    @Test fun thePayloadHasTodaysHoursAndAppsAndOnlyTotalsForEarlierDays() {
        val days = compute(
            Event(t(21, 9), "com.youtube", RESUMED), Event(t(21, 10), "com.youtube", PAUSED),
            Event(t(22, 9), "com.whatsapp", RESUMED), Event(t(22, 9, 30), "com.whatsapp", PAUSED),
            Event(t(22, 14), "com.youtube", RESUMED), Event(t(22, 15), "com.youtube", PAUSED))
        val p = ScreenTimeCompute.payload(days, "2026-11-22", { if (it == "com.whatsapp") "WhatsApp" else "YouTube" })
        assertEquals(2, p.size)
        val (yesterday, today) = p
        assertEquals("2026-11-21", yesterday.day)
        assertEquals(3600L, yesterday.seconds)
        assertNull(yesterday.hours); assertNull(yesterday.apps)
        assertEquals(5400L, today.seconds)
        assertEquals(24, today.hours!!.size)
        assertEquals(1800L, today.hours!![9])
        assertEquals(listOf("com.youtube", "com.whatsapp"), today.apps!!.map { it.id })
        assertEquals(listOf("YouTube", "WhatsApp"), today.apps!!.map { it.name })
        assertEquals(3600L, today.apps!![0].seconds)
    }

    @Test fun thePayloadKeepsOnlyTheNewestDaysAndTopApps() {
        val ev = ArrayList<Event>()
        for (d in 1..20) { ev.add(Event(t(d, 10), "a", RESUMED)); ev.add(Event(t(d, 11), "a", PAUSED)) }
        for (i in 0 until 30) { ev.add(Event(t(22, 12, i), "app$i", RESUMED)); ev.add(Event(t(22, 12, i, 30 + i), "app$i", PAUSED)) }
        val days = ScreenTimeCompute.compute(ev, t(22, 23), zone, home)
        val p = ScreenTimeCompute.payload(days, "2026-11-22", { it }, maxDays = 7, maxApps = 20)
        assertEquals(7, p.size)
        assertEquals("2026-11-22", p.last().day)
        assertEquals(20, p.last().apps!!.size)
        assertEquals("app29", p.last().apps!!.first().id) // 59 s, the longest
        assertEquals(listOf("app29", "app28"), ScreenTimeCompute.topApps(days, "2026-11-22", 2))
    }

    // ---- screens inside one app (the Reddit bug: post -> comments stopped the count)

    @Test fun openingAnotherScreenInsideTheAppKeepsCounting() {
        // Android reports the new screen resumed first, then the old one stopped.
        val d = compute(
            Event(t(22, 10), "com.reddit.frontpage", RESUMED, "Main"),
            Event(t(22, 10, 5), "com.reddit.frontpage", RESUMED, "Comments"),
            Event(t(22, 10, 5, 1), "com.reddit.frontpage", STOPPED, "Main"),
            Event(t(22, 10, 15), "com.reddit.frontpage", PAUSED, "Comments")).getValue("2026-11-22")
        assertEquals(900.0, d.seconds, 0.01)
    }

    @Test fun goingBackToTheFirstScreenKeepsCountingToo() {
        val d = compute(
            Event(t(22, 10), "r", RESUMED, "Main"), Event(t(22, 10, 0, 1), "r", PAUSED, "Main"),
            Event(t(22, 10, 0, 1), "r", RESUMED, "Post"), Event(t(22, 10, 0, 2), "r", STOPPED, "Main"),
            Event(t(22, 10, 8), "r", PAUSED, "Post"), Event(t(22, 10, 8), "r", RESUMED, "Main"),
            Event(t(22, 10, 8, 1), "r", STOPPED, "Post"), Event(t(22, 10, 20), "r", PAUSED, "Main")).getValue("2026-11-22")
        assertEquals(1200.0, d.seconds, 0.01)
    }

    @Test fun anAppStopsCountingWhenItsLastScreenLeaves() {
        val d = compute(
            Event(t(22, 10), "x", RESUMED, "A"), Event(t(22, 10, 2), "x", RESUMED, "B"),
            Event(t(22, 10, 2), "x", STOPPED, "A"), Event(t(22, 10, 6), "x", PAUSED, "B"),
            Event(t(22, 10, 30), "", SCREEN_NON_INTERACTIVE)).getValue("2026-11-22")
        assertEquals(360.0, d.seconds, 0.01)
    }

    @Test fun switchingAppsStillMovesTheTimeToTheNewApp() {
        val d = compute(
            Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 10), "b", RESUMED, "B1"),
            Event(t(22, 10, 10, 1), "a", STOPPED, "A1"), Event(t(22, 10, 25), "b", PAUSED, "B1")).getValue("2026-11-22")
        assertEquals(600.0, d.apps.getValue("a"), 0.01)
        assertEquals(900.0, d.apps.getValue("b"), 0.01)
    }

    @Test fun theOldAppStoppingLateDoesNotEndTheNewOne() {
        val d = compute(
            Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 10), "b", RESUMED, "B1"),
            Event(t(22, 10, 12), "a", STOPPED, "A1"), Event(t(22, 10, 30), "b", PAUSED, "B1")).getValue("2026-11-22")
        assertEquals(1200.0, d.apps.getValue("b"), 0.01)
    }

    @Test fun afterTheScreenTurnsOffNothingIsLeftShowing() {
        val d = compute(
            Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 5), "", SCREEN_NON_INTERACTIVE),
            Event(t(22, 10, 40), "a", PAUSED, "A1"), Event(t(22, 11), "a", RESUMED, "A1"), Event(t(22, 11, 10), "a", PAUSED, "A1")).getValue("2026-11-22")
        assertEquals(900.0, d.seconds, 0.01)
    }

    // ---- Android 10+: counted while visible (Digital Wellbeing's rule)

    private fun visible(vararg e: Event, now: Long = t(22, 23)) =
        ScreenTimeCompute.compute(e.toList(), now, zone, home, untilStopped = true)

    @Test fun anAppUnderAnotherAppsDialogKeepsCounting() {
        val d = visible(
            Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 5), "a", PAUSED, "A1"),
            Event(t(22, 10, 5), "perm", RESUMED, "Dialog"), Event(t(22, 10, 6), "perm", PAUSED, "Dialog"),
            Event(t(22, 10, 6), "a", RESUMED, "A1"), Event(t(22, 10, 6), "perm", STOPPED, "Dialog"),
            Event(t(22, 10, 20), "a", PAUSED, "A1"), Event(t(22, 10, 20), "a", STOPPED, "A1")).getValue("2026-11-22")
        assertEquals(1200.0, d.apps.getValue("a"), 0.01)
        assertEquals(60.0, d.apps.getValue("perm"), 0.01)
    }

    @Test fun pausedButStillVisibleCountsUntilTheScreenTurnsOff() {
        val d = visible(Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 5), "a", PAUSED, "A1"),
            Event(t(22, 10, 15), "", SCREEN_NON_INTERACTIVE)).getValue("2026-11-22")
        assertEquals(900.0, d.seconds, 0.01)
    }

    @Test fun switchingToAnotherAppEndsTheFirstWhenItStops() {
        val d = visible(
            Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 10), "a", PAUSED, "A1"),
            Event(t(22, 10, 10), "b", RESUMED, "B1"), Event(t(22, 10, 10, 1), "a", STOPPED, "A1"),
            Event(t(22, 10, 25), "b", PAUSED, "B1"), Event(t(22, 10, 25), "b", STOPPED, "B1")).getValue("2026-11-22")
        assertEquals(601.0, d.apps.getValue("a"), 0.01)
        assertEquals(900.0, d.apps.getValue("b"), 0.01)
    }

    @Test fun screensInsideOneAppStillCountAsOneStretch() {
        val d = visible(
            Event(t(22, 10), "r", RESUMED, "Main"), Event(t(22, 10, 5), "r", PAUSED, "Main"),
            Event(t(22, 10, 5), "r", RESUMED, "Comments"), Event(t(22, 10, 5, 1), "r", STOPPED, "Main"),
            Event(t(22, 10, 15), "r", PAUSED, "Comments"), Event(t(22, 10, 15), "r", STOPPED, "Comments")).getValue("2026-11-22")
        assertEquals(900.0, d.seconds, 0.01)
    }

    @Test fun goingHomeEndsEverythingAtOnce() {
        val homePkg = home.first()
        val d = visible(Event(t(22, 10), "a", RESUMED, "A1"), Event(t(22, 10, 7), homePkg, RESUMED, "Home"),
            Event(t(22, 10, 7, 1), "a", STOPPED, "A1")).getValue("2026-11-22")
        assertEquals(420.0, d.seconds, 0.01)
    }

    @Test fun anAppStillShowingCountsUpToNow() {
        val d = visible(Event(t(22, 22, 30), "a", RESUMED, "A1")).getValue("2026-11-22")
        assertEquals(1800.0, d.seconds, 0.01)
    }
}
