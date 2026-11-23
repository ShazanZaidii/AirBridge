import AppIntents
import Charts
import SwiftUI
import WidgetKit

// AirBridge's own widgets. The data comes from the app through the shared app group (Widgets/Shared).

@main
struct AirBridgeWidgets: WidgetBundle {
    var body: some Widget {
        ScreenTimeWidget()
    }
}

// MARK: - Choosing a device

/// A device the widget can show, or "All devices".
struct DeviceEntity: AppEntity {
    static let allId = "all"
    let id: String
    let name: String
    let platform: String

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Device" }
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
    static var defaultQuery = DeviceQuery()

    static let all = DeviceEntity(id: allId, name: "All devices", platform: "all")
}

struct DeviceQuery: EntityQuery {
    func entities(for identifiers: [DeviceEntity.ID]) async throws -> [DeviceEntity] {
        try await suggestedEntities().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [DeviceEntity] {
        [DeviceEntity.all] + STStore.load().devices.map { DeviceEntity(id: $0.id, name: $0.name, platform: $0.platform) }
    }

    func defaultResult() async -> DeviceEntity? { .all }
}

struct SelectDeviceIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "Screen Time" }
    static var description: IntentDescription { "Screen time from your devices." }

    @Parameter(title: "Device")
    var device: DeviceEntity?
}

// MARK: - Timeline

struct ScreenTimeEntry: TimelineEntry {
    let date: Date
    let summary: STSummary
    /// PNG data for the top apps' icons, by app id.
    let icons: [String: Data]
    let isEmpty: Bool
    /// The device picked in the widget's settings (nil: all), for the page a click opens.
    var deviceId: String? = nil
}

struct ScreenTimeProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ScreenTimeEntry { Self.sample() }

    func snapshot(for configuration: SelectDeviceIntent, in context: Context) async -> ScreenTimeEntry {
        let entry = Self.entry(for: configuration, now: Date())
        return entry.isEmpty && context.isPreview ? Self.sample() : entry
    }

    func timeline(for configuration: SelectDeviceIntent, in context: Context) async -> Timeline<ScreenTimeEntry> {
        let now = Date()
        // Stale after 5 minutes: when the widget is next shown (Notification Center opened), macOS asks again and it draws the
        // latest numbers the app has saved. The app itself only asks for a redraw about hourly, or at once when someone opens
        // AirBridge's Screen Time page or menu.
        return Timeline(entries: [Self.entry(for: configuration, now: now)], policy: .after(now.addingTimeInterval(5 * 60)))
    }

    static func entry(for configuration: SelectDeviceIntent, now: Date) -> ScreenTimeEntry {
        let snap = STStore.load()
        let id = configuration.device?.id
        let summary = STSummary.make(snap, deviceId: id == nil || id == DeviceEntity.allId ? nil : id, now: now)
        var icons: [String: Data] = [:]
        if let folder = ScreenTimeShared.folder() {
            for app in summary.topApps {
                if let d = try? Data(contentsOf: ScreenTimeShared.iconURL(appId: app.id, in: folder)) { icons[app.id] = d }
            }
        }
        return ScreenTimeEntry(date: now, summary: summary, icons: icons, isEmpty: snap.devices.isEmpty,
                               deviceId: id == DeviceEntity.allId ? nil : id)
    }

    /// What the widget gallery shows before any device has reported.
    static func sample() -> ScreenTimeEntry {
        let cal = Calendar.current
        let now = Date()
        var snap = STSnapshot()
        let values = [5.2, 6.1, 4.4, 7.0, 6.3, 5.0, 8.2, 6.8, 5.9, 7.4, 6.6, 9.1, 7.2, 8.4]
        let days = values.enumerated().map { i, h in
            STDay(day: STSummary.dayKey(cal.date(byAdding: .day, value: -(values.count - 1 - i), to: now)!, calendar: cal),
                  seconds: Int(h * 3600), hours: nil,
                  apps: i == values.count - 1 ? [STApp(id: "a", name: "WhatsApp", seconds: 9000), STApp(id: "b", name: "Xcode", seconds: 7200),
                                                  STApp(id: "c", name: "YouTube", seconds: 5400), STApp(id: "d", name: "Safari", seconds: 3600),
                                                  STApp(id: "e", name: "Instagram", seconds: 2400), STApp(id: "f", name: "Finder", seconds: 600)] : nil)
        }
        snap.upsert(STDevice(id: "sample", name: "Your devices", platform: "android", updated: now, days: days))
        return ScreenTimeEntry(date: now, summary: STSummary.make(snap, deviceId: nil, now: now), icons: [:], isEmpty: false)
    }
}

// MARK: - Widget

