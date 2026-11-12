package com.shazan.airbridge.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** The streamed ZIP, read back with Java's own unzip as the independent check. */
class AirWebZipTest {

    private fun entry(name: String, bytes: ByteArray, time: Long = 1_700_000_000_000L) =
        AirWebZip.Entry(name, bytes.size.toLong(), time) { ByteArrayInputStream(bytes) }

    private fun random(n: Int, seed: Long = 1) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun build(entries: List<AirWebZip.Entry>, zip64At: Long = 0xFFFFFFFFL, zip64Entries: Int = 0xFFFF): Pair<AirWebZip, ByteArray> {
        val zip = AirWebZip(entries, zip64At, zip64Entries)
        val out = ByteArrayOutputStream()
        zip.write(out)
        return zip to out.toByteArray()
    }

    /** Reads the archive twice: front to back (streaming readers) and by its central directory (what Explorer and Finder do). */
    private fun readBack(bytes: ByteArray, streaming: Boolean = true): Map<String, ByteArray> {
        val sequential = LinkedHashMap<String, ByteArray>()
        if (streaming) ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                sequential[e.name] = zin.readBytes()
            }
        }
        val tmp = File.createTempFile("airweb", ".zip")
        try {
            tmp.writeBytes(bytes)
            ZipFile(tmp).use { zf ->
                val entries = zf.entries().toList()
                if (streaming) assertEquals("central directory lists the same files", sequential.keys.toList(), entries.map { it.name })
                for (e in entries) {
                    val data = zf.getInputStream(e).use { it.readBytes() }
                    if (streaming) assertArrayEquals("${e.name} (central directory)", sequential[e.name], data) else sequential[e.name] = data
                    assertEquals("${e.name} size", data.size.toLong(), e.size)
                    assertEquals("${e.name} crc", CRC32().apply { update(data) }.value, e.crc)
                }
            }
        } finally { tmp.delete() }
        return sequential
    }

    @Test fun aFewFilesComeBackExactlyAndTheLengthIsAnnounced() {
        val a = random(1000, 1); val b = "hello".toByteArray(); val c = ByteArray(0)
        val (zip, bytes) = build(listOf(entry("a.bin", a), entry("b.txt", b), entry("empty.txt", c)))
        assertEquals(bytes.size.toLong(), zip.length)
        val back = readBack(bytes)
        assertEquals(listOf("a.bin", "b.txt", "empty.txt"), back.keys.toList())
        assertArrayEquals(a, back["a.bin"]); assertArrayEquals(b, back["b.txt"]); assertArrayEquals(c, back["empty.txt"])
    }

    @Test fun everyBlockBoundaryWorks() {
        val sizes = listOf(0, 1, 2, 65_534, 65_535, 65_536, 65_537, 131_069, 131_070, 131_071, 200_000)
        val files = sizes.mapIndexed { i, n -> "f$i.bin" to random(n, i.toLong()) }
        val (zip, bytes) = build(files.map { entry(it.first, it.second) })
        assertEquals(bytes.size.toLong(), zip.length)
        val back = readBack(bytes)
        files.forEach { (name, data) -> assertArrayEquals("$name (${data.size} bytes)", data, back[name]) }
    }

    @Test fun twoHundredFilesIsAPieceOfCake() {
        val rnd = Random(7)
        val files = (0 until 200).map { i -> "photo-$i.jpg" to random(rnd.nextInt(30_000), i.toLong()) }
        val t0 = System.nanoTime()
        val (zip, bytes) = build(files.map { entry(it.first, it.second) })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(bytes.size.toLong(), zip.length)
        val back = readBack(bytes)
        assertEquals(200, back.size)
        files.forEach { (name, data) -> assertArrayEquals(name, data, back[name]) }
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test fun moreThanSixtyFiveThousandFilesUsesZip64Directory() {
        val files = (0 until 70_000).map { i -> entry("n$i", byteArrayOf((i and 0xff).toByte())) }
        val (zip, bytes) = build(files)
        assertEquals(bytes.size.toLong(), zip.length)
        val tmp = File.createTempFile("airweb", ".zip")
        try {
            tmp.writeBytes(bytes)
            ZipFile(tmp).use { zf ->
                assertEquals(70_000, zf.size())
                assertEquals(69_999 and 0xff, zf.getInputStream(zf.getEntry("n69999")).read())
                assertEquals(5 and 0xff, zf.getInputStream(zf.getEntry("n5")).read())
            }
            var n = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (z.nextEntry != null) n++ }
            assertEquals(70_000, n)
        } finally { tmp.delete() }
    }

    @Test fun zip64PathsWorkWhenLimitsAreLowered() {
        // Every file of 1000 bytes or more takes the ZIP64 form, and 5 files or more get a ZIP64 directory.
        val files = listOf(100, 999, 1_000, 5_000, 70_000, 2, 3, 400_000).mapIndexed { i, n -> "z$i.bin" to random(n, i.toLong()) }
        val (zip, bytes) = build(files.map { entry(it.first, it.second) }, zip64At = 1_000, zip64Entries = 5)
        assertEquals(bytes.size.toLong(), zip.length)
        // By the central directory only: a stream reader like Java's ZipInputStream sizes the descriptor by the real size.
        val back = readBack(bytes, streaming = false)
        files.forEach { (name, data) -> assertArrayEquals(name, data, back[name]) }
    }

    @Test fun zip64OffsetsAreWrittenWhenTheDirectoryStartsPastTheLimit() {
        val files = (0 until 12).map { i -> "o$i.bin" to random(3_000 + i, i.toLong()) }
        // After a few files the offset is past 20 000: those entries carry their offset in a ZIP64 extra.
        val (zip, bytes) = build(files.map { entry(it.first, it.second) }, zip64At = 20_000, zip64Entries = 1_000)
        assertEquals(bytes.size.toLong(), zip.length)
        val back = readBack(bytes)
        files.forEach { (name, data) -> assertArrayEquals(name, data, back[name]) }
    }

    @Test fun filesOfUnknownLengthStillZipAndTheLengthIsUnknown() {
        val a = random(70_000, 3)
        val streaming = AirWebZip.Entry("stream.bin", -1, 0) { ByteArrayInputStream(a) }
        val (zip, bytes) = build(listOf(entry("known.txt", "x".toByteArray()), streaming, entry("after.txt", "y".toByteArray())))
        assertEquals(-1L, zip.length)
        val back = readBack(bytes)
        assertArrayEquals(a, back["stream.bin"])
        assertEquals("x", String(back["known.txt"]!!)); assertEquals("y", String(back["after.txt"]!!))
    }

    @Test fun namesAreSafeAndUnique() {
        val names = AirWebZip.uniqueNames(listOf("a.txt", "A.TXT", "../../etc/passwd", "dir/b.txt", "c\\d.txt", "", "...", "résumé 日本語.pdf", "a.txt", "no-ext", "no-ext", "x\u0000y.txt"))
        assertEquals(listOf("a.txt", "A (2).TXT", "passwd", "b.txt", "d.txt", "file", "file (2)", "résumé 日本語.pdf", "a (3).txt", "no-ext", "no-ext (2)", "xy.txt"), names)
        assertTrue(AirWebZip.uniqueNames(listOf("x".repeat(500) + ".jpg")).single().toByteArray().size <= 200)
        assertTrue(AirWebZip.uniqueNames(listOf("x".repeat(500) + ".jpg")).single().endsWith(".jpg"))
        // And they survive a round trip.
        val (_, bytes) = build(listOf(entry("日本語 résumé.txt", "ok".toByteArray())))
        assertEquals(setOf("日本語 résumé.txt"), readBack(bytes).keys)
    }

    @Test fun aFileThatShrinksOrVanishesAbortsInsteadOfProducingABrokenArchive() {
        val shrinking = AirWebZip.Entry("s.bin", 1_000, 0) { ByteArrayInputStream(ByteArray(10)) }
        try { build(listOf(shrinking)); fail("should have failed") } catch (_: IOException) {}
        val gone = AirWebZip.Entry("g.bin", 5, 0) { null }
        try { build(listOf(gone)); fail("should have failed") } catch (_: IOException) {}
    }

    @Test fun aFileThatGrowsIsCutAtItsAnnouncedSize() {
        val growing = AirWebZip.Entry("g.bin", 100, 0) { ByteArrayInputStream(ByteArray(5_000) { 9 }) }
        val (zip, bytes) = build(listOf(growing))
        assertEquals(bytes.size.toLong(), zip.length)
        assertEquals(100, readBack(bytes)["g.bin"]!!.size)
    }

    @Test fun aHugeFileStreamsInConstantMemory() {
        // 300 MB of zeros through the writer into a counting sink: the archive length matches and nothing is buffered.
        val size = 300L * 1024 * 1024
        val zeros = object : InputStream() {
            var left = size
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt(); java.util.Arrays.fill(b, off, off + n, 0); left -= n; return n
            }
        }
        val zip = AirWebZip(listOf(AirWebZip.Entry("zeros.bin", size, 0) { zeros }))
        var counted = 0L
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        zip.write(object : OutputStream() {
            override fun write(b: Int) { counted++ }
            override fun write(b: ByteArray, off: Int, len: Int) { counted += len }
        })
        assertEquals(zip.length, counted)
        val grown = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - before
        assertTrue("heap grew by $grown bytes", grown < 64L * 1024 * 1024)
    }

    @Test fun parseIdsHandlesRangesAndGarbage() {
        assertEquals(null, AirWebHttp.parseIds(null, 100))
        assertEquals(null, AirWebHttp.parseIds("  ", 100))
        assertEquals(listOf(1, 2, 3, 7, 1_000_001), AirWebHttp.parseIds("1-3,7,1000001", 100))
        assertEquals(listOf(5, 4), AirWebHttp.parseIds("5,4,5", 100)) // order kept, no repeats
        assertEquals(listOf(1, 2), AirWebHttp.parseIds("1,2,x,3-1,-4,9999999999,--", 100).orEmpty().take(2))
        assertEquals((1..10).toList(), AirWebHttp.parseIds("1-1000", 10)) // capped
        assertFalse(AirWebHttp.parseIds("0-5000000", 100).orEmpty().size > 100)
    }
}
