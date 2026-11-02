package com.shazan.airbridge.net.link

/*
 * Per-peer Wi-Fi Direct link state machine (PROTOCOL §10.1, docs/WIFI_DIRECT_ANDROID_WINDOWS.md §3/§4/§6.4).
 * Pure Kotlin (no Android types) so it is unit-testable with a fake environment; LinkManager supplies
 * the real one (Wi-Fi join, WebSocket dial, Hub/WsClient hooks, the airbridge-net scheduler thread).
 *
 * Threading: every entry point and every environment callback must run on one thread.
 */

enum class LinkState { IDLE, REQUESTED, OFFERED, JOINING, DIALING, READY, TEARDOWN }

/** The `link_*` messages, decoupled from JSON (LinkWire converts). */
sealed class LinkMsg {
    abstract val lid: Int

    data class Request(override val lid: Int, val why: String, val bytes: Long, val types: List<String>) : LinkMsg()
    data class Offer(
        override val lid: Int,
        val type: String,
        val cred: String = "derived",
        val gen: Int = 0,
        val ssid: String? = null,
        /** Only with `cred:"inline"`. */
        val psk: String? = null,
        val sec: String = "wpa2",
        val band: String = "unknown",
        val addr: List<String> = emptyList(),
        val port: Int = 0,
        val ttl: Int = LinkPolicy.TTL_DEFAULT_S,
    ) : LinkMsg() {
        override fun toString() = "Offer(lid=$lid, type=$type, cred=$cred, gen=$gen, ssid=$ssid, band=$band, addr=$addr, port=$port, ttl=$ttl)"
    }
    data class Ready(override val lid: Int, val addr: String?, val freq: Int, val sta: String) : LinkMsg()
    data class Fail(override val lid: Int, val reason: String) : LinkMsg()
    data class Done(override val lid: Int, val reason: String) : LinkMsg()
}

fun interface Cancellable { fun cancel() }

sealed class JoinOutcome<out N> {
    data class Joined<N>(
        val network: N,
        /** This phone's address on the joined network (for `link_ready.addr`). */
        val localAddr: String?,
        /** DHCP server / default gateway of the joined network: fallback dial targets (§4 Addressing). */
        val gateways: List<String>,
        val freq: Int,
        /** "kept" | "dropped" | "none" (§10.1 `link_ready.sta`). */
        val sta: String,
        /** null = unknown. */
        val dialog: Boolean?,
        val ms: Long,
        val api: Int,
    ) : JoinOutcome<N>()
    data class Failed(val reason: String, val detail: String = "") : JoinOutcome<Nothing>()
}

/** How a link dial ended before (or instead of) becoming ready. */
data class DialEnd(val connected: Boolean, val eperm: Boolean, val why: String)

interface LinkEnv<N> {
    fun now(): Long
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    /** Send on the peer's current ready session (LAN before migration, the link session after). */
    fun send(peerId: String, msg: LinkMsg): Boolean
    /** null if this device can join a Wi-Fi Direct legacy group now; otherwise a §10.1 reason (`api_level`, `radio_off`). */
    fun joinUnsupported(): String?
    /** Credentials for [offer] (derived from K, or inline). null = no key for this peer. */
    fun creds(peerId: String, offer: LinkMsg.Offer): WfdCreds?
    /** Start joining. [onResult] once; [onLost] after a successful join when the network goes away. Cancel = release the request. */
    fun join(peerId: String, lid: Int, creds: WfdCreds, timeoutMs: Long, onResult: (JoinOutcome<N>) -> Unit, onLost: () -> Unit): Cancellable
    /** Dial `ws://host:port/v2` bound to [network]. Success is reported through [LinkMachine.onLinkSessionReady]. */
    fun dial(peerId: String, host: String, port: Int, network: N, onEnd: (DialEnd) -> Unit)
    fun closeLinkSession(peerId: String)
    /** Clear the replaced-session pause and kick the LAN dialer. [overLink]: allowed while the link session is still ready (make-before-break back). */
    fun resumeLan(peerId: String, overLink: Boolean)
    /** A non-link ready session exists for the peer. */
    fun lanReady(peerId: String): Boolean
    /** The link session is ready. */
    fun linkReady(peerId: String): Boolean
    /** Monotonic count of bulk bytes on the peer's ready session. */
    fun traffic(peerId: String): Long
    /** A file transfer is in progress with the peer. */
    fun busy(peerId: String): Boolean
    fun log(line: String)
}

