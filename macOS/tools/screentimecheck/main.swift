import Foundation

var passed = 0, failed = 0
func check(_ name: String, _ ok: Bool) { if ok { passed += 1 } else { failed += 1; print("FAIL: \(name)") } }

var cal = Calendar(identifier: .gregorian)
cal.timeZone = TimeZone(identifier: "Asia/Kolkata")!
// "Now" is 22 Nov 2026, 20:30 local.
let now = cal.date(from: DateComponents(year: 2026, month: 11, day: 22, hour: 20, minute: 30))!
func key(_ back: Int) -> String { STSummary.dayKey(cal.date(byAdding: .day, value: -back, to: now)!, calendar: cal) }
func hours(_ pairs: [Int: Int]) -> [Int] { (0..<24).map { pairs[$0] ?? 0 } }

// ---- formatting
check("hours and minutes", STFormat.duration(9 * 3600 + 30 * 60) == "9h 30m")
check("whole hours drop the minutes", STFormat.duration(3 * 3600) == "3h")
check("minutes", STFormat.duration(59 * 60 + 10) == "59m")
check("seconds", STFormat.duration(45) == "45s")
check("zero is 0m", STFormat.duration(0) == "0m")
check("negative is 0m", STFormat.duration(-5) == "0m")
check("delta up", STFormat.delta(today: 5 * 3600, average: 4 * 3600) == "+1h")
check("delta down", STFormat.delta(today: 3600, average: 3600 + 25 * 60) == "−25m")
check("a tiny delta is not mentioned", STFormat.delta(today: 3600, average: 3630) == nil && STFormat.delta(today: 3600, average: 3600 + 240) == nil)
check("no average, no delta", STFormat.delta(today: 3600, average: nil) == nil)

// ---- file names
check("app ids become safe file names", ScreenTimeShared.fileSafe("com.whatsapp") == "com.whatsapp")
check("slashes and spaces are replaced", ScreenTimeShared.fileSafe("../../etc passwd") == ".._.._etc_passwd")
check("an empty id still gives a name", ScreenTimeShared.fileSafe("") == "_" && ScreenTimeShared.fileSafe("..") == "_")

// ---- upsert
var snap = STSnapshot()
let phoneOld = STDevice(id: "p", name: "Pixel", platform: "android", updated: now, days: [
    STDay(day: key(2), seconds: 100, hours: nil, apps: nil), STDay(day: key(1), seconds: 200, hours: nil, apps: nil)])
snap.upsert(phoneOld)
let phoneToday = STDevice(id: "p", name: "Pixel", platform: "android", updated: now, days: [
    STDay(day: key(1), seconds: 250, hours: nil, apps: nil), STDay(day: key(0), seconds: 300, hours: nil, apps: nil)])
snap.upsert(phoneToday)
let p = snap.devices.first { $0.id == "p" }!
check("an upsert keeps older days it did not resend", p.days.map(\.day) == [key(2), key(1), key(0)])
check("a resent day replaces the stored one", p.days.first { $0.day == key(1) }?.seconds == 250)
check("one device, one entry", snap.devices.count == 1)
var big = STSnapshot()
big.upsert(STDevice(id: "x", name: "X", platform: "windows", updated: now, days: (0..<40).map { STDay(day: key($0), seconds: $0, hours: nil, apps: nil) }), keepDays: 30)
check("history is capped at keepDays, newest kept", big.devices[0].days.count == 30 && big.devices[0].days.last?.day == key(0))
var two = STSnapshot()
two.upsert(STDevice(id: "b", name: "zeta", platform: "windows", updated: now, days: []))
two.upsert(STDevice(id: "a", name: "Alpha", platform: "macos", updated: now, days: []))
check("devices are sorted by name", two.devices.map(\.name) == ["Alpha", "zeta"])
two.remove(deviceId: "a")
check("a device can be removed", two.devices.map(\.id) == ["b"])

