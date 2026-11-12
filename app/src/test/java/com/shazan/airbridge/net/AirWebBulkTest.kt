package com.shazan.airbridge.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

/**
 * Download all / download a selection as one zip, and "already downloaded" per browser. Real sockets on 127.0.0.1.
 */
class AirWebBulkTest {

    private class Entry(val file: AirWebServer.WebFile, val open: () -> InputStream?)

    private class PoolHost : AirWebServer.Host {
        val pool = CopyOnWriteArrayList<Entry>()
        override fun deviceName() = "Test Phone"
        override fun page() = "<html></html>"
        override fun offered() = pool.map { it.file }
        override fun open(file: AirWebServer.WebFile): InputStream? = pool.firstOrNull { it.file.id == file.id }?.open?.invoke()
        override fun createUpload(rawName: String, mime: String?, size: Long): AirWebServer.UploadTarget = throw java.io.IOException("no uploads here")
        fun add(id: Int, name: String, bytes: ByteArray) {
            pool += Entry(AirWebServer.WebFile(id, name, bytes.size.toLong(), null, 1_700_000_000_000L + id)) { ByteArrayInputStream(bytes) }
        }
    }

    private val token = AirWebServer.newToken()
    private val host = PoolHost()
    private var server: AirWebServer? = null
    private var port = 0

    private fun start(limits: AirWebServer.Limits = AirWebServer.Limits(perIp = 10_000)) {
        server = AirWebServer(token, host, limits).also { port = it.start(0, 0..0) }
    }

    @After fun tearDown() { server?.stop() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String): HttpURLConnection {
        val sep = if (path.contains('?')) "&" else "?"
        return (URL("http://127.0.0.1:$port$path${sep}t=$token").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 60_000; setRequestProperty("Connection", "close")
        }
    }

    private class Reply(val code: Int, val headers: Map<String, String?>, val body: ByteArray)

    private fun get(path: String, method: String = "GET"): Reply {
        val c = open(path); c.requestMethod = method
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        val h = mapOf("content-length" to c.getHeaderField("Content-Length"), "content-type" to c.getHeaderField("Content-Type"),
            "content-disposition" to c.getHeaderField("Content-Disposition"))
        c.disconnect()
        return Reply(code, h, body)
    }