class LinkMachine<N>(private val env: LinkEnv<N>) {

    private inner class Link(val peer: String, val lid: Int, val local: Boolean) {
        val startedAt = env.now()
        var state = LinkState.IDLE
        var offer: LinkMsg.Offer? = null
        var joined: JoinOutcome.Joined<N>? = null
        var joinHandle: Cancellable? = null
        val timers = mutableListOf<Cancellable>()
        var candidates: List<String> = emptyList()
        var port = 0
        var attempt = 0
        var expiresAt = 0L
        var lastTraffic = -1L
        var lastActiveAt = 0L
        var readyAt = 0L
        var doneReason = ""
        var notifyDone = false
        var waitingLan = false
        var finished = false
        val holders = mutableListOf<(Boolean) -> Unit>()
        fun ms() = env.now() - startedAt
    }

    private val links = HashMap<String, Link>()
    private val nextLid = HashMap<String, Int>()

    fun state(peerId: String): LinkState = links[peerId]?.state ?: LinkState.IDLE
    fun lid(peerId: String): Int? = links[peerId]?.lid
    fun activePeers(): Set<String> = links.keys.toSet()

    /** Wraps a callback so it is dropped once [l] is no longer the peer's current link. */
    private fun <T> guard(l: Link, f: (T) -> Unit): (T) -> Unit = { v -> if (links[l.peer] === l) f(v) }
    private fun guard0(l: Link, f: () -> Unit): () -> Unit = { if (links[l.peer] === l) f() }

    private fun log(l: Link, ev: String, extra: String = "") {
        val first = ev == "request" || ev == "offer"
        val head = if (first) "lid=${l.lid} peer=${l.peer.take(8)} type=wfd_legacy" else "lid=${l.lid}"
        env.log("$head ev=$ev ms=${l.ms()}" + (if (extra.isNotEmpty()) " $extra" else ""))
    }

    // ---- phone-initiated (§10.1 link_request) ----

    /**
     * Ask [peerId] to host a link. [onSettled] fires once: true when the new session is ready, false
     * when the link failed (fall back to the existing session). Joins an upgrade already in progress.
     * Returns false (and settles false) if no upgrade can be attempted.
     */
    fun request(peerId: String, why: String, bytes: Long, onSettled: ((Boolean) -> Unit)? = null): Boolean {
        links[peerId]?.let { l ->
            return when (l.state) {
                LinkState.READY -> { onSettled?.invoke(true); true }
                LinkState.TEARDOWN -> { onSettled?.invoke(false); false }
                else -> { onSettled?.let { l.holders.add(it) }; true }
            }
        }
        env.joinUnsupported()?.let { reason ->
            env.log("lid=- peer=${peerId.take(8)} type=wfd_legacy ev=request_skipped reason=$reason")
            onSettled?.invoke(false)
            return false
        }
        val lid = (nextLid[peerId] ?: 1).also { nextLid[peerId] = it + 1 }
        val l = Link(peerId, lid, local = true)
        l.state = LinkState.REQUESTED
        links[peerId] = l
        onSettled?.let { l.holders.add(it) }
        log(l, "request", "why=$why bytes=$bytes")
        armDeadline(l)
        if (!env.send(peerId, LinkMsg.Request(lid, why, bytes, listOf(TYPE_WFD_LEGACY)))) {
            fail(l, "no_session", notifyPeer = false)
            return false
        }
        return true
    }

