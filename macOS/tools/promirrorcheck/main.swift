import Foundation

// Pro mirroring self-check: scrcpy framing (byte-exact against scrcpy v5.0.1's own serializer tests),
// input mapping, mDNS matching, adb commands/parsing and the setup state machine.
// Run with tools/promirrorcheck/run.sh (no Xcode project, no app launch, no phone).

var failures = 0
var checks = 0

func check(_ ok: Bool, _ what: String, file: StaticString = #file, line: UInt = #line) {
    checks += 1
    if !ok { failures += 1; print("FAIL \(line): \(what)") }
}

func bytes(_ d: Data) -> [UInt8] { Array(d) }
func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined(separator: " ") }
func expectBytes(_ c: Scrcpy.Control, _ expected: [UInt8], _ what: String, line: UInt = #line) {
    let got = bytes(c.bytes)
    check(got == expected, "\(what)\n  got      \(hex(got))\n  expected \(hex(expected))", line: line)
}

// MARK: - Control messages (app/tests/test_control_msg_serialize.c, v5.0.1)

check(Scrcpy.ControlType.resetVideo.rawValue == 17 && Scrcpy.ControlType.setDisplayPower.rawValue == 10
      && Scrcpy.ControlType.rotateDevice.rawValue == 11 && Scrcpy.ControlType.getClipboard.rawValue == 8,
      "control type numbering matches ControlMessage.java")

expectBytes(.keycode(action: Scrcpy.keyUp, keycode: 66, repeatCount: 5, metaState: 0x41),
            [0x00, 0x01, 0, 0, 0, 0x42, 0, 0, 0, 0x05, 0, 0, 0, 0x41], "inject keycode")

expectBytes(.text("hello, world!"),
            [0x01, 0, 0, 0, 0x0d] + Array("hello, world!".utf8), "inject text")

do {
    let long = String(repeating: "a", count: 400)
    let got = bytes(Scrcpy.Control.text(long).bytes)
    check(got.count == 5 + 300 && Array(got[0..<5]) == [0x01, 0, 0, 0x01, 0x2c], "inject text truncates at 300 bytes")
    // A multi-byte character straddling the limit is dropped whole, never split.
    let edge = String(repeating: "a", count: 299) + "é"
    let e = bytes(Scrcpy.Control.text(edge).bytes)
    check(e.count == 5 + 299 && e[4] == 0x2b, "inject text never splits a UTF-8 sequence")
}

expectBytes(.touch(action: Scrcpy.motionDown, pointerId: 0x1234567887654321, x: 100, y: 200, width: 1080, height: 1920,
                   pressure: 1.0, actionButton: 1, buttons: 1),
            [0x02, 0x00, 0x12, 0x34, 0x56, 0x78, 0x87, 0x65, 0x43, 0x21,
             0, 0, 0, 0x64, 0, 0, 0, 0xc8, 0x04, 0x38, 0x07, 0x80,
             0xff, 0xff, 0, 0, 0, 0x01, 0, 0, 0, 0x01], "inject touch")

expectBytes(.scroll(x: 260, y: 1026, width: 1080, height: 1920, h: 16, v: -16, buttons: 1),
            [0x03, 0, 0, 0x01, 0x04, 0, 0, 0x04, 0x02, 0x04, 0x38, 0x07, 0x80,
             0x7f, 0xff, 0x80, 0x00, 0, 0, 0, 0x01], "inject scroll")

expectBytes(.backOrScreenOn(action: Scrcpy.keyUp), [0x04, 0x01], "back or screen on")
expectBytes(.expandNotificationPanel, [0x05], "expand notification panel")
expectBytes(.expandSettingsPanel, [0x06], "expand settings panel")
expectBytes(.collapsePanels, [0x07], "collapse panels")
expectBytes(.getClipboard(.copy), [0x08, 0x01], "get clipboard (copy)")
expectBytes(.setClipboard(sequence: 0x0102030405060708, text: "hello, world!", paste: true),
            [0x09, 1, 2, 3, 4, 5, 6, 7, 8, 1, 0, 0, 0, 0x0d] + Array("hello, world!".utf8), "set clipboard")
