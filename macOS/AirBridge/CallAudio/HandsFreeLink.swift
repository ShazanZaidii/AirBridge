import Foundation
import IOBluetooth

/// The Bluetooth hands-free link to one phone, as the routing policy sees it. The real one is
/// `BluetoothHandsFreeLink` (IOBluetooth); tests can use a fake.
@MainActor protocol HandsFreeLink: AnyObject {
    /// Events for `CallAudioPolicy` (`linkUp`, `linkFailed`, `linkDown`, `audioOpened`, `audioFailed`,
    /// `audioClosed`, `callIndicators`). Always delivered on the main thread.
    var onEvent: ((CallAudioPolicy.Event) -> Void)? { get set }
    /// Bluetooth address of the phone ("aa-bb-cc-dd-ee-ff"), used to find its audio device.
    var address: String { get }
    func connect()
    func disconnect()
    func openAudio()
    func closeAudio()
    func refuseIncomingAudio(_ refuse: Bool)
}

/// The Mac as a Bluetooth Hands-Free unit (HFP "HF") for the phone (the Audio Gateway), on top of
/// `IOBluetoothHandsFreeDevice`: it sets up the service level connection (RFCOMM + AT handshake), opens and
/// closes the SCO audio connection and reports the phone's call indicators.
///
/// Notes from the macOS 26 IOBluetooth framework:
/// - The HF role offers CVSD (8 kHz narrowband) only: it never sends AT+BAC, so no mSBC wideband.
/// - Audio of the SCO connection appears as a Core Audio device of the phone; `CallAudioBridge` connects it
///   to this Mac's microphone and speakers.
/// - `setRejectSCOConnection:` (used by the framework's own `acceptCallOnPhone`) refuses SCO connections the
///   phone opens by itself; we use it to keep in-band ringtones off the Mac. It is not public API, so it is
///   only called when the object responds to it.
@MainActor final class BluetoothHandsFreeLink: NSObject, HandsFreeLink {
    var onEvent: ((CallAudioPolicy.Event) -> Void)?
    let address: String
    private let device: IOBluetoothDevice
    private var hf: IOBluetoothHandsFreeDevice?
    private var connectTimer: Timer?
    private var audioTimer: Timer?
    private var linkIsUp = false
    /// Audio the phone opens by itself is refused until the policy says otherwise (matches the policy's start).
    private var refuse = true
    private var releaseWork: DispatchWorkItem?

    /// How long the link and the audio connection may take before we give up.
    static let connectTimeout: TimeInterval = 15
    static let audioTimeout: TimeInterval = 8

    init?(address: String) {
        guard let d = IOBluetoothDevice(addressString: address) else { return nil }
        self.device = d
        self.address = address
        super.init()
    }

