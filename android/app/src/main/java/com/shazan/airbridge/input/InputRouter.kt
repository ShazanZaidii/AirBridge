package com.shazan.airbridge.input

import android.content.Context
import android.util.Log
import com.shazan.airbridge.AirBridgeInputService
import com.shazan.airbridge.RemoteControlSetup
import com.shazan.airbridge.net.PeerBus
import com.shazan.airbridge.net.PeerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** A peer told us (as controller) that our input is being dropped (§8.6). */
data class BusyInfo(val holder: String, val atMillis: Long)

/**
 * The input half's single registration with [PeerBus]. Fans control messages out to the target
 * side ([TargetController], present while the accessibility service runs), to the controller side
 * (lease banner, screen-mirror sink) and exposes peer and caps-lock state to the trackpad UI.
 * Never throws back into the network thread.
 */
object InputRouter {
    private const val TAG = "AirBridgeInput"

    @Volatile private var appContext: Context? = null
    @Volatile var target: TargetController? = null

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers

    /** Last `key_state.caps` reported by each peer (absent = never reported). */
    private val _caps = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val capsByPeer: StateFlow<Map<String, Boolean>> = _caps

    /** Latest `ctl_busy` per peer; cleared by any other inbound control message from that peer. */
    private val _busy = MutableStateFlow<Map<String, BusyInfo>>(emptyMap())
    val busyByPeer: StateFlow<Map<String, BusyInfo>> = _busy

    private val _controlledBy = MutableStateFlow<String?>(null)
    /** Peer holding this phone's control lease right now (§8.6), or null. Drives "Controlling you". */
    val controlledBy: StateFlow<String?> = _controlledBy

    /**
     * Peer this phone is driving right now (trackpad / keyboard / screen mode in front), or null.
     * §8.6 "No loops": that peer's `ctl_enter` is refused with `reason=loop`, its input gets `ctl_busy`.
     */
    @Volatile var driving: String? = null

    internal fun setControlledBy(peerId: String?) { _controlledBy.value = peerId }

    /** "Stop being controlled": send `ctl_leave` to the holder and drop its lease. */
    fun stopBeingControlled() {
        val t = target
        if (t != null) t.main.post { try { t.releaseControl("user") } catch (e: Throwable) { Log.w(TAG, "release", e) } }
        else _controlledBy.value?.let { h ->
            try { PeerBus.send(h, JSONObject().put("t", "ctl_leave")) } catch (_: Throwable) {}
            _controlledBy.value = null
        }
    }

    /** Screen-mirror sink (controller side): `mirror_info` / `mirror_stop` from a source. */
    @Volatile var mirrorControl: ((peerId: String, msg: JSONObject) -> Unit)? = null
    /** Screen-mirror sink: inner binary frames of kind 0x03. */
    @Volatile var mirrorFrames: ((peerId: String, frame: ByteArray) -> Unit)? = null

    /** Message types handled by the target role. */
    private val TARGET_TYPES = setOf(
        "ctl_enter", "ctl_leave", "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click", "ptr_abs", "key", "action",
    )

    /** Idempotent; call from the accessibility service and from TrackpadActivity. */
    fun install(context: Context) {
        appContext = context.applicationContext
        PeerBus.controlHandler = ::onControl
        PeerBus.binaryHandler = ::onBinary
        PeerBus.peersHandler = ::onPeers
        refreshPeers()
    }

    fun clearBusy(peerId: String) {
        _busy.update { if (peerId in it) it - peerId else it }
    }

    fun refreshPeers() {
        try { _peers.value = PeerBus.peers() } catch (t: Throwable) { Log.w(TAG, "peers() failed", t) }
    }

    /** Optimistic local update after we pressed Caps Lock on a peer that reports its state. */
    fun setCapsOptimistic(peerId: String, on: Boolean) {
        _caps.update { if (peerId in it) it + (peerId to on) else it }
    }

    private fun onControl(peerId: String, msg: JSONObject) {
        try {
            val type = msg.optString("t")
            if (type == "ctl_busy") {
                val holder = msg.optString("holder", "").ifBlank { "another device" }
                _busy.update { it + (peerId to BusyInfo(holder, System.currentTimeMillis())) }
                return
            }
            clearBusy(peerId) // the peer is talking to us normally again
            when (type) {
                "key_state" -> if (msg.has("caps")) {
                    val on = msg.optBoolean("caps", false)
                    _caps.update { it + (peerId to on) }
                }
                "mirror_info", "mirror_stop" -> mirrorControl?.invoke(peerId, msg)
                // Only reaches us if the network half routes it (not in CONTROL_TYPES today).
                "error" -> if (msg.optString("code").startsWith("mirror_")) mirrorControl?.invoke(peerId, msg)
                // We never advertise mirror_source: mirror_start/key/ack addressed to us are ignored.
                in TARGET_TYPES -> {
                    val t = target
                    if (t != null && AirBridgeInputService.isRunning) t.onControl(peerId, msg)
                    else if (type == "ctl_enter") refuseUnavailable(peerId)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Dropped control message from $peerId", t)
        }
    }

    private fun onBinary(peerId: String, frame: ByteArray) {
        try {
            if (frame.isEmpty() || (frame[0].toInt() and 0xFF) != PeerBus.BINARY_KIND_VIDEO) return
            mirrorFrames?.invoke(peerId, frame)
        } catch (t: Throwable) {
            Log.w(TAG, "Dropped binary frame from $peerId", t)
        }
    }

    private fun onPeers(list: List<PeerInfo>) {
        try {
            _peers.value = list
            val ids = list.mapTo(HashSet()) { it.id }
            _caps.update { m -> m.filterKeys { it in ids } }
            _busy.update { m -> m.filterKeys { it in ids } }
            // No service = no lease table; never leave a stale "Controlling you".
            if (target == null) _controlledBy.value?.let { if (it !in ids) _controlledBy.value = null }
            target?.onPeers(list)
        } catch (t: Throwable) {
            Log.w(TAG, "peers update failed", t)
        }
    }

    /**
     * §8.1: the accessibility service is off, so we can neither show a cursor nor inject input.
     * Answer at once so the controller releases its pointer, never take the lease, and tell the user.
     */
    private fun refuseUnavailable(peerId: String) {
        val sent = try {
            PeerBus.send(peerId, JSONObject().put("t", "ctl_leave").put("reason", TargetAck.UNAVAILABLE))
        } catch (t: Throwable) { Log.w(TAG, "unavailable reply failed", t); false }
        Log.i(TAG, "ctl_enter from $peerId refused: accessibility service off (reply sent=$sent)")
        val ctx = appContext ?: return
        val plat = try { _peers.value.firstOrNull { it.id == peerId }?.platform ?: PeerBus.peers().firstOrNull { it.id == peerId }?.platform } catch (_: Throwable) { null }
        RemoteControlSetup.onRefused(ctx, plat)
    }
}