expectBytes(.setDisplayPower(on: true), [0x0a, 0x01], "set display power")
expectBytes(.rotateDevice, [0x0b], "rotate device")
expectBytes(.resetVideo, [0x11], "reset video")

check(Scrcpy.u16fp(0.5) == 0x8000 && Scrcpy.u16fp(0) == 0 && Scrcpy.u16fp(1) == 0xffff, "u16 fixed point")
check(Scrcpy.i16fp(-1) == -0x8000 && Scrcpy.i16fp(0.5) == 0x4000 && Scrcpy.i16fp(1) == 0x7fff, "i16 fixed point")

// MARK: - Video stream headers (doc/develop.md, Streamer.java)

check(Scrcpy.parseHeader([0x80, 0, 0, 0, 0, 0, 0x04, 0x38, 0, 0, 0x07, 0x80])
      == .session(width: 1080, height: 1920, clientResized: false), "session packet")
check(Scrcpy.parseHeader([0x80, 0, 0, 1, 0, 0, 0x02, 0xd0, 0, 0, 0x05, 0x00])
      == .session(width: 720, height: 1280, clientResized: true), "session packet with client-resized flag")
check(Scrcpy.parseHeader([0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x1a])
      == .media(pts: 0, config: true, keyFrame: false, size: 26), "config packet")
check(Scrcpy.parseHeader([0x20, 0, 0, 0, 0, 0x0f, 0x42, 0x40, 0, 0x01, 0, 0])
      == .media(pts: 1_000_000, config: false, keyFrame: true, size: 65536), "key frame with pts")
check(Scrcpy.parseHeader([0x00, 0, 0, 0, 0, 0, 0x30, 0x39, 0, 0, 0x10, 0])
      == .media(pts: 12345, config: false, keyFrame: false, size: 4096), "plain frame")
check(Scrcpy.parseHeader([0x00, 0, 0]) == nil, "short header")
check(Scrcpy.streamStart(0x68323634) == .codec(Scrcpy.codecH264) && Scrcpy.streamStart(0) == .disabled
      && Scrcpy.streamStart(1) == .error, "codec id / disable codes")
check(Scrcpy.deviceName(Array("Pixel 9".utf8) + [UInt8](repeating: 0, count: 57)) == "Pixel 9", "device name field")
check(Scrcpy.socketName(scid: 0x1234abcd) == "scrcpy_1234abcd", "socket name")

// MARK: - Device messages (app/tests/test_device_msg_deserialize.c)

do {
    var p = Scrcpy.DeviceMessageParser()
    check(p.feed(Data([0x00, 0, 0, 0, 0x03, 0x41, 0x42, 0x43])) == [.clipboard("ABC")], "clipboard message")
    check(p.feed(Data([0x01, 1, 2, 3, 4, 5, 6, 7, 8])) == [.ackClipboard(0x0102030405060708)], "ack clipboard")
    check(p.feed(Data([0x02, 0, 42, 0, 5, 1, 2, 3, 4, 5])) == [.uhidOutput(id: 42, data: Data([1, 2, 3, 4, 5]))], "uhid output")
    // Split across reads, and two in one read.
    check(p.feed(Data([0x00, 0, 0])).isEmpty, "partial message waits")
    check(p.feed(Data([0, 0x02, 0x68, 0x69, 0x01, 0, 0, 0, 0, 0, 0, 0, 9])) == [.clipboard("hi"), .ackClipboard(9)], "split + batched messages")
    check(p.feed(Data([0x07, 1, 2])).isEmpty && p.failed, "unknown type stops parsing")
}

// MARK: - Server command

