import AVFoundation
import Combine
import CoreAudio
import CoreBluetooth
import Foundation
import IOBluetooth

/// What a phone told us about its Bluetooth (AirBridge `call_audio_state`, PROTOCOL §13.4).
nonisolated struct PhoneBluetoothInfo: Equatable, Sendable {
    var bluetoothOn: Bool?
    var name: String?
    var headset: String?
}

/// The implementation behind `CallAudio`: installs its hooks, feeds `CallAudioPolicy` with what Bluetooth,
/// the phone and the call window say, and carries out what the policy decides (hands-free link, audio
/// connection, audio bridge). Bluetooth is not touched until macOS allows AirBridge to use it, so nobody sees
/// a Bluetooth prompt before opening "Talk from your Mac".
@MainActor final class CallAudioController {
    static let shared = CallAudioController()

    /// Paired phone chosen in the setup (Bluetooth address, "aa-bb-cc-dd-ee-ff").
    static let phoneAddressKey = "callAudio.phoneAddress"
    /// Its Bluetooth name (to leave its audio device out of the Mac device lists from any thread).
    static let phoneNameKey = "callAudio.phoneName"
    /// `hello.caps`: we carry call audio over Bluetooth hands-free and want `call_audio_state` (§13.4).
    static let cap = "call_audio"
    /// A phone that sends `call_audio_state` and takes `call_audio_hello`.
    static let phoneCap = "call_audio_info"

    private var policy = CallAudioPolicy(bluetoothOn: true, phonePaired: false)
    /// One link object per phone address, kept for the app's lifetime (IOBluetooth's delegate is unretained).
    private var links: [String: HandsFreeLink] = [:]
    private var linkAddress: String?
    private var link: HandsFreeLink? { linkAddress.flatMap { links[$0] } }
    private let bridge = CallAudioBridge()
    private var idleTimer: Timer?
    private var graceTimer: Timer?
    private var callsSub: AnyCancellable?
    private var pollTimer: Timer?
    private var bridgeRetry: Timer?
    private var peersSub: AnyCancellable?
    private var infos: [String: PhoneBluetoothInfo] = [:]
    private var standbyTries = 0
    /// The paired phone's call, to ask the phone itself to move the audio here.
    private var currentCall: PhoneCall?
    private var openFallback: Timer?
    private var userWantsMac = false
    private static let maxStandbyTries = 3
    private var helloSent: [String: String] = [:]
    private var installed = false

    /// How long a link may stay up with no call (the answer may still be on its way to the phone).
    static let idleTimeout: TimeInterval = 20
    /// How long audio the phone opens by itself is refused after the link comes up, after answering on the
    /// phone, or after moving audio back to the phone (Android's automatic routing happens within ~1–2 s).
    static let graceWindow: TimeInterval = 5

    func install() {
        guard !installed else { return }
        installed = true
        let ca = CallAudio.shared
        ca.onUseMac = { [weak self] in self?.useMac() }
        ca.onUsePhone = { [weak self] in self?.apply(.usePhone) }
        ca.onSetMacMicMuted = { [weak self] m in self?.apply(.setMicMuted(m)) }
        ca.onCallEnded = { [weak self] in self?.apply(.callEnded) }
        ca.onOpenSetup = { TalkFromMacWindow.shared.show() }
        bridge.onFailure = { [weak self] in self?.apply(.bridgeFailed) }
        ca.onSelectMacInput = { [weak self] uid in self?.selectMacDevice(uid, input: true) }
        ca.onSelectMacOutput = { [weak self] uid in self?.selectMacDevice(uid, input: false) }
        bridge.deviceResolver = { phone, fallback in
            let name = UserDefaults.standard.string(forKey: CallAudioController.phoneNameKey)
            return MacAudioDevices.callDevices(excluding: phone, phoneName: name, fallback: fallback)
        }
        MacAudioDevices.shared.onChange = { [weak self] in self?.publishDevices() }
        MacAudioDevices.shared.start()
        // The phone's call (AirBridge call_state): ringing/active brings up the standby link for its dialer.
        callsSub = PhoneNotifCenter.shared.$calls.sink { [weak self] calls in
            DispatchQueue.main.async { self?.callsChanged(calls) }
        }
        peersSub = AppModel.shared.$readyPeers.sink { [weak self] peers in
            DispatchQueue.main.async { self?.peersChanged(peers) }
        }
        // Light housekeeping: Bluetooth power and pairings change without notifications we can rely on.
        pollTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        pollTimer?.tolerance = 3
        refresh()
        publishDevices()
    }