// ---- store round trip
let dir = FileManager.default.temporaryDirectory.appendingPathComponent("stcheck-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: dir) }
check("save works", STStore.save(snap, to: dir))
let back = STStore.load(from: dir)
check("load gives the same devices and days", back.devices.count == 1 && back.devices[0].days.count == 3 && back.devices[0].days.last?.seconds == 300)
check("a missing file loads as empty", STStore.load(from: dir.appendingPathComponent("nope")).devices.isEmpty)
check("no folder: nothing saved", !STStore.save(snap, to: nil))
check("icon save and lookup", STStore.saveIcon(Data([0x89, 0x50, 0x4E, 0x47]), appId: "com.whatsapp", in: dir) && STStore.hasIcon(appId: "com.whatsapp", in: dir))
check("an oversized icon is refused", !STStore.saveIcon(Data(count: 300 * 1024), appId: "big", in: dir))
check("an unknown icon is not there", !STStore.hasIcon(appId: "com.nothing", in: dir))

// ---- summary across devices
var all = STSnapshot()
all.upsert(STDevice(id: "phone", name: "Pixel", platform: "android", updated: now, days: [
    STDay(day: key(3), seconds: 2 * 3600, hours: nil, apps: nil),
    STDay(day: key(1), seconds: 4 * 3600, hours: nil, apps: nil),
    STDay(day: key(0), seconds: 3 * 3600, hours: hours([9: 1800, 13: 3600, 20: 5400]), apps: [
        STApp(id: "com.whatsapp", name: "WhatsApp", seconds: 5400), STApp(id: "com.google.android.youtube", name: "YouTube", seconds: 3600),
        STApp(id: "com.android.chrome", name: "Chrome", seconds: 1800)])]))
all.upsert(STDevice(id: "mac", name: "MacBook", platform: "macos", updated: now, days: [
    STDay(day: key(1), seconds: 6 * 3600, hours: nil, apps: nil),
    STDay(day: key(0), seconds: 5 * 3600, hours: hours([10: 3600 * 2, 14: 3600 * 3]), apps: [
        STApp(id: "com.google.android.studio", name: "Android Studio", seconds: 3 * 3600), STApp(id: "com.android.chrome", name: "Chrome", seconds: 7200)])]))
let s = STSummary.make(all, deviceId: nil, now: now, calendar: cal)
check("all devices: title", s.title == "All devices")
check("all devices: today is the sum", s.today == 8 * 3600)
check("average over previous days with data: (10h + 2h) / 2", s.average == 6 * 3600)
check("trend starts at the first day with data and ends today", s.trend.count == 4 && s.trend.last!.seconds == 8 * 3600 && s.trend.first!.seconds == 2 * 3600)
check("a day with no data is zero, not skipped", s.trend[1].seconds == 0)
check("trend dates are consecutive days", zip(s.trend, s.trend.dropFirst()).allSatisfy { cal.dateComponents([.day], from: $0.date, to: $1.date).day == 1 })
check("today's running total ends at the hourly sum", s.todayCumulative.last?.seconds == 8 * 3600 && s.todayCumulative.count == 21)
check("the running total never goes down", zip(s.todayCumulative, s.todayCumulative.dropFirst()).allSatisfy { $0.seconds <= $1.seconds })
check("no point is in the future", s.todayCumulative.allSatisfy { $0.date <= now })
check("the same app on two devices is merged", s.topApps.first { $0.id == "com.android.chrome" }?.seconds == 1800 + 7200)
check("top apps are sorted, most used first", s.topApps.map(\.seconds) == s.topApps.map(\.seconds).sorted(by: >) && s.topApps.first?.name == "Android Studio")
check("the badge shows where an app was used most", s.topApps.first { $0.id == "com.android.chrome" }?.platform == "macos" && s.topApps.first { $0.id == "com.whatsapp" }?.platform == "android")
check("two devices contributed today", s.deviceCount == 2)
let ph = STSummary.make(all, deviceId: "phone", now: now, calendar: cal)
check("one device: its name and its own total", ph.title == "Pixel" && ph.today == 3 * 3600 && ph.average == 3 * 3600)
check("one device: only its apps", Set(ph.topApps.map(\.id)) == ["com.whatsapp", "com.google.android.youtube", "com.android.chrome"])
let none = STSummary.make(STSnapshot(), deviceId: nil, now: now, calendar: cal)
check("nothing yet: zero, no average, one trend point, no apps", none.today == 0 && none.average == nil && none.trend.count == 1 && none.topApps.isEmpty && none.todayCumulative.isEmpty)
var many = STSnapshot()
many.upsert(STDevice(id: "w", name: "PC", platform: "windows", updated: now, days: (0..<25).map { STDay(day: key($0), seconds: 3600, hours: nil, apps: nil) }))
check("the trend shows at most 14 days", STSummary.make(many, deviceId: nil, now: now, calendar: cal).trend.count == 14)
var apps = STSnapshot()
apps.upsert(STDevice(id: "w", name: "PC", platform: "windows", updated: now, days: [STDay(day: key(0), seconds: 100, hours: nil, apps: (0..<10).map { STApp(id: "a\($0)", name: "A\($0)", seconds: 10 + $0) })]))
check("at most 6 top apps", STSummary.make(apps, deviceId: nil, now: now, calendar: cal).topApps.count == 6)
check("an unknown device id gives an empty summary", STSummary.make(all, deviceId: "nope", now: now, calendar: cal).today == 0)


// ---- accumulator: time per app, hour and day
func at(_ d: Int, _ h: Int, _ m: Int = 0, _ sec: Int = 0) -> Date { cal.date(from: DateComponents(year: 2026, month: 11, day: d, hour: h, minute: m, second: sec))! }
var acc = STAccumulator()
acc.add(id: "com.apple.Safari", name: "Safari", from: at(22, 9, 50), to: at(22, 10, 20), calendar: cal)
let k22 = STSummary.dayKey(at(22, 12), calendar: cal)
check("30 minutes are counted", Int(acc.days[k22]!.seconds) == 1800)
check("split at the hour: 10 min in 9:00, 20 min in 10:00", Int(acc.days[k22]!.hours[9]) == 600 && Int(acc.days[k22]!.hours[10]) == 1200)
acc.add(id: "com.apple.Safari", name: "Safari", from: at(22, 23, 50), to: at(23, 0, 15), calendar: cal)
let k23 = STSummary.dayKey(at(23, 12), calendar: cal)
check("a stretch over midnight goes to both days", Int(acc.days[k22]!.hours[23]) == 600 && Int(acc.days[k23]!.hours[0]) == 900)
acc.add(id: "com.apple.dt.Xcode", name: "Xcode", from: at(22, 14), to: at(22, 15), calendar: cal)
check("apps are kept apart", Int(acc.days[k22]!.apps["com.apple.dt.Xcode"]!.seconds) == 3600 && Int(acc.days[k22]!.apps["com.apple.Safari"]!.seconds) == 2400)
var gap = STAccumulator()
gap.add(id: "x", name: "X", from: at(22, 0), to: at(22, 12), calendar: cal)
check("one stretch counts at most 4 hours (a missed sleep is never counted whole)", Int(gap.days[k22]!.seconds) == 4 * 3600)
var none2 = STAccumulator()
none2.add(id: "x", name: "X", from: at(22, 10), to: at(22, 9), calendar: cal)
none2.add(id: "", name: "", from: at(22, 9), to: at(22, 10), calendar: cal)
check("backwards time and an empty app id count nothing", none2.days.isEmpty)
var old = STAccumulator()
for d in 1...20 { old.add(id: "x", name: "X", from: at(d, 10), to: at(d, 11), calendar: cal) }
old.prune(keep: 7)
check("prune keeps the newest days", old.days.count == 7 && old.days.keys.sorted().last == STSummary.dayKey(at(20, 12), calendar: cal))
let dev = acc.device(id: "mac1", name: "MacBook", platform: "macos", now: at(22, 20), calendar: cal)
let todayDay = dev.days.first { $0.day == k22 }!
check("device: today has 24 hours and its apps, most used first", todayDay.hours?.count == 24 && todayDay.apps?.first?.id == "com.apple.dt.Xcode")
check("device: other days have no hours", dev.days.first { $0.day == k23 }?.hours == nil)
var lots = STAccumulator()
for i in 0..<30 { lots.add(id: "app\(i)", name: "A\(i)", from: at(22, 10, i), to: at(22, 10, i, 30 + i), calendar: cal) }
check("device: at most 20 apps", lots.device(id: "m", name: "M", platform: "macos", now: at(22, 11), calendar: cal).days[0].apps?.count == 20)
let enc = try! JSONEncoder().encode(acc); let dec = try! JSONDecoder().decode(STAccumulator.self, from: enc)
check("the accumulator survives a save and reload", dec == acc)

// ---- the §15 messages
let msg = STWire.message(for: dev)
check("message type", msg["t"] as? String == "screen_time")
let json = try! JSONSerialization.data(withJSONObject: msg)
let parsed = try! JSONSerialization.jsonObject(with: json) as! [String: Any]
let got = STWire.device(from: parsed, id: "mac1", name: "MacBook", platform: "macos", now: at(22, 20))
check("round trip through JSON keeps every day and total", got?.days.map(\.day) == dev.days.map(\.day) && got?.days.map(\.seconds) == dev.days.map(\.seconds))
check("round trip keeps today's hours and apps", got?.days.first { $0.day == k22 }?.hours == todayDay.hours && got?.days.first { $0.day == k22 }?.apps == todayDay.apps)
func parse(_ o: [String: Any]) -> STDevice? { STWire.device(from: o, id: "p", name: "P", platform: "android", now: now) }
check("no days: refused", parse(["t": "screen_time", "days": []]) == nil)
check("a bad date: refused", parse(["days": [["day": "22-11-2026", "s": 10]]]) == nil && parse(["days": [["day": "2026-13-01", "s": 10]]]) == nil)
check("23 hours instead of 24: refused", parse(["days": [["day": "2026-11-22", "s": 10, "hours": Array(repeating: 1, count: 23)]]]) == nil)
check("too many days: refused", parse(["days": (0..<40).map { _ in ["day": "2026-11-22", "s": 1] }]) == nil)
let clamped = parse(["days": [["day": "2026-11-22", "s": 999_999, "hours": [5000] + Array(repeating: -3, count: 23), "apps": [["id": "a", "s": 200_000]]]]])
check("values are clamped to what a day can hold", clamped?.days[0].seconds == 86_400 && clamped?.days[0].hours?[0] == 3600 && clamped?.days[0].hours?[1] == 0 && clamped?.days[0].apps?[0].seconds == 86_400)
check("an app without a name uses its id", clamped?.days[0].apps?[0].name == "a")
let longApps = parse(["days": [["day": "2026-11-22", "s": 1, "apps": (0..<80).map { ["id": "a\($0)", "s": 1] }]]])
check("at most 50 apps per day are kept", longApps?.days[0].apps?.count == 50)
check("numbers sent as decimals are accepted", parse(["days": [["day": "2026-11-22", "s": 12.7]]])?.days[0].seconds == 12)
let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3])
check("icon message round trip", STWire.icon(from: STWire.iconMessage(id: "com.whatsapp", png: png)!)?.png == png)
check("an icon that is not a PNG is refused", STWire.icon(from: ["id": "x", "png": Data("GIF89a".utf8).base64EncodedString()]) == nil)
check("an oversized icon is refused", STWire.icon(from: ["id": "x", "png": (png + Data(count: 100 * 1024)).base64EncodedString()]) == nil && STWire.iconMessage(id: "x", png: Data(count: 100 * 1024)) == nil)
check("an icon with no id is refused", STWire.icon(from: ["png": png.base64EncodedString()]) == nil)


