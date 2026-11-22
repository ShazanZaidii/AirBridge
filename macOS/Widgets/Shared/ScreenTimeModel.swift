import Foundation

// Screen time from every device AirBridge knows (docs/protocol.md §15). The app writes it, the AirBridge widget reads it,
// through the shared app group container. Everything here is plain value types and pure functions, so tools/screentimecheck
// can check it without the app or the widget.

/// Where the shared screen-time data lives.
nonisolated enum ScreenTimeShared {
    static let groupId = "group.com.shazan.AirBridge"
    /// The widget's kind, also what the app reloads when new data arrives.
    static let widgetKind = "AirBridgeScreenTime"
    /// How many days of history are kept per device.
    static let keepDays = 30

    static func folder() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)?
            .appendingPathComponent("ScreenTime", isDirectory: true)
    }

    static func snapshotURL(in folder: URL) -> URL { folder.appendingPathComponent("devices.json") }

    static func iconURL(appId: String, in folder: URL) -> URL {
        folder.appendingPathComponent("icons", isDirectory: true).appendingPathComponent(fileSafe(appId) + ".png")
    }

    /// An app id as a file name: letters, digits, dot, dash and underscore only.
    static func fileSafe(_ id: String) -> String {
        let ok = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
        let cleaned = String(id.prefix(120).map { ok.contains($0) ? $0 : "_" })
        return cleaned.isEmpty || cleaned.allSatisfy({ $0 == "." }) ? "_" : cleaned
    }
}

/// Time in one app on one day.
nonisolated struct STApp: Codable, Hashable, Sendable {
    var id: String
    var name: String
    var seconds: Int
    /// Times it was opened (came on screen), when the device counts that.
    var opens: Int? = nil
    /// Notifications it showed, when the device counts that.
    var notifs: Int? = nil
    /// Its kind ("social", "games", …, see `STCategory`), when the device knows it.
    var cat: String? = nil
}

/// One day on one device. `day` is the device's own calendar date, "yyyy-MM-dd".
nonisolated struct STDay: Codable, Hashable, Sendable {
    var day: String
    var seconds: Int
    /// Seconds in each hour of the day (24 values), when the device sent them (usually today only).
    var hours: [Int]?
    /// The day's apps, most used first, when the device sent them.
    var apps: [STApp]?
    /// Unlocks ("pickups"), when the device counts them.
    var unlocks: Int? = nil
    /// Notifications that day, when the device counts them.
    var notifs: Int? = nil
    /// When the device was first used that day: seconds after midnight.
    var first: Int? = nil
}

/// What one device reported: its identity and recent days.
nonisolated struct STDevice: Codable, Hashable, Sendable {
    var id: String
    var name: String
    /// The device's `plat` from its hello: "android", "macos", "windows", "linux", "ios".
    var platform: String
    var updated: Date
    var days: [STDay]
}

