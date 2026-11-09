package com.shazan.airbridge.phone

/**
 * Rate limit for forwarded notifications (pure, unit-tested; not thread-safe, callers serialise).
 *
 * - Token buckets per app ([perApp] posts, one back every [appRefillMs]) and for the whole phone.
 * - A post that finds no token is parked: the newest version per key wins, and [due] hands it out once a
 *   token is back. Removals are never limited, and a removal drops a parked post of the same key.
 * - At most [maxParked] posts wait; beyond that the oldest parked one is dropped (a flood of a chatty app
 *   must not grow memory).
 */
class FloodGate<T>(
    private val perApp: Int = 6,
    private val appRefillMs: Long = 2_000,
    private val global: Int = 30,
    private val globalRefillMs: Long = 300,
    private val maxParked: Int = 60,
) {
    private class Bucket(var tokens: Double, var at: Long)

    private val apps = HashMap<String, Bucket>()
    private var all: Bucket? = null
    /** key → (app, item), insertion ordered (oldest first). */
    private val parked = LinkedHashMap<String, Pair<String, T>>()

    private fun refill(b: Bucket, cap: Int, every: Long, now: Long) {
        if (now > b.at) {
            b.tokens = minOf(cap.toDouble(), b.tokens + (now - b.at).toDouble() / every)
            b.at = now
        }
    }

    private fun bucketFor(app: String, now: Long): Bucket =
        apps.getOrPut(app) { Bucket(perApp.toDouble(), now) }.also { refill(it, perApp, appRefillMs, now) }

    private fun globalBucket(now: Long): Bucket =
        (all ?: Bucket(global.toDouble(), now).also { all = it }).also { refill(it, global, globalRefillMs, now) }

    private fun tryTake(app: String, now: Long): Boolean {
        val a = bucketFor(app, now)
        val g = globalBucket(now)
        if (a.tokens < 1.0 || g.tokens < 1.0) return false
        a.tokens -= 1.0; g.tokens -= 1.0
        return true
    }

    /** True = send [item] now. False = parked; call [due] later (see [nextDueInMs]). */
    fun offer(app: String, key: String, item: T, now: Long): Boolean {
        if (!parked.containsKey(key) && tryTake(app, now)) return true
        parked.remove(key)
        parked[key] = app to item
        while (parked.size > maxParked) parked.remove(parked.keys.first())
        return false
    }

    /** The notification was removed: nothing parked for it may be sent any more. */
    fun forget(key: String) { parked.remove(key) }

    fun clear() { parked.clear() }

    /** Parked posts that may go now, oldest first. */
    fun due(now: Long): List<T> {
        val out = ArrayList<T>()
        val it = parked.entries.iterator()
        while (it.hasNext()) {
            val (app, item) = it.next().value
            if (tryTake(app, now)) { out += item; it.remove() }
        }
        return out
    }

    /** How long until [due] can return something, or null when nothing is parked. */
    fun nextDueInMs(now: Long): Long? {
        if (parked.isEmpty()) return null
        val g = globalBucket(now)
        val gWait = if (g.tokens >= 1.0) 0L else ((1.0 - g.tokens) * globalRefillMs).toLong() + 1
        val aWait = parked.values.minOf { (app, _) ->
            val a = bucketFor(app, now)
            if (a.tokens >= 1.0) 0L else ((1.0 - a.tokens) * appRefillMs).toLong() + 1
        }
        return maxOf(gWait, aWait).coerceAtLeast(50)
    }

    val parkedCount: Int get() = parked.size
}
