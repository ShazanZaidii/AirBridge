import AppKit
import Charts
import Observation
import SwiftUI

// The Screen Time page: what a click on the AirBridge widget opens. Everything Apple's Screen Time and Android's Digital
// Wellbeing show that AirBridge's devices can measure: a day or a week (step back through both), all devices or only phones,
// Macs or the rest, the hours or days as bars split by device, pickups with the first one, notifications, devices, categories,
// and every app, sortable by screen time, times opened or notifications, each with its own week.

/// The window (one at a time).
@MainActor final class ScreenTimeWindow: NSObject, NSWindowDelegate {
    static let shared = ScreenTimeWindow()
    private var window: NSWindow?
    let model = ScreenTimePageModel()

    /// Shows the page, narrowed to `device` when the widget was set to one.
    func show(device: String? = nil) {
        model.open(device: device)
        if window == nil {
            let host = NSHostingController(rootView: ScreenTimePage(model: model))
            host.sizingOptions = [.minSize]
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1120, height: 880),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Screen Time"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentViewController = host
            w.setContentSize(NSSize(width: 1120, height: 880))
            w.minSize = NSSize(width: 820, height: 600)
            w.delegate = self
            w.setFrameAutosaveName("AirBridgeScreenTime")
            if w.frame.origin == .zero { w.center() }
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) { model.close() }
}

enum AppSort: String, CaseIterable { case time, opens, notifs }

@MainActor @Observable final class ScreenTimePageModel {
    var filter: STFilter = .all { didSet { if oldValue != filter { device = nil; recompute() } } }
    var device: String? { didSet { if oldValue != device { recompute() } } }
    var range: STRange = .day { didSet { if oldValue != range { recompute() } } }
    var anchor = Date() { didSet { recompute() } }
    var sort: AppSort = .time
    var expanded: String?
    var showAllApps = false
    var hoverBucket: Int?

    private(set) var report: STReport
    private(set) var snapshot = STSnapshot()
    private(set) var now = Date()
    private(set) var historyAvailable = true
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var iconCache: [String: NSImage] = [:]
    @ObservationIgnored let calendar = Calendar.current

    init() {
        report = STReport.make(STSnapshot(), filter: .all, range: .day, anchor: Date(), now: Date())
    }

    func open(device: String?) {
        reload()
        if let device, snapshot.devices.contains(where: { $0.id == device }) { filter = .all; self.device = device }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { _ in MainActor.assumeIsolated { ScreenTimeWindow.shared.model.reload() } }
    }

    func close() { timer?.invalidate(); timer = nil; iconCache = [:] }

    func reload() {
        let wasToday = calendar.isDate(anchor, inSameDayAs: now)
        snapshot = STStore.load()
        now = Date()
        historyAvailable = LocalScreenTime.shared.historyAvailable || !ScreenTimeHub.trackingEnabled
        if wasToday { anchor = now } else { recompute() }
    }

    private func recompute() {
        report = STReport.make(snapshot, filter: filter, device: device, range: range, anchor: anchor, now: now, calendar: calendar)
        hoverBucket = nil
    }

    func step(_ by: Int) {
        anchor = calendar.date(byAdding: range == .day ? .day : .weekOfYear, value: by, to: anchor) ?? anchor
    }

    func goToday() { anchor = now }

    /// A click on a day of the week chart opens that day.
    func openDay(_ date: Date) { range = .day; anchor = date }

    var isCurrent: Bool {
        range == .day ? calendar.isDate(anchor, inSameDayAs: now) : calendar.isDate(anchor, equalTo: now, toGranularity: .weekOfYear)
    }

    var deviceName: String? { device.flatMap { id in snapshot.devices.first { $0.id == id }?.name } }

    /// Whether the selection includes this Mac, whose past AirBridge can fill in with Full Disk Access.
    var showsThisMac: Bool { device.map { $0 == Identity.id } ?? filter.includes("macos") }

    func icon(_ id: String) -> NSImage? {
        if let i = iconCache[id] { return i }
        guard let folder = ScreenTimeShared.folder(), let img = NSImage(contentsOf: ScreenTimeShared.iconURL(appId: id, in: folder)) else { return nil }
        iconCache[id] = img
        return img
    }

    func recheckAccess() {
        LocalScreenTime.shared.refreshHistory(force: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.reload() }
    }
}

// MARK: - Design language
//
// Graphite base with two soft ambient glows; cards are smoked glass with a fine top highlight; every device group has its
// own gradient (Mac: AirBridge blue, phones: coral to pink, the rest: violet), used the same way everywhere (bars, donut,
// devices). Numbers in SF Rounded; labels small, spaced capitals. Motion: bars grow when the view changes, numbers roll,
// cards lift a touch under the pointer.

