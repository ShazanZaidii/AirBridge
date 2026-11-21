import Foundation

/// android.view.KeyEvent codes and meta states for Pro mirroring, and the HID usage (page 0x07, §8.4) →
/// Android keycode table. HID codes are physical positions; printable keys usually go as text instead
/// (ProMirrorInput), so non-US Mac layouts type what their keycaps say.
nonisolated enum AndroidKey {
    static let home: UInt32 = 3, back: UInt32 = 4, call: UInt32 = 5
    static let dpadUp: UInt32 = 19, dpadDown: UInt32 = 20, dpadLeft: UInt32 = 21, dpadRight: UInt32 = 22
    static let volUp: UInt32 = 24, volDown: UInt32 = 25, power: UInt32 = 26
    static let a: UInt32 = 29
    static let tab: UInt32 = 61, space: UInt32 = 62, enter: UInt32 = 66, del: UInt32 = 67
    static let search: UInt32 = 84, mediaPlayPause: UInt32 = 85, mediaNext: UInt32 = 87, mediaPrev: UInt32 = 88
    static let escape: UInt32 = 111, forwardDel: UInt32 = 112
    static let moveHome: UInt32 = 122, moveEnd: UInt32 = 123
    static let sysrq: UInt32 = 120
    static let volMute: UInt32 = 164
    static let appSwitch: UInt32 = 187
    static let assist: UInt32 = 219, brightnessDown: UInt32 = 220, brightnessUp: UInt32 = 221
    static let sleep: UInt32 = 223
    static let allApps: UInt32 = 284
    static let copy: UInt32 = 278, paste: UInt32 = 279, cut: UInt32 = 277

    // KeyEvent.META_*
    static let metaShift: UInt32 = 0x1 | 0x40           // SHIFT_ON | SHIFT_LEFT_ON
    static let metaAlt: UInt32 = 0x02 | 0x10            // ALT_ON | ALT_LEFT_ON
    static let metaCtrl: UInt32 = 0x1000 | 0x2000       // CTRL_ON | CTRL_LEFT_ON
    static let metaMeta: UInt32 = 0x10000 | 0x20000     // META_ON | META_LEFT_ON
    static let metaCapsLock: UInt32 = 0x100000

    /// HID usage → KEYCODE_*. Modifiers map too, but ProMirrorInput sends them only as meta state.
    static func keycode(hid: Int) -> UInt32? {
        switch hid {
        case 0x04...0x1D: return a + UInt32(hid - 0x04)             // A…Z
        case 0x1E...0x26: return 8 + UInt32(hid - 0x1E)             // 1…9
        case 0x27: return 7                                         // 0
        case 0x28: return enter
        case 0x29: return escape
        case 0x2A: return del                                       // Backspace
        case 0x2B: return tab
        case 0x2C: return space
        case 0x2D: return 69                                        // -
        case 0x2E: return 70                                        // =
        case 0x2F: return 71                                        // [
        case 0x30: return 72                                        // ]
        case 0x31, 0x32: return 73                                  // \ (and ISO #)
        case 0x33: return 74                                        // ;
        case 0x34: return 75                                        // '
        case 0x35: return 68                                        // `
        case 0x36: return 55                                        // ,
        case 0x37: return 56                                        // .
        case 0x38: return 76                                        // /
        case 0x39: return 115                                       // Caps Lock
        case 0x3A...0x45: return 131 + UInt32(hid - 0x3A)           // F1…F12
        case 0x46: return sysrq                                     // Print Screen
        case 0x47: return 116                                       // Scroll Lock
        case 0x48: return 121                                       // Pause/Break
        case 0x49: return 124                                       // Insert
        case 0x4A: return moveHome
        case 0x4B: return 92                                        // Page Up
        case 0x4C: return forwardDel
        case 0x4D: return moveEnd
        case 0x4E: return 93                                        // Page Down
        case 0x4F: return dpadRight
        case 0x50: return dpadLeft
        case 0x51: return dpadDown
        case 0x52: return dpadUp
        case 0x53: return 143                                       // Num Lock
        case 0x54: return 154                                       // KP /
        case 0x55: return 155                                       // KP *
        case 0x56: return 156                                       // KP -
        case 0x57: return 157                                       // KP +
        case 0x58: return 160                                       // KP Enter
        case 0x59...0x61: return 145 + UInt32(hid - 0x59)           // KP 1…9
        case 0x62: return 144                                       // KP 0
        case 0x63: return 158                                       // KP .
        case 0x64: return 73                                        // ISO \ (non-US)
        case 0x65: return 82                                        // Application (menu)
        case 0x67: return 161                                       // KP =
        case 0x7F: return volMute
        case 0x80: return volUp
        case 0x81: return volDown
        case 0xE0: return 113                                       // Left Ctrl
        case 0xE1: return 59                                        // Left Shift
        case 0xE2: return 57                                        // Left Alt
        case 0xE3: return 117                                       // Left Meta
        case 0xE4: return 114                                       // Right Ctrl
        case 0xE5: return 60                                        // Right Shift
        case 0xE6: return 58                                        // Right Alt
        case 0xE7: return 118                                       // Right Meta
        default: return nil
        }
    }

    static func isModifier(hid: Int) -> Bool { (0xE0...0xE7).contains(hid) || hid == 0x39 }

    /// Protocol `mods` (§8.3: 1 shift, 2 ctrl, 4 alt, 8 cmd/meta, 16 caps) → Android meta state, as typed
    /// on a Mac: ⌘ becomes Ctrl, because Android's shortcuts (copy, paste, undo, select all) are Ctrl-based.
    static func metaState(mods: Int) -> UInt32 {
        var m: UInt32 = 0
        if mods & 1 != 0 { m |= metaShift }
        if mods & (2 | 8) != 0 { m |= metaCtrl }
        if mods & 4 != 0 { m |= metaAlt }
        if mods & 16 != 0 { m |= metaCapsLock }
        return m
    }
}
