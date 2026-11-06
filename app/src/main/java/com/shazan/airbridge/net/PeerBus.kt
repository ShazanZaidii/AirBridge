package com.shazan.airbridge.net

import org.json.JSONObject

/** A peer with a ready (authenticated) session. */
data class PeerInfo(
    val id: String,
    val name: String,
    val platform: String,
    val caps: Set<String>,
    val screenW: Int = 0,
    val screenH: Int = 0,
)

/**
 * Meeting point between the network half (discovery, sessions, pairing, encryption, clipboard, file
 * transfer) and the input half (virtual cursor, trackpad, remote keyboard, screen-mirror sink).
 * See docs/PROTOCOL.md §12.
 *
 * Network half: assigns [transport]; calls [dispatchControl] for control-type messages,
 * [dispatchBinary] for inner binary frames of kind 0x03 (video), and [notifyPeers] whenever the set of
 * ready peers changes. Everything the input half sees is already decrypted.
 *
 * Input half: sets [controlHandler] / [binaryHandler] / [peersHandler]; calls [send] / [broadcast] /
 * [sendBinary] / [peers].
 *
 * Threading: handlers are invoked on a network thread. The input half must hop to the main thread
 * itself where UI or gesture dispatch requires it. Sends are safe from any thread and never block on
 * the network.
 */
object PeerBus {

    /** Message types that belong to the input half (routed through [controlHandler]). */
    val CONTROL_TYPES: Set<String> = setOf(
        "ctl_enter", "ctl_leave", "ctl_ack", "ctl_busy",
        "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click", "ptr_abs",
        "key", "key_state", "action",
        "mirror_start", "mirror_info", "mirror_key", "mirror_ack", "mirror_stop",
    )

    /** Inner binary frame kinds routed to the input half (first byte of the inner frame). */
    const val BINARY_KIND_VIDEO: Int = 0x03

    interface Transport {
        /** Send to one peer. Returns false if that peer has no ready session. */
        fun send(peerId: String, msg: JSONObject): Boolean
        /** Send to every ready peer. */
        fun broadcast(msg: JSONObject)
        /** Send an inner binary frame (already starting with its kind byte, e.g. 0x03) to one peer. */
        fun sendBinary(peerId: String, frame: ByteArray): Boolean
        /** Snapshot of ready peers. */
        fun peers(): List<PeerInfo>
    }

    @Volatile var transport: Transport? = null
    @Volatile var controlHandler: ((peerId: String, msg: JSONObject) -> Unit)? = null
    @Volatile var binaryHandler: ((peerId: String, frame: ByteArray) -> Unit)? = null
    @Volatile var peersHandler: ((peers: List<PeerInfo>) -> Unit)? = null

    fun send(peerId: String, msg: JSONObject): Boolean = transport?.send(peerId, msg) ?: false
    fun broadcast(msg: JSONObject) { transport?.broadcast(msg) }
    fun sendBinary(peerId: String, frame: ByteArray): Boolean = transport?.sendBinary(peerId, frame) ?: false
    fun peers(): List<PeerInfo> = transport?.peers() ?: emptyList()

    private val heard = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Network half: any authenticated frame from [peerId] arrived (ping/pong included). */
    fun heard(peerId: String) { heard[peerId] = android.os.SystemClock.uptimeMillis() }

    /** `SystemClock.uptimeMillis()` of the last frame from [peerId], or null (§8.6 silence safety net). */
    fun lastHeard(peerId: String): Long? = heard[peerId]

    fun dispatchControl(peerId: String, msg: JSONObject) { controlHandler?.invoke(peerId, msg) }
    fun dispatchBinary(peerId: String, frame: ByteArray) { binaryHandler?.invoke(peerId, frame) }
    fun notifyPeers(peers: List<PeerInfo>) { peersHandler?.invoke(peers) }
}