/// Everything the widget shows.
nonisolated struct STSnapshot: Codable, Sendable {
    var devices: [STDevice] = []

    /// Replaces or adds `device`. Days it sent replace the stored ones with the same date; older days are kept up to
    /// `keepDays`, so a device that only sends today still builds up a history.
    mutating func upsert(_ device: STDevice, keepDays: Int = ScreenTimeShared.keepDays) {
        var merged = device
        if let old = devices.first(where: { $0.id == device.id }) {
            var byDay = Dictionary(old.days.map { ($0.day, $0) }, uniquingKeysWith: { a, _ in a })
            for d in device.days { byDay[d.day] = d }
            merged.days = byDay.values.sorted { $0.day < $1.day }
        } else {
            merged.days = device.days.sorted { $0.day < $1.day }
        }
        if merged.days.count > keepDays { merged.days = Array(merged.days.suffix(keepDays)) }
        devices.removeAll { $0.id == device.id }
        devices.append(merged)
        devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    mutating func remove(deviceId: String) { devices.removeAll { $0.id == deviceId } }
}

/// Reading and writing the snapshot file. `folder` is a parameter so the checks can use a temporary folder.
nonisolated enum STStore {
    static func load(from folder: URL? = ScreenTimeShared.folder()) -> STSnapshot {
        guard let folder, let data = try? Data(contentsOf: ScreenTimeShared.snapshotURL(in: folder)) else { return STSnapshot() }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .secondsSince1970
        return (try? dec.decode(STSnapshot.self, from: data)) ?? STSnapshot()
    }

    @discardableResult
    static func save(_ snapshot: STSnapshot, to folder: URL? = ScreenTimeShared.folder()) -> Bool {
        guard let folder else { return false }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .secondsSince1970
        guard let data = try? enc.encode(snapshot) else { return false }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: ScreenTimeShared.snapshotURL(in: folder), options: .atomic)
            return true
        } catch { return false }
    }

    /// Saves an app's icon (PNG) next to the snapshot. Small icons only.
    @discardableResult
    static func saveIcon(_ png: Data, appId: String, in folder: URL? = ScreenTimeShared.folder()) -> Bool {
        guard let folder, !png.isEmpty, png.count <= 256 * 1024 else { return false }
        let url = ScreenTimeShared.iconURL(appId: appId, in: folder)
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try png.write(to: url, options: .atomic)
            return true
        } catch { return false }
    }

    static func hasIcon(appId: String, in folder: URL? = ScreenTimeShared.folder()) -> Bool {
        guard let folder else { return false }
        return FileManager.default.fileExists(atPath: ScreenTimeShared.iconURL(appId: appId, in: folder).path)
    }
}

