import Foundation
import SQLite3

// This Mac's past screen time, read from the records macOS itself keeps (the ones Apple's Screen Time is built from), so the
// AirBridge widget has today from midnight and the days before AirBridge was installed, not only the time since it started.
//
// - Biome streams (macOS 14 and newer), in ~/Library/Biome/streams/restricted: App.InFocus (an app came to the front or left),
//   Device.ScreenLocked and the display backlight (to stop counting while locked or dark). The files are SEGB containers of
//   small protobuf records; the layouts follow the open-source forensic readers ccl-segb (CCL Forensics) and iLEAPP.
// - knowledgeC.db `/app/usage` (older macOS), as a fallback.
// - The power log (`pmset -g log`, readable without any permission, about the last week): display off and on, sleep and wake.
//   App.InFocus only shows the lock screen when the Mac is woken, so without this an app left in front would count through
//   a whole night's sleep (up to the 4-hour limit).
//
// Both are protected by macOS: AirBridge can only read them once you turn on Full Disk Access for it. Nothing read here
// leaves your own devices. The format readers and the interval rules are pure (no files, no clock), so tools/screentimecheck
// checks them with synthetic files.

/// SEGB containers (v1 and v2): the payloads of the records that are written (not deleted).
nonisolated enum SEGB {
    struct Entry: Equatable { let time: Double; let payload: Data }

    static func entries(_ data: Data) -> [Entry] {
        if data.count >= 32, data.prefix(4) == Data("SEGB".utf8) { return v2(data) }
        if data.count >= 56, data.subdata(in: 52..<56) == Data("SEGB".utf8) { return v1(data) }
        return []
    }

    /// v1: 56-byte header (end of data at 0, magic at 52), then records: length, state, two Cocoa times, CRC, unknown
    /// (32 bytes), the payload, padding to 8.
    private static func v1(_ d: Data) -> [Entry] {
        let end = min(Int(u32(d, 0)), d.count)
        var at = 56, out: [Entry] = []
        while at + 32 <= end {
            let len = Int(i32(d, at)), state = i32(d, at + 4), t = f64(d, at + 8)
            let start = at + 32
            guard len >= 0, start + len <= d.count else { break }
            if state == 1 { out.append(Entry(time: t, payload: d.subdata(in: start..<(start + len)))) }
            at = start + len
            if at % 8 != 0 { at += 8 - at % 8 }
        }
        return out
    }

    /// v2: 32-byte header (magic, entry count, Cocoa time, padding); a trailer of 16-byte items at the end of the file (end
    /// offset of the entry relative to the end of the header, state, Cocoa time); each entry is an 8-byte header (CRC,
    /// unknown) then the payload, padded to 4.
    private static func v2(_ d: Data) -> [Entry] {
        let count = Int(i32(d, 4))
        guard count > 0, count * 16 <= d.count - 32 else { return [] }
        var items: [(end: Int, state: Int32, time: Double)] = []
        let trailer = d.count - count * 16
        for i in 0..<count {
            let o = trailer + i * 16
            items.append((Int(i32(d, o)), i32(d, o + 4), f64(d, o + 8)))
        }
        items.sort { $0.end < $1.end }
        var at = 32, lastEnd = -1, out: [Entry] = []
        for it in items {
            if it.state == 4 { continue }
            if it.end == lastEnd { continue }
            let entryEnd = it.end + 32
            guard entryEnd <= trailer, entryEnd - at >= 8 else { continue }
            if it.state == 1 { out.append(Entry(time: it.time, payload: d.subdata(in: (at + 8)..<entryEnd))) }
            lastEnd = it.end
            at = entryEnd
            if it.end % 4 != 0 { at += 4 - it.end % 4 }
        }
        return out
    }

    private static func u32(_ d: Data, _ o: Int) -> UInt32 {
        guard o + 4 <= d.count else { return 0 }
        return d.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: UInt32.self)) }
    }
    private static func i32(_ d: Data, _ o: Int) -> Int32 { Int32(bitPattern: u32(d, o)) }
    private static func f64(_ d: Data, _ o: Int) -> Double {
        guard o + 8 <= d.count else { return 0 }
        return d.withUnsafeBytes { Double(bitPattern: UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: UInt64.self))) }
    }
}

