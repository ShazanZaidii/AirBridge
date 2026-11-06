package com.shazan.airbridge.input

/**
 * The target's control lease (PROTOCOL.md §8.6), as a pure table so it can be unit-tested
 * (ControlLeaseTest). [TargetController] owns one and acts on the returned decisions.
 * Not thread-safe: call from one thread (main).
 */
class ControlLease(
    private val implicitMs: Long = 1500,
    private val busyEveryMs: Long = 2000,
    /** §8.6 "No stray state": drop the lease after this long without any message from the holder. */
    private val silentMs: Long = 10_000,
    /** After a hand-back, in-flight input from that controller is ignored this long (no cursor pop-up). */
    private val handBackGraceMs: Long = 700,
) {
    /** Current holder peer id, or null. */
    var holder: String? = null; private set
    /** True when the lease came from `ctl_enter` (no expiry). */
    var explicit: Boolean = false; private set
    private var lastInput = 0L
    private val lastBusySent = HashMap<String, Long>()
    private var handedBack: String? = null
    private var handedBackAt = 0L

    /**
     * §8.6 "No loops": the peer this device is driving right now (its trackpad/mirror input goes to
     * that peer), or null. That peer can't enter, and its input is answered with `ctl_busy`.
     */
    var driving: String? = null

    sealed interface Decision {
        /** Apply the input. [gained] = the lease was just taken (implicitly). */
        data class Accept(val gained: Boolean) : Decision
        /**
         * Drop it. [busyTo] = send `ctl_busy` to this peer now (throttled), or null. [loop] = the sender
         * is the peer we are driving: `ctl_busy.holder` is this device itself.
         */
        data class Drop(val busyTo: String?, val loop: Boolean = false) : Decision
    }

    /**
     * `ctl_enter` from [peer]: it takes the lease immediately. Returns the pre-empted previous holder
     * (send it `ctl_leave`, release its held keys/buttons), or null.
     */
    /** False = refuse `ctl_enter` from [peer] with `ctl_leave reason=loop` (lease untouched). */
    fun enterAllowed(peer: String): Boolean = peer != driving

    fun onEnter(peer: String, t: Long): String? {
        if (handedBack == peer) handedBack = null
        val prev = holder?.takeIf { it != peer }
        holder = peer
        explicit = true
        lastInput = t
        return prev
    }

    /** `ptr_*` / `key` / `action` from [peer]. */
    fun onInput(peer: String, t: Long): Decision {
        expire(t)
        if (peer == driving) return Decision.Drop(busyTo = throttleBusy(peer, t), loop = true)
        // Moves still in flight from a controller we just handed back to: never a new (direct) lease.
        if (peer == handedBack && holder == null && t - handedBackAt in 0 until handBackGraceMs) return Decision.Drop(null)
        val h = holder
        return when {
            h == null -> {
                holder = peer; explicit = false; lastInput = t
                Decision.Accept(gained = true)
            }
            h == peer -> { lastInput = t; Decision.Accept(gained = false) }
            else -> Decision.Drop(busyTo = throttleBusy(peer, t))
        }
    }

    private fun throttleBusy(peer: String, t: Long): String? {
        val last = lastBusySent[peer]
        if (last != null && t - last in 0 until busyEveryMs) return null
        lastBusySent[peer] = t
        return peer
    }

    /**
     * The explicit lease of [peer] ended by a hand-back (our cursor crossed the entry edge, or its
     * `ctl_leave`) at [t]: late `ptr_*` from it within the grace period are dropped.
     */
    fun onHandBack(peer: String, t: Long) {
        handedBack = peer
        handedBackAt = t
    }

    /** True when the holder sent nothing at all (not even ping) for [silentMs]; [lastHeard] from the bus. */
    fun isSilent(now: Long, lastHeard: Long?): Boolean {
        if (holder == null || lastHeard == null) return false
        return now - lastHeard >= silentMs
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
