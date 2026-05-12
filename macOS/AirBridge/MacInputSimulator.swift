//
//  MacInputSimulator.swift
//  AirBridge
//
//  Created by Shazan Zaidi on 12/05/26.
//

import Cocoa
import CoreGraphics

class MacInputSimulator {
    static var scrollYAcc: CGFloat = 0
    static var scrollXAcc: CGFloat = 0
    
    // Sticky Modifiers: Touchscreens can't "hold" keys, so we toggle them
    static var activeModifiers: CGEventFlags = []
    
    static let keyMap: [String: UInt16] = [
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9,
        "b": 11, "q": 12, "w": 13, "e": 14, "r": 15, "y": 16, "t": 17,
        "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "=": 24, "9": 25,
        "7": 26, "-": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33,
        "i": 34, "p": 35, "l": 37, "j": 38, "'": 39, "k": 40, ";": 41, "\\": 42,
        ",": 43, "/": 44, "n": 45, "m": 46, ".": 47, "`": 50, " ": 49
    ]

    static var currentMousePos: CGPoint {
        return CGEvent(source: nil)?.location ?? .zero
    }

    static func moveMouse(dx: CGFloat, dy: CGFloat) {
        let sensitivity: CGFloat = 1.8
        var pos = currentMousePos
        pos.x += (dx * sensitivity)
        pos.y += (dy * sensitivity)
        
        let event = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: pos, mouseButton: .left)
        event?.post(tap: .cghidEventTap)
    }

    static func scroll(dx: CGFloat, dy: CGFloat) {
        let scrollMultiplier: CGFloat = 0.5
        scrollXAcc += dx * scrollMultiplier
        scrollYAcc += dy * scrollMultiplier
        
        let intX = Int32(scrollXAcc)
        let intY = Int32(scrollYAcc)
        
        if intX != 0 || intY != 0 {
            let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: intY, wheel2: intX, wheel3: 0)
            event?.post(tap: .cghidEventTap)
            scrollXAcc -= CGFloat(intX)
            scrollYAcc -= CGFloat(intY)
        }
    }

    static func tap() {
        leftClickDown()
        usleep(20_000)
        leftClickUp()
    }
    
    static func doubleTap() {
        let pos = currentMousePos
        guard let eventDown1 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: pos, mouseButton: .left),
              let eventUp1 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: pos, mouseButton: .left),
              let eventDown2 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: pos, mouseButton: .left),
              let eventUp2 = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: pos, mouseButton: .left) else { return }
        
        eventDown1.setIntegerValueField(.mouseEventClickState, value: 1)
        eventUp1.setIntegerValueField(.mouseEventClickState, value: 1)
        eventDown1.post(tap: .cghidEventTap)
        usleep(20_000)
        eventUp1.post(tap: .cghidEventTap)
        
        usleep(50_000)
        
        eventDown2.setIntegerValueField(.mouseEventClickState, value: 2)
        eventUp2.setIntegerValueField(.mouseEventClickState, value: 2)
        eventDown2.post(tap: .cghidEventTap)
        usleep(20_000)
        eventUp2.post(tap: .cghidEventTap)
    }

    static func leftClickDown() {
        let event = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: currentMousePos, mouseButton: .left)
        event?.post(tap: .cghidEventTap)
    }

    static func leftClickUp() {
        let event = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: currentMousePos, mouseButton: .left)
        event?.post(tap: .cghidEventTap)
    }

    // 🔥 FIX: Uses NSEvent to bypass Swift's missing CGEvent systemDefined data fields
    static func pressMediaKey(_ key: Int) {
        let data1Down = (key << 16) | (0xa << 8)
        let eventDown = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, subtype: 8, data1: data1Down, data2: -1)
        eventDown?.cgEvent?.post(tap: .cghidEventTap)
        
        usleep(20_000)
        
        let data1Up = (key << 16) | (0xb << 8)
        let eventUp = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, subtype: 8, data1: data1Up, data2: -1)
        eventUp?.cgEvent?.post(tap: .cghidEventTap)
    }

    static func pressKeyCode(_ keyCode: UInt16) {
        // 1. Modifiers toggle
        if [56, 59, 58, 55].contains(keyCode) {
            let flag: CGEventFlags
            switch keyCode {
            case 56: flag = .maskShift
            case 59: flag = .maskControl
            case 58: flag = .maskAlternate
            case 55: flag = .maskCommand
            default: return
            }
            if activeModifiers.contains(flag) { activeModifiers.remove(flag) }
            else { activeModifiers.insert(flag) }
            return
        }

        // 2. Hardware Media Keys intercepts
        let mediaMap: [UInt16: Int] = [
            145: 3,  // Brightness Down
            144: 2,  // Brightness Up
            100: 16, // Play
            98: 18,  // Prev
            101: 17, // Next
            109: 7,  // Mute
            103: 1,  // Vol Down
            104: 0   // Vol Up
        ]
        if let mKey = mediaMap[keyCode] {
            pressMediaKey(mKey)
            return
        }

        // 3. Custom F-Row Actions
        if keyCode == 160 { executeShortcut(keyCode: 126); return } // F3 (Mission Control)
        if keyCode == 131 { // F4 (Search / Spotlight)
            activeModifiers = [.maskCommand]
            pressKeyCode(49) // Cmd + Space
            return
        }
        if keyCode == 127 { // Power / Lock
            let script = "tell application \"System Events\" to sleep"
            let task = Process()
            task.launchPath = "/usr/bin/osascript"
            task.arguments = ["-e", script]
            task.launch()
            return
        }
        
        // 4. Normal Keystroke Engine (CRITICAL: .hidSystemState bypasses sandboxing!)
        let src = CGEventSource(stateID: .hidSystemState)
        guard let eventDown = CGEvent(keyboardEventSource: src, virtualKey: CGKeyCode(keyCode), keyDown: true),
              let eventUp = CGEvent(keyboardEventSource: src, virtualKey: CGKeyCode(keyCode), keyDown: false) else { return }
        
        if !activeModifiers.isEmpty {
            eventDown.flags = activeModifiers
            eventUp.flags = activeModifiers
        }
        
        eventDown.post(tap: .cghidEventTap)
        usleep(20_000)
        eventUp.post(tap: .cghidEventTap)
        
        activeModifiers = []
    }

    static func typeString(_ string: String) {
        if let code = keyMap[string.lowercased()] {
            pressKeyCode(code)
        } else {
            let script = "tell application \"System Events\" to keystroke \"\(string)\""
            let task = Process()
            task.launchPath = "/usr/bin/osascript"
            task.arguments = ["-e", script]
            task.launch()
            activeModifiers = []
        }
    }

    static func executeShortcut(keyCode: Int) {
        let script = "tell application \"System Events\" to key code \(keyCode) using control down"
        let task = Process()
        task.launchPath = "/usr/bin/osascript"
        task.arguments = ["-e", script]
        task.launch()
    }
    
    // MARK: - Added to satisfy SyncServer calls
    
    // Toggle macOS Accessibility Keyboard (System Settings -> Accessibility -> Keyboard -> Accessibility Keyboard)
    // This uses UI scripting to toggle the menu bar item if available; otherwise attempts to open the pane.
    static func toggleAccessibilityKeyboard() {
        // Try to toggle via menu bar (if the Accessibility Keyboard is running)
        let script = """
        tell application "System Events"
            if exists (process "Accessibility Keyboard") then
                tell process "Accessibility Keyboard"
                    try
                        click menu bar item 1 of menu bar 1
                    on error
                        -- If click fails, quit the app
                        tell application "Accessibility Keyboard" to quit
                    end try
                end tell
            else
                -- Launch Accessibility Keyboard via System Settings toggle
                try
                    tell application "System Settings" to activate
                    delay 0.2
                    tell application "System Events"
                        tell process "System Settings"
                            try
                                click button "Keyboard" of scroll area 1 of group 1 of window 1
                            end try
                        end tell
                    end tell
                end try
                try
                    do shell script "open -b com.apple.Accessibility-AccessibilityKeyboard"
                end try
            end if
        end tell
        """
        let task = Process()
        task.launchPath = "/usr/bin/osascript"
        task.arguments = ["-e", script]
        task.launch()
    }
    
    static func missionControl() {
        // Control + Up Arrow (key code 126)
        executeShortcut(keyCode: 126)
    }
    
    static func appExpose() {
        // Control + Down Arrow (key code 125)
        executeShortcut(keyCode: 125)
    }
    
    static func spaceLeft() {
        // Control + Left Arrow (key code 123)
        executeShortcut(keyCode: 124)
    }
    
    static func spaceRight() {
        // Control + Right Arrow (key code 124)
        executeShortcut(keyCode: 123)
    }
}