private enum Look {
    static let macA = Color(red: 0.36, green: 0.62, blue: 1.00), macB = Color(red: 0.55, green: 0.76, blue: 1.00)
    static let mobA = Color(red: 1.00, green: 0.42, blue: 0.47), mobB = Color(red: 1.00, green: 0.62, blue: 0.40)
    static let othA = Color(red: 0.62, green: 0.48, blue: 1.00), othB = Color(red: 0.78, green: 0.66, blue: 1.00)
    static let warm = Color(nsColor: NSColor(name: nil) { a in
        a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 1.0, green: 0.71, blue: 0.28, alpha: 1)
            : NSColor(srgbRed: 0.80, green: 0.45, blue: 0.0, alpha: 1)
    })
    static let cool = Color(nsColor: NSColor(name: nil) { a in
        a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 0.35, green: 0.80, blue: 0.98, alpha: 1)
            : NSColor(srgbRed: 0.0, green: 0.48, blue: 0.75, alpha: 1)
    })
    static let radius: CGFloat = 22

    static func color(_ g: STFilter) -> Color {
        switch g {
        case .mobile: mobA
        case .mac: macA
        default: othA
        }
    }

    static func gradient(_ g: STFilter) -> LinearGradient {
        let (a, b): (Color, Color) = g == .mobile ? (mobA, mobB) : (g == .mac ? (macA, macB) : (othA, othB))
        return LinearGradient(colors: [a, b], startPoint: .bottom, endPoint: .top)
    }

    /// Category colours: distinct, warm and cool, no greens.
    static func color(category c: String) -> Color {
        switch c {
        case "social": Color(red: 1.0, green: 0.42, blue: 0.62)
        case "entertainment": Color(red: 0.68, green: 0.52, blue: 1.0)
        case "games": Color(red: 1.0, green: 0.50, blue: 0.36)
        case "productivity": macA
        case "creativity": Color(red: 1.0, green: 0.80, blue: 0.32)
        case "reading": Color(red: 0.35, green: 0.80, blue: 0.98)
        case "travel": Color(red: 0.98, green: 0.60, blue: 0.86)
        case "shopping": Color(red: 1.0, green: 0.64, blue: 0.30)
        case "health": Color(red: 1.0, green: 0.36, blue: 0.42)
        case "utilities": Color(red: 0.62, green: 0.66, blue: 0.74)
        default: Color(red: 0.50, green: 0.52, blue: 0.58)
        }
    }

    static func symbol(platform: String) -> String {
        switch platform {
        case "android", "ios": "iphone"
        case "macos": "laptopcomputer"
        case "windows": "pc"
        case "linux": "terminal.fill"
        default: "desktopcomputer"
        }
    }

    static func platformName(_ p: String) -> String {
        switch p { case "android": "Android"; case "ios": "iPhone"; case "macos": "Mac"; case "windows": "Windows"; case "linux": "Linux"; default: p }
    }

    /// An app whose name never arrived shows its id's last part, tidied ("com.foo.stprobe" → "Stprobe").
    static func name(_ app: STReport.AppRow) -> String {
        guard app.name == app.id, app.id.contains("."), let last = app.id.split(separator: ".").last else { return app.name }
        return last.prefix(1).uppercased() + last.dropFirst()
    }
}

/// True while the debug snapshot draws the page (no motion, everything at rest).
private struct SnapshotKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var stSnapshot: Bool {
        get { self[SnapshotKey.self] }
        set { self[SnapshotKey.self] = newValue }
    }
}

/// Small spaced capitals above a number or a section.
private struct Eyebrow: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.system(size: 10.5, weight: .semibold)).tracking(0.9).foregroundStyle(.secondary)
    }
}

/// Smoked glass card with a fine highlight along the top edge.
private struct Glass<Content: View>: View {
    var padding: CGFloat = 22
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var scheme
    @State private var hover = false

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: Look.radius, style: .continuous)
                    .fill(scheme == .dark ? Color.white.opacity(hover ? 0.065 : 0.05) : Color.white.opacity(0.78))
            }
            .overlay {
                RoundedRectangle(cornerRadius: Look.radius, style: .continuous)
                    .strokeBorder(LinearGradient(colors: [Color.white.opacity(scheme == .dark ? 0.14 : 0.9), Color.white.opacity(scheme == .dark ? 0.03 : 0.4)],
                                                 startPoint: .top, endPoint: .bottom), lineWidth: 1)
            }
            .shadow(color: .black.opacity(scheme == .dark ? 0.28 : 0.07), radius: hover ? 22 : 16, y: hover ? 10 : 7)
            .onHover { h in withAnimation(.easeOut(duration: 0.18)) { hover = h } }
    }
}

/// Capsule choices: the picked one is a white pill that slides between them.
private struct Pills<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let title: (T) -> String
    var small = false
    @Namespace private var ns
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 2) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button { withAnimation(.spring(response: 0.34, dampingFraction: 0.78)) { selection = item } } label: {
                    Text(title(item))
                        .font(.system(size: small ? 11.5 : 13, weight: on ? .semibold : .medium))
                        .padding(.horizontal, small ? 11 : 15)
                        .padding(.vertical, small ? 5 : 7)
                        .foregroundStyle(on ? Color.black : Color.primary.opacity(0.7))
                        .background {
                            if on {
                                Capsule().fill(Color.white)
                                    .shadow(color: .black.opacity(0.22), radius: 3, y: 1)
                                    .matchedGeometryEffect(id: "pill", in: ns)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(Capsule().fill(Color.primary.opacity(scheme == .dark ? 0.08 : 0.06)))
        .overlay(Capsule().strokeBorder(Color.primary.opacity(0.06)))
    }
}

/// Soft colour glows behind the page.
private struct Ambient: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let k: Double = scheme == .dark ? 1 : 0.55
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            RadialGradient(colors: [Look.macA.opacity(0.20 * k), .clear], center: UnitPoint(x: 0.12, y: 0.0), startRadius: 0, endRadius: 620)
            RadialGradient(colors: [Look.mobA.opacity(0.13 * k), .clear], center: UnitPoint(x: 0.92, y: 0.06), startRadius: 0, endRadius: 560)
            RadialGradient(colors: [Look.othA.opacity(0.08 * k), .clear], center: UnitPoint(x: 0.5, y: 0.75), startRadius: 0, endRadius: 700)
        }
        .ignoresSafeArea()
    }
}

// MARK: - Page

struct ScreenTimePage: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        ScrollView {
            ScreenTimeContent(model: model)
        }
        .scrollIndicators(.automatic)
        .background(Ambient())
        .frame(minWidth: 820, minHeight: 600)
    }
}

/// The page without its scroll view (also what the debug snapshot renders).
struct ScreenTimeContent: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        let r = model.report
        VStack(alignment: .leading, spacing: 20) {
            Masthead(model: model)
            if model.snapshot.devices.isEmpty {
                EmptyPage()
            } else {
                if model.showsThisMac && !model.historyAvailable { AccessCard(model: model) }
                Hero(model: model, r: r)
                UsageChart(model: model, r: r)
                Insights(model: model, r: r)
                if !r.apps.filter({ $0.seconds > 0 }).isEmpty { Podium(model: model, r: r) }
                HStack(alignment: .top, spacing: 20) {
                    CategoriesCard(r: r)
                    DevicesCard(model: model, r: r)
                }
                AppsCard(model: model, r: r)
                Text("Counted on your own devices. Nothing leaves them.")
                    .font(.caption).foregroundStyle(.tertiary).frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 36)
        .padding(.top, 44)
        .padding(.bottom, 32)
        .frame(maxWidth: 1120)
        .frame(maxWidth: .infinity)
    }
}

