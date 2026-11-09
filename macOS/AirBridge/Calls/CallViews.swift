import AppKit
import Combine
import SwiftUI

// The incoming-call banner, the in-call window and its collapsed pill (protocol.md §13.3–13.5), modelled on how
// macOS shows a call from an iPhone: a notification-style glass banner with the caller's photo, name and round
// Decline / Accept buttons that stays until the call is answered or over, then a compact call window with the
// duration, Mute, Audio and End that floats for the whole call. No AirBridge services are used here so
// tools/callrender can draw them on their own.

/// What the call UI shows and what its buttons do. One per phone; owned by `CallPanels`.
@MainActor final class CallUI: ObservableObject, Identifiable {
    enum Action { case answer, answerOnPhone, decline, end, toggleMute, audioPhone, audioSpeaker, expand }

    nonisolated let phoneId: String
    nonisolated var id: String { phoneId }
    @Published var call: PhoneCall
    @Published var phoneName: String
    @Published var photo: NSImage?
    @Published var ended = false
    /// A short problem to show ("V2404 couldn't do that"), cleared after a few seconds.
    @Published var flash: String?
    /// This Mac is ringing for the call.
    @Published var ringingHere = false
    @Published var route: CallAudio.Route = .phone
    @Published var macMicMuted = false
    /// The audio is moving to this Mac right now.
    @Published var switching = false
    /// The Mac speaker in use (for the Audio button's icon while the call is on this Mac).
    @Published var macOutputSymbol: String?
    var perform: (Action) -> Void = { _ in }

    init(call: PhoneCall, phoneName: String) {
        self.phoneId = call.phoneId
        self.call = call
        self.phoneName = phoneName
    }

    var title: String { call.caller }
    var subtitle: String { CallerText.subtitle(label: call.name?.isEmpty == false ? call.label : nil, phoneName: phoneName) }
    /// Mute shows the microphone that is really in use: the Mac's while the call audio is here.
    var isMuted: Bool { route == .mac ? macMicMuted : call.muted }
    var canMute: Bool { route == .mac || call.can.contains("mute") }

    /// The phone route in use (nil when the audio is on this Mac).
    var phoneRoute: PhoneRoute? {
        guard route != .mac else { return nil }
        return RouteText.menuRoutes(call.routes).first { $0.id == call.route }
    }

    /// The Audio button's face: where the sound is now.
    var audioSymbol: String {
        if route == .mac { return macOutputSymbol ?? "laptopcomputer" }
        if let r = phoneRoute { return RouteText.symbol(r) }
        return call.speaker ? "speaker.wave.2" : "iphone"
    }

    var audioCaption: String {
        if route == .mac { return "This Mac" }
        if let r = phoneRoute { return RouteText.name(r, phoneName: phoneName) }
        return call.speaker ? "Speaker" : phoneName
    }

    /// Highlighted when the sound isn't at the phone's ear (speaker, a headset or this Mac), as on iPhone.
    var audioHighlighted: Bool {
        if route == .mac { return true }
        if let r = phoneRoute { return r.kind != "earpiece" }
        return call.speaker
    }
}

// MARK: Pieces

/// The caller's round photo, or their initials on the grey Contacts gradient, or a person glyph.
struct CallerAvatar: View {
    let photo: NSImage?
    let name: String?
    let size: CGFloat

