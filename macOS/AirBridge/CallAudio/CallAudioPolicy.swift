import Foundation

/// The routing rules for call audio over Bluetooth hands-free, with no Bluetooth or audio code in it, so it can
/// be tested on its own (tools/CallAudioPolicyTests). `CallAudioController` feeds it events from IOBluetooth,
/// the phone and the call window, and carries out the commands it returns.
///
/// Two kinds of link to the phone:
/// - **Mac audio** (`wantMac`): the user answered on the Mac, picked "Audio: Mac" here, or picked this Mac in the
///   phone's own dialer. Service level connection (SLC) + audio connection (SCO), audio bridged to the Mac.
/// - **Standby**: while the phone has a call (ringing or active), the Mac is set up and reachable, and the phone
///   does not use another Bluetooth headset, the SLC alone is kept up so the phone's dialer lists this Mac as an
///   audio route. No audio flows. It is dropped when the call ends. Never without a call, never next to another
///   headset (no Bluetooth link "for waste").
///
/// Audio the phone opens by itself (the AG side requests SCO):
/// - while the call is ringing: refused (no in-band ringtone on the Mac; the call window rings itself);
/// - during a short **grace window** after the SLC comes up, after the call is answered on the phone, or after
///   the user moved audio back to the phone from the Mac: refused. Android sends call audio to a headset that
///   just connected, or to the active headset when a call is answered; that is not the user's choice;
/// - otherwise: accepted, and treated as the user picking this Mac in the phone's dialer (route becomes Mac).
/// Risks of the heuristic: if Android retries its automatic routing after the grace window, the audio comes to
/// the Mac without the user asking (it shows as "Mac" here and moves back with one click); if the user picks the
/// Mac in the dialer within the grace window, that first try is refused (picking it again works).
///
/// When the phone takes the audio back (earpiece or speaker picked in the dialer), the route follows it and the
/// standby link stays for the rest of the call. This policy never touches the Mac's audio devices.
nonisolated struct CallAudioPolicy: Equatable, Sendable {
    enum Route: Equatable, Sendable {
        case unavailable(reason: String)
        case phone
        case mac
    }

    enum Link: Equatable, Sendable { case down, connecting, up }
    enum Audio: Equatable, Sendable { case closed, opening, open }

    /// The call, from the phone's hands-free indicators (while the link is up) or AirBridge `call_state`.
    enum Call: Equatable, Sendable {
        case none
        case ringing        // incoming, not answered yet
        case outgoing       // dialing or the other side is ringing
        case active         // answered (or held)

        /// From the HFP `call` and `callsetup` indicators.
        static func from(active: Bool, setup: Int) -> Call {
            if active { return .active }
            switch setup {
            case 1: return .ringing
            case 2, 3: return .outgoing
            default: return .none
            }
        }

        /// Audio may be opened (a call is up, or the user wants to hear the other side ringing).
        var carriesAudio: Bool { self == .active || self == .outgoing }
    }

    enum Failure: Equatable, Sendable {
        case unreachable        // link could not be set up (out of range, phone refused, timeout)
        case audioFailed        // link up, but the audio connection could not be opened
        case linkLost           // the link dropped while the call audio was on the Mac
        case micDenied          // this Mac's microphone is not allowed for AirBridge
        case bridgeFailed       // the audio connection is up but this Mac could not play/record it
    }

    enum Event: Equatable, Sendable {
        /// AirBridge may use Bluetooth (macOS privacy setting). Nothing touches Bluetooth until it may.
        case bluetoothAccess(Bool)
        case bluetoothPower(on: Bool)
        /// Whether a Bluetooth-paired device for the AirBridge phone is known on this Mac.
        case phonePaired(Bool)
        /// What the phone says about itself (AirBridge `call_audio_state`): Bluetooth on, and the name of another
        /// Bluetooth headset it uses for calls (nil if none). `bluetoothOn == nil` means unknown (older app).
        case phoneInfo(bluetoothOn: Bool?, headset: String?)
        /// The microphone may be used (standby links are only offered when the Mac could really take the call).
        case micAllowed(Bool)
        /// The phone's call as AirBridge reports it (`call_state`), `.none` when it ended.
        case phoneCall(Call)
        case useMac
        case usePhone
        case setMicMuted(Bool)
        case micDenied
        /// SLC established; the indicators read during setup.
        case linkUp(callActive: Bool, callSetup: Int)
        case linkFailed
        case linkDown
        case audioOpened
        case audioFailed
        case audioClosed
        case bridgeFailed
        case callIndicators(callActive: Bool, callSetup: Int)
        /// The link has been up this long without any call (Mac audio wanted).
        case idleTimeout
        /// The grace window started by `armGrace` is over.
        case graceOver
        /// The call window says the call is over.
        case callEnded
    }

    enum Command: Equatable, Sendable {
        case connectLink
        case disconnectLink
        case openAudio
        case closeAudio
        /// Refuse (true) or accept (false) audio connections the phone opens on its own.
        case refuseIncomingAudio(Bool)
        case startBridge
        case stopBridge
        case setBridgeMuted(Bool)
        case armIdleTimer
        case cancelIdleTimer
        /// Start (or restart) the grace timer; `graceOver` follows.
        case armGrace
    }

    // MARK: State

    private(set) var bluetoothAllowed = true
    private(set) var bluetoothOn = true
    private(set) var phonePaired = false
    private(set) var phoneBluetoothOn: Bool?
    private(set) var phoneHeadset: String?
    private(set) var micAllowed = true
    /// The call according to AirBridge.
    private(set) var phoneCall: Call = .none
    /// The call according to the hands-free indicators (only while the link is up).
    private(set) var call: Call = .none
    /// The call audio belongs on the Mac (answered here, picked here, or picked in the phone's dialer).
    private(set) var wantMac = false
    private(set) var link: Link = .down
    private(set) var audio: Audio = .closed
    private(set) var bridging = false
    private(set) var failure: Failure?
    private(set) var micMuted = false
    /// Phone-opened audio is refused right now (the value last sent to the link; links start refusing).
    private(set) var refusing = true
    private(set) var grace = false
    /// A standby link failed or was dropped during this call: don't keep retrying by ourselves.
    private(set) var standbyGaveUp = false

    init(bluetoothOn: Bool = true, phonePaired: Bool = false) {
        self.bluetoothOn = bluetoothOn
        self.phonePaired = phonePaired
    }

    // MARK: Derived

    /// Why the Mac can't take the audio at all right now (nil if it can).
    var blocker: String? {
        if !bluetoothAllowed { return "Allow Bluetooth for AirBridge in System Settings to talk from this Mac." }
        if !bluetoothOn { return "Turn on Bluetooth on this Mac to talk from here." }
        if !phonePaired { return "Pair your phone with this Mac over Bluetooth to talk from here." }
        if phoneBluetoothOn == false { return "Turn on Bluetooth on your phone to talk from this Mac." }
        return nil
    }

    /// A call is going on (either source).
    var callInProgress: Bool { phoneCall != .none || (link == .up && call != .none) }

    /// The best knowledge of the call: the indicators while the link is up, else AirBridge.
    var currentCall: Call { link == .up && call != .none ? call : phoneCall }

    /// Keep an audio-less link so the phone's dialer can offer this Mac.
    var standbyWanted: Bool {
        blocker == nil && phoneHeadset == nil && micAllowed && callInProgress && !standbyGaveUp
    }

    var route: Route {
        if let b = blocker { return .unavailable(reason: b) }
        if audio == .open && bridging { return .mac }
        if let f = failure { return .unavailable(reason: Self.sentence(f)) }
        return .phone
    }

    /// A short line to show next to an available route (e.g. why audio stays on the phone).
    var note: String? {
        guard blocker == nil, failure == nil else { return nil }
        if let h = phoneHeadset, route != .mac { return "Your phone is using \(h)." }
        return nil
    }

    /// The audio is on its way to the Mac (the call window may show a quiet progress hint).
    var switching: Bool { wantMac && link != .down && route != .mac && failure == nil && blocker == nil }

    static func sentence(_ f: Failure) -> String {
        switch f {
        case .unreachable: return "Couldn't reach your phone over Bluetooth. Keep it near this Mac and try again."
        case .audioFailed: return "Your phone didn't send the call audio to this Mac. Try again."
        case .linkLost: return "The Bluetooth link to your phone dropped, so the call is back on your phone."
        case .micDenied: return "Allow microphone access for AirBridge in System Settings to talk from this Mac."
        case .bridgeFailed: return "This Mac couldn't play the call audio. Check your sound settings and try again."
        }
    }

    // MARK: Transitions

    mutating func handle(_ e: Event) -> [Command] {
        var cmds: [Command] = []
        switch e {
        case .bluetoothAccess(let ok):
            guard ok != bluetoothAllowed else { return [] }
            bluetoothAllowed = ok
            failure = nil
            if !ok { cmds += dropMac() }

        case .bluetoothPower(let on):
            guard on != bluetoothOn else { return [] }
            bluetoothOn = on
            failure = nil
            if !on { cmds += dropMac() }

        case .phonePaired(let p):
            guard p != phonePaired else { return [] }
            phonePaired = p
            failure = nil
            if !p { cmds += dropMac() }

        case .phoneInfo(let btOn, let headset):
            let name = headset?.trimmingCharacters(in: .whitespacesAndNewlines)
            let newHeadset = (name?.isEmpty ?? true) ? nil : name
            guard btOn != phoneBluetoothOn || newHeadset != phoneHeadset else { return [] }
            phoneBluetoothOn = btOn
            phoneHeadset = newHeadset
            // Another headset or Bluetooth off on the phone: the audio belongs on the phone.
            if phoneHeadset != nil || btOn == false { cmds += dropMac() }

        case .micAllowed(let ok):
            guard ok != micAllowed else { return [] }
            micAllowed = ok

        case .phoneCall(let c):
            let before = currentCall
            let hadAirBridgeCall = phoneCall != .none
            phoneCall = c
            if c == .none { return hadAirBridgeCall ? endOfCall() : [] }
            cmds += noteCallChange(from: before)

        case .useMac:
            failure = nil
            // The user's own pick wins even next to a headset on the phone (only the automatic link stays off).
            guard blocker == nil else { return [] }
            wantMac = true
            standbyGaveUp = false
            grace = false
            if link == .up { cmds += openAudioIfCallAllows() }

        case .usePhone:
            failure = nil
            let wasOnMac = wantMac || audio != .closed
            cmds += dropMac()
            // Moving audio back to the phone: Android may try to hand it straight back to the headset.
            if wasOnMac && link == .up { cmds += startGrace() }

        case .setMicMuted(let m):
            micMuted = m
            return bridging ? [.setBridgeMuted(m)] : []

        case .micDenied:
            micAllowed = false
            cmds += dropMac()
            failure = .micDenied

        case .linkUp(let active, let setup):
            if link == .up { return [] }
            guard link == .connecting, wantMac || standbyWanted else {
                // A connect we no longer want finished anyway.
                link = .down
                return [.disconnectLink]
            }
            link = .up
            call = Call.from(active: active, setup: setup)
            if wantMac { cmds += openAudioIfCallAllows() } else { cmds += startGrace() }

        case .linkFailed:
            guard link != .down else { return [] }
            let wanted = wantMac
            cmds += linkGone(sendDisconnect: false)
            standbyGaveUp = true
            if wanted { failure = .unreachable }

        case .linkDown:
            guard link != .down else { return [] }
            let lost = audio == .open && wantMac
            cmds += linkGone(sendDisconnect: false)
            // The phone (or the user there) dropped us: no automatic reconnect during this call.
            standbyGaveUp = true
            if lost { failure = .linkLost }

        case .audioOpened:
            guard link == .up else { return audio == .open ? [] : [.closeAudio] }
            if audio == .open { return [] }
            if !wantMac {
                if refusing || !currentCall.carriesAudio {
                    // Automatic routing that got through anyway: send it back.
                    return [.closeAudio]
                }
                // Opened by the phone outside the grace window: the user picked this Mac in the dialer.
                wantMac = true
                standbyGaveUp = false
                failure = nil
            }
            audio = .open
            bridging = true
            cmds += [.cancelIdleTimer, .startBridge, .setBridgeMuted(micMuted)]

        case .audioFailed:
            guard audio == .opening else { return [] }
            audio = .closed
            cmds += dropMac()
            failure = .audioFailed

        case .audioClosed:
            guard audio != .closed else { return [] }
            // The phone took the audio back (earpiece or speaker picked there) or it went away: follow it.
            audio = .closed
            cmds += dropMac()

        case .bridgeFailed:
            guard bridging else { return [] }
            cmds += dropMac()
            failure = .bridgeFailed

        case .callIndicators(let active, let setup):
            guard link == .up else { return [] }
            let before = currentCall
            call = Call.from(active: active, setup: setup)
            if call == .none && before != .none {
                phoneCall = .none
                return endOfCall()
            }
            cmds += noteCallChange(from: before)

        case .idleTimeout:
            guard wantMac, currentCall == .none, audio != .open else { return [] }
            cmds += dropMac()

        case .graceOver:
            guard grace else { return [] }
            grace = false

        case .callEnded:
            phoneCall = .none
            return endOfCall()
        }
        return cmds + reconcile()
    }

    // MARK: Helpers

    /// Brings the link and the refusal flag in line with what is wanted now.
    private mutating func reconcile() -> [Command] {
        var cmds: [Command] = []
        let wantLink = wantMac || standbyWanted
        if !wantLink && link != .down {
            cmds += linkGone(sendDisconnect: true)
        } else if wantLink && link == .down {
            link = .connecting
            cmds += [.cancelIdleTimer, .connectLink]
        }
        let refuse = desiredRefusal
        if refuse != refusing {
            refusing = refuse
            cmds.append(.refuseIncomingAudio(refuse))
        }
        return cmds
    }

    /// Refuse phone-opened audio unless the Mac wants it, or the user could have asked for it in the dialer
    /// (call up, no grace window).
    private var desiredRefusal: Bool {
        let c = currentCall
        if wantMac { return audio == .closed && !c.carriesAudio }
        return grace || !c.carriesAudio
    }

    private mutating func noteCallChange(from before: Call) -> [Command] {
        let now = currentCall
        var cmds: [Command] = []
        // Answered on the phone (or the call came up) while the link is up and the Mac isn't wanted: Android
        // routes to the headset by itself now; that's not the user's choice.
        if !wantMac && link == .up && now == .active && before != .active { cmds += startGrace() }
        if wantMac { cmds += openAudioIfCallAllows() }
        return cmds
    }

    private mutating func startGrace() -> [Command] {
        grace = true
        return [.armGrace]
    }

    private mutating func openAudioIfCallAllows() -> [Command] {
        guard link == .up, wantMac, audio == .closed else { return [] }
        if currentCall.carriesAudio {
            audio = .opening
            var cmds: [Command] = [.cancelIdleTimer]
            if refusing { refusing = false; cmds.append(.refuseIncomingAudio(false)) }
            return cmds + [.openAudio]
        }
        // Still ringing (answer on its way) or no call yet: wait a little for it, then let go.
        return [.armIdleTimer]
    }

    private mutating func endOfCall() -> [Command] {
        var cmds = dropMac()
        cmds += linkGone(sendDisconnect: true)
        failure = nil
        micMuted = false
        standbyGaveUp = false
        grace = false
        if !refusing { refusing = true; cmds.append(.refuseIncomingAudio(true)) }
        return cmds
    }

    /// Audio off the Mac (bridge stopped, audio closed); the link itself is left to `reconcile`.
    private mutating func dropMac() -> [Command] {
        var cmds: [Command] = []
        if bridging { cmds.append(.stopBridge) }
        if audio != .closed && link == .up { cmds.append(.closeAudio) }
        if wantMac { cmds.append(.cancelIdleTimer) }
        bridging = false
        audio = .closed
        wantMac = false
        return cmds
    }

    /// The link is (or goes) down: everything on it goes too.
    private mutating func linkGone(sendDisconnect: Bool) -> [Command] {
        var cmds: [Command] = []
        if bridging { cmds.append(.stopBridge) }
        if sendDisconnect && audio != .closed && link == .up { cmds.append(.closeAudio) }
        if sendDisconnect && link != .down { cmds.append(.disconnectLink) }
        if wantMac || link != .down { cmds.append(.cancelIdleTimer) }
        bridging = false
        audio = .closed
        link = .down
        call = .none
        wantMac = false
        grace = false
        return cmds
    }
}