/// What the widget draws for one selection (all devices, or one), worked out from a snapshot.
nonisolated struct STSummary: Sendable, Equatable {
    struct Point: Hashable, Sendable { let date: Date; let seconds: Int }
    struct TopApp: Hashable, Sendable {
        let id: String
        let name: String
        let seconds: Int
        /// Platform of the device the app was used on most today (for the little device badge).
        let platform: String
    }

    let title: String
    /// Today's total.
    let today: Int
    /// Mean of the previous days that have data (up to 7), nil when there are none yet.
    let average: Int?
    /// One point per day, oldest first, ending today, zero-filled from the first day with data (at most `trendDays`).
    let trend: [Point]
    /// Today's running total at the end of each hour so far (for a first day, when there is no trend yet).
    let todayCumulative: [Point]
    let topApps: [TopApp]
    /// How many devices contributed today.
    let deviceCount: Int

    static let trendDays = 14

    /// The calendar date key "yyyy-MM-dd" for `date` in `calendar`.
    static func dayKey(_ date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func date(fromKey key: String, calendar: Calendar) -> Date? {
        let p = key.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: p[0], month: p[1], day: p[2]))
    }

    /// `deviceId` nil means all devices.
    static func make(_ snapshot: STSnapshot, deviceId: String?, now: Date, calendar: Calendar = .current) -> STSummary {
        let devices = deviceId.map { id in snapshot.devices.filter { $0.id == id } } ?? snapshot.devices
        let title = deviceId == nil ? "All devices" : (devices.first?.name ?? "Device")
        let todayKey = dayKey(now, calendar: calendar)

        // Seconds per day across the selection.
        var perDay: [String: Int] = [:]
        for d in devices { for day in d.days { perDay[day.day, default: 0] += max(0, day.seconds) } }
        let today = perDay[todayKey] ?? 0

        // Previous days with data, newest first, up to 7.
        let previous = perDay.filter { $0.key < todayKey && $0.value > 0 }.sorted { $0.key > $1.key }.prefix(7)
        let average = previous.isEmpty ? nil : previous.map(\.value).reduce(0, +) / previous.count

        // Trend: from the first day with data (or today), at most trendDays, ending today.
        let startOfToday = calendar.startOfDay(for: now)
        let firstKey = perDay.filter { $0.value > 0 }.keys.min() ?? todayKey
        let firstDate = date(fromKey: firstKey, calendar: calendar) ?? startOfToday
        let span = (calendar.dateComponents([.day], from: calendar.startOfDay(for: firstDate), to: startOfToday).day ?? 0) + 1
        let count = min(trendDays, max(1, span))
        var trend: [Point] = []
        for back in stride(from: count - 1, through: 0, by: -1) {
            guard let d = calendar.date(byAdding: .day, value: -back, to: startOfToday) else { continue }
            trend.append(Point(date: d, seconds: perDay[dayKey(d, calendar: calendar)] ?? 0))
        }

        // Today's running total per hour, up to the current hour.
        var hours = [Int](repeating: 0, count: 24)
        for d in devices {
            guard let day = d.days.first(where: { $0.day == todayKey }), let h = day.hours, h.count == 24 else { continue }
            for i in 0..<24 { hours[i] += max(0, h[i]) }
        }
        let nowHour = calendar.component(.hour, from: now)
        var running = 0
        var cumulative: [Point] = []
        if hours.contains(where: { $0 > 0 }) {
            for i in 0...nowHour {
                running += hours[i]
                if let d = calendar.date(byAdding: .hour, value: i + 1, to: startOfToday) {
                    cumulative.append(Point(date: min(d, now), seconds: running))
                }
            }
        }

        // Top apps today, merged across devices by app id.
        var byApp: [String: (name: String, seconds: Int, best: (platform: String, seconds: Int))] = [:]
        var contributing = 0
        for d in devices {
            guard let day = d.days.first(where: { $0.day == todayKey }) else { continue }
            if day.seconds > 0 { contributing += 1 }
            for a in day.apps ?? [] where a.seconds > 0 {
                var e = byApp[a.id] ?? (name: a.name, seconds: 0, best: (platform: d.platform, seconds: 0))
                e.seconds += a.seconds
                if a.seconds > e.best.seconds { e.best = (d.platform, a.seconds) }
                if e.name.isEmpty { e.name = a.name }
                byApp[a.id] = e
            }
        }
        let top = byApp.map { TopApp(id: $0.key, name: $0.value.name.isEmpty ? $0.key : $0.value.name, seconds: $0.value.seconds, platform: $0.value.best.platform) }
            .sorted { $0.seconds != $1.seconds ? $0.seconds > $1.seconds : $0.name < $1.name }

        return STSummary(title: title, today: today, average: average, trend: trend, todayCumulative: cumulative,
                         topApps: Array(top.prefix(6)), deviceCount: contributing)
    }
}

/// Durations the way Screen Time writes them: "9h 30m", "3h", "59m", "45s".
nonisolated enum STFormat {
    static func duration(_ seconds: Int) -> String {
        let s = max(0, seconds)
        if s >= 3600 {
            let h = s / 3600, m = (s % 3600) / 60
            return m == 0 ? "\(h)h" : "\(h)h \(m)m"
        }
        if s >= 60 { return "\(s / 60)m" }
        return s == 0 ? "0m" : "\(s)s"
    }

    /// "+1h 12m" / "−25m" against the average, or nil when it is too small to mention (under 5 minutes).
    static func delta(today: Int, average: Int?) -> String? {
        guard let average else { return nil }
        let d = today - average
        guard abs(d) >= 300 else { return nil }
        return (d > 0 ? "+" : "−") + duration(abs(d))
    }
}