// ---- macOS's own records (Biome SEGB files, protobuf records)

/// Little-endian bytes.
func le<T: FixedWidthInteger>(_ v: T) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
func le(_ v: Double) -> Data { le(v.bitPattern) }
func pvarint(_ v: UInt64) -> Data { var v = v, out = Data(); repeat { var b = UInt8(v & 0x7F); v >>= 7; if v != 0 { b |= 0x80 }; out.append(b) } while v != 0; return out }
func pfield(_ n: Int, varint v: UInt64) -> Data { pvarint(UInt64(n << 3)) + pvarint(v) }
func pfield(_ n: Int, double v: Double) -> Data { pvarint(UInt64(n << 3 | 1)) + le(v) }
func pfield(_ n: Int, string v: String) -> Data { let d = Data(v.utf8); return pvarint(UInt64(n << 3 | 2)) + pvarint(UInt64(d.count)) + d }
func cocoa(_ d: Date) -> Double { d.timeIntervalSince1970 - MacUsageHistory.cocoaOffset }
func tm(_ h: Int, _ m: Int, _ s: Int = 0, day: Int = 22) -> Date { cal.date(from: DateComponents(year: 2026, month: 11, day: day, hour: h, minute: m, second: s))! }
func focusRecord(_ bundle: String, _ starting: Bool, _ t: Date, display: UInt64? = nil) -> Data {
    var d = Data([0x0A, 0x01, 0x41]) // field 1 (bytes) first, as real records have other fields too
    d += pfield(3, varint: starting ? 1 : 0) + pfield(4, double: cocoa(t)) + pfield(6, string: bundle)
    if let display { d += pfield(13, varint: display) }
    return d
}

