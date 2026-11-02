package com.shazan.airbridge.net

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
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
            .socketFactory(NoDelaySocketFactory)
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
            }
        }
    }

    fun statusOf(peerId: String): PeerStatus {
        val d = dialers[peerId] ?: return PeerStatus.OFFLINE
        val s = d.session
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
        }
    }

    private fun ensure(d: Dialer, now: Boolean, manual: Boolean = false) {
        synchronized(d) {
            if (!running || d.paused || (!manual && !autoConnect) || d.session != null) return
            if (Hub.readySession(d.peerId) != null) return // never dial a peer we already have
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

    private fun open(host: String, port: Int, expectedId: String?, mode: PairMode, onEnd: (Session, Outcome) -> Unit): Session {
        val hostLiteral = if (host.contains(':')) "[$host]" else host
        val link = WsLink(host, port, "$hostLiteral:$port")
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
        link.ws = http.newWebSocket(req, link)
        return session
    }

    /** OkHttp listener + [Link] for one socket. */
    private class WsLink(val host: String, val port: Int, override val label: String) : WebSocketListener(), Link {
        override val kind = "lan"

        /** Remember the address that worked: fallback for when mDNS is silent (§2.3). */
        override fun onReady(peerId: String) = PairStore.updateMeta(peerId, host = host, port = port)
        @Volatile var ws: WebSocket? = null
        lateinit var session: Session

        override fun sendText(text: String) = ws?.send(text) ?: false
        override fun sendBinary(data: ByteArray) = ws?.send(data.toByteString()) ?: false
        override fun queuedBytes() = ws?.queueSize() ?: 0L
        @Volatile private var opened = false

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
            session.onTransportClosed(t.message ?: "failure")
        }
    }

    /** Disables Nagle on every socket (§6.4). */
    private object NoDelaySocketFactory : SocketFactory() {
        private val d = getDefault()
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