    var body: some View {
        ZStack {
            if let photo {
                Image(nsImage: photo).resizable().interpolation(.high).scaledToFill()
            } else {
                LinearGradient(colors: [Color(red: 0.66, green: 0.68, blue: 0.73), Color(red: 0.52, green: 0.54, blue: 0.59)],
                               startPoint: .top, endPoint: .bottom)
                if let i = CallerText.initials(name) {
                    Text(i)
                        .font(.system(size: size * (i.count > 1 ? 0.40 : 0.44), weight: .medium, design: .rounded))
                        .foregroundStyle(.white)
                        .minimumScaleFactor(0.5)
                } else {
                    Image(systemName: "person.fill")
                        .font(.system(size: size * 0.5, weight: .regular))
                        .foregroundStyle(.white)
                        .offset(y: size * 0.03)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().strokeBorder(Color.black.opacity(0.06), lineWidth: 0.5))
        .accessibilityHidden(true)
    }
}

/// The round red / green call buttons.
struct RoundCallButton: View {
    enum Kind { case accept, decline, end }
    let kind: Kind
    let size: CGFloat
    var enabled = true
    let action: () -> Void

    private var color: Color { kind == .accept ? Color(nsColor: .systemGreen) : Color(nsColor: .systemRed) }
    private var symbol: String { kind == .accept ? "phone.fill" : "phone.down.fill" }
    private var label: String {
        switch kind { case .accept: "Accept"; case .decline: "Decline"; case .end: "End" }
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * (kind == .accept ? 0.42 : 0.40), weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(Circle().fill(color))
                .contentShape(Circle())
        }
        .buttonStyle(PressDimStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .help(label)
        .accessibilityLabel(label)
    }
}

/// Darkens a little while pressed, brightens a touch on hover (like system notification buttons).
struct PressDimStyle: ButtonStyle {
    @State private var hovering = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .brightness(configuration.isPressed ? -0.12 : (hovering ? 0.04 : 0))
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
            .onHover { hovering = $0 }
    }
}

// MARK: Incoming banner

/// The incoming call, as a notification-style banner (top right, above other windows, never takes focus). Like a
/// call on a Mac it has no close button: it stays until the call is answered, declined or over.
struct IncomingCallBanner: View {
    @ObservedObject var ui: CallUI

    static let width: CGFloat = 360
    static let corner: CGFloat = 22

    var body: some View {
        HStack(spacing: 11) {
            CallerAvatar(photo: ui.photo, name: ui.call.name, size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(ui.title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(ui.ended ? "Call Ended" : ui.subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if !ui.ended {
                HStack(spacing: 12) {
                    RoundCallButton(kind: .decline, size: 34, enabled: ui.call.can.contains("decline")) { ui.perform(.decline) }
                    RoundCallButton(kind: .accept, size: 34, enabled: ui.call.can.contains("answer")) { ui.perform(.answer) }
                }
                .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }
        }
        .padding(14)
        .frame(width: Self.width)
        .glassEffect(.regular, in: .rect(cornerRadius: Self.corner))
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: ui.ended)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Incoming call from \(ui.title), \(ui.subtitle)")
    }
}

// MARK: In-call window

/// The call in progress: who, how long, and Mute / Audio / End.
struct InCallView: View {
    @ObservedObject var ui: CallUI

    var body: some View {
        VStack(spacing: 0) {
            CallerAvatar(photo: ui.photo, name: ui.call.name, size: 72)
                .padding(.top, 34)
            Text(ui.title)
                .font(.system(size: 17, weight: .semibold))
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.horizontal, 20)
                .padding(.top, 12)
            CallStatusText(ui: ui)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .padding(.top, 2)
            if let f = ui.flash ?? (ui.switching ? "Moving the call to this Mac…" : nil) {
                Text(f)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 18)
                    .padding(.top, 6)
                    .transition(.opacity)
            }
            HStack(alignment: .top, spacing: 22) {
                ToggleCircle(symbol: "mic.slash.fill", title: "Mute", on: ui.isMuted,
                             enabled: !ui.ended && ui.canMute) { ui.perform(.toggleMute) }
                AudioRouteButton(ui: ui)
                VStack(spacing: 6) {
                    RoundCallButton(kind: .end, size: 50, enabled: !ui.ended && ui.call.can.contains("end")) { ui.perform(.end) }
                    Text("End").font(.system(size: 11)).foregroundStyle(.secondary)
                }
            }
            .padding(.top, 20)
            .padding(.bottom, 20)
            .opacity(ui.ended ? 0.4 : 1)
        }
        .frame(width: 288)
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: ui.flash)
        .animation(.easeOut(duration: 0.2), value: ui.ended)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Call with \(ui.title)")
    }
}

