import Foundation

/// Wire format of the bundled scrcpy server (Genymobile/scrcpy, Apache-2.0), pinned to `version`.
/// The protocol is internal to scrcpy and changes between releases: the server refuses any other client
/// version, so this file, `version` and the bundled jar always change together (tools/fetch-pro-mirror-vendor.sh).
///
/// Sources for every byte here (v5.0.1): server `device/Streamer.java`, `device/DesktopConnection.java`,
/// `control/ControlMessageReader.java`, `control/DeviceMessageWriter.java`; client `app/src/control_msg.c`,
/// `app/src/demuxer.c` and the byte-exact tests in `app/tests/test_control_msg_serialize.c` (mirrored by
/// tools/promirrorcheck).
nonisolated enum Scrcpy {
    static let version = "5.0.1"
    /// Bundled resource and where it lives on the phone (our own name, so a scrcpy install on the same
    /// phone never runs our copy or the other way round).
    /// No file extension: Xcode treats a `.jar` as Java and leaves it out of the bundle.
    static let serverResource = "scrcpy-server-v5.0.1"
    static let serverSHA256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
    static let devicePath = "/data/local/tmp/airbridge-scrcpy-server-5.0.1.jar"
    static let deviceNameLength = 64

    /// `localabstract:` socket the server listens on in forward mode.
    static func socketName(scid: UInt32) -> String { String(format: "scrcpy_%08x", scid & 0x7FFF_FFFF) }

    /// Fresh 31-bit session id (scrcpy uses the same range).
    static func randomSCID() -> UInt32 { UInt32.random(in: 0...0x7FFF_FFFF) }

    // MARK: Server options

    struct Options: Equatable, Sendable {
        var scid: UInt32
        var maxSize = 1920
        var bitRate = 10_000_000
        var maxFps = 60
        var codec = "h264"
        var audio = false
        var stayAwake = true
        var clipboardAutosync = true
        var logLevel = "info"

        /// `key=value` arguments after the version, in a stable order (tests compare them).
        var arguments: [String] {
            [
                String(format: "scid=%08x", scid & 0x7FFF_FFFF),
                "log_level=\(logLevel)",
                "tunnel_forward=true",
                "video=true",
                "audio=\(audio)",
                "control=true",
                "video_codec=\(codec)",
                "max_size=\(maxSize)",
                "video_bit_rate=\(bitRate)",
                "max_fps=\(maxFps)",
                "stay_awake=\(stayAwake)",
                "clipboard_autosync=\(clipboardAutosync)",
                "power_off_on_close=false",
                "downsize_on_error=true",
                "cleanup=true",
                "power_on=true",
                "send_device_meta=true",
                "send_frame_meta=true",
                "send_dummy_byte=true",
                "send_stream_meta=true",
            ]
        }
    }

    /// The `adb shell` command line that starts the server (one string: adb passes it to the phone's shell).
    static func serverCommand(_ o: Options) -> String {
        (["CLASSPATH=\(devicePath)", "app_process", "/", "com.genymobile.scrcpy.Server", version] + o.arguments)
            .joined(separator: " ")
    }

    // MARK: Video stream

    static let codecH264: UInt32 = 0x6832_3634   // "h264"
    static let codecH265: UInt32 = 0x6832_3635   // "h265"
    static let codecAV1: UInt32 = 0x0061_7631    // "av1"

    /// The 4-byte codec id that starts the video socket (after the dummy byte and device name). 0 means the
    /// server disabled the stream on purpose, 1 that it hit a configuration error.
    enum StreamStart: Equatable {
        case codec(UInt32)
        case disabled
        case error
    }

    static func streamStart(_ v: UInt32) -> StreamStart {
        switch v {
        case 0: return .disabled
        case 1: return .error
        default: return .codec(v)
        }
    }

    static let headerSize = 12

    /// A 12-byte header on the video socket.
    enum PacketHeader: Equatable {
        /// New capture session (start, rotation, fold): the frame size from now on.
        case session(width: Int, height: Int, clientResized: Bool)
        /// Media packet: `size` bytes of Annex-B follow. `config` packets carry SPS/PPS (no PTS) and must be
        /// prepended to the next media packet.
        case media(pts: UInt64, config: Bool, keyFrame: Bool, size: Int)
    }

    static let flagSession: UInt64 = 1 << 63
    static let flagConfig: UInt64 = 1 << 62
    static let flagKeyFrame: UInt64 = 1 << 61
    static let ptsMask: UInt64 = flagKeyFrame - 1

    static func parseHeader<C: Collection>(_ b: C) -> PacketHeader? where C.Element == UInt8, C.Index == Int {
        guard b.count >= headerSize else { return nil }
        let s = b.startIndex
        if b[s] & 0x80 != 0 {
            let w = Int(be32(b, s + 4)), h = Int(be32(b, s + 8))
            return .session(width: w, height: h, clientResized: b[s + 3] & 1 != 0)
        }
        let v = be64(b, s)
        let size = Int(be32(b, s + 8))
        let config = v & flagConfig != 0
        return .media(pts: config ? 0 : v & ptsMask, config: config, keyFrame: v & flagKeyFrame != 0, size: size)
    }

    /// Turns video-socket packets into decodable access units: a config packet (SPS/PPS, no PTS) is held and
    /// prepended to the next media packet, as scrcpy's own client does (`packet_merger.c`).
    struct VideoAssembler {
        enum Output: Equatable {
            case session(width: Int, height: Int)
            case accessUnit(Data, pts: UInt64, keyFrame: Bool)
            case none
        }

        private var config: Data?

        mutating func packet(_ header: PacketHeader, payload: Data = Data()) -> Output {
            switch header {
            case let .session(w, h, _):
                return w > 0 && h > 0 ? .session(width: w, height: h) : .none
            case let .media(pts, isConfig, key, _):
                if isConfig { config = payload; return .none }
                guard let c = config else { return .accessUnit(payload, pts: pts, keyFrame: key) }
                config = nil
                return .accessUnit(c + payload, pts: pts, keyFrame: key)
            }
        }
    }

    /// Largest media packet we accept (a 4K keyframe is a few MB).
    static let maxPacket = 32 << 20

    /// Device name sent on the first socket: 64 bytes, NUL-padded UTF-8.
    static func deviceName<C: Collection>(_ b: C) -> String where C.Element == UInt8 {
        let bytes = Array(b.prefix(deviceNameLength))
        let end = bytes.firstIndex(of: 0) ?? bytes.count
        return String(decoding: bytes[..<end], as: UTF8.self)
    }

    // MARK: Control messages (Mac → phone)

    enum ControlType: UInt8 {
        case injectKeycode = 0, injectText, injectTouch, injectScroll, backOrScreenOn
        case expandNotificationPanel, expandSettingsPanel, collapsePanels
        case getClipboard, setClipboard, setDisplayPower, rotateDevice
        case uhidCreate, uhidInput, uhidDestroy, openHardKeyboardSettings, startApp, resetVideo
    }

    /// android.view.KeyEvent / MotionEvent constants used on the wire.
    static let keyDown: UInt8 = 0, keyUp: UInt8 = 1
    static let motionDown: UInt8 = 0, motionUp: UInt8 = 1, motionMove: UInt8 = 2
    static let pointerMouse: UInt64 = UInt64.max            // -1
    static let pointerFinger: UInt64 = UInt64.max - 1       // -2 (generic finger: a touch, never a mouse)
    static let buttonPrimary: UInt32 = 1

    static let injectTextMax = 300
    static let messageMax = 1 << 18
    static let clipboardTextMax = messageMax - 14

    enum CopyKey: UInt8 { case none = 0, copy = 1, cut = 2 }

    enum Control: Equatable {
        case keycode(action: UInt8, keycode: UInt32, repeatCount: UInt32, metaState: UInt32)
        case text(String)
        case touch(action: UInt8, pointerId: UInt64, x: Int32, y: Int32, width: UInt16, height: UInt16,
                   pressure: Float, actionButton: UInt32, buttons: UInt32)
        case scroll(x: Int32, y: Int32, width: UInt16, height: UInt16, h: Float, v: Float, buttons: UInt32)
        case backOrScreenOn(action: UInt8)
        case expandNotificationPanel
        case expandSettingsPanel
        case collapsePanels
        case getClipboard(CopyKey)
        case setClipboard(sequence: UInt64, text: String, paste: Bool)
        case setDisplayPower(on: Bool)
        case rotateDevice
        case resetVideo

        /// Serialized exactly as scrcpy's `sc_control_msg_serialize`.
        var bytes: Data {
            var d = Data()
            switch self {
            case let .keycode(action, keycode, rep, meta):
                d.append(ControlType.injectKeycode.rawValue)
                d.append(action)
                put32(&d, keycode); put32(&d, rep); put32(&d, meta)
            case let .text(s):
                d.append(ControlType.injectText.rawValue)
                putString(&d, s, max: injectTextMax)
            case let .touch(action, pid, x, y, w, h, pressure, ab, buttons):
                d.append(ControlType.injectTouch.rawValue)
                d.append(action)
                put64(&d, pid)
                put32(&d, UInt32(bitPattern: x)); put32(&d, UInt32(bitPattern: y)); put16(&d, w); put16(&d, h)
                put16(&d, u16fp(pressure))
                put32(&d, ab); put32(&d, buttons)
            case let .scroll(x, y, w, h, hs, vs, buttons):
                d.append(ControlType.injectScroll.rawValue)
                put32(&d, UInt32(bitPattern: x)); put32(&d, UInt32(bitPattern: y)); put16(&d, w); put16(&d, h)
                // scrcpy accepts [-16, 16] and normalises to [-1, 1] before the i16 fixed point.
                put16(&d, UInt16(bitPattern: i16fp(min(1, max(-1, hs / 16)))))
                put16(&d, UInt16(bitPattern: i16fp(min(1, max(-1, vs / 16)))))
                put32(&d, buttons)
            case let .backOrScreenOn(action):
                d.append(ControlType.backOrScreenOn.rawValue)
                d.append(action)
            case .expandNotificationPanel: d.append(ControlType.expandNotificationPanel.rawValue)
            case .expandSettingsPanel: d.append(ControlType.expandSettingsPanel.rawValue)
            case .collapsePanels: d.append(ControlType.collapsePanels.rawValue)
            case let .getClipboard(k):
                d.append(ControlType.getClipboard.rawValue)
                d.append(k.rawValue)
            case let .setClipboard(seq, text, paste):
                d.append(ControlType.setClipboard.rawValue)
                put64(&d, seq)
                d.append(paste ? 1 : 0)
                putString(&d, text, max: clipboardTextMax)
            case let .setDisplayPower(on):
                d.append(ControlType.setDisplayPower.rawValue)
                d.append(on ? 1 : 0)
            case .rotateDevice: d.append(ControlType.rotateDevice.rawValue)
            case .resetVideo: d.append(ControlType.resetVideo.rawValue)
            }
            return d
        }
    }

    // MARK: Device messages (phone → Mac, control socket)

    enum DeviceMessage: Equatable {
        case clipboard(String)
        case ackClipboard(UInt64)
        case uhidOutput(id: UInt16, data: Data)
    }

    /// Incremental parser: feed whatever arrived, get whole messages back. Unknown types make the stream
    /// unparseable (no length field), so `failed` turns true and the caller stops reading.
    struct DeviceMessageParser {
        private var buf = Data()
        private(set) var failed = false

        mutating func feed(_ chunk: Data) -> [DeviceMessage] {
            guard !failed else { return [] }
            buf.append(chunk)
            var out: [DeviceMessage] = []
            while let (msg, used) = next() {
                out.append(msg)
                buf.removeFirst(used)
            }
            return out
        }

        private mutating func next() -> (DeviceMessage, Int)? {
            let b = buf
            guard let type = b.first else { return nil }
            let s = b.startIndex
            switch type {
            case 0:
                guard b.count >= 5 else { return nil }
                let len = Int(be32(b, s + 1))
                guard len <= messageMax else { failed = true; return nil }
                guard b.count >= 5 + len else { return nil }
                return (.clipboard(String(decoding: b[(s + 5)..<(s + 5 + len)], as: UTF8.self)), 5 + len)
            case 1:
                guard b.count >= 9 else { return nil }
                return (.ackClipboard(be64(b, s + 1)), 9)
            case 2:
                guard b.count >= 5 else { return nil }
                let id = UInt16(b[s + 1]) << 8 | UInt16(b[s + 2])
                let len = Int(UInt16(b[s + 3]) << 8 | UInt16(b[s + 4]))
                guard b.count >= 5 + len else { return nil }
                return (.uhidOutput(id: id, data: Data(b[(s + 5)..<(s + 5 + len)])), 5 + len)
            default:
                failed = true
                return nil
            }
        }
    }

    // MARK: Fixed point (util/binary.h)

    static func u16fp(_ f: Float) -> UInt16 {
        let c = min(1, max(0, f))
        let u = UInt32(c * 65536)
        return u >= 0xFFFF ? 0xFFFF : UInt16(u)
    }

    static func i16fp(_ f: Float) -> Int16 {
        let c = min(1, max(-1, f))
        let i = Int32(c * 32768)
        return i >= 0x7FFF ? 0x7FFF : Int16(i)
    }

    // MARK: Byte helpers

    static func be32<C: Collection>(_ b: C, _ i: C.Index) -> UInt32 where C.Element == UInt8, C.Index == Int {
        UInt32(b[i]) << 24 | UInt32(b[i + 1]) << 16 | UInt32(b[i + 2]) << 8 | UInt32(b[i + 3])
    }

    static func be64<C: Collection>(_ b: C, _ i: C.Index) -> UInt64 where C.Element == UInt8, C.Index == Int {
        UInt64(be32(b, i)) << 32 | UInt64(be32(b, i + 4))
    }

    static func put16(_ d: inout Data, _ v: UInt16) { d.append(UInt8(v >> 8)); d.append(UInt8(v & 0xFF)) }
    static func put32(_ d: inout Data, _ v: UInt32) { for s in stride(from: 24, through: 0, by: -8) { d.append(UInt8((v >> UInt32(s)) & 0xFF)) } }
    static func put64(_ d: inout Data, _ v: UInt64) { for s in stride(from: 56, through: 0, by: -8) { d.append(UInt8((v >> UInt64(s)) & 0xFF)) } }

    /// u32 length + UTF-8, cut at a character boundary at or below `max` bytes (scrcpy's truncation rule).
    static func putString(_ d: inout Data, _ s: String, max: Int) {
        let u = Array(s.utf8)
        var len = min(u.count, max)
        // Never split a multi-byte sequence: back up to a lead byte.
        if len < u.count { while len > 0 && (u[len] & 0xC0) == 0x80 { len -= 1 } }
        put32(&d, UInt32(len))
        d.append(contentsOf: u[0..<len])
    }
}
