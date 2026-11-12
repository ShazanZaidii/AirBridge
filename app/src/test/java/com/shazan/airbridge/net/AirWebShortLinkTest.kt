package com.shazan.airbridge.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The short link: the code logic with a fake clock, then the routes over real sockets. */
class AirWebShortLinkTest {

    private var now = 1_000_000L
    private fun link(maxMisses: Int = 8) = AirWebShortLink(ttlMs = 600_000, maxMisses = maxMisses, windowMs = 60_000, clock = { now })

    @Test fun `a code is six characters from the clear alphabet`() {
        val s = link().issue()
        assertEquals(6, s.code.length)
        assertTrue(s.code.all { it in AirWebShortLink.ALPHABET })
        assertFalse(s.code.any { it in "01OIL" })
        assertEquals(600L, s.secondsLeft)
    }

    @Test fun `asking again gives the same live code and the time left`() {
        val l = link()
        val a = l.issue()
        now += 125_000
        val b = l.issue()
        assertEquals(a.code, b.code)
        assertEquals(475L, b.secondsLeft)
    }

    @Test fun `the right code matches in any case and a wrong one does not`() {
        val l = link()
        val c = l.issue().code
        assertEquals(AirWebShortLink.Outcome.MATCH, l.check(c))
        assertEquals(AirWebShortLink.Outcome.MATCH, l.check(c.lowercase()))
        assertEquals(AirWebShortLink.Outcome.MISS, l.check(if (c[0] == '2') "3" + c.substring(1) else "2" + c.substring(1)))
        assertEquals(AirWebShortLink.Outcome.MISS, l.check(c + "2"))
        assertEquals(AirWebShortLink.Outcome.MISS, l.check(null))
        assertEquals(AirWebShortLink.Outcome.MISS, l.check(""))
    }

    @Test fun `nothing matches before one was asked for`() {
        assertEquals(AirWebShortLink.Outcome.MISS, link().check("ABC234"))
    }

    @Test fun `a code expires after ten minutes and a new one is made`() {
        val l = link()
        val old = l.issue().code
        now += 599_000
        assertEquals(AirWebShortLink.Outcome.MATCH, l.check(old))
        now += 2_000
        assertEquals(AirWebShortLink.Outcome.MISS, l.check(old))
        val fresh = l.issue()
        assertEquals(600L, fresh.secondsLeft)
        assertEquals(AirWebShortLink.Outcome.MATCH, l.check(fresh.code))
    }

    @Test fun `guessing is locked out for the rest of the window, even for the right code`() {
        val l = link(maxMisses = 5)
        val c = l.issue().code
        repeat(5) { assertEquals(AirWebShortLink.Outcome.MISS, l.check("ZZZZZ$it".take(6))) }
        assertEquals(AirWebShortLink.Outcome.LOCKED, l.check("ZZZZZZ"))
        assertEquals(AirWebShortLink.Outcome.LOCKED, l.check(c))
        now += 61_000
        assertEquals(AirWebShortLink.Outcome.MATCH, l.check(c))
    }

    @Test fun `fresh codes differ`() {
        val seen = HashSet<String>()
        repeat(50) { val l = link(); seen += l.issue().code }
        assertTrue("codes should not repeat", seen.size > 45)
        assertNotEquals(1, seen.size)
    }

    // ---- over real sockets ----

    private class Host : AirWebServer.Host {
        override fun deviceName() = "Test Phone"
        override fun page() = "<html>page</html>"
        override fun offered() = emptyList<AirWebServer.WebFile>()
        override fun open(file: AirWebServer.WebFile): InputStream? = ByteArrayInputStream(ByteArray(0))
        override fun createUpload(rawName: String, mime: String?, size: Long): AirWebServer.UploadTarget = throw java.io.IOException("no")
    }

    private val token = AirWebServer.newToken()
    private var server: AirWebServer? = null
    private var port = 0
    @After fun tearDown() { server?.stop() }
    private fun start() { server = AirWebServer(token, Host(), AirWebServer.Limits(perIp = 10_000)).also { port = it.start(0, 0..0) } }

    private class Reply(val code: Int, val location: String?, val body: String)

    private fun call(method: String, path: String): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.setRequestProperty("Connection", "close")
        if (method == "POST") { c.doOutput = true; c.outputStream.close() }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        return Reply(code, c.getHeaderField("Location"), stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty())
    }

    private fun code(body: String) = Regex("\"code\":\"([A-Z0-9]+)\"").find(body)!!.groupValues[1]

    @Test fun `asking for a short link needs the token and POST`() {
        start()
        assertEquals(403, call("POST", "/api/short").code)
        assertEquals(405, call("GET", "/api/short?t=$token").code)
        val r = call("POST", "/api/short?t=$token")
        assertEquals(200, r.code)
        assertEquals(6, code(r.body).length)
        assertTrue(r.body.contains("\"ttl\":600"))
    }

    @Test fun `the short code sends a browser on to the real link without a token`() {
        start()
        val c = code(call("POST", "/api/short?t=$token").body)
        val r = call("GET", "/j/$c")
        assertEquals(302, r.code)
        assertEquals("/?t=$token", r.location)
        assertEquals(302, call("GET", "/j/${c.lowercase()}").code)
    }

    @Test fun `a wrong or unissued code gets a plain page and no redirect`() {
        start()
        val r = call("GET", "/j/ABC234")
        assertEquals(404, r.code)
        assertNull(r.location)
        assertFalse(r.body.contains(token))
        assertTrue(r.body.contains("short link"))
    }

    @Test fun `too many wrong codes lock the short link out`() {
        start()
        val c = code(call("POST", "/api/short?t=$token").body)
        var locked = 0
        repeat(12) { if (call("GET", "/j/ZZZZZ$it".take(9)).code == 429) locked++ }
        assertTrue("some tries should be refused", locked > 0)
        assertEquals(429, call("GET", "/j/$c").code)
        // The real link keeps working while the short one is locked.
        assertEquals(200, call("GET", "/?t=$token").code)
    }
}
