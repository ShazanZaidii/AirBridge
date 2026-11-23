package com.shazan.airbridge.phone.screentime

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Turns Android's app usage events into screen time per day, per hour and per app (docs/protocol.md §15).
 * Pure Kotlin (no Android types), so ScreenTimeComputeTest checks it on the JVM.
 *
 * An app counts while any of its screens (activities) is showing: from the first one coming to the front until the last one
 * leaves, the screen turns off, the lock screen shows or the phone shuts down. Opening another screen inside the same app
 * (a post, then its comments) is reported as "new screen resumed" and only then "old screen stopped", so screens are tracked
 * one by one, or that app would stop counting at every tap. The home screen and the system UI are not counted.
 *
 * With [compute]'s `untilStopped` (Android 10 and newer, which report when a screen stops being visible) an app counts while
 * it is *visible*, until its last screen stops, not only while it has focus. That is how Android's own Digital Wellbeing
 * counts: an app stays visible (and counted) under a permission dialog, a share sheet, the assistant or another app's small
 * window on top. Two apps visible at once (split screen) both count, as in Digital Wellbeing.
 */
object ScreenTimeCompute {
    // The UsageEvents.Event constants this needs.
    const val RESUMED = 1
    const val PAUSED = 2
    const val NOTIFICATION = 12
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val STOPPED = 23
    const val DESTROYED = 24
    const val DEVICE_SHUTDOWN = 26

    /** The longest single stretch counted at once (a missed "screen off" never counts as a whole night). */
    const val MAX_SEGMENT_MS = 4L * 3600 * 1000

    /** [cls] is the activity (screen) class the event is about. */
    data class Event(val time: Long, val pkg: String, val type: Int, val cls: String = "")

    class Day {
        var seconds = 0.0
        val hours = DoubleArray(24)
        val apps = HashMap<String, Double>()
        /** Times each app was opened (came on screen after not being on screen). */
        val opens = HashMap<String, Int>()
        /** Notifications each app showed. */
        val notifs = HashMap<String, Int>()
        /** Times the phone was unlocked ("pickups"). */
        var unlocks = 0
        /** When the phone was first used that day: seconds after midnight. */
        var first: Int? = null
    }

    /**
     * Screen time per day ("yyyy-MM-dd" in [zone]) from [events]. An app still showing at [now] counts up to [now].
     * [untilStopped]: count apps while visible (needs the "stopped" events of Android 10+), else while in focus.
     */
    fun compute(events: List<Event>, now: Long, zone: ZoneId, ignore: Set<String>, untilStopped: Boolean = false): Map<String, Day> {
        val days = if (untilStopped) computeVisible(events, now, zone, ignore) else computeFocused(events, now, zone, ignore)
        for (e in events) when (e.type) {
            NOTIFICATION -> if (e.pkg.isNotEmpty() && e.pkg !in ignore) day(days, e.time, zone).let { it.notifs[e.pkg] = (it.notifs[e.pkg] ?: 0) + 1 }
            KEYGUARD_HIDDEN -> day(days, e.time, zone).let { it.unlocks++; firstUse(it, e.time, zone) }
        }
        return days
    }

    /**
     * Whether [e] is the home screen (or system UI) coming up, which means nothing is in use. [ignore] holds whole packages
     * ("com.android.systemui") or single screens ("pkg/full.ClassName"). Home apps are listed by screen, not package: vivo
     * lists Settings as a backup home (its FallbackHome screen), and its App Lock screen is also part of Settings; matching
     * the whole package made every App Lock prompt over Instagram look like "went home" and drop the rest of that stretch.
     */
    fun isHome(e: Event, ignore: Set<String>): Boolean = e.pkg in ignore || "${e.pkg}/${e.cls}" in ignore

    private fun day(days: MutableMap<String, Day>, t: Long, zone: ZoneId): Day =
        days.getOrPut(Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString()) { Day() }

