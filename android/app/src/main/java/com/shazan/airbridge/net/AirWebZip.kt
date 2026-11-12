package com.shazan.airbridge.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.zip.CRC32

/**
 * A ZIP archive written as it is sent: files are read once, in 64 KB pieces, and never held in memory or copied to
 * a temporary file, so a few files or a thousand of them cost the same little memory.
 *
 * Nothing is compressed. Each file goes in as "deflate" blocks of kind *stored*, which every unzip tool reads as an
 * ordinary compressed entry (so the CRC and sizes can follow the data in a data descriptor, which "stored" entries
 * can't do portably). That keeps the phone's CPU and battery out of it, and it makes the size of the whole archive
 * exact before the first byte is sent, so the browser shows a real progress bar. Only files of unknown length make it
 * unknown.
 *
 * ZIP64 is used only where needed: a file of 4 GB or more, more than 65 535 files, or an archive past 4 GB. [zip64At]
 * and [zip64Entries] are the limits (tests lower them to exercise those paths with small files). A file whose length
 * isn't known up front is written in the plain form (and fails if it turns out to be 4 GB or more), because readers
 * disagree about how wide the descriptor after a small ZIP64 entry is, and agree only when the file really is huge.
 */
class AirWebZip(
    entries: List<Entry>,
    private val zip64At: Long = 0xFFFFFFFFL,
    private val zip64Entries: Int = 0xFFFF,
) {
    /** One file: [size] is -1 when unknown, [time] is epoch milliseconds, [open] returns null if it can't be read now. */
    class Entry(val name: String, val size: Long, val time: Long, val open: () -> InputStream?)

    private class Done(val crc: Long, val csize: Long, val usize: Long)

    private val files: List<Entry> = entries
    private val names: List<ByteArray> = uniqueNames(entries.map { it.name }).map { it.toByteArray(StandardCharsets.UTF_8) }

    /** The exact length of the archive, or -1 when a file's length isn't known. */
    val length: Long

    init {
        var total = 0L
        var unknown = false
        var offset = 0L
        var cd = 0L
        for ((i, e) in files.withIndex()) {
            if (e.size < 0) { unknown = true; break }
            val z = entryNeedsZip64(e.size)
            val data = storedLength(e.size)
            val header = 30L + names[i].size + (if (z) 20 else 0)
            val dd = if (z) 24L else 16L
            total += header + data + dd
            // Central directory entry: its zip64 extra holds the sizes, plus the offset if that is too big.
            val offsetBig = offset >= zip64At
            val extra = if (z || offsetBig) 4 + (if (z) 16 else 0) + (if (offsetBig) 8 else 0) else 0
            cd += 46L + names[i].size + extra
            offset += header + data + dd
        }
        length = if (unknown) -1 else total + cd + endLength(offset, cd, files.size)
    }

    /** Writes the whole archive to [out]; [entryDone] is told after each file was written in full. */
    fun write(out: OutputStream, entryDone: (Int) -> Unit = {}) {
        val sink = CountingSink(out)
        val done = ArrayList<Done>(files.size)
        val offsets = LongArray(files.size)
        val buf = ByteArray(BLOCK + 5)
        for ((i, e) in files.withIndex()) {
            offsets[i] = sink.count
            val z = e.size >= 0 && entryNeedsZip64(e.size)
            sink.write(localHeader(i, e, z))
            val input = e.open() ?: throw IOException("can't read ${e.name}")
            var crc = CRC32()
            var usize = 0L
            var csize = 0L
            input.use { src ->
                while (true) {
                    val n = readBlock(src, buf, 5, BLOCK, if (e.size >= 0) e.size - usize else Long.MAX_VALUE)
                    if (n == 0) break
                    buf[0] = 0 // BFINAL = 0, BTYPE = stored
                    buf[1] = n.toByte(); buf[2] = (n shr 8).toByte()
                    buf[3] = n.inv().toByte(); buf[4] = (n.inv() shr 8).toByte()
                    crc.update(buf, 5, n)
                    sink.write(buf, 0, 5 + n)
                    usize += n
                    csize += 5 + n
                }
            }
            if (e.size >= 0 && usize != e.size) throw IOException("${e.name} is shorter than announced")
            // A file of unknown length is written in the plain form; one that turns out to need ZIP64 can't be (download it alone).
            if (e.size < 0 && csize + 5 >= zip64At) throw IOException("${e.name} is too big to put in a zip of unknown size")
            // The closing, empty block.
            sink.write(FINAL_BLOCK)
            csize += 5
            done += Done(crc.value, csize, usize)
            sink.write(descriptor(done.last(), z))
            entryDone(i)
        }
        val cdStart = sink.count
        for ((i, e) in files.withIndex()) {
            val d = done[i]
            sink.write(centralHeader(i, e, d, offsets[i], e.size >= 0 && entryNeedsZip64(e.size)))
        }
        val cdSize = sink.count - cdStart
        sink.write(end(cdStart, cdSize, files.size))
        sink.flush()
        if (length >= 0 && sink.count != length) throw IOException("archive length ${sink.count} differs from the announced $length")
    }

    // ---------------------------------------------------------------- pieces

    private fun entryNeedsZip64(size: Long) = size + storedOverhead(size) >= zip64At

    /** Bytes of a file of [size] stored as deflate blocks: the data, 5 bytes per block, and the closing block. */
    private fun storedLength(size: Long) = size + storedOverhead(size)
    private fun storedOverhead(size: Long) = 5L * ((size + BLOCK - 1) / BLOCK) + 5

    private fun localHeader(i: Int, e: Entry, zip64: Boolean): ByteArray {
        val name = names[i]
        val b = Bytes(30 + name.size + (if (zip64) 20 else 0))
        val (date, time) = dos(e.time)
        b.u32(0x04034b50); b.u16(if (zip64) 45 else 20); b.u16(FLAGS); b.u16(8); b.u16(time); b.u16(date)
        b.u32(0) // crc, in the descriptor
        if (zip64) { b.u32(0xFFFFFFFFL); b.u32(0xFFFFFFFFL) } else { b.u32(0); b.u32(0) }
        b.u16(name.size); b.u16(if (zip64) 20 else 0)
        b.bytes(name)
        if (zip64) { b.u16(1); b.u16(16); b.u64(0); b.u64(0) }
        return b.array
    }

    private fun descriptor(d: Done, zip64: Boolean): ByteArray {
        val b = Bytes(if (zip64) 24 else 16)
        b.u32(0x08074b50); b.u32(d.crc)
        if (zip64) { b.u64(d.csize); b.u64(d.usize) } else { b.u32(d.csize); b.u32(d.usize) }
        return b.array
    }

    private fun centralHeader(i: Int, e: Entry, d: Done, offset: Long, zip64: Boolean): ByteArray {
        val name = names[i]
        val offsetBig = offset >= zip64At
        val extra = if (zip64 || offsetBig) 4 + (if (zip64) 16 else 0) + (if (offsetBig) 8 else 0) else 0
        val b = Bytes(46 + name.size + extra)
        val (date, time) = dos(e.time)
        b.u32(0x02014b50); b.u16(45); b.u16(if (zip64 || offsetBig) 45 else 20); b.u16(FLAGS); b.u16(8); b.u16(time); b.u16(date)
        b.u32(d.crc)
        b.u32(if (zip64) 0xFFFFFFFFL else d.csize); b.u32(if (zip64) 0xFFFFFFFFL else d.usize)
        b.u16(name.size); b.u16(extra); b.u16(0); b.u16(0); b.u16(0); b.u32(0)
        b.u32(if (offsetBig) 0xFFFFFFFFL else offset)
        b.bytes(name)
        if (extra > 0) {
            b.u16(1); b.u16(extra - 4)
            if (zip64) { b.u64(d.usize); b.u64(d.csize) }
            if (offsetBig) b.u64(offset)
        }
        return b.array
    }

    private fun needsEnd64(cdStart: Long, cdSize: Long, count: Int) = count >= zip64Entries || cdStart >= zip64At || cdSize >= zip64At
    private fun endLength(cdStart: Long, cdSize: Long, count: Int) = 22L + if (needsEnd64(cdStart, cdSize, count)) 56 + 20 else 0

    private fun end(cdStart: Long, cdSize: Long, count: Int): ByteArray {
        val z = needsEnd64(cdStart, cdSize, count)
        val b = Bytes(22 + if (z) 56 + 20 else 0)
        if (z) {
            // ZIP64 end of central directory, then its locator.
            b.u32(0x06064b50); b.u64(44); b.u16(45); b.u16(45); b.u32(0); b.u32(0)
            b.u64(count.toLong()); b.u64(count.toLong()); b.u64(cdSize); b.u64(cdStart)
            b.u32(0x07064b50); b.u32(0); b.u64(cdStart + cdSize); b.u32(1)
        }
        b.u32(0x06054b50); b.u16(0); b.u16(0)
        b.u16(if (z) 0xFFFF else count); b.u16(if (z) 0xFFFF else count)
        b.u32(if (z) 0xFFFFFFFFL else cdSize); b.u32(if (z) 0xFFFFFFFFL else cdStart); b.u16(0)
        return b.array
    }

    // ---------------------------------------------------------------- helpers

    private class Bytes(size: Int) {
        val array = ByteArray(size)
        private var p = 0
        fun u16(v: Int) { array[p++] = v.toByte(); array[p++] = (v shr 8).toByte() }
        fun u32(v: Long) { for (k in 0 until 4) array[p++] = (v shr (8 * k)).toByte() }
        fun u64(v: Long) { for (k in 0 until 8) array[p++] = (v shr (8 * k)).toByte() }
        fun bytes(b: ByteArray) { System.arraycopy(b, 0, array, p, b.size); p += b.size }
    }

    /** Counts what passes through, so offsets in the central directory are exact. */
    private class CountingSink(private val out: OutputStream) {
        var count = 0L
            private set
        fun write(b: ByteArray) = write(b, 0, b.size)
        fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        fun flush() = out.flush()
    }

    /** Reads up to [max] bytes (and no more than [remaining]) into [buf] at [off], looping until it is full or the input ends. */
    private fun readBlock(src: InputStream, buf: ByteArray, off: Int, max: Int, remaining: Long): Int {
        val want = minOf(max.toLong(), remaining).toInt()
        var got = 0
        while (got < want) {
            val n = src.read(buf, off + got, want - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    companion object {
        private const val BLOCK = 65535
        /** Bit 3: sizes and CRC follow the data. Bit 11: names are UTF-8. */
        private const val FLAGS = 0x0808
        private val FINAL_BLOCK = byteArrayOf(1, 0, 0, -1, -1)

        /** MS-DOS date and time (local time, 2-second steps, from 1980). */
        private fun dos(ms: Long): Pair<Int, Int> {
            val c = Calendar.getInstance().apply { timeInMillis = if (ms > 0) ms else System.currentTimeMillis() }
            val y = c.get(Calendar.YEAR)
            if (y < 1980) return (1 shl 5 or 1) to 0
            val date = ((y - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
            val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
            return date to time
        }

        /**
         * Names that are safe and unique inside an archive: no folders or control characters, 200 bytes at most, and
         * "name (2).ext" for a repeat (compared without regard to case, since Windows and macOS do).
         */
        fun uniqueNames(raw: List<String>): List<String> {
            val seen = HashSet<String>()
            return raw.map { r ->
                var n = r.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim().trim('.')
                if (n.isEmpty()) n = "file"
                n = cut(n, 200)
                var candidate = n
                var k = 2
                while (!seen.add(candidate.lowercase())) {
                    val dot = n.lastIndexOf('.')
                    candidate = if (dot > 0) "${n.substring(0, dot)} ($k)${n.substring(dot)}" else "$n ($k)"
                    k++
                }
                candidate
            }
        }

        private fun cut(s: String, maxBytes: Int): String {
            if (s.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return s
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 12) s.substring(dot) else ""
            var base = s.substring(0, if (ext.isEmpty()) s.length else dot)
            while ((base + ext).toByteArray(StandardCharsets.UTF_8).size > maxBytes && base.isNotEmpty()) base = base.dropLast(1)
            return base + ext
        }
    }
}
