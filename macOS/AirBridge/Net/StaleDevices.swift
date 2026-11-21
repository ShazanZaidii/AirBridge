import Foundation

/// Old entries for a device that was reinstalled. A device gets a new id on every install, so the paired list would keep
/// one dead entry per reinstall. Names can't tell them apart (a person can own two devices with the same name), but the
/// hardware fingerprint can: it is the same for one physical device and different for any other. Entries from before
/// fingerprints existed have none, so those are only ever *offered* for removal, never removed without a yes.
nonisolated enum StaleDevices {
    struct Entry: Equatable, Sendable {
        let id: String
        let name: String
        let plat: String
        let hw: String?
    }

    /// Entries that are the same physical device as `current` (just connected, fingerprint `hw`) under an older install.
    /// Anything connected right now is left alone (two installs can run on one device at once).
    static func superseded(current: String, hw: String?, among: [Entry], connected: Set<String>) -> [Entry] {
        guard let hw, !hw.isEmpty else { return [] }
        return among.filter { $0.id != current && $0.hw == hw && !connected.contains($0.id) }
    }

    /// Entries with no fingerprint that look like earlier installs of `current` (same kind of device and same name).
    /// Only to ask the user about: two real devices can share a name. Not offered again once the user said keep.
    static func candidates(current: Entry, among: [Entry], connected: Set<String>, dismissed: Set<String>) -> [Entry] {
        among.filter {
            $0.id != current.id && $0.hw == nil && $0.plat == current.plat && $0.name == current.name
                && !connected.contains($0.id) && !dismissed.contains($0.id)
        }
    }
}
