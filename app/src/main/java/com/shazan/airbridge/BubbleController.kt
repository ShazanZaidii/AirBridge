package com.shazan.airbridge

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Decides whether the floating clipboard bubble may be on screen (§5): it exists only for manual
 * pushes, so it is hidden while any suppression reason is active and comes back when all are off
 * (and the user's "Floating bubble" setting is on).
 *
 * Reasons used by the input half: `"lease"` (a peer holds the control lease on this phone),
 * `"trackpad"`, `"mirror"` (those screens are open). The network half additionally suppresses while
 * a trackpad/mirror activity is started (reason `"ui:<ActivityName>"`), so the bubble never covers
 * those screens even if a call is missed.
 *
 * Called by the input half via reflection:
 * `Class.forName("com.shazan.airbridge.BubbleController").getField("INSTANCE")` then
 * `setSuppressed(String, boolean)`. Keep that signature (and the R8 keep rule) stable.
 */
object BubbleController {
    private val main = Handler(Looper.getMainLooper())
    private val _reasons = MutableStateFlow<Set<String>>(emptySet())
    /** Active suppression reasons. */
    val reasons: StateFlow<Set<String>> = _reasons

    private val _leaseHolder = MutableStateFlow<String?>(null)
    /** Optional: peer id holding the control lease, if the input half reports it. */
    val leaseHolder: StateFlow<String?> = _leaseHolder

    /** Invoked on the main thread whenever [isSuppressed] may have changed (the service refreshes the bubble). */
    @Volatile var onChange: (() -> Unit)? = null

    val isSuppressed: Boolean get() = _reasons.value.isNotEmpty()
    val isLeased: Boolean get() = "lease" in _reasons.value

    /** Thread-safe. Idempotent per reason. */
    fun setSuppressed(reason: String, on: Boolean) {
        var changed = false
        _reasons.update { cur ->
            val next = if (on) cur + reason else cur - reason
            changed = next != cur
            next
        }
        if (reason == "lease" && !on) _leaseHolder.value = null
        if (changed) main.post { onChange?.invoke() }
    }

    /** Optional companion to `setSuppressed("lease", true)`: who holds the lease (null = unknown/none). */
    fun setLeaseHolder(peerId: String?) {
        _leaseHolder.value = peerId
    }
}
