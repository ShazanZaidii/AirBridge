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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AirWeb: a small HTTP/1.1 server so a phone or computer without AirBridge can swap files with this
 * phone from its browser. The page, the file list, downloads and uploads all need the session token
 * (`?t=…`, 128 random bits) or the answer is 403. One thread per connection, `Connection: close` on
 * every response, files streamed in both directions (never held in memory).
 *
 * Plain JVM code (no Android types) so it is tested end to end with real sockets; [Host] supplies the
 * files, the destination for uploads and the page.
 *
 * Routes (all need `t`):
 *  - `GET /` the page
 *  - `GET /api/state` JSON: phone name, offered files, free space
 *  - `GET|HEAD /d/<id>[/<name>]` download one offered file
 *  - `POST /u?n=<file name>` upload one file, raw body, Content-Length required
 */
class AirWebServer(val token: String, private val host: Host) {

    /** [added]: when it was offered (epoch ms), for "1 min ago" on the page. */
    class WebFile(val id: Int, val name: String, val size: Long, val mime: String?, val added: Long = 0L)

    /** Where one upload is written. [commit] makes it visible and returns its final name. */
    interface UploadTarget {
        val out: OutputStream
        fun commit(): String
        fun discard()
    }

    interface Host {
        fun deviceName(): String
        fun page(): String
        fun offered(): List<WebFile>
        fun open(file: WebFile): InputStream?
        /** Bytes free for uploads, or -1 when unknown. */
        fun freeSpace(): Long = -1
        /** Throws [IOException] if it can't be created (no space, storage error). */
        fun createUpload(rawName: String, mime: String?, size: Long): UploadTarget
        fun onUploadStarted(id: Long, name: String, size: Long) {}
        fun onUploadProgress(id: Long, done: Long, total: Long) {}
        /** [target] and [savedName] are null when the upload failed. */
        fun onUploadFinished(id: Long, target: UploadTarget?, savedName: String?) {}
        fun onDownloaded(file: WebFile) {}
    }