    // MARK: Bluetooth facts

    static var bluetoothAllowed: Bool { CBManager.authorization == .allowedAlways }

    static var bluetoothOn: Bool {
        guard bluetoothAllowed, let host = IOBluetoothHostController.default() else { return false }
        return host.powerState == kBluetoothHCIPowerStateON
    }

    /// Paired devices that look like phones (hands-free gateway, or the "phone" device class).
    static func pairedPhones() -> [IOBluetoothDevice] {
        guard bluetoothAllowed else { return [] }
        let all = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        return all.filter { $0.isHandsFreeAudioGateway || $0.deviceClassMajor == BluetoothDeviceClassMajor(kBluetoothDeviceClassMajorPhone) }
    }

    static func normalized(_ address: String?) -> String { (address ?? "").lowercased().filter(\.isHexDigit) }

    /// Names AirBridge phones gave for their Bluetooth (to match a paired device without asking).
    var phoneBluetoothNames: [String] { infos.values.compactMap(\.name) }

    /// The paired Bluetooth device that is the AirBridge phone: the one picked in the setup, else the one whose
    /// name a connected AirBridge phone reported, else the only paired phone.
    func chosenPhone() -> IOBluetoothDevice? {
        let phones = Self.pairedPhones()
        if let saved = UserDefaults.standard.string(forKey: Self.phoneAddressKey),
           let d = phones.first(where: { Self.normalized($0.addressString) == Self.normalized(saved) }) { return d }
        let names = phoneBluetoothNames.map { $0.lowercased() }
        if let d = phones.first(where: { names.contains(($0.name ?? "").lowercased()) }) { return d }
        return phones.count == 1 ? phones[0] : nil
    }

    /**
     * The phone's "other headset", unless it is really this Mac: some phones (vivo) label every Bluetooth call device
     * with the phone's own model name, so older AirBridge builds reported this Mac's own link as a headset named
     * after the phone. That made the Mac drop its link, see the "headset" vanish, reconnect, and so on.
     */
    private func realHeadset(_ info: PhoneBluetoothInfo, phone: IOBluetoothDevice?) -> String? {
        guard let h = info.headset?.trimmingCharacters(in: .whitespaces), !h.isEmpty else { return nil }
        let own = [info.name, phone?.name] + AppModel.shared.readyPeers.filter { $0.caps.contains(Self.phoneCap) }.map { Optional($0.name) }
        if own.contains(where: { $0?.caseInsensitiveCompare(h) == .orderedSame }) { return nil }
        return h
    }

    func choosePhone(address: String) {
        UserDefaults.standard.set(address, forKey: Self.phoneAddressKey)
        refresh()
    }

    /// Info from the AirBridge phone that matches the chosen Bluetooth device (or the only one that sent any).
    private func infoForChosen(_ device: IOBluetoothDevice?) -> PhoneBluetoothInfo? {
        if let n = device?.name?.lowercased(),
           let hit = infos.values.first(where: { $0.name?.lowercased() == n }) { return hit }
        return infos.count == 1 ? infos.values.first : nil
    }