do {
    let cmd = Scrcpy.serverCommand(Scrcpy.Options(scid: 0x00abcdef))
    check(cmd.hasPrefix("CLASSPATH=/data/local/tmp/airbridge-scrcpy-server-5.0.1.jar app_process / com.genymobile.scrcpy.Server 5.0.1 scid=00abcdef "),
          "server command prefix: \(cmd)")
    for arg in ["tunnel_forward=true", "audio=false", "video_codec=h264", "max_size=1920", "video_bit_rate=10000000",
                "max_fps=60", "control=true", "clipboard_autosync=true", "cleanup=true"] {
        check(cmd.contains(" \(arg)"), "server command has \(arg)")
    }
    check(!cmd.contains("  "), "no empty arguments")
}

// MARK: - adb commands and output

check(ADB.pair(host: "192.168.1.5", port: 41563, code: "123456") == ["pair", "192.168.1.5:41563", "123456"], "adb pair")
check(ADB.connect(host: "192.168.1.5", port: 37291) == ["connect", "192.168.1.5:37291"], "adb connect")
check(ADB.connect(host: "fe80::1", port: 5555) == ["connect", "[fe80::1]:5555"], "adb connect IPv6")
check(ADB.forward("192.168.1.5:37291", scid: 0x1234abcd) == ["-s", "192.168.1.5:37291", "forward", "tcp:0", "localabstract:scrcpy_1234abcd"], "adb forward")
check(ADB.push("s", local: "/a/b", remote: "/data/local/tmp/x.jar") == ["-s", "s", "push", "/a/b", "/data/local/tmp/x.jar"], "adb push")
check(ADB.startServer("s", Scrcpy.Options(scid: 1)).count == 4 && ADB.startServer("s", Scrcpy.Options(scid: 1))[2] == "shell",
      "server runs as one shell argument")
do {
    let env = ADB.environment(home: "/h", logPath: "/h/log")
    check(env["HOME"] == "/h" && env["ANDROID_ADB_SERVER_PORT"] == "27150" && env["ADB_USB"] == "0"
          && env["ADB_EMU"] == "0" && env["ADB_MDNS_AUTO_CONNECT"] == "0", "isolated adb environment")
}

check(ADB.parsePair("Successfully paired to 192.168.1.5:41563 [guid=adb-R5CR10ABCDE-qqLBvB]", status: 0)
      == .paired(guid: "adb-R5CR10ABCDE-qqLBvB"), "pair success + guid")
check(ADB.parsePair("Failed: Wrong password or connection was dropped.", status: 1) == .failed(.wrongCode), "pair wrong code")
check(ADB.parseConnect("connected to 192.168.1.5:37291", host: "192.168.1.5", port: 37291) == .connected(serial: "192.168.1.5:37291"), "connect ok")
check(ADB.parseConnect("already connected to 192.168.1.5:37291", host: "192.168.1.5", port: 37291) == .connected(serial: "192.168.1.5:37291"), "already connected")
check(ADB.parseConnect("failed to connect to '192.168.1.5:37291': Connection refused", host: "192.168.1.5", port: 37291) == .failed(.wirelessDebuggingOff), "connect refused")
check(ADB.parseConnect("failed to authenticate to 192.168.1.5:37291", host: "192.168.1.5", port: 37291) == .failed(.notPaired), "connect not paired")
check(ADB.parseConnect("cannot connect to 192.168.1.5:37291: No route to host (65)", host: "192.168.1.5", port: 37291) == .failed(.notOnNetwork), "connect no route")
check(ADB.parseConnect("failed to connect to 192.168.1.5:37291: Operation timed out", host: "192.168.1.5", port: 37291) == .failed(.timeout), "connect timeout")
check(ADB.parseState("device\n") == nil && ADB.parseState("error: device unauthorized.") == .unauthorized
      && ADB.parseState("error: device offline") == .offline, "get-state")
