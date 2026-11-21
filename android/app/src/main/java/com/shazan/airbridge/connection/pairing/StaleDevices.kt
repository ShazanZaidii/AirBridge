package com.shazan.airbridge.connection.pairing

/**
 * Old entries for a device that was reinstalled. A device gets a new id on every install, so the paired list would keep
 * one dead entry per reinstall. Names can't tell them apart (a person can own two devices with the same name), but the
 * hardware fingerprint can: it is the same for one physical device and different for any other.
 */
object StaleDevices {
    /**
     * The ids of paired entries that are the same physical device as the one that just connected as [currentId] with
     * fingerprint [hw], under an older install. Entries that are connected right now are never touched (two installs
     * of the app can legitimately run on one device at once), and neither is anything without a fingerprint.
     */
    fun superseded(currentId: String, hw: String?, paired: List<PairedPeer>, connected: Set<String>): List<String> =
        if (hw.isNullOrBlank()) emptyList()
        else paired.filter { it.id != currentId && it.hw == hw && it.id !in connected }.map { it.id }
}
