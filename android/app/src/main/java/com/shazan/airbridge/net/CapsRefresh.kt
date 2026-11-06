package com.shazan.airbridge.net

import android.util.Log
import com.shazan.airbridge.net.link.LinkState
import com.shazan.airbridge.net.link.LinkManager
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps `hello.caps` honest (§3.1, §8.1): when either accessibility service (core → `ctl_target`,
 * typing → `type_target`) connects or disconnects, the ready LAN sessions whose `hello` advertised
 * a different set of those caps are closed with a
 * plain `bye shutdown` (never `replaced`, so neither side pauses) and [WsClient] redials them after
 * its usual 1 s backoff; the new `hello` carries the current caps.
 *
 * Storm guards: changes are debounced, only mismatched sessions are touched (a fresh session that
 * already reports the current value is left alone), a peer is refreshed at most once per
 * [Policy.MIN_GAP_MS], and a session moving a file, a link upgrade or a stream is deferred.
 */
object CapsRefresh {
    private const val TAG = "AirBridge.Caps"
    const val BYE_REASON = "shutdown"

    /** Pure decision logic (CapsRefreshTest). */
    object Policy {
        const val DEBOUNCE_MS = 1_500L
        const val MIN_GAP_MS = 10_000L
        const val BUSY_RETRY_MS = 5_000L

        data class Peer(
            val id: String,
            /** A LAN (WebSocket) session that this side dialled and will redial. */
            val lan: Boolean,
            /** The runtime caps (`ctl_target`, `type_target`) our `hello` carried; null = no hello sent. */
            val advertised: Set<String>?,
            /** File transfer, link upgrade or media stream in progress: don't cut it. */
            val busy: Boolean,
            /** When this peer was last reconnected for a caps change, or null. */
            val lastRefreshAt: Long?,
        )

        data class Decision(val reconnect: List<String>, val recheckInMs: Long?)

        fun decide(peers: List<Peer>, capsNow: Set<String>, now: Long): Decision {
            val go = ArrayList<String>()
            var recheck: Long? = null
            fun later(ms: Long) { recheck = minOf(recheck ?: ms, ms) }
            for (p in peers) {
                if (!p.lan || p.advertised == null || p.advertised == capsNow) continue
                val since = p.lastRefreshAt?.let { now - it }
                when {
                    p.busy -> later(BUSY_RETRY_MS)
                    since != null && since in 0 until MIN_GAP_MS -> later(MIN_GAP_MS - since)
                    else -> go += p.id
                }
            }
            return Decision(go, recheck)
        }
    }

    private val lastRefresh = HashMap<String, Long>()
    private val lastTraffic = HashMap<String, Long>()
    private var pending: ScheduledFuture<*>? = null

    /** An accessibility service connected/disconnected, or a session became ready: re-check soon. */
    fun poke(delayMs: Long = Policy.DEBOUNCE_MS) {
        try {
            synchronized(this) {
                pending?.cancel(false)
                pending = Hub.scheduler.schedule({ run() }, delayMs, TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) { Log.w(TAG, "poke", e) }
    }

    private fun run() {
        try {
            val now = System.currentTimeMillis()
            val capsNow = Protocol.dynamicCaps()
            val sessions = Hub.readySessions().filter { it.isReady }
            val peers = synchronized(this) {
                sessions.mapNotNull { s ->
                    val id = s.peerId ?: return@mapNotNull null
                    // Bytes moving since the last check (video, file chunks) = something is streaming.
                    val bytes = s.trafficBytes
                    val streaming = lastTraffic.put(id, bytes)?.let { it != bytes } ?: (bytes > 0)
                    val linkBusy = LinkManager.states.value[id]?.let { it != LinkState.IDLE } == true
                    Policy.Peer(
                        id = id,
                        lan = s.role == Role.CLIENT && s.link.kind == "lan",
                        advertised = s.advertisedDynamic,
                        busy = s.xfer.isBusy() || linkBusy || streaming,
                        lastRefreshAt = lastRefresh[id],
                    )
                }
            }
            val d = Policy.decide(peers, capsNow, now)
            for (id in d.reconnect) {
                val s = sessions.firstOrNull { it.peerId == id } ?: continue
                synchronized(this) { lastRefresh[id] = now }
                Log.i(TAG, "runtime caps now $capsNow: reconnecting ${s.peerName} for a fresh hello")
                s.close(Outcome.Lost("caps changed"), BYE_REASON)
            }
            d.recheckInMs?.let { poke(it) }
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
        }
    }
}