    private fun firstUse(d: Day, t: Long, zone: ZoneId) {
        val sec = Instant.ofEpochMilli(t).atZone(zone).toLocalTime().toSecondOfDay()
        if (d.first == null || sec < d.first!!) d.first = sec
    }

    /** [pkg] came on screen at [t]. */
    private fun opened(days: MutableMap<String, Day>, pkg: String, t: Long, zone: ZoneId) {
        val d = day(days, t, zone)
        d.opens[pkg] = (d.opens[pkg] ?: 0) + 1
        firstUse(d, t, zone)
    }

    /** Android 10+: each app counts from its first screen showing until its last screen stops (apps can overlap). */
    private fun computeVisible(events: List<Event>, now: Long, zone: ZoneId, ignore: Set<String>): HashMap<String, Day> {
        val days = HashMap<String, Day>()
        val visible = HashMap<String, MutableSet<String>>()
        val since = HashMap<String, Long>()
        fun close(pkg: String, at: Long) {
            val start = since.remove(pkg) ?: return
            visible.remove(pkg)
            add(days, pkg, start, minOf(at, start + MAX_SEGMENT_MS), zone)
        }
        fun closeAll(at: Long) { for (p in since.keys.toList()) close(p, at); visible.clear() }
        for (e in events.sortedBy { it.time }) {
            when (e.type) {
                RESUMED -> {
                    if (e.pkg.isEmpty()) continue
                    if (isHome(e, ignore)) { closeAll(e.time); continue } // home screen or recents: nothing is in use
                    visible.getOrPut(e.pkg) { mutableSetOf() }.add(e.cls)
                    if (e.pkg !in since) { since[e.pkg] = e.time; opened(days, e.pkg, e.time, zone) }
                }
                STOPPED, DESTROYED -> {
                    val set = visible[e.pkg] ?: continue
                    set.remove(e.cls)
                    if (set.isEmpty()) close(e.pkg, e.time) // the app's last screen is gone
                }
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> closeAll(e.time)
            }
        }
        closeAll(now)
        return days
    }

    /** Older Android: the app in focus counts, one at a time, until its last screen pauses. */
    private fun computeFocused(events: List<Event>, now: Long, zone: ZoneId, ignore: Set<String>): HashMap<String, Day> {
        val days = HashMap<String, Day>()
        /** Screens of each app that are showing now. */
        val showing = HashMap<String, MutableSet<String>>()
        var curPkg: String? = null
        var curStart = 0L
        fun close(at: Long) {
            val pkg = curPkg ?: return
            add(days, pkg, curStart, minOf(at, curStart + MAX_SEGMENT_MS), zone)
            curPkg = null
        }
        for (e in events.sortedBy { it.time }) {
            when (e.type) {
                RESUMED -> {
                    if (e.pkg.isEmpty()) continue
                    if (isHome(e, ignore)) { close(e.time); showing.clear(); continue } // home screen: nothing is in use
                    showing.getOrPut(e.pkg) { mutableSetOf() }.add(e.cls)
                    if (curPkg != e.pkg) { close(e.time); curPkg = e.pkg; curStart = e.time; opened(days, e.pkg, e.time, zone) }
                }
                PAUSED, STOPPED -> {
                    val set = showing[e.pkg] ?: continue
                    set.remove(e.cls)
                    if (set.isEmpty()) {
                        showing.remove(e.pkg)
                        if (curPkg == e.pkg) close(e.time) // the app's last screen left
                    }
                }
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> { close(e.time); showing.clear() }
            }
        }
        close(now)
        return days
    }

    private fun add(days: HashMap<String, Day>, pkg: String, start: Long, end: Long, zone: ZoneId) {
        var cur = start
        while (cur < end) {
            val z = Instant.ofEpochMilli(cur).atZone(zone)
            val hourEnd = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val pieceEnd = minOf(end, hourEnd)
            val secs = (pieceEnd - cur) / 1000.0
            val day = days.getOrPut(z.toLocalDate().toString()) { Day() }
            day.seconds += secs
            day.hours[z.hour] += secs
            day.apps[pkg] = (day.apps[pkg] ?: 0.0) + secs
            cur = pieceEnd
        }
    }

