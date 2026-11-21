import Foundation
import Darwin

/// A ZIP archive written as it is sent: files are read once, in 64 KB pieces, and never held in memory or copied to
/// a temporary file, so a few files or a thousand of them cost the same little memory. The same format as the Android
/// app's `AirWebZip` (see there for the reasoning).
///
/// Nothing is compressed. Each file goes in as "deflate" blocks of kind *stored*, which every unzip tool reads as an
/// ordinary compressed entry (so the CRC and sizes can follow the data in a data descriptor). That keeps the CPU out of
/// it and makes the size of the whole archive exact before the first byte is sent, so the browser shows a real progress
/// bar. Only files of unknown length make it unknown.
///
/// ZIP64 is used only where needed: a file of 4 GB or more, more than 65 535 files, or an archive past 4 GB. A file of
/// unknown length is written in the plain form (and fails if it turns out to be 4 GB or more), because readers disagree
/// about how wide the descriptor after a small ZIP64 entry is.
nonisolated final class AirWebZip: @unchecked Sendable {
    /// One file: `size` is -1 when unknown, `timeMs` is epoch milliseconds (0 = now).
    struct Entry: Sendable {
        let name: String
        let size: Int64
        let timeMs: Int64
        let path: String
    }

    struct ZipError: Error { let message: String }

    private struct Done { let crc: UInt32; let csize: Int64; let usize: Int64 }

    private static let block = 65_535
    /// Bit 3: sizes and CRC follow the data. Bit 11: names are UTF-8.
    private static let flags: UInt16 = 0x0808
    private static let finalBlock: [UInt8] = [1, 0, 0, 0xFF, 0xFF]

    private let entries: [Entry]
    private let names: [[UInt8]]
    private let zip64At: Int64
    private let zip64Entries: Int
    /// The exact length of the archive, or -1 when a file's length isn't known.
    let length: Int64

    init(_ entries: [Entry], zip64At: Int64 = 0xFFFF_FFFF, zip64Entries: Int = 0xFFFF) {
        self.entries = entries
        self.zip64At = zip64At
        self.zip64Entries = zip64Entries
        let nm = Self.uniqueNames(entries.map(\.name)).map { Array($0.utf8) }
        names = nm
        var total: Int64 = 0, offset: Int64 = 0, cd: Int64 = 0
        var unknown = false
        for (i, e) in entries.enumerated() {
            if e.size < 0 { unknown = true; break }
            let z = Self.needs64(e.size, zip64At)
            let data = e.size + Self.storedOverhead(e.size)
            let header = Int64(30 + nm[i].count + (z ? 20 : 0))
            let dd: Int64 = z ? 24 : 16
            total += header + data + dd
            let offsetBig = offset >= zip64At
            let extra = (z || offsetBig) ? 4 + (z ? 16 : 0) + (offsetBig ? 8 : 0) : 0
            cd += Int64(46 + nm[i].count + extra)
            offset += header + data + dd
        }
        length = unknown ? -1 : total + cd + Self.endLength(offset, cd, entries.count, zip64At, zip64Entries)
    }

    private static func needs64(_ size: Int64, _ at: Int64) -> Bool { size + storedOverhead(size) >= at }
    private static func storedOverhead(_ size: Int64) -> Int64 { 5 * ((size + Int64(block) - 1) / Int64(block)) + 5 }

    /// Writes the whole archive through `send`; `entryDone` is told after each file was written in full.
    func write(send: (UnsafeRawPointer, Int) throws -> Void, entryDone: (Int) -> Void = { _ in }) throws {
        var count: Int64 = 0
        func put(_ bytes: [UInt8]) throws {
            try bytes.withUnsafeBytes { try send($0.baseAddress!, $0.count) }
            count += Int64(bytes.count)
        }
        var done: [Done] = []
        var offsets: [Int64] = []
        var buf = [UInt8](repeating: 0, count: Self.block + 5)
        for (i, e) in entries.enumerated() {
            offsets.append(count)
            let z = e.size >= 0 && Self.needs64(e.size, zip64At)
            try put(localHeader(i, e, z))
            let fd = Darwin.open(e.path, O_RDONLY | O_CLOEXEC)
            if fd < 0 { throw ZipError(message: "can't read \(e.name)") }
            defer { Darwin.close(fd) }
            var crc: UInt32 = 0
            var usize: Int64 = 0, csize: Int64 = 0
            while true {
                let remaining = e.size >= 0 ? e.size - usize : Int64.max
                let n = try buf.withUnsafeMutableBytes { raw -> Int in
                    try Self.readBlock(fd, raw.baseAddress! + 5, min(Int64(Self.block), remaining))
                }
                if n == 0 { break }
                buf[0] = 0 // BFINAL = 0, BTYPE = stored
                buf[1] = UInt8(n & 0xFF); buf[2] = UInt8(n >> 8)
                buf[3] = UInt8(~n & 0xFF); buf[4] = UInt8((~n >> 8) & 0xFF)
                crc = buf.withUnsafeBytes { CRC32.update(crc, $0.baseAddress! + 5, n) }
                try buf.withUnsafeBytes { try send($0.baseAddress!, 5 + n) }
                count += Int64(5 + n)
                usize += Int64(n)
                csize += Int64(5 + n)
            }
            if e.size >= 0 && usize != e.size { throw ZipError(message: "\(e.name) is shorter than announced") }
            if e.size < 0 && csize + 5 >= zip64At { throw ZipError(message: "\(e.name) is too big to put in a zip of unknown size") }
            try put(Self.finalBlock)
            csize += 5
            let d = Done(crc: crc, csize: csize, usize: usize)
            done.append(d)
            try put(descriptor(d, z))
            entryDone(i)
        }
        let cdStart = count
        for (i, e) in entries.enumerated() {
            try put(centralHeader(i, e, done[i], offsets[i], e.size >= 0 && Self.needs64(e.size, zip64At)))
        }
        let cdSize = count - cdStart
        try put(end(cdStart, cdSize, entries.count))
        if length >= 0 && count != length { throw ZipError(message: "archive length \(count) differs from the announced \(length)") }
    }

    // MARK: Pieces

    private func localHeader(_ i: Int, _ e: Entry, _ zip64: Bool) -> [UInt8] {
        var b = ByteWriter()
        let (date, time) = Self.dos(e.timeMs)
        b.u32(0x04034b50); b.u16(zip64 ? 45 : 20); b.u16(Self.flags); b.u16(8); b.u16(time); b.u16(date)
        b.u32(0) // crc, in the descriptor
        if zip64 { b.u32(0xFFFF_FFFF); b.u32(0xFFFF_FFFF) } else { b.u32(0); b.u32(0) }
        b.u16(UInt16(names[i].count)); b.u16(zip64 ? 20 : 0)
        b.bytes(names[i])
        if zip64 { b.u16(1); b.u16(16); b.u64(0); b.u64(0) }
        return b.data
    }

    private func descriptor(_ d: Done, _ zip64: Bool) -> [UInt8] {
        var b = ByteWriter()
        b.u32(0x08074b50); b.u32(d.crc)
        if zip64 { b.u64(UInt64(d.csize)); b.u64(UInt64(d.usize)) } else { b.u32(UInt32(d.csize)); b.u32(UInt32(d.usize)) }
        return b.data
    }

    private func centralHeader(_ i: Int, _ e: Entry, _ d: Done, _ offset: Int64, _ zip64: Bool) -> [UInt8] {
        let offsetBig = offset >= zip64At
        let extra = (zip64 || offsetBig) ? 4 + (zip64 ? 16 : 0) + (offsetBig ? 8 : 0) : 0
        var b = ByteWriter()
        let (date, time) = Self.dos(e.timeMs)
        b.u32(0x02014b50); b.u16(45); b.u16((zip64 || offsetBig) ? 45 : 20); b.u16(Self.flags); b.u16(8); b.u16(time); b.u16(date)
        b.u32(d.crc)
        b.u32(zip64 ? 0xFFFF_FFFF : UInt32(d.csize)); b.u32(zip64 ? 0xFFFF_FFFF : UInt32(d.usize))
        b.u16(UInt16(names[i].count)); b.u16(UInt16(extra)); b.u16(0); b.u16(0); b.u16(0); b.u32(0)
        b.u32(offsetBig ? 0xFFFF_FFFF : UInt32(offset))
        b.bytes(names[i])
        if extra > 0 {
            b.u16(1); b.u16(UInt16(extra - 4))
            if zip64 { b.u64(UInt64(d.usize)); b.u64(UInt64(d.csize)) }
            if offsetBig { b.u64(UInt64(offset)) }
        }
        return b.data
    }

    private static func needsEnd64(_ cdStart: Int64, _ cdSize: Int64, _ count: Int, _ at: Int64, _ entries: Int) -> Bool {
        count >= entries || cdStart >= at || cdSize >= at
    }
    private static func endLength(_ cdStart: Int64, _ cdSize: Int64, _ count: Int, _ at: Int64, _ entries: Int) -> Int64 {
        22 + (needsEnd64(cdStart, cdSize, count, at, entries) ? 56 + 20 : 0)
    }

    private func end(_ cdStart: Int64, _ cdSize: Int64, _ count: Int) -> [UInt8] {
        let z = Self.needsEnd64(cdStart, cdSize, count, zip64At, zip64Entries)
        var b = ByteWriter()
        if z {
            // ZIP64 end of central directory, then its locator.
            b.u32(0x06064b50); b.u64(44); b.u16(45); b.u16(45); b.u32(0); b.u32(0)
            b.u64(UInt64(count)); b.u64(UInt64(count)); b.u64(UInt64(cdSize)); b.u64(UInt64(cdStart))
            b.u32(0x07064b50); b.u32(0); b.u64(UInt64(cdStart + cdSize)); b.u32(1)
        }
        b.u32(0x06054b50); b.u16(0); b.u16(0)
        b.u16(z ? 0xFFFF : UInt16(count)); b.u16(z ? 0xFFFF : UInt16(count))
        b.u32(z ? 0xFFFF_FFFF : UInt32(cdSize)); b.u32(z ? 0xFFFF_FFFF : UInt32(cdStart)); b.u16(0)
        return b.data
    }

    // MARK: Helpers

    private struct ByteWriter {
        var data: [UInt8] = []
        mutating func u16(_ v: UInt16) { data.append(UInt8(v & 0xFF)); data.append(UInt8(v >> 8)) }
        mutating func u32(_ v: UInt32) { for k in 0..<4 { data.append(UInt8((v >> UInt32(8 * k)) & 0xFF)) } }
        mutating func u64(_ v: UInt64) { for k in 0..<8 { data.append(UInt8((v >> UInt64(8 * k)) & 0xFF)) } }
        mutating func bytes(_ b: [UInt8]) { data.append(contentsOf: b) }
    }

    /// Reads up to `max` bytes into `p`, looping until it is full or the file ends.
    private static func readBlock(_ fd: Int32, _ p: UnsafeMutableRawPointer, _ max: Int64) throws -> Int {
        let want = Int(max)
        var got = 0
        while got < want {
            let n = Darwin.read(fd, p + got, want - got)
            if n < 0 { if errno == EINTR { continue } else { throw ZipError(message: "read failed") } }
            if n == 0 { break }
            got += n
        }
        return got
    }

    /// MS-DOS date and time (local time, 2-second steps, from 1980).
    private static func dos(_ ms: Int64) -> (date: UInt16, time: UInt16) {
        let d = ms > 0 ? Date(timeIntervalSince1970: Double(ms) / 1000) : Date()
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: d)
        let y = c.year ?? 1980
        if y < 1980 { return (33, 0) }
        let year: Int = (y - 1980) << 9
        let month: Int = (c.month ?? 1) << 5
        let day: Int = c.day ?? 1
        let hour: Int = (c.hour ?? 0) << 11
        let minute: Int = (c.minute ?? 0) << 5
        let second: Int = (c.second ?? 0) / 2
        return (UInt16(year | month | day), UInt16(hour | minute | second))
    }

    /// Names that are safe and unique inside an archive: no folders or control characters, 200 bytes at most, and
    /// "name (2).ext" for a repeat (compared without regard to case, since Windows and macOS do).
    static func uniqueNames(_ raw: [String]) -> [String] {
        var seen = Set<String>()
        let dots = CharacterSet(charactersIn: ".")
        return raw.map { r in
            var n = r.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? r
            n = n.split(separator: "\\", omittingEmptySubsequences: false).last.map(String.init) ?? n
            n = String(String.UnicodeScalarView(n.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7F }))
            n = n.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: dots)
            if n.isEmpty { n = "file" }
            n = cut(n, 200)
            var candidate = n
            var k = 2
            while !seen.insert(candidate.lowercased()).inserted {
                if let dot = n.lastIndex(of: "."), dot != n.startIndex {
                    candidate = "\(n[..<dot]) (\(k))\(n[dot...])"
                } else {
                    candidate = "\(n) (\(k))"
                }
                k += 1
            }
            return candidate
        }
    }

    private static func cut(_ s: String, _ maxBytes: Int) -> String {
        if s.utf8.count <= maxBytes { return s }
        var ext = ""
        var base = s
        if let dot = s.lastIndex(of: "."), dot != s.startIndex, s.distance(from: dot, to: s.endIndex) <= 12 {
            ext = String(s[dot...]); base = String(s[..<dot])
        }
        while (base + ext).utf8.count > maxBytes && !base.isEmpty { base.removeLast() }
        return base + ext
    }
}

