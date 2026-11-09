import Foundation

/// Turns the mirror window's input (the same JSON vocabulary as normal mirroring: `ptr_abs`, `ptr_btn`,
/// `ptr_scroll`, `key`, `action`, §8.2–8.5) into scrcpy control messages. Pure state machine, no I/O, so the
/// window's input code stays shared and this mapping is unit-tested (tools/promirrorcheck).
///
/// - A click is a real finger: DOWN at the press, MOVEs while dragging, UP at the release, with the user's
///   own timing (long press, swipes and flings work like on the glass).
/// - Two-finger trackpad scrolls become Android scroll events at the pointer, sized so the content follows
///   the fingers about 1:1 as seen in the window.
/// - Printable keys go as text (any Mac keyboard layout types what its keycaps say); everything else as
///   key codes with meta state. ⌘ shortcuts are sent as Ctrl (Android's shortcut key); ⌘C/⌘X/⌘V also sync the
///   clipboard both ways; ⌘←/→ jump to line start/end, ⌥←/→ by word, like on a Mac. Esc is Back.
nonisolated struct ProMirrorInput {
    /// Current video frame size: positions are sent in these pixels (scrcpy drops events for another size).
    private(set) var frameW = 0, frameH = 0
    /// Physical panel's long side and density (`wm size` / `wm density`), for scroll speed. 0 = unknown.
    var physicalLong = 0
    var density = 0

    /// Must match MirrorGeometry.targetGain: the window divides precise scroll deltas by it.
    static let windowGain = 1.6
    /// Android's default `config_verticalScrollFactor` (64 dp per scroll unit).
    static let scrollFactorDp = 64.0

    private var x = 0.5, y = 0.5
    private var touching = false
    /// HID usage → what went down for it (so the up matches even if modifiers changed in between).
    private var held: [Int: (keycode: UInt32, meta: UInt32, repeats: UInt32)] = [:]
    /// HID usages whose press was sent as text or a one-shot command: their key-up sends nothing.
    private var swallowed: Set<Int> = []
    private var clipSequence: UInt64 = 1

    init() {}

    var hasFrame: Bool { frameW > 0 && frameH > 0 }

    /// New capture session (start or rotation). A finger still down is lifted at the old size first.
    mutating func setFrame(width: Int, height: Int) -> [Scrcpy.Control] {
        var out: [Scrcpy.Control] = []
        if touching { out.append(touch(Scrcpy.motionUp)); touching = false }
        frameW = width; frameH = height
        return out
    }

    /// `pasteText` is read only for ⌘V / `paste` (the Mac clipboard to put on the phone first).
    mutating func translate(_ msg: [String: Any], pasteText: () -> String? = { nil }) -> [Scrcpy.Control] {
        guard let t = msg["t"] as? String else { return [] }
        switch t {
        case "ptr_abs":
            guard let nx = Self.num(msg["x"]), let ny = Self.num(msg["y"]) else { return [] }
            x = min(1, max(0, nx)); y = min(1, max(0, ny))
            return touching && hasFrame ? [touch(Scrcpy.motionMove)] : []
        case "ptr_btn":
            guard (Self.num(msg["b"]) ?? 0) == 0, hasFrame else { return [] }
            let down = msg["down"] as? Bool ?? false
            if down == touching { return [] }
            touching = down
            return [touch(down ? Scrcpy.motionDown : Scrcpy.motionUp)]
        case "ptr_scroll":
            return scroll(dx: Self.num(msg["dx"]) ?? 0, dy: Self.num(msg["dy"]) ?? 0, precise: msg["px"] as? Bool ?? false)
        case "key":
            return key(msg, pasteText: pasteText)
        case "action":
            return action(msg["name"] as? String ?? "", pasteText: pasteText)
        default:
            return []
        }
    }

    /// Focus lost / window closing: lift the finger and every key.
    mutating func releaseAll() -> [Scrcpy.Control] {
        var out: [Scrcpy.Control] = []
        if touching, hasFrame { out.append(touch(Scrcpy.motionUp)) }
        touching = false
        for (_, k) in held.sorted(by: { $0.key < $1.key }) {
            out.append(.keycode(action: Scrcpy.keyUp, keycode: k.keycode, repeatCount: 0, metaState: k.meta))
        }
        held.removeAll()
        swallowed.removeAll()
        return out
    }

    // MARK: pointer

    private var px: (Int32, Int32) {
        let w = max(1, frameW), h = max(1, frameH)
        return (Int32(min(Double(w - 1), (x * Double(w)).rounded(.down))),
                Int32(min(Double(h - 1), (y * Double(h)).rounded(.down))))
    }

    private func touch(_ action: UInt8) -> Scrcpy.Control {
        let p = px
        return .touch(action: action, pointerId: Scrcpy.pointerFinger, x: p.0, y: p.1,
                      width: UInt16(clamping: frameW), height: UInt16(clamping: frameH),
                      pressure: action == Scrcpy.motionUp ? 0 : 1, actionButton: 0, buttons: 0)
    }

    /// Wire `ptr_scroll` (content motion: +dx content left, +dy content up; `px` = window-scaled pixels) →
    /// Android AXIS_HSCROLL (+ = scroll right) / AXIS_VSCROLL (+ = scroll up, content moves down).
    func scrollUnits(dx: Double, dy: Double, precise: Bool) -> (h: Float, v: Float) {
        if !precise { return (Float(dx), Float(-dy)) }   // wheel: one line ≈ one scroll unit
        // Long sides compare in any orientation (the video follows rotation, `wm size` doesn't).
        let videoLong = Double(max(1, max(frameW, frameH)))
        let phys = physicalLong > 0 ? Double(physicalLong) : videoLong
        let dens = density > 0 ? Double(density) : 440
        let unitPx = Self.scrollFactorDp * dens / 160
        let k = Self.windowGain * (phys / videoLong) / unitPx
        return (Float(dx * k), Float(-dy * k))
    }

    private func scroll(dx: Double, dy: Double, precise: Bool) -> [Scrcpy.Control] {
        guard hasFrame, !touching else { return [] }
        let u = scrollUnits(dx: dx, dy: dy, precise: precise)
        guard u.h != 0 || u.v != 0 else { return [] }
        let p = px
        return [.scroll(x: p.0, y: p.1, width: UInt16(clamping: frameW), height: UInt16(clamping: frameH),
                        h: max(-16, min(16, u.h)), v: max(-16, min(16, u.v)), buttons: 0)]
    }

    // MARK: keyboard

    private mutating func key(_ msg: [String: Any], pasteText: () -> String?) -> [Scrcpy.Control] {
        guard let hidD = Self.num(msg["hid"]) else { return [] }
        let hid = Int(hidD)
        let down = msg["down"] as? Bool ?? false
        let mods = Int(Self.num(msg["mods"]) ?? 0)
        let isRepeat = msg["rep"] as? Bool ?? false

        if !down {
            if swallowed.remove(hid) != nil { return [] }
            guard let k = held.removeValue(forKey: hid) else { return [] }
            return [.keycode(action: Scrcpy.keyUp, keycode: k.keycode, repeatCount: 0, metaState: k.meta)]
        }

        // Modifiers travel as meta state on the keys they modify; Caps Lock is already in the text.
        if AndroidKey.isModifier(hid: hid) { return [] }

        // Printable text without ⌘/Ctrl: inject the characters themselves.
        if let text = msg["text"] as? String, !text.isEmpty, mods & (2 | 8) == 0 {
            swallowed.insert(hid)
            return [.text(text)]
        }

        let cmd = mods & 8 != 0, alt = mods & 4 != 0, shift = mods & 1 != 0
        if cmd && !isRepeat {
            switch hid {
            case 0x06: swallowed.insert(hid); return [.getClipboard(.copy)]          // ⌘C
            case 0x1B: swallowed.insert(hid); return [.getClipboard(.cut)]           // ⌘X
            case 0x19:                                                               // ⌘V
                swallowed.insert(hid)
                return pasteControls(pasteText())
            default: break
            }
        }

        var keycode: UInt32
        var meta = AndroidKey.metaState(mods: mods)
        if hid == 0x29 {
            keycode = AndroidKey.back                       // Esc = Back
            meta = 0
        } else if cmd, [0x4F, 0x50, 0x51, 0x52].contains(hid) {
            // ⌘← / ⌘→: line start / end; ⌘↑ / ⌘↓: document start / end (Ctrl+Home / Ctrl+End).
            let right = hid == 0x4F || hid == 0x51
            keycode = right ? AndroidKey.moveEnd : AndroidKey.moveHome
            meta = (shift ? AndroidKey.metaShift : 0) | (hid == 0x51 || hid == 0x52 ? AndroidKey.metaCtrl : 0)
        } else if alt, [0x4F, 0x50, 0x2A, 0x4C].contains(hid) {
            // ⌥←/→ by word, ⌥⌫ / ⌥⌦ delete a word: Ctrl on Android.
            guard let k = AndroidKey.keycode(hid: hid) else { return [] }
            keycode = k
            meta = (meta & ~AndroidKey.metaAlt) | AndroidKey.metaCtrl
        } else {
            guard let k = AndroidKey.keycode(hid: hid) else { return [] }
            keycode = k
        }

        if isRepeat, var h = held[hid] {
            h.repeats += 1
            held[hid] = h
            return [.keycode(action: Scrcpy.keyDown, keycode: h.keycode, repeatCount: h.repeats, metaState: h.meta)]
        }
        // ⌘ combos arrive as a down/up pair right away (macOS sends no key-up for them).
        var out: [Scrcpy.Control] = []
        if let old = held[hid] {
            out.append(.keycode(action: Scrcpy.keyUp, keycode: old.keycode, repeatCount: 0, metaState: old.meta))
        }
        held[hid] = (keycode, meta, 0)
        out.append(.keycode(action: Scrcpy.keyDown, keycode: keycode, repeatCount: 0, metaState: meta))
        return out
    }

    private mutating func pasteControls(_ text: String?) -> [Scrcpy.Control] {
        if let text, !text.isEmpty {
            clipSequence += 1
            return [.setClipboard(sequence: clipSequence, text: text, paste: true)]
        }
        return press(AndroidKey.paste)
    }

    private func press(_ keycode: UInt32, meta: UInt32 = 0) -> [Scrcpy.Control] {
        [.keycode(action: Scrcpy.keyDown, keycode: keycode, repeatCount: 0, metaState: meta),
         .keycode(action: Scrcpy.keyUp, keycode: keycode, repeatCount: 0, metaState: meta)]
    }

    // MARK: actions (§8.5)

    private mutating func action(_ name: String, pasteText: () -> String?) -> [Scrcpy.Control] {
        switch name {
        // Back also wakes a sleeping screen (scrcpy's BACK_OR_SCREEN_ON).
        case "back": return [.backOrScreenOn(action: Scrcpy.keyDown), .backOrScreenOn(action: Scrcpy.keyUp)]
        case "home": return press(AndroidKey.home)
        case "recents", "mission_control", "app_expose", "last_app": return press(AndroidKey.appSwitch)
        case "app_drawer": return press(AndroidKey.allApps)
        case "notifications": return [.expandNotificationPanel]
        case "quick_settings": return [.expandSettingsPanel]
        case "vol_up": return press(AndroidKey.volUp)
        case "vol_down": return press(AndroidKey.volDown)
        case "vol_mute": return press(AndroidKey.volMute)
        case "brightness_up": return press(AndroidKey.brightnessUp)
        case "brightness_down": return press(AndroidKey.brightnessDown)
        case "media_play_pause": return press(AndroidKey.mediaPlayPause)
        case "media_next": return press(AndroidKey.mediaNext)
        case "media_prev": return press(AndroidKey.mediaPrev)
        case "assistant": return press(AndroidKey.assist)
        case "spotlight": return press(AndroidKey.search)
        case "lock": return press(AndroidKey.sleep)
        case "screenshot": return press(AndroidKey.sysrq)
        case "rotate": return [.rotateDevice]
        case "copy": return [.getClipboard(.copy)]
        case "paste": return pasteControls(pasteText())
        default: return []
        }
    }

    static func num(_ v: Any?) -> Double? {
        switch v {
        case let d as Double: return d
        case let i as Int: return Double(i)
        case let n as NSNumber: return n.doubleValue
        default: return nil
        }
    }
}

/// Parses `adb shell wm size; wm density` output (override wins over physical).
nonisolated enum WMInfo {
    static func parse(_ s: String) -> (width: Int, height: Int, density: Int) {
        var w = 0, h = 0, d = 0
        var ow = 0, oh = 0, od = 0
        for raw in s.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if key.hasSuffix("size") {
                let parts = value.split(separator: "x").compactMap { Int($0) }
                guard parts.count == 2 else { continue }
                if key.hasPrefix("override") { ow = parts[0]; oh = parts[1] } else { w = parts[0]; h = parts[1] }
            } else if key.hasSuffix("density"), let v = Int(value) {
                if key.hasPrefix("override") { od = v } else { d = v }
            }
        }
        return (ow > 0 ? ow : w, oh > 0 ? oh : h, od > 0 ? od : d)
    }
}
