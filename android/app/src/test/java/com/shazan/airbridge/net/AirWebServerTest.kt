package com.shazan.airbridge.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The AirWeb server end to end over real sockets on 127.0.0.1, with a temp folder standing in for Downloads. */
class AirWebServerTest {

    private lateinit var dir: File
    private lateinit var server: AirWebServer
    private var port = 0
    private val token = AirWebServer.newToken()
    private val offered = Collections.synchronizedList(ArrayList<Pair<AirWebServer.WebFile, File?>>())
    private val finished = Collections.synchronizedList(ArrayList<String?>())
    private val downloaded = AtomicInteger()
    private var free = -1L

    private inner class TempUpload(val part: File, val name: String) : AirWebServer.UploadTarget {
        override val out: OutputStream = FileOutputStream(part).buffered(256 * 1024)
        override fun commit(): String {
            out.close()
            val f = File(dir, name)
            check(f.canonicalFile.parentFile == dir.canonicalFile) { "escaped the folder" }
            assertTrue(part.renameTo(f))
            return name
        }
        override fun discard() { try { out.close() } catch (_: IOException) {}; part.delete() }
    }

    private val host = object : AirWebServer.Host {
        val taken = HashSet<String>()
        override fun deviceName() = "Dad's <Phone>"
        override fun page() = "<html><title>{{NAME}}</title></html>"
        override fun offered() = offered.map { it.first }
        override fun open(file: AirWebServer.WebFile): InputStream? = offered.firstOrNull { it.first.id == file.id }?.second?.inputStream()
            ?: if (file.size < 0) java.io.ByteArrayInputStream("streamed without a length".toByteArray()) else null
        override fun freeSpace() = free
        override fun createUpload(rawName: String, mime: String?, size: Long): AirWebServer.UploadTarget = synchronized(taken) {
            val clean = Saver.sanitize(rawName)
            var name = clean
            var i = 2
            while (name in taken || File(dir, name).exists()) {
                val dot = clean.lastIndexOf('.')
                name = if (dot > 0) "${clean.substring(0, dot)} ($i)${clean.substring(dot)}" else "$clean ($i)"
                i++
            }
            taken += name
            TempUpload(File(dir, ".$name.part"), name)
        }
        override fun onUploadFinished(id: Long, target: AirWebServer.UploadTarget?, savedName: String?) { finished += savedName }
        override fun onDownloaded(file: AirWebServer.WebFile) { downloaded.incrementAndGet() }
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("airweb").toFile()
        // One test machine is every "browser" here, so the per-address cap is lifted (AirWebPoolTest covers it).
        server = AirWebServer(token, host, AirWebServer.Limits(perIp = 10_000))
        port = server.start(0, 0..0) // any free port
    }

    @After fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    // ---- helpers ----

    private fun url(path: String, withToken: Boolean = true): URL {
        val sep = if (path.contains('?')) "&" else "?"
        return URL("http://127.0.0.1:$port$path${if (withToken) "${sep}t=$token" else ""}")
    }

    private fun get(path: String, withToken: Boolean = true): Pair<Int, ByteArray> {
        val c = url(path, withToken).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 10000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        return code to body
    }