check(ADB.parseForwardPort("54321\n") == 54321 && ADB.parseForwardPort("error: x") == nil, "forward port")
check(ADB.serverFailure("[server] ERROR: The server version (5.0) does not match the client (5.0.1)") == .serverMismatch, "server version mismatch")
check(ADB.serverFailure("[server] INFO: Device: [Google] google Pixel 9 (Android 15)") == nil, "server info line is fine")
check(ProMirrorError.wirelessDebuggingOff.canFallBack && !ProMirrorError.serverMismatch.canFallBack, "fallback policy")
check(ProMirrorError.wirelessDebuggingOff.message(phone: "Pixel").hasPrefix("Wireless debugging is off on Pixel."), "plain message")

// MARK: - mDNS matching

check(ADBService.serial(fromInstance: "adb-R5CR10ABCDE-qqLBvB") == "R5CR10ABCDE", "serial from instance")
check(ADBService.serial(fromInstance: "adb-10BE8Q23XM000ZA-Ab1Cd2") == "10BE8Q23XM000ZA", "serial from instance (vivo style)")
check(ADBService.serial(fromInstance: "studio-abc") == nil, "not an adb instance")
do {
    let a = ADBService(kind: .connect, name: "adb-AAA-111111", host: "192.168.1.5", port: 37001)
    let b = ADBService(kind: .connect, name: "adb-BBB-222222", host: "192.168.1.9", port: 37002)
    check(ADBService.match([a, b], serial: "BBB", ip: "192.168.1.5", allowSingle: false) == b, "serial wins over IP")
    check(ADBService.match([a, b], serial: nil, ip: "192.168.1.5", allowSingle: false) == a, "IP match")
    check(ADBService.match([a, b], serial: nil, ip: nil, allowSingle: true) == nil, "two phones, no hint: no guess")
    check(ADBService.match([b], serial: nil, ip: nil, allowSingle: true) == b, "single phone during setup")
    check(ADBService.match([b], serial: "AAA", ip: nil, allowSingle: false) == nil, "other phone never matched later")
}

// MARK: - WM info

do {
    let i = WMInfo.parse("Physical size: 1260x2800\nOverride size: 1080x2400\nPhysical density: 480\nOverride density: 420\n")
    check(i.width == 1080 && i.height == 2400 && i.density == 420, "wm override wins")
    let j = WMInfo.parse("Physical size: 1260x2800\nPhysical density: 480\n")
    check(j.width == 1260 && j.height == 2800 && j.density == 480, "wm physical")
}

// MARK: - Input mapping

