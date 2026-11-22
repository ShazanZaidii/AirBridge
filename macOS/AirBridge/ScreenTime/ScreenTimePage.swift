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
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 780),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Screen Time"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentViewController = host
            w.setContentSize(NSSize(width: 1000, height: 780))
            w.minSize = NSSize(width: 760, height: 560)
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

// MARK: - Look

private enum Look {
    static let mobile = Color(red: 0x5B / 255, green: 0x9D / 255, blue: 0xFF / 255)
    static let mac = Color.primary.opacity(0.62)
    static let other = Color(red: 1.0, green: 0.66, blue: 0.32)
    static let card = Color.primary.opacity(0.045)
    static let stroke = Color.primary.opacity(0.07)
    static let radius: CGFloat = 16

    static func color(_ g: STFilter) -> Color {
        switch g {
        case .mobile: mobile
        case .mac: mac
        default: other
        }
    }

    /// Category colours: distinct, no greens (AirBridge's palette).
    static func color(category c: String) -> Color {
        switch c {
        case "social": Color(red: 1.0, green: 0.42, blue: 0.60)
        case "entertainment": Color(red: 0.66, green: 0.55, blue: 0.98)
        case "games": Color(red: 1.0, green: 0.48, blue: 0.35)
        case "productivity": mobile
        case "creativity": Color(red: 1.0, green: 0.82, blue: 0.35)
        case "reading": Color(red: 0.35, green: 0.78, blue: 0.98)
        case "travel": Color(red: 0.98, green: 0.62, blue: 0.85)
        case "shopping": other
        case "health": Color(red: 1.0, green: 0.36, blue: 0.40)
        case "utilities": Color.primary.opacity(0.5)
        default: Color.primary.opacity(0.3)
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
}

private struct Card<Content: View>: View {
    var title: String?
    var trailing: AnyView?
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if title != nil || trailing != nil {
                HStack {
                    if let title { Text(title).font(.headline) }
                    Spacer()
                    if let trailing { trailing }
                }
            }
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Look.radius, style: .continuous).fill(Look.card))
        .overlay(RoundedRectangle(cornerRadius: Look.radius, style: .continuous).strokeBorder(Look.stroke))
    }
}

/// Capsule choices: the picked one is white (dark text), the rest quiet.
private struct Pills<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let title: (T) -> String
    var small = false
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        HStack(spacing: 4) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button { withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { selection = item } } label: {
                    Text(title(item))
                        .font(small ? .caption.weight(.medium) : .callout.weight(.medium))
                        .padding(.horizontal, small ? 10 : 14)
                        .padding(.vertical, small ? 4 : 6)
                        .foregroundStyle(on ? Color.black : Color.primary.opacity(0.75))
                        .background(Capsule().fill(on ? Color.white : Color.clear))
                        .shadow(color: .black.opacity(on ? 0.18 : 0), radius: 2, y: 1)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(Capsule().fill(Color.primary.opacity(scheme == .dark ? 0.08 : 0.06)))
    }
}

// MARK: - Page

struct ScreenTimePage: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        ScrollView {
            ScreenTimeContent(model: model)
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .frame(minWidth: 760, minHeight: 560)
    }
}

/// The page without its scroll view (also what the debug snapshot renders).
struct ScreenTimeContent: View {
    @Bindable var model: ScreenTimePageModel

    var body: some View {
        let r = model.report
            VStack(alignment: .leading, spacing: 18) {
                TopBar(model: model)
                if model.snapshot.devices.isEmpty {
                    EmptyPage()
                } else {
                    if model.showsThisMac && !model.historyAvailable { AccessCard(model: model) }
                    Hero(model: model, r: r)
                    UsageChart(model: model, r: r)
                    StatTiles(model: model, r: r)
                    HStack(alignment: .top, spacing: 18) {
                        DevicesCard(model: model, r: r)
                        CategoriesCard(r: r)
                    }
                    AppsCard(model: model, r: r)
                }
            }
            .padding(.horizontal, 28)
            .padding(.top, 34)
            .padding(.bottom, 28)
            .frame(maxWidth: 1080)
            .frame(maxWidth: .infinity)
    }
}

