import Foundation

// The pure half of screen time on the Mac (docs/protocol.md §15): adding up time per app, hour and day, and turning it into
// (and back out of) the wire message. No AppKit, no clock of its own: tools/screentimecheck checks it.

/// Time spent in apps, per day, per hour, per app. Codable so the Mac's own tracking survives restarts.
nonisolated struct STAccumulator: Codable, Equatable {
    nonisolated struct App: Codable, Equatable { var name: String; var seconds: Double }
    nonisolated struct Day: Codable, Equatable {
        var seconds: Double = 0
        var hours: [Double] = Array(repeating: 0, count: 24)
        var apps: [String: App] = [:]
    }

    var days: [String: Day] = [:]

    /// The longest single stretch counted at once. A longer gap (sleep the tracker missed) is cut, never counted whole.
    static let maxSegment: TimeInterval = 4 * 3600

    /// Adds the time from `start` to `end` in app `id`, split at hour (and so day) boundaries.
    mutating func add(id: String, name: String, from start: Date, to end: Date, calendar: Calendar) {
        guard end > start, !id.isEmpty else { return }
        var cur = start
        let stop = min(end, start.addingTimeInterval(Self.maxSegment))
        while cur < stop {
            guard let hourStart = calendar.dateInterval(of: .hour, for: cur) else { return }
            let pieceEnd = min(stop, hourStart.end)
            let secs = pieceEnd.timeIntervalSince(cur)
            let key = STSummary.dayKey(cur, calendar: calendar)
            let hour = calendar.component(.hour, from: cur)
            var day = days[key] ?? Day()
            day.seconds += secs
            if (0..<24).contains(hour) { day.hours[hour] += secs }
            var app = day.apps[id] ?? App(name: name, seconds: 0)
            app.seconds += secs
            if !name.isEmpty { app.name = name }
            day.apps[id] = app
            days[key] = day
            cur = pieceEnd
        }
    }

    /// Keeps only the newest `keep` days.
    mutating func prune(keep: Int) {
        guard days.count > keep else { return }
        for k in days.keys.sorted().dropLast(keep) { days.removeValue(forKey: k) }
    }

    /// The device as the widget and the protocol see it: the newest `maxDays` days, today with hours and apps.
    func device(id: String, name: String, platform: String, now: Date, calendar: Calendar,
                maxDays: Int = 14, maxApps: Int = 20) -> STDevice {
        let todayKey = STSummary.dayKey(now, calendar: calendar)
        let keys = days.keys.sorted().suffix(maxDays)
        let out: [STDay] = keys.map { k in
            let d = days[k]!
            let apps = d.apps.map { STApp(id: $0.key, name: $0.value.name, seconds: Int($0.value.seconds.rounded())) }
                .filter { $0.seconds > 0 }
                .sorted { $0.seconds != $1.seconds ? $0.seconds > $1.seconds : $0.id < $1.id }
            return STDay(day: k, seconds: Int(d.seconds.rounded()),
                         hours: k == todayKey ? d.hours.map { Int($0.rounded()) } : nil,
                         apps: Array(apps.prefix(maxApps)))
        }
        return STDevice(id: id, name: name, platform: platform, updated: now, days: out)
    }
}

/// The §15 messages.
nonisolated enum STWire {
    static let maxDays = 31
    static let maxApps = 50
    static let maxIconBytes = 96 * 1024

    /// `{"t":"screen_time","days":[{"day":"…","s":…,"hours":[…],"apps":[{"id":…,"name":…,"s":…}]}, …]}`
    static func message(for device: STDevice) -> [String: Any] {
        let days: [[String: Any]] = device.days.suffix(maxDays).map { d in
            var o: [String: Any] = ["day": d.day, "s": d.seconds]
            if let h = d.hours, h.count == 24 { o["hours"] = h }
            if let a = d.apps, !a.isEmpty { o["apps"] = a.prefix(maxApps).map { ["id": $0.id, "name": $0.name, "s": $0.seconds] as [String: Any] } }
            return o
        }
        return ["t": "screen_time", "days": days]
    }

    /// The device behind a received `screen_time`, or nil if the message is malformed. Values are clamped to what a day can hold.
    static func device(from msg: [String: Any], id: String, name: String, platform: String, now: Date) -> STDevice? {
        guard let raw = msg["days"] as? [Any], !raw.isEmpty, raw.count <= maxDays else { return nil }
        var days: [STDay] = []
        for item in raw {
            guard let o = item as? [String: Any], let key = o["day"] as? String, isDayKey(key), let s = number(o["s"]) else { return nil }
            var hours: [Int]?
            if let h = o["hours"] as? [Any] {
                let v = h.compactMap(number)
                guard v.count == 24, h.count == 24 else { return nil }
                hours = v.map { min(3600, max(0, $0)) }
            }
            var apps: [STApp]?
            if let a = o["apps"] as? [Any] {
                apps = a.prefix(maxApps).compactMap { e -> STApp? in
                    guard let e = e as? [String: Any], let aid = e["id"] as? String, !aid.isEmpty, let secs = number(e["s"]) else { return nil }
                    let nm = (e["name"] as? String).map { String($0.prefix(80)) } ?? aid
                    return STApp(id: String(aid.prefix(200)), name: nm, seconds: min(86_400, max(0, secs)))
                }
            }
            days.append(STDay(day: key, seconds: min(86_400, max(0, s)), hours: hours, apps: apps))
        }
        return STDevice(id: id, name: name, platform: platform, updated: now, days: days)
    }

    static func isDayKey(_ s: String) -> Bool {
        let p = s.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, p[0].count == 4, p[1].count == 2, p[2].count == 2,
              let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]) else { return false }
        return (2000...2200).contains(y) && (1...12).contains(m) && (1...31).contains(d)
    }

    private static func number(_ v: Any?) -> Int? {
        switch v {
        case let i as Int: return i
        case let d as Double where d.isFinite: return Int(d)
        case let n as NSNumber: return n.intValue
        default: return nil
        }
    }

    /// `{"t":"screen_time_icon","id":…,"png":"<base64>"}` → (app id, PNG bytes), or nil.
    static func icon(from msg: [String: Any]) -> (id: String, png: Data)? {
        guard let id = msg["id"] as? String, !id.isEmpty, id.count <= 200, let b64 = msg["png"] as? String,
              let data = Data(base64Encoded: b64), !data.isEmpty, data.count <= maxIconBytes,
              data.starts(with: [0x89, 0x50, 0x4E, 0x47]) else { return nil }
        return (id, data)
    }

    static func iconMessage(id: String, png: Data) -> [String: Any]? {
        guard png.count <= maxIconBytes else { return nil }
        return ["t": "screen_time_icon", "id": id, "png": png.base64EncodedString()]
    }
}
