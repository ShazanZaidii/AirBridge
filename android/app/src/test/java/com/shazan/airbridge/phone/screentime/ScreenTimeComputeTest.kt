package com.shazan.airbridge.phone.screentime

import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.AppOut
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.Event
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.KEYGUARD_HIDDEN
import com.shazan.airbridge.phone.screentime.ScreenTimeCompute.NOTIFICATION
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

    @Test fun thePayloadHasHoursAndAppsForEveryDay() {
        val days = compute(
            Event(t(21, 9), "com.youtube", RESUMED), Event(t(21, 10), "com.youtube", PAUSED),
            Event(t(22, 9), "com.whatsapp", RESUMED), Event(t(22, 9, 30), "com.whatsapp", PAUSED),
            Event(t(22, 14), "com.youtube", RESUMED), Event(t(22, 15), "com.youtube", PAUSED))
        val p = ScreenTimeCompute.payload(days, "2026-11-22", { if (it == "com.whatsapp") "WhatsApp" else "YouTube" })
        assertEquals(2, p.size)
        val (yesterday, today) = p
        assertEquals("2026-11-21", yesterday.day)
        assertEquals(3600L, yesterday.seconds)
        assertEquals(3600L, yesterday.hours!![9]); assertEquals(listOf("com.youtube"), yesterday.apps!!.map { it.id })
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
        // Icons go to the most used apps of all the days sent: "a" had an hour every day.
        assertEquals(listOf("a", "app29"), ScreenTimeCompute.topApps(days, "2026-11-22", 2))
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

    // ---- opens, notifications, unlocks, first use (the detail page's Digital Wellbeing numbers)

    @Test fun opensCountComingOnScreenNotScreensInside() {
        val d = visible(
            Event(t(22, 9, 0), "", KEYGUARD_HIDDEN),
            Event(t(22, 9, 1), "r", RESUMED, "Main"), Event(t(22, 9, 2), "r", RESUMED, "Post"), Event(t(22, 9, 2, 1), "r", STOPPED, "Main"),
            Event(t(22, 9, 5), "", SCREEN_NON_INTERACTIVE),
            Event(t(22, 12, 0), "", KEYGUARD_HIDDEN), Event(t(22, 12, 0, 2), "r", RESUMED, "Post"),
            Event(t(22, 12, 3), "r", STOPPED, "Post")).getValue("2026-11-22")
        assertEquals(2, d.opens.getValue("r"))
        assertEquals(2, d.unlocks)
        assertEquals(9 * 3600, d.first)
    }

    @Test fun notificationsCountPerApp() {
        val d = visible(Event(t(22, 8), "com.whatsapp", NOTIFICATION), Event(t(22, 8, 1), "com.whatsapp", NOTIFICATION),
            Event(t(22, 8, 2), "com.reddit", NOTIFICATION)).getValue("2026-11-22")
        assertEquals(2, d.notifs.getValue("com.whatsapp"))
        assertEquals(1, d.notifs.getValue("com.reddit"))
    }

    @Test fun payloadHasEveryDayWithAppsAndTheNewNumbers() {
        val days = visible(
            Event(t(21, 10), "a", RESUMED, "A"), Event(t(21, 10, 30), "a", STOPPED, "A"),
            Event(t(22, 7, 30), "", KEYGUARD_HIDDEN), Event(t(22, 7, 31), "b", NOTIFICATION),
            Event(t(22, 8), "a", RESUMED, "A"), Event(t(22, 8, 10), "a", STOPPED, "A"))
        val p = ScreenTimeCompute.payload(days, "2026-11-22", { it.uppercase() }, category = { if (it == "a") "social" else null })
        assertEquals(listOf("2026-11-21", "2026-11-22"), p.map { it.day })
        assertEquals(1800L, p[0].apps!!.single().seconds)
        assertEquals(24, p[0].hours!!.size)
        val today = p[1]
        assertEquals(1, today.unlocks); assertEquals(1, today.notifs); assertEquals(7 * 3600 + 30 * 60, today.first)
        assertEquals(listOf("a", "b"), today.apps!!.map { it.id })   // b only sent a notification
        assertEquals(AppOut("a", "A", 600, opens = 1, notifs = 0, cat = "social"), today.apps!![0])
        assertEquals(AppOut("b", "B", 0, opens = 0, notifs = 1, cat = null), today.apps!![1])
    }

    @Test fun iconsAreForTheWeeksMostUsedApps() {
        val days = visible(
            Event(t(21, 10), "old", RESUMED, "A"), Event(t(21, 12), "old", STOPPED, "A"),
            Event(t(22, 10), "new", RESUMED, "A"), Event(t(22, 10, 5), "new", STOPPED, "A"))
        assertEquals(listOf("old", "new"), ScreenTimeCompute.topApps(days, "2026-11-22", 5))
    }

    @Test fun androidCategoriesMapToTheProtocolWords() {
        assertEquals("social", ScreenTimeCompute.categoryName(4))
        assertEquals("games", ScreenTimeCompute.categoryName(0))
        assertEquals(null, ScreenTimeCompute.categoryName(-1))
    }

    // ---- home screens are matched by screen, not by app (vivo: Settings is a backup home and also the App Lock prompt)

    private val vivoHome = setOf("com.android.systemui", "com.android.launcher3/com.android.launcher3.Launcher",
        "com.android.settings/com.android.settings.FallbackHome")

    @Test fun anAppLockPromptOverInstagramDoesNotEndIt() {
        // What the phone recorded at 02:02 on 23 Nov: launcher, App Lock (a Settings screen), Instagram, a second App Lock
        // check while Instagram stays on screen, then home 20 minutes later.
        val d = ScreenTimeCompute.compute(listOf(
            Event(t(23, 2, 2, 0), "com.android.launcher3", RESUMED, "com.android.launcher3.Launcher"),
            Event(t(23, 2, 2, 7), "com.android.settings", RESUMED, "com.vivo.settings.secret.PasswordActivity"),
            Event(t(23, 2, 2, 9), "com.android.settings", PAUSED, "com.vivo.settings.secret.PasswordActivity"),
            Event(t(23, 2, 2, 9), "com.instagram.android", RESUMED, "com.instagram.mainactivity.InstagramMainActivity"),
            Event(t(23, 2, 2, 10), "com.android.settings", STOPPED, "com.vivo.settings.secret.PasswordActivity"),
            Event(t(23, 2, 5, 0), "com.android.settings", RESUMED, "com.vivo.settings.secret.PasswordActivityMultiWindow"),
            Event(t(23, 2, 5, 2), "com.android.settings", STOPPED, "com.vivo.settings.secret.PasswordActivityMultiWindow"),
            Event(t(23, 2, 22, 44), "com.android.launcher3", RESUMED, "com.android.launcher3.Launcher"),
            Event(t(23, 2, 22, 45), "com.instagram.android", STOPPED, "com.instagram.mainactivity.InstagramMainActivity"),
        ), t(23, 3), zone, vivoHome, untilStopped = true).getValue("2026-11-23")
        assertEquals((20 * 60 + 35).toDouble(), d.apps.getValue("com.instagram.android"), 0.01)
        // The App Lock prompt itself counts as Settings, like any screen.
        assertTrue((d.apps["com.android.settings"] ?: 0.0) > 0)
    }

    @Test fun theRealHomeScreenStillEndsEverything() {
        val d = ScreenTimeCompute.compute(listOf(
            Event(t(23, 10), "a", RESUMED, "A"),
            Event(t(23, 10, 5), "com.android.launcher3", RESUMED, "com.android.launcher3.Launcher"),
            Event(t(23, 10, 30), "a", STOPPED, "A"),
        ), t(23, 11), zone, vivoHome, untilStopped = true).getValue("2026-11-23")
        assertEquals(300.0, d.apps.getValue("a"), 0.01)
    }

    @Test fun homeMatching() {
        assertTrue(ScreenTimeCompute.isHome(Event(0, "com.android.systemui", RESUMED, "Anything"), vivoHome))
        assertTrue(ScreenTimeCompute.isHome(Event(0, "com.android.settings", RESUMED, "com.android.settings.FallbackHome"), vivoHome))
        assertTrue(!ScreenTimeCompute.isHome(Event(0, "com.android.settings", RESUMED, "com.vivo.settings.secret.PasswordActivity"), vivoHome))
    }
}