/// A SEGB v2 file: header, entries (8-byte header + payload, padded to 4), trailer of (end offset, state, time).
func segb2(_ items: [(payload: Data, state: Int32, time: Double)]) -> Data {
    var body = Data(), trailer = Data()
    for it in items {
        body += le(UInt32(0)) + le(Int32(0)) + it.payload
        let end = Int32(body.count)
        trailer += le(end) + le(it.state) + le(it.time)
        while body.count % 4 != 0 { body.append(0) }
    }
    return Data("SEGB".utf8) + le(Int32(items.count)) + le(0.0) + Data(count: 16) + body + trailer
}
/// A SEGB v1 file: 56-byte header (end of data first, magic last), records with a 32-byte header, padded to 8.
func segb1(_ items: [(payload: Data, state: Int32, time: Double)]) -> Data {
    var body = Data()
    for it in items {
        body += le(Int32(it.payload.count)) + le(it.state) + le(it.time) + le(it.time) + le(UInt32(0)) + le(Int32(0)) + it.payload
        while (56 + body.count) % 8 != 0 { body.append(0) }
    }
    return le(UInt32(56 + body.count)) + Data(count: 48) + Data("SEGB".utf8) + body
}

let r1 = focusRecord("com.apple.Safari", true, tm(9, 0)), r2 = focusRecord("com.apple.dt.Xcode", true, tm(9, 30)), r3 = Data([0x08, 0x01])
let v2 = SEGB.entries(segb2([(r1, 1, 1), (r2, 1, 2), (r3, 3, 3)]))
check("SEGB v2: written records are read, deleted ones skipped", v2.map(\.payload) == [r1, r2])
check("SEGB v2: the record time comes from the trailer", v2.map(\.time) == [1, 2])
let v1 = SEGB.entries(segb1([(r1, 1, 5), (r3, 3, 6), (r2, 1, 7)]))
check("SEGB v1: written records are read in order", v1.map(\.payload) == [r1, r2] && v1.map(\.time) == [5, 7])
check("not a SEGB file gives nothing", SEGB.entries(Data("hello world, not segb at all, really not".utf8)).isEmpty && SEGB.entries(Data()).isEmpty)
check("a truncated SEGB v2 file does not crash", SEGB.entries(segb2([(r1, 1, 1)]).prefix(40)).isEmpty)