    /// Re-reads Bluetooth access, power and pairing and tells the policy what changed.
    func refresh() {
        let allowed = Self.bluetoothAllowed
        var cmds = policy.handle(.bluetoothAccess(allowed || CBManager.authorization == .notDetermined))
        cmds += policy.handle(.micAllowed(AVCaptureDevice.authorizationStatus(for: .audio) == .authorized))
        if allowed {
            cmds += policy.handle(.bluetoothPower(on: Self.bluetoothOn))
            let phone = chosenPhone()
            if let n = phone?.name, UserDefaults.standard.string(forKey: Self.phoneNameKey) != n {
                UserDefaults.standard.set(n, forKey: Self.phoneNameKey)
            }
            cmds += policy.handle(.phonePaired(phone != nil))
            if let phone, let addr = phone.addressString, linkAddress != addr, policy.link == .down {
                useLink(address: addr)
            }
            if let info = infoForChosen(phone) {
                cmds += policy.handle(.phoneInfo(bluetoothOn: info.bluetoothOn, headset: realHeadset(info, phone: phone)))
            }
            sendHellos()
        } else {
            cmds += policy.handle(.phonePaired(false))
        }
        run(cmds)
        publish()
    }

    private func useLink(address: String) {
        if links[address] == nil, let l = BluetoothHandsFreeLink(address: address) {
            l.onEvent = { [weak self, weak l] e in
                // Events from a phone that is no longer the chosen one are only allowed to bring it down.
                guard let self, let l, l.address == self.linkAddress else { l?.disconnect(); return }
                self.apply(e)
            }
            links[address] = l
        }
        linkAddress = links[address] == nil ? nil : address
    }

    // MARK: Actions