#if DEBUG
extension ScreenTimeWindow {
    /// airbridge://debug/screentime_snapshot?range=week&filter=mobile&expand=1 — the page drawn into
    /// ScreenTime/debug-page.png in the shared folder, to review the design without a screen capture.
    func debugSnapshot(range: String?, filter: String?, expand: Bool, dark: Bool) {
        let m = ScreenTimePageModel()
        m.open(device: nil)
        m.close()
        if let f = filter.flatMap(STFilter.init(rawValue:)) { m.filter = f }
        if let r = range.flatMap(STRange.init(rawValue:)) { m.range = r }
        if expand { m.expanded = m.report.apps.first?.id }
        let view = ScreenTimeContent(model: m)
            .frame(width: 1000)
            .background(Color(nsColor: .windowBackgroundColor))
            .environment(\.colorScheme, dark ? .dark : .light)
        let r = ImageRenderer(content: view)
        r.scale = 1
        guard let cg = r.cgImage, let folder = ScreenTimeShared.folder() else { return }
        let rep = NSBitmapImageRep(cgImage: cg)
        try? rep.representation(using: .png, properties: [:])?.write(to: folder.appendingPathComponent("debug-page.png"))
    }
}
#endif

private struct TopBar: View {
    @Bindable var model: ScreenTimePageModel
    var body: some View {
        HStack(spacing: 12) {
            Pills(items: STFilter.allCases, selection: $model.filter, title: \.title)
            if let name = model.deviceName {
                Button { model.device = nil } label: {
                    HStack(spacing: 5) {
                        Text(name).font(.callout.weight(.medium))
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Capsule().fill(Color.primary.opacity(0.08)))
                }
                .buttonStyle(.plain)
                .help("Show every device again")
            }
            Spacer()
            Pills(items: STRange.allCases, selection: $model.range, title: { $0 == .day ? "Day" : "Week" })
            DateStepper(model: model)
        }
    }
}

private struct DateStepper: View {
    @Bindable var model: ScreenTimePageModel
    var body: some View {
        let r = model.report
        HStack(spacing: 2) {
            StepButton(symbol: "chevron.left", enabled: r.hasEarlier) { model.step(-1) }
            Button { model.goToday() } label: {
                Text(label(r)).font(.callout.weight(.medium)).monospacedDigit().frame(minWidth: 112)
            }
            .buttonStyle(.plain)
            .help(model.isCurrent ? "" : "Back to today")
            StepButton(symbol: "chevron.right", enabled: r.hasLater) { model.step(1) }
        }
        .padding(3)
        .background(Capsule().fill(Color.primary.opacity(0.06)))
    }

    private func label(_ r: STReport) -> String {
        let cal = model.calendar
        if r.range == .day {
            if cal.isDateInToday(r.start) { return "Today" }
            if cal.isDateInYesterday(r.start) { return "Yesterday" }
            return r.start.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        }
        if cal.isDate(r.start, equalTo: model.now, toGranularity: .weekOfYear) { return "This week" }
        if let last = cal.date(byAdding: .weekOfYear, value: -1, to: model.now), cal.isDate(r.start, equalTo: last, toGranularity: .weekOfYear) { return "Last week" }
        let endDay = r.end.addingTimeInterval(-1)
        return "\(r.start.formatted(.dateTime.day().month(.abbreviated))) – \(endDay.formatted(.dateTime.day().month(.abbreviated)))"
    }
}

private struct StepButton: View {
    let symbol: String
    let enabled: Bool
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 11, weight: .semibold))
                .frame(width: 26, height: 26)
                .background(Circle().fill(Color.primary.opacity(hover && enabled ? 0.1 : 0)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.3)
        .onHover { hover = $0 }
    }
}

// MARK: Hero

