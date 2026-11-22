import Foundation

// What the Screen Time page shows for one choice of devices and dates, worked out from the shared snapshot. Pure (no files, no
// clock of its own), so tools/screentimecheck checks it.

/// Which devices count: everything (default), phones and tablets, Macs, or the rest (Windows, Linux, …).
nonisolated enum STFilter: String, CaseIterable, Sendable, Identifiable {
    case all, mobile, mac, other
    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "All"
        case .mobile: "Mobile"
        case .mac: "Mac"
        case .other: "Other"
        }
    }

    /// The group a platform belongs to.
    static func group(_ platform: String) -> STFilter {
        switch platform {
        case "android", "ios", "ipados": .mobile
        case "macos": .mac
        default: .other
        }
    }

    func includes(_ platform: String) -> Bool { self == .all || Self.group(platform) == self }
}

nonisolated enum STRange: String, CaseIterable, Sendable { case day, week }

nonisolated struct STReport: Sendable, Equatable {
    /// One bar piece: a bucket (hour of the day, or day of the week) and one device group's seconds in it.
    struct Bar: Hashable, Sendable { let bucket: Int; let date: Date; let group: STFilter; let seconds: Int }
    struct AppRow: Hashable, Sendable, Identifiable {
        let id: String
        let name: String
        let seconds: Int
        let opens: Int?
        let notifs: Int?
        let category: String
        /// Platforms it was used on, most time first.
        let platforms: [String]
        /// Seconds on each day of the selected date's week (7 values, for the app's own chart).
        let week: [Int]
    }
    struct DeviceRow: Hashable, Sendable, Identifiable {
        let id: String
        let name: String
        let platform: String
        let seconds: Int
        let updated: Date
    }
    struct CategoryRow: Hashable, Sendable { let category: String; let seconds: Int }

    let range: STRange
    let start: Date
    /// Exclusive end of the range.
    let end: Date
    let total: Int
    /// Week: total divided by the days of the week so far. Day: nil.
    let dailyAverage: Int?
    /// Day: the mean of the 7 days before (with data); for today, of those days up to the same time of day (days without hourly
    /// numbers are left out). Week: last week's daily average.
    let baseline: Int?
    let bars: [Bar]
    let apps: [AppRow]
    let categories: [CategoryRow]
    let devices: [DeviceRow]
    /// Nil when no device in the selection counts these.
    let unlocks: Int?
    let notifs: Int?
    /// Day only: earliest first use, seconds after midnight.
    let firstUse: Int?
    /// Whether any data exists before / after this range.
    let hasEarlier: Bool
    let hasLater: Bool
    /// The days of the week shown in each app's chart.
    let weekDays: [Date]

    var isEmpty: Bool { total == 0 && apps.isEmpty }

    /// Today vs the baseline as a signed number of seconds (day) or of daily-average seconds (week).
    var change: Int? {
        guard let baseline, baseline > 0 else { return nil }
        return (range == .week ? (dailyAverage ?? 0) : total) - baseline
    }
    /// The change as a percentage of the baseline.
    var changePercent: Int? {
        guard let change, let baseline, baseline > 0 else { return nil }
        return Int((Double(change) / Double(baseline) * 100).rounded())
    }

    /// `device` (an id) narrows to one device and wins over `filter`. `anchor` is any moment in the wanted day or week.
    static func make(_ snapshot: STSnapshot, filter: STFilter, device: String? = nil, range: STRange, anchor: Date, now: Date,
                     calendar: Calendar = .current) -> STReport {
        let devs = snapshot.devices.filter { d in device.map { d.id == $0 } ?? filter.includes(d.platform) }
        let interval: DateInterval = range == .day
            ? (calendar.dateInterval(of: .day, for: anchor) ?? DateInterval(start: anchor, duration: 86_400))
            : (calendar.dateInterval(of: .weekOfYear, for: anchor) ?? DateInterval(start: anchor, duration: 7 * 86_400))
        let days = dayList(interval, calendar)
        let keys = Set(days.map { STSummary.dayKey($0, calendar: calendar) })
        let todayKey = STSummary.dayKey(now, calendar: calendar)
        let weekInterval = calendar.dateInterval(of: .weekOfYear, for: anchor) ?? interval
        let weekDays = dayList(weekInterval, calendar)
        let weekKeys = weekDays.map { STSummary.dayKey($0, calendar: calendar) }

        var total = 0
        var bars: [String: Int] = [:]           // "bucket|group"
        var appAcc: [String: (name: String, s: Int, opens: Int?, notifs: Int?, cat: String?, plat: [String: Int])] = [:]
        var appWeek: [String: [Int]] = [:]
        var devRows: [DeviceRow] = []
        var unlocks: Int?, notifs: Int?, first: Int?

        for d in devs {
            var devTotal = 0
            for day in d.days {
                if let wi = weekKeys.firstIndex(of: day.day) {
                    for a in day.apps ?? [] { appWeek[a.id, default: Array(repeating: 0, count: 7)][wi] += max(0, a.seconds) }
                }
                guard keys.contains(day.day) else { continue }
                let secs = max(0, day.seconds)
                total += secs; devTotal += secs
                let g = STFilter.group(d.platform)
                if range == .day {
                    if let h = day.hours, h.count == 24 { for i in 0..<24 where h[i] > 0 { bars["\(i)|\(g.rawValue)", default: 0] += h[i] } }
                } else if let i = days.firstIndex(where: { STSummary.dayKey($0, calendar: calendar) == day.day }) {
                    bars["\(i)|\(g.rawValue)", default: 0] += secs
                }
                if let u = day.unlocks { unlocks = (unlocks ?? 0) + u }
                if let n = day.notifs { notifs = (notifs ?? 0) + n }
                if range == .day, let f = day.first { first = min(first ?? f, f) }
                for a in day.apps ?? [] {
                    var e = appAcc[a.id] ?? (name: a.name, s: 0, opens: nil, notifs: nil, cat: nil, plat: [:])
                    e.s += max(0, a.seconds)
                    if let o = a.opens { e.opens = (e.opens ?? 0) + o }
                    if let n = a.notifs { e.notifs = (e.notifs ?? 0) + n }
                    if e.cat == nil { e.cat = STCategory.valid(a.cat) }
                    if e.name.isEmpty || e.name == a.id { e.name = a.name }
                    e.plat[d.platform, default: 0] += max(0, a.seconds) + 1
                    appAcc[a.id] = e
                }
            }
            if devTotal > 0 { devRows.append(DeviceRow(id: d.id, name: d.name, platform: d.platform, seconds: devTotal, updated: d.updated)) }
        }

        var apps: [AppRow] = []
        for (id, e) in appAcc {
            let name: String = e.name.isEmpty ? id : e.name
            let category: String = STCategory.valid(e.cat) ?? STCategory.guess(id) ?? "other"
            let platforms: [String] = e.plat.sorted { $0.value > $1.value }.map { $0.key }
            let week: [Int] = appWeek[id] ?? Array(repeating: 0, count: 7)
            apps.append(AppRow(id: id, name: name, seconds: e.s, opens: e.opens, notifs: e.notifs,
                               category: category, platforms: platforms, week: week))
        }
        apps.sort { a, b in
            if a.seconds != b.seconds { return a.seconds > b.seconds }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }

        var catSum: [String: Int] = [:]
        for a in apps where a.seconds > 0 { catSum[a.category, default: 0] += a.seconds }
        let categories = catSum.map { CategoryRow(category: $0.key, seconds: $0.value) }.sorted { $0.seconds > $1.seconds }

        let bucketCount = range == .day ? 24 : days.count
        var barList: [Bar] = []
        for b in 0..<bucketCount {
            let date = range == .day ? (calendar.date(byAdding: .hour, value: b, to: interval.start) ?? interval.start) : days[b]
            for g in [STFilter.mobile, .mac, .other] {
                if let s = bars["\(b)|\(g.rawValue)"], s > 0 { barList.append(Bar(bucket: b, date: date, group: g, seconds: s)) }
            }
        }

        // Averages and the comparison.
        var perDay: [String: Int] = [:]
        for d in devs { for day in d.days { perDay[day.day, default: 0] += max(0, day.seconds) } }
        var dailyAverage: Int?
        var baseline: Int?
        if range == .day {
            let key = STSummary.dayKey(interval.start, calendar: calendar)
            if interval.contains(now) {
                // Today is not over: compare with the days before up to the same time of day (from their hours).
                let cut = now.timeIntervalSince(interval.start)
                var partial: [String: Int] = [:], unknown = Set<String>()
                for d in devs {
                    for day in d.days where day.day < key && day.seconds > 0 {
                        guard let h = day.hours, h.count == 24 else { unknown.insert(day.day); continue }
                        let whole = min(24, Int(cut / 3600))
                        var v = h.prefix(whole).reduce(0, +)
                        if whole < 24 { v += Int(Double(h[whole]) * (cut - Double(whole) * 3600) / 3600) }
                        partial[day.day, default: 0] += v
                    }
                }
                let prev = perDay.filter { $0.key < key && $0.value > 0 }.keys.sorted(by: >).prefix(7).filter { !unknown.contains($0) }
                baseline = prev.isEmpty ? nil : prev.map { partial[$0] ?? 0 }.reduce(0, +) / prev.count
            } else {
                let prev = perDay.filter { $0.key < key && $0.value > 0 }.sorted { $0.key > $1.key }.prefix(7)
                baseline = prev.isEmpty ? nil : prev.map(\.value).reduce(0, +) / prev.count
            }
        } else {
            let elapsed = days.filter { STSummary.dayKey($0, calendar: calendar) <= todayKey }.count
            dailyAverage = elapsed > 0 ? total / elapsed : nil
            if let lastWeekStart = calendar.date(byAdding: .day, value: -7, to: interval.start) {
                let lastKeys = dayList(DateInterval(start: lastWeekStart, end: interval.start), calendar).map { STSummary.dayKey($0, calendar: calendar) }
                let withData = lastKeys.filter { (perDay[$0] ?? 0) > 0 }
                baseline = withData.isEmpty ? nil : lastKeys.map { perDay[$0] ?? 0 }.reduce(0, +) / lastKeys.count
            }
        }

        let startKey = STSummary.dayKey(interval.start, calendar: calendar)
        let endKey = STSummary.dayKey(interval.end.addingTimeInterval(-1), calendar: calendar)
        let allKeys = perDay.filter { $0.value > 0 }.keys
        return STReport(
            range: range, start: interval.start, end: interval.end, total: total, dailyAverage: dailyAverage, baseline: baseline,
            bars: barList, apps: apps, categories: categories, devices: devRows.sorted { $0.seconds > $1.seconds },
            unlocks: unlocks, notifs: notifs, firstUse: first,
            hasEarlier: allKeys.contains { $0 < startKey }, hasLater: endKey < todayKey, weekDays: weekDays)
    }

    private static func dayList(_ interval: DateInterval, _ calendar: Calendar) -> [Date] {
        var out: [Date] = []
        var d = calendar.startOfDay(for: interval.start)
        while d < interval.end, out.count < 31 {
            out.append(d)
            guard let next = calendar.date(byAdding: .day, value: 1, to: d) else { break }
            d = next
        }
        return out
    }
}