    // ---- incoming messages ----

    fun onMessage(peerId: String, msg: LinkMsg) {
        val l = links[peerId]
        when (msg) {
            // A0: this phone never hosts (wfd_go is T2, not built yet).
            is LinkMsg.Request -> {
                env.log("lid=${msg.lid} peer=${peerId.take(8)} type=${msg.types.joinToString(",")} ev=request_in reason=no_go_support")
                env.send(peerId, LinkMsg.Fail(msg.lid, "no_go_support"))
            }
            is LinkMsg.Offer -> onOffer(peerId, msg)
            is LinkMsg.Ready -> Unit // only the GO side (wfd_go, T2) receives link_ready
            is LinkMsg.Fail -> if (l != null && l.lid == msg.lid) {
                if (l.state == LinkState.READY) done(l, "lost", notifyPeer = false) else fail(l, msg.reason, notifyPeer = false, remote = true)
            }
            is LinkMsg.Done -> if (l != null && l.lid == msg.lid) {
                if (l.state == LinkState.READY) done(l, msg.reason, notifyPeer = false) else fail(l, msg.reason, notifyPeer = false, remote = true)
            }
        }
    }

    private fun onOffer(peerId: String, o: LinkMsg.Offer) {
        var l = links[peerId]
        if (l != null && (l.lid != o.lid || l.state != LinkState.REQUESTED)) {
            env.log("lid=${o.lid} peer=${peerId.take(8)} type=${o.type} ev=offer_refused reason=busy active=${l.lid}")
            env.send(peerId, LinkMsg.Fail(o.lid, "busy"))
            return
        }
        val refuse = env.joinUnsupported()
            ?: when (o.type) {
                TYPE_WFD_LEGACY -> null
                TYPE_WFD_GO -> "no_go_support"
                else -> "join_failed"
            }
        if (refuse != null) {
            env.log("lid=${o.lid} peer=${peerId.take(8)} type=${o.type} ev=offer_refused reason=$refuse")
            env.send(peerId, LinkMsg.Fail(o.lid, refuse))
            l?.let { fail(it, refuse, notifyPeer = false) }
            return
        }
        if (l == null) {
            l = Link(peerId, o.lid, local = false)
            links[peerId] = l
            armDeadline(l)
        }
        l.offer = o
        l.state = LinkState.OFFERED
        log(l, "offer", "ssid=${o.ssid} band=${o.band} addr=${o.addr.joinToString(",")} port=${o.port} ttl=${o.ttl} gen=${o.gen} cred=${o.cred}")
        val creds = env.creds(peerId, o)
        if (creds == null) { fail(l, "join_failed", detail = "no_key"); return }
        if (o.cred == "derived" && o.ssid != null && o.ssid != creds.ssid) {
            fail(l, "join_failed", detail = "ssid_mismatch ours=${creds.ssid}")
            return
        }
        l.state = LinkState.JOINING
        log(l, "join_start", "ssid=${creds.ssid}")
        val link = l
        l.joinHandle = env.join(peerId, l.lid, creds, LinkPolicy.JOIN_TIMEOUT_MS,
            guard(link) { r -> onJoin(link, r) }, guard0(link) { onNetworkLost(link) })
    }

    private fun onJoin(l: Link, r: JoinOutcome<N>) {
        if (l.state != LinkState.JOINING) return
        when (r) {
            is JoinOutcome.Failed -> fail(l, r.reason, detail = r.detail)
            is JoinOutcome.Joined -> {
                l.joined = r
                log(l, "joined", "join_ms=${r.ms} freq=${r.freq} sta=${r.sta} dlg=${r.dialog ?: "unknown"} api=${r.api} ip=${r.localAddr}")
                // Best effort: without STA+STA the LAN session may already be gone; the peer also
                // accepts the new hello on its Wi-Fi Direct interface as readiness.
                env.send(l.peer, LinkMsg.Ready(l.lid, r.localAddr, r.freq, r.sta))
                val o = l.offer!!
                l.candidates = (o.addr + r.gateways).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                l.port = if (o.port in 1..65535) o.port else LinkPolicy.DEFAULT_PORT
                if (l.candidates.isEmpty()) { fail(l, "no_ip"); return }
                l.state = LinkState.DIALING
                dialNext(l)
            }
        }
    }

