package com.shazan.airbridge.input

/**
 * The target's control lease (PROTOCOL.md §8.6), as a pure table so it can be unit-tested
 * (ControlLeaseTest). [TargetController] owns one and acts on the returned decisions.
 * Not thread-safe: call from one thread (main).
 */
class ControlLease(
    private val implicitMs: Long = 1500,
    private val busyEveryMs: Long = 2000,
) {
    /** Current holder peer id, or null. */
    var holder: String? = null; private set
    /** True when the lease came from `ctl_enter` (no expiry). */
    var explicit: Boolean = false; private set
    private var lastInput = 0L
    private val lastBusySent = HashMap<String, Long>()

    sealed interface Decision {
        /** Apply the input. [gained] = the lease was just taken (implicitly). */
        data class Accept(val gained: Boolean) : Decision
        /** Drop it. [busyTo] = send `ctl_busy` to this peer now (throttled), or null. */
        data class Drop(val busyTo: String?) : Decision
    }

    /**
     * `ctl_enter` from [peer]: it takes the lease immediately. Returns the pre-empted previous holder
     * (send it `ctl_leave`, release its held keys/buttons), or null.
     */
    fun onEnter(peer: String, t: Long): String? {
        val prev = holder?.takeIf { it != peer }
        holder = peer
        explicit = true
        lastInput = t
        return prev
    }

    /** `ptr_*` / `key` / `action` from [peer]. */
    fun onInput(peer: String, t: Long): Decision {
        expire(t)
        val h = holder
        return when {
            h == null -> {
                holder = peer; explicit = false; lastInput = t
                Decision.Accept(gained = true)
            }
            h == peer -> { lastInput = t; Decision.Accept(gained = false) }
            else -> {
                val last = lastBusySent[peer]
                if (last == null || t - last >= busyEveryMs) {
                    lastBusySent[peer] = t
                    Decision.Drop(busyTo = peer)
                } else Decision.Drop(busyTo = null)
            }
        }
    }

    /** `ctl_leave` from [peer]. Returns true if that released the lease. */
    fun onLeave(peer: String): Boolean {
        if (holder != peer) return false
        clear()
        return true
    }

    /** [peer] disconnected. Returns true if it held the lease. */
    fun onPeerGone(peer: String): Boolean {
        lastBusySent.remove(peer)
        return onLeave(peer)
    }

    /** Releases an implicit lease idle for ≥ implicitMs. Returns the released holder, or null. */
    fun expire(t: Long): String? {
        val h = holder ?: return null
        if (explicit || t - lastInput < implicitMs) return null
        clear()
        return h
    }

    /** When [expire] should next be called, or null. */
    fun nextExpiry(): Long? = if (holder != null && !explicit) lastInput + implicitMs else null

    /** Local release (our cursor went back through the edge, service stopping). */
    fun clear() {
        holder = null
        explicit = false
    }
}