    private fun upload(name: String, data: ByteArray, type: String = "application/octet-stream"): Pair<Int, String> {
        val c = url("/u?n=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        c.setFixedLengthStreamingMode(data.size.toLong())
        c.outputStream.use { it.write(data) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        c.disconnect()
        return code to body
    }

    /** One raw request; returns the whole response (head + body) as bytes. */
    private fun raw(request: ByteArray, thenClose: Boolean = false): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10000
            s.getOutputStream().write(request)
            s.getOutputStream().flush()
            if (thenClose) { s.shutdownOutput() }
            val out = ByteArrayOutputStream()
            try { s.getInputStream().copyTo(out) } catch (_: IOException) {}
            return String(out.toByteArray(), Charsets.ISO_8859_1)
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun addOffered(name: String, bytes: ByteArray, mime: String? = null): AirWebServer.WebFile {
        val f = File.createTempFile("offer", ".bin", dir.parentFile).apply { writeBytes(bytes); deleteOnExit() }
        val wf = AirWebServer.WebFile(offered.size + 1, name, bytes.size.toLong(), mime)
        offered += wf to f
        return wf
    }

    // ---- tests ----

    @Test fun everythingNeedsTheToken() {
        assertEquals(403, get("/", withToken = false).first)
        assertEquals(403, get("/api/state", withToken = false).first)
        assertEquals(403, get("/?t=wrong", withToken = false).first)
        assertEquals(403, get("/?t=${token.dropLast(1)}", withToken = false).first)
        assertEquals(403, get("/d/1/x?t=", withToken = false).first)
        assertEquals(403, get("/favicon.ico", withToken = false).first)
        val r = raw("POST /u?n=x.txt HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
        assertTrue(r, r.startsWith("HTTP/1.1 403"))
        assertTrue(dir.listFiles()!!.isEmpty())
        // The expired page tells the person what to do.
        assertTrue(String(get("/", withToken = false).second).contains("Scan the code on the phone again"))
    }

    @Test fun servesThePageWithTheEscapedName() {
        val (code, body) = get("/")
        assertEquals(200, code)
        assertEquals("<html><title>Dad&#39;s &lt;Phone&gt;</title></html>", String(body))
        val c = url("/").openConnection() as HttpURLConnection
        assertTrue(c.getHeaderField("Content-Security-Policy").contains("default-src 'none'"))
        assertEquals("no-store", c.getHeaderField("Cache-Control"))
        c.disconnect()
    }

    @Test fun stateListsOfferedFiles() {
        addOffered("a \"quoted\" name.txt", "hello".toByteArray())
        free = 12345
        val (code, body) = get("/api/state")
        assertEquals(200, code)
        // "now" is the server clock (for "1 min ago" on the page); everything else is exact.
        val before = System.currentTimeMillis()
        val json = String(body)
        val now = Regex("\"now\":(\\d+),").find(json)!!.groupValues[1].toLong()
        assertTrue(now >= before - 5_000 && now <= System.currentTimeMillis())
        val v = Regex("\"v\":\"([0-9a-f]+)\"").find(json)!!.groupValues[1]
        assertEquals("{\"name\":\"Dad's \\u003cPhone>\",\"free\":12345,\"now\":$now,\"poll\":2000,\"v\":\"$v\",\"gn\":0,\"act\":[],\"count\":0,\"peers\":[]," +
            "\"files\":[{\"id\":1,\"name\":\"a \\\"quoted\\\" name.txt\",\"size\":5,\"added\":0}]}", json)
    }

    @Test fun downloadStreamsTheExactBytesWithHeaders() {
        val data = ByteArray(3 * 1024 * 1024 + 17).also { Random(1).nextBytes(it) }
        val wf = addOffered("Hindi नमस्ते.bin", data, "application/x-test")
        val c = url("/d/${wf.id}/" + URLEncoder.encode(wf.name, "UTF-8")).openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals(data.size.toLong(), c.getHeaderField("Content-Length").toLong())
        assertEquals("application/x-test", c.contentType)
        assertTrue(c.getHeaderField("Content-Disposition").contains("filename*=UTF-8''Hindi%20%E0%A4%A8"))
        assertArrayEquals(sha(data), sha(c.inputStream.use { it.readBytes() }))
        c.disconnect()
        Thread.sleep(100)
        assertEquals(1, downloaded.get())
        assertEquals(404, get("/d/99").first)
        assertEquals(404, get("/d/abc").first)
        assertEquals(404, get("/nothing").first)
    }

    @Test fun headAndUnknownLength() {
        val wf = addOffered("x.bin", ByteArray(1000))
        val r = raw("HEAD /d/${wf.id}?t=$token HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertTrue(r, r.startsWith("HTTP/1.1 200"))
        assertTrue(r.contains("Content-Length: 1000\r\n"))
        assertTrue(r.endsWith("\r\n\r\n")) // no body
        // A provider that can't tell the size: streamed until the connection closes.
        offered += AirWebServer.WebFile(50, "s.txt", -1, null) to null
        val (code, body) = get("/d/50")
        assertEquals(200, code)
        assertEquals("streamed without a length", String(body))
    }

    @Test fun uploadSavesTheFileUnderACleanUniqueName() {
        val data = "first".toByteArray()
        assertEquals(200 to "{\"ok\":true,\"name\":\"notes.txt\"}", upload("notes.txt", data, "text/plain"))
        assertEquals(200 to "{\"ok\":true,\"name\":\"notes (2).txt\"}", upload("notes.txt", "second".toByteArray()))
        assertEquals(200, upload("../../../etc/passwd", "x".toByteArray()).first)
        assertEquals(200, upload("..", ByteArray(0)).first)
        assertEquals("first", File(dir, "notes.txt").readText())
        assertEquals("second", File(dir, "notes (2).txt").readText())
        assertTrue(File(dir, "passwd").exists())
        assertEquals(0L, File(dir, "file").length())
        assertEquals(setOf("notes.txt", "notes (2).txt", "passwd", "file"), dir.list()!!.toSet())
    }

    @Test fun largeUploadIsStreamed() {
        // 64 MiB generated on the fly: never held in memory on either side.
        val size = 64L * 1024 * 1024
        val c = url("/u?n=big.bin").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setFixedLengthStreamingMode(size)
        val md = MessageDigest.getInstance("SHA-256")
        c.outputStream.use { out ->
            val buf = ByteArray(1 shl 16)
            val rnd = Random(7)
            var left = size
            while (left > 0) {
                rnd.nextBytes(buf)
                val n = minOf(left, buf.size.toLong()).toInt()
                md.update(buf, 0, n); out.write(buf, 0, n); left -= n
            }
        }
        assertEquals(200, c.responseCode)
        c.disconnect()
        val f = File(dir, "big.bin")
        assertEquals(size, f.length())
        val md2 = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md2.update(b, 0, n) } }
        assertArrayEquals(md.digest(), md2.digest())
    }

    @Test fun cutOffUploadIsDiscarded() {
        val r = raw("POST /u?t=$token&n=half.bin HTTP/1.1\r\nContent-Length: 100000\r\n\r\n".toByteArray() + ByteArray(5000), thenClose = true)
        assertFalse(r.startsWith("HTTP/1.1 200"))
        Thread.sleep(200)
        assertEquals(listOf<String?>(null), finished.toList())
        assertTrue(dir.list()!!.isEmpty()) // neither the file nor its .part remains
    }

    @Test fun uploadNeedsALength() {
        val r = raw("POST /u?t=$token&n=a.txt HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray())
        assertTrue(r, r.startsWith("HTTP/1.1 411"))
        val r2 = raw("POST /u?t=$token&n=a.txt HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(r2, r2.startsWith("HTTP/1.1 411"))
        assertTrue(raw("GET /u?t=$token HTTP/1.1\r\n\r\n".toByteArray()).startsWith("HTTP/1.1 405"))
        assertTrue(raw("garbage\r\n\r\n".toByteArray()).startsWith("HTTP/1.1 400"))
    }

    @Test fun expectContinueAndNoSpace() {
        val r = raw("POST /u?t=$token&n=c.txt HTTP/1.1\r\nContent-Length: 3\r\nExpect: 100-continue\r\n\r\nabc".toByteArray())
        assertTrue(r, r.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200"))
        assertEquals("abc", File(dir, "c.txt").readText())
        free = 2
        val r2 = raw("POST /u?t=$token&n=d.txt HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
        assertTrue(r2, r2.startsWith("HTTP/1.1 507"))
        assertFalse(File(dir, "d.txt").exists())
    }

    @Test fun manyConcurrentDownloadsAndUploads() {
        val data = ByteArray(512 * 1024).also { Random(3).nextBytes(it) }
        val wf = addOffered("shared.bin", data)
        val pool = Executors.newFixedThreadPool(24)
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val done = CountDownLatch(40)
        repeat(40) { i ->
            pool.execute {
                try {
                    if (i % 2 == 0) {
                        val (code, body) = get("/d/${wf.id}")
                        assertEquals(200, code); assertArrayEquals(data, body)
                    } else {
                        assertEquals(200, upload("up.bin", data).first)
                    }
                } catch (t: Throwable) { errors += t } finally { done.countDown() }
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
        val ups = dir.list()!!.filter { it.startsWith("up") }
        assertEquals(20, ups.size)
        assertEquals(20, ups.toSet().size) // every upload got its own name
        ups.forEach { assertArrayEquals(data, File(dir, it).readBytes()) }
        assertEquals(0, server.activeTransfers)
    }

    @Test fun stopCutsConnectionsAndRestartKeepsTheToken() {
        val c = url("/u?n=slow.bin").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setFixedLengthStreamingMode(10_000_000L)
        val out = c.outputStream
        out.write(ByteArray(100_000)); out.flush()
        Thread.sleep(200)
        assertEquals(1, server.activeTransfers)
        server.stop()
        assertFalse(server.isRunning)
        try { repeat(200) { out.write(ByteArray(65536)) }; out.close() } catch (_: IOException) {}
        Thread.sleep(200)
        assertTrue(dir.list()!!.isEmpty())
        // Same token, preferred port: a page that is still open keeps working.
        val again = server.start(port, 0..0)
        assertEquals(port, again)
        assertEquals(200, get("/api/state").first)
    }

    @Test fun usesTheAirWebPortRange() {
        val other = AirWebServer(AirWebServer.newToken(), host)
        try {
            val p = other.start()
            assertTrue(p in AirWebServer.PORTS)
            assertEquals("http://192.168.1.20:$p/?t=abc", AirWebServer.url("192.168.1.20", p, "abc"))
        } finally { other.stop() }
    }
}
