import Foundation

/// Legacy v1 shim kept only so the old SyncServer compiles; translates to v2 messages for
/// `Injector`. Delete together with SyncServer.swift.
nonisolated enum MacInputSimulator {
    private static let peer = "legacy-v1"
    private static func send(_ m: [String: Any]) { Injector.shared.submit(peer, m) }

    static func moveMouse(dx: CGFloat, dy: CGFloat) { send(["t": "ptr_move", "dx": Double(dx), "dy": Double(dy)]) }
    static func scroll(dx: CGFloat, dy: CGFloat) { send(["t": "ptr_scroll", "dx": Double(dx), "dy": Double(dy), "px": true]) }
    static func tap() { send(["t": "ptr_click", "b": 0, "n": 1]) }
    static func doubleTap() { send(["t": "ptr_click", "b": 0, "n": 2]) }
    static func leftClickDown() { send(["t": "ptr_btn", "b": 0, "down": true]) }
    static func leftClickUp() { send(["t": "ptr_btn", "b": 0, "down": false]) }
    static func typeString(_ s: String) { send(["t": "key", "hid": 0, "down": true, "text": s]) }
    static func pressKeyCode(_ keyCode: UInt16) {
        guard let hid = KeyMap.hid(forMac: keyCode) else { return }
        send(["t": "key", "hid": hid, "down": true])
        send(["t": "key", "hid": hid, "down": false])
    }
    static func toggleAccessibilityKeyboard() { send(["t": "action", "name": "keyboard_toggle"]) }
    static func missionControl() { send(["t": "action", "name": "mission_control"]) }
    static func appExpose() { send(["t": "action", "name": "app_expose"]) }
    static func spaceLeft() { send(["t": "action", "name": "desktop_left"]) }
    static func spaceRight() { send(["t": "action", "name": "desktop_right"]) }
}
