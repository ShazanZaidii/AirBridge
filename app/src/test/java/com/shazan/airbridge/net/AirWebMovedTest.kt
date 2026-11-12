package com.shazan.airbridge.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A new link for a running session: pages open on the old one are told where to go; other addresses are offered. */
class AirWebMovedTest {
    private class Host(val addrs: List<String> = emptyList()) : AirWebServer.Host {
        override fun deviceName() = "Test Phone"
        override fun page() = "<html>page</html>"
        override fun offered() = emptyList<AirWebServer.WebFile>()
        override fun open(file: AirWebServer.WebFile): InputStream? = ByteArrayInputStream(ByteArray(0))
        override fun createUpload(rawName: String, mime: String?, size: Long): AirWebServer.UploadTarget = throw java.io.IOException("no")
        override fun addresses() = addrs
    }

    private val first = AirWebServer.newToken()
    private var server: AirWebServer? = null
    private var port = 0
    @After fun tearDown() { server?.stop() }
    private fun start(addrs: List<String> = emptyList()) {
        server = AirWebServer(first, Host(addrs), AirWebServer.Limits(perIp = 10_000)).also { port = it.start(0, 0..0) }
    }

    private class Reply(val code: Int, val location: String?, val body: String)
    private fun get(path: String): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("Connection", "close")
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        return Reply(code, c.getHeaderField("Location"), stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty())
    }

    @Test fun `after a new link the old state poll says where the session went`() {
        start()
        val second = server!!.rotate()
        assertNotEquals(first, second)
        assertEquals(second, server!!.token)
        val old = get("/api/state?t=$first&c=abcdefgh1")
        assertEquals(200, old.code)
        assertTrue(old.body, old.body.contains("\"moved\":\"/?t=$second\""))
        assertFalse(old.body.contains("\"files\""))
        val fresh = get("/api/state?t=$second&c=abcdefgh2")
        assertTrue(fresh.body.contains("\"name\":\"Test Phone\""))
        assertFalse(fresh.body.contains("moved"))
    }

    @Test fun `reloading a page on the old link lands on the new one`() {
        start()
        val second = server!!.rotate()
        val r = get("/?t=$first")
        assertEquals(302, r.code)
        assertEquals("/?t=$second", r.location)
        assertEquals(200, get("/?t=$second").code)
    }

    @Test fun `an old link gets nothing else and a guess still gets nothing`() {
        start()
        server!!.rotate()
        assertEquals(403, get("/z?t=$first").code)
        assertEquals(403, get("/d/1?t=$first").code)
        assertEquals(403, get("/api/state?t=${AirWebServer.newToken()}").code)
        assertEquals(403, get("/?t=${AirWebServer.newToken()}").code)
    }

    @Test fun `only the last few old links are remembered`() {
        start()
        val tokens = mutableListOf(first)
        repeat(10) { tokens += server!!.rotate() }
        assertEquals(403, get("/api/state?t=${tokens[0]}").code) // too old
        assertEquals(200, get("/api/state?t=${tokens[9]}").code) // recent
        assertTrue(get("/api/state?t=${tokens[9]}").body.contains("moved"))
    }

    @Test fun `the state lists the other addresses with the current link`() {
        start(listOf("192.168.1.9", "192.168.43.1"))
        val r = get("/api/state?t=$first&c=abcdefgh1")
        assertTrue(r.body, r.body.contains("\"alts\":[\"http://192.168.1.9:$port/?t=$first\",\"http://192.168.43.1:$port/?t=$first\"]"))
    }

    @Test fun `no other addresses leaves the field out`() {
        start()
        assertFalse(get("/api/state?t=$first").body.contains("alts"))
    }
}
