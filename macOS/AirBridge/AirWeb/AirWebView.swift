import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// AirWeb: the QR code and link on the left, what is offered and what came in on the right.
struct AirWebView: View {
    @ObservedObject var session: AirWebSession
    @State private var dropping = false
    @State private var copied = false

    var body: some View {
        HStack(spacing: 0) {
            connectPane
                .frame(width: 320)
                .frame(maxHeight: .infinity, alignment: .top)
            Divider()
            VStack(spacing: 0) {
                ScrollView {
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        VStack(alignment: .leading, spacing: 20) {
                            peersSection
                            offeredSection(now: ctx.date)
                            receivedSection(now: ctx.date)
                        }
                        .padding(20)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                Divider()
                bottomBar
            }
        }
        .frame(minWidth: 700, idealWidth: 760, minHeight: 480, idealHeight: 540)
        .overlay { if dropping { dropOverlay } }
        .onDrop(of: [.fileURL], isTargeted: $dropping, perform: drop)
    }

    // MARK: Left: how to connect

    @ViewBuilder private var connectPane: some View {
        VStack(spacing: 14) {
            if session.failed {
                stateCard(icon: "exclamationmark.triangle", title: "Couldn't start AirWeb",
                          text: "Another app may be using the network ports. Try again.") {
                    Button("Try Again") { session.startServer() }
                }
            } else if session.address == nil {
                stateCard(icon: "wifi.slash", title: "Join a Wi-Fi network",
                          text: "Connect this Mac to Wi-Fi or Ethernet. The other device must be on the same network.") {
                    Button("Open Network Settings") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Network-Settings.extension") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                }
            } else if let link = session.link {
                if let qr = session.qr {
                    Image(qr, scale: 1, label: Text("QR code for the AirWeb link"))
                        .interpolation(.none)
                        .resizable()
                        .aspectRatio(1, contentMode: .fit)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: Theme.radius).fill(.white))
                        .overlay(RoundedRectangle(cornerRadius: Theme.radius).strokeBorder(Color.primary.opacity(0.08)))
                        .frame(width: 232, height: 232)
                }
                linkRow(link)
                Text("Scan this with a camera and open the link. Everyone who joins shares the same files. All devices must be on the same Wi-Fi.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Label(session.deviceName, systemImage: "laptopcomputer")
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                if session.addresses.count > 1 {
                    Button("Use Another Address") { session.nextAddress() }
                        .controlSize(.small)
                        .help("This Mac is on more than one network. Show the link for the next one.")
                }
            } else {
                ProgressView().controlSize(.small).padding(.top, 120)
            }
        }
        .padding(24)
    }

    private func linkRow(_ link: String) -> some View {
        VStack(spacing: Theme.sm) {
            Text(link)
                .font(.callout)
                .multilineTextAlignment(.center)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
            Button {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.setString(link, forType: .string)
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
            } label: {
                Label(copied ? "Copied" : "Copy Link", systemImage: copied ? "checkmark" : "doc.on.doc")
                    .frame(minWidth: 90)
            }
            .controlSize(.small)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: Theme.radius).fill(.quaternary.opacity(0.5)))
    }

