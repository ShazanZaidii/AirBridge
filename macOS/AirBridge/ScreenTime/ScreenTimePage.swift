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

// MARK: - Design language: charcoal, Swiss
//
// Neutral zinc greys only: a page, a slightly lifted card surface, hairline borders, white text with two quieter levels.
// Device groups differ by lightness (Mac white, phones mid grey, the rest dark grey) and are always labelled; the only colour
// on the page is the apps' own icons. One hero number, a stat strip divided by hairlines, a calm bar chart, then lists.
// Motion is short and quiet: bars ease up when the view changes; nothing bounces.

private enum Ink {
    private static func dyn(_ dark: UInt32, _ light: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { a in
            let v = a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        })
    }
    static let page = dyn(0x111113, 0xF4F4F5)
    static let surface = dyn(0x18181B, 0xFFFFFF)
    static let raised = dyn(0x202024, 0xF4F4F5)
    static let hairline = dyn(0x27272B, 0xE4E4E7)
    static let text = dyn(0xF4F4F5, 0x18181B)
    static let secondary = dyn(0xA1A1AA, 0x52525B)
    static let tertiary = dyn(0x71717A, 0xA1A1AA)
    static let track = dyn(0x232327, 0xEDEDEF)
    /// Device groups, by lightness.
    static let mac = dyn(0xE4E4E7, 0x27272A)
    /// The picked segment: a white pill in dark mode, a charcoal one in light mode.
    static let pill = dyn(0xFFFFFF, 0x27272A)
    static let onPill = dyn(0x000000, 0xFFFFFF)
    static let mobile = dyn(0x8E8E96, 0x71717A)
    static let other = dyn(0x4E4E56, 0xB4B4BC)

    static func series(_ g: STFilter) -> Color {
        switch g {
        case .mobile: mobile
        case .mac: mac
        default: other
        }
    }

    /// Categories in five steps of grey, most used lightest.
    static func step(_ i: Int) -> Color {
        [text, dyn(0xC4C4CC, 0x3F3F46), mobile, dyn(0x63636B, 0x9A9AA2), other][min(max(0, i), 4)]
    }

    static let radius: CGFloat = 14
}

private enum Fmt {
    static func symbol(platform: String) -> String {
        switch platform {
        case "android", "ios": "iphone"
        case "macos": "laptopcomputer"
        case "windows": "pc"
        case "linux": "terminal"
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

    static func percent(_ part: Int, of total: Int) -> String {
        total > 0 ? "\(Int((Double(part) / Double(total) * 100).rounded()))%" : "0%"
    }
}

/// True while the debug snapshot draws the page (no motion).
private struct SnapshotKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var stSnapshot: Bool {
        get { self[SnapshotKey.self] }
        set { self[SnapshotKey.self] = newValue }
    }
}

/// Small spaced capitals.
private struct Eyebrow: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.system(size: 10.5, weight: .semibold)).tracking(1.0).foregroundStyle(Ink.tertiary)
    }
}

/// A flat card: lifted surface, one hairline.
private struct Panel<Content: View>: View {
    var padding: CGFloat = 24
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).fill(Ink.surface))
            .overlay(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
    }
}

private struct PanelTitle: View {
    let title: String
    var detail: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Ink.text)
            if let detail { Text(detail).font(.system(size: 12)).foregroundStyle(Ink.tertiary) }
        }
    }
}

/// Segmented choice: the picked one is a white pill (dark text) that slides; the rest are quiet text.
private struct Segmented<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let title: (T) -> String
    var small = false
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button {
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { selection = item }
                } label: {
                    Text(title(item))
                        .font(.system(size: small ? 11.5 : 12.5, weight: on ? .semibold : .medium))
                        .foregroundStyle(on ? Ink.onPill : Ink.secondary)
                        .padding(.horizontal, small ? 11 : 14)
                        .padding(.vertical, small ? 5 : 6)
                        .background {
                            if on { Capsule().fill(Ink.pill).matchedGeometryEffect(id: "seg", in: ns) }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Capsule().fill(Ink.raised))
        .overlay(Capsule().strokeBorder(Ink.hairline, lineWidth: 1))
    }
}

// MARK: - Page