do {
    var inp = ProMirrorInput()
    check(inp.translate(["t": "ptr_btn", "b": 0, "down": true]).isEmpty, "no input before the first frame")
    _ = inp.setFrame(width: 1080, height: 2400)
    _ = inp.translate(["t": "ptr_abs", "x": 0.5, "y": 0.25])
    let down = inp.translate(["t": "ptr_btn", "b": 0, "down": true])
    check(down == [.touch(action: 0, pointerId: Scrcpy.pointerFinger, x: 540, y: 600, width: 1080, height: 2400,
                          pressure: 1, actionButton: 0, buttons: 0)], "click down = finger down at the point")
    let move = inp.translate(["t": "ptr_abs", "x": 0.5, "y": 0.5])
    check(move.count == 1 && { if case .touch(2, _, 540, 1200, _, _, _, _, _) = move[0] { return true }; return false }(), "drag = finger move")
    let up = inp.translate(["t": "ptr_btn", "b": 0, "down": false])
    check(up.count == 1 && { if case .touch(1, _, _, _, _, _, 0, _, _) = up[0] { return true }; return false }(), "release = finger up, pressure 0")
    check(inp.translate(["t": "ptr_abs", "x": 1.0, "y": 1.0]).isEmpty, "hover sends nothing")
    let edge = inp.translate(["t": "ptr_btn", "b": 0, "down": true])
    check({ if case .touch(0, _, 1079, 2399, _, _, _, _, _) = edge[0] { return true }; return false }(), "x=1,y=1 stays inside the frame")
    // Rotation while the finger is down lifts it at the old size first.
    let rot = inp.setFrame(width: 2400, height: 1080)
    check(rot.count == 1 && { if case .touch(1, _, _, _, 1080, 2400, _, _, _) = rot[0] { return true }; return false }(), "rotation lifts the finger")

    // Scroll: content moving up (+dy) is Android scrolling down (negative VSCROLL).
    var s = ProMirrorInput()
    _ = s.setFrame(width: 1080, height: 2400)
    s.physicalLong = 2400; s.density = 480
    let sc = s.translate(["t": "ptr_scroll", "dx": 0, "dy": 100, "px": true])
    if case let .scroll(_, _, w, h, hh, v, _) = sc.first {
        // 100 * 1.6 px of video = 160 px on the panel; one unit is 64 dp = 192 px at 480 dpi.
        check(w == 1080 && h == 2400 && hh == 0 && abs(v - (-160.0 / 192.0)) < 0.001, "precise scroll units (v=\(v))")
    } else { check(false, "precise scroll produces a scroll event") }
    let wheel = s.translate(["t": "ptr_scroll", "dx": 0, "dy": -3, "px": false])
    check({ if case .scroll(_, _, _, _, 0, 3, _) = wheel.first { return true }; return false }(), "wheel up = +3 units")

    // Keyboard.
    var k = ProMirrorInput()
    check(k.translate(["t": "key", "hid": 0x04, "down": true, "mods": 1, "text": "A"]) == [.text("A")], "printable = text")
    check(k.translate(["t": "key", "hid": 0x04, "down": false, "mods": 1]).isEmpty, "text key-up sends nothing")
    check(k.translate(["t": "key", "hid": 0xE1, "down": true, "mods": 1]).isEmpty, "bare modifier sends nothing")
    check(k.translate(["t": "key", "hid": 0x28, "down": true, "mods": 0]) == [.keycode(action: 0, keycode: 66, repeatCount: 0, metaState: 0)], "Enter down")
    check(k.translate(["t": "key", "hid": 0x28, "down": true, "mods": 0, "rep": true]) == [.keycode(action: 0, keycode: 66, repeatCount: 1, metaState: 0)], "Enter repeat")
    check(k.translate(["t": "key", "hid": 0x28, "down": false, "mods": 0]) == [.keycode(action: 1, keycode: 66, repeatCount: 0, metaState: 0)], "Enter up")
    check(k.translate(["t": "key", "hid": 0x29, "down": true, "mods": 0]) == [.keycode(action: 0, keycode: 4, repeatCount: 0, metaState: 0)], "Esc = Back")
    check(k.translate(["t": "key", "hid": 0x04, "down": true, "mods": 8]) == [.keycode(action: 0, keycode: 29, repeatCount: 0, metaState: AndroidKey.metaCtrl)], "⌘A = Ctrl+A")
    check(k.translate(["t": "key", "hid": 0x06, "down": true, "mods": 8]) == [.getClipboard(.copy)], "⌘C copies on the phone and syncs back")
    check(k.translate(["t": "key", "hid": 0x06, "down": false, "mods": 8]).isEmpty, "⌘C up swallowed")
    check(k.translate(["t": "key", "hid": 0x19, "down": true, "mods": 8], pasteText: { "from mac" })
          == [.setClipboard(sequence: 2, text: "from mac", paste: true)], "⌘V puts the Mac clipboard on the phone and pastes")
    check(k.translate(["t": "key", "hid": 0x50, "down": true, "mods": 8 | 1]) == [.keycode(action: 0, keycode: AndroidKey.moveHome, repeatCount: 0, metaState: AndroidKey.metaShift)], "⇧⌘← selects to line start")
    check(k.translate(["t": "key", "hid": 0x2A, "down": true, "mods": 4]) == [.keycode(action: 0, keycode: AndroidKey.del, repeatCount: 0, metaState: AndroidKey.metaCtrl)], "⌥⌫ deletes a word")
    let rel = k.releaseAll()
    check(rel.contains(.keycode(action: 1, keycode: 4, repeatCount: 0, metaState: 0)) && rel.count == 4, "releaseAll lifts every held key (Esc, ⌘A, ⇧⌘←, ⌥⌫) (\(rel.count))")

    var a = ProMirrorInput()
    check(a.translate(["t": "action", "name": "back"]) == [.backOrScreenOn(action: 0), .backOrScreenOn(action: 1)], "Back action")
    check(a.translate(["t": "action", "name": "home"]).count == 2, "Home action")
    check(a.translate(["t": "action", "name": "recents"]).first == .keycode(action: 0, keycode: 187, repeatCount: 0, metaState: 0), "Recents action")
    check(a.translate(["t": "action", "name": "notifications"]) == [.expandNotificationPanel], "notifications")
}