// MARK: Masthead

private struct Masthead: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Screen Time").font(.system(size: 34, weight: .bold, design: .rounded))
                    Text(subtitle).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                }
                Spacer()
                DateStepper(model: model)
            }
            HStack(spacing: 12) {
                Pills(items: STFilter.allCases, selection: $model.filter, title: \.title)
                if let name = model.deviceName {
                    Button { withAnimation(.snappy) { model.device = nil } } label: {
                        HStack(spacing: 6) {
                            Text(name).font(.system(size: 12.5, weight: .semibold))
                            Image(systemName: "xmark").font(.system(size: 9, weight: .bold))
                        }
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(Capsule().fill(Color.primary.opacity(0.1)))
                    }
                    .buttonStyle(.plain)
                    .help("Show every device again")
                    .transition(.scale.combined(with: .opacity))
                }
                Spacer()
                Pills(items: STRange.allCases, selection: $model.range, title: { $0 == .day ? "Day" : "Week" })
            }
        }
    }

    private var subtitle: String {
        let r = model.report
        if r.range == .day { return r.start.formatted(.dateTime.weekday(.wide).day().month(.wide)) }
        let end = r.end.addingTimeInterval(-1)
        return "\(r.start.formatted(.dateTime.day().month(.wide))) – \(end.formatted(.dateTime.day().month(.wide)))"
    }
}

private struct DateStepper: View {
    @Bindable var model: ScreenTimePageModel
    var body: some View {
        let r = model.report
        HStack(spacing: 2) {
            StepButton(symbol: "chevron.left", enabled: r.hasEarlier) { model.step(-1) }
            Button { model.goToday() } label: {
                Text(label(r)).font(.system(size: 13, weight: .semibold)).monospacedDigit().frame(minWidth: 96)
            }
            .buttonStyle(.plain)
            .help(model.isCurrent ? "" : "Back to today")
            StepButton(symbol: "chevron.right", enabled: r.hasLater) { model.step(1) }
        }
        .padding(3)
        .background(Capsule().fill(Color.primary.opacity(0.07)))
        .overlay(Capsule().strokeBorder(Color.primary.opacity(0.06)))
    }

    private func label(_ r: STReport) -> String {
        let cal = model.calendar
        if r.range == .day {
            if cal.isDateInToday(r.start) { return "Today" }
            if cal.isDateInYesterday(r.start) { return "Yesterday" }
            return r.start.formatted(.dateTime.weekday(.abbreviated).day())
        }
        if cal.isDate(r.start, equalTo: model.now, toGranularity: .weekOfYear) { return "This week" }
        if let last = cal.date(byAdding: .weekOfYear, value: -1, to: model.now), cal.isDate(r.start, equalTo: last, toGranularity: .weekOfYear) { return "Last week" }
        return "Week of \(r.start.formatted(.dateTime.day().month(.abbreviated)))"
    }
}

private struct StepButton: View {
    let symbol: String
    let enabled: Bool
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 11, weight: .bold))
                .frame(width: 28, height: 28)
                .background(Circle().fill(Color.primary.opacity(hover && enabled ? 0.12 : 0)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.28)
        .onHover { hover = $0 }
    }
}

// MARK: Hero

private struct Hero: View {
    let model: ScreenTimePageModel
    let r: STReport

    private var groups: [(group: STFilter, seconds: Int)] {
        var by: [STFilter: Int] = [:]
        for d in r.devices { by[STFilter.group(d.platform), default: 0] += d.seconds }
        return [STFilter.mac, .mobile, .other].compactMap { g in by[g].map { (g, $0) } }.filter { $0.seconds > 0 }
    }

