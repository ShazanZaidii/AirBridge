package com.shazan.airbridge.net

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AirWeb: a small HTTP/1.1 server so phones and computers without AirBridge can swap files through this
 * device from their browsers. Everyone who opens the link joins one **shared pool**: a file added on the host or
 * uploaded by any browser is listed for, and downloadable by, everyone else who is connected. The page, the pool
 * list, downloads and uploads all need the session token (`?t=…`, 128 random bits) or the answer is 403.
 * Files are streamed in both directions (never held in memory), `Connection: close` on every response.
 *
 * Built to stay up with 50+ browsers on one phone (see [Limits]): a bounded thread pool, a per-address
 * connection cap, a head-read deadline against slow clients, a fair waiting room for the few transfer slots,
 * space reserved for uploads in flight, a watchdog for stalled sockets, and a state poll that answers "nothing
 * changed" in a couple of hundred bytes.
 *
 * Plain JVM code (no Android types) so it is tested end to end with real sockets; [Host] supplies the files, the
 * destination for uploads and the page.
 *
 * Routes (all need `t`; `c` = the page's random client id, `w` = its device label, both optional):
 *  - `GET /` the page
 *  - `GET /api/state?c=&w=&v=` JSON: host name, free space, poll interval, who is connected, the pool
 *    (omitted, with `"same":true`, when `v` is the pool's current version)
 *  - `GET|HEAD /d/<id>[/<name>]` download one pool file
 *  - `POST /u?n=<file name>&c=&w=` upload one file into the pool, raw body, Content-Length required
 *  - `GET /qr.svg` the QR code of this page's own link (built from the `Host` header the browser used), so any
 *    connected browser can show it to bring in more people
 */
class AirWebServer(val token: String, private val host: Host, private val limits: Limits = Limits()) {

    /**
     * [added]: when it was offered (epoch ms), for "1 min ago" on the page. [by]: who put it in the pool (a device
     * label; null = the host). [owner]: the client id of the page that uploaded it, so that page can mark it "yours".
     */
    class WebFile(
        val id: Int, val name: String, val size: Long, val mime: String?, val added: Long = 0L,
        val by: String? = null, val owner: String? = null,
    )

    /** A browser that is connected: seen within [Limits.peerTtlMs]. [label] is unique among the connected ones. */
    data class Peer(val id: String, val label: String, val joined: Long, val lastSeen: Long)

    /** Where one upload is written. [commit] makes it visible and returns its final name. */
    interface UploadTarget {
        val out: OutputStream
        fun commit(): String
        fun discard()
    }

    interface Host {
        fun deviceName(): String
        fun page(): String
        /** The whole pool: what the host added and what browsers uploaded. Ids are unique across both. */
        fun offered(): List<WebFile>
        fun open(file: WebFile): InputStream?
        /** Bytes free for uploads, or -1 when unknown. */
        fun freeSpace(): Long = -1
        /** The QR code of [text] as rows of modules (true = dark, no quiet zone), or null if it can't be drawn. */
        fun qr(text: String): Array<BooleanArray>? = null
        /** Throws [IOException] if it can't be created (no space, storage error). */
        fun createUpload(rawName: String, mime: String?, size: Long): UploadTarget
        fun onUploadStarted(id: Long, name: String, size: Long) {}
        /** [by] is the uploading browser (null when it didn't identify itself). */
        fun onUploadStarted(id: Long, name: String, size: Long, by: Peer?) = onUploadStarted(id, name, size)
        fun onUploadProgress(id: Long, done: Long, total: Long) {}
        /** [target] and [savedName] are null when the upload failed. */
        fun onUploadFinished(id: Long, target: UploadTarget?, savedName: String?) {}
        fun onDownloaded(file: WebFile) {}
        /** The set of connected browsers changed. Called on a server thread: return quickly. */
        fun onPeersChanged(peers: List<Peer>) {}
    }

    /** Every limit in one place; tests shrink them. */
    data class Limits(
        /** Connections open at once (each holds a thread); more get 503 + Retry-After. */
        val maxConnections: Int = 128,
        /** Connections from one address (a browser opens about six): one device can't starve the rest. */
        val perIp: Int = 24,
        /** Files moving at once; the others wait their turn in the waiting room. */
        val maxTransfers: Int = 8,
        /** Requests allowed to wait for a transfer slot; beyond that: 503 + Retry-After. */
        val maxWaiting: Int = 32,
        val transferWaitMs: Long = 120_000,
        /** The whole request head must arrive within this (slow-client / half-open connection guard). */
        val headDeadlineMs: Long = 10_000,
        /** No byte moving for this long while reading a body: the connection is dropped. */
        val idleMs: Int = 30_000,
        /** A transfer socket with no progress for this long is cut (a client that stopped reading). */
        val stallMs: Long = 45_000,
        /** A browser not heard from for this long is no longer "connected". */
        val peerTtlMs: Long = 20_000,
        val maxPeers: Int = 256,
        /** Files in the pool; uploads beyond this are refused. */
        val maxPool: Int = 1000,
    )

    companion object {
        val PORTS = 47650..47659
        /** Pool ids of uploaded files start here (the host's own files count up from 1). */
        const val UPLOAD_ID_BASE = 1_000_000
        private const val BUF = 64 * 1024
        private const val PROGRESS_EVERY = 256L * 1024
        private const val HEAD_STEP_MS = 5_000
        private const val MAX_PEERS_LISTED = 24
        private val rng = SecureRandom()

        /** 16 random bytes as 22 base64url characters. */
        fun newToken(): String {
            val b = ByteArray(16)
            rng.nextBytes(b)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        fun url(address: String, port: Int, token: String) = "http://$address:$port/?t=$token"
    }

    /** One open connection: the watchdog cuts it when a transfer stops moving. */
    private class Conn(val sock: Socket) {
        @Volatile var last = System.nanoTime()
        /** Waiting for a transfer slot: exempt from the stall watchdog (the waiting room has its own deadline). */
        @Volatile var waiting = false
        fun touch() { last = System.nanoTime() }
    }

    private class PeerRec(val id: String, var label: String, val joined: Long, var lastSeen: Long)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    private val conns: MutableSet<Conn> = Collections.synchronizedSet(HashSet())
    private val connections = AtomicInteger()
    private val perIpCount = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val uploadIds = AtomicLong()
    private val slots = java.util.concurrent.Semaphore(limits.maxTransfers, true)
    private val waiting = AtomicInteger()
    private val reserveLock = Any()
    private var reservedBytes = 0L
    private var workers: java.util.concurrent.ThreadPoolExecutor? = null
    private var sweeper: java.util.concurrent.ScheduledExecutorService? = null
    private var rejecter: java.util.concurrent.ThreadPoolExecutor? = null

    private val peerLock = Any()
    private val peerMap = LinkedHashMap<String, PeerRec>()
    private var lastPeerKey = ""

    val port: Int get() = server?.localPort ?: 0
    val isRunning: Boolean get() = server?.isClosed == false
    /** Downloads and uploads in progress right now. */
    val activeTransfers: Int get() = limits.maxTransfers - slots.availablePermits()
    /** Requests waiting for a transfer slot right now. */
    val waitingTransfers: Int get() = waiting.get()
    val openConnections: Int get() = connections.get()

    /** The connected browsers, oldest first. */
    fun peers(): List<Peer> = synchronized(peerLock) { snapshotLocked() }

    /**
     * Listens on [preferred] if it is free, else the first free port of [ports]. Returns the port.
     * Throws [IOException] if none is free.
     */
    @Synchronized
    fun start(preferred: Int = 0, ports: IntRange = PORTS): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val candidates = (listOf(preferred).filter { it > 0 } + ports.toList()).distinct()
        val ss = candidates.firstNotNullOfOrNull { p ->
            try { ServerSocket(p, 128).apply { reuseAddress = true } } catch (_: IOException) { null }
        } ?: throw IOException("no free port in $ports")
        stopped = false
        server = ss
        var n = 0
        val daemon = java.util.concurrent.ThreadFactory { r -> Thread(r, "airweb-conn-${++n}").apply { isDaemon = true } }
        workers = java.util.concurrent.ThreadPoolExecutor(0, limits.maxConnections, 20, TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(), daemon)
        rejecter = java.util.concurrent.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(64)) { r -> Thread(r, "airweb-reject").apply { isDaemon = true } }
        sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "airweb-sweep").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ sweep() }, 2, 2, TimeUnit.SECONDS) }
        Thread({ acceptLoop(ss) }, "airweb-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    /** Stops listening and cuts every open connection (unfinished uploads are discarded). */
    @Synchronized
    fun stop() {
        stopped = true
        try { server?.close() } catch (_: IOException) {}
        server = null
        sweeper?.shutdownNow(); sweeper = null
        rejecter?.shutdownNow(); rejecter = null
        val open = synchronized(conns) { conns.toList() }
        open.forEach { try { it.sock.close() } catch (_: IOException) {} }
        workers?.shutdown(); workers = null
        synchronized(peerLock) { peerMap.clear(); lastPeerKey = "" }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = try { ss.accept() } catch (_: IOException) { break }
            val ip = sock.inetAddress?.hostAddress ?: "?"
            if (!admit(ip)) { reject(sock); continue }
            val conn = Conn(sock)
            conns.add(conn)
            val pool = workers
            try {
                if (pool == null) throw java.util.concurrent.RejectedExecutionException()
                pool.execute {
                    try { serve(conn) } finally { leave(conn, ip) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                leave(conn, ip)
                reject(sock)
            }
        }
    }

    private fun admit(ip: String): Boolean {
        if (connections.incrementAndGet() > limits.maxConnections) { connections.decrementAndGet(); return false }
        val n = perIpCount.computeIfAbsent(ip) { AtomicInteger() }
        if (n.incrementAndGet() > limits.perIp) {
            n.decrementAndGet(); connections.decrementAndGet()
            return false
        }
        return true
    }

    private fun leave(conn: Conn, ip: String) {
        conns.remove(conn)
        try { conn.sock.close() } catch (_: IOException) {}
        connections.decrementAndGet()
        perIpCount.compute(ip) { _, n -> if (n == null || n.decrementAndGet() <= 0) null else n }
    }

    /** Too busy: answer 503 (and let the browser retry) without tying up a worker or the accept loop. */
    private fun reject(sock: Socket) {
        val r = rejecter
        val task = Runnable {
            try {
                sock.soTimeout = 1_000
                val body = "503 Service Unavailable\n".toByteArray()
                sock.getOutputStream().write(AirWebHttp.head(503, "Service Unavailable", "text/plain; charset=utf-8",
                    body.size.toLong(), "Retry-After: 3\r\n").toByteArray() + body)
                sock.shutdownOutput()
                // Read what it sent, so closing doesn't reset the connection before it has read the answer.
                val buf = ByteArray(2048)
                val end = System.nanoTime() + 500_000_000L
                while (System.nanoTime() < end && sock.getInputStream().read(buf) >= 0) {}
            } catch (_: Exception) {} finally { try { sock.close() } catch (_: IOException) {} }
        }
        try { if (r == null) throw java.util.concurrent.RejectedExecutionException(); r.execute(task) }
        catch (_: java.util.concurrent.RejectedExecutionException) { try { sock.close() } catch (_: IOException) {} }
    }

    /** Every couple of seconds: browsers that went quiet leave, transfers that stalled are cut. */
    private fun sweep() {
        try {
            val now = System.currentTimeMillis()
            val gone = synchronized(peerLock) { peerMap.values.removeAll { now - it.lastSeen > limits.peerTtlMs } }
            if (gone) notifyPeers()
            val cut = System.nanoTime() - limits.stallMs * 1_000_000
            val stalled = synchronized(conns) { conns.filter { !it.waiting && it.last < cut } }
            stalled.forEach { try { it.sock.close() } catch (_: IOException) {} }
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- who is connected

    private fun touch(id: String?, rawLabel: String?): Peer? {
        if (id == null) return null
        val now = System.currentTimeMillis()
        val label = AirWebHttp.cleanLabel(rawLabel)
        val changed: Boolean
        synchronized(peerLock) {
            val p = peerMap[id]
            if (p == null) {
                if (peerMap.size >= limits.maxPeers) peerMap.values.minByOrNull { it.lastSeen }?.let { peerMap.remove(it.id) }
                peerMap[id] = PeerRec(id, label, now, now)
                changed = true
            } else {
                p.lastSeen = now
                changed = p.label != label
                p.label = label
            }
        }
        if (changed) notifyPeers()
        return peers().firstOrNull { it.id == id }
    }

    private fun snapshotLocked(): List<Peer> {
        val list = peerMap.values.sortedBy { it.joined }
        val total = list.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return list.map { p ->
            val k = (seen[p.label] ?: 0) + 1
            seen[p.label] = k
            Peer(p.id, if ((total[p.label] ?: 1) > 1) "${p.label} $k" else p.label, p.joined, p.lastSeen)
        }
    }

    private fun notifyPeers() {
        val snap = synchronized(peerLock) {
            val s = snapshotLocked()
            val key = s.joinToString("|") { it.id + "=" + it.label }
            if (key == lastPeerKey) return
            lastPeerKey = key
            s
        }
        try { host.onPeersChanged(snap) } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- transfer slots (the waiting room)

    /** A transfer slot, waiting for one if need be; false when the room is full, time ran out or the server stopped. */
    private fun acquireSlot(conn: Conn): Boolean {
        if (slots.tryAcquire()) return true
        if (waiting.incrementAndGet() > limits.maxWaiting) { waiting.decrementAndGet(); return false }
        conn.waiting = true
        try {
            val end = System.nanoTime() + limits.transferWaitMs * 1_000_000
            while (!stopped && System.nanoTime() < end) {
                if (slots.tryAcquire(500, TimeUnit.MILLISECONDS)) return true
            }
            return false
        } finally {
            conn.waiting = false
            conn.touch()
            waiting.decrementAndGet()
        }
    }

    /** Space for an upload of [len] bytes on top of the uploads already running, or false when it won't fit. */
    private fun reserve(len: Long): Boolean = synchronized(reserveLock) {
        val free = host.freeSpace()
        if (free >= 0 && reservedBytes + len > free) false else { reservedBytes += len; true }
    }

    private fun unreserve(len: Long) = synchronized(reserveLock) { reservedBytes = maxOf(0L, reservedBytes - len) }

    // ---------------------------------------------------------------- one request per connection

    private fun serve(conn: Conn) {
        val sock = conn.sock
        try {
            sock.soTimeout = minOf(HEAD_STEP_MS.toLong(), limits.headDeadlineMs).toInt()
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream(), BUF)
            val out = sock.getOutputStream()
            val req = AirWebHttp.readRequest(input, System.nanoTime() + limits.headDeadlineMs * 1_000_000)
            sock.soTimeout = limits.idleMs
            conn.touch()
            if (req == null) { simple(out, 400, "Bad Request"); return }
            if (!AirWebHttp.tokenMatches(req.query["t"], token)) {
                if (req.path == "/" && req.method == "GET") html(out, 403, "Forbidden", AirWebHttp.expiredPage())
                else simple(out, 403, "Forbidden")
                return
            }
            when {
                req.path == "/" -> when (req.method) {
                    "GET", "HEAD" -> html(out, 200, "OK", host.page().replace("{{NAME}}", AirWebHttp.escapeHtml(host.deviceName())), req.method == "HEAD")
                    else -> simple(out, 405, "Method Not Allowed")
                }
                req.path == "/api/state" -> if (req.method == "GET") state(out, req) else simple(out, 405, "Method Not Allowed")
                req.path == "/qr.svg" -> if (req.method == "GET") qr(out, req) else simple(out, 405, "Method Not Allowed")
                req.path.startsWith("/d/") -> when (req.method) {
                    "GET", "HEAD" -> download(conn, out, req)
                    else -> simple(out, 405, "Method Not Allowed")
                }
                req.path == "/u" -> if (req.method == "POST") upload(conn, input, out, req) else simple(out, 405, "Method Not Allowed")
                else -> simple(out, 404, "Not Found")
            }
            out.flush()
        } catch (_: Exception) {
            // Client went away, timed out, or storage failed; nothing more to answer. Never let it escape:
            // an uncaught exception on this thread would end the app.
        }
    }

    private fun busy(out: OutputStream) {
        val body = "503 Busy, try again in a moment\n".toByteArray()
        out.write(AirWebHttp.head(503, "Service Unavailable", "text/plain; charset=utf-8", body.size.toLong(), "Retry-After: 5\r\n").toByteArray())
        out.write(body)
    }

    private fun simple(out: OutputStream, code: Int, reason: String) {
        val body = "$code $reason\n".toByteArray()
        out.write(AirWebHttp.head(code, reason, "text/plain; charset=utf-8", body.size.toLong()).toByteArray())
        out.write(body)
    }

    private fun html(out: OutputStream, code: Int, reason: String, page: String, headOnly: Boolean = false) {
        val body = page.toByteArray(StandardCharsets.UTF_8)
        out.write(AirWebHttp.head(code, reason, "text/html; charset=utf-8", body.size.toLong(), AirWebHttp.PAGE_HEADERS).toByteArray())
        if (!headOnly) out.write(body)
    }

    private fun json(out: OutputStream, code: Int, reason: String, body: String) {
        val b = body.toByteArray(StandardCharsets.UTF_8)
        out.write(AirWebHttp.head(code, reason, "application/json; charset=utf-8", b.size.toLong()).toByteArray())
        out.write(b)
    }

    private fun state(out: OutputStream, req: AirWebHttp.Request) {
        val c = AirWebHttp.cleanClientId(req.query["c"])
        touch(c, req.query["w"])
        val peers = peers()
        val pool = host.offered()
        val sig = AirWebHttp.signature(pool)
        val same = req.query["v"] == sig
        val sb = StringBuilder(256 + if (same) 0 else pool.size * 120)
        sb.append("{\"name\":").append(AirWebHttp.jsonString(host.deviceName()))
            .append(",\"free\":").append(host.freeSpace())
            // "now" lets the page turn "added" into "1 min ago" even when the two clocks disagree.
            .append(",\"now\":").append(System.currentTimeMillis())
            // The page polls this often: slower the more browsers there are, so 50 of them stay cheap.
            .append(",\"poll\":").append((2_000 + 100 * peers.size).coerceIn(2_000, 8_000))
            .append(",\"v\":\"").append(sig).append('"')
            .append(",\"count\":").append(peers.size)
            .append(",\"peers\":[")
        // With a big crowd only some names are listed; the asking page is always among them (it marks itself "you").
        val shown = peers.take(MAX_PEERS_LISTED).let { first ->
            val mine = peers.firstOrNull { it.id == c }
            if (mine == null || first.any { it.id == c }) first else first.dropLast(1) + mine
        }
        shown.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(AirWebHttp.jsonString(p.id)).append(",\"name\":").append(AirWebHttp.jsonString(p.label)).append('}')
        }
        sb.append(']')
        if (same) sb.append(",\"same\":true")
        else {
            sb.append(",\"files\":[")
            pool.forEachIndexed { i, f ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(f.id).append(",\"name\":").append(AirWebHttp.jsonString(f.name))
                    .append(",\"size\":").append(f.size).append(",\"added\":").append(f.added)
                if (f.by != null) sb.append(",\"by\":").append(AirWebHttp.jsonString(f.by))
                if (f.owner != null && f.owner == c) sb.append(",\"own\":true")
                sb.append('}')
            }
            sb.append(']')
        }
        sb.append('}')
        json(out, 200, "OK", sb.toString())
    }

    private fun qr(out: OutputStream, req: AirWebHttp.Request) {
        // The link the browser itself used (hotspot and Wi-Fi addresses differ), so whoever scans it can reach us.
        val hostHeader = req.headers["host"]?.takeIf { it.length in 3..80 && it.all { c -> c.isLetterOrDigit() || c in ".-:[]" } }
        val matrix = if (hostHeader == null) null else try { host.qr("http://$hostHeader/?t=$token") } catch (_: Throwable) { null }
        if (matrix == null || matrix.isEmpty()) { simple(out, 404, "Not Found"); return }
        val body = AirWebHttp.qrSvg(matrix).toByteArray(StandardCharsets.UTF_8)
        out.write(AirWebHttp.head(200, "OK", "image/svg+xml; charset=utf-8", body.size.toLong()).toByteArray())
        out.write(body)
    }

    private fun download(conn: Conn, out: OutputStream, req: AirWebHttp.Request) {
        val id = req.path.removePrefix("/d/").substringBefore('/').toIntOrNull()
        val f = host.offered().firstOrNull { it.id == id }
        if (f == null) { simple(out, 404, "Not Found"); return }
        val headOnly = req.method == "HEAD"
        if (headOnly) {
            out.write(AirWebHttp.head(200, "OK", f.mime?.takeIf { it.contains('/') } ?: "application/octet-stream",
                f.size.takeIf { it >= 0 }, "Content-Disposition: ${AirWebHttp.contentDisposition(f.name)}\r\n").toByteArray())
            return
        }
        if (!acquireSlot(conn)) { busy(out); return }
        try {
            val stream: InputStream? = try { host.open(f) } catch (_: Exception) { null }
            if (stream == null) { simple(out, 410, "Gone"); return } // the sharing app took its grant back
            stream.use { input ->
                val extra = "Content-Disposition: ${AirWebHttp.contentDisposition(f.name)}\r\n"
                out.write(AirWebHttp.head(200, "OK", f.mime?.takeIf { it.contains('/') } ?: "application/octet-stream",
                    f.size.takeIf { it >= 0 }, extra).toByteArray())
                val buf = ByteArray(BUF)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // Never send more than announced (a file that grew would break the response).
                    val k = if (f.size >= 0) minOf(n.toLong(), f.size - sent).toInt() else n
                    if (k <= 0) break
                    out.write(buf, 0, k)
                    sent += k
                    conn.touch()
                }
                out.flush()
                if (f.size < 0 || sent == f.size) try { host.onDownloaded(f) } catch (_: Throwable) {}
            }
        } finally {
            slots.release()
        }
    }

    private fun upload(conn: Conn, input: InputStream, out: OutputStream, req: AirWebHttp.Request) {
        if (req.headers.containsKey("transfer-encoding")) { simple(out, 411, "Length Required"); return }
        val len = req.contentLength
        if (len == null || len < 0) { simple(out, if (len == null) 411 else 400, if (len == null) "Length Required" else "Bad Request"); return }
        val rawName = AirWebHttp.baseName(req.query["n"].orEmpty())
        val mime = req.headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.contains('/') && it != "application/octet-stream" }
        val who = touch(AirWebHttp.cleanClientId(req.query["c"]), req.query["w"])
        if (host.offered().size >= limits.maxPool) { json(out, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"full\"}"); return }
        // Wait for a transfer slot first: the browser keeps sending into the connection's buffers meanwhile.
        if (!acquireSlot(conn)) { busy(out); return }
        var reserved = false
        try {
            if (!reserve(len)) { json(out, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"space\"}"); return }
            reserved = true
            val target = try { host.createUpload(rawName, mime, len) } catch (_: Exception) {
                json(out, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"save\"}"); return
            }
            if (req.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
            }
            val id = uploadIds.incrementAndGet()
            try { host.onUploadStarted(id, rawName, len, who) } catch (_: Throwable) {}
            var saved: String? = null
            try {
                val buf = ByteArray(BUF)
                var got = 0L
                var lastReport = 0L
                while (got < len) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), len - got).toInt())
                    if (n < 0) throw IOException("upload ended early")
                    target.out.write(buf, 0, n)
                    got += n
                    conn.touch()
                    if (got - lastReport >= PROGRESS_EVERY) { lastReport = got; try { host.onUploadProgress(id, got, len) } catch (_: Throwable) {} }
                }
                target.out.flush()
                saved = target.commit()
            } catch (e: Exception) {
                target.discard()
                throw e
            } finally {
                try { host.onUploadFinished(id, if (saved != null) target else null, saved) } catch (_: Throwable) {}
            }
            json(out, 200, "OK", "{\"ok\":true,\"name\":${AirWebHttp.jsonString(saved ?: "")}}")
        } finally {
            if (reserved) unreserve(len)
            slots.release()
        }
    }
}