struct ScreenTimeWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: ScreenTimeShared.widgetKind, intent: SelectDeviceIntent.self, provider: ScreenTimeProvider()) { entry in
            ScreenTimeView(entry: entry)
                .containerBackground(for: .widget) { WidgetTone.top }
                .widgetURL(STLink.page(device: entry.deviceId))
        }
        .configurationDisplayName("Screen Time")
        .description("Screen time from your phone, PCs and Macs, together.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Views

private let lineColor = Color(red: 0.36, green: 0.62, blue: 1.0)   // AirBridge blue

/// Two charcoal tones: a top and a slightly darker band behind the app list. Darker than Apple's Screen Time widget (the user
/// found its grey too light), lighter than near-black. White and a soft grey in light mode.
enum WidgetTone {
    static let top = Color(nsColor: NSColor(name: nil) { a in
        a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 30 / 255, green: 30 / 255, blue: 32 / 255, alpha: 1) : .white
    })
    static let band = Color(nsColor: NSColor(name: nil) { a in
        a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 28 / 255, green: 28 / 255, blue: 30 / 255, alpha: 1)
            : NSColor(srgbRed: 246 / 255, green: 246 / 255, blue: 248 / 255, alpha: 1)
    })
}

struct ScreenTimeView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ScreenTimeEntry

    var body: some View {
        if entry.isEmpty {
            EmptyStateView(compact: family == .systemSmall)
        } else {
            switch family {
            case .systemSmall: SmallView(s: entry.summary)
            case .systemMedium: MediumView(s: entry.summary)
            default: LargeView(entry: entry)
            }
        }
    }
}

private struct Header: View {
    let s: STSummary
    var big: CGFloat = 34
    var showDelta = true

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(STFormat.duration(s.today))
                    .font(.system(size: big, weight: .semibold, design: .rounded))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Text("Today · \(s.title)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if showDelta, let d = STFormat.delta(today: s.today, average: s.average) {
                DeltaChip(text: d, up: s.today > (s.average ?? 0))
            }
        }
    }
}

private struct DeltaChip: View {
    let text: String
    let up: Bool

    var body: some View {
        VStack(alignment: .trailing, spacing: 1) {
            Label(text, systemImage: up ? "arrow.up.right" : "arrow.down.right")
                .font(.caption.weight(.semibold))
                .labelStyle(.titleAndIcon)
            Text("vs your average").font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// The trend line: one point per day, a smooth line with a soft fill, the average as a dashed line and today marked.
/// On a first day (no trend yet) it shows today's running total by hour instead.
private struct TrendChart: View {
    let s: STSummary
    var axes = true

    private var points: [STSummary.Point] { s.trend.count >= 2 ? s.trend : s.todayCumulative }
    private var hourly: Bool { s.trend.count < 2 }

    var body: some View {
        let pts = points
        let maxH = max(1.0, (pts.map { Double($0.seconds) / 3600 }.max() ?? 1))
        Chart {
            ForEach(pts, id: \.date) { p in
                AreaMark(x: .value("Time", p.date), y: .value("Hours", Double(p.seconds) / 3600))
                    .interpolationMethod(.monotone)
                    .foregroundStyle(LinearGradient(colors: [lineColor.opacity(0.35), lineColor.opacity(0.02)], startPoint: .top, endPoint: .bottom))
                LineMark(x: .value("Time", p.date), y: .value("Hours", Double(p.seconds) / 3600))
                    .interpolationMethod(.monotone)
                    .foregroundStyle(lineColor)
                    .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }
            if !hourly, let avg = s.average {
                RuleMark(y: .value("Average", Double(avg) / 3600))
                    .foregroundStyle(.secondary.opacity(0.6))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
            if let last = pts.last {
                PointMark(x: .value("Time", last.date), y: .value("Hours", Double(last.seconds) / 3600))
                    .foregroundStyle(lineColor)
                    .symbolSize(axes ? 60 : 30)
            }
        }
        .chartYScale(domain: 0...(maxH * 1.15))
        .chartYAxis {
            if axes {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                    AxisGridLine().foregroundStyle(.secondary.opacity(0.25))
                    AxisValueLabel { if let h = v.as(Double.self) { Text(axisLabel(h)).font(.caption2) } }
                }
            }
        }
        .chartXScale(domain: xDomain, range: .plotDimension(padding: axes ? 14 : 4))
        .chartXAxis {
            if axes {
                let ticks = tickDates
                AxisMarks(values: ticks) { v in
                    // The current hour sits flush right (it is at the very edge, "now"); today's day fits centred.
                    let d = v.as(Date.self) ?? .distantPast
                    AxisValueLabel(anchor: hourly && d == ticks.last && ticks.count > 1 ? .topTrailing : .top) {
                        Text(label(d)).font(.caption2.weight(isNow(d) ? .semibold : .regular))
                    }
                }
            }
        }
    }

    private var cal: Calendar { .current }

    /// Hourly (a first day): midnight to now. Daily: the first day to today.
    private var xDomain: ClosedRange<Date> {
        let pts = points
        if hourly {
            let start = cal.startOfDay(for: pts.last?.date ?? Date())
            return start...max(start.addingTimeInterval(3600), pts.last?.date ?? start)
        }
        let first = pts.first?.date ?? Date(), last = pts.last?.date ?? first
        return first...max(last, first.addingTimeInterval(86_400))
    }

    /// Labels that end at "now": today's day, or the current hour, and step back from it so they fit.
    private var tickDates: [Date] {
        let pts = points
        if hourly {
            guard let last = pts.last?.date else { return [] }
            let start = cal.startOfDay(for: last)
            let hour = cal.component(.hour, from: last)
            return STText.ticksBack(from: hour, step: STText.step(span: hour + 1, maxLabels: 5))
                .compactMap { cal.date(byAdding: .hour, value: $0, to: start) }
        }
        return STText.ticksBack(from: pts.count - 1, step: STText.step(span: pts.count, maxLabels: 7)).map { pts[$0].date }
    }

    private func label(_ d: Date) -> String {
        hourly ? STText.hour(cal.component(.hour, from: d), h24: STText.uses24Hour(), calendar: cal)
               : d.formatted(.dateTime.weekday(.abbreviated))
    }

    private func isNow(_ d: Date) -> Bool {
        hourly ? cal.isDate(d, equalTo: Date(), toGranularity: .hour) : cal.isDateInToday(d)
    }
}

private struct SmallView: View {
    let s: STSummary
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Header(s: s, big: 26, showDelta: false)
            TrendChart(s: s, axes: false)
            if let top = s.topApps.first {
                Text("\(top.name) · \(STFormat.duration(top.seconds))").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }
}

private struct MediumView: View {
    let s: STSummary
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Header(s: s, big: 28)
            TrendChart(s: s)
        }
    }
}

private struct LargeView: View {
    let entry: ScreenTimeEntry
    var body: some View {
        let s = entry.summary
        VStack(alignment: .leading, spacing: 10) {
            Header(s: s)
            TrendChart(s: s).frame(height: 128)
            if !s.topApps.isEmpty {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], alignment: .leading, spacing: 10) {
                    ForEach(s.topApps, id: \.id) { app in
                        AppRow(app: app, icon: entry.icons[app.id])
                    }
                }
                .padding(.top, 12)
                .frame(maxHeight: .infinity, alignment: .top)
                .background(alignment: .top) { BandBackground() }
            } else {
                Spacer(minLength: 0)
            }
        }
    }
}