struct ScreenTimePage: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        ScrollView {
            ScreenTimeContent(model: model)
        }
        .background(Ink.page)
        .frame(minWidth: 820, minHeight: 600)
    }
}

/// The page without its scroll view (also what the debug snapshot renders).
struct ScreenTimeContent: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        let r = model.report
        VStack(alignment: .leading, spacing: 16) {
            Masthead(model: model).padding(.bottom, 8)
            if model.snapshot.devices.isEmpty {
                EmptyPage()
            } else {
                if model.showsThisMac && !model.historyAvailable { AccessCard(model: model) }
                Summary(model: model, r: r)
                UsageChart(model: model, r: r)
                HStack(alignment: .top, spacing: 16) {
                    AppsCard(model: model, r: r)
                        .frame(maxWidth: .infinity)
                        .layoutPriority(1)
                    VStack(spacing: 16) {
                        CategoriesCard(r: r)
                        DevicesCard(model: model, r: r)
                    }
                    .frame(width: 340)
                }
                Text("Counted on your own devices. Nothing leaves them.")
                    .font(.system(size: 11)).foregroundStyle(Ink.tertiary)
                    .frame(maxWidth: .infinity).padding(.top, 8)
            }
        }
        .padding(.horizontal, 40)
        .padding(.top, 46)
        .padding(.bottom, 32)
        .frame(maxWidth: 1120)
        .frame(maxWidth: .infinity)
        .foregroundStyle(Ink.text)
    }
}

// MARK: Masthead

private struct Masthead: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Screen Time").font(.system(size: 26, weight: .semibold)).tracking(-0.3)
                Text(subtitle).font(.system(size: 13)).foregroundStyle(Ink.secondary)
            }
            Spacer(minLength: 16)
            Segmented(items: STFilter.allCases, selection: $model.filter, title: \.title)
            Segmented(items: STRange.allCases, selection: $model.range, title: { $0 == .day ? "Day" : "Week" })
            DateStepper(model: model)
        }
    }

    private var subtitle: String {
        let r = model.report
        var s: String
        if r.range == .day { s = r.start.formatted(.dateTime.weekday(.wide).day().month(.wide)) }
        else { s = "\(r.start.formatted(.dateTime.day().month(.abbreviated))) – \(r.end.addingTimeInterval(-1).formatted(.dateTime.day().month(.abbreviated)))" }
        if let n = model.deviceName { s += " · \(n)" }
        return s
    }
}

private struct DateStepper: View {
    @Bindable var model: ScreenTimePageModel
    var body: some View {
        let r = model.report
        HStack(spacing: 0) {
            StepButton(symbol: "chevron.left", enabled: r.hasEarlier, help: "Earlier") { model.step(-1) }
            Button { model.goToday() } label: {
                Text(label(r)).font(.system(size: 12.5, weight: .semibold)).monospacedDigit().frame(minWidth: 84)
            }
            .buttonStyle(.plain)
            .help(model.isCurrent ? "" : "Back to today")
            StepButton(symbol: "chevron.right", enabled: r.hasLater, help: "Later") { model.step(1) }
        }
        .padding(3)
        .background(Capsule().fill(Ink.raised))
        .overlay(Capsule().strokeBorder(Ink.hairline, lineWidth: 1))
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
        return r.start.formatted(.dateTime.day().month(.abbreviated))
    }
}

private struct StepButton: View {
    let symbol: String
    let enabled: Bool
    let help: String
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(Ink.secondary)
                .frame(width: 26, height: 26)
                .background(Circle().fill(Ink.text.opacity(hover && enabled ? 0.08 : 0)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.3)
        .onHover { hover = $0 }
        .help(help)
        .accessibilityLabel(help)
    }
}

// MARK: Summary: the number, the split, the stat strip

private struct Summary: View {
    let model: ScreenTimePageModel
    let r: STReport

    private var elapsedDays: Int { max(1, r.weekDays.filter { $0 <= model.now }.count) }

    private var groups: [(group: STFilter, seconds: Int)] {
        var by: [STFilter: Int] = [:]
        for d in r.devices { by[STFilter.group(d.platform), default: 0] += d.seconds }
        return [STFilter.mac, .mobile, .other].compactMap { g in by[g].map { (g, $0) } }.filter { $0.seconds > 0 }
    }