    private fun maxAttempts(l: Link) = maxOf(LinkPolicy.DIAL_ATTEMPTS, l.candidates.size)

    private fun dialNext(l: Link) {
        val host = l.candidates[l.attempt % l.candidates.size]
        l.attempt++
        log(l, "dial", "try=${l.attempt} addr=$host port=${l.port}")
        env.dial(l.peer, host, l.port, l.joined!!.network, guard(l) { e -> onDialEnd(l, e) })
    }

    private fun onDialEnd(l: Link, e: DialEnd) {
        if (l.state != LinkState.DIALING) return
        when {
            e.eperm -> fail(l, "vpn", detail = e.why)
            !e.connected && l.attempt < maxAttempts(l) -> {
                log(l, "dial_fail", "try=${l.attempt} why=\"${e.why}\"")
                l.timers += env.schedule(LinkPolicy.DIAL_RETRY_MS, guard0(l) { if (l.state == LinkState.DIALING) dialNext(l) })
            }
            else -> fail(l, "timeout", detail = e.why)
        }
    }

    // ---- session / network events ----

    /** The dialled link session completed its handshake (Hub has already retired the old session). */
    fun onLinkSessionReady(peerId: String) {
        val l = links[peerId] ?: return
        if (l.state != LinkState.DIALING) return
        l.timers.forEach { it.cancel() }; l.timers.clear()
        l.state = LinkState.READY
        val now = env.now()
        l.readyAt = now
        l.expiresAt = now + (l.offer?.ttl ?: LinkPolicy.TTL_DEFAULT_S).coerceAtLeast(1) * 1000L
        l.lastTraffic = env.traffic(peerId)
        l.lastActiveAt = now
        log(l, "ready")
        val hs = l.holders.toList(); l.holders.clear()
        if (hs.isNotEmpty()) log(l, "xfer_release", "held=${hs.size} via=link")
        hs.forEach { it(true) }
        scheduleTick(l)
    }

    /** A non-link session became ready (the LAN came back). */
    fun onLanReady(peerId: String) {
        val l = links[peerId] ?: return
        if (l.state == LinkState.TEARDOWN && l.waitingLan) finishDone(l)
    }

    /** The link session ended after having been ready. */
    fun onLinkSessionEnded(peerId: String) {
        val l = links[peerId] ?: return
        if (l.state == LinkState.READY) done(l, "lost", notifyPeer = true)
    }

    private fun onNetworkLost(l: Link) {
        when (l.state) {
            LinkState.READY -> done(l, "lost", notifyPeer = true)
            LinkState.JOINING, LinkState.DIALING -> fail(l, "join_failed", detail = "network_lost")
            else -> Unit
        }
    }

    /** User action ("Release", cancel) or service stop. */
    fun end(peerId: String, reason: String = "user") {
        val l = links[peerId] ?: return
        if (l.state == LinkState.READY) done(l, reason, notifyPeer = true) else fail(l, reason)
    }

    fun endAll(reason: String = "user") = links.keys.toList().forEach { end(it, reason) }

    // ---- timers ----

    private fun armDeadline(l: Link) {
        l.timers += env.schedule(LinkPolicy.LINK_READY_MS, guard0(l) {
            if (l.state != LinkState.READY && l.state != LinkState.TEARDOWN) fail(l, "timeout", detail = "link not ready in ${LinkPolicy.LINK_READY_MS / 1000}s")
        })
    }

