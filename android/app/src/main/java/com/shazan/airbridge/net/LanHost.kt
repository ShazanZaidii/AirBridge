package com.shazan.airbridge.net

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * This phone as a host on the local network (§2), like the desktop apps: a WebSocket endpoint at
 * `ws://<ip>:<port>/v2` and a `_airbridge._tcp` service, so another phone on the same Wi-Fi finds it
 * under "On this Wi-Fi" and pairs with the 6-digit code. No Bluetooth involved; Nearby stays the
 * route when there is no shared network.
 *
 * Roles: the phone that dials is the protocol client and keeps `via = "lan"`; this side is the host
 * and stores the peer with `via = [KIND]`, which [WsClient] never dials, so two phones never race to
 * connect to each other. Pairing needs the user to accept the code here too (Session, mode confirm).
 *
 * Active while the AirBridge service runs or the app is on screen ([acquire] / [release]).
 */
object LanHost {
    private const val TAG = "AirBridge.LanHost"
    const val KIND = "lanhost"
    private val PORTS = 47632..47641 // §2.2

    @Volatile private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var refs = 0

    /** Port we listen on, 0 when not listening. */
    val port: Int get() = server?.localPort ?: 0

    // ---- "Show QR code" (§2.4, mode qr): a one-time secret, valid while the code is on screen ----

    @Volatile private var qrSecret: ByteArray? = null
    private val _qrLink = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** The `airbridge://pair?…` link to draw as a QR code, while one is shown. */
    val qrLink: kotlinx.coroutines.flow.StateFlow<String?> = _qrLink

    /** Start showing a QR code; a new secret every time. Null when this phone is not reachable yet. */
    @Synchronized fun openQr(): String? {
        if (server == null) start()
        val p = port
        val addr = localAddresses().firstOrNull()
        if (p == 0 || addr == null) { _qrLink.value = null; return null }
        val secret = Protocol.randomBytes(32)
        qrSecret = secret
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val link = "airbridge://pair?v=${Protocol.VERSION}&id=${enc(Identity.id)}&name=${enc(Identity.name)}" +
            "&plat=${Protocol.PLATFORM}&addr=$addr&port=$p&k=${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"
        _qrLink.value = link
        if (com.shazan.airbridge.BuildConfig.DEBUG) Log.d(TAG, "qr link: $link") // debug builds only (contains the one-time secret)
        return link
    }

    /** The QR code left the screen: its secret stops working. */
    @Synchronized fun closeQr() { qrSecret = null; _qrLink.value = null }

    /** The secret of the code on screen, if any (Session, host side). */
    fun qrSecretNow(): ByteArray? = qrSecret

    /** A scan just used the code: show a fresh one (a QR secret pairs once). */
    @Synchronized fun qrUsed() { if (qrSecret != null) openQr() }

    @Synchronized fun acquire() { refs++; if (refs == 1) start() }
    @Synchronized fun release() { if (refs > 0) refs--; if (refs == 0) stop() }

    /** The name changed: announce it again. */
    @Synchronized fun onNameChanged() {
        val ss = server ?: return
        unregister(); register(ss.localPort)
    }