/// Just enough protobuf: the first value of each top-level field.
nonisolated enum MiniProto {
    enum Value: Equatable { case varint(UInt64), fixed64(UInt64), bytes(Data), fixed32(UInt32) }

    static func fields(_ d: Data) -> [Int: Value] {
        var out: [Int: Value] = [:]
        let b = [UInt8](d)
        var i = 0
        func varint() -> UInt64? {
            var v: UInt64 = 0, shift: UInt64 = 0
            while i < b.count, shift < 64 {
                let x = b[i]; i += 1
                v |= UInt64(x & 0x7F) << shift
                if x & 0x80 == 0 { return v }
                shift += 7
            }
            return nil
        }
        while i < b.count {
            guard let key = varint() else { break }
            let field = Int(key >> 3)
            let value: Value
            switch key & 7 {
            case 0: guard let v = varint() else { return out }; value = .varint(v)
            case 1:
                guard i + 8 <= b.count else { return out }
                var v: UInt64 = 0
                for k in 0..<8 { v |= UInt64(b[i + k]) << (8 * UInt64(k)) }
                i += 8; value = .fixed64(v)
            case 2:
                guard let n = varint(), n <= UInt64(b.count - i) else { return out }
                value = .bytes(Data(b[i..<(i + Int(n))])); i += Int(n)
            case 5:
                guard i + 4 <= b.count else { return out }
                var v: UInt32 = 0
                for k in 0..<4 { v |= UInt32(b[i + k]) << (8 * UInt32(k)) }
                i += 4; value = .fixed32(v)
            default: return out
            }
            if out[field] == nil { out[field] = value }
        }
        return out
    }

    static func int(_ v: Value?) -> Int? { if case .varint(let x)? = v { return Int(truncatingIfNeeded: x) }; return nil }
    static func double(_ v: Value?) -> Double? { if case .fixed64(let x)? = v { return Double(bitPattern: x) }; return nil }
    static func string(_ v: Value?) -> String? { if case .bytes(let x)? = v { return String(data: x, encoding: .utf8) }; return nil }
}