    var body: some View {
        Panel(padding: 28) {
            VStack(alignment: .leading, spacing: 26) {
                HStack(alignment: .bottom, spacing: 40) {
                    VStack(alignment: .leading, spacing: 8) {
                        Eyebrow(text: r.range == .day ? "Screen time" : "Daily average")
                        Text(STFormat.duration(r.range == .day ? r.total : (r.dailyAverage ?? 0)))
                            .font(.system(size: 64, weight: .medium))
                            .tracking(-1.6)
                            .contentTransition(.numericText())
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        if let line = changeLine {
                            HStack(spacing: 6) {
                                if let up = line.up {
                                    Image(systemName: up ? "arrow.up.right" : "arrow.down.right").font(.system(size: 10, weight: .bold))
                                }
                                Text(line.text)
                            }
                            .font(.system(size: 13)).foregroundStyle(Ink.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                    if !groups.isEmpty { SplitBar(groups: groups, total: max(1, r.total)).frame(width: 360) }
                }
                Rectangle().fill(Ink.hairline).frame(height: 1)
                HStack(alignment: .top, spacing: 0) {
                    Stat(label: "Pickups", value: r.unlocks.map { r.range == .day ? "\($0)" : "\($0 / elapsedDays)" } ?? "–",
                         detail: pickupsDetail)
                    Divider().overlay(Ink.hairline).padding(.horizontal, 24)
                    Stat(label: "Notifications", value: r.notifs.map { r.range == .day ? "\($0)" : "\($0 / elapsedDays)" } ?? "–",
                         detail: r.notifs == nil ? "Counted on Android phones" : (r.range == .day ? topNotifier : "A day, on average"))
                    Divider().overlay(Ink.hairline).padding(.horizontal, 24)
                    Stat(label: r.range == .day ? "Peak hour" : "Peak day", value: peak.value, detail: peak.detail)
                    Divider().overlay(Ink.hairline).padding(.horizontal, 24)
                    Stat(label: "Apps used", value: "\(r.apps.filter { $0.seconds >= 60 }.count)",
                         detail: r.apps.first(where: { $0.seconds > 0 }).map { "Most: \(Fmt.name($0))" } ?? "None yet")
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var changeLine: (text: String, up: Bool?)? {
        guard let change = r.change, let pct = r.changePercent, let base = r.baseline else { return nil }
        let vs = r.range == .day ? "your usual \(STFormat.duration(base))" : "last week's \(STFormat.duration(base)) a day"
        if abs(pct) < 10 || abs(change) < 300 { return ("In line with \(vs)", nil) }
        return change > 0 ? ("\(STFormat.duration(change)) above \(vs)", true) : ("\(STFormat.duration(-change)) below \(vs)", false)
    }

    private var pickupsDetail: String {
        guard r.unlocks != nil else { return "Counted on phones and Macs" }
        if r.range == .week { return "A day, on average" }
        if let f = r.firstUse { return "First at \(STText.clock(f, on: r.start, calendar: model.calendar))" }
        return "Unlocks"
    }

    private var topNotifier: String {
        guard let a = r.apps.filter({ ($0.notifs ?? 0) > 0 }).max(by: { ($0.notifs ?? 0) < ($1.notifs ?? 0) }) else { return "None" }
        return "Most from \(Fmt.name(a))"
    }

    private var peak: (value: String, detail: String) {
        var by: [Int: Int] = [:]
        for b in r.bars { by[b.bucket, default: 0] += b.seconds }
        guard let top = by.max(by: { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }), top.value > 0 else { return ("–", "Nothing yet") }
        if r.range == .day {
            let d = model.calendar.date(bySettingHour: top.key, minute: 0, second: 0, of: r.start) ?? r.start
            return (d.formatted(.dateTime.hour().minute()), "\(STFormat.duration(top.value)) in that hour")
        }
        let name = top.key < r.weekDays.count ? r.weekDays[top.key].formatted(.dateTime.weekday(.wide)) : "–"
        return (name, STFormat.duration(top.value))
    }
}

private struct Stat: View {
    let label: String
    let value: String
    let detail: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Eyebrow(text: label)
            Text(value).font(.system(size: 22, weight: .medium)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
                .contentTransition(.numericText())
            Text(detail).font(.system(size: 12)).foregroundStyle(Ink.tertiary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// How the time splits between device groups: one thin segmented bar with labels under it.
private struct SplitBar: View {
    let groups: [(group: STFilter, seconds: Int)]
    let total: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow(text: "By device")
            GeometryReader { g in
                HStack(spacing: 3) {
                    ForEach(groups, id: \.group) { item in
                        RoundedRectangle(cornerRadius: 2, style: .continuous)
                            .fill(Ink.series(item.group))
                            .frame(width: max(4, (g.size.width - CGFloat(groups.count - 1) * 3) * CGFloat(item.seconds) / CGFloat(total)))
                    }
                }
            }
            .frame(height: 8)
            HStack(alignment: .top, spacing: 22) {
                ForEach(groups, id: \.group) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 2).fill(Ink.series(item.group)).frame(width: 8, height: 8)
                            Text(item.group.title).font(.system(size: 12, weight: .medium)).foregroundStyle(Ink.secondary)
                        }
                        Text(STFormat.duration(item.seconds)).font(.system(size: 15, weight: .medium)).monospacedDigit()
                        Text(Fmt.percent(item.seconds, of: total)).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary).monospacedDigit()
                    }
                }
            }
        }
    }
}

// MARK: Chart

private struct UsageChart: View {
    @Bindable var model: ScreenTimePageModel
    let r: STReport
    @State private var grow: Double = 1
    @Environment(\.stSnapshot) private var snapshot
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var day: Bool { r.range == .day }
    private var buckets: Int { day ? 24 : 7 }
    private var groups: [STFilter] { [.mac, .mobile, .other].filter { g in r.bars.contains { $0.group == g } } }
    private func value(_ s: Int) -> Double { day ? Double(s) / 60 : Double(s) / 3600 }
    private func bucketTotal(_ b: Int) -> Int { r.bars.filter { $0.bucket == b }.map(\.seconds).reduce(0, +) }
    private var top: Double {
        let m = value((0..<buckets).map(bucketTotal).max() ?? 0)
        return day ? max(60, (m / 15).rounded(.up) * 15) : max(2, m.rounded(.up))
    }

    var body: some View {
        Panel {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .firstTextBaseline) {
                    PanelTitle(title: day ? "Through the day" : "Day by day", detail: day ? "Minutes in each hour" : "Click a day to open it")
                    Spacer()
                    HStack(spacing: 16) {
                        ForEach(groups, id: \.self) { g in
                            HStack(spacing: 6) {
                                RoundedRectangle(cornerRadius: 2).fill(Ink.series(g)).frame(width: 8, height: 8)
                                Text(g.title).font(.system(size: 12)).foregroundStyle(Ink.secondary)
                            }
                        }
                        if !day, let avg = r.dailyAverage, avg > 0 {
                            HStack(spacing: 6) {
                                Path { p in p.move(to: CGPoint(x: 0, y: 0.5)); p.addLine(to: CGPoint(x: 14, y: 0.5)) }
                                    .stroke(Ink.tertiary, style: StrokeStyle(lineWidth: 1, dash: [2, 3]))
                                    .frame(width: 14, height: 1)
                                Text("Avg \(STFormat.duration(avg))").font(.system(size: 12)).foregroundStyle(Ink.secondary).monospacedDigit()
                            }
                        }
                    }
                }
                if r.bars.isEmpty {
                    Text(day && r.total > 0 ? "These devices don't send hour-by-hour numbers yet." : "Nothing used in this \(day ? "day" : "week").")
                        .font(.system(size: 13)).foregroundStyle(Ink.tertiary)
                        .frame(maxWidth: .infinity, minHeight: 200)
                } else {
                    chart.frame(height: 220)
                    if let note = missingHoursNote {
                        Text(note).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary)
                    }
                }
            }
        }
        .onChange(of: "\(r.start.timeIntervalSince1970)|\(r.range.rawValue)|\(model.filter.rawValue)|\(model.device ?? "")") { _, _ in
            guard !snapshot, !reduceMotion else { return }
            grow = 0
            withAnimation(.easeOut(duration: 0.35)) { grow = 1 }
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
        return "\(names.joined(separator: ", ")) sent no hour-by-hour numbers for this day, so that time isn't in the bars. Updating AirBridge on it fixes this."
    }

    private struct Seg: Hashable { let bucket: Int; let group: STFilter; let lo: Double; let hi: Double }

    private var segments: [Seg] {
        var height: [Int: Double] = [:]
        var out: [Seg] = []
        for g in [STFilter.mac, .mobile, .other] {
            for b in r.bars where b.group == g {
                let lo = height[b.bucket] ?? 0
                let hi = lo + value(b.seconds) * grow
                height[b.bucket] = hi
                out.append(Seg(bucket: b.bucket, group: g, lo: lo, hi: hi))
            }
        }
        return out
    }

    private func pieceMark(_ s: Seg) -> some ChartContent {
        let half: Double = day ? 0.3 : 0.24
        let x0: Double = Double(s.bucket) - half
        let x1: Double = Double(s.bucket) + half
        let dim: Bool = model.hoverBucket != nil && model.hoverBucket != s.bucket
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", s.lo), yEnd: .value("Top", s.hi))
            .foregroundStyle(Ink.series(s.group))
            .cornerRadius(day ? 2.5 : 4)
            .opacity(dim ? 0.3 : 1)
    }

    private var chart: some View {
        Chart {
            ForEach(segments, id: \.self) { s in pieceMark(s) }
            if !day, let avg = r.dailyAverage, avg > 0 {
                RuleMark(y: .value("Average", value(avg)))
                    .foregroundStyle(Ink.tertiary)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 4]))
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
            AxisMarks(values: day ? [0, 6, 12, 18] : Array(0..<7)) { v in
                AxisValueLabel { if let i = v.as(Int.self) { Text(xLabel(i)).font(.system(size: 11)).foregroundStyle(Ink.tertiary) } }
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 1)).foregroundStyle(Ink.hairline)
                AxisValueLabel { if let d = v.as(Double.self) { Text(day ? "\(Int(d))m" : axisHours(d)).font(.system(size: 10.5)).foregroundStyle(Ink.tertiary) } }
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
            return d.formatted(.dateTime.hour().minute())
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
            Text(title).font(.system(size: 11, weight: .medium)).foregroundStyle(Ink.tertiary)
            Text(STFormat.duration(bucketTotal(b))).font(.system(size: 17, weight: .semibold)).monospacedDigit()
            ForEach(parts, id: \.self) { p in
                HStack(spacing: 7) {
                    RoundedRectangle(cornerRadius: 2).fill(Ink.series(p.group)).frame(width: 8, height: 8)
                    Text(p.group.title).font(.system(size: 12)).foregroundStyle(Ink.secondary)
                    Spacer(minLength: 14)
                    Text(STFormat.duration(p.seconds)).font(.system(size: 12, weight: .medium)).monospacedDigit()
                }
            }
        }
        .padding(12)
        .frame(minWidth: 150)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Ink.raised))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: 10, y: 4)
    }
}