    private fun scheduleTick(l: Link) {
        l.timers += env.schedule(LinkPolicy.TICK_MS, guard0(l) { tick(l) })
    }

    private fun tick(l: Link) {
        if (l.state != LinkState.READY) return
        val now = env.now()
        val t = env.traffic(l.peer)
        if (t != l.lastTraffic || env.busy(l.peer)) {
            l.lastTraffic = t
            l.lastActiveAt = now
            if (l.expiresAt - now < LinkPolicy.TTL_EXTEND_MS) l.expiresAt = now + LinkPolicy.TTL_EXTEND_MS
        }
        when {
            now - l.lastActiveAt >= LinkPolicy.IDLE_MS -> done(l, "idle", notifyPeer = true)
            now >= l.expiresAt -> done(l, "ttl", notifyPeer = true)
            else -> { l.timers.removeAll { true }; scheduleTick(l) }
        }
    }

    // ---- endings ----

    /** Before READY: tell the peer (unless it told us), release everything, fall back. */
    private fun fail(l: Link, reason: String, notifyPeer: Boolean = true, detail: String = "", remote: Boolean = false) {
        if (l.state == LinkState.TEARDOWN) return
        val prev = l.state
        l.state = LinkState.TEARDOWN
        l.timers.forEach { it.cancel() }; l.timers.clear()
        log(l, "fail", "reason=$reason state=${prev.name.lowercase()}" + (if (remote) " by=peer" else "") +
            (if (detail.isNotEmpty()) " detail=\"$detail\"" else ""))
        if (notifyPeer) env.send(l.peer, LinkMsg.Fail(l.lid, reason))
        l.joinHandle?.cancel(); l.joinHandle = null
        env.closeLinkSession(l.peer)
        if (links[l.peer] === l) links.remove(l.peer)
        env.resumeLan(l.peer, overLink = false)
        val hs = l.holders.toList(); l.holders.clear()
        if (hs.isNotEmpty()) log(l, "xfer_release", "held=${hs.size} via=existing")
        hs.forEach { it(false) }
        log(l, "released")
    }

    /** From READY. If the phone kept its home Wi-Fi, bring the LAN session back first (make-before-break back). */
    private fun done(l: Link, reason: String, notifyPeer: Boolean) {
        if (l.state == LinkState.TEARDOWN) return
        l.state = LinkState.TEARDOWN
        l.timers.forEach { it.cancel() }; l.timers.clear()
        l.doneReason = reason
        l.notifyDone = notifyPeer
        log(l, "teardown", "reason=$reason sta=${l.joined?.sta}")
        if (l.joined?.sta == "kept" && env.linkReady(l.peer)) {
            l.waitingLan = true
            env.resumeLan(l.peer, overLink = true)
            if (env.lanReady(l.peer)) { finishDone(l); return }
            l.timers += env.schedule(LinkPolicy.BACK_DIAL_WAIT_MS, guard0(l) { finishDone(l) })
        } else finishDone(l)
    }

    private fun finishDone(l: Link) {
        if (l.finished) return
        l.finished = true
        l.timers.forEach { it.cancel() }; l.timers.clear()
        val lanBack = env.lanReady(l.peer)
        // On the LAN session if it is back, otherwise still on the link session (before closing it).
        if (l.notifyDone) env.send(l.peer, LinkMsg.Done(l.lid, l.doneReason))
        env.closeLinkSession(l.peer)
        l.joinHandle?.cancel(); l.joinHandle = null
        if (links[l.peer] === l) links.remove(l.peer)
        env.resumeLan(l.peer, overLink = false)
        val dur = if (l.readyAt > 0) (env.now() - l.readyAt) / 1000 else 0
        log(l, "done", "reason=${l.doneReason} dur=${dur}s lan_back=$lanBack")
    }

    companion object {
        const val TYPE_WFD_LEGACY = "wfd_legacy"
        const val TYPE_WFD_GO = "wfd_go"
    }
}
