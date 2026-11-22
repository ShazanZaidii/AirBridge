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
 * one by one, the way Android's own Digital Wellbeing does, or that app would stop counting at every tap. The home screen and the system UI are not counted, like Android's own Digital Wellbeing.
 */
object ScreenTimeCompute {
    // The UsageEvents.Event constants this needs.
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    /** The longest single stretch counted at once (a missed "screen off" never counts as a whole night). */
    const val MAX_SEGMENT_MS = 4L * 3600 * 1000

    /** [cls] is the activity (screen) class the event is about. */
    data class Event(val time: Long, val pkg: String, val type: Int, val cls: String = "")

    class Day {
        var seconds = 0.0
        val hours = DoubleArray(24)
        val apps = HashMap<String, Double>()
    }

    /** Screen time per day ("yyyy-MM-dd" in [zone]) from [events]. An app still in front at [now] counts up to [now]. */
    fun compute(events: List<Event>, now: Long, zone: ZoneId, ignore: Set<String>): Map<String, Day> {
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
                    if (e.pkg in ignore) { close(e.time); showing.clear(); continue } // home screen: nothing is in use
                    showing.getOrPut(e.pkg) { mutableSetOf() }.add(e.cls)
                    if (curPkg != e.pkg) { close(e.time); curPkg = e.pkg; curStart = e.time }
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

    data class AppOut(val id: String, val name: String, val seconds: Long)
    data class DayOut(val day: String, val seconds: Long, val hours: List<Long>?, val apps: List<AppOut>?)

    /**
     * What goes in the `screen_time` message: the newest [maxDays] days, today ([todayKey]) with its 24 hours and up to
     * [maxApps] apps, most used first. [name] gives an app's label. Plain data, so it is checkable without the Android runtime.
     */
    fun payload(days: Map<String, Day>, todayKey: String, name: (String) -> String, maxDays: Int = 7, maxApps: Int = 20): List<DayOut> =
        days.keys.sorted().takeLast(maxDays).map { key ->
            val d = days.getValue(key)
            if (key != todayKey) DayOut(key, Math.round(d.seconds), null, null)
            else DayOut(key, Math.round(d.seconds), d.hours.map { Math.round(it) },
                d.apps.entries.map { it.key to Math.round(it.value) }.filter { it.second > 0 }
                    .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
                    .take(maxApps).map { (pkg, sec) -> AppOut(pkg, name(pkg), sec) })
        }

    /** The `screen_time` message for [payload]. */
    fun message(days: Map<String, Day>, todayKey: String, name: (String) -> String, maxDays: Int = 7, maxApps: Int = 20): JSONObject {
        val arr = JSONArray()
        for (d in payload(days, todayKey, name, maxDays, maxApps)) {
            val o = JSONObject().put("day", d.day).put("s", d.seconds)
            d.hours?.let { o.put("hours", JSONArray(it)) }
            d.apps?.let { list -> o.put("apps", JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("s", it.seconds)) } }) }
            arr.put(o)
        }
        return JSONObject().put("t", "screen_time").put("days", arr)
    }

    /** Today's top apps, most used first (whose icons are worth sending). */
    fun topApps(days: Map<String, Day>, todayKey: String, n: Int): List<String> =
        days[todayKey]?.apps?.entries?.sortedByDescending { it.value }?.take(n)?.map { it.key } ?: emptyList()
}
