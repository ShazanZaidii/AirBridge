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
 * Meeting point between the network half (discovery, sessions, pairing, clipboard, file transfer)
 * and the input half (virtual cursor, trackpad, remote keyboard). See docs/PROTOCOL.md §12.
 *
 * Network half: assigns [transport]; calls [dispatchControl] for control-type messages and
 * [notifyPeers] whenever the set of ready peers changes.
 *
 * Input half: sets [controlHandler] and [peersHandler]; calls [send] / [broadcast] / [peers].
 *
 * Threading: [controlHandler] is invoked on a network thread. The input half must hop to the
 * main thread itself where UI or gesture dispatch requires it. [send] / [broadcast] are safe to
 * call from any thread and never block on the network.
 */
object PeerBus {

    /** Message types that belong to the input half (routed through [controlHandler]). */
    val CONTROL_TYPES: Set<String> = setOf(
        "ctl_enter", "ctl_leave",
        "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click",
        "key", "key_state", "action",
    )

    interface Transport {
        /** Send to one peer. Returns false if that peer has no ready session. */
        fun send(peerId: String, msg: JSONObject): Boolean
        /** Send to every ready peer. */
        fun broadcast(msg: JSONObject)
        /** Snapshot of ready peers. */
        fun peers(): List<PeerInfo>
    }

    @Volatile var transport: Transport? = null
    @Volatile var controlHandler: ((peerId: String, msg: JSONObject) -> Unit)? = null
    @Volatile var peersHandler: ((peers: List<PeerInfo>) -> Unit)? = null

    fun send(peerId: String, msg: JSONObject): Boolean = transport?.send(peerId, msg) ?: false
    fun broadcast(msg: JSONObject) { transport?.broadcast(msg) }
    fun peers(): List<PeerInfo> = transport?.peers() ?: emptyList()

    fun dispatchControl(peerId: String, msg: JSONObject) { controlHandler?.invoke(peerId, msg) }
    fun notifyPeers(peers: List<PeerInfo>) { peersHandler?.invoke(peers) }
}