    private func stateCard<A: View>(icon: String, title: String, text: String, @ViewBuilder action: () -> A) -> some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 30)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(text)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            action().padding(.top, Theme.xs)
        }
        .padding(.top, 80)
    }

    // MARK: Right: who is here, files

    /// Everyone connected shares one pool: this Mac's files and everything browsers upload.
    private var peersSection: some View {
        VStack(alignment: .leading, spacing: Theme.sm) {
            SectionHeader(title: "Connected")
            card {
                let names = session.peers.map(\.label)
                VStack(alignment: .leading, spacing: 2) {
                    Text(names.isEmpty ? "No one connected yet" : "\(names.count) \(names.count == 1 ? "device" : "devices") connected")
                    Text(names.isEmpty ? "Everyone who joins sees the same files and can add or download."
                         : names.prefix(4).joined(separator: " · ") + (names.count > 4 ? " · +\(names.count - 4)" : ""))
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, Theme.sm)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func offeredSection(now: Date) -> some View {
        VStack(alignment: .leading, spacing: Theme.sm) {
            SectionHeader(title: "Shared by this Mac")
            card {
                if session.offered.isEmpty {
                    emptyRow("Nothing added", "Everyone connected can add files too. Add some here to share them.")
                } else {
                    ForEach(Array(session.offered.enumerated()), id: \.element.id) { i, f in
                        if i > 0 { Divider() }
                        offeredRow(f, now: now)
                    }
                }
            }
        }
    }

    private func offeredRow(_ f: AirWebSession.Offered, now: Date) -> some View {
        let parts = [f.size >= 0 ? formatBytes(f.size) : nil, f.downloads > 0 ? "Downloaded" : nil, airWebAgo(f.added, now: now)]
        return HStack(spacing: 10) {
            Image(systemName: "doc").foregroundStyle(.secondary).frame(width: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text(f.name).lineLimit(1).truncationMode(.middle)
                Text(parts.compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: Theme.sm)
            Button { session.remove(f.id) } label: {
                Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).frame(width: 22, height: 22)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
            .help("Remove")
            .accessibilityLabel("Remove \(f.name)")
        }
        .padding(.vertical, Theme.sm)
        .accessibilityElement(children: .combine)
    }

    private func receivedSection(now: Date) -> some View {
        VStack(alignment: .leading, spacing: Theme.sm) {
            SectionHeader(title: "Added by others")
            card {
                if session.received.isEmpty {
                    emptyRow("Nothing yet", "Files that others add appear here and in Downloads › AirBridge Transfers.")
                } else {
                    let list = Array(session.received.reversed())
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, r in
                        if i > 0 { Divider() }
                        receivedRow(r, now: now)
                    }
                }
            }
        }
    }

    @ViewBuilder private func receivedRow(_ r: AirWebSession.Received, now: Date) -> some View {
        if let url = r.saved {
            HStack(spacing: 0) {
                Button { NSWorkspace.shared.activateFileViewerSelecting([url]) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "doc").foregroundStyle(.secondary).frame(width: 20)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(r.name).lineLimit(1).truncationMode(.middle)
                            let parts = [formatBytes(r.size), r.by.map { "from \($0)" }, airWebAgo(r.finished ?? r.started, now: now),
                                         r.shared ? nil : "Not shared"]
                            Text(parts.compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: Theme.sm)
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    }
                    .padding(.vertical, Theme.xs)
                }
                .buttonStyle(MenuRowButtonStyle())
                .help("Show in Finder")
                .accessibilityHint("Shows the file in Finder")
                // Uploaded files are shared with everyone connected until you stop sharing them here.
                if r.shared {
                    Button { session.stopSharing(r.id) } label: {
                        Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).frame(width: 22, height: 22)
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Stop sharing with the others")
                    .accessibilityLabel("Stop sharing \(r.name)")
                }
            }
            .padding(.horizontal, -Theme.sm)
            .padding(.vertical, Theme.xs)
        } else {
            HStack(spacing: 10) {
                Image(systemName: r.failed ? "exclamationmark.circle" : "arrow.down.circle")
                    .foregroundStyle(.secondary).frame(width: 20)
                VStack(alignment: .leading, spacing: 3) {
                    Text(r.name).lineLimit(1).truncationMode(.middle)
                    if r.failed {
                        Text("Didn't finish · \(airWebAgo(r.finished ?? r.started, now: now))")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        let pct = r.size > 0 ? Int(r.done * 100 / r.size) : 0
                        Text("Receiving · \(pct)% of \(formatBytes(r.size))")
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        AirWebBar(value: r.size > 0 ? Double(r.done) / Double(r.size) : 0)
                    }
                }
            }
            .padding(.vertical, Theme.sm)
            .accessibilityElement(children: .combine)
        }
    }

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(.horizontal, 12)
            .padding(.vertical, Theme.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Theme.radius).fill(.quaternary.opacity(0.5)))
    }

    private func emptyRow(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(text).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, Theme.sm)
    }

    // MARK: Bottom bar, adding files

    private var bottomBar: some View {
        HStack(spacing: Theme.sm) {
            Text(session.notice ?? "You can also drop files on this window.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            Spacer(minLength: Theme.sm)
            Button("Add Files…", action: pickFiles)
            Button("Stop") { AirWebWindow.shared.stop() }
        }
        .controlSize(.large)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private var dropOverlay: some View {
        RoundedRectangle(cornerRadius: 12)
            .strokeBorder(Color.primary.opacity(0.35), style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.primary.opacity(0.04)))
            .overlay(Text("Drop to send").font(.headline).foregroundStyle(.secondary))
            .padding(8)
            .allowsHitTesting(false)
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.prompt = "Add"
        panel.message = "Choose files for the other device to download"
        let s = session
        panel.begin { response in
            if response == .OK { s.add(panel.urls) }
        }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        let s = session
        var accepted = false
        for p in providers where p.canLoadObject(ofClass: URL.self) {
            accepted = true
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                guard let url, url.isFileURL else { return }
                Task { @MainActor in s.add([url]) }
            }
        }
        return accepted
    }
}

/// Thin progress bar in neutral greys.
struct AirWebBar: View {
    let value: Double

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.1))
                Capsule().fill(Color.primary.opacity(0.55)).frame(width: g.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}