private struct Hero: View {
    let model: ScreenTimePageModel
    let r: STReport
    var body: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                Text(r.range == .day ? caption : "Daily average")
                    .font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
                Text(STFormat.duration(r.range == .day ? r.total : (r.dailyAverage ?? 0)))
                    .font(.system(size: 60, weight: .semibold, design: .rounded))
                    .contentTransition(.numericText())
                    .animation(.snappy, value: r.total)
                if r.range == .week {
                    Text("\(STFormat.duration(r.total)) in total").font(.callout).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if let mood { MoodChip(mood: mood) }
        }
    }

    private var caption: String {
        let who = model.deviceName ?? (model.filter == .all ? "all your devices" : "your \(model.filter.title.lowercased()) devices")
        return "Screen time on \(who)"
    }

    /// A plain-words comparison: with the 7 days before (a day) or last week's daily average (a week).
    private var mood: (title: String, detail: String, up: Bool?)? {
        guard let change = r.change, let pct = r.changePercent, let base = r.baseline else { return nil }
        let vs = r.range == .day ? "your usual \(STFormat.duration(base))" : "last week's \(STFormat.duration(base)) a day"
        if abs(pct) < 10 || abs(change) < 300 { return ("About your usual", "Close to \(vs)", nil) }
        if change > 0 { return (r.range == .day ? "Heavier than usual" : "Up on last week", "\(STFormat.duration(change)) more than \(vs) (+\(pct)%)", true) }
        return (r.range == .day ? "Lighter than usual" : "Down on last week", "\(STFormat.duration(-change)) less than \(vs) (\(pct)%)", false)
    }
}

private struct MoodChip: View {
    let mood: (title: String, detail: String, up: Bool?)
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: mood.up == nil ? "equal" : (mood.up! ? "arrow.up.right" : "arrow.down.right"))
                .font(.system(size: 14, weight: .bold))
                .frame(width: 30, height: 30)
                .background(Circle().fill(Color.primary.opacity(0.08)))
            VStack(alignment: .leading, spacing: 1) {
                Text(mood.title).font(.callout.weight(.semibold))
                Text(mood.detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Look.card))
    }
}

// MARK: Chart

private struct UsageChart: View {
    @Bindable var model: ScreenTimePageModel
    let r: STReport

    private var groups: [STFilter] { [.mobile, .mac, .other].filter { g in r.bars.contains { $0.group == g } } }
    private var day: Bool { r.range == .day }
    /// Day: minutes per hour. Week: hours per day.
    private func value(_ s: Int) -> Double { day ? Double(s) / 60 : Double(s) / 3600 }
    private func bucketTotal(_ b: Int) -> Int { r.bars.filter { $0.bucket == b }.map(\.seconds).reduce(0, +) }