    var body: some View {
        Glass(padding: 28) {
            HStack(alignment: .center, spacing: 28) {
                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow(text: r.range == .day ? caption : "Daily average · \(caption.lowercased())")
                    Text(STFormat.duration(r.range == .day ? r.total : (r.dailyAverage ?? 0)))
                        .font(.system(size: 76, weight: .bold, design: .rounded))
                        .tracking(-1.5)
                        .contentTransition(.numericText())
                        .animation(.snappy, value: r.total)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    HStack(spacing: 10) {
                        if let mood { MoodPill(mood: mood) }
                        if r.range == .week { Text("\(STFormat.duration(r.total)) this week").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary) }
                    }
                    if let line = insight {
                        Text(line).font(.system(size: 13)).foregroundStyle(.secondary).padding(.top, 2)
                    }
                }
                Spacer(minLength: 12)
                if !groups.isEmpty { SplitDonut(groups: groups, total: max(1, r.total), devices: r.devices.count) }
            }
        }
    }

    private var caption: String {
        if let n = model.deviceName { return "on \(n)" }
        switch model.filter {
        case .all: return "All your devices"
        case .mobile: return "Your phones"
        case .mac: return "Your Macs"
        case .other: return "Your other devices"
        }
    }

    private var mood: (title: String, up: Bool?)? {
        guard let change = r.change, let pct = r.changePercent else { return nil }
        if abs(pct) < 10 || abs(change) < 300 { return (r.range == .day ? "Right on your usual" : "Same as last week", nil) }
        let what = r.range == .day ? "your usual" : "last week"
        return change > 0 ? ("\(STFormat.duration(change)) over \(what)", true) : ("\(STFormat.duration(-change)) under \(what)", false)
    }

    /// One friendly line: the busiest hour (or day) and the app that led.
    private var insight: String? {
        var by: [Int: Int] = [:]
        for b in r.bars { by[b.bucket, default: 0] += b.seconds }
        let top = r.apps.first(where: { $0.seconds > 0 })
        var parts: [String] = []
        if let peak = by.max(by: { $0.value < $1.value }), peak.value > 0 {
            if r.range == .day { parts.append("Busiest around \(hourText(peak.key))") }
            else if peak.key < r.weekDays.count { parts.append("Busiest on \(r.weekDays[peak.key].formatted(.dateTime.weekday(.wide)))") }
        }
        if let top { parts.append("\(Look.name(top)) led with \(STFormat.duration(top.seconds))") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func hourText(_ h: Int) -> String {
        let d = model.calendar.date(bySettingHour: h, minute: 0, second: 0, of: r.start) ?? r.start
        return d.formatted(.dateTime.hour().minute())
    }
}

private struct MoodPill: View {
    let mood: (title: String, up: Bool?)
    var body: some View {
        let tint: Color = mood.up == nil ? Color.primary.opacity(0.7) : (mood.up! ? Look.warm : Look.cool)
        HStack(spacing: 6) {
            Image(systemName: mood.up == nil ? "equal" : (mood.up! ? "arrow.up.right" : "arrow.down.right"))
                .font(.system(size: 10.5, weight: .heavy))
            Text(mood.title).font(.system(size: 12.5, weight: .semibold))
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 11).padding(.vertical, 6)
        .background(Capsule().fill(tint.opacity(0.14)))
        .overlay(Capsule().strokeBorder(tint.opacity(0.25)))
    }
}

/// The share of each device group, as a ring with the legend beside it.
private struct SplitDonut: View {
    let groups: [(group: STFilter, seconds: Int)]
    let total: Int
    let devices: Int

    var body: some View {
        HStack(spacing: 22) {
            Chart(groups, id: \.group) { g in
                SectorMark(angle: .value("Time", g.seconds), innerRadius: .ratio(0.72), angularInset: 2.5)
                    .cornerRadius(5)
                    .foregroundStyle(Look.gradient(g.group))
            }
            .chartLegend(.hidden)
            .frame(width: 128, height: 128)
            .overlay {
                VStack(spacing: 0) {
                    Text("\(devices)").font(.system(size: 26, weight: .bold, design: .rounded))
                    Text(devices == 1 ? "device" : "devices").font(.system(size: 10.5, weight: .medium)).foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 12) {
                ForEach(groups, id: \.group) { g in
                    HStack(spacing: 10) {
                        Circle().fill(Look.gradient(g.group)).frame(width: 10, height: 10)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(g.group.title).font(.system(size: 13, weight: .semibold))
                            Text("\(STFormat.duration(g.seconds)) · \(Int((Double(g.seconds) / Double(total) * 100).rounded()))%")
                                .font(.system(size: 12)).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                }
            }
            .frame(minWidth: 130, alignment: .leading)
        }
    }
}

// MARK: Chart

private struct UsageChart: View {
    @Bindable var model: ScreenTimePageModel
    let r: STReport
    @State private var grow: Double = 1
    @Environment(\.stSnapshot) private var snapshot

    private var day: Bool { r.range == .day }
    private var buckets: Int { day ? 24 : 7 }
    private var groups: [STFilter] { [.mac, .mobile, .other].filter { g in r.bars.contains { $0.group == g } } }
    /// Day: minutes per hour. Week: hours per day.
    private func value(_ s: Int) -> Double { day ? Double(s) / 60 : Double(s) / 3600 }
    private func bucketTotal(_ b: Int) -> Int { r.bars.filter { $0.bucket == b }.map(\.seconds).reduce(0, +) }
    private var top: Double {
        let m = value((0..<buckets).map(bucketTotal).max() ?? 0)
        return day ? max(60, (m / 15).rounded(.up) * 15) : max(2, m.rounded(.up))
    }

    var body: some View {
        Glass {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(day ? "Through the day" : "Day by day").font(.system(size: 17, weight: .semibold))
                        Text(day ? "Minutes in each hour" : "Click a day to open it").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    legend
                }
                if r.bars.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "moon.zzz").font(.system(size: 26, weight: .light)).foregroundStyle(.secondary)
                        Text(day && r.total > 0 ? "These devices don't send hour-by-hour numbers yet." : "A quiet \(day ? "day" : "week"). Nothing used.")
                            .font(.system(size: 13)).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 220)
                } else {
                    chart.frame(height: 240)
                    if let note = missingHoursNote {
                        Label(note, systemImage: "info.circle").font(.system(size: 11.5)).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .onChange(of: "\(r.start.timeIntervalSince1970)|\(r.range.rawValue)|\(model.filter.rawValue)|\(model.device ?? "")") { _, _ in
            guard !snapshot else { return }
            grow = 0
            withAnimation(.spring(response: 0.7, dampingFraction: 0.82)) { grow = 1 }
        }
    }

    /// Day view: devices that sent a total but no hours for this day (an older AirBridge), so their time is not in the bars.
    private var missingHoursNote: String? {
        guard day else { return nil }
        let key = STSummary.dayKey(r.start, calendar: model.calendar)
        let ids = Set(r.devices.map(\.id))
        let names = model.snapshot.devices.filter { d in
            ids.contains(d.id) && d.days.contains { $0.day == key && $0.seconds > 0 && ($0.hours?.count ?? 0) != 24 }
        }.map(\.name)
        guard !names.isEmpty else { return nil }
        return "\(names.joined(separator: ", ")) didn't send hour-by-hour numbers for this day, so its time isn't in the bars. Updating AirBridge on it fixes that."
    }

    private struct Seg: Hashable { let bucket: Int; let group: STFilter; let lo: Double; let hi: Double; let seconds: Int }

    /// Stacked by hand (Mac at the bottom, then phones, then the rest), as separate rounded pieces with a hairline gap.
    private var segments: [Seg] {
        var height: [Int: Double] = [:]
        let gap = top * 0.012
        var out: [Seg] = []
        for g in [STFilter.mac, .mobile, .other] {
            for b in r.bars where b.group == g {
                let lo = height[b.bucket].map { $0 + gap } ?? 0
                let hi = lo + value(b.seconds) * grow
                height[b.bucket] = hi
                out.append(Seg(bucket: b.bucket, group: g, lo: lo, hi: hi, seconds: b.seconds))
            }
        }
        return out
    }

    private func pieceMark(_ s: Seg) -> some ChartContent {
        let half: Double = day ? 0.34 : 0.3
        let x0: Double = Double(s.bucket) - half
        let x1: Double = Double(s.bucket) + half
        let dim: Bool = model.hoverBucket != nil && model.hoverBucket != s.bucket
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", s.lo), yEnd: .value("Top", s.hi))
            .foregroundStyle(Look.gradient(s.group))
            .cornerRadius(day ? 4 : 7)
            .opacity(dim ? 0.35 : 1)
    }

    private func trackMark(_ b: Int) -> some ChartContent {
        let half: Double = day ? 0.34 : 0.3
        let x0: Double = Double(b) - half
        let x1: Double = Double(b) + half
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", 0.0), yEnd: .value("Top", top))
            .foregroundStyle(Color.primary.opacity(0.035))
            .cornerRadius(day ? 4 : 7)
    }

    private var chart: some View {
        Chart {
            ForEach(0..<buckets, id: \.self) { b in trackMark(b) }
            ForEach(segments, id: \.self) { s in pieceMark(s) }
            if !day, let avg = r.dailyAverage, avg > 0 {
                RuleMark(y: .value("Average", value(avg)))
                    .foregroundStyle(Color.primary.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 4]))
                    .annotation(position: .top, alignment: .trailing) {
                        Text("Average \(STFormat.duration(avg))").font(.system(size: 10.5, weight: .semibold)).foregroundStyle(.secondary)
                    }
            }
            if let h = model.hoverBucket, bucketTotal(h) > 0 {
                RuleMark(x: .value("When", Double(h)))
                    .foregroundStyle(Color.clear)
                    .annotation(position: .top, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) { tooltip(h) }
            }
        }
        .chartXScale(domain: -0.6...(Double(buckets) - 0.4))
        .chartYScale(domain: 0...top)
        .chartXAxis {
            AxisMarks(values: day ? [0, 3, 6, 9, 12, 15, 18, 21] : Array(0..<7)) { v in
                AxisValueLabel { if let i = v.as(Int.self) { Text(xLabel(i)).font(.system(size: 11, weight: .medium)).foregroundStyle(.secondary) } }
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [2, 3])).foregroundStyle(Color.primary.opacity(0.12))
                AxisValueLabel { if let d = v.as(Double.self) { Text(day ? "\(Int(d))m" : axisHours(d)).font(.system(size: 10.5)).foregroundStyle(.tertiary) } }
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geo in
                Rectangle().fill(Color.clear).contentShape(Rectangle())
                    .onContinuousHover { phase in
                        switch phase {
                        case .active(let p): model.hoverBucket = bucket(at: p, proxy: proxy, geo: geo)
                        case .ended: model.hoverBucket = nil
                        }
                    }
                    .onTapGesture { p in
                        guard !day, let b = bucket(at: p, proxy: proxy, geo: geo), b < r.weekDays.count else { return }
                        model.openDay(r.weekDays[b])
                    }
            }
        }
    }

    private func bucket(at p: CGPoint, proxy: ChartProxy, geo: GeometryProxy) -> Int? {
        guard let frame = proxy.plotFrame else { return nil }
        guard let v: Double = proxy.value(atX: p.x - geo[frame].origin.x) else { return nil }
        let b = Int(v.rounded())
        return (0..<buckets).contains(b) ? b : nil
    }

    private func xLabel(_ i: Int) -> String {
        if day {
            let d = model.calendar.date(bySettingHour: i, minute: 0, second: 0, of: r.start) ?? r.start
            return d.formatted(.dateTime.hour())
        }
        guard i < r.weekDays.count else { return "" }
        return r.weekDays[i].formatted(.dateTime.weekday(.abbreviated))
    }

    private func axisHours(_ h: Double) -> String { h == 0 ? "0" : (h == h.rounded() ? "\(Int(h))h" : String(format: "%.1fh", h)) }

    private func tooltip(_ b: Int) -> some View {
        let parts = r.bars.filter { $0.bucket == b }
        let title = day ? "\(xLabel(b)) – \(xLabel((b + 1) % 24))"
            : (b < r.weekDays.count ? r.weekDays[b].formatted(.dateTime.weekday(.wide).day().month(.abbreviated)) : "")
        return VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary)
            Text(STFormat.duration(bucketTotal(b))).font(.system(size: 20, weight: .bold, design: .rounded)).monospacedDigit()
            ForEach(parts, id: \.self) { p in
                HStack(spacing: 7) {
                    Circle().fill(Look.gradient(p.group)).frame(width: 8, height: 8)
                    Text(p.group.title).font(.system(size: 12))
                    Spacer(minLength: 14)
                    Text(STFormat.duration(p.seconds)).font(.system(size: 12, weight: .medium)).monospacedDigit()
                }
            }
        }
        .padding(12)
        .frame(minWidth: 160)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(.regularMaterial))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.12)))
        .shadow(color: .black.opacity(0.3), radius: 12, y: 5)
    }

    private var legend: some View {
        HStack(spacing: 16) {
            ForEach(groups, id: \.self) { g in
                HStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 3).fill(Look.gradient(g)).frame(width: 12, height: 12)
                    Text(g.title).font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
                }
            }
        }
    }
}

