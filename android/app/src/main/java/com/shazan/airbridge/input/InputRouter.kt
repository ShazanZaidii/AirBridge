package com.shazan.airbridge.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.shazan.airbridge.net.PeerBus
import com.shazan.airbridge.net.PeerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * The input half's single registration with [PeerBus]. Fans control messages out to the target
 * side ([TargetController], present while the accessibility service runs) and exposes peer and
 * caps-lock state to the trackpad UI. Never throws back into the network thread.
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

    @Volatile private var warnedNoService = false

    /** Idempotent; call from the accessibility service and from TrackpadActivity. */
    fun install(context: Context) {
        appContext = context.applicationContext
        PeerBus.controlHandler = ::onControl
        PeerBus.peersHandler = ::onPeers
        refreshPeers()
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
            when (msg.optString("t")) {
                "key_state" -> if (msg.has("caps")) {
                    val on = msg.optBoolean("caps", false)
                    _caps.update { it + (peerId to on) }
                }
                else -> {
                    val t = target
                    if (t != null) t.onControl(peerId, msg)
                    else if (msg.optString("t") == "ctl_enter") warnNoService()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Dropped control message from $peerId", t)
        }
    }

    private fun onPeers(list: List<PeerInfo>) {
        try {
            _peers.value = list
            val ids = list.mapTo(HashSet()) { it.id }
            _caps.update { m -> m.filterKeys { it in ids } }
            target?.onPeers(list)
        } catch (t: Throwable) {
            Log.w(TAG, "peers update failed", t)
        }
    }

    private fun warnNoService() {
        if (warnedNoService) return
        warnedNoService = true
        val ctx = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, "Enable the AirBridge accessibility service to control this phone from your computer", Toast.LENGTH_LONG).show()
        }
    }
}