// MARK: - Setup state machine

do {
    typealias S = ProSetup
    var ctx = S.Context(phoneIP: "192.168.1.5")
    var step = S.Step.intro
    func go(_ e: S.Event) -> [S.Effect] { let (n, fx) = S.reduce(step, e, &ctx); step = n; return fx }

    check(go(.begin) == [.askPhoneStatus] && step == .developerOptions, "begin → step 1")
    _ = go(.phoneStatus(developerOptions: true, wirelessDebugging: false))
    check(step == .wirelessDebugging, "phone reports Developer options on → step 2")
    _ = go(.phoneStatus(developerOptions: true, wirelessDebugging: true))
    check(step == .waitingForPairing, "phone reports Wireless debugging on → step 3")
    let other = ADBService(kind: .pairing, name: "adb-OTHER-000000", host: "192.168.1.77", port: 40001)
    let mine = ADBService(kind: .pairing, name: "adb-MINE-111111", host: "192.168.1.5", port: 40123)
    _ = go(.pairingServices([other, mine]))
    check(step == .enterCode(mine, error: nil), "pairing service matched by the phone's IP")
    check(go(.submitCode("12 34")).isEmpty && step == .enterCode(mine, error: .wrongCode), "short code rejected locally")
    check(go(.submitCode("483 920")) == [.pair(mine, code: "483920")] && step == .pairing(mine), "code submitted")
    check(go(.pairFailed(.wrongCode)).isEmpty && step == .enterCode(mine, error: .wrongCode), "wrong code → enter again")
    _ = go(.submitCode("483920"))
    check(go(.paired(guid: "adb-MINE-111111")) == [.connect(serial: "MINE", pairingHost: "192.168.1.5")]
          && step == .connecting(serial: "MINE"), "paired → connect")
    check(go(.connectFailed(.wirelessDebuggingOff)).isEmpty && step == .failed(.wirelessDebuggingOff, retry: .connecting(serial: "MINE")), "connect failure")
    check(go(.retry) == [.connect(serial: "MINE", pairingHost: "192.168.1.5")], "retry connects again")
    check(go(.connected(serial: "MINE", host: "192.168.1.5", port: 37001)) == [.save(serial: "MINE", host: "192.168.1.5", port: 37001)]
          && step == .done, "connected → saved, done")

    // The pairing dialog closes while the code is being typed.
    step = .enterCode(mine, error: nil)
    _ = go(.pairingServices([other]))
    check(step == .waitingForPairing, "pairing dialog closed → wait again")
    // Already on the pairing screen at step 1: jump ahead.
    step = .developerOptions
    _ = go(.pairingServices([mine]))
    check(step == .enterCode(mine, error: nil), "pairing screen open early → straight to the code")
    check(S.normalizeCode("483-920") == "483920" && S.normalizeCode("48392a") == nil && S.normalizeCode("4839201") == nil, "code normalisation")
}

print(failures == 0 ? "OK: \(checks) checks passed" : "\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