    func connect() {
        CallAudioLog.shared.log("link: connect to \(device.nameOrAddress ?? address)")
        linkIsUp = false
        releaseWork?.cancel(); releaseWork = nil
        let hf = self.hf ?? IOBluetoothHandsFreeDevice(device: device, delegate: self)
        guard let hf else {
            CallAudioLog.shared.log("link: IOBluetoothHandsFreeDevice could not be created")
            emit(.linkFailed)
            return
        }
        self.hf = hf
        applyRefuse()
        connectTimer?.invalidate()
        connectTimer = Timer.scheduledTimer(withTimeInterval: Self.connectTimeout, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, !self.linkIsUp else { return }
                CallAudioLog.shared.log("link: connect timed out")
                self.connectTimer = nil
                self.hf?.disconnect()
                self.scheduleRelease()
                self.emit(.linkFailed)
            }
        }
        hf.connect()
    }

    func disconnect() {
        CallAudioLog.shared.log("link: disconnect")
        connectTimer?.invalidate(); connectTimer = nil
        audioTimer?.invalidate(); audioTimer = nil
        linkIsUp = false
        guard let hf else { return }
        if hf.isSCOConnected() { hf.disconnectSCO() }
        hf.disconnect()
        scheduleRelease()
    }

    /// Drops the IOBluetooth object a little after disconnecting. While it exists it listens for the phone
    /// connecting to us, and Android reconnects known headsets by itself: that would make the Mac the phone's
    /// call headset with nobody asking for it.
    private func scheduleRelease() {
        releaseWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated {
                guard let self, !self.linkIsUp, self.connectTimer == nil, let hf = self.hf else { return }
                if hf.isConnected { hf.disconnect() }
                hf.delegate = nil
                self.hf = nil
                CallAudioLog.shared.log("link: released")
            }
        }
        releaseWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    func openAudio() {
        guard let hf else { emit(.audioFailed); return }
        if hf.isSCOConnected() {
            CallAudioLog.shared.log("audio: already open")
            emit(.audioOpened)
            return
        }
        CallAudioLog.shared.log("audio: open (SCO)")
        audioTimer?.invalidate()
        audioTimer = Timer.scheduledTimer(withTimeInterval: Self.audioTimeout, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.hf?.isSCOConnected() != true else { return }
                CallAudioLog.shared.log("audio: open timed out")
                self.emit(.audioFailed)
            }
        }
        hf.connectSCO()
    }

    func closeAudio() {
        audioTimer?.invalidate(); audioTimer = nil
        guard let hf, hf.isSCOConnected() else { return }
        CallAudioLog.shared.log("audio: close (SCO)")
        hf.disconnectSCO()
    }

    func refuseIncomingAudio(_ refuse: Bool) {
        self.refuse = refuse
        applyRefuse()
    }

    private func applyRefuse() {
        guard let hf, hf.responds(to: NSSelectorFromString("setRejectSCOConnection:")) else { return }
        hf.setValue(refuse, forKey: "rejectSCOConnection")
    }

    private func emit(_ e: CallAudioPolicy.Event) { onEvent?(e) }

    private func indicators() -> (Bool, Int) {
        guard let hf else { return (false, 0) }
        return (hf.indicator(IOBluetoothHandsFreeIndicatorCall) == 1, Int(hf.indicator(IOBluetoothHandsFreeIndicatorCallSetup)))
    }

    private static func ok(_ status: NSNumber?) -> Bool { (status?.int32Value ?? -1) == 0 }

    // MARK: Delegate callbacks (IOBluetooth calls these on the main run loop)

    fileprivate func connected(_ status: NSNumber?) {
        connectTimer?.invalidate(); connectTimer = nil
        guard Self.ok(status), hf?.isConnected == true else {
            CallAudioLog.shared.log("link: connect failed (status \(status?.int32Value ?? -1))")
            linkIsUp = false
            scheduleRelease()
            emit(.linkFailed)
            return
        }
        linkIsUp = true
        applyRefuse()
        let (active, setup) = indicators()
        CallAudioLog.shared.log("link: up (call \(active), setup \(setup), AG features \(hf?.deviceSupportedFeatures ?? 0))")
        emit(.linkUp(callActive: active, callSetup: setup))
    }

    fileprivate func disconnected(_ status: NSNumber?) {
        CallAudioLog.shared.log("link: down (status \(status?.int32Value ?? -1))")
        connectTimer?.invalidate(); connectTimer = nil
        audioTimer?.invalidate(); audioTimer = nil
        let wasUp = linkIsUp
        linkIsUp = false
        scheduleRelease()
        emit(wasUp ? .linkDown : .linkFailed)
    }

    fileprivate func scoOpened(_ status: NSNumber?) {
        audioTimer?.invalidate(); audioTimer = nil
        // Asking for audio that is already open reports a non-zero status; what counts is the connection.
        if Self.ok(status) || hf?.isSCOConnected() == true {
            CallAudioLog.shared.log("audio: open")
            emit(.audioOpened)
        } else {
            CallAudioLog.shared.log("audio: open failed (status \(status?.int32Value ?? -1))")
            emit(.audioFailed)
        }
    }

    fileprivate func scoClosed(_ status: NSNumber?) {
        CallAudioLog.shared.log("audio: closed (status \(status?.int32Value ?? -1))")
        emit(.audioClosed)
    }

    fileprivate func indicatorsChanged() {
        guard linkIsUp else { return }
        let (active, setup) = indicators()
        emit(.callIndicators(callActive: active, callSetup: setup))
    }
}

extension BluetoothHandsFreeLink: IOBluetoothHandsFreeDeviceDelegate {
    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, connected status: NSNumber!) {
        callAudioOnMain { self.connected(status) }
    }
    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, disconnected status: NSNumber!) {
        callAudioOnMain { self.disconnected(status) }
    }
    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionOpened status: NSNumber!) {
        callAudioOnMain { self.scoOpened(status) }
    }
    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionClosed status: NSNumber!) {
        callAudioOnMain { self.scoClosed(status) }
    }
    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, isCallActive: NSNumber!) {
        callAudioOnMain { self.indicatorsChanged() }
    }
    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, callSetupMode: NSNumber!) {
        callAudioOnMain { self.indicatorsChanged() }
    }
}
