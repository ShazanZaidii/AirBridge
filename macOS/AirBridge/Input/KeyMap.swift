import CoreGraphics

/// Carbon `kVK_*` virtual keycode ↔ USB HID usage (page 0x07). protocol.md §8.4.
nonisolated enum KeyMap {
    /// Canonical pairs. ISO keys use the ANSI-position convention (kVK_ANSI_Grave ↔ 0x35,
    /// kVK_ISO_Section ↔ 0x64); PC-only keys map to the keys macOS itself maps them to.
    private static let pairs: [(UInt16, Int)] = [
        (0x00, 0x04), (0x0B, 0x05), (0x08, 0x06), (0x02, 0x07), (0x0E, 0x08), (0x03, 0x09), // A B C D E F
        (0x05, 0x0A), (0x04, 0x0B), (0x22, 0x0C), (0x26, 0x0D), (0x28, 0x0E), (0x25, 0x0F), // G H I J K L
        (0x2E, 0x10), (0x2D, 0x11), (0x1F, 0x12), (0x23, 0x13), (0x0C, 0x14), (0x0F, 0x15), // M N O P Q R
        (0x01, 0x16), (0x11, 0x17), (0x20, 0x18), (0x09, 0x19), (0x0D, 0x1A), (0x07, 0x1B), // S T U V W X
        (0x10, 0x1C), (0x06, 0x1D),                                                          // Y Z
        (0x12, 0x1E), (0x13, 0x1F), (0x14, 0x20), (0x15, 0x21), (0x17, 0x22),                // 1 2 3 4 5
        (0x16, 0x23), (0x1A, 0x24), (0x1C, 0x25), (0x19, 0x26), (0x1D, 0x27),                // 6 7 8 9 0
        (0x24, 0x28), (0x35, 0x29), (0x33, 0x2A), (0x30, 0x2B), (0x31, 0x2C),                // Return Esc Delete Tab Space
        (0x1B, 0x2D), (0x18, 0x2E), (0x21, 0x2F), (0x1E, 0x30), (0x2A, 0x31),                // - = [ ] \
        (0x29, 0x33), (0x27, 0x34), (0x32, 0x35), (0x2B, 0x36), (0x2F, 0x37), (0x2C, 0x38),  // ; ' ` , . /
        (0x39, 0x39),                                                                        // Caps Lock
        (0x7A, 0x3A), (0x78, 0x3B), (0x63, 0x3C), (0x76, 0x3D), (0x60, 0x3E), (0x61, 0x3F),  // F1-F6
        (0x62, 0x40), (0x64, 0x41), (0x65, 0x42), (0x6D, 0x43), (0x67, 0x44), (0x6F, 0x45),  // F7-F12
        (0x72, 0x49), (0x73, 0x4A), (0x74, 0x4B), (0x75, 0x4C), (0x77, 0x4D), (0x79, 0x4E),  // Help/Insert Home PgUp FwdDel End PgDn
        (0x7C, 0x4F), (0x7B, 0x50), (0x7D, 0x51), (0x7E, 0x52),                              // → ← ↓ ↑
        (0x47, 0x53),                                                                        // Keypad Clear / Num Lock
        (0x4B, 0x54), (0x43, 0x55), (0x4E, 0x56), (0x45, 0x57), (0x4C, 0x58),                // Keypad / * - + Enter
        (0x53, 0x59), (0x54, 0x5A), (0x55, 0x5B), (0x56, 0x5C), (0x57, 0x5D),                // Keypad 1-5
        (0x58, 0x5E), (0x59, 0x5F), (0x5B, 0x60), (0x5C, 0x61), (0x52, 0x62), (0x41, 0x63),  // Keypad 6-9 0 .
        (0x0A, 0x64),                                                                        // ISO § / Non-US \
        (0x6E, 0x65),                                                                        // PC Application/Menu
        (0x51, 0x67),                                                                        // Keypad =
        (0x69, 0x68), (0x6B, 0x69), (0x71, 0x6A), (0x6A, 0x6B),                              // F13 F14 F15 F16
        (0x40, 0x6C), (0x4F, 0x6D), (0x50, 0x6E), (0x5A, 0x6F),                              // F17 F18 F19 F20
        (0x5F, 0x85),                                                                        // JIS keypad comma
        (0x5E, 0x87), (0x5D, 0x89),                                                          // JIS _ (Ro), JIS ¥
        (0x68, 0x90), (0x66, 0x91),                                                          // JIS Kana (LANG1), Eisu (LANG2)
        (0x3B, 0xE0), (0x38, 0xE1), (0x3A, 0xE2), (0x37, 0xE3),                              // LCtrl LShift LOpt LCmd
        (0x3E, 0xE4), (0x3C, 0xE5), (0x3D, 0xE6), (0x36, 0xE7),                              // RCtrl RShift ROpt RCmd
    ]

    /// HID-only aliases (no distinct Mac key): PC keys that macOS maps onto existing keycodes.
    private static let hidAliases: [Int: UInt16] = [
        0x32: 0x2A,  // Non-US # → backslash
        0x46: 0x69,  // PrintScreen → F13
        0x47: 0x6B,  // ScrollLock → F14
        0x48: 0x71,  // Pause → F15
        0x75: 0x72,  // Help
        0x76: 0x6E,  // Menu
        0x86: 0x51,  // Keypad = (AS/400)
        0x88: 0x68,  // Katakana/Hiragana → Kana
        0x8A: 0x68,  // Henkan → Kana
        0x8B: 0x66,  // Muhenkan → Eisu
    ]

    private static let macToHid: [UInt16: Int] = Dictionary(uniqueKeysWithValues: pairs.map { ($0.0, $0.1) })
    private static let hidToMac: [Int: UInt16] = {
        var m = Dictionary(uniqueKeysWithValues: pairs.map { ($0.1, $0.0) })
        for (h, k) in hidAliases where m[h] == nil { m[h] = k }
        return m
    }()

    static func hid(forMac keycode: UInt16) -> Int? { macToHid[keycode] }
    static func mac(forHid usage: Int) -> UInt16? { hidToMac[usage] }

    static func isModifierHid(_ usage: Int) -> Bool { (0xE0...0xE7).contains(usage) }

    static let modifierKeycodes: [UInt16] = [0x38, 0x3C, 0x3B, 0x3E, 0x3A, 0x3D, 0x37, 0x36]

    /// Device-dependent (left/right) flag bit + generic flag for each modifier keycode.
    static func modifierFlags(forMac keycode: UInt16) -> (device: UInt64, generic: CGEventFlags)? {
        switch keycode {
        case 0x3B: return (0x0000_0001, .maskControl)    // LCtrl
        case 0x3E: return (0x0000_2000, .maskControl)    // RCtrl
        case 0x38: return (0x0000_0002, .maskShift)      // LShift
        case 0x3C: return (0x0000_0004, .maskShift)      // RShift
        case 0x3A: return (0x0000_0020, .maskAlternate)  // LOpt
        case 0x3D: return (0x0000_0040, .maskAlternate)  // ROpt
        case 0x37: return (0x0000_0008, .maskCommand)    // LCmd
        case 0x36: return (0x0000_0010, .maskCommand)    // RCmd
        default: return nil
        }
    }

    /// Keys whose real hardware events carry the Fn + numeric-pad flags; system hotkeys such as
    /// Ctrl+↑ (Mission Control) only match when those flags are present.
    static func extraFlags(forMac keycode: UInt16) -> CGEventFlags {
        switch keycode {
        case 0x7B, 0x7C, 0x7D, 0x7E: return [.maskSecondaryFn, .maskNumericPad]
        case 0x72, 0x73, 0x74, 0x75, 0x77, 0x79: return [.maskSecondaryFn]
        case 0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F,
             0x69, 0x6B, 0x71, 0x6A, 0x40, 0x4F, 0x50, 0x5A: return [.maskSecondaryFn]
        case 0x41, 0x43, 0x45, 0x47, 0x4B, 0x4C, 0x4E, 0x51, 0x52...0x59, 0x5B, 0x5C, 0x5F:
            return [.maskNumericPad]
        default: return []
        }
    }

    /// Protocol `mods` bitmask → CGEventFlags (left-side device bits).
    static func flags(fromMods mods: Int) -> CGEventFlags {
        var raw: UInt64 = 0
        var f: CGEventFlags = []
        if mods & 1 != 0 { f.insert(.maskShift); raw |= 0x02 }
        if mods & 2 != 0 { f.insert(.maskControl); raw |= 0x01 }
        if mods & 4 != 0 { f.insert(.maskAlternate); raw |= 0x20 }
        if mods & 8 != 0 { f.insert(.maskCommand); raw |= 0x08 }
        if mods & 16 != 0 { f.insert(.maskAlphaShift) }
        return CGEventFlags(rawValue: f.rawValue | raw)
    }

    /// Apple-keyboard function-row keycodes with no HID page-7 usage → protocol `action`.
    static func action(forMac keycode: UInt16) -> String? {
        switch keycode {
        case 0x48: return "vol_up"            // kVK_VolumeUp
        case 0x49: return "vol_down"          // kVK_VolumeDown
        case 0x4A: return "vol_mute"          // kVK_Mute
        case 0xA0: return "mission_control"   // Mission Control key
        case 0x83: return "app_drawer"        // Launchpad key
        case 0xB1: return "spotlight"         // Spotlight key
        case 0xB0: return "assistant"         // Dictation key
        case 0xB2: return "dnd_toggle"        // Do Not Disturb key
        case 0x90: return "brightness_up"
        case 0x91: return "brightness_down"
        default: return nil
        }
    }

    /// NX_KEYTYPE_* (system-defined subtype 8 events) → protocol `action`.
    static func action(forNXKeyType t: Int) -> String? {
        switch t {
        case 0: return "vol_up"
        case 1: return "vol_down"
        case 2: return "brightness_up"
        case 3: return "brightness_down"
        case 7: return "vol_mute"
        case 16: return "media_play_pause"
        case 17, 19: return "media_next"
        case 18, 20: return "media_prev"
        default: return nil
        }
    }

    static func nxKeyType(forAction name: String) -> Int? {
        switch name {
        case "vol_up": return 0
        case "vol_down": return 1
        case "brightness_up": return 2
        case "brightness_down": return 3
        case "vol_mute": return 7
        case "media_play_pause": return 16
        case "media_next": return 17
        case "media_prev": return 18
        default: return nil
        }
    }
}
