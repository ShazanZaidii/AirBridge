package com.shazan.airbridge.net.link

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.Identity
import com.shazan.airbridge.net.PairStore
import com.shazan.airbridge.net.Protocol
import com.shazan.airbridge.net.Session
import com.shazan.airbridge.net.WsClient
import com.shazan.airbridge.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Direct link upgrades, Android side of Phase A0 (PROTOCOL §10.1): the Windows peer hosts a
 * `wfd_legacy` group, this phone joins it and dials the peer's WebSocket over it; the new session
 * replaces the LAN one (make-before-break) and the LAN session is redialled when the link ends.
 * Signalling (`link_*`) travels over the existing ready session.
 *
 * Network half only. Every [LinkMachine] call runs on [Hub.scheduler] (single thread).
 */
object LinkManager {
    const val TAG = "AirBridge.Link"
    private const val PREFS = "AirBridgeLink"
    /** Automatic upgrade of large transfers (Send fast always asks). */
    const val PREF_AUTO = "LINK_AUTO"
    private const val KEY_GEN = "gen:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val env = AndroidEnv()
    private val machine = LinkMachine(env)

    private val _states = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    /** Peers with a link in progress or up. */
    val states: StateFlow<Map<String, LinkState>> = _states

    private const val LOG_CAP = 300
    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Recent `AirBridge.Link …` lines, oldest first (debug screen). */
    val log: StateFlow<List<String>> = _log
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val prefs get() = Hub.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoUpgrade: Boolean
        get() = prefs.getBoolean(PREF_AUTO, true)
        set(v) { prefs.edit().putBoolean(PREF_AUTO, v).apply() }

    /** `gen` last offered by [peerId] (0 if never): the debug screen derives the same network. */
    fun lastGen(peerId: String): Int = prefs.getInt(KEY_GEN + peerId, 0)

    /** Credentials this phone would use to join [peerId]'s group (host = peer, client = this phone). */
    fun credsFor(peerId: String, gen: Int = lastGen(peerId)): WfdCreds? {
        val k = PairStore.key(peerId) ?: return null
        return try { LinkCreds.wfd(k, hostId = peerId, clientId = Identity.id, gen = gen) } catch (_: Exception) { null }
    }

    private fun onMachine(block: () -> Unit) {
        Hub.scheduler.execute {
            try { block() } catch (e: Exception) { Log.w(TAG, "link machine", e) }
            publish()
        }
    }

    private fun publish() {
        _states.value = machine.activePeers().associateWith { machine.state(it) }
    }

    internal fun appendLog(line: String) {
        Log.i(TAG, line)
        val stamped = "${timeFmt.format(Date())} $TAG $line"
        synchronized(this) { _log.value = (_log.value + stamped).takeLast(LOG_CAP) }
    }

    fun clearLog() = synchronized(this) { _log.value = emptyList() }

    // ---- entry points (Hub, FileTransfer, UI) ----

    /** A ready session received a `link_*` message. Unknown `link_*` types are ignored (rule 1). */
    fun onMessage(s: Session, t: String, m: JSONObject) {
        val peer = s.peerId ?: return
        val msg = LinkWire.parse(t, m) ?: run { Log.d(TAG, "ignored $t"); return }
        onMachine { machine.onMessage(peer, msg) }
    }

    /** Hub: [s] became ready (after retiring any older session for the same peer). */
    fun onSessionReady(s: Session) {
        val peer = s.peerId ?: return
        val isLink = s.link.kind == WsClient.KIND_WFD
        onMachine { if (isLink) machine.onLinkSessionReady(peer) else machine.onLanReady(peer) }
    }

    /** Ask [peerId] for a link without holding anything (`why` = "mirror" | "user"). */
    fun request(peerId: String, why: String = "user", bytes: Long = 0) = onMachine { machine.request(peerId, why, bytes) }

    /** End the peer's link (user "Release"). */
    fun end(peerId: String, reason: String = "user") = onMachine { machine.end(peerId, reason) }

    /** Service stopping: release every network request. */
    fun stopAll() = onMachine { machine.endAll("user") }