    private func useMac() {
        refresh()
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            proceedUseMac()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { ok in
                DispatchQueue.main.async { [weak self] in ok ? self?.proceedUseMac() : self?.apply(.micDenied) }
            }
        default:
            apply(.micDenied)
        }
    }

    private func proceedUseMac() {
        if policy.link == .down { bridge.rememberDefaults() }
        userWantsMac = true
        defer { userWantsMac = false }
        apply(.useMac)
    }

    func apply(_ e: CallAudioPolicy.Event) {
        let before = policy
        let cmds = policy.handle(e)
        if before != policy || !cmds.isEmpty {
            CallAudioLog.shared.log("policy: \(e) -> \(cmds.isEmpty ? "-" : cmds.map { "\($0)" }.joined(separator: ", ")) | route \(policy.route)")
        }
        run(cmds)
        publish()
        // The phone's own audio device comes and goes with the link: keep it out of the Mac device lists.
        if before.link != policy.link || before.bridging != policy.bridging { publishDevices() }
    }

    private func run(_ cmds: [CallAudioPolicy.Command]) {
        for c in cmds {
            switch c {
            case .connectLink:
                // The standby link for a call: a few tries at most, so a phone that keeps dropping it (or a call
                // that isn't on it) can't loop. Using the Mac on purpose always tries.
                if !userWantsMac && policy.phoneCall != .none {
                    standbyTries += 1
                    if standbyTries > Self.maxStandbyTries {
                        if standbyTries == Self.maxStandbyTries + 1 { CallAudioLog.shared.log("link: no more standby tries for this call") }
                        continue
                    }
                }
                if link == nil, let addr = chosenPhone()?.addressString { useLink(address: addr) }
                if let link { link.connect() } else { apply(.linkFailed) }
            case .disconnectLink: link?.disconnect()
            case .openAudio:
                // Ask the phone to send the call audio here, as picking this Mac in its call screen does: phones
                // often refuse audio the Mac opens itself (vivo: "unsupported"), never their own pick. The Mac's
                // own request follows only if nothing came.
                openFallback?.invalidate(); openFallback = nil
                if let c = currentCall, c.can.contains("route") {
                    PeerBus.shared.send(to: c.phoneId, PhoneNotifWire.callRoute(c.id, dev: "computer"))
                    CallAudioLog.shared.log("audio: asked the phone to route the call here")
                    openFallback = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
                        MainActor.assumeIsolated {
                            guard let self, self.policy.wantMac, self.policy.audio != .open else { return }
                            self.link?.openAudio()
                        }
                    }
                } else {
                    link?.openAudio()
                }
            case .closeAudio: link?.closeAudio()
            case .refuseIncomingAudio(let r): link?.refuseIncomingAudio(r)
            case .startBridge: startBridge(attempt: 0)
            case .stopBridge:
                bridgeRetry?.invalidate(); bridgeRetry = nil
                bridge.stop()
            case .setBridgeMuted(let m): bridge.setMicMuted(m)
            case .armIdleTimer:
                idleTimer?.invalidate()
                idleTimer = Timer.scheduledTimer(withTimeInterval: Self.idleTimeout, repeats: false) { [weak self] _ in
                    MainActor.assumeIsolated { self?.apply(.idleTimeout) }
                }
            case .cancelIdleTimer:
                idleTimer?.invalidate(); idleTimer = nil
            case .armGrace:
                graceTimer?.invalidate()
                graceTimer = Timer.scheduledTimer(withTimeInterval: Self.graceWindow, repeats: false) { [weak self] _ in
                    MainActor.assumeIsolated { self?.apply(.graceOver) }
                }
            }
        }
    }

    /// The phone's Core Audio device shows up shortly after the audio connection opens; look for it for up to
    /// three seconds. If none appears, macOS is carrying the audio itself (logged; the route stays Mac).
    private func startBridge(attempt: Int) {
        bridgeRetry?.invalidate(); bridgeRetry = nil
        guard policy.bridging, let addr = link?.address else { return }
        let name = IOBluetoothDevice(addressString: addr)?.name
        if let devs = CoreAudioDevices.phoneDevices(address: addr, name: name) {
            do {
                try bridge.start(phoneInput: devs.input, phoneOutput: devs.output)
                bridge.setMicMuted(policy.micMuted)
                publishDevices()
            } catch {
                CallAudioLog.shared.log("bridge: start failed: \(error)")
                apply(.bridgeFailed)
            }
            return
        }
        guard attempt < 12 else {
            CallAudioLog.shared.log("bridge: no Core Audio device for the phone after 3 s; leaving the audio to macOS")
            return
        }
        bridgeRetry = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.startBridge(attempt: attempt + 1) }
        }
    }

    private func publish() {
        let ca = CallAudio.shared
        switch policy.route {
        case .unavailable(let reason): ca.report(route: .unavailable(reason: reason))
        case .phone: ca.report(route: .phone)
        case .mac: ca.report(route: .mac)
        }
        ca.report(macMicMuted: policy.micMuted)
        ca.report(note: policy.note)
        ca.report(switching: policy.switching)
    }

    // MARK: The phone's call (AirBridge call_state)

    /// The AirBridge peer whose calls belong to the chosen Bluetooth phone: the one whose reported Bluetooth
    /// name matches, else the only phone with a call.
    private func callsChanged(_ calls: [String: PhoneCall]) {
        let chosenName = UserDefaults.standard.string(forKey: Self.phoneNameKey)?.lowercased()
        let matched = calls.first { id, _ in chosenName != nil && infos[id]?.name?.lowercased() == chosenName }?.value
        // Unmatched, a lone call counts only when one phone is connected at all: a call on another phone (a second
        // phone, an emulator) must never bring up the link to the paired one (it would connect, see no call, drop).
        let phones = AppModel.shared.readyPeers.filter { $0.caps.contains(Self.phoneCap) }.count
        let call = matched ?? (calls.count == 1 && phones <= 1 ? calls.values.first : nil)
        currentCall = call
        let state: CallAudioPolicy.Call
        switch call?.state {
        case .ringing: state = .ringing
        case .active, .held: state = .active
        case .ended, nil: state = .none
        }
        guard state != policy.phoneCall else { return }
        standbyTries = 0
        // About to bring up a link: remember the defaults before macOS may switch them to the phone.
        if state != .none && policy.link == .down { bridge.rememberDefaults() }
        apply(.phoneCall(state))
    }

    // MARK: Mac devices for the call

    /// Publishes the Mac's microphones and speakers and the ones the call uses (or would use).
    func publishDevices() {
        let phone = bridge.phoneDevices.union(knownPhoneAudioDevices())
        let name = UserDefaults.standard.string(forKey: Self.phoneNameKey)
        let ins = MacAudioDevices.list(input: true, excluding: phone, phoneName: name)
        let outs = MacAudioDevices.list(input: false, excluding: phone, phoneName: name)
        let selIn = MacAudioDevices.effective(input: true, excluding: phone, phoneName: name, fallback: nil).flatMap(CoreAudioDevices.uid)
        let selOut = MacAudioDevices.effective(input: false, excluding: phone, phoneName: name, fallback: nil).flatMap(CoreAudioDevices.uid)
        let ca = CallAudio.shared
        if ca.macInputs != ins || ca.selectedMacInput != selIn { ca.report(macInputs: ins, selected: selIn) }
        if ca.macOutputs != outs || ca.selectedMacOutput != selOut { ca.report(macOutputs: outs, selected: selOut) }
    }

    /// The chosen phone's own Core Audio device(s), if macOS has made them (only while its audio is connected).
    private func knownPhoneAudioDevices() -> Set<AudioDeviceID> {
        guard Self.bluetoothAllowed, let addr = linkAddress, policy.link == .up else { return [] }
        guard let d = CoreAudioDevices.phoneDevices(address: addr, name: UserDefaults.standard.string(forKey: Self.phoneNameKey)) else { return [] }
        return [d.input, d.output]
    }

    /// The user picked a Mac microphone or speaker: remember it, use it for this call, and bring the call
    /// audio to the Mac if a call is going on and it isn't here yet.
    private func selectMacDevice(_ uid: String, input: Bool) {
        CallAudioLog.shared.log("devices: picked \(input ? "mic" : "output") \(uid)")
        MacAudioDevices.shared.remember(uid: uid, input: input)
        publishDevices()
        bridge.reconfigure()
        if policy.callInProgress && policy.route == .phone { useMac() }
    }

    // MARK: AirBridge messages (§13.4)

    nonisolated static func receive(from peerId: String, _ msg: [String: Any]) {
        guard msg["t"] as? String == "call_audio_state" else { return }
        let info = PhoneBluetoothInfo(
            bluetoothOn: msg["bt"] as? Bool,
            name: (msg["bt_name"] as? String).map { String($0.prefix(100)) },
            headset: (msg["headset"] as? String).map { String($0.prefix(100)) })
        callAudioOnMain { CallAudioController.shared.phoneInfo(info, from: peerId) }
    }

    private func phoneInfo(_ info: PhoneBluetoothInfo, from peerId: String) {
        guard infos[peerId] != info else { return }
        infos[peerId] = info
        CallAudioLog.shared.log("phone \(peerId.prefix(8)): bt \(info.bluetoothOn.map(String.init) ?? "?") name \(info.name ?? "-") headset \(info.headset ?? "-")")
        refresh()
    }

    private func peersChanged(_ peers: [PeerInfo]) {
        let ids = Set(peers.map(\.id))
        infos = infos.filter { ids.contains($0.key) }
        helloSent = helloSent.filter { ids.contains($0.key) }
        sendHellos()
        refresh()
    }

    /// Tells phones this Mac's Bluetooth name and address, so they can leave the Mac out when they report
    /// which headset they use. Sent again only when it changes.
    private func sendHellos() {
        guard Self.bluetoothAllowed, let host = IOBluetoothHostController.default() else { return }
        var msg: [String: Any] = ["t": "call_audio_hello"]
        if let n = host.nameAsString(), !n.isEmpty { msg["bt_name"] = n }
        if let a = host.addressAsString(), !a.isEmpty { msg["bt_addr"] = a.replacingOccurrences(of: "-", with: ":").uppercased() }
        let key = "\(msg["bt_name"] ?? "")|\(msg["bt_addr"] ?? "")"
        for p in AppModel.shared.readyPeers where p.caps.contains(Self.phoneCap) && helloSent[p.id] != key {
            if PeerBus.shared.send(to: p.id, msg) { helloSent[p.id] = key }
        }
    }
}