/// App kinds, the same words on every platform. The device says which when it knows (Android's app category, a Mac app's
/// LSApplicationCategoryType); otherwise a short list of well-known apps fills in, and the rest is "other".
nonisolated enum STCategory {
    static let all = ["social", "entertainment", "games", "productivity", "creativity", "reading", "travel", "shopping",
                      "health", "utilities", "other"]

    static func title(_ c: String) -> String {
        switch c {
        case "social": "Social"
        case "entertainment": "Entertainment"
        case "games": "Games"
        case "productivity": "Productivity"
        case "creativity": "Creativity"
        case "reading": "Information & Reading"
        case "travel": "Travel & Maps"
        case "shopping": "Shopping & Food"
        case "health": "Health & Fitness"
        case "utilities": "Utilities"
        default: "Other"
        }
    }

    static func symbol(_ c: String) -> String {
        switch c {
        case "social": "bubble.left.and.bubble.right.fill"
        case "entertainment": "play.rectangle.fill"
        case "games": "gamecontroller.fill"
        case "productivity": "hammer.fill"
        case "creativity": "paintpalette.fill"
        case "reading": "book.fill"
        case "travel": "map.fill"
        case "shopping": "bag.fill"
        case "health": "heart.fill"
        case "utilities": "wrench.and.screwdriver.fill"
        default: "square.grid.2x2.fill"
        }
    }

    /// A word from a device, if it is one of ours.
    static func valid(_ c: String?) -> String? { c.flatMap { all.contains($0) ? $0 : nil } }

    /// A Mac app's LSApplicationCategoryType ("public.app-category.developer-tools").
    static func fromApple(_ type: String?) -> String? {
        guard let t = type?.lowercased().replacingOccurrences(of: "public.app-category.", with: "") else { return nil }
        if t.hasSuffix("-games") || t == "games" { return "games" }
        switch t {
        case "social-networking": return "social"
        case "entertainment", "music", "video": return "entertainment"
        case "productivity", "business", "developer-tools", "education", "finance": return "productivity"
        case "graphics-design", "photography": return "creativity"
        case "news", "reference", "weather", "books", "magazines-and-newspapers": return "reading"
        case "travel", "navigation": return "travel"
        case "shopping", "food-and-drink": return "shopping"
        case "healthcare-fitness", "medical", "sports", "lifestyle": return "health"
        case "utilities": return "utilities"
        default: return nil
        }
    }

    /// Well-known apps whose makers give no category, by id (Android package, Mac bundle id, Windows exe name).
    static func guess(_ id: String) -> String? {
        let s = id.lowercased()
        let table: [(String, [String])] = [
            ("social", ["instagram", "whatsapp", "facebook", "messenger", "snapchat", "twitter", "com.x.", "reddit", "telegram",
                        "discord", "linkedin", "threads", "signal", "tiktok", "musically", "pinterest", "mobilesms", "messages", "slack"]),
            ("entertainment", ["youtube", "netflix", "spotify", "primevideo", "hotstar", "jiocinema", "twitch", "music", "vlc", "iina",
                               "podcasts", "tv"]),
            ("productivity", ["xcode", "android.studio", "studio64", "com.google.android.studio", "jetbrains", "vscode", "code.exe",
                              "com.microsoft.vscode", "terminal", "iterm", "notion", "office", "word", "excel", "powerpoint", "docs",
                              "sheets", "mail", "gmail", "outlook", "calendar", "chatgpt", "openai", "claude", "anthropic", "zoom", "teams"]),
            ("creativity", ["photoshop", "figma", "lightroom", "finalcut", "premiere", "blender", "procreate", "canva", "photos", "camera"]),
            ("reading", ["chrome", "safari", "firefox", "edge", "brave", "arc", "news", "kindle", "books", "wikipedia", "quora"]),
            ("travel", ["maps", "uber", "ola", "rapido", "airbnb"]),
            ("shopping", ["amazon", "flipkart", "zepto", "blinkit", "swiggy", "zomato", "myntra", "meesho", "instamart"]),
            ("utilities", ["finder", "settings", "systempreferences", "files", "explorer.exe", "calculator", "clock", "airbridge"]),
        ]
        for (cat, keys) in table where keys.contains(where: { s.contains($0) }) { return cat }
        return nil
    }

    /// The category to show for an app.
    static func of(_ app: STApp) -> String { valid(app.cat) ?? guess(app.id) ?? "other" }
}