    var body: some View {
        Card(title: day ? "Through the day" : "Day by day", trailing: AnyView(legend)) {
            if r.bars.isEmpty {
                Text(day && r.total > 0 ? "These devices don't send hour-by-hour numbers yet." : "Nothing used in this \(day ? "day" : "week").")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 180)
            } else {
                chart.frame(height: 210)
                if !day { Text("Click a day to open it.").font(.caption).foregroundStyle(.tertiary) }
            }
        }
    }

    /// The bars stacked by hand (mobile at the bottom, then Mac, then the rest), each piece from its bottom to its top.
    private var segments: [(bar: STReport.Bar, lo: Double, hi: Double)] {
        var height: [Int: Double] = [:]
        return r.bars.map { b in
            let lo = height[b.bucket] ?? 0
            let hi = lo + value(b.seconds)
            height[b.bucket] = hi
            return (b, lo, hi)
        }
    }

    private func barMark(_ seg: (bar: STReport.Bar, lo: Double, hi: Double)) -> some ChartContent {
        let x0: Double = Double(seg.bar.bucket) - 0.31
        let x1: Double = Double(seg.bar.bucket) + 0.31
        let dim: Bool = model.hoverBucket != nil && model.hoverBucket != seg.bar.bucket
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", seg.lo), yEnd: .value("Top", seg.hi))
            .foregroundStyle(Look.color(seg.bar.group))
            .cornerRadius(2.5)
            .opacity(dim ? 0.4 : 1)
    }

    private var chart: some View {
        let maxBucket = Double((0..<(day ? 24 : 7)).map(bucketTotal).max() ?? 0)
        let top = day ? max(60, (value(Int(maxBucket)) / 15).rounded(.up) * 15) : max(1, value(Int(maxBucket)).rounded(.up))
        return Chart {
            ForEach(segments, id: \.bar) { seg in barMark(seg) }
            if !day, let avg = r.dailyAverage, avg > 0 {
                RuleMark(y: .value("Average", value(avg)))
                    .foregroundStyle(Color.primary.opacity(0.45))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: .top, alignment: .trailing) {
                        Text("avg \(STFormat.duration(avg))").font(.caption2).foregroundStyle(.secondary)
                    }
            }
            if let h = model.hoverBucket, bucketTotal(h) > 0 {
                RuleMark(x: .value("When", Double(h)))
                    .foregroundStyle(Color.clear)
                    .annotation(position: .top, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) { tooltip(h) }
            }
        }
        .chartXScale(domain: -0.5...(day ? 23.5 : 6.5))
        .chartYScale(domain: 0...top)
        .chartXAxis {
            AxisMarks(values: day ? [0, 6, 12, 18] : Array(0..<7)) { v in
                AxisGridLine().foregroundStyle(Color.primary.opacity(day ? 0.1 : 0))
                AxisValueLabel { if let i = v.as(Int.self) { Text(xLabel(i)).font(.caption) } }
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                AxisGridLine().foregroundStyle(Color.primary.opacity(0.1))
                AxisValueLabel { if let d = v.as(Double.self) { Text(day ? "\(Int(d))m" : axisHours(d)).font(.caption) } }
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
        let x = p.x - geo[frame].origin.x
        guard let v: Double = proxy.value(atX: x) else { return nil }
        let b = Int(v.rounded())
        return (0..<(day ? 24 : 7)).contains(b) ? b : nil
    }

    private func xLabel(_ i: Int) -> String {
        if day { return String(format: "%02d", i) }
        guard i < r.weekDays.count else { return "" }
        return r.weekDays[i].formatted(.dateTime.weekday(.abbreviated))
    }

    private func axisHours(_ h: Double) -> String { h == 0 ? "0" : (h == h.rounded() ? "\(Int(h))h" : String(format: "%.1fh", h)) }

    private func tooltip(_ b: Int) -> some View {
        let parts = r.bars.filter { $0.bucket == b }
        let title = day ? String(format: "%02d:00 – %02d:00", b, (b + 1) % 24) : (b < r.weekDays.count ? r.weekDays[b].formatted(.dateTime.weekday(.wide).day().month(.abbreviated)) : "")
        return VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption.weight(.semibold))
            Text(STFormat.duration(bucketTotal(b))).font(.title3.weight(.semibold)).monospacedDigit()
            ForEach(parts, id: \.self) { p in
                HStack(spacing: 6) {
                    Circle().fill(Look.color(p.group)).frame(width: 7, height: 7)
                    Text(p.group.title).font(.caption)
                    Spacer(minLength: 12)
                    Text(STFormat.duration(p.seconds)).font(.caption).monospacedDigit()
                }
            }
        }
        .padding(10)
        .frame(minWidth: 150)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.regularMaterial))
        .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
    }

    private var legend: some View {
        HStack(spacing: 14) {
            ForEach(groups, id: \.self) { g in
                HStack(spacing: 5) {
                    Circle().fill(Look.color(g)).frame(width: 8, height: 8)
                    Text(g.title).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

// MARK: Tiles

private struct StatTiles: View {
    let model: ScreenTimePageModel
    let r: STReport

    private var elapsedDays: Int { max(1, r.weekDays.filter { $0 <= model.now }.count) }

    var body: some View {
        HStack(spacing: 14) {
            Tile(symbol: "hand.raised.fingers.spread.fill", title: "Pickups",
                 value: r.unlocks.map { r.range == .day ? "\($0)" : "\($0 / elapsedDays)" } ?? "—",
                 detail: pickupsDetail)
            Tile(symbol: "bell.badge.fill", title: "Notifications",
                 value: r.notifs.map { r.range == .day ? "\($0)" : "\($0 / elapsedDays)" } ?? "—",
                 detail: r.notifs == nil ? "Counted on Android phones" : (r.range == .day ? topNotifier : "a day on average"))
            Tile(symbol: r.range == .day ? "clock.fill" : "calendar", title: r.range == .day ? "Busiest hour" : "Busiest day",
                 value: busiest.value, detail: busiest.detail)
            Tile(symbol: "rectangle.stack.fill", title: "Apps used", value: "\(r.apps.filter { $0.seconds >= 60 }.count)",
                 detail: r.apps.first.map { "Most: \($0.name)" } ?? "None yet")
        }
    }

    private var pickupsDetail: String {
        guard r.unlocks != nil else { return "Counted on phones and Macs" }
        if r.range == .week { return "a day on average" }
        if let f = r.firstUse { return "First at \(STText.clock(f, on: r.start, calendar: model.calendar))" }
        return "Unlocks today"
    }

    private var topNotifier: String {
        guard let a = r.apps.filter({ ($0.notifs ?? 0) > 0 }).max(by: { ($0.notifs ?? 0) < ($1.notifs ?? 0) }) else { return "None" }
        return "Most from \(a.name)"
    }

    private var busiest: (value: String, detail: String) {
        var by: [Int: Int] = [:]
        for b in r.bars { by[b.bucket, default: 0] += b.seconds }
        guard let top = by.max(by: { $0.value < $1.value }), top.value > 0 else { return ("—", "Nothing yet") }
        if r.range == .day { return (String(format: "%02d:00", top.key), STFormat.duration(top.value)) }
        let name = top.key < r.weekDays.count ? r.weekDays[top.key].formatted(.dateTime.weekday(.wide)) : "—"
        return (name, STFormat.duration(top.value))
    }
}

private struct Tile: View {
    let symbol: String
    let title: String
    let value: String
    let detail: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: symbol).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 26, weight: .semibold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Look.radius, style: .continuous).fill(Look.card))
        .overlay(RoundedRectangle(cornerRadius: Look.radius, style: .continuous).strokeBorder(Look.stroke))
    }
}

