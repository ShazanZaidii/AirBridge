package com.shazan.airbridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AirWebHttpTest {

    private fun parse(s: String) = AirWebHttp.readRequest(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)))

    @Test fun parsesRequestLineQueryAndHeaders() {
        val r = parse("POST /u?t=abc&n=My%20Photo%2B1.jpg HTTP/1.1\r\nHost: 192.168.1.20:47650\r\nContent-Length: 1234\r\nContent-Type: image/jpeg\r\n\r\nBODY")!!
        assertEquals("POST", r.method)
        assertEquals("/u", r.path)
        assertEquals("abc", r.query["t"])
        assertEquals("My Photo+1.jpg", r.query["n"])
        assertEquals(1234L, r.contentLength)
        assertEquals("image/jpeg", r.headers["content-type"])
    }

    @Test fun readsNoBodyBytes() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\nrest".toByteArray())
        assertNotNull(AirWebHttp.readRequest(input))
        assertEquals("rest", String(input.readBytes()))
    }

    @Test fun decodesUtf8NamesAndPath() {
        val r = parse("GET /d/3/%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87+x.pdf?t=k HTTP/1.1\r\n\r\n")!!
        assertEquals("/d/3/नमस्ते+x.pdf", r.path) // '+' in a path is a plus, not a space
        val q = parse("POST /u?t=k&n=caf%C3%A9+menu.pdf HTTP/1.1\r\nContent-Length: 0\r\n\r\n")!!
        assertEquals("café menu.pdf", q.query["n"])
    }

    @Test fun rejectsMalformedRequests() {
        assertNull(parse(""))
        assertNull(parse("GET / HTTP/1.1\r\nHost: x\r\n")) // never ends
        assertNull(parse("GET /\r\n\r\n")) // HTTP/0.9
        assertNull(parse("GET http://evil/ HTTP/1.1\r\n\r\n")) // absolute form
        assertNull(parse("get / HTTP/1.1\r\n\r\n"))
        assertNull(parse("GET / SPDY/3\r\n\r\n"))
        assertNull(parse("GET /?n=%zz HTTP/1.1\r\n\r\n"))
        assertNull(parse("GET / HTTP/1.1\r\nNoColonHere\r\n\r\n"))
        assertNull(parse("POST /u HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\n")) // smuggling
        assertNull(parse("GET /" + "a".repeat(17 * 1024) + " HTTP/1.1\r\n\r\n")) // too large
        assertNull(parse("GET / HTTP/1.1\r\n" + "X-A: b\r\n".repeat(150) + "\r\n")) // too many headers
    }

    @Test fun toleratesLeadingBlankLineAndBareLf() {
        val r = parse("\r\nGET /api/state?t=k HTTP/1.1\nHost: x\n\n")!!
        assertEquals("/api/state", r.path)
        assertEquals("x", r.headers["host"])
    }

    @Test fun contentLengthValidation() {
        assertEquals(-1L, parse("POST /u HTTP/1.1\r\nContent-Length: -5\r\n\r\n")!!.contentLength)
        assertEquals(-1L, parse("POST /u HTTP/1.1\r\nContent-Length: 12abc\r\n\r\n")!!.contentLength)
        assertEquals(-1L, parse("POST /u HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n")!!.contentLength)
        assertNull(parse("POST /u HTTP/1.1\r\n\r\n")!!.contentLength)
        assertEquals(0L, parse("POST /u HTTP/1.1\r\nContent-Length: 0\r\n\r\n")!!.contentLength)
        assertEquals(5_000_000_000L, parse("POST /u HTTP/1.1\r\nContent-Length: 5000000000\r\n\r\n")!!.contentLength)
    }

    @Test fun queryFirstValueWins() {
        assertEquals(mapOf("t" to "good", "x" to ""), AirWebHttp.parseQuery("t=good&t=evil&x&&"))
    }

    @Test fun tokenCheck() {
        val t = AirWebServer.newToken()
        assertEquals(22, t.length)
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(AirWebHttp.tokenMatches(t, t))
        assertFalse(AirWebHttp.tokenMatches(null, t))
        assertFalse(AirWebHttp.tokenMatches("", t))
        assertFalse(AirWebHttp.tokenMatches(t.dropLast(1), t))
        assertFalse(AirWebHttp.tokenMatches(t + "x", t))
        assertFalse(AirWebHttp.tokenMatches(t.uppercase(), t.lowercase()))
        assertFalse(AirWebHttp.tokenMatches("", ""))
        assertTrue(AirWebServer.newToken() != AirWebServer.newToken())
    }

    @Test fun uploadNamesCannotEscapeTheFolder() {
        fun clean(raw: String) = Saver.sanitize(AirWebHttp.baseName(raw))
        assertEquals("passwd", clean("../../etc/passwd"))
        assertEquals("evil.exe", clean("..\\..\\Windows\\evil.exe"))
        assertEquals("file", clean(".."))
        assertEquals("file", clean("../.."))
        assertEquals("file", clean(""))
        assertEquals("abc.txt", clean("a\u0000b\nc.txt"))
        assertEquals("_CON.txt", clean("CON.txt"))
        assertEquals("photo.jpg", clean("C:\\Users\\me\\photo.jpg"))
        assertEquals("a b.txt", clean("  a b.txt  "))
        assertEquals("name.txt", clean("na<>:\"|?*me.txt"))
        val long = clean("x".repeat(500) + ".pdf")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.toByteArray().size <= 200)
        assertFalse(clean("/storage/emulated/0/../../data/x").contains('/'))
    }

    @Test fun contentDispositionCarriesUtf8Names() {
        assertEquals("attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf", AirWebHttp.contentDisposition("report.pdf"))
        assertEquals("attachment; filename=\"caf_ _1_.txt\"; filename*=UTF-8''caf%C3%A9%20%221%22.txt", AirWebHttp.contentDisposition("café \"1\".txt"))
        // A newline can never split the header.
        assertFalse(AirWebHttp.contentDisposition("a\r\nSet-Cookie: x").contains('\n'))
    }

    @Test fun escaping() {
        assertEquals("&lt;b&gt;Tom &amp; &quot;Jo&#39;s&quot;&lt;/b&gt;", AirWebHttp.escapeHtml("<b>Tom & \"Jo's\"</b>"))
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script>\\u0001\"", AirWebHttp.jsonString("a\"b\\c\n</script>\u0001"))
    }

    @Test fun headIsWellFormed() {
        val h = AirWebHttp.head(200, "OK", "text/plain", 5, "X-A: b\r\n")
        assertTrue(h.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(h.contains("Content-Length: 5\r\n"))
        assertTrue(h.contains("X-A: b\r\n"))
        assertTrue(h.endsWith("Connection: close\r\n\r\n"))
        assertFalse(AirWebHttp.head(200, "OK", "x/y", null).contains("Content-Length"))
    }
}
