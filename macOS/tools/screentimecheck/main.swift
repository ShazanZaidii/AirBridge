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

print("screentimecheck: \(passed) passed, \(failed) failed"); exit(failed == 0 ? 0 : 1)