// MARK: Devices and categories

private struct DevicesCard: View {
    @Bindable var model: ScreenTimePageModel
    let r: STReport
    var body: some View {
        Card(title: "Devices") {
            if r.devices.isEmpty {
                Text("No device has time here.").font(.callout).foregroundStyle(.secondary)
            }
            ForEach(r.devices) { d in
                Button { model.device = model.device == d.id ? nil : d.id } label: {
                    HStack(spacing: 12) {
                        Image(systemName: Look.symbol(platform: d.platform))
                            .font(.system(size: 15))
                            .frame(width: 34, height: 34)
                            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Look.color(STFilter.group(d.platform)).opacity(0.18)))
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(d.name).font(.callout.weight(.medium)).lineLimit(1)
                                Spacer()
                                Text(STFormat.duration(d.seconds)).font(.callout).monospacedDigit()
                            }
                            ShareBar(fraction: r.total > 0 ? Double(d.seconds) / Double(r.total) : 0, color: Look.color(STFilter.group(d.platform)))
                            Text("Updated \(STText.ago(d.updated, now: model.now))").font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(model.device == d.id ? "Show every device" : "Show only \(d.name)")
            }
        }
    }
}

private struct ShareBar: View {
    let fraction: Double
    let color: Color
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.07))
                Capsule().fill(color).frame(width: max(4, g.size.width * min(1, fraction)))
            }
        }
        .frame(height: 5)
    }
}