/// Times of day and counts for the page.
nonisolated enum STText {
    /// Seconds after midnight as a clock time in `calendar`'s locale ("7:42 AM" / "07:42").
    static func clock(_ secondsAfterMidnight: Int, on day: Date, calendar: Calendar) -> String {
        let d = calendar.startOfDay(for: day).addingTimeInterval(TimeInterval(secondsAfterMidnight))
        let f = DateFormatter()
        f.calendar = calendar; f.timeZone = calendar.timeZone
        f.setLocalizedDateFormatFromTemplate("jmm")
        return f.string(from: d)
    }

    /// "just now", "4 min ago", "2 h ago", "yesterday", "3 days ago".
    static func ago(_ date: Date, now: Date) -> String {
        let s = Int(now.timeIntervalSince(date))
        if s < 60 { return "just now" }
        if s < 3600 { return "\(s / 60) min ago" }
        if s < 86_400 { return "\(s / 3600) h ago" }
        if s < 2 * 86_400 { return "yesterday" }
        return "\(s / 86_400) days ago"
    }
}

/// airbridge://screentime[?device=ID]: the Screen Time page, opened by a click on the widget.
nonisolated enum STLink {
    static func page(device: String?) -> URL {
        var c = URLComponents()
        c.scheme = "airbridge"; c.host = "screentime"
        if let device { c.queryItems = [URLQueryItem(name: "device", value: device)] }
        return c.url ?? URL(string: "airbridge://screentime")!
    }

    /// The device in a page link (nil: all devices).
    static func device(in url: URL) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "device" }?.value.flatMap { $0.isEmpty ? nil : $0 }
    }
}