/// CRC-32 (the zip one), eight bytes at a time.
nonisolated enum CRC32 {
    private static let tables: [[UInt32]] = {
        var t = [[UInt32]](repeating: [UInt32](repeating: 0, count: 256), count: 8)
        for i in 0..<256 {
            var c = UInt32(i)
            for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
            t[0][i] = c
        }
        for i in 0..<256 {
            var c = t[0][i]
            for k in 1..<8 { c = t[0][Int(c & 0xFF)] ^ (c >> 8); t[k][i] = c }
        }
        return t
    }()

    static func update(_ crc: UInt32, _ p: UnsafeRawPointer, _ n: Int) -> UInt32 {
        var c = ~crc
        var i = 0
        let b = p.assumingMemoryBound(to: UInt8.self)
        let t0 = tables[0], t1 = tables[1], t2 = tables[2], t3 = tables[3]
        let t4 = tables[4], t5 = tables[5], t6 = tables[6], t7 = tables[7]
        while n - i >= 8 {
            let b0 = UInt32(b[i]), b1 = UInt32(b[i + 1]), b2 = UInt32(b[i + 2]), b3 = UInt32(b[i + 3])
            let b4 = UInt32(b[i + 4]), b5 = UInt32(b[i + 5]), b6 = UInt32(b[i + 6]), b7 = UInt32(b[i + 7])
            let lo: UInt32 = c ^ (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
            let x0: UInt32 = t7[Int(lo & 0xFF)]
            let x1: UInt32 = t6[Int((lo >> 8) & 0xFF)]
            let x2: UInt32 = t5[Int((lo >> 16) & 0xFF)]
            let x3: UInt32 = t4[Int(lo >> 24)]
            let x4: UInt32 = t3[Int(b4)]
            let x5: UInt32 = t2[Int(b5)]
            let x6: UInt32 = t1[Int(b6)]
            let x7: UInt32 = t0[Int(b7)]
            c = (x0 ^ x1) ^ (x2 ^ x3) ^ ((x4 ^ x5) ^ (x6 ^ x7))
            i += 8
        }
        while i < n { c = t0[Int((c ^ UInt32(b[i])) & 0xFF)] ^ (c >> 8); i += 1 }
        return ~c
    }
}