    companion object {
        val PORTS = 47650..47659
        const val MAX_CONNECTIONS = 48
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUF = 64 * 1024
        private const val PROGRESS_EVERY = 256L * 1024
        private val rng = SecureRandom()

        /** 16 random bytes as 22 base64url characters. */
        fun newToken(): String {
            val b = ByteArray(16)
            rng.nextBytes(b)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        fun url(address: String, port: Int, token: String) = "http://$address:$port/?t=$token"
    }

    @Volatile private var server: ServerSocket? = null
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val connections = AtomicInteger()
    private val transfers = AtomicInteger()
    private val uploadIds = AtomicLong()

    val port: Int get() = server?.localPort ?: 0
    val isRunning: Boolean get() = server?.isClosed == false
    /** Downloads and uploads in progress right now. */
    val activeTransfers: Int get() = transfers.get()

    /**
     * Listens on [preferred] if it is free, else the first free port of [ports]. Returns the port.
     * Throws [IOException] if none is free.
     */
    @Synchronized
    fun start(preferred: Int = 0, ports: IntRange = PORTS): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val candidates = (listOf(preferred).filter { it > 0 } + ports.toList()).distinct()
        val ss = candidates.firstNotNullOfOrNull { p ->
            try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } } catch (_: IOException) { null }
        } ?: throw IOException("no free port in $ports")
        server = ss
        Thread({ acceptLoop(ss) }, "airweb-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    /** Stops listening and cuts every open connection (unfinished uploads are discarded). */
    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        val open = synchronized(sockets) { sockets.toList() }
        open.forEach { try { it.close() } catch (_: IOException) {} }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = try { ss.accept() } catch (_: IOException) { break }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet()
                try {
                    sock.soTimeout = 2_000
                    sock.getOutputStream().write(AirWebHttp.head(503, "Service Unavailable", "text/plain", 0).toByteArray())
                } catch (_: IOException) {}
                try { sock.close() } catch (_: IOException) {}
                continue
            }
            sockets.add(sock)
            Thread({
                try { serve(sock) } finally {
                    sockets.remove(sock)
                    connections.decrementAndGet()
                    try { sock.close() } catch (_: IOException) {}
                }
            }, "airweb-conn").apply { isDaemon = true }.start()
        }
    }

    // ---------------------------------------------------------------- one request per connection

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = READ_TIMEOUT_MS
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream(), BUF)
            val out = sock.getOutputStream()
            val req = AirWebHttp.readRequest(input)
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
                req.path == "/api/state" -> if (req.method == "GET") state(out) else simple(out, 405, "Method Not Allowed")
                req.path.startsWith("/d/") -> when (req.method) {
                    "GET", "HEAD" -> download(out, req)
                    else -> simple(out, 405, "Method Not Allowed")
                }
                req.path == "/u" -> if (req.method == "POST") upload(input, out, req) else simple(out, 405, "Method Not Allowed")
                else -> simple(out, 404, "Not Found")
            }
            out.flush()
        } catch (_: Exception) {
            // Client went away, timed out, or storage failed; nothing more to answer. Never let it escape:
            // an uncaught exception on this thread would end the app.
        }
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

    private fun state(out: OutputStream) {
        val files = host.offered().joinToString(",") {
            "{\"id\":${it.id},\"name\":${AirWebHttp.jsonString(it.name)},\"size\":${it.size},\"added\":${it.added}}"
        }
        // "now" lets the page turn "added" into "1 min ago" even when the two clocks disagree.
        json(out, 200, "OK", "{\"name\":${AirWebHttp.jsonString(host.deviceName())},\"free\":${host.freeSpace()}," +
            "\"now\":${System.currentTimeMillis()},\"files\":[$files]}")
    }

    private fun download(out: OutputStream, req: AirWebHttp.Request) {
        val id = req.path.removePrefix("/d/").substringBefore('/').toIntOrNull()
        val f = host.offered().firstOrNull { it.id == id }
        if (f == null) { simple(out, 404, "Not Found"); return }
        val headOnly = req.method == "HEAD"
        val stream: InputStream? = if (headOnly) null else try { host.open(f) } catch (_: Exception) { null }
        if (!headOnly && stream == null) { simple(out, 410, "Gone"); return } // the sharing app took its grant back
        transfers.incrementAndGet()
        try {
            stream.use { input ->
                val extra = "Content-Disposition: ${AirWebHttp.contentDisposition(f.name)}\r\n"
                out.write(AirWebHttp.head(200, "OK", f.mime?.takeIf { it.contains('/') } ?: "application/octet-stream",
                    f.size.takeIf { it >= 0 }, extra).toByteArray())
                if (input == null) return
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
                }
                out.flush()
                if (f.size < 0 || sent == f.size) host.onDownloaded(f)
            }
        } finally {
            transfers.decrementAndGet()
        }
    }

    private fun upload(input: InputStream, out: OutputStream, req: AirWebHttp.Request) {
        if (req.headers.containsKey("transfer-encoding")) { simple(out, 411, "Length Required"); return }
        val len = req.contentLength
        if (len == null || len < 0) { simple(out, if (len == null) 411 else 400, if (len == null) "Length Required" else "Bad Request"); return }
        val rawName = AirWebHttp.baseName(req.query["n"].orEmpty())
        val free = host.freeSpace()
        if (free >= 0 && len > free) { json(out, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"space\"}"); return }
        val mime = req.headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.contains('/') && it != "application/octet-stream" }
        val target = try { host.createUpload(rawName, mime, len) } catch (_: Exception) {
            json(out, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"save\"}"); return
        }
        if (req.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
        }
        val id = uploadIds.incrementAndGet()
        transfers.incrementAndGet()
        host.onUploadStarted(id, rawName, len)
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
                if (got - lastReport >= PROGRESS_EVERY) { lastReport = got; host.onUploadProgress(id, got, len) }
            }
            target.out.flush()
            saved = target.commit()
        } catch (e: Exception) {
            target.discard()
            throw e
        } finally {
            transfers.decrementAndGet()
            host.onUploadFinished(id, if (saved != null) target else null, saved)
        }
        json(out, 200, "OK", "{\"ok\":true,\"name\":${AirWebHttp.jsonString(saved ?: "")}}")
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

    /** Request line + headers, or null if malformed / too large / not HTTP. Reads no body bytes. */
    fun readRequest(input: InputStream): Request? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (++total > MAX_HEAD) return null
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
