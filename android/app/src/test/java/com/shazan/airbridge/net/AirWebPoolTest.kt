package com.shazan.airbridge.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The shared pool and the limits that keep AirWeb standing with many browsers: uploads from one browser are
 * downloadable by the others, who is connected, and what happens with slow clients, too many connections,
 * too many transfers, little space and stalled sockets. Real sockets on 127.0.0.1.
 */
class AirWebPoolTest {

    private class Entry(val file: AirWebServer.WebFile, val open: () -> InputStream?)

    private class MemUpload(val name: String) : AirWebServer.UploadTarget {
        val bytes = ByteArrayOutputStream()
        override val out: OutputStream get() = bytes
        override fun commit() = name
        override fun discard() {}
    }

    /** A host that keeps its pool in memory, merging uploads in the way the phone and the Mac do. */
    private class TestHost : AirWebServer.Host {
        val pool = CopyOnWriteArrayList<Entry>()
        val started = Collections.synchronizedMap(HashMap<Long, AirWebServer.Peer?>())
        val peerEvents = CopyOnWriteArrayList<List<AirWebServer.Peer>>()
        val nextHostId = AtomicInteger(1)
        @Volatile var free = -1L
        @Volatile var qrFor: String? = null

        fun offer(name: String, bytes: ByteArray, open: (() -> InputStream?)? = null): AirWebServer.WebFile {
            val f = AirWebServer.WebFile(nextHostId.getAndIncrement(), name, bytes.size.toLong(), null, 1_000L)
            pool += Entry(f, open ?: { ByteArrayInputStream(bytes) })
            return f
        }

        override fun deviceName() = "Test Phone"
        override fun page() = "<html>{{NAME}}</html>"
        override fun offered() = pool.map { it.file }
        override fun open(file: AirWebServer.WebFile): InputStream? = pool.firstOrNull { it.file.id == file.id }?.open?.invoke()
        override fun freeSpace() = free
        override fun createUpload(rawName: String, mime: String?, size: Long): AirWebServer.UploadTarget = MemUpload(rawName.ifBlank { "file" })
        override fun onUploadStarted(id: Long, name: String, size: Long, by: AirWebServer.Peer?) { started[id] = by }
        override fun onUploadFinished(id: Long, target: AirWebServer.UploadTarget?, savedName: String?) {
            val up = target as? MemUpload ?: return
            val by = started[id]
            val data = up.bytes.toByteArray()
            pool += Entry(AirWebServer.WebFile(AirWebServer.UPLOAD_ID_BASE + id.toInt(), savedName ?: up.name, data.size.toLong(), null,
                System.currentTimeMillis(), by?.label, by?.id)) { ByteArrayInputStream(data) }
        }
        override fun onPeersChanged(peers: List<AirWebServer.Peer>) { peerEvents += peers }
        override fun qr(text: String): Array<BooleanArray>? {
            qrFor = text
            return Array(21) { y -> BooleanArray(21) { x -> (x + y) % 3 == 0 } }
        }
    }

    private val token = AirWebServer.newToken()
    private val host = TestHost()
    private var server: AirWebServer? = null
    private var port = 0

    private fun start(limits: AirWebServer.Limits = AirWebServer.Limits(perIp = 10_000)): AirWebServer {
        val s = AirWebServer(token, host, limits)
        port = s.start(0, 0..0)
        server = s
        return s
    }

    @After fun tearDown() { server?.stop() }

