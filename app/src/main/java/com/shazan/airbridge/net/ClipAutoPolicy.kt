package com.shazan.airbridge.net

/**
 * What we know about a clip without its text: built from `ClipDescription` (and the first item).
 * The description is only readable while one of our windows has focus (AOSP `ClipboardService`
 * applies the same focus rule as for the content), so in the background it is usually unknown.
 */
data class ClipMeta(
    /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+, same key as the legacy extra). */
    val sensitive: Boolean = false,
    /** `ClipDescription.EXTRA_IS_REMOTE_DEVICE`: the clip came from another device (system cross-device sync). */
    val remoteDevice: Boolean = false,
    /** A list of file/content URIs, not text. */
    val fileList: Boolean = false,
    /** Set by AirBridge itself ([ClipboardSync.onRemote] labels its clips). */
    val ownClip: Boolean = false,
)

/**
 * Pure decision logic for automatic phone → computer clipboard sync (§5), kept free of Android
 * types so it can be unit-tested. Not thread-safe: [ClipboardSync] calls it on the main thread.
 *
 * Flow: a change signal ([onChange]) arms a debounce timer; when it fires ([onTimer]) the caller
 * reads the clipboard (directly, or by briefly taking focus), then asks [onRead] whether to send.
 * If the focused read could not happen ([onReadFailed]) the copy becomes *pending*: the bubble shows
 * a dot until the user taps it ([onPushed]) or the clipboard changes again.
 */
class ClipAutoPolicy(
    val debounceMs: Long = 400,
    /** After we set the clipboard from a remote `clip`, change signals in this window are our own echo. */
    val echoWindowMs: Long = 2_000,
    /** A focused read that has not reported back by then counts as blocked. */
    val readTimeoutMs: Long = 4_000,
) {
    enum class Skip { DISABLED, ECHO, OWN_CLIP, SENSITIVE, REMOTE_DEVICE, FILES, NO_PEERS, IN_FLIGHT, EMPTY, UNCHANGED, TOO_LARGE }

    sealed interface Decision {
        /** Nothing to do (logged with the reason). */
        data class Skipped(val why: Skip) : Decision
        /** Arm the timer: call [onTimer] after [delayMs]. */
        data class Wait(val delayMs: Long) : Decision
        /** A timer is already armed; this change is folded into it. */
        data object Coalesced : Decision
        /** Read the clipboard now; report with [onRead] or [onReadFailed]. */
        data object Read : Decision
        /** Send the text that was just read. */
        data object Send : Decision
    }

    private var lastChangeAt = Long.MIN_VALUE
    private var timerArmed = false
    private var echoUntil = Long.MIN_VALUE

    /** A read (direct or focused) is running; further change signals are ignored until it ends. */
    var inFlight = false
        private set
    private var readStartedAt = 0L

    /** The latest copy could not be read automatically: tapping the bubble sends it. */
    var pending = false
        private set

    var lastRemoteHash: String? = null
        private set
    var lastSentHash: String? = null
        private set

    /**
     * The clipboard changed (listener or another signal). [meta] is null when the description is
     * not readable (background). Clears [pending]: the old copy is no longer the latest.
     */
    fun onChange(now: Long, meta: ClipMeta?, enabled: Boolean, hasPeers: Boolean): Decision {
        if (now <= echoUntil) return Decision.Skipped(Skip.ECHO)
        if (inFlight && !readExpired(now)) return Decision.Skipped(Skip.IN_FLIGHT)
        if (inFlight) inFlight = false // the read never reported back; do not wedge
        pending = false
        metaSkip(meta)?.let { return Decision.Skipped(it) }
        if (!enabled) return Decision.Skipped(Skip.DISABLED)
        if (!hasPeers) return Decision.Skipped(Skip.NO_PEERS)
        lastChangeAt = now
        if (timerArmed) return Decision.Coalesced
        timerArmed = true
        return Decision.Wait(debounceMs)
    }

    /** The debounce timer fired. */
    fun onTimer(now: Long, enabled: Boolean, hasPeers: Boolean): Decision {
        if (!timerArmed) return Decision.Skipped(Skip.IN_FLIGHT)
        val quietFor = now - lastChangeAt
        if (quietFor < debounceMs) return Decision.Wait(debounceMs - quietFor)
        timerArmed = false
        if (!enabled) return Decision.Skipped(Skip.DISABLED)
        if (!hasPeers) return Decision.Skipped(Skip.NO_PEERS)
        if (inFlight && !readExpired(now)) return Decision.Skipped(Skip.IN_FLIGHT)
        inFlight = true
        readStartedAt = now
        return Decision.Read
    }

    /**
     * The clipboard was read (with focus). [textHash] is null when it holds no text.
     * Ends the in-flight read. Returns [Decision.Send] or why not.
     */
    fun onRead(meta: ClipMeta?, textHash: String?, sizeBytes: Int, maxBytes: Int): Decision {
        inFlight = false
        pending = false
        return check(meta, textHash, sizeBytes, maxBytes)?.let { Decision.Skipped(it) } ?: Decision.Send
    }

    /** Whether automatically read content may be sent (no state change). Null = send. */
    fun check(meta: ClipMeta?, textHash: String?, sizeBytes: Int, maxBytes: Int): Skip? {
        metaSkip(meta)?.let { return it }
        return when {
            textHash == null -> Skip.EMPTY
            textHash == lastRemoteHash -> Skip.ECHO
            textHash == lastSentHash -> Skip.UNCHANGED
            sizeBytes > maxBytes -> Skip.TOO_LARGE
            else -> null
        }
    }

    /** The focused read was blocked or timed out: remember that the latest copy was not sent. */
    fun onReadFailed() {
        inFlight = false
        pending = true
    }

    /** Mark the latest copy as not sent without trying (e.g. the service was restarted by the system). */
    fun markPending() { pending = true }

    /** Text was sent to at least one peer (any reason). */
    fun onPushed(hash: String) {
        lastSentHash = hash
        pending = false
    }

    /** Clears the bubble hint without sending (the user pushed and there was nothing to send). */
    fun clearPending() { pending = false }

    /** We are about to set the local clipboard from a remote `clip` (echo suppression, §5). */
    fun onRemoteSet(now: Long, hash: String) {
        lastRemoteHash = hash
        lastSentHash = hash
        echoUntil = now + echoWindowMs
    }

    /** Non-forced pushes skip content we just received or already sent. */
    fun isDuplicate(hash: String): Boolean = hash == lastRemoteHash || hash == lastSentHash

    private fun readExpired(now: Long) = now - readStartedAt > readTimeoutMs

    private fun metaSkip(meta: ClipMeta?): Skip? = when {
        meta == null -> null
        meta.ownClip -> Skip.OWN_CLIP
        meta.sensitive -> Skip.SENSITIVE
        meta.remoteDevice -> Skip.REMOTE_DEVICE
        meta.fileList -> Skip.FILES
        else -> null
    }
}