/// "0:42", "On Hold", "Call Ended"…
struct CallStatusText: View {
    @ObservedObject var ui: CallUI
    var body: some View {
        if ui.ended {
            Text("Call Ended")
        } else if ui.call.state == .held {
            Text("On Hold")
        } else if let since = ui.call.since {
            TimelineView(.periodic(from: since, by: 1)) { ctx in
                Text(CallerText.duration(ctx.date.timeIntervalSince(since))).monospacedDigit()
            }
        } else {
            Text(ui.call.state == .ringing ? "Incoming Call" : "Connecting…")
        }
    }
}

/// A grey round toggle with its title under it; filled when on (iOS / macOS call controls).
struct ToggleCircle: View {
    let symbol: String
    let title: String
    let on: Bool
    var enabled = true
    let action: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            Button(action: action) { ToggleCircleFace(symbol: symbol, on: on) }
                .buttonStyle(PressDimStyle())
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.45)
                .help(title)
                .accessibilityLabel(title)
                .accessibilityValue(on ? "On" : "Off")
            Text(title).font(.system(size: 11)).foregroundStyle(.secondary)
        }
    }
}

struct ToggleCircleFace: View {
    let symbol: String
    let on: Bool
    var size: CGFloat = 50
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.38, weight: .medium))
            .foregroundStyle(on ? Color(nsColor: .windowBackgroundColor) : Color.primary)
            .frame(width: size, height: size)
            .background(Circle().fill(on ? Color.primary.opacity(0.88) : Color.primary.opacity(0.10)))
            .contentShape(Circle())
            .animation(.easeOut(duration: 0.15), value: on)
    }
}

/// "Audio": opens the native audio menu (this Mac's microphones and speakers, the phone's earpiece, speaker and
/// headsets) under the button.
struct AudioRouteButton: View {
    @ObservedObject var ui: CallUI
    @State private var anchor: NSView?
    @State private var menu: AudioRouteMenu?

    var body: some View {
        VStack(spacing: 6) {
            Button {
                guard let anchor else { return }
                let m = menu ?? AudioRouteMenu(ui: ui)
                menu = m
                m.popUp(below: anchor)
            } label: {
                ToggleCircleFace(symbol: ui.audioSymbol, on: ui.audioHighlighted)
            }
            .buttonStyle(PressDimStyle())
            .background(MenuAnchor { anchor = $0 })
            .disabled(ui.ended)
            .help("Audio")
            .accessibilityLabel("Audio, \(ui.audioCaption)")
            Text(ui.audioCaption)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: 76)
        }
    }
}

// MARK: Collapsed pill

/// The call while its window is closed: a small glass pill at the top right that stays for the whole call (as
/// macOS keeps a call present). Click it to bring the window back; Mute and End work from here.
struct CallPillView: View {
    @ObservedObject var ui: CallUI

    static let width: CGFloat = 264

    var body: some View {
        HStack(spacing: 8) {
            Button { ui.perform(.expand) } label: {
                HStack(spacing: 9) {
                    CallerAvatar(photo: ui.photo, name: ui.call.name, size: 30)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(ui.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                        CallStatusText(ui: ui).font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Show the call")
            .accessibilityLabel("Call with \(ui.title). Show the call")
            Button { ui.perform(.toggleMute) } label: { ToggleCircleFace(symbol: "mic.slash.fill", on: ui.isMuted, size: 30) }
                .buttonStyle(PressDimStyle())
                .disabled(ui.ended || !ui.canMute)
                .help(ui.isMuted ? "Unmute" : "Mute")
                .accessibilityLabel("Mute")
                .accessibilityValue(ui.isMuted ? "On" : "Off")
            RoundCallButton(kind: .end, size: 30, enabled: !ui.ended && ui.call.can.contains("end")) { ui.perform(.end) }
        }
        .padding(6)
        .frame(width: Self.width)
        .glassEffect(.regular, in: .capsule)
        .accessibilityElement(children: .contain)
    }
}
