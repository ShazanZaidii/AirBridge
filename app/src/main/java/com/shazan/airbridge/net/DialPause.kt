package com.shazan.airbridge.net

/** Who ended the connection on purpose (§4.1 "explicit user disconnect must not trigger reconnect"). */
enum class DisconnectBy { SELF, PEER }

/** Persistent per-peer user-disconnect flag. [PairStore] keeps it in the peer's record. */
interface PauseStore {
    fun userPause(peerId: String): DisconnectBy?
    fun setUserPause(peerId: String, by: DisconnectBy?)
}

/**
 * Whether [WsClient] may dial one peer, and what may lift a pause. Two kinds of pause:
 *
 * - **transient** (in memory): any non-reconnect ending (`bye replaced`, local shutdown …).
 *   Lifted by app open / service start, the end of a link upgrade, and — for `bye replaced`
 *   only — a network change (§3.4).
 * - **user** (persisted in [store], survives restarts): our Disconnect action ([DisconnectBy.SELF])
 *   or the peer's `bye user` ([DisconnectBy.PEER]). Lifted **only** by the Connect action or a new
 *   pairing; opening the app or a network change never redials a device the user disconnected.
 *
 * Not thread-safe: the owning dialer serialises calls. Unit-tested in DialPauseTest.
 */
class DialPause(private val peerId: String, private val store: PauseStore) {
    enum class Trigger { APP_OPEN, NETWORK_CHANGE, LINK_ENDED, USER_CONNECT, NEW_PAIRING }

    private var transient = false
    /** The transient pause came from `bye replaced`. */
    var byReplace = false; private set

    val user: DisconnectBy? get() = store.userPause(peerId)
    val paused: Boolean get() = transient || user != null

    /** A session with this peer ended with [outcome]. */
    fun onEnded(outcome: Outcome) {
        if (outcome.reconnect) return
        if (outcome is Outcome.Ended && outcome.why == BYE_USER) store.setUserPause(peerId, DisconnectBy.PEER)
        transient = true
        byReplace = outcome is Outcome.Ended && outcome.why == "bye replaced"
    }

    /** The user tapped Disconnect. */
    fun userDisconnect() {
        store.setUserPause(peerId, DisconnectBy.SELF)
        transient = true
        byReplace = false
    }

    /** Applies [t]; returns true if dialling is now allowed. */
    fun apply(t: Trigger): Boolean {
        when (t) {
            Trigger.USER_CONNECT, Trigger.NEW_PAIRING -> {
                if (user != null) store.setUserPause(peerId, null)
                clearTransient()
            }
            Trigger.APP_OPEN, Trigger.LINK_ENDED -> clearTransient()
            Trigger.NETWORK_CHANGE -> if (byReplace) clearTransient()
        }
        return !paused
    }

    private fun clearTransient() { transient = false; byReplace = false }

    companion object {
        /** [Outcome.Ended.why] for a received `bye user` (see Session.byeOutcome). */
        const val BYE_USER = "bye user"
    }
}