// MARK: Insights

private struct Insights: View {
    let model: ScreenTimePageModel
    let r: STReport

    private var elapsedDays: Int { max(1, r.weekDays.filter { $0 <= model.now }.count) }

    var body: some View {
        HStack(spacing: 16) {
            InsightTile(symbol: "hand.tap.fill", tint: Look.mobA, title: "Pickups",
                        value: r.unlocks.map { r.range == .day ? "\($0)" : "\($0 / elapsedDays)" } ?? "—",
                        detail: pickupsDetail)
            InsightTile(symbol: "bell.fill", tint: Look.othA, title: "Notifications",
                        value: r.notifs.map { r.range == .day ? "\($0)" : "\($0 / elapsedDays)" } ?? "—",
                        detail: r.notifs == nil ? "From Android phones" : (r.range == .day ? topNotifier : "a day, on average"))
            InsightTile(symbol: r.range == .day ? "sun.max.fill" : "calendar", tint: Look.warm, title: r.range == .day ? "Peak hour" : "Peak day",
                        value: busiest.value, detail: busiest.detail)
            InsightTile(symbol: "square.grid.2x2.fill", tint: Look.macA, title: "Apps used",
                        value: "\(r.apps.filter { $0.seconds >= 60 }.count)",
                        detail: "\(r.categories.count) \(r.categories.count == 1 ? "category" : "categories")")
        }
    }