private struct CategoriesCard: View {
    let r: STReport
    var body: some View {
        let total = max(1, r.categories.map(\.seconds).reduce(0, +))
        Card(title: "Categories") {
            if r.categories.isEmpty {
                Text("Nothing used yet.").font(.callout).foregroundStyle(.secondary)
            } else {
                GeometryReader { g in
                    HStack(spacing: 2) {
                        ForEach(r.categories, id: \.category) { c in
                            Rectangle().fill(Look.color(category: c.category))
                                .frame(width: max(3, (g.size.width - CGFloat(r.categories.count) * 2) * CGFloat(c.seconds) / CGFloat(total)))
                        }
                    }
                    .clipShape(Capsule())
                }
                .frame(height: 10)
                ForEach(r.categories.prefix(6), id: \.category) { c in
                    HStack(spacing: 10) {
                        Image(systemName: STCategory.symbol(c.category))
                            .font(.system(size: 11))
                            .foregroundStyle(Look.color(category: c.category))
                            .frame(width: 18)
                        Text(STCategory.title(c.category)).font(.callout)
                        Spacer()
                        Text("\(Int((Double(c.seconds) / Double(total) * 100).rounded()))%").font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        Text(STFormat.duration(c.seconds)).font(.callout).monospacedDigit().frame(minWidth: 58, alignment: .trailing)
                    }
                }
            }
        }
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

    private var sorted: [STReport.AppRow] {
        let sort = sorts.contains(model.sort) ? model.sort : .time
        switch sort {
        case .time: return r.apps.filter { $0.seconds > 0 }
        case .opens: return r.apps.filter { ($0.opens ?? 0) > 0 }.sorted { ($0.opens ?? 0) > ($1.opens ?? 0) }
        case .notifs: return r.apps.filter { ($0.notifs ?? 0) > 0 }.sorted { ($0.notifs ?? 0) > ($1.notifs ?? 0) }
        }
    }

    var body: some View {
        let list = sorted
        let shown = model.showAllApps ? list : Array(list.prefix(10))
        let sort = sorts.contains(model.sort) ? model.sort : .time
        let maxValue = max(1, list.map { metric($0, sort) }.max() ?? 1)
        Card(title: "Most used", trailing: sorts.count > 1 ? AnyView(
            Pills(items: sorts, selection: $model.sort, title: { s in
                switch s { case .time: "Screen time"; case .opens: "Opens"; case .notifs: "Notifications" }
            }, small: true)) : nil) {
            if list.isEmpty {
                Text(sort == .time ? "No apps used in this \(r.range == .day ? "day" : "week")." : "Nothing to count here.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            VStack(spacing: 2) {
                ForEach(shown) { app in
                    AppLine(model: model, app: app, sort: sort, fraction: Double(metric(app, sort)) / Double(maxValue))
                }
            }
            if list.count > 10 {
                Button(model.showAllApps ? "Show fewer" : "Show all \(list.count) apps") {
                    withAnimation(.snappy) { model.showAllApps.toggle() }
                }
                .buttonStyle(.plain)
                .font(.callout.weight(.medium))
                .foregroundStyle(Look.mobile)
            }
        }
    }

    private func metric(_ a: STReport.AppRow, _ s: AppSort) -> Int {
        switch s { case .time: a.seconds; case .opens: a.opens ?? 0; case .notifs: a.notifs ?? 0 }
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
            Button { withAnimation(.snappy) { model.expanded = open ? nil : app.id } } label: {
                HStack(spacing: 12) {
                    STAppIcon(image: model.icon(app.id), name: app.name, size: 30)
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(spacing: 6) {
                            Text(app.name).font(.callout.weight(.medium)).lineLimit(1)
                            ForEach(app.platforms, id: \.self) { p in
                                Image(systemName: Look.symbol(platform: p)).font(.system(size: 10)).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(valueText).font(.callout).monospacedDigit()
                        }
                        ShareBar(fraction: fraction, color: Look.color(category: app.category).opacity(0.9))
                    }
                    Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold)).foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .padding(.vertical, 7).padding(.horizontal, 8)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.primary.opacity(hover || open ? 0.05 : 0)))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { hover = $0 }
            if open { AppDetail(model: model, app: app).padding(.leading, 50).padding(.trailing, 8).padding(.bottom, 10) }
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
        HStack(alignment: .top, spacing: 22) {
            Chart {
                ForEach(Array(app.week.enumerated()), id: \.offset) { i, s in dayBar(i, s, r) }
            }
            .chartXScale(domain: -0.5...6.5)
            .chartXAxis {
                AxisMarks(values: Array(0..<7)) { v in
                    AxisValueLabel { if let i = v.as(Int.self), i < r.weekDays.count { Text(r.weekDays[i].formatted(.dateTime.weekday(.narrow))).font(.caption2) } }
                }
            }
            .chartYAxis(.hidden)
            .frame(width: 180, height: 70)
            VStack(alignment: .leading, spacing: 6) {
                Fact(label: "Daily average this week", value: STFormat.duration(weekTotal / elapsed))
                if let o = app.opens { Fact(label: r.range == .day ? "Opened" : "Opened this week", value: "\(o) times") }
                if let n = app.notifs { Fact(label: "Notifications", value: "\(n)") }
            }
            VStack(alignment: .leading, spacing: 6) {
                Fact(label: "Category", value: STCategory.title(app.category))
                Fact(label: "Used on", value: app.platforms.map(platformName).joined(separator: ", "))
            }
            Spacer(minLength: 0)
        }
    }