// MARK: Apps

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
        let shown = model.showAllApps ? all : Array(all.prefix(8))
        let maxValue = max(1, all.map { metric($0, sort) }.max() ?? 1)
        Panel {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .firstTextBaseline) {
                    PanelTitle(title: "Most used", detail: "\(all.count) \(all.count == 1 ? "app" : "apps")")
                    Spacer()
                    if sorts.count > 1 {
                        Segmented(items: sorts, selection: $model.sort, title: { s in
                            switch s { case .time: "Time"; case .opens: "Opens"; case .notifs: "Notifications" }
                        }, small: true)
                    }
                }
                if all.isEmpty {
                    Text(sort == .time ? "No apps used in this \(r.range == .day ? "day" : "week")." : "Nothing to count here.")
                        .font(.system(size: 13)).foregroundStyle(Ink.tertiary).padding(.vertical, 8)
                }
                VStack(spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, app in
                        if i > 0 { Rectangle().fill(Ink.hairline).frame(height: 1).padding(.leading, 50) }
                        AppLine(model: model, app: app, sort: sort, fraction: Double(metric(app, sort)) / Double(maxValue))
                    }
                }
                if all.count > 8 {
                    Button { withAnimation(.easeOut(duration: 0.2)) { model.showAllApps.toggle() } } label: {
                        Text(model.showAllApps ? "Show fewer" : "Show all \(all.count)")
                            .font(.system(size: 12, weight: .medium)).foregroundStyle(Ink.secondary)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .overlay(Capsule().strokeBorder(Ink.hairline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct AppLine: View {
    @Bindable var model: ScreenTimePageModel
    let app: STReport.AppRow
    let sort: AppSort
    let fraction: Double
    @State private var hover = false

    private var open: Bool { model.expanded == app.id }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation(.easeOut(duration: 0.22)) { model.expanded = open ? nil : app.id } } label: {
                HStack(spacing: 14) {
                    STAppIcon(image: model.icon(app.id), name: Fmt.name(app), size: 32)
                    VStack(alignment: .leading, spacing: 7) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(Fmt.name(app)).font(.system(size: 13, weight: .medium)).lineLimit(1)
                            Text(meta).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary).lineLimit(1)
                            Spacer()
                            Text(valueText).font(.system(size: 13, weight: .medium)).monospacedDigit()
                        }
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Ink.track)
                                Capsule().fill(Ink.secondary).frame(width: max(3, g.size.width * min(1, fraction)))
                            }
                        }
                        .frame(height: 3)
                    }
                }
                .padding(.vertical, 11).padding(.horizontal, 8)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Ink.text.opacity(open ? 0.05 : (hover ? 0.03 : 0))))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { hover = $0 }
            if open {
                AppDetail(model: model, app: app)
                    .padding(.leading, 54).padding(.trailing, 8).padding(.top, 4).padding(.bottom, 14)
                    .transition(.opacity)
            }
        }
    }

    private var meta: String {
        ([STCategory.title(app.category)] + app.platforms.map(Fmt.platformName)).joined(separator: " · ")
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
        HStack(alignment: .top, spacing: 32) {
            Chart {
                ForEach(Array(app.week.enumerated()), id: \.offset) { i, s in dayBar(i, s, r) }
            }
            .chartXScale(domain: -0.5...6.5)
            .chartXAxis {
                AxisMarks(values: Array(0..<7)) { v in
                    AxisValueLabel { if let i = v.as(Int.self), i < r.weekDays.count { Text(r.weekDays[i].formatted(.dateTime.weekday(.narrow))).font(.system(size: 10)).foregroundStyle(Ink.tertiary) } }
                }
            }
            .chartYAxis(.hidden)
            .frame(width: 180, height: 64)
            Fact(label: "Daily average", value: STFormat.duration(weekTotal / elapsed))
            if let o = app.opens { Fact(label: r.range == .day ? "Opened" : "Opened, week", value: "\(o)×") }
            if let n = app.notifs { Fact(label: "Notifications", value: "\(n)") }
            Spacer(minLength: 0)
        }
    }

    private func dayBar(_ i: Int, _ seconds: Int, _ r: STReport) -> some ChartContent {
        let x0: Double = Double(i) - 0.28
        let x1: Double = Double(i) + 0.28
        let h: Double = Double(seconds) / 3600
        let selected: Bool = r.range == .week || (i < r.weekDays.count && model.calendar.isDate(r.weekDays[i], inSameDayAs: r.start))
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", 0.0), yEnd: .value("Hours", h))
            .foregroundStyle(selected ? Ink.text : Ink.other)
            .cornerRadius(2)
    }
}