    private var pickupsDetail: String {
        guard r.unlocks != nil else { return "From phones and Macs" }
        if r.range == .week { return "a day, on average" }
        if let f = r.firstUse { return "First at \(STText.clock(f, on: r.start, calendar: model.calendar))" }
        return "Unlocks"
    }

    private var topNotifier: String {
        guard let a = r.apps.filter({ ($0.notifs ?? 0) > 0 }).max(by: { ($0.notifs ?? 0) < ($1.notifs ?? 0) }) else { return "None" }
        return "Most from \(Look.name(a))"
    }

    private var busiest: (value: String, detail: String) {
        var by: [Int: Int] = [:]
        for b in r.bars { by[b.bucket, default: 0] += b.seconds }
        guard let top = by.max(by: { $0.value < $1.value }), top.value > 0 else { return ("—", "Nothing yet") }
        if r.range == .day {
            let d = model.calendar.date(bySettingHour: top.key, minute: 0, second: 0, of: r.start) ?? r.start
            return (d.formatted(.dateTime.hour().minute()), "\(STFormat.duration(top.value)) in that hour")
        }
        let name = top.key < r.weekDays.count ? r.weekDays[top.key].formatted(.dateTime.weekday(.wide)) : "—"
        return (name, STFormat.duration(top.value))
    }
}

private struct InsightTile: View {
    let symbol: String
    let tint: Color
    let title: String
    let value: String
    let detail: String

    var body: some View {
        Glass(padding: 18) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Image(systemName: symbol)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(LinearGradient(colors: [tint, tint.opacity(0.7)], startPoint: .top, endPoint: .bottom)))
                        .shadow(color: tint.opacity(0.45), radius: 6, y: 2)
                    Eyebrow(text: title)
                }
                Text(value).font(.system(size: 30, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
                    .contentTransition(.numericText())
                Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }
}

// MARK: Podium

/// The three apps that took the most time, as cards.
private struct Podium: View {
    let model: ScreenTimePageModel
    let r: STReport