/// The darker band behind the app list, out to the widget's edges like Apple's.
private struct BandBackground: View {
    @Environment(\.widgetContentMargins) private var margins
    var body: some View {
        WidgetTone.band
            .padding(.leading, -margins.leading)
            .padding(.trailing, -margins.trailing)
            .padding(.bottom, -margins.bottom)
    }
}

private struct AppRow: View {
    let app: STSummary.TopApp
    let icon: Data?

    var body: some View {
        HStack(spacing: 9) {
            AppIcon(name: app.name, data: icon)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(app.name).font(.callout.weight(.medium)).lineLimit(1)
                    Image(systemName: platformSymbol(app.platform)).font(.system(size: 9)).foregroundStyle(.secondary)
                }
                Text(STFormat.duration(app.seconds)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
    }
}

private struct AppIcon: View {
    let name: String
    let data: Data?

    var body: some View {
        Group {
            if let data, let img = NSImage(data: data) {
                Image(nsImage: img).resizable().interpolation(.high).aspectRatio(contentMode: .fit)
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 7, style: .continuous).fill(.secondary.opacity(0.25))
                    Text(String(name.prefix(1)).uppercased()).font(.system(size: 14, weight: .semibold, design: .rounded))
                }
            }
        }
        .frame(width: 30, height: 30)
        .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
}

private struct EmptyStateView: View {
    let compact: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: "hourglass").font(compact ? .title3 : .title2).foregroundStyle(lineColor)
            Text("No screen time yet").font(compact ? .callout.weight(.semibold) : .headline)
            Text("Connect your phone or PC with AirBridge and it shows up here.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// An axis value in hours as a short label: "0", "20m", "1h", "1.5h", "8h".
func axisLabel(_ hours: Double) -> String {
    if hours <= 0 { return "0" }
    if hours < 1 { return "\(Int((hours * 60).rounded()))m" }
    let r = (hours * 2).rounded() / 2
    return r == r.rounded() ? "\(Int(r))h" : String(format: "%.1fh", r)
}

/// The same pictures the AirBridge battery entries use.
private func platformSymbol(_ platform: String) -> String {
    switch platform {
    case "android", "ios": return "smartphone"
    case "windows": return "pc"
    case "linux": return "terminal.fill"
    case "macos": return "laptopcomputer"
    default: return "desktopcomputer"
    }
}