    // ---- client helpers ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(path: String): HttpURLConnection {
        val sep = if (path.contains('?')) "&" else "?"
        val c = URL("http://127.0.0.1:$port$path${sep}t=$token").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 30000
        c.setRequestProperty("Connection", "close")
        return c
    }

    private fun get(path: String): Pair<Int, ByteArray> {
        val c = open(path)
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        return code to body
    }

    private fun state(client: String?, label: String? = null, v: String? = null): String {
        val q = buildString {
            append("/api/state?x=1")
            if (client != null) append("&c=").append(enc(client))
            if (label != null) append("&w=").append(enc(label))
            if (v != null) append("&v=").append(enc(v))
        }
        val (code, body) = get(q)
        assertEquals(200, code)
        return String(body)
    }

    private fun upload(client: String?, label: String?, name: String, data: ByteArray): Pair<Int, String> {
        val q = "/u?n=${enc(name)}" + (if (client != null) "&c=${enc(client)}" else "") + (if (label != null) "&w=${enc(label)}" else "")
        val c = open(q)
        c.requestMethod = "POST"; c.doOutput = true
        c.setFixedLengthStreamingMode(data.size.toLong())
        c.outputStream.use { it.write(data) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        c.disconnect()
        return code to body
    }

    private fun count(json: String) = Regex("\"count\":(\\d+)").find(json)!!.groupValues[1].toInt()
    private fun fileIds(json: String) = Regex("\"files\":\\[(.*)]").find(json)?.groupValues?.get(1).orEmpty()

    private fun idle(): Socket = Socket("127.0.0.1", port).apply { soTimeout = 15000 }

    /** True when the server has closed [s] (reads end of stream or reset) within [ms]. */
    private fun closedWithin(s: Socket, ms: Int): Boolean {
        s.soTimeout = ms
        return try { s.getInputStream().read() < 0 } catch (_: java.net.SocketTimeoutException) { false } catch (_: IOException) { true }
    }

    private fun awaitTrue(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
        return cond()
    }

    // ---- the pool ----

    @Test fun whatOneBrowserUploadsAnotherCanDownload() {
        start()
        host.offer("from-host.txt", "host file".toByteArray())
        val data = ByteArray(300_000).also { Random(5).nextBytes(it) }
        assertEquals(200, upload("browserA001", "Chrome on Windows", "photo.jpg", data).first)

        val seenByB = state("browserB002", "Safari on iPhone")
        assertTrue(seenByB, seenByB.contains("\"name\":\"photo.jpg\""))
        assertTrue(seenByB, seenByB.contains("\"by\":\"Chrome on Windows\""))
        assertFalse("B did not upload it", seenByB.contains("\"own\":true"))
        assertTrue("the host's own file is in the pool too", seenByB.contains("from-host.txt"))
        val seenByA = state("browserA001", "Chrome on Windows")
        assertTrue("A sees it is theirs", seenByA.contains("\"own\":true"))

        val id = Regex("\"id\":(\\d+),\"name\":\"photo.jpg\"").find(seenByB)!!.groupValues[1]
        val (code, body) = get("/d/$id/photo.jpg")
        assertEquals(200, code)
        assertArrayEquals(data, body)
    }

    @Test fun threeBrowsersAndTheHostAllShareOnePool() {
        start()
        host.offer("host.txt", "h".toByteArray())
        listOf("aaaaaaa1" to "Chrome on Mac", "bbbbbbb2" to "Safari on iPhone", "ccccccc3" to "Chrome on Android").forEach { (c, w) ->
            assertEquals(200, upload(c, w, "$w.txt".replace(' ', '_'), "from $w".toByteArray()).first)
        }
        listOf("aaaaaaa1", "bbbbbbb2", "ccccccc3", "ddddddd4").forEach { c ->
            val json = state(c)
            assertEquals(4, "\"name\":\"".toRegex().findAll(fileIds(json)).count())
        }
    }

    @Test fun connectedBrowsersAreListedWithUniqueNamesAndLeaveWhenQuiet() {
        start(AirWebServer.Limits(perIp = 10_000, peerTtlMs = 1_200))
        state("browserA001", "Chrome on Windows")
        state("browserB002", "Chrome on Windows")
        val json = state("browserC003", "Safari on iPhone")
        assertEquals(3, count(json))
        assertTrue(json, json.contains("\"name\":\"Chrome on Windows 1\"") && json.contains("\"name\":\"Chrome on Windows 2\""))
        assertTrue(json.contains("\"name\":\"Safari on iPhone\""))
        assertEquals(3, server!!.peers().size)
        assertNotNull(host.peerEvents.lastOrNull())
        // Nobody polls any more: they drop out (the sweeper runs every 2 s).
        assertTrue(awaitTrue(8_000) { server!!.peers().isEmpty() })
        assertTrue(awaitTrue(2_000) { host.peerEvents.lastOrNull()?.isEmpty() == true })
        assertEquals(0, count(state(null)))
    }

    @Test fun anUnchangedPoolAnswersInAFewBytes() {
        start()
        host.offer("a.txt", "a".toByteArray())
        val first = state("browserA001")
        val v = Regex("\"v\":\"([0-9a-f]+)\"").find(first)!!.groupValues[1]
        assertTrue(first.contains("\"files\""))
        val again = state("browserA001", v = v)
        assertTrue(again, again.contains("\"same\":true") && !again.contains("\"files\""))
        assertTrue("small: ${again.length}", again.length < 400)
        // A new upload changes the version, so the next poll carries the list again.
        assertEquals(200, upload("browserB002", "Firefox on Linux", "b.txt", "b".toByteArray()).first)
        val changed = state("browserA001", v = v)
        assertTrue(changed, changed.contains("\"files\"") && !changed.contains("\"same\""))
    }

    @Test fun theAskingPageIsAlwaysListedEvenInABigCrowd() {
        start()
        repeat(40) { state("crowd%05d".format(it), "Chrome on Windows") }
        val json = state("latecomer1", "Safari on iPhone")
        assertEquals(41, count(json))
        assertTrue(json, json.contains("\"id\":\"latecomer1\""))
        assertEquals(24, "\"id\":".toRegex().findAll(json.substringBefore("\"files\"")).count())
    }

    @Test fun invalidClientIdsAreNotCounted() {
        start()
        assertEquals(0, count(state("x", "Short id")))
        assertEquals(0, count(state("bad id with spaces", "Spaces")))
        assertEquals(0, count(state(null)))
        assertEquals(1, count(state("good-client_1", "Fine")))
    }

    @Test fun labelsAreCleanedAndCapped() {
        assertEquals("Browser", AirWebHttp.cleanLabel(null))
        assertEquals("Browser", AirWebHttp.cleanLabel("   \n\t "))
        assertEquals("a b", AirWebHttp.cleanLabel("a\u0000  b"))
        assertEquals(40, AirWebHttp.cleanLabel("x".repeat(100)).length)
        assertEquals(null, AirWebHttp.cleanClientId("short"))
        assertEquals("abcdefgh", AirWebHttp.cleanClientId("abcdefgh"))
        assertEquals(null, AirWebHttp.cleanClientId("abcdefgh<script>"))
    }

    @Test fun poolFullRefusesMoreUploads() {
        start(AirWebServer.Limits(perIp = 10_000, maxPool = 2))
        host.offer("1", byteArrayOf(1)); host.offer("2", byteArrayOf(2))
        val (code, body) = upload("browserA001", "x", "three.txt", byteArrayOf(3))
        assertEquals(507, code)
        assertTrue(body, body.contains("\"error\":\"full\""))
    }

    @Test fun qrRouteDrawsTheLinkTheBrowserUsed() {
        start()
        val (code, body) = get("/qr.svg")
        assertEquals(200, code)
        val svg = String(body)
        assertTrue(svg, svg.startsWith("<svg") && svg.contains("<path") && svg.contains("viewBox=\"0 0 29 29\""))
        assertEquals("http://127.0.0.1:$port/?t=$token", host.qrFor)
        // Needs the token like everything else.
        val c = URL("http://127.0.0.1:$port/qr.svg").openConnection() as HttpURLConnection
        assertEquals(403, c.responseCode)
        c.disconnect()
    }

    // ---- slow and many ----

    @Test fun silentAndDribblingConnectionsDoNotHoldTheServer() {
        start(AirWebServer.Limits(perIp = 10_000, headDeadlineMs = 800))
        val silent = List(100) { idle() }
        val dribblers = List(10) { idle() }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            try { while (!stop.get()) { dribblers.forEach { try { it.getOutputStream().write('G'.code); it.getOutputStream().flush() } catch (_: IOException) {} }; Thread.sleep(150) } } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try {
            val t0 = System.currentTimeMillis()
            assertEquals(200, get("/api/state").first) // still answered at once with 110 dead-weight connections open
            assertTrue("answered in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
            assertTrue("silent ones are dropped after the head deadline", silent.all { closedWithin(it, 4_000) })
            assertTrue("dribblers too (one byte every 150 ms does not keep a head open)", dribblers.all { closedWithin(it, 4_000) })
            assertTrue(awaitTrue(3_000) { server!!.openConnections == 0 })
        } finally { stop.set(true); (silent + dribblers).forEach { try { it.close() } catch (_: IOException) {} } }
    }

    @Test fun oneAddressCannotTakeMoreThanItsShare() {
        start(AirWebServer.Limits(perIp = 5, headDeadlineMs = 30_000))
        val held = List(5) { idle() }
        Thread.sleep(150)
        val extra = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        extra.getOutputStream().write("GET /api/state?t=$token HTTP/1.1\r\n\r\n".toByteArray())
        val answer = String(extra.getInputStream().readBytes(), Charsets.ISO_8859_1)
        assertTrue(answer, answer.startsWith("HTTP/1.1 503") && answer.contains("Retry-After"))
        extra.close()
        held.forEach { it.close() }
        assertTrue(awaitTrue(3_000) { server!!.openConnections == 0 })
        assertEquals(200, get("/api/state").first) // all well again
    }

    @Test fun tooManyConnectionsGetBusyNotCrash() {
        start(AirWebServer.Limits(perIp = 1000, maxConnections = 6, headDeadlineMs = 30_000))
        val held = List(6) { idle() }
        Thread.sleep(150)
        val answers = List(10) {
            val s = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
            try { s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()); String(s.getInputStream().readBytes(), Charsets.ISO_8859_1) } finally { s.close() }
        }
        assertTrue(answers.joinToString("\n"), answers.all { it.startsWith("HTTP/1.1 503") })
        held.forEach { it.close() }
        assertTrue(awaitTrue(3_000) { server!!.openConnections == 0 })
        assertEquals(200, get("/api/state").first)
    }

    // ---- transfer slots ----

    /** A stream of [total] bytes that takes about [ms] to read, so transfers overlap. */
    private fun slow(total: Int, ms: Long): () -> InputStream? = {
        object : InputStream() {
            var left = total
            val per = maxOf(1L, ms / maxOf(1, total / 65_536))
            override fun read(): Int = if (left-- > 0) 7 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                Thread.sleep(per)
                val n = minOf(len, left, 65_536)
                java.util.Arrays.fill(b, off, off + n, 7)
                left -= n
                return n
            }
        }
    }

    @Test fun transfersQueueInTurnAndNeverExceedTheLimit() {
        start(AirWebServer.Limits(perIp = 10_000, maxTransfers = 2))
        val f = host.offer("big.bin", ByteArray(0), slow(65_536 * 4, 400)).let { AirWebServer.WebFile(it.id, it.name, 65_536L * 4, null) }
        host.pool.removeAll { it.file.id == f.id }
        host.pool += Entry(f, slow(65_536 * 4, 400))
        val peak = AtomicInteger(); val peakWait = AtomicInteger()
        val sampler = Thread {
            try { while (true) { peak.set(maxOf(peak.get(), server!!.activeTransfers)); peakWait.set(maxOf(peakWait.get(), server!!.waitingTransfers)); Thread.sleep(5) } } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        val ok = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) { pool.execute { try { val (c, b) = get("/d/${f.id}"); if (c == 200 && b.size == 65_536 * 4) ok.incrementAndGet() } finally { done.countDown() } } }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        sampler.interrupt(); pool.shutdown()
        assertEquals("every download finished", 8, ok.get())
        assertTrue("at most 2 at a time, saw ${peak.get()}", peak.get() <= 2)
        assertTrue("the rest waited their turn", peakWait.get() >= 3)
        assertEquals(0, server!!.activeTransfers)
        assertEquals(0, server!!.waitingTransfers)
    }

    @Test fun aFullWaitingRoomAnswersBusyWithRetryAfter() {
        start(AirWebServer.Limits(perIp = 10_000, maxTransfers = 1, maxWaiting = 2))
        val gate = CountDownLatch(1)
        val blocker = host.offer("held.bin", ByteArray(0)) { object : InputStream() {
            override fun read(): Int { gate.await(); return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { gate.await(); return -1 }
        } }
        val pool = Executors.newFixedThreadPool(4)
        val codes = Collections.synchronizedList(ArrayList<Int>())
        val retry = Collections.synchronizedList(ArrayList<String?>())
        fun fetch() = pool.submit {
            val c = open("/d/${blocker.id}")
            val code = c.responseCode
            codes += code; retry += c.getHeaderField("Retry-After")
            try { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } } catch (_: IOException) {}
            c.disconnect()
        }
        val first = fetch()
        assertTrue(awaitTrue(3_000) { server!!.activeTransfers == 1 })
        val waiters = listOf(fetch(), fetch())
        assertTrue(awaitTrue(3_000) { server!!.waitingTransfers == 2 })
        val overflow = fetch()
        overflow.get(10, TimeUnit.SECONDS)
        // The first request already has its 200 (the head goes out before the body); the overflow was turned away.
        assertEquals(1, codes.count { it == 503 })
        assertEquals("5", retry[codes.indexOf(503)])
        gate.countDown()
        first.get(10, TimeUnit.SECONDS); waiters.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(listOf(200, 200, 200, 503), codes.sorted())
    }

    @Test fun waitingTooLongGivesUpPolitely() {
        start(AirWebServer.Limits(perIp = 10_000, maxTransfers = 1, transferWaitMs = 600))
        val gate = CountDownLatch(1)
        val blocker = host.offer("held.bin", ByteArray(0)) { object : InputStream() {
            override fun read(): Int { gate.await(); return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { gate.await(); return -1 }
        } }
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit { try { get("/d/${blocker.id}") } catch (_: IOException) {} }
        assertTrue(awaitTrue(3_000) { server!!.activeTransfers == 1 })
        val t0 = System.currentTimeMillis()
        assertEquals(503, get("/d/${blocker.id}").first)
        val waited = System.currentTimeMillis() - t0
        assertTrue("waited $waited ms", waited in 500..3_000)
        gate.countDown(); first.get(10, TimeUnit.SECONDS); pool.shutdown()
    }

    @Test fun uploadsShareTheFreeSpaceTogether() {
        start()
        host.free = 1_000
        val slowUp = open("/u?n=${enc("slow.bin")}&c=browserA001&w=x")
        slowUp.requestMethod = "POST"; slowUp.doOutput = true
        slowUp.setFixedLengthStreamingMode(600L)
        val out = slowUp.outputStream
        out.write(ByteArray(100)); out.flush()
        assertTrue(awaitTrue(3_000) { server!!.activeTransfers == 1 })
        // 600 are spoken for; another 600 doesn't fit in 1000 even though each alone would.
        val (code, body) = upload("browserB002", "y", "second.bin", ByteArray(600))
        assertEquals(507, code)
        assertTrue(body, body.contains("\"error\":\"space\""))
        out.write(ByteArray(500)); out.close()
        assertEquals(200, slowUp.responseCode)
        slowUp.disconnect()
        // The space is free to use again once that upload is done (nothing is "stuck reserved").
        assertEquals(200, upload("browserB002", "y", "third.bin", ByteArray(600)).first)
    }

    @Test fun aClientThatStopsReadingIsCutLoose() {
        start(AirWebServer.Limits(perIp = 10_000, stallMs = 800))
        val big = host.offer("huge.bin", ByteArray(0)) { object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 1); return len }
        } }
        val f = AirWebServer.WebFile(big.id, big.name, 2_000_000_000L, null)
        host.pool.removeAll { it.file.id == f.id }
        host.pool += Entry(f) { object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 1); return len }
        } }
        val s = Socket("127.0.0.1", port)
        s.getOutputStream().write("GET /d/${f.id}/huge.bin?t=$token HTTP/1.1\r\n\r\n".toByteArray())
        s.getOutputStream().flush()
        // ...and never reads. The server's writes fill the buffers and block; the watchdog notices no progress.
        assertTrue(awaitTrue(5_000) { server!!.activeTransfers == 1 })
        assertTrue("slot freed after the stall", awaitTrue(10_000) { server!!.activeTransfers == 0 })
        s.close()
    }

    // ---- the whole crowd ----

    @Test fun fiftyBrowsersAtOnceKeepTheServerHealthy() {
        // The default limits, as shipped. Each "browser" polls every few hundred ms like the page does.
        start(AirWebServer.Limits(perIp = 10_000))
        host.offer("shared.bin", ByteArray(256 * 1024) { 3 })
        val browsers = 60
        val pool = Executors.newFixedThreadPool(browsers + 8)
        val latencies = Collections.synchronizedList(ArrayList<Long>())
        val failures = Collections.synchronizedList(ArrayList<String>())
        val busy = AtomicInteger()
        val uploadsOk = AtomicInteger(); val downloadsOk = AtomicInteger()
        val rounds = 10
        val done = CountDownLatch(browsers + 12)
        val data = ByteArray(512 * 1024).also { Random(1).nextBytes(it) }
        val threadsBefore = Thread.activeCount()
        repeat(browsers) { i ->
            pool.execute {
                try {
                    val me = "browser%03d".format(i)
                    var v: String? = null
                    repeat(rounds) { r ->
                        var tries = 0
                        while (true) {
                            val t0 = System.nanoTime()
                            val c = open("/api/state?c=$me&w=${enc("Chrome on Windows")}" + (v?.let { "&v=$it" } ?: ""))
                            val code = try { c.responseCode } catch (e: IOException) { -1 }
                            val body = if (code == 200) c.inputStream.use { String(it.readBytes()) } else ""
                            c.disconnect()
                            if (code == 200) {
                                latencies += (System.nanoTime() - t0) / 1_000_000
                                if (!body.contains("\"same\":true")) v = Regex("\"v\":\"([0-9a-f]+)\"").find(body)?.groupValues?.get(1)
                                break
                            }
                            busy.incrementAndGet()
                            if (++tries > 3) { failures += "poll $me/$r gave $code"; break }
                            Thread.sleep(200)
                        }
                        Thread.sleep(150L + (i % 5) * 20)
                    }
                } catch (t: Throwable) { failures += t.toString() } finally { done.countDown() }
            }
        }
        repeat(6) { i -> pool.execute {
            try {
                var code = upload("browser%03d".format(i), "Chrome on Windows", "up$i.bin", data).first
                var tries = 0
                while (code == 503 && tries++ < 20) { Thread.sleep(300); code = upload("browser%03d".format(i), "Chrome on Windows", "up$i.bin", data).first }
                if (code == 200) uploadsOk.incrementAndGet() else failures += "upload $i gave $code"
            } catch (t: Throwable) { failures += t.toString() } finally { done.countDown() }
        } }
        repeat(6) { i -> pool.execute {
            try {
                var tries = 0
                while (true) {
                    val (code, b) = get("/d/1/shared.bin")
                    if (code == 200 && b.size == 256 * 1024) { downloadsOk.incrementAndGet(); break }
                    if (++tries > 20) { failures += "download $i gave $code"; break }
                    Thread.sleep(300)
                }
            } catch (t: Throwable) { failures += t.toString() } finally { done.countDown() }
        } }
        assertTrue("all clients finished", done.await(120, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(6, uploadsOk.get())
        assertEquals(6, downloadsOk.get())
        val sorted = latencies.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        assertTrue("p95 poll latency $p95 ms over ${sorted.size} polls (busy answers: ${busy.get()})", p95 < 1_500)
        assertEquals("everyone is listed", browsers, server!!.peers().size)
        // All the uploads reached the pool, and everyone can see them.
        assertEquals(6, host.pool.count { it.file.id >= AirWebServer.UPLOAD_ID_BASE })
        assertTrue(awaitTrue(5_000) { server!!.openConnections == 0 })
        assertEquals(0, server!!.activeTransfers)
        assertTrue("threads settle (before ${threadsBefore}, now ${Thread.activeCount()})", awaitTrue(30_000) { Thread.activeCount() < threadsBefore + 40 })
    }
}