    private func dayBar(_ i: Int, _ seconds: Int, _ r: STReport) -> some ChartContent {
        let x0: Double = Double(i) - 0.28
        let x1: Double = Double(i) + 0.28
        let h: Double = Double(seconds) / 3600
        let color: Color = Look.color(category: app.category).opacity(isSelected(i, r) ? 1 : 0.4)
        return RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1), yStart: .value("Bottom", 0.0), yEnd: .value("Hours", h))
            .foregroundStyle(color)
            .cornerRadius(2)
    }

    private func isSelected(_ i: Int, _ r: STReport) -> Bool {
        r.range == .week || (i < r.weekDays.count && model.calendar.isDate(r.weekDays[i], inSameDayAs: r.start))
    }

    private func platformName(_ p: String) -> String {
        switch p { case "android": "Android"; case "ios": "iPhone"; case "macos": "Mac"; case "windows": "Windows"; case "linux": "Linux"; default: p }
    }
}

private struct Fact: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.callout.weight(.medium))
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
                .fill(Color.primary.opacity(0.1))
                .frame(width: size, height: size)
                .overlay(Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.45, weight: .semibold, design: .rounded)))
        }
    }
}

// MARK: Access and empty states

private struct AccessCard: View {
    let model: ScreenTimePageModel
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "clock.arrow.circlepath").font(.system(size: 20))
                .frame(width: 42, height: 42)
                .background(Circle().fill(Color.primary.opacity(0.08)))
            VStack(alignment: .leading, spacing: 3) {
                Text("Fill in this Mac's whole day").font(.callout.weight(.semibold))
                Text("This Mac only counts from when AirBridge started. Turn on Full Disk Access for AirBridge and it reads the records macOS keeps for its own Screen Time: today from midnight and the weeks before. Nothing leaves your devices.")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 6) {
                Button("Open Full Disk Access") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles") { NSWorkspace.shared.open(url) }
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(.black)
                Button("Check again") { model.recheckAccess() }.buttonStyle(.plain).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: Look.radius, style: .continuous).fill(Look.card))
        .overlay(RoundedRectangle(cornerRadius: Look.radius, style: .continuous).strokeBorder(Look.stroke))
    }
}

private struct EmptyPage: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "hourglass").font(.system(size: 40, weight: .light)).foregroundStyle(.secondary)
            Text("No screen time yet").font(.title2.weight(.semibold))
            Text("Your phone sends its screen time once AirBridge on it has Usage access. This Mac counts its own when \"This Mac's screen time\" is on in Settings.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 440)
        }
        .frame(maxWidth: .infinity, minHeight: 420)
    }
}