let pf = MiniProto.fields(focusRecord("com.apple.Safari", false, tm(9, 0), display: 1))
check("protobuf: varint, double and string fields", MiniProto.int(pf[3]) == 0 && MiniProto.string(pf[6]) == "com.apple.Safari"
      && MiniProto.double(pf[4]) == cocoa(tm(9, 0)) && MiniProto.int(pf[13]) == 1)
check("protobuf: garbage does not crash", MiniProto.fields(Data([0xFF, 0xFF, 0xFF])).isEmpty || true)
let f1 = MacUsageHistory.focus(SEGB.Entry(time: 0, payload: r1))
check("App.InFocus record: app, front, time", f1 == MacUsageHistory.Focus(time: tm(9, 0), bundle: "com.apple.Safari", starting: true))
check("App.InFocus: iPhone Mirroring apps are not this Mac's", MacUsageHistory.focus(SEGB.Entry(time: 0, payload: focusRecord("com.burbn.instagram", true, tm(9, 0), display: 4))) == nil)
check("Device.ScreenLocked record", MacUsageHistory.lock(SEGB.Entry(time: cocoa(tm(10, 0)), payload: pfield(1, varint: 1))) == MacUsageHistory.Break(time: tm(10, 0), off: true))
check("Backlight record: level 0 is dark", MacUsageHistory.backlight(SEGB.Entry(time: 0, payload: pfield(1, double: cocoa(tm(11, 0))) + pfield(2, varint: 0)))?.off == true)

typealias F = MacUsageHistory.Focus
typealias B = MacUsageHistory.Break
func secs(_ spans: [MacUsageHistory.Span], _ b: String) -> Int { Int(spans.filter { $0.bundle == b }.map { $0.end.timeIntervalSince($0.start) }.reduce(0, +)) }
let sp = MacUsageHistory.spans(focus: [F(time: tm(9, 0), bundle: "s", starting: true), F(time: tm(9, 30), bundle: "x", starting: true),
                                       F(time: tm(10, 40), bundle: "x", starting: false)],
                               breaks: [B(time: tm(10, 0), off: true), B(time: tm(10, 20), off: false)], now: tm(12, 0))
check("history: the next app ends the last one", secs(sp, "s") == 1800)
check("history: a lock stops counting, the unlock carries on with the app in front", secs(sp, "x") == 1800 + 1200)
let sp2 = MacUsageHistory.spans(focus: [F(time: tm(9, 0), bundle: "s", starting: true), F(time: tm(9, 10), bundle: "com.apple.loginwindow", starting: true),
                                        F(time: tm(9, 50), bundle: "com.apple.loginwindow", starting: false)], breaks: [], now: tm(10, 0))
check("history: the lock screen coming up stops counting, going away resumes", secs(sp2, "s") == 600 + 600)
let sp3 = MacUsageHistory.spans(focus: [F(time: tm(1, 0), bundle: "s", starting: true)], breaks: [], now: tm(12, 0))
check("history: a missed sleep never counts more than 4 hours", secs(sp3, "s") == 4 * 3600)
let sp4 = MacUsageHistory.spans(focus: [F(time: tm(9, 0), bundle: "s", starting: true)], breaks: [B(time: tm(9, 5), off: true)], now: tm(12, 0))
check("history: nothing counts while dark or locked at the end", secs(sp4, "s") == 300)
let sp5 = MacUsageHistory.spans(focus: [F(time: tm(9, 0), bundle: "s", starting: true), F(time: tm(9, 10), bundle: "s", starting: true)], breaks: [], now: tm(9, 20))
check("history: the same app reported twice is one stretch", sp5.count == 1 && secs(sp5, "s") == 1200)
let hacc = MacUsageHistory.accumulate(sp, unlocks: MacUsageHistory.unlocks(focus: [], locks: [B(time: tm(8, 59), off: false), B(time: tm(10, 0), off: true), B(time: tm(10, 20), off: false)]),
                                      since: tm(0, 0), calendar: cal, name: { $0.uppercased() })