/// The past, turned into time per app.
nonisolated enum MacUsageHistory {
    /// An app came to the front (`starting`) or left it.
    struct Focus: Equatable { let time: Date; let bundle: String; let starting: Bool }
    /// The Mac stopped being usable (`off`: locked, display dark) or became usable again.
    struct Break: Equatable { let time: Date; let off: Bool }
    struct Span: Equatable { let bundle: String; let start: Date; let end: Date }

    /// Seconds between 1970 and 2001, the start of Cocoa time.
    static let cocoaOffset: TimeInterval = 978_307_200
    /// The same limit as the live tracker: a missed sleep or lock never counts as more than this at once.
    static let maxSpan: TimeInterval = 4 * 3600
    /// Apps that mean nobody is using the Mac (lock screen, screen saver).
    static let notUse: Set<String> = ["com.apple.loginwindow", "com.apple.ScreenSaver.Engine", "com.apple.SecurityAgent"]

    // MARK: Record layouts

    /// App.InFocus: 3 = starting (1 front, 0 left), 4 = Cocoa time, 6 = bundle id, 13 = display (4 = iPhone Mirroring,
    /// which is the phone's app, not this Mac's).
    static func focus(_ e: SEGB.Entry) -> Focus? {
        let f = MiniProto.fields(e.payload)
        guard let bundle = MiniProto.string(f[6]), !bundle.isEmpty, let start = MiniProto.int(f[3]) else { return nil }
        if MiniProto.int(f[13]) == 4 { return nil }
        let t = MiniProto.double(f[4]).flatMap { $0 > 0 && $0.isFinite ? $0 : nil } ?? e.time
        return Focus(time: Date(timeIntervalSince1970: t + cocoaOffset), bundle: bundle, starting: start == 1)
    }

    /// Device.ScreenLocked: 1 = 1 locked, 0 unlocked; the time is the record's.
    static func lock(_ e: SEGB.Entry) -> Break? {
        guard let v = MiniProto.int(MiniProto.fields(e.payload)[1]), v == 0 || v == 1, e.time > 0 else { return nil }
        return Break(time: Date(timeIntervalSince1970: e.time + cocoaOffset), off: v == 1)
    }

    /// Backlight: 1 = Cocoa time, 2 = level (0 = dark).
    static func backlight(_ e: SEGB.Entry) -> Break? {
        let f = MiniProto.fields(e.payload)
        guard let level = MiniProto.int(f[2]) else { return nil }
        let t = MiniProto.double(f[1]).flatMap { $0 > 0 && $0.isFinite ? $0 : nil } ?? e.time
        return Break(time: Date(timeIntervalSince1970: t + cocoaOffset), off: level == 0)
    }

    /// Display off / sleep (off) and display on / wake (on) from `pmset -g log` output. Lines look like
    /// "2026-11-16 15:31:08 +0530 Notification        \tDisplay is turned off".
    static func powerBreaks(_ log: String) -> [Break] {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss Z"
        var out: [Break] = []
        for line in log.split(separator: "\n", omittingEmptySubsequences: true) {
            guard line.count > 27, line.first?.isNumber == true else { continue }
            let rest = line.dropFirst(25)
            let off: Bool
            if rest.contains("Display is turned off") { off = true }
            else if rest.contains("Display is turned on") { off = false }
            else if rest.hasPrefix(" Sleep ") { off = true }
            else if rest.hasPrefix(" Wake ") { off = false }
            else { continue }
            guard let t = f.date(from: String(line.prefix(25))) else { continue }
            out.append(Break(time: t, off: off))
        }
        return out
    }

    /// The power log, or "" if it can't be read. Takes a second or two: call off the main thread, not often.
    static func readPowerLog() -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        p.arguments = ["-g", "log"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return "" }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: Rules

    /// Time per app: an app counts from coming to the front until another app does, it leaves, the Mac locks, the display
    /// goes dark, or the lock screen or screen saver comes up. After an unlock the app that was in front counts again.
    static func spans(focus: [Focus], breaks: [Break], now: Date) -> [Span] {
        enum Ev { case f(Focus), b(Break) }
        let all: [(Date, Int, Ev)] = focus.map { ($0.time, 1, .f($0)) } + breaks.map { ($0.time, 0, .b($0)) }
        var out: [Span] = []
        var cur: (bundle: String, since: Date)?
        var front: String?   // the app in front, even while the Mac is locked
        var off = false
        func close(_ t: Date) {
            guard let c = cur else { return }
            let end = min(t, c.since.addingTimeInterval(maxSpan))
            if end > c.since { out.append(Span(bundle: c.bundle, start: c.since, end: end)) }
            cur = nil
        }
        for (t, _, ev) in all.sorted(by: { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }) {
            switch ev {
            case .f(let f):
                if notUse.contains(f.bundle) {
                    if f.starting { close(t); off = true } else { off = false; if cur == nil, let fr = front { cur = (fr, t) } }
                } else if f.starting {
                    if cur?.bundle == f.bundle { front = f.bundle; continue }
                    close(t); front = f.bundle; off = false; cur = (f.bundle, t)
                } else if cur?.bundle == f.bundle {
                    close(t); front = nil
                } else if front == f.bundle { front = nil }
            case .b(let b):
                if b.off { close(t); off = true }
                else if off { off = false; if cur == nil, let fr = front { cur = (fr, t) } }
            }
        }
        close(now) // while locked or dark nothing is open, so this only closes an app still in use
        return out
    }

    /// Unlock times: the lock stream's unlocks, or (without that stream) the lock screen going away.
    static func unlocks(focus: [Focus], locks: [Break]) -> [Date] {
        if !locks.isEmpty { return locks.filter { !$0.off }.map(\.time) }
        return focus.filter { $0.bundle == "com.apple.loginwindow" && !$0.starting }.map(\.time)
    }

    /// Adds the spans (each one an open) and unlocks to an accumulator, `name` giving each app's display name.
    static func accumulate(_ spans: [Span], unlocks: [Date] = [], since: Date, calendar: Calendar, name: (String) -> String) -> STAccumulator {
        var acc = STAccumulator()
        var names: [String: String] = [:]
        var lastEnd: [String: Date] = [:]
        for s in spans where s.end > since {
            let n = names[s.bundle] ?? { let v = name(s.bundle); names[s.bundle] = v; return v }()
            // An app that carries on after a cut at the 4-hour limit is the same stretch, not a new open.
            if s.start >= since, lastEnd[s.bundle] != s.start { acc.open(id: s.bundle, name: n, at: s.start, calendar: calendar) }
            acc.add(id: s.bundle, name: n, from: max(s.start, since), to: s.end, calendar: calendar)
            lastEnd[s.bundle] = s.end
        }
        for u in unlocks where u >= since { acc.unlock(at: u, calendar: calendar) }
        return acc
    }

    // MARK: Reading (needs Full Disk Access)

    struct Status: Codable, Equatable {
        var source = "none"      // "biome", "knowledgeC" or "none"
        var reason = ""
        var focusEvents = 0
        var breakEvents = 0
        var powerEvents = 0
        var first: Date?
        var last: Date?
        var days: [String: Int] = [:]
    }

    static var biomeRoot: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Biome/streams/restricted", isDirectory: true)
    }
    static var knowledgeDB: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Knowledge/knowledgeC.db")
    }

    /// Whether macOS lets AirBridge read the records (Full Disk Access).
    static func canRead() -> Bool {
        (try? FileManager.default.contentsOfDirectory(atPath: biomeRoot.path)) != nil
            || FileManager.default.isReadableFile(atPath: knowledgeDB.path) && openDB() != nil
    }

    /// Entries of every file of one stream's `local` folder (other devices' copies under `remote` are left out).
    static func streamEntries(_ name: String) -> [SEGB.Entry]? {
        let dir = biomeRoot.appendingPathComponent(name, isDirectory: true).appendingPathComponent("local", isDirectory: true)
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return nil }
        var out: [SEGB.Entry] = []
        for f in files.sorted() where !f.hasPrefix(".") {
            let url = dir.appendingPathComponent(f)
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue,
                  let d = try? Data(contentsOf: url, options: .mappedIfSafe) else { continue }
            out += SEGB.entries(d)
        }
        return out
    }

    /// The spans since `since`, from Biome, else knowledgeC. `status` says what was found.
    static func read(since: Date, now: Date, power: [Break] = []) -> (spans: [Span], unlocks: [Date], status: Status) {
        var st = Status()
        st.powerEvents = power.count
        let cutoff = since.addingTimeInterval(-maxSpan)
        if let raw = streamEntries("App.InFocus") {
            let focus = raw.compactMap(focus).filter { $0.time >= cutoff && $0.time <= now }
            let locks = (streamEntries("Device.ScreenLocked") ?? []).compactMap(lock).filter { $0.time >= cutoff && $0.time <= now }
            let lights = (streamEntries("Device.Display.Backlight") ?? []).compactMap(backlight).filter { $0.time >= cutoff && $0.time <= now }
            let breaks = locks + lights + power.filter { $0.time >= cutoff && $0.time <= now }
            st.focusEvents = focus.count; st.breakEvents = breaks.count
            st.first = focus.map(\.time).min(); st.last = focus.map(\.time).max()
            if !focus.isEmpty {
                st.source = "biome"
                return (spans(focus: focus, breaks: breaks, now: now), unlocks(focus: focus, locks: locks), st)
            }
            st.reason = "App.InFocus has no records since \(since)"
        } else {
            st.reason = "Biome not readable (Full Disk Access is off)"
        }
        if let kc = knowledgeSpans(since: cutoff, now: now), !kc.isEmpty {
            st.source = "knowledgeC"; st.reason = ""
            st.focusEvents = kc.count; st.first = kc.map(\.start).min(); st.last = kc.map(\.end).max()
            return (kc, [], st)
        }
        return ([], [], st)
    }

    private static func openDB() -> OpaquePointer? {
        var db: OpaquePointer?
        let uri = "file:" + knowledgeDB.path + "?mode=ro"
        guard sqlite3_open_v2(uri, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil) == SQLITE_OK, let db else {
            if let db { sqlite3_close(db) }
            return nil
        }
        // Opening is lazy: touch the schema so a refused read shows up here.
        if sqlite3_exec(db, "SELECT 1 FROM sqlite_master LIMIT 1", nil, nil, nil) != SQLITE_OK { sqlite3_close(db); return nil }
        return db
    }

    /// knowledgeC `/app/usage` sessions (start, end, bundle id).
    static func knowledgeSpans(since: Date, now: Date) -> [Span]? {
        guard let db = openDB() else { return nil }
        defer { sqlite3_close(db) }
        let sql = "SELECT ZVALUESTRING, ZSTARTDATE, ZENDDATE FROM ZOBJECT WHERE ZSTREAMNAME = '/app/usage' AND ZENDDATE > ? ORDER BY ZSTARTDATE"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_double(stmt, 1, since.timeIntervalSince1970 - cocoaOffset)
        var out: [Span] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            guard let c = sqlite3_column_text(stmt, 0) else { continue }
            let bundle = String(cString: c)
            let s = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1) + cocoaOffset)
            let e = min(now, Date(timeIntervalSince1970: sqlite3_column_double(stmt, 2) + cocoaOffset))
            guard !bundle.isEmpty, !notUse.contains(bundle), e > s else { continue }
            out.append(Span(bundle: bundle, start: s, end: min(e, s.addingTimeInterval(maxSpan))))
        }
        return out
    }
}
