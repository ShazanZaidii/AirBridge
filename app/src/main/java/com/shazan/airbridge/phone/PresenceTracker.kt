package com.shazan.airbridge.phone

/**
 * Is the user on the phone right now (§13.4)? Pure, unit-tested; [PhonePresence] feeds it Android's events.
 *
 * Interaction = an unlock, or a tap / window change seen by the accessibility service. The screen turning on
 * is NOT interaction: an incoming call turns it on by itself. For the same reason an unlock reported right
 * after a call started ringing on a dark screen (phones without a lock screen send "user present" when the
 * call wakes them) is ignored.
 */
class PresenceTracker(private val clock: () -> Long) {
    var screenOn = false
        private set
    private var lastInteraction: Long? = null
    private var ringAt: Long? = null
    private var screenOffAtRing = false
    private var lastSentAt: Long? = null
    private var lastSent: NotifWire.Presence? = null

    fun onScreen(on: Boolean) { screenOn = on }

    fun onUnlock() {
        val now = clock()
        val r = ringAt
        if (r != null && screenOffAtRing && now - r < WAKE_GRACE_MS) return
        lastInteraction = now
    }

    fun onTouch() { lastInteraction = clock() }

    /** The moment the call arrives (before it wakes the screen). Later calls for the same ring are ignored. */
    fun onRingStart() {
        if (ringAt != null) return
        ringAt = clock()
        screenOffAtRing = !screenOn
    }

    fun onRingEnd() { ringAt = null }

    fun snapshot(locked: Boolean, touchKnown: Boolean): NotifWire.Presence {
        val now = clock()
        return NotifWire.Presence(screenOn, locked, lastInteraction?.let { (now - it).coerceAtLeast(0) }, touchKnown)
    }

    /**
     * Whether [p] is worth sending: always when screen / lock / touch-tracking changed, and after a tap at most every
     * [TOUCH_EVERY_MS] (the computer adds the time since it arrived, so it stays accurate to that much).
     */
    fun shouldSend(p: NotifWire.Presence, force: Boolean = false): Boolean {
        val now = clock()
        val prev = lastSent ?: return true
        if (force) return true
        if (prev.screenOn != p.screenOn || prev.locked != p.locked || prev.touchKnown != p.touchKnown) return true
        if ((prev.idleMs == null) != (p.idleMs == null)) return true
        val idle = p.idleMs ?: return false
        val since = now - (lastSentAt ?: 0)
        // A new interaction since the last message (idle reset) and the last one is old enough.
        val prevInteractionAt = (lastSentAt ?: 0) - (prev.idleMs ?: 0)
        val thisInteractionAt = now - idle
        return thisInteractionAt > prevInteractionAt + 500 && since >= TOUCH_EVERY_MS
    }

    fun markSent(p: NotifWire.Presence) { lastSent = p; lastSentAt = clock() }

    companion object {
        const val WAKE_GRACE_MS = 3_000L
        const val TOUCH_EVERY_MS = 4_000L
    }
}