let hday = hacc.days["2026-11-22"]
check("history: opens and unlocks are counted", hday?.apps["x"]?.opens == 2 && hday?.apps["s"]?.opens == 1 && hday?.unlocks == 2)
check("history: first use is the first unlock", hday?.first == 8 * 3600 + 59 * 60)
check("history: names come from the name function", hday?.apps["x"]?.name == "X")
check("history: unlocks without a lock stream come from the lock screen leaving",
      MacUsageHistory.unlocks(focus: [F(time: tm(7, 0), bundle: "com.apple.loginwindow", starting: false)], locks: []) == [tm(7, 0)])

let plog = """
Time stamp                Domain              \tMessage
2026-11-22 09:20:00 +0530 Notification        \tDisplay is turned off
2026-11-22 09:25:00 +0530 Sleep               \tEntering Sleep state due to 'Idle Sleep'
2026-11-22 09:40:00 +0530 DarkWake            \tDarkWake from Deep Idle
2026-11-22 10:00:00 +0530 Wake                \tWake from Deep Idle [CDNVA] : due to UserActivity
2026-11-22 10:00:01 +0530 Notification        \tDisplay is turned on
2026-11-22 10:05:00 +0530 Assertions          \tPID 1 Created PreventUserIdleSystemSleep
"""
let pb = MacUsageHistory.powerBreaks(plog)
check("power log: display off, sleep, wake and display on; the rest skipped", pb.map(\.off) == [true, true, false, false])
check("power log: times are read with their zone", pb.first?.time == tm(9, 20))
let sp6 = MacUsageHistory.spans(focus: [F(time: tm(9, 0), bundle: "s", starting: true), F(time: tm(10, 0, 5), bundle: "com.apple.loginwindow", starting: true),
                                        F(time: tm(10, 1), bundle: "com.apple.loginwindow", starting: false)], breaks: pb, now: tm(10, 31))
check("power log: the sleep is not counted; after the unlock the app counts again", secs(sp6, "s") == 1200 + 5 + 1800)

// ---- the new numbers on the wire

var full = STAccumulator()
full.add(id: "a", name: "A", from: tm(9, 0, day: 21), to: tm(9, 30, day: 21), calendar: cal)
full.open(id: "a", name: "A", at: tm(9, 0, day: 21), calendar: cal)
full.unlock(at: tm(8, 0, day: 21), calendar: cal)
full.add(id: "a", name: "A", from: tm(10, 0), to: tm(10, 10), calendar: cal)
let fdev = full.device(id: "m", name: "Mac", platform: "macos", now: tm(12, 0), calendar: cal) { $0 == "a" ? "productivity" : nil }
check("every day carries its hours now", fdev.days.allSatisfy { $0.hours?.count == 24 } && fdev.days.first?.hours?[9] == 1800)
check("opens, unlocks, first use and category go out", fdev.days.first?.apps?.first?.opens == 1 && fdev.days.first?.unlocks == 1
      && fdev.days.first?.first == 8 * 3600 && fdev.days.first?.apps?.first?.cat == "productivity")
let wmsg = STWire.message(for: fdev)
let wback = STWire.device(from: wmsg, id: "m", name: "Mac", platform: "macos", now: now)
check("the new numbers survive the wire", wback?.days.first?.unlocks == 1 && wback?.days.first?.first == 8 * 3600
      && wback?.days.first?.apps?.first?.opens == 1 && wback?.days.first?.apps?.first?.cat == "productivity")
let odd = STWire.device(from: ["days": [["day": key(0), "s": 60, "unlocks": -4, "notifs": 9_999_999, "first": 90_000,
                                          "apps": [["id": "z", "name": "Z", "s": 60, "cat": "weapons", "opens": 3]]]]],
                        id: "p", name: "P", platform: "android", now: now)
check("counts are clamped, a bad first use and an unknown category are dropped",
      odd?.days.first?.unlocks == 0 && odd?.days.first?.notifs == 100_000 && odd?.days.first?.first == nil
      && odd?.days.first?.apps?.first?.cat == nil && odd?.days.first?.apps?.first?.opens == 3)