    /**
     * IPv4 addresses of this phone on its local networks, best first, for "connect by address": Wi-Fi
     * and Ethernet first; mobile data (rmnet/ccmni), VPN tunnels and other virtual links are left out,
     * since nobody on the Wi-Fi can reach them.
     */
    fun localAddresses(): List<String> = try {
        fun rank(name: String): Int = when {
            name.startsWith("wlan") || name.startsWith("eth") -> 0
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("p2p") -> 1 // hotspot / Wi-Fi Direct
            else -> 2
        }
        val mobileOrVirtual = listOf("rmnet", "ccmni", "tun", "ppp", "v4-", "dummy", "ipsec", "clat", "radio")
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && mobileOrVirtual.none { p -> it.name.startsWith(p) } }
            .sortedBy { rank(it.name) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun start() {
        if (server != null) return
        val ss = PORTS.firstNotNullOfOrNull { p ->
            try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } } catch (_: IOException) { null }
        }
        if (ss == null) { Log.w(TAG, "no free port in $PORTS"); return }
        server = ss
        Log.i(TAG, "listening on :${ss.localPort}${Protocol.WS_PATH}")
        Thread({ acceptLoop(ss) }, "airbridge-lanhost").apply { isDaemon = true }.start()
        register(ss.localPort)
    }

    private fun stop() {
        unregister()
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = try { ss.accept() } catch (_: IOException) { break }
            Thread({ serve(sock) }, "airbridge-lanhost-conn").apply { isDaemon = true }.start()
        }
    }

    // ---------------------------------------------------------------- mDNS

    private fun register(port: Int) {
        // An emulator's address (10.0.2.x) is unreachable from the real network: announcing it would only
        // list a device nobody can reach. It can still dial out ("Connect by address").
        if (EmulatorHost.isEmulator) { Log.i(TAG, "emulator: not announcing (unreachable from outside)"); return }
        val nsd = Hub.app.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = Identity.name.take(60).ifBlank { "AirBridge phone" }
            serviceType = Protocol.SERVICE_TYPE
            this.port = port
            setAttribute("v", Protocol.VERSION.toString())
            setAttribute("id", Identity.id)
            setAttribute("name", Identity.name.take(60))
            setAttribute("plat", Protocol.PLATFORM)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { Log.i(TAG, "announced as ${si.serviceName}") }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "announce failed: $errorCode") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); registration = l } catch (e: Exception) { Log.w(TAG, "registerService", e) }
    }

    private fun unregister() {
        val l = registration ?: return
        registration = null
        try { Hub.app.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- one connection

    private fun serve(sock: Socket) {
        val label = "${sock.inetAddress?.hostAddress}:${sock.port} (in)"
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 10_000 // the HTTP upgrade must arrive promptly
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val out = sock.getOutputStream()
            val headers = WsServerCodec.readRequest(input)
            val key = headers?.get("sec-websocket-key")
            val path = headers?.get(":path") ?: ""
            if (headers != null && path.substringBefore('?') != Protocol.WS_PATH) {
                // §2.2: any other path is a 404, so v1 clients fail fast.
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                sock.close()
                return
            }
            if (headers == null || key == null || headers["upgrade"]?.lowercase() != "websocket") {
                out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                sock.close()
                return
            }
            out.write(WsServerCodec.upgradeResponse(key).toByteArray())
            out.flush()
            sock.soTimeout = 0 // liveness is the session's job (§4 ping/pong)
            val link = HostLink(sock, out, label)
            val session = Session(Role.HOST, link, null, PairMode.Confirm(auto = false)) { _, _ -> link.close() }
            link.session = session
            link.startWriter()
            session.start()
            link.readLoop(input) // until the socket ends
        } catch (e: Exception) {
            Log.i(TAG, "$label: ${e.message}")
            try { sock.close() } catch (_: IOException) {}
        }
    }

    private class HostLink(private val sock: Socket, private val out: OutputStream, override val label: String) : Link {
        override val kind = KIND
        @Volatile var session: Session? = null
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicLong()
        @Volatile private var closed = false

        fun startWriter() {
            Thread({
                try {
                    while (!closed) {
                        val f = queue.take()
                        if (f.isEmpty()) break // close marker
                        out.write(f)
                        queued.addAndGet(-f.size.toLong())
                        if (queue.isEmpty()) out.flush()
                    }
                    out.flush()
                } catch (_: Exception) {
                } finally {
                    try { sock.close() } catch (_: IOException) {}
                }
            }, "airbridge-lanhost-tx").apply { isDaemon = true }.start()
        }

        fun readLoop(input: InputStream) {
            try {
                WsServerCodec.readMessages(input, Protocol.MAX_WIRE_FRAME + 1024,
                    onText = { session?.onText(it) },
                    onBinary = { if (it.size <= Protocol.MAX_WIRE_FRAME) session?.onBinary(it) },
                    onPing = { enqueue(WsServerCodec.frame(0xA, it)) },
                )
                session?.onTransportClosed("closed")
            } catch (e: Exception) {
                session?.onTransportClosed(e.message ?: "read error")
            } finally {
                close()
            }
        }

        private fun enqueue(f: ByteArray): Boolean {
            if (closed) return false
            queued.addAndGet(f.size.toLong())
            queue.put(f)
            return true
        }

        override fun sendText(text: String) = enqueue(WsServerCodec.frame(0x1, text.toByteArray(Charsets.UTF_8)))
        override fun sendBinary(data: ByteArray) = enqueue(WsServerCodec.frame(0x2, data))
        override fun queuedBytes(): Long = queued.get()

        override fun close() {
            if (closed) return
            enqueue(WsServerCodec.frame(0x8, byteArrayOf(0x03, 0xE8.toByte()))) // 1000 normal
            closed = true
            queue.put(ByteArray(0))
        }
    }
}

/** The server side of RFC 6455, only what AirBridge peers use (no extensions). Pure Kotlin, unit-tested. */
internal object WsServerCodec {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** Request line + headers (lower-case names; the path under ":path"), or null if malformed. */
    fun readRequest(input: InputStream): Map<String, String>? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (++total > 16 * 1024) return null
            if (c == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                line.setLength(0)
                if (l.isEmpty()) break
                lines += l
            } else line.append(c.toChar())
        }
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2 || first[0] != "GET") return null
        val h = HashMap<String, String>()
        h[":path"] = first[1]
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return h
    }

    fun accept(key: String): String =
        java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))

    fun upgradeResponse(key: String) =
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept(key)}\r\n\r\n"

    /** One unmasked server frame (FIN set). */
    fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val head = when {
            n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
            n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (n shr 8).toByte(), n.toByte())
            else -> ByteArray(10).also { b ->
                b[0] = (0x80 or opcode).toByte(); b[1] = 127
                for (i in 0 until 8) b[2 + i] = (n.toLong() shr (8 * (7 - i))).toByte()
            }
        }
        return head + payload
    }

    /**
     * Reads client frames until a close frame or EOF. Reassembles fragmented messages; [maxMessage]
     * bounds memory (larger messages end the connection).
     */
    fun readMessages(
        input: InputStream, maxMessage: Int,
        onText: (String) -> Unit, onBinary: (ByteArray) -> Unit, onPing: (ByteArray) -> Unit,
    ) {
        val msg = ByteArrayOutputStream()
        var msgOp = 0
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = readByte(input)
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(input).toLong() } }
            if (len < 0 || len > maxMessage) throw IOException("frame too large ($len)")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val data = ByteArray(len.toInt())
            readFully(input, data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                0x8 -> return
                0x9 -> onPing(data)
                0xA -> Unit
                0x0, 0x1, 0x2 -> {
                    if (op != 0) { msg.reset(); msgOp = op }
                    msg.write(data)
                    if (msg.size() > maxMessage) throw IOException("message too large")
                    if (fin) {
                        val bytes = msg.toByteArray()
                        msg.reset()
                        if (msgOp == 0x1) onText(String(bytes, Charsets.UTF_8)) else onBinary(bytes)
                    }
                }
                else -> throw IOException("bad opcode $op")
            }
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}