private struct Fact: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Eyebrow(text: label)
            Text(value).font(.system(size: 15, weight: .medium)).monospacedDigit()
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
            RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
                .fill(Ink.raised)
                .overlay(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
                .frame(width: size, height: size)
                .overlay(Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.42, weight: .medium)).foregroundStyle(Ink.secondary))
        }
    }
}

// MARK: Categories and devices

private struct CategoriesCard: View {
    let r: STReport
    var body: some View {
        let cats = Array(r.categories.prefix(5))
        let total = max(1, r.categories.map(\.seconds).reduce(0, +))
        Panel {
            VStack(alignment: .leading, spacing: 16) {
                PanelTitle(title: "Categories")
                if cats.isEmpty {
                    Text("Nothing used yet.").font(.system(size: 13)).foregroundStyle(Ink.tertiary)
                } else {
                    GeometryReader { g in
                        HStack(spacing: 2) {
                            ForEach(Array(cats.enumerated()), id: \.element.category) { i, c in
                                Rectangle().fill(Ink.step(i))
                                    .frame(width: max(2, (g.size.width - CGFloat(cats.count - 1) * 2) * CGFloat(c.seconds) / CGFloat(total)))
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
                    }
                    .frame(height: 8)
                    VStack(spacing: 11) {
                        ForEach(Array(cats.enumerated()), id: \.element.category) { i, c in
                            HStack(spacing: 10) {
                                RoundedRectangle(cornerRadius: 2).fill(Ink.step(i)).frame(width: 8, height: 8)
                                Text(STCategory.title(c.category)).font(.system(size: 12.5)).lineLimit(1)
                                Spacer(minLength: 8)
                                Text(Fmt.percent(c.seconds, of: total)).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary).monospacedDigit()
                                Text(STFormat.duration(c.seconds)).font(.system(size: 12.5, weight: .medium)).monospacedDigit()
                                    .frame(minWidth: 54, alignment: .trailing)
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
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                PanelTitle(title: "Devices", detail: "Click one to show only it")
                if r.devices.isEmpty { Text("No device has time here.").font(.system(size: 13)).foregroundStyle(Ink.tertiary) }
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
        let on = model.device == d.id
        Button { withAnimation(.easeOut(duration: 0.2)) { model.device = on ? nil : d.id } } label: {
            HStack(spacing: 12) {
                Image(systemName: Fmt.symbol(platform: d.platform))
                    .font(.system(size: 14))
                    .foregroundStyle(Ink.secondary)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Ink.raised))
                    .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(d.name).font(.system(size: 13, weight: .medium)).lineLimit(1)
                        Spacer()
                        Text(STFormat.duration(d.seconds)).font(.system(size: 13, weight: .medium)).monospacedDigit()
                    }
                    HStack {
                        Text("Updated \(STText.ago(d.updated, now: model.now))").font(.system(size: 11.5)).foregroundStyle(Ink.tertiary)
                        Spacer()
                        Text(Fmt.percent(d.seconds, of: total)).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary).monospacedDigit()
                    }
                }
            }
            .padding(8)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Ink.text.opacity(on ? 0.06 : (hover ? 0.03 : 0))))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Ink.text.opacity(on ? 0.25 : 0), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .padding(.horizontal, -8)
    }
}

// MARK: Access and empty states

private struct AccessCard: View {
    let model: ScreenTimePageModel
    var body: some View {
        Panel(padding: 20) {
            HStack(spacing: 16) {
                Image(systemName: "clock.arrow.circlepath").font(.system(size: 16)).foregroundStyle(Ink.secondary)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(Ink.raised))
                VStack(alignment: .leading, spacing: 3) {
                    Text("Fill in this Mac's whole day").font(.system(size: 13.5, weight: .semibold))
                    Text("Right now this Mac only counts from when AirBridge started. With Full Disk Access, AirBridge reads the records macOS keeps for its own Screen Time: today from midnight, and the weeks before.")
                        .font(.system(size: 12)).foregroundStyle(Ink.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 12)
                VStack(alignment: .trailing, spacing: 8) {
                    Button {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles") { NSWorkspace.shared.open(url) }
                    } label: {
                        Text("Open Full Disk Access").font(.system(size: 12, weight: .semibold)).foregroundStyle(.black)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background(Capsule().fill(Color.white))
                    }
                    .buttonStyle(.plain)
                    Button("Check again") { model.recheckAccess() }.buttonStyle(.plain).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary)
                }
            }
        }
    }
}

private struct EmptyPage: View {
    var body: some View {
        Panel(padding: 40) {
            VStack(spacing: 12) {
                Image(systemName: "hourglass").font(.system(size: 26, weight: .light)).foregroundStyle(Ink.secondary)
                Text("No screen time yet").font(.system(size: 18, weight: .semibold))
                Text("Your phone sends its screen time once AirBridge on it has Usage access. This Mac counts its own when \"This Mac's screen time\" is on in Settings.")
                    .font(.system(size: 13)).foregroundStyle(Ink.secondary).multilineTextAlignment(.center).frame(maxWidth: 440)
            }
            .frame(maxWidth: .infinity, minHeight: 320)
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
            .background(Ink.page)
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
