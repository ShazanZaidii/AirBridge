import Foundation

// Checks the reinstall rules (Net/StaleDevices.swift) and the layout cleanup (Input/Layout.swift). Layout stores into the
// tool's own defaults domain, never the app's. No Keychain, no network.
var passed = 0, failed = 0
func check(_ name: String, _ ok: Bool) { if ok { passed += 1 } else { failed += 1; print("FAIL: \(name)") } }
typealias E = StaleDevices.Entry
func e(_ id: String, _ name: String = "V2404", _ plat: String = "android", hw: String? = nil) -> E { E(id: id, name: name, plat: plat, hw: hw) }

// --- superseded: same physical device under an older install
let paired = [e("old1", hw: "H"), e("old2", hw: "H"), e("new", hw: "H"), e("other", hw: "OTHER"), e("legacy")]
check("same fingerprint, other ids are superseded", Set(StaleDevices.superseded(current: "new", hw: "H", among: paired, connected: []).map(\.id)) == ["old1", "old2"])
check("a different fingerprint is kept even with the same name", !StaleDevices.superseded(current: "new", hw: "H", among: paired, connected: []).contains { $0.id == "other" })
check("no fingerprint is never guessed at", !StaleDevices.superseded(current: "new", hw: "H", among: paired, connected: []).contains { $0.id == "legacy" })
check("nothing without a fingerprint from the device", StaleDevices.superseded(current: "new", hw: nil, among: paired, connected: []).isEmpty)
check("an entry connected right now is left alone", StaleDevices.superseded(current: "new", hw: "H", among: paired, connected: ["old1"]).map(\.id) == ["old2"])
let twins = [e("a", "My phone", hw: "H1"), e("b", "My phone", hw: "H2")]
check("two devices with one name are both kept", StaleDevices.superseded(current: "b", hw: "H2", among: twins, connected: []).isEmpty)

// --- candidates: older entries without a fingerprint, only to ask about
let me = e("new", hw: "H")
let legacy = [e("l1"), e("l2"), e("lava", "LAVA LXX516"), e("mac", "V2404", "macos"), e("withhw", hw: "X"), e("live")]
check("same kind and name, no fingerprint: offered", Set(StaleDevices.candidates(current: me, among: legacy, connected: ["live"], dismissed: []).map(\.id)) == ["l1", "l2"])
check("a different name is not offered", !StaleDevices.candidates(current: me, among: legacy, connected: [], dismissed: []).contains { $0.id == "lava" })
check("a different kind of device is not offered", !StaleDevices.candidates(current: me, among: legacy, connected: [], dismissed: []).contains { $0.id == "mac" })
check("an entry with a fingerprint is not offered", !StaleDevices.candidates(current: me, among: legacy, connected: [], dismissed: []).contains { $0.id == "withhw" })
check("a connected entry is not offered", !StaleDevices.candidates(current: me, among: legacy, connected: ["live"], dismissed: []).contains { $0.id == "live" })
check("kept entries are not offered again", StaleDevices.candidates(current: me, among: legacy, connected: ["live"], dismissed: ["l1"]).map(\.id) == ["l2"])

// --- layout: the arrangement follows a reinstalled device, dead ids go
UserDefaults.standard.removeObject(forKey: "AirBridge.layout.v2")
let L = Layout.shared
L.set("old1", edge: .right, start: 0.2, end: 0.8, name: "V2404")
L.set("gone", edge: .left, start: 0, end: 1, name: "Old")
L.migrate(from: "old1", to: "new")
check("placement moved to the new id", L.placement(for: "new")?.edge == .right && L.placement(for: "new")?.start == 0.2)
check("old id no longer has a placement", L.placement(for: "old1") == nil)
L.set("new2", edge: .top, start: 0, end: 1, name: "X")
L.migrate(from: "gone", to: "new2")
check("migrate never overwrites an existing placement", L.placement(for: "new2")?.edge == .top && L.placement(for: "gone") == nil)
L.set("stale", edge: .bottom, start: 0, end: 1, name: "S")
L.prune(keeping: ["new", "new2"])
check("prune drops unpaired devices", L.placement(for: "stale") == nil)
check("prune keeps paired devices", L.placement(for: "new") != nil && L.placement(for: "new2") != nil)
L.remove("new2")
check("remove forgets one placement", L.placement(for: "new2") == nil)
check("the right edge is assigned to the new id", L.assignment(for: .right)?.id == "new")

UserDefaults.standard.removeObject(forKey: "AirBridge.layout.v2")
print("\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
