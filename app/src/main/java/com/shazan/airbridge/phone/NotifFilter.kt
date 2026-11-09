package com.shazan.airbridge.phone

/**
 * Which phone notifications go to the computers (pure, unit-tested). The listener turns each
 * `StatusBarNotification` into [Facts]; [decide] says forward, hand to the call tracker, or skip (and why).
 */
object NotifFilter {

    /** Android's `NotificationManager.IMPORTANCE_*` values (copied so this stays JVM-only). */
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3

    /** `Notification.VISIBILITY_SECRET`. */
    const val VISIBILITY_SECRET = -1

    /** Packages whose notifications are system chatter (USB, screenshots, charging…). */
    val SYSTEM_PACKAGES = setOf("android", "com.android.systemui", "com.android.providers.downloads")

    /** Android categories that are status, not news. */
    val NOISE_CATEGORIES = setOf("transport", "service", "progress", "navigation", "sys", "status", "recommendation", "location_sharing", "stopwatch")

    data class Facts(
        val pkg: String,
        val ownPkg: String,
        val category: String?,
        val ongoing: Boolean,
        val foregroundService: Boolean,
        val groupSummary: Boolean,
        val mediaStyle: Boolean,
        /** Channel importance (O+); [IMPORTANCE_DEFAULT] when unknown. */
        val importance: Int,
        val conversation: Boolean,
        val hasContent: Boolean,
        /** Dialer call notification (category call with a call type / CallStyle, or ongoing call category). */
        val isCall: Boolean,
        val visibility: Int,
        val muted: Boolean,
    )

    /** Device state that applies to every notification. */
    data class Context(
        val locked: Boolean,
        /** "Hide sensitive content" on the lock screen, or notifications hidden there entirely. null = unknown. */
        val hideSensitiveWhenLocked: Boolean?,
    )

    sealed class Decision {
        object Forward : Decision()
        object Call : Decision()
        /** Not forwarded now; [later] = forward when the phone is unlocked. */
        data class Skip(val reason: String, val later: Boolean = false) : Decision()
        override fun toString(): String = javaClass.simpleName
    }

    fun decide(f: Facts, c: Context): Decision {
        if (f.pkg == f.ownPkg) return Decision.Skip("own")
        if (f.isCall) return Decision.Call
        if (f.muted) return Decision.Skip("muted")
        if (f.pkg in SYSTEM_PACKAGES) return Decision.Skip("system")
        if (f.groupSummary) return Decision.Skip("summary")
        if (f.foregroundService) return Decision.Skip("fgs")
        if (f.mediaStyle || f.category == "transport") return Decision.Skip("media")
        if (f.ongoing) return Decision.Skip("ongoing")
        if (f.category in NOISE_CATEGORIES) return Decision.Skip("noise")
        if (f.importance in 0 until IMPORTANCE_DEFAULT && !f.conversation) return Decision.Skip("silent")
        if (!f.hasContent) return Decision.Skip("empty")
        if (c.locked) {
            if (f.visibility == VISIBILITY_SECRET) return Decision.Skip("secret", later = true)
            if (c.hideSensitiveWhenLocked == true) return Decision.Skip("locked", later = true)
        }
        return Decision.Forward
    }
}