    /**
     * Hold-until-upgrade for an outgoing `xfer_offer` (§10.1). Blocking; call on a transfer thread.
     * Returns the session to send on: the new link session once it is ready, else the existing one
     * (or whichever ready session the peer has after a fallback).
     */
    fun prepareSend(s: Session, total: Long, fast: Boolean, onHolding: (() -> Unit)? = null): Session {
        val peer = s.peerId ?: return s
        val canJoin = WfdJoiner.supported(Hub.app)
        if (!LinkPolicy.shouldUpgrade("xfer", total, fast, s.peerCaps, s.link.kind, canJoin, autoUpgrade)) return s
        onHolding?.invoke()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        onMachine { machine.request(peer, "xfer", total) { r -> ok.set(r); latch.countDown() } }
        if (!latch.await(LinkPolicy.LINK_READY_MS + 5_000, TimeUnit.MILLISECONDS)) appendLog("lid=? ev=xfer_hold_timeout")
        if (ok.get()) Hub.readySession(peer)?.let { return it }
        if (!s.isClosed) return s
        // The old session went away meanwhile (e.g. joining dropped the home Wi-Fi): wait for the LAN to return.
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            Hub.readySession(peer)?.let { return it }
            Thread.sleep(250)
        }
        return s
    }

    // ---- the real environment ----

    private class AndroidEnv : LinkEnv<Network> {
        private val joiner by lazy { WfdJoiner(Hub.app) }

        override fun now() = SystemClock.elapsedRealtime()

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val f = Hub.scheduler.schedule({
                try { task() } catch (e: Exception) { Log.w(TAG, "link timer", e) }
                publish()
            }, delayMs, TimeUnit.MILLISECONDS)
            return Cancellable { f.cancel(false) }
        }

        override fun send(peerId: String, msg: LinkMsg): Boolean = Hub.send(peerId, LinkWire.toJson(msg))

        override fun joinUnsupported(): String? =
            WfdJoiner.unsupportedReason(Hub.app) ?: if (!WfdJoiner.wifiOn(Hub.app)) "radio_off" else null

        override fun creds(peerId: String, offer: LinkMsg.Offer): WfdCreds? {
            if (offer.cred == "inline") {
                val ssid = offer.ssid ?: return null
                val psk = offer.psk ?: return null
                return WfdCreds(ssid, psk, offer.gen)
            }
            if (offer.gen !in 0..255) return null
            prefs.edit().putInt(KEY_GEN + peerId, offer.gen).apply()
            return credsFor(peerId, offer.gen)
        }

        override fun join(
            peerId: String, lid: Int, creds: WfdCreds, timeoutMs: Long,
            onResult: (JoinOutcome<Network>) -> Unit, onLost: () -> Unit,
        ): Cancellable {
            val joined = AtomicReference<WfdJoiner.Joined?>(null)
            val cancelled = AtomicBoolean(false)
            val job = scope.launch {
                val outcome: JoinOutcome<Network> = try {
                    val j = joiner.join(creds, timeoutMs)
                    joined.set(j)
                    if (cancelled.get()) { j.release(); return@launch }
                    j.onLost { Hub.scheduler.execute { onLost(); publish() } }
                    JoinOutcome.Joined(j.network, j.localAddr, j.gateways, j.freq, j.sta, j.dialogShown, j.ms, android.os.Build.VERSION.SDK_INT)
                } catch (e: JoinFailed) {
                    JoinOutcome.Failed(e.reason, e.detail)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JoinOutcome.Failed("join_failed", "${e.javaClass.simpleName}: ${e.message}")
                }
                Hub.scheduler.execute { onResult(outcome); publish() }
            }
            return Cancellable {
                cancelled.set(true)
                job.cancel()
                joined.get()?.release()
            }
        }

        override fun dial(peerId: String, host: String, port: Int, network: Network, onEnd: (DialEnd) -> Unit) {
            val started = WsClient.dialVia(peerId, host, port, network) { end ->
                Hub.scheduler.execute {
                    if (end.everReady) {
                        machine.onLinkSessionEnded(peerId)
                        // Backstop: whatever happened to the link bookkeeping, the LAN must come back.
                        if (machine.state(peerId) == LinkState.IDLE) WsClient.resumeAfterLink(peerId, overLink = false)
                    } else onEnd(DialEnd(end.connected, end.eperm, end.why))
                    publish()
                }
            }
            if (!started) Hub.scheduler.execute { onEnd(DialEnd(false, false, "service not running")); publish() }
        }

        override fun closeLinkSession(peerId: String) = WsClient.closeLink(peerId, bye = "replaced")
        override fun resumeLan(peerId: String, overLink: Boolean) = WsClient.resumeAfterLink(peerId, overLink)
        override fun lanReady(peerId: String) = Hub.readySession(peerId)?.let { it.link.kind != WsClient.KIND_WFD } == true
        override fun linkReady(peerId: String) = Hub.readySession(peerId)?.link?.kind == WsClient.KIND_WFD
        override fun traffic(peerId: String) = Hub.readySession(peerId)?.trafficBytes ?: 0L
        override fun busy(peerId: String) = Hub.readySession(peerId)?.xfer?.isBusy() == true
        override fun log(line: String) = appendLog(line)
        override fun failed(peerId: String, reason: String, byPeer: Boolean, local: Boolean) {
            // Only for links this phone asked for (a held transfer falls back to the existing session).
            if (local && reason != "no_session") Hub.toast("${LinkPolicy.failText(reason)}. Sending over the current connection.")
        }
    }
}