    data class AppOut(val id: String, val name: String, val seconds: Long, val opens: Int = 0, val notifs: Int = 0, val cat: String? = null)
    data class DayOut(
        val day: String, val seconds: Long, val hours: List<Long>?, val apps: List<AppOut>?,
        val unlocks: Int = 0, val notifs: Int = 0, val first: Int? = null,
    )

    /**
     * What goes in the `screen_time` message: the newest [maxDays] days, each with its 24 hours, unlocks, notifications, first
     * use and up to [maxApps] apps (most used first, then the ones that only sent notifications). [name] gives an app's label,
     * [category] its kind ("social", "games", …) when known. Plain data, so it is checkable without the Android runtime.
     */
    fun payload(
        days: Map<String, Day>, todayKey: String, name: (String) -> String, maxDays: Int = 7, maxApps: Int = 20,
        category: (String) -> String? = { null },
    ): List<DayOut> =
        days.keys.filter { it <= todayKey }.sorted().takeLast(maxDays).map { key ->
            val d = days.getValue(key)
            val ids = (d.apps.keys.filter { Math.round(d.apps.getValue(it)) > 0 } + d.notifs.keys).distinct()
            val apps = ids.map { pkg -> AppOut(pkg, "", Math.round(d.apps[pkg] ?: 0.0), d.opens[pkg] ?: 0, d.notifs[pkg] ?: 0) }
                .sortedWith(compareByDescending<AppOut> { it.seconds }.thenByDescending { it.notifs }.thenBy { it.id })
                .take(maxApps).map { it.copy(name = name(it.id), cat = category(it.id)) }
            DayOut(key, Math.round(d.seconds), d.hours.map { Math.round(it) }, apps, d.unlocks, d.notifs.values.sum(), d.first)
        }

    /** The `screen_time` message for [payload]. */
    fun message(
        days: Map<String, Day>, todayKey: String, name: (String) -> String, maxDays: Int = 7, maxApps: Int = 20,
        category: (String) -> String? = { null },
    ): JSONObject {
        val arr = JSONArray()
        for (d in payload(days, todayKey, name, maxDays, maxApps, category)) {
            val o = JSONObject().put("day", d.day).put("s", d.seconds).put("unlocks", d.unlocks).put("notifs", d.notifs)
            d.first?.let { o.put("first", it) }
            d.hours?.let { o.put("hours", JSONArray(it)) }
            d.apps?.let { list ->
                o.put("apps", JSONArray().apply {
                    list.forEach { a ->
                        val j = JSONObject().put("id", a.id).put("name", a.name).put("s", a.seconds).put("opens", a.opens).put("notifs", a.notifs)
                        a.cat?.let { j.put("cat", it) }
                        put(j)
                    }
                })
            }
            arr.put(o)
        }
        return JSONObject().put("t", "screen_time").put("days", arr)
    }

    /** Today's top apps, most used first (whose icons are worth sending). */
    /** The [n] most used apps over the days up to [todayKey] (whose icons the computer needs). */
    fun topApps(days: Map<String, Day>, todayKey: String, n: Int): List<String> {
        val total = HashMap<String, Double>()
        for ((k, d) in days) if (k <= todayKey) for ((pkg, s) in d.apps) total[pkg] = (total[pkg] ?: 0.0) + s
        return total.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(n).map { it.key }
    }

    /** Android's own app category (ApplicationInfo.category) as the protocol's word, or null when the app gives none. */
    fun categoryName(androidCategory: Int): String? = when (androidCategory) {
        0 -> "games"
        1, 2 -> "entertainment"
        3 -> "creativity"
        4 -> "social"
        5 -> "reading"
        6 -> "travel"
        7 -> "productivity"
        8 -> "utilities"
        else -> null
    }
}
