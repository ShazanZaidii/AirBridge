package com.shazan.airbridge.phone

import android.media.AudioDeviceInfo

/**
 * Where the phone can play a call (§13.5 `routes`): the phone's own call audio devices as the computers see them.
 * Pure (Android's device type constants only), unit-tested; [CallWatcher] reads the devices and switches.
 *
 * Ids are "phone:<kind>" for built-in devices and "phone:<kind>:<address>" for headsets, so a computer can ask for
 * exactly that one back (`call_action` op `route`).
 */
object CallRoutes {
    data class Dev(val type: Int, val name: String?, val address: String?)

    fun kind(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt"
        TYPE_BLE_HEADSET -> "ble"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        else -> null
    }

    private fun builtIn(kind: String) = kind == "earpiece" || kind == "speaker"

    fun id(d: Dev): String? {
        val k = kind(d.type) ?: return null
        val a = d.address?.trim().orEmpty()
        return if (builtIn(k) || a.isEmpty()) "phone:$k" else "phone:$k:${a.take(40)}"
    }

    /**
     * The routes to report, in a stable order (earpiece, speaker, wired/USB, Bluetooth), without duplicates. [isComputer]
     * marks a headset that is really a connected computer (its hands-free unit); it is reported with `computer:true`.
     */
    fun routes(devs: List<Dev>, isComputer: (Dev) -> Boolean): List<NotifWire.Route> {
        val order = listOf("earpiece", "speaker", "wired", "usb", "bt", "ble", "hearing_aid")
        val seen = HashSet<String>()
        return devs.mapNotNull { d ->
            val k = kind(d.type) ?: return@mapNotNull null
            val id = id(d) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            NotifWire.Route(id, k, d.name?.trim()?.takeIf { !builtIn(k) && it.isNotEmpty() }?.take(60), computer = !builtIn(k) && isComputer(d))
        }.sortedBy { order.indexOf(it.kind) }
    }

    /** What choosing [id] means. */
    sealed class Pick {
        object Speaker : Pick()
        object Earpiece : Pick()
        data class Device(val kind: String, val address: String?) : Pick()
    }

    fun parse(id: String): Pick? {
        val parts = id.split(":", limit = 3)
        if (parts.size < 2 || parts[0] != "phone") return null
        return when (parts[1]) {
            "speaker" -> Pick.Speaker
            "earpiece" -> Pick.Earpiece
            "wired", "usb", "bt", "ble", "hearing_aid" -> Pick.Device(parts[1], parts.getOrNull(2))
            else -> null
        }
    }

    /** `AudioDeviceInfo.TYPE_BLE_HEADSET` (API 31; the value, so older SDK stubs need nothing). */
    const val TYPE_BLE_HEADSET = 26
}