let oldStyle = STWire.device(from: ["days": [["day": key(0), "s": 60]]], id: "p", name: "P", platform: "android", now: now)
check("a device without the new numbers still works", oldStyle?.days.first?.unlocks == nil && oldStyle?.days.first?.seconds == 60)
let oldJSON = Data(#"{"days":{"2026-11-22":{"seconds":60,"hours":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,60],"apps":{"a":{"name":"A","seconds":60}}}}}"#.utf8)
check("a Mac's saved tracking from before still loads", (try? JSONDecoder().decode(STAccumulator.self, from: oldJSON))?.days["2026-11-22"]?.seconds == 60)

// ---- categories

check("Apple categories map to ours", STCategory.fromApple("public.app-category.developer-tools") == "productivity"
      && STCategory.fromApple("public.app-category.action-games") == "games" && STCategory.fromApple("public.app-category.social-networking") == "social")
check("well-known apps get a category", STCategory.guess("com.instagram.android") == "social" && STCategory.guess("com.zeptonow.customer") == "shopping"
      && STCategory.guess("com.google.android.youtube") == "entertainment" && STCategory.guess("com.unknown.thing") == nil)
check("a device's own category wins", STCategory.of(STApp(id: "com.instagram.android", name: "I", seconds: 1, cat: "games")) == "games")

// ---- the Screen Time page

var page = STSnapshot()
page.upsert(STDevice(id: "phone", name: "Phone", platform: "android", updated: now, days: [
    STDay(day: key(1), seconds: 3 * 3600, hours: hours([20: 3 * 3600]), apps: [STApp(id: "com.instagram.android", name: "Instagram", seconds: 3 * 3600, opens: 10, notifs: 4)],
          unlocks: 30, notifs: 50, first: 7 * 3600),
    STDay(day: key(0), seconds: 2 * 3600, hours: hours([9: 3600, 20: 3600]),
          apps: [STApp(id: "com.instagram.android", name: "Instagram", seconds: 5400, opens: 6, notifs: 2, cat: "social"),
                 STApp(id: "com.whatsapp", name: "WhatsApp", seconds: 1800, opens: 3, notifs: 9)],
          unlocks: 20, notifs: 40, first: 6 * 3600 + 30 * 60)]))
page.upsert(STDevice(id: "mac", name: "Mac", platform: "macos", updated: now, days: [
    STDay(day: key(0), seconds: 3600, hours: hours([9: 1800, 14: 1800]),
          apps: [STApp(id: "com.apple.dt.Xcode", name: "Xcode", seconds: 2400, opens: 2), STApp(id: "com.instagram.android", name: "Instagram", seconds: 1200)],
          unlocks: 3, first: 8 * 3600)]))
page.upsert(STDevice(id: "pc", name: "PC", platform: "windows", updated: now, days: [STDay(day: key(0), seconds: 600, hours: nil, apps: [STApp(id: "code.exe", name: "Code", seconds: 600)])]))

let today = STReport.make(page, filter: .all, range: .day, anchor: now, now: now, calendar: cal)
check("page: today's total across every device", today.total == 2 * 3600 + 3600 + 600)
check("page: the same app on two devices is one row with both platforms",
      today.apps.first?.id == "com.instagram.android" && today.apps.first?.seconds == 5400 + 1200 && today.apps.first?.platforms == ["android", "macos"])
check("page: opens and notifications add up per app", today.apps.first?.opens == 6 && today.apps.first?.notifs == 2)
check("page: hourly bars per device group", today.bars.first(where: { $0.bucket == 9 && $0.group == .mobile })?.seconds == 3600
      && today.bars.first(where: { $0.bucket == 9 && $0.group == .mac })?.seconds == 1800)
check("page: pickups and notifications only from devices that count them", today.unlocks == 23 && today.notifs == 40)
check("page: first use is the earliest device's", today.firstUse == 6 * 3600 + 30 * 60)
// "now" is 20:30: yesterday's phone hours put all 3 h at 20:00-21:00, so by 20:30 it had 1.5 h; the PC (no hours) has no
// earlier day. Today is compared with that, not with yesterday's whole day.
check("page: today is compared with the days before up to the same time", today.baseline == 3 * 3600 / 2)
let endOfToday = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: now))!
let afterToday = STReport.make(page, filter: .all, range: .day, anchor: now, now: endOfToday.addingTimeInterval(3600), calendar: cal)
check("page: a finished day is compared with whole days", afterToday.baseline == 3 * 3600)
var noHours = STSnapshot()
noHours.upsert(STDevice(id: "o", name: "Old", platform: "android", updated: now, days: [STDay(day: key(1), seconds: 7200, hours: nil, apps: nil),
                                                                                      STDay(day: key(0), seconds: 60, hours: nil, apps: nil)]))