    var body: some View {
        let top = Array(r.apps.filter { $0.seconds > 0 }.prefix(3))
        VStack(alignment: .leading, spacing: 12) {
            Text("Your top apps").font(.system(size: 17, weight: .semibold)).padding(.leading, 4)
            HStack(spacing: 16) {
                ForEach(Array(top.enumerated()), id: \.element.id) { i, app in
                    PodiumCard(model: model, app: app, rank: i + 1, share: r.total > 0 ? Double(app.seconds) / Double(r.total) : 0)
                }
                ForEach(0..<(3 - top.count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
            }
        }
    }
}

private struct PodiumCard: View {
    let model: ScreenTimePageModel
    let app: STReport.AppRow
    let rank: Int
    let share: Double

    var body: some View {
        let tint = Look.color(category: app.category)
        Glass(padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    STAppIcon(image: model.icon(app.id), name: Look.name(app), size: 52)
                        .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
                    Spacer()
                    Text("#\(rank)").font(.system(size: 13, weight: .heavy, design: .rounded)).foregroundStyle(rank == 1 ? Look.warm : .secondary)
                        .padding(.horizontal, 9).padding(.vertical, 4)
                        .background(Capsule().fill((rank == 1 ? Look.warm : Color.primary).opacity(0.12)))
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(Look.name(app)).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    HStack(spacing: 5) {
                        Text(STCategory.title(app.category)).font(.system(size: 11.5)).foregroundStyle(.secondary).lineLimit(1)
                        ForEach(app.platforms, id: \.self) { p in
                            Image(systemName: Look.symbol(platform: p)).font(.system(size: 9.5)).foregroundStyle(.tertiary)
                        }
                    }
                }
                Text(STFormat.duration(app.seconds)).font(.system(size: 28, weight: .bold, design: .rounded)).monospacedDigit()
                VStack(alignment: .leading, spacing: 6) {
                    GlowBar(fraction: share, tint: tint)
                    Text("\(Int((share * 100).rounded()))% of your time").font(.system(size: 11.5)).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A rounded bar with a soft glow in its colour.
private struct GlowBar: View {
    let fraction: Double
    let tint: Color
    var height: CGFloat = 6
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.08))
                Capsule()
                    .fill(LinearGradient(colors: [tint.opacity(0.75), tint], startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(height, g.size.width * min(1, max(0, fraction))))
                    .shadow(color: tint.opacity(0.5), radius: 4)
            }
        }
        .frame(height: height)
    }
}

// MARK: Categories and devices

private struct CategoriesCard: View {
    let r: STReport
    var body: some View {
        let cats = Array(r.categories.prefix(6))
        let total = max(1, r.categories.map(\.seconds).reduce(0, +))
        Glass {
            VStack(alignment: .leading, spacing: 16) {
                Text("Categories").font(.system(size: 17, weight: .semibold))
                if cats.isEmpty {
                    Text("Nothing used yet.").font(.system(size: 13)).foregroundStyle(.secondary)
                } else {
                    HStack(alignment: .center, spacing: 20) {
                        Chart(cats, id: \.category) { c in
                            SectorMark(angle: .value("Time", c.seconds), innerRadius: .ratio(0.66), angularInset: 2)
                                .cornerRadius(4)
                                .foregroundStyle(Look.color(category: c.category))
                        }
                        .chartLegend(.hidden)
                        .frame(width: 112, height: 112)
                        .overlay {
                            if let first = cats.first {
                                VStack(spacing: 0) {
                                    Image(systemName: STCategory.symbol(first.category)).font(.system(size: 18)).foregroundStyle(Look.color(category: first.category))
                                    Text("\(Int((Double(first.seconds) / Double(total) * 100).rounded()))%").font(.system(size: 15, weight: .bold, design: .rounded))
                                }
                            }
                        }
                        VStack(alignment: .leading, spacing: 9) {
                            ForEach(cats, id: \.category) { c in
                                HStack(spacing: 9) {
                                    RoundedRectangle(cornerRadius: 3).fill(Look.color(category: c.category)).frame(width: 10, height: 10)
                                    Text(STCategory.title(c.category)).font(.system(size: 13)).lineLimit(1)
                                    Spacer(minLength: 8)
                                    Text(STFormat.duration(c.seconds)).font(.system(size: 13, weight: .medium)).monospacedDigit()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct DevicesCard: View {
    @Bindable var model: ScreenTimePageModel
    let r: STReport
    var body: some View {
        Glass {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Devices").font(.system(size: 17, weight: .semibold))
                    Spacer()
                    Text("Click one to focus").font(.system(size: 11.5)).foregroundStyle(.tertiary)
                }
                if r.devices.isEmpty { Text("No device has time here.").font(.system(size: 13)).foregroundStyle(.secondary) }
                ForEach(r.devices) { d in DeviceLine(model: model, d: d, total: r.total) }
            }
        }
    }
}

private struct DeviceLine: View {
    @Bindable var model: ScreenTimePageModel
    let d: STReport.DeviceRow
    let total: Int
    @State private var hover = false

    var body: some View {
        let g = STFilter.group(d.platform)
        let on = model.device == d.id
        Button { withAnimation(.snappy) { model.device = on ? nil : d.id } } label: {
            HStack(spacing: 14) {
                Image(systemName: Look.symbol(platform: d.platform))
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Look.gradient(g)))
                    .shadow(color: Look.color(g).opacity(0.4), radius: 6, y: 2)
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(d.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                        Spacer()
                        Text(STFormat.duration(d.seconds)).font(.system(size: 13.5, weight: .semibold, design: .rounded)).monospacedDigit()
                    }
                    GlowBar(fraction: total > 0 ? Double(d.seconds) / Double(total) : 0, tint: Look.color(g), height: 5)
                    Text("\(Look.platformName(d.platform)) · updated \(STText.ago(d.updated, now: model.now))")
                        .font(.system(size: 11)).foregroundStyle(.tertiary)
                }
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.primary.opacity(on ? 0.1 : (hover ? 0.05 : 0))))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Look.color(g).opacity(on ? 0.5 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .padding(.horizontal, -10)
    }
}

// MARK: All apps

private struct AppsCard: View {
    @Bindable var model: ScreenTimePageModel
    let r: STReport

    private var sorts: [AppSort] {
        var out: [AppSort] = [.time]
        if r.apps.contains(where: { $0.opens != nil }) { out.append(.opens) }
        if r.apps.contains(where: { $0.notifs != nil }) { out.append(.notifs) }
        return out
    }

    private func list(_ sort: AppSort) -> [STReport.AppRow] {
        switch sort {
        case .time: return r.apps.filter { $0.seconds > 0 }
        case .opens: return r.apps.filter { ($0.opens ?? 0) > 0 }.sorted { ($0.opens ?? 0) > ($1.opens ?? 0) }
        case .notifs: return r.apps.filter { ($0.notifs ?? 0) > 0 }.sorted { ($0.notifs ?? 0) > ($1.notifs ?? 0) }
        }
    }

    private func metric(_ a: STReport.AppRow, _ s: AppSort) -> Int {
        switch s { case .time: a.seconds; case .opens: a.opens ?? 0; case .notifs: a.notifs ?? 0 }
    }

    var body: some View {
        let sort = sorts.contains(model.sort) ? model.sort : .time
        let all = list(sort)
        let shown = model.showAllApps ? all : Array(all.prefix(10))
        let maxValue = max(1, all.map { metric($0, sort) }.max() ?? 1)
        Glass {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("All apps").font(.system(size: 17, weight: .semibold))
                        Text("\(all.count) \(all.count == 1 ? "app" : "apps") · click one for its week").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if sorts.count > 1 {
                        Pills(items: sorts, selection: $model.sort, title: { s in
                            switch s { case .time: "Screen time"; case .opens: "Opens"; case .notifs: "Notifications" }
                        }, small: true)
                    }
                }
                if all.isEmpty {
                    Text(sort == .time ? "No apps used in this \(r.range == .day ? "day" : "week")." : "Nothing to count here.")
                        .font(.system(size: 13)).foregroundStyle(.secondary).padding(.vertical, 8)
                }
                VStack(spacing: 2) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, app in
                        AppLine(model: model, app: app, rank: i + 1, sort: sort, fraction: Double(metric(app, sort)) / Double(maxValue))
                    }
                }
                if all.count > 10 {
                    Button { withAnimation(.snappy) { model.showAllApps.toggle() } } label: {
                        HStack(spacing: 5) {
                            Text(model.showAllApps ? "Show fewer" : "Show all \(all.count)")
                            Image(systemName: model.showAllApps ? "chevron.up" : "chevron.down").font(.system(size: 9, weight: .bold))
                        }
                        .font(.system(size: 12.5, weight: .semibold))
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(Capsule().fill(Color.primary.opacity(0.08)))
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

private struct AppLine: View {
    @Bindable var model: ScreenTimePageModel
    let app: STReport.AppRow
    let rank: Int
    let sort: AppSort
    let fraction: Double
    @State private var hover = false

    private var open: Bool { model.expanded == app.id }

    var body: some View {
        let tint = Look.color(category: app.category)
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { model.expanded = open ? nil : app.id } } label: {
                HStack(spacing: 14) {
                    Text("\(rank)").font(.system(size: 12, weight: .bold, design: .rounded)).foregroundStyle(.tertiary).frame(width: 18)
                    STAppIcon(image: model.icon(app.id), name: Look.name(app), size: 34)
                    VStack(alignment: .leading, spacing: 7) {
                        HStack(spacing: 7) {
                            Text(Look.name(app)).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                            ForEach(app.platforms, id: \.self) { p in
                                Image(systemName: Look.symbol(platform: p)).font(.system(size: 9.5)).foregroundStyle(.tertiary)
                            }
                            Text(STCategory.title(app.category)).font(.system(size: 11)).foregroundStyle(.tertiary).lineLimit(1)
                            Spacer()
                            Text(valueText).font(.system(size: 13.5, weight: .semibold, design: .rounded)).monospacedDigit()
                        }
                        GlowBar(fraction: fraction, tint: tint, height: 5)
                    }
                    Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold)).foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .padding(.vertical, 9).padding(.horizontal, 10)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.primary.opacity(open ? 0.07 : (hover ? 0.045 : 0))))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { hover = $0 }
            if open {
                AppDetail(model: model, app: app)
                    .padding(.leading, 76).padding(.trailing, 14).padding(.top, 6).padding(.bottom, 14)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private var valueText: String {
        switch sort {
        case .time: STFormat.duration(app.seconds)
        case .opens: "\(app.opens ?? 0)×"
        case .notifs: "\(app.notifs ?? 0)"
        }
    }
}

private struct AppDetail: View {
    let model: ScreenTimePageModel
    let app: STReport.AppRow

    var body: some View {
        let r = model.report
        let elapsed = max(1, r.weekDays.filter { $0 <= model.now }.count)
        let weekTotal = app.week.reduce(0, +)
        HStack(alignment: .top, spacing: 28) {
            Chart {
                ForEach(Array(app.week.enumerated()), id: \.offset) { i, s in dayBar(i, s, r) }
            }
            .chartXScale(domain: -0.5...6.5)
            .chartXAxis {
                AxisMarks(values: Array(0..<7)) { v in
                    AxisValueLabel { if let i = v.as(Int.self), i < r.weekDays.count { Text(r.weekDays[i].formatted(.dateTime.weekday(.narrow))).font(.system(size: 10)).foregroundStyle(.secondary) } }
                }
            }
            .chartYAxis(.hidden)
            .frame(width: 200, height: 78)
            Fact(label: "Daily average", value: STFormat.duration(weekTotal / elapsed))
            if let o = app.opens { Fact(label: r.range == .day ? "Opened" : "Opened this week", value: "\(o)×") }
            if let n = app.notifs { Fact(label: "Notifications", value: "\(n)") }
            Fact(label: "Used on", value: app.platforms.map(Look.platformName).joined(separator: ", "))
            Spacer(minLength: 0)
        }
    }

    private func dayBar(_ i: Int, _ seconds: Int, _ r: STReport) -> some ChartContent {
        let x0: Double = Double(i) - 0.3
        let x1: Double = Double(i) + 0.3
        let h: Double = Double(seconds) / 3600
        let selected: Bool = r.range == .week || (i < r.weekDays.count && model.calendar.isDate(r.weekDays[i], inSameDayAs: r.start))
        let tint: Color = Look.color(category: app.category)
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", 0.0), yEnd: .value("Hours", h))
            .foregroundStyle(tint.opacity(selected ? 1 : 0.35))
            .cornerRadius(3)
    }
}

private struct Fact: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Eyebrow(text: label)
            Text(value).font(.system(size: 16, weight: .semibold, design: .rounded))
        }
    }
}

private struct STAppIcon: View {
    let image: NSImage?
    let name: String
    let size: CGFloat
    var body: some View {
        if let image {
            Image(nsImage: image).resizable().interpolation(.high).frame(width: size, height: size)
        } else {
            RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                .fill(LinearGradient(colors: [Color.primary.opacity(0.16), Color.primary.opacity(0.08)], startPoint: .top, endPoint: .bottom))
                .frame(width: size, height: size)
                .overlay(Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.44, weight: .bold, design: .rounded)))
        }
    }
}

// MARK: Access and empty states

private struct AccessCard: View {
    let model: ScreenTimePageModel
    var body: some View {
        Glass(padding: 20) {
            HStack(spacing: 16) {
                Image(systemName: "clock.arrow.circlepath").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Look.gradient(.mac)))
                    .shadow(color: Look.macA.opacity(0.45), radius: 8, y: 3)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Fill in this Mac's whole day").font(.system(size: 14, weight: .semibold))
                    Text("Right now this Mac only counts from when AirBridge started. With Full Disk Access, AirBridge reads the records macOS keeps for its own Screen Time: today from midnight, and the weeks before.")
                        .font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 12)
                VStack(alignment: .trailing, spacing: 8) {
                    Button {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles") { NSWorkspace.shared.open(url) }
                    } label: {
                        Text("Open Full Disk Access").font(.system(size: 12.5, weight: .semibold)).foregroundStyle(.black)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(Capsule().fill(Color.white))
                    }
                    .buttonStyle(.plain)
                    Button("Check again") { model.recheckAccess() }.buttonStyle(.plain).font(.system(size: 11.5)).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct EmptyPage: View {
    var body: some View {
        Glass(padding: 40) {
            VStack(spacing: 14) {
                Image(systemName: "hourglass").font(.system(size: 30, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(Look.gradient(.mac)))
                    .shadow(color: Look.macA.opacity(0.5), radius: 14, y: 6)
                Text("No screen time yet").font(.system(size: 22, weight: .bold, design: .rounded))
                Text("Your phone sends its screen time once AirBridge on it has Usage access. This Mac counts its own when \"This Mac's screen time\" is on in Settings.")
                    .font(.system(size: 13)).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 440)
            }
            .frame(maxWidth: .infinity, minHeight: 360)
        }
    }
}

#if DEBUG
extension ScreenTimeWindow {
    /// airbridge://debug/screentime_snapshot?range=week&filter=mobile&expand=1&back=1&light=1 — the page drawn into
    /// ScreenTime/debug-page.png in the shared folder, to review the design without a screen capture.
    func debugSnapshot(range: String?, filter: String?, expand: Bool, dark: Bool, back: Int = 0) {
        let m = ScreenTimePageModel()
        m.open(device: nil)
        m.close()
        if let f = filter.flatMap(STFilter.init(rawValue:)) { m.filter = f }
        if let r = range.flatMap(STRange.init(rawValue:)) { m.range = r }
        if back > 0 { m.step(-back) }
        if expand { m.expanded = m.report.apps.first?.id }
        let view = ScreenTimeContent(model: m)
            .frame(width: 1120)
            .background(Ambient())
            .environment(\.colorScheme, dark ? .dark : .light)
            .environment(\.stSnapshot, true)
        let r = ImageRenderer(content: view)
        r.scale = 1
        guard let cg = r.cgImage, let folder = ScreenTimeShared.folder() else { return }
        let rep = NSBitmapImageRep(cgImage: cg)
        try? rep.representation(using: .png, properties: [:])?.write(to: folder.appendingPathComponent("debug-page.png"))
    }
}
#endif