/** HTTP pieces of [AirWebServer]: request parsing and response headers. Pure Kotlin, unit-tested. */
internal object AirWebHttp {
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_HEADERS = 100

    class Request(
        val method: String,
        /** Decoded path without the query. */
        val path: String,
        val query: Map<String, String>,
        /** Lower-case names. */
        val headers: Map<String, String>,
    ) {
        /** Null when absent; -1 when malformed or repeated with different values. */
        val contentLength: Long? get() = headers["content-length"]?.let { v ->
            if (v.isEmpty() || v.length > 18 || v.any { it !in '0'..'9' }) -1L else v.toLong()
        }
    }

    /**
     * Request line + headers, or null if malformed / too large / not HTTP. Reads no body bytes. Throws
     * [java.net.SocketTimeoutException] when the head hasn't fully arrived by [deadlineNanos] (`System.nanoTime()`
     * scale): a client dribbling bytes can't hold a connection open.
     */
    fun readRequest(input: InputStream, deadlineNanos: Long = Long.MAX_VALUE): Request? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (++total > MAX_HEAD) return null
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) throw java.net.SocketTimeoutException("request head too slow")
            if (c == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                line.setLength(0)
                if (l.isEmpty()) { if (lines.isEmpty()) continue else break } // tolerate a stray CRLF before the request
                lines += l
                if (lines.size > MAX_HEADERS + 1) return null
            } else line.append(c.toChar()) // ISO-8859-1: one byte, one char
        }
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val method = parts[0]
        if (method.isEmpty() || method.any { it !in 'A'..'Z' }) return null
        val target = parts[1]
        if (!target.startsWith("/")) return null
        val rawPath = target.substringBefore('?')
        val path = decode(rawPath, plusIsSpace = false) ?: return null
        val query = parseQuery(target.substringAfter('?', "")) ?: return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i <= 0) return null
            val name = l.substring(0, i).trim().lowercase(Locale.ROOT)
            val value = l.substring(i + 1).trim()
            val prev = headers[name]
            if (name == "content-length" && prev != null && prev != value) return null // request smuggling guard
            headers[name] = value
        }
        return Request(method, path, query, headers)
    }

    /** `a=1&b=x%20y` → map; null when an escape is malformed. */
    fun parseQuery(q: String): Map<String, String>? {
        if (q.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = decode(pair.substringBefore('='), plusIsSpace = true) ?: return null
            val v = decode(pair.substringAfter('=', ""), plusIsSpace = true) ?: return null
            if (k !in m) m[k] = v // first wins
        }
        return m
    }

    private fun decode(s: String, plusIsSpace: Boolean): String? = try {
        URLDecoder.decode(if (plusIsSpace) s else s.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) { null }

    /** [matrix] (rows of modules, true = dark) as an SVG with the 4-module quiet zone scanners need, black on white. */
    fun qrSvg(matrix: Array<BooleanArray>): String {
        val n = matrix.size
        val q = 4
        val sb = StringBuilder(2048)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n + 2 * q).append(' ').append(n + 2 * q)
            .append("\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"")
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!matrix[y][x]) { x++; continue }
                var e = x
                while (e < n && matrix[y][e]) e++
                sb.append('M').append(x + q).append(' ').append(y + q).append('h').append(e - x).append("v1h-").append(e - x).append('z')
                x = e
            }
        }
        sb.append("\"/></svg>")
        return sb.toString()
    }

    /** The page's random client id, or null when absent or not 8 to 40 of `A-Z a-z 0-9 _ -`. */
    fun cleanClientId(raw: String?): String? =
        raw?.takeIf { it.length in 8..40 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' } }

    /** A device label for the page's "who is here": no control characters, collapsed spaces, 40 characters. */
    fun cleanLabel(raw: String?): String {
        val t = (raw ?: "").filter { it >= ' ' && it != '\u007f' && it != '\u2028' && it != '\u2029' }
            .replace(Regex("\\s+"), " ").trim()
        val cut = if (t.length > 40) t.take(40).trimEnd() else t
        return cut.ifEmpty { "Browser" }
    }

    /** A short fingerprint of the pool (ids, names, sizes, owners): the page sends it back to skip an unchanged list. */
    fun signature(pool: List<AirWebServer.WebFile>): String {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        fun mix(x: Long) { h = (h xor x) * 0x100000001b3L }
        fun mix(s: String?) { if (s == null) mix(-1L) else { s.forEach { mix(it.code.toLong()) }; mix(0x1f) } }
        pool.forEach { f -> mix(f.id.toLong()); mix(f.name); mix(f.size); mix(f.added); mix(f.by); mix(f.owner) }
        mix(pool.size.toLong())
        return java.lang.Long.toHexString(h)
    }

    /** Constant-time token check. */
    fun tokenMatches(given: String?, expected: String): Boolean {
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), expected.toByteArray(StandardCharsets.UTF_8))
    }

    /** The last path segment of an uploaded name (browsers send only the name; old IE sent the full path). */
    fun baseName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\').trim()

    fun head(code: Int, reason: String, type: String, length: Long?, extra: String = ""): String = buildString {
        append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        append("Content-Type: ").append(type).append("\r\n")
        if (length != null) append("Content-Length: ").append(length).append("\r\n")
        append("Cache-Control: no-store\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("Referrer-Policy: no-referrer\r\n")
        append(extra)
        append("Connection: close\r\n\r\n")
    }

    const val PAGE_HEADERS =
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" +
            "X-Frame-Options: DENY\r\n"

    /** `attachment; filename="ascii"; filename*=UTF-8''percent-encoded` (RFC 6266 / 5987). */
    fun contentDisposition(name: String): String {
        val clean = name.filter { it >= ' ' && it != '\u007f' }.ifEmpty { "file" }
        val ascii = clean.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''${rfc5987(clean)}"
    }

    private fun rfc5987(s: String): String = buildString {
        for (b in s.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "!#\$&+-.^_`|~") append(ch)
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    fun escapeHtml(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' || c == ' ' || c == ' ' || c == '<' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    fun expiredPage(): String = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>AirWeb</title>
<style>body{font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;background:#f4f4f3;color:#1b1b1b;margin:0;padding:24px;line-height:1.5}
@media (prefers-color-scheme:dark){body{background:#161616;color:#ececec}}main{max-width:32rem;margin:10vh auto}</style></head>
<body><main><h1 style="font-size:1.4rem">This link has stopped working</h1>
<p>Scan the code on the phone again to get a new link.</p></main></body></html>"""
}