check("page: without earlier hours there is no comparison for today", STReport.make(noHours, filter: .all, range: .day, anchor: now, now: now, calendar: cal).baseline == nil)
check("page: categories", today.categories.first?.category == "social" && today.categories.contains { $0.category == "productivity" })
check("page: devices sorted by time, with when they last reported", today.devices.map(\.id) == ["phone", "mac", "pc"])
check("page: there is earlier data, nothing later than today", today.hasEarlier && !today.hasLater)
var calMon = cal; calMon.firstWeekday = 2
let todayMon = STReport.make(page, filter: .all, range: .day, anchor: now, now: now, calendar: calMon)
check("page: each app has its week (Monday-first)", todayMon.apps.first?.week.reduce(0, +) == 3 * 3600 + 5400 + 1200)
check("page: with Sunday-first weeks, Sunday starts a new week", today.apps.first?.week.reduce(0, +) == 5400 + 1200)

let mobile = STReport.make(page, filter: .mobile, range: .day, anchor: now, now: now, calendar: cal)
check("page: Mobile leaves the Mac and the PC out", mobile.total == 2 * 3600 && mobile.devices.map(\.id) == ["phone"] && mobile.apps.first?.platforms == ["android"])
let mac = STReport.make(page, filter: .mac, range: .day, anchor: now, now: now, calendar: cal)
check("page: Mac has no notification count", mac.total == 3600 && mac.notifs == nil && mac.unlocks == 3)
let other = STReport.make(page, filter: .other, range: .day, anchor: now, now: now, calendar: cal)
check("page: Other is the PC; no hours means no hourly bars", other.total == 600 && other.bars.isEmpty && other.unlocks == nil)
let one = STReport.make(page, filter: .mobile, device: "mac", range: .day, anchor: now, now: now, calendar: cal)
check("page: one device wins over the filter", one.total == 3600)

let week = STReport.make(page, filter: .all, range: .week, anchor: now, now: now, calendar: calMon)
check("page: the week holds both days", week.total == 3 * 3600 + 2 * 3600 + 3600 + 600)
check("page: week bars are per day", Set(week.bars.map(\.bucket)).count == 2)
let elapsedDays = week.weekDays.filter { $0 <= now }.count
check("page: daily average over the days of the week so far", week.dailyAverage == week.total / elapsedDays)
let yesterdayPage = STReport.make(page, filter: .all, range: .day, anchor: cal.date(byAdding: .day, value: -1, to: now)!, now: now, calendar: cal)
check("page: an earlier day can go forward, and has no earlier data", yesterdayPage.hasLater && !yesterdayPage.hasEarlier && yesterdayPage.total == 3 * 3600)
check("page: an empty day is empty", STReport.make(STSnapshot(), filter: .all, range: .day, anchor: now, now: now, calendar: cal).isEmpty)
check("page: clock times", STText.clock(6 * 3600 + 30 * 60, on: now, calendar: cal).contains("6") && STText.clock(6 * 3600 + 30 * 60, on: now, calendar: cal).contains("30"))
check("page: how long ago", STText.ago(now.addingTimeInterval(-30), now: now) == "just now" && STText.ago(now.addingTimeInterval(-600), now: now) == "10 min ago")

// ---- axis labels: they end at "now" and follow the 12/24-hour setting
var en = Calendar(identifier: .gregorian); en.locale = Locale(identifier: "en_US")
check("24-hour labels", STText.hour(13, h24: true, calendar: en) == "13:00" && STText.hour(0, h24: true, calendar: en) == "00:00")
check("12-hour labels with AM/PM", STText.hour(13, h24: false, calendar: en) == "1 PM" && STText.hour(0, h24: false, calendar: en) == "12 AM"
      && STText.hour(12, h24: false, calendar: en) == "12 PM" && STText.hour(9, h24: false, calendar: en) == "9 AM")
check("the region decides when there is no override", STText.uses24Hour(locale: Locale(identifier: "de_DE")) && !STText.uses24Hour(locale: Locale(identifier: "en_US")))
check("labels end at the current hour", STText.ticksBack(from: 13, step: 3) == [1, 4, 7, 10, 13])
check("labels end at today", STText.ticksBack(from: 13, step: 2) == [1, 3, 5, 7, 9, 11, 13] && STText.ticksBack(from: 6, step: 1) == [0, 1, 2, 3, 4, 5, 6])
check("a label step that fits", STText.step(span: 14, maxLabels: 7) == 2 && STText.step(span: 7, maxLabels: 7) == 1 && STText.step(span: 24, maxLabels: 6) == 4)
check("just after midnight there is one label", STText.ticksBack(from: 0, step: 3) == [0])

print("screentimecheck: \(passed) passed, \(failed) failed"); exit(failed == 0 ? 0 : 1)