/** JSON ↔ [LinkMsg] for the §10.1 messages. */
object LinkWire {
    fun parse(t: String, m: JSONObject): LinkMsg? {
        if (!m.has("lid")) return null
        val lid = m.optInt("lid", -1)
        if (lid < 0) return null
        return when (t) {
            "link_request" -> LinkMsg.Request(lid, m.str("why") ?: "user", m.optLong("bytes", 0), strings(m.optJSONArray("types")))
            "link_offer" -> LinkMsg.Offer(
                lid = lid,
                type = m.str("type") ?: "",
                cred = m.str("cred") ?: "derived",
                gen = m.optInt("gen", 0),
                ssid = m.str("ssid"),
                psk = m.str("psk"),
                sec = m.str("sec") ?: "wpa2",
                band = m.str("band") ?: "unknown",
                // §10.1: a list; PROXIMITY_DESIGN's earlier draft used a single string.
                addr = m.optJSONArray("addr")?.let { strings(it) } ?: listOfNotNull(m.str("addr")),
                port = m.optInt("port", 0),
                ttl = m.optInt("ttl", LinkPolicy.TTL_DEFAULT_S),
            )
            "link_ready" -> LinkMsg.Ready(lid, m.str("addr"), m.optInt("freq", 0), m.str("sta") ?: "none")
            "link_fail" -> LinkMsg.Fail(lid, m.str("reason") ?: "join_failed")
            "link_done" -> LinkMsg.Done(lid, m.str("reason") ?: "user")
            else -> null
        }
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }

    fun toJson(msg: LinkMsg): JSONObject = when (msg) {
        is LinkMsg.Request -> Protocol.msg("link_request").put("lid", msg.lid).put("why", msg.why).put("bytes", msg.bytes)
            .put("types", JSONArray(msg.types))
        is LinkMsg.Offer -> Protocol.msg("link_offer").put("lid", msg.lid).put("type", msg.type).put("cred", msg.cred)
            .put("gen", msg.gen).put("sec", msg.sec).put("band", msg.band).put("addr", JSONArray(msg.addr))
            .put("port", msg.port).put("ttl", msg.ttl).apply { msg.ssid?.let { put("ssid", it) } }
        is LinkMsg.Ready -> Protocol.msg("link_ready").put("lid", msg.lid).put("freq", msg.freq).put("sta", msg.sta)
            .apply { msg.addr?.let { put("addr", it) } }
        is LinkMsg.Fail -> Protocol.withReason("link_fail", msg.reason).put("lid", msg.lid)
        is LinkMsg.Done -> Protocol.withReason("link_done", msg.reason).put("lid", msg.lid)
    }
}
