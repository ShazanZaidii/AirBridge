package com.shazan.airbridge.phone.notifications

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

    /** How a forwarded notification sits in the phone's shade: [ALERTING] pops up, the others are listed only. */
    const val KIND_ALERTING = ""
    /** Ongoing (can't be swiped away): VPN active, USB, media playing, downloads, navigation, foreground services. */
    const val KIND_ONGOING = "ongoing"
    /** Low importance: the phone files these under "Silent" and never pops them up. */
    const val KIND_SILENT = "silent"

    sealed class Decision {
        /** Forward. [kind] = [KIND_ALERTING], or [KIND_ONGOING] / [KIND_SILENT] for list-only entries that never alert. */
        data class Forward(val kind: String = KIND_ALERTING) : Decision()
        /** Locked, and the phone hides sensitive content on its lock screen: forward with the contents hidden (as the lock
         *  screen shows it); the full version follows quietly at unlock. */
        data class ForwardHidden(val kind: String = KIND_ALERTING) : Decision()
        object Call : Decision()
        /** Not forwarded now; [later] = forward when the phone is unlocked. */
        data class Skip(val reason: String, val later: Boolean = false) : Decision()
        override fun toString(): String = javaClass.simpleName
    }

    /**
     * The computer's list mirrors the phone's shade, so status notifications (VPN, USB, music, silent ones) go too, marked
     * with a kind that keeps them from ever popping up there. AirBridge's own go too ("Connected · <the Mac's name>"),
     * always as status: the computer already shows its own side of a transfer or a call. Only these stay on the phone:
     * apps the user muted, group summaries (their children carry the content) and empty ones.
     */
    fun decide(f: Facts, c: Context): Decision {
        val own = f.pkg == f.ownPkg
        if (f.isCall && !own) return Decision.Call
        if (f.muted) return Decision.Skip("muted")
        if (f.groupSummary) return Decision.Skip("summary")
        if (!f.hasContent) return Decision.Skip("empty")
        val kind = when {
            f.ongoing || f.foregroundService || f.mediaStyle || f.category == "transport" -> KIND_ONGOING
            own -> KIND_SILENT
            f.pkg in SYSTEM_PACKAGES || f.category in NOISE_CATEGORIES -> KIND_SILENT
            f.importance in 0 until IMPORTANCE_DEFAULT && !f.conversation -> KIND_SILENT
            else -> KIND_ALERTING
        }
        if (c.locked) {
            if (f.visibility == VISIBILITY_SECRET) return Decision.Skip("secret", later = true)
            if (c.hideSensitiveWhenLocked == true) return Decision.ForwardHidden(kind)
        }
        return Decision.Forward(kind)
    }
}
