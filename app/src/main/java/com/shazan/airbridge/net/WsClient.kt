package com.shazan.airbridge.net

import android.content.Context
import android.net.Network
import android.util.Log
import com.shazan.airbridge.net.link.LinkPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Dials hosts over `ws://host:port/v2` (§2.2, §4.1). Exactly one attempt per peer id at any
 * time; exponential backoff 1 s → 30 s; immediate retry on network change or mDNS update.
 */
object WsClient {
    private const val TAG = "AirBridge.Ws"
    const val PREF_AUTO_CONNECT = "AUTO_CONNECT"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .socketFactory(NoDelaySocketFactory())
            // §6.4: permessage-deflate off. OkHttp adds the offer itself; strip it before it is sent.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Sec-WebSocket-Extensions").build())
            }
            .minWebSocketMessageToCompress(Long.MAX_VALUE)
            .build()
    }

    // Mutated under synchronized(dialer); volatile so statusOf can read without locking
    // (Session.finish → Hub.recomputeStatus → statusOf runs while another dialer's lock is held).
    private class Dialer(val peerId: String) {
        @Volatile var session: Session? = null
        var backoffMs = 1000L
        @Volatile var retry: ScheduledFuture<*>? = null
        /** Set after bye user/replaced etc.; cleared by explicit user action or app foreground. */
        @Volatile var paused = false
        /** Pause came from `bye replaced`: a network change also clears it (§3.4). */
        var pausedByReplace = false
        /** The session dialled over a Wi-Fi Direct link (§10.1), alongside [session]. Never auto-redialled. */
        @Volatile var link: Session? = null
    }

    private val dialers = ConcurrentHashMap<String, Dialer>()
    @Volatile private var running = false

    private val autoConnect: Boolean
        get() = Hub.app.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).getBoolean(PREF_AUTO_CONNECT, true)

    fun start() {
        running = true
        retryAllNow(unpause = true)
    }

    fun stop() {
        running = false
        dialers.values.forEach { d ->
            synchronized(d) {
                d.retry?.cancel(false); d.retry = null
                d.session?.close(Outcome.Ended("shutdown"), "shutdown")
                d.session = null
                d.link?.close(Outcome.Ended("shutdown"), "shutdown")
                d.link = null
            }
        }
    }

    fun statusOf(peerId: String): PeerStatus {
        val d = dialers[peerId] ?: return PeerStatus.OFFLINE
        val s = d.session ?: d.link
        return when {
            s?.isReady == true -> PeerStatus.CONNECTED
            s != null -> PeerStatus.CONNECTING
            running && !d.paused && d.retry != null -> PeerStatus.RECONNECTING
            else -> PeerStatus.OFFLINE
        }
    }

    /**
     * Connect (or retry immediately) every paired LAN peer. [unpause] = user-initiated (app opened,
     * service started): also connects when auto-reconnect is off and clears a bye-induced pause.
     */
    fun retryAllNow(unpause: Boolean = false, networkChanged: Boolean = false) {
        if (!running) return
        PairStore.all().filter { it.via == "lan" }.forEach { p ->
            val d = dialers.getOrPut(p.id) { Dialer(p.id) }
            synchronized(d) {
                if (unpause || (networkChanged && d.pausedByReplace)) { d.paused = false; d.pausedByReplace = false }
                d.backoffMs = 1000L
            }
            ensure(d, now = true, manual = unpause)
        }
        Hub.recomputeStatus()
    }

    fun onDiscovered(peerId: String) {
        if (!running || PairStore.get(peerId)?.via != "lan") return
        val d = dialers.getOrPut(peerId) { Dialer(peerId) }
        ensure(d, now = true)
    }

    fun forget(peerId: String) {
        val d = dialers.remove(peerId) ?: return
        synchronized(d) {
            d.retry?.cancel(false)
            d.session?.close(Outcome.Ended("forgotten"), null)
            d.link?.close(Outcome.Ended("forgotten"), null)
        }
    }

    /**
     * [overLink]: §10.1 exception to §3.4 — dial the LAN even though the peer's ready session is a
     * link session that is being torn down (make-before-break back).
     */
    private fun ensure(d: Dialer, now: Boolean, manual: Boolean = false, overLink: Boolean = false) {
        synchronized(d) {
            if (!running || d.paused || (!manual && !autoConnect) || d.session != null) return
            val ready = Hub.readySession(d.peerId)
            if (ready != null && !(overLink && ready.link.kind == KIND_WFD)) return // never dial a peer we already have
            if (now) { d.retry?.cancel(false); d.retry = null } else if (d.retry != null) return
            val addr = Discovery.lookup(d.peerId)
                ?: PairStore.get(d.peerId)?.let { p -> p.lastHost?.let { h -> h to p.lastPort } }
            if (addr == null || addr.second !in 1..65535) {
                Log.d(TAG, "no address for ${d.peerId}; waiting for mDNS")
                return
            }
            val s = open(addr.first, addr.second, d.peerId, PairMode.None) { s, outcome -> onEnded(d, s, outcome) }
            d.session = s.takeUnless { it.isClosed } // closed synchronously on a malformed address
        }
        Hub.recomputeStatus()
    }

    // ---- §10.1 link upgrades ----

    /** How a link dial ended. [connected] = the WebSocket opened; [eperm] = the socket could not be bound (VPN lockdown). */
    class LinkDialEnd(val session: Session, val outcome: Outcome, val everReady: Boolean, val connected: Boolean, val eperm: Boolean, val why: String)

    /**
     * Dial `ws://host:port/v2` over [network] for [peerId] although a ready session may exist (§10.1
     * exception to §3.4). The usual handshake runs; when it completes [Hub.onReady] retires the old
     * session (make-before-break). Sockets are bound with [Network.getSocketFactory]; the process is
     * never bound. [onEnd] runs once when this session ends (whether or not it became ready).
     */
    fun dialVia(peerId: String, host: String, port: Int, network: Network, onEnd: (LinkDialEnd) -> Unit): Boolean {
        if (!running) return false
        val d = dialers.getOrPut(peerId) { Dialer(peerId) }
        synchronized(d) {
            d.link?.close(Outcome.Ended("link redial"), null)
            d.link = null
            val client = http.newBuilder()
                .socketFactory(NoDelaySocketFactory(network.socketFactory))
                .proxy(Proxy.NO_PROXY) // a Wi-Fi proxy must not capture link traffic
                .connectTimeout(LinkPolicy.DIAL_CONNECT_MS, TimeUnit.MILLISECONDS)
                .build()
            val s = open(host, port, peerId, PairMode.None, client, KIND_WFD) { s, outcome ->
                synchronized(d) { if (d.link === s) d.link = null }
                val link = s.link as? WsLink
                val why = when (outcome) { is Outcome.Lost -> outcome.why; is Outcome.Ended -> outcome.why; else -> outcome.toString() }
                try {
                    onEnd(LinkDialEnd(s, outcome, s.everReady, link?.connected == true, link?.eperm == true, why))
                } catch (e: Exception) { Log.w(TAG, "link onEnd", e) }
                Hub.recomputeStatus()
            }
            d.link = s.takeUnless { it.isClosed }
        }
        Hub.recomputeStatus()
        return true
    }

    /** Close the peer's link session, if any. */
    fun closeLink(peerId: String, bye: String? = null) {
        val d = dialers[peerId] ?: return
        val s = synchronized(d) { d.link.also { d.link = null } } ?: return
        s.close(Outcome.Ended("link closed"), bye)
    }

    /**
     * The link ended (or is ending): clear the pause left by the session the link replaced and dial
     * the LAN now (§10.1: the replaced session must be redialled). A local-only network going away is
     * not a default-network change, so nothing else would clear `pausedByReplace`. [overLink] allows
     * the dial while the link session is still ready (make-before-break back).
     */
    fun resumeAfterLink(peerId: String, overLink: Boolean) {
        if (!running || PairStore.get(peerId)?.via != "lan") return
        val d = dialers.getOrPut(peerId) { Dialer(peerId) }
        synchronized(d) {
            d.paused = false
            d.pausedByReplace = false
            d.backoffMs = 1000L
            d.retry?.cancel(false); d.retry = null
        }
        ensure(d, now = true, manual = true, overLink = overLink)
        Hub.recomputeStatus()
    }

    private fun onEnded(d: Dialer, s: Session, outcome: Outcome) {
        synchronized(d) {
            if (d.session !== s) return
            d.session = null
            when {
                outcome is Outcome.Unpaired || PairStore.get(d.peerId) == null -> { dialers.remove(d.peerId, d); return }
                !outcome.reconnect -> {
                    d.paused = true
                    d.pausedByReplace = outcome is Outcome.Ended && outcome.why == "bye replaced"
                    return
                }
                !running || dialers[d.peerId] !== d -> return
            }
            if (s.everReady) d.backoffMs = 1000L
            val delay = d.backoffMs
            d.backoffMs = (d.backoffMs * 2).coerceAtMost(30_000L)
            d.retry = Hub.scheduler.schedule({
                synchronized(d) { d.retry = null }
                ensure(d, now = false)
            }, delay, TimeUnit.MILLISECONDS)
        }
        Hub.recomputeStatus()
    }

    /**
     * Pair with a host: mode `qr` (scanned secret, no prompt) or `confirm` (both screens show the
     * same 6 digits, both users confirm). Cancels any in-flight attempt for that id first. Progress
     * is in [Hub.pairing]; the result arrives as [NetEvent.PairResult].
     */
    fun pair(host: String, port: Int, expectedId: String?, mode: PairMode, name: String, plat: String) {
        val modeName = if (mode is PairMode.Qr) "qr" else "confirm"
        val d = expectedId?.let { dialers.getOrPut(it) { Dialer(it) } }
        if (d == null) {
            val s = open(host, port, null, mode) { _, _ -> }
            Hub.beginPairing(s, name, plat, modeName)
            return
        }
        val s = synchronized(d) {
            d.retry?.cancel(false); d.retry = null
            d.session?.close(Outcome.Ended("re-pairing"), "replaced")
            d.paused = false
            d.pausedByReplace = false
            d.backoffMs = 1000L
            val s = open(host, port, expectedId, mode) { s, outcome -> onEnded(d, s, outcome) }
            d.session = s.takeUnless { it.isClosed }
            s
        }
        Hub.beginPairing(s, name, plat, modeName)
    }

    private fun open(
        host: String, port: Int, expectedId: String?, mode: PairMode,
        client: OkHttpClient = http, kind: String = "lan", onEnd: (Session, Outcome) -> Unit,
    ): Session {
        val hostLiteral = if (host.contains(':')) "[$host]" else host
        val link = WsLink(host, port, "$hostLiteral:$port", kind)
        val session = Session(Role.CLIENT, link, expectedId, mode, onEnd)
        link.session = session
        val req = try {
            Request.Builder().url("http://$hostLiteral:$port${Protocol.WS_PATH}").build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad address $host:$port")
            Hub.register(session)
            session.onTransportClosed("bad address")
            return session
        }
        Hub.register(session)
        link.ws = client.newWebSocket(req, link)
        return session
    }

    const val KIND_WFD = "wfd"

    /** OkHttp listener + [Link] for one socket. */
    private class WsLink(val host: String, val port: Int, override val label: String, override val kind: String) : WebSocketListener(), Link {
        /** Remember the address that worked: fallback for when mDNS is silent (§2.3). Never a link address. */
        override fun onReady(peerId: String) { if (kind == "lan") PairStore.updateMeta(peerId, host = host, port = port) }
        @Volatile var ws: WebSocket? = null
        lateinit var session: Session

        override fun sendText(text: String) = ws?.send(text) ?: false
        override fun sendBinary(data: ByteArray) = ws?.send(data.toByteString()) ?: false
        override fun queuedBytes() = ws?.queueSize() ?: 0L
        @Volatile private var opened = false
        val connected: Boolean get() = opened
        /** The socket could not be bound to the network (always-on VPN in lockdown → EPERM). */
        @Volatile var eperm = false

        override fun close() {
            val w = ws ?: return
            if (!opened || !w.close(1000, null)) w.cancel()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (ws == null) ws = webSocket
            opened = true
            session.start()
        }
        override fun onMessage(webSocket: WebSocket, text: String) = session.onText(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size <= Protocol.MAX_WIRE_FRAME) session.onBinary(bytes.toByteArray())
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            session.onTransportClosed("closing $code")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = session.onTransportClosed("closed $code")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "$label failed: ${t.message}")
            if (generateSequence(t) { it.cause }.take(8).any { c -> c.message?.let { "EPERM" in it || "Operation not permitted" in it } == true }) eperm = true
            session.onTransportClosed(t.message ?: "failure")
        }
    }

    /** Disables Nagle on every socket (§6.4). [d] = the factory to wrap (default, or a `Network`'s). */
    private class NoDelaySocketFactory(private val d: SocketFactory = getDefault()) : SocketFactory() {
        private fun Socket.nd(): Socket = apply { try { tcpNoDelay = true } catch (_: Exception) {} }
        override fun createSocket(): Socket = d.createSocket().nd()
        override fun createSocket(host: String?, port: Int): Socket = d.createSocket(host, port).nd()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            d.createSocket(host, port, localHost, localPort).nd()
        override fun createSocket(host: InetAddress?, port: Int): Socket = d.createSocket(host, port).nd()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            d.createSocket(address, port, localAddress, localPort).nd()
    }
}