    private fun unzip(body: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(body)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    private fun state(client: String, g: String? = null): String {
        val r = get("/api/state?c=$client" + (if (g != null) "&g=$g" else ""))
        assertEquals(200, r.code)
        return String(r.body)
    }

    private fun intList(json: String, key: String): List<Int>? =
        Regex("\"$key\":\\[([0-9,]*)]").find(json)?.groupValues?.get(1)?.split(',')?.filter { it.isNotEmpty() }?.map { it.toInt() }

    private fun awaitTrue(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
        return cond()
    }

    private fun bytes(n: Int, seed: Int) = ByteArray(n).also { Random(seed.toLong()).nextBytes(it) }

    // ---- the zip ----

    @Test fun downloadAllGivesOneZipWithEveryFileAndItsRealLength() {
        start()
        val data = (1..5).associate { "file$it.bin" to bytes(10_000 * it, it) }
        data.entries.forEachIndexed { i, (n, b) -> host.add(i + 1, n, b) }
        val r = get("/z")
        assertEquals(200, r.code)
        assertEquals("application/zip", r.headers["content-type"])
        assertEquals(r.body.size.toString(), r.headers["content-length"])
        assertTrue(r.headers["content-disposition"]!!.contains("AirWeb-5-files.zip"))
        val z = unzip(r.body)
        assertEquals(data.keys.toList(), z.keys.toList())
        data.forEach { (n, b) -> assertArrayEquals(n, b, z[n]) }
    }

    @Test fun aSelectionByIdsAndRangesBecomesAZipOfJustThose() {
        start()
        (1..10).forEach { host.add(it, "f$it.txt", "content $it".toByteArray()) }
        val r = get("/z?ids=" + enc("2-4,9,7"))
        assertEquals(200, r.code)
        assertEquals(listOf("f2.txt", "f3.txt", "f4.txt", "f9.txt", "f7.txt"), unzip(r.body).keys.toList())
        // Ids that aren't in the pool are ignored; nothing left means 404.
        assertEquals(listOf("f1.txt"), unzip(get("/z?ids=1,99").body).keys.toList())
        assertEquals(404, get("/z?ids=99-120").code)
    }

    @Test fun headAnnouncesTheZipWithoutSendingIt() {
        start()
        host.add(1, "a.bin", bytes(5_000, 1)); host.add(2, "b.bin", bytes(7_000, 2))
        val full = get("/z")
        val head = get("/z", "HEAD")
        assertEquals(200, head.code)
        assertEquals(full.body.size.toString(), head.headers["content-length"])
        assertEquals(0, head.body.size)
    }

    @Test fun twoHundredFilesAndEvenAThousandZipQuicklyAndIntact() {
        start()
        val rnd = Random(3)
        val files = (1..1000).associate { i -> "photo-$i.jpg" to bytes(rnd.nextInt(8_000), i) }
        files.entries.forEachIndexed { i, (n, b) -> host.add(i + 1, n, b) }
        val t0 = System.nanoTime()
        val r200 = get("/z?ids=1-200")
        val ms200 = (System.nanoTime() - t0) / 1_000_000
        val z200 = unzip(r200.body)
        assertEquals(200, z200.size)
        z200.forEach { (n, b) -> assertArrayEquals(n, files[n], b) }
        assertEquals(r200.body.size.toString(), r200.headers["content-length"])
        val all = unzip(get("/z").body)
        assertEquals(1000, all.size)
        all.forEach { (n, b) -> assertArrayEquals(n, files[n], b) }
        assertTrue("200 files took $ms200 ms", ms200 < 3_000)
        assertEquals(0, server!!.activeTransfers)
    }

    @Test fun twoFilesWithTheSameNameBothMakeItIntoTheZip() {
        start()
        host.add(1, "photo.jpg", "first".toByteArray()); host.add(2, "photo.jpg", "second".toByteArray())
        val z = unzip(get("/z").body)
        assertEquals(listOf("photo.jpg", "photo (2).jpg"), z.keys.toList())
        assertEquals("first", String(z["photo.jpg"]!!)); assertEquals("second", String(z["photo (2).jpg"]!!))
    }

    @Test fun aFileThatCannotBeReadIsLeftOutAndTheRestStillZips() {
        start()
        host.add(1, "ok.txt", "fine".toByteArray())
        host.pool += Entry(AirWebServer.WebFile(2, "gone.txt", 10, null)) { null }
        val z = unzip(get("/z").body)
        assertEquals(listOf("ok.txt"), z.keys.toList())
    }

    // ---- already downloaded ----

    @Test fun aBrowserRemembersWhatItHasDownloadedInFullAndOthersDont() {
        start()
        host.add(1, "a.bin", bytes(2_000, 1)); host.add(2, "b.bin", bytes(3_000, 2)); host.add(3, "c.bin", bytes(4_000, 3))
        val a = "browserA0001"; val b = "browserB0002"
        assertEquals(emptyList<Int>(), intList(state(a), "got"))
        assertEquals(200, get("/d/2/b.bin?c=$a").code)
        val sa = state(a)
        assertEquals(listOf(2), intList(sa, "got"))
        assertTrue(sa, sa.contains("\"gn\":1"))
        // B sees nothing as downloaded.
        assertEquals(emptyList<Int>(), intList(state(b), "got"))
        // Told the count it already knows, the server doesn't resend the list.
        assertNull(intList(state(a, g = "1"), "got"))
        // Without a client id nothing is recorded.
        assertEquals(200, get("/d/3/c.bin").code)
        assertEquals(listOf(2), intList(state(a), "got"))
    }

    @Test fun aZipMarksEveryFileInItAsDownloaded() {
        start()
        (1..6).forEach { host.add(it, "f$it.bin", bytes(1_000 + it, it)) }
        val a = "browserA0001"
        assertEquals(200, get("/z?ids=2-4&c=$a").code)
        assertEquals(listOf(2, 3, 4), intList(state(a), "got"))
        assertEquals(200, get("/z?c=$a").code)
        assertEquals((1..6).toList(), intList(state(a), "got"))
    }

    @Test fun aZipThatIsCutShortOnlyCountsTheFilesThatWentThrough() {
        start()
        (1..3).forEach { host.add(it, "big$it.bin", bytes(8 * 1024 * 1024, it)) }
        val a = "browserA0001"
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET /z?c=$a&t=$token HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(64 * 1024)
            var read = 0
            while (read < 200_000) { val n = s.getInputStream().read(buf); if (n < 0) break; read += n }
        } // closed: the rest never reaches the browser
        assertTrue(awaitTrue(10_000) { server!!.activeTransfers == 0 })
        val got = intList(state(a), "got").orEmpty()
        assertFalse("the last file never finished: $got", 3 in got)
    }

    @Test fun whatIsBeingDownloadedShowsAsActiveUntilItFinishes() {
        start()
        val gate = java.util.concurrent.CountDownLatch(1)
        host.pool += Entry(AirWebServer.WebFile(1, "slow.bin", 100_000, null)) { object : InputStream() {
            var left = 100_000
            override fun read(): Int = if (left-- > 0) 1 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                if (left == 100_000) { val n = minOf(len, 10_000); left -= n; return n } // some data, then wait
                gate.await(); val n = minOf(len, left); left -= n; return n
            }
        } }
        val a = "browserA0001"
        val t = Thread { try { get("/d/1/slow.bin?c=$a") } catch (_: Exception) {} }.apply { start() }
        assertTrue(awaitTrue(5_000) { intList(state(a), "act") == listOf(1) })
        assertEquals(emptyList<Int>(), intList(state(a), "got"))
        gate.countDown(); t.join(10_000)
        assertTrue(awaitTrue(5_000) { intList(state(a), "act") == emptyList<Int>() && intList(state(a), "got") == listOf(1) })
    }

    @Test fun theDownloadLogIsBoundedAndForgetsTheLeastRecentBrowsers() {
        start(AirWebServer.Limits(perIp = 10_000, maxDownloadLogs = 3))
        host.add(1, "a.bin", bytes(100, 1))
        (1..5).forEach { assertEquals(200, get("/d/1/a.bin?c=browser$it" + "00000").code) }
        assertEquals(emptyList<Int>(), intList(state("browser100000"), "got")) // forgotten
        assertEquals(listOf(1), intList(state("browser500000"), "got"))      // still there
    }

    @Test fun filesThatLeaveThePoolDropOutOfTheDownloadedList() {
        start()
        host.add(1, "a.bin", bytes(100, 1)); host.add(2, "b.bin", bytes(100, 2))
        val a = "browserA0001"
        get("/d/1/a.bin?c=$a"); get("/d/2/b.bin?c=$a")
        assertEquals(listOf(1, 2), intList(state(a), "got"))
        host.pool.removeAll { it.file.id == 1 }
        assertEquals(listOf(2), intList(state(a), "got"))
    }
}
