package com.shazan.airbridge.net

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shazan.airbridge.SyncActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Clipboard sync (§5).
 *
 * Android 10+ only lets the focused app (or the default keyboard) read the clipboard — and, in stock
 * AOSP, even the change listener and the clip description are withheld from background apps. Outgoing
 * pushes therefore happen when:
 *  - the clipboard changes while the service runs and automatic sync is on → [onChangeSignal]: debounced,
 *    then read by briefly taking focus ([ClipFocusWindow], else the invisible [SyncActivity]). Signals come
 *    from the change listener (fires in the background on some ROMs, and whenever we are focused) and from
 *    our accessibility service when the user enabled it ([onAccessibilityEvent]). If the focused read is
 *    blocked, the copy is marked [pending] and the bubble shows a dot.
 *  - the user pushes (tile / bubble / share / notification) → [pushNow] or [pushText], always sent
 *  - a controller asked for `copy`              → the input half calls `ClipboardSync.pushNow("copy")`
 *  - our app gains focus with changed content   → [onAppFocused]
 *
 * Automatic sends never include content flagged sensitive, file lists, or what we just received.
 * Logs carry only lengths and short hashes, never clipboard text.
 */
object ClipboardSync {
    private const val TAG = "AirBridge.Clip"
    const val EXTRA_REASON = "reason"
    /** Settings switch "Send copies to my computers automatically" (default on). */
    const val PREF_AUTO = "CLIP_AUTO_SEND"
    /** Label of clips AirBridge sets itself (own-change suppression). */
    private const val OWN_LABEL = "AirBridge"
    // Same strings as ClipDescription.EXTRA_IS_SENSITIVE / EXTRA_IS_REMOTE_DEVICE (API 33), which apps
    // set on older releases too.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val EXTRA_IS_REMOTE_DEVICE = "android.content.extra.IS_REMOTE_DEVICE"
    /** How long the overlay focus window may take before we fall back to [SyncActivity]. */
    private const val WINDOW_FOCUS_TIMEOUT_MS = 1_000L
    private const val SYSTEM_UI = "com.android.systemui"

    /** Reasons for which the user explicitly asked to push (send even if unchanged, show feedback). */
    private val FORCED = setOf("tile", "bubble", "share", "manual")

    private val cm: ClipboardManager by lazy { Hub.app.getSystemService(ClipboardManager::class.java) }
    private val prefs by lazy { Hub.app.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE) }

    /** Main thread only. */
    private val policy = ClipAutoPolicy()
    private var focusWindow: ClipFocusWindow? = null
    /** Bumped whenever a focused read completes or a new one starts; stale timeouts compare against it. */
    private var readToken = 0
    private var listening = false

    private val _pending = MutableStateFlow(false)
    /** The latest copy could not be sent automatically; tapping the bubble sends it. */
    val pending: StateFlow<Boolean> = _pending

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onChangeSignal("listener") }
    private val timer = Runnable { onTimer() }

    fun startListening() = Hub.main.post {
        if (!listening) { cm.addPrimaryClipChangedListener(listener); listening = true }
    }

    fun stopListening() = Hub.main.post {
        if (listening) { cm.removePrimaryClipChangedListener(listener); listening = false }
        Hub.main.removeCallbacks(timer)
        focusWindow?.hide()
    }

    var autoEnabled: Boolean
        get() = prefs.getBoolean(PREF_AUTO, true)
        set(on) { prefs.edit().putBoolean(PREF_AUTO, on).apply() }

    // ---------------------------------------------------------------- automatic push

    /**
     * Something suggests the clipboard changed ([source] is only logged). Debounced; reads the clipboard
     * (taking focus if needed) and sends it to every ready peer with `clip`. Any thread.
     */
    fun onChangeSignal(source: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { Hub.main.post { onChangeSignal(source) }; return }
        val meta = describe()
        val d = policy.onChange(now(), meta, autoEnabled, hasClipPeers())
        syncPending()
        Log.d(TAG, "change ($source, description ${if (meta == null) "hidden" else "readable"}): $d")
        if (d is ClipAutoPolicy.Decision.Wait) Hub.main.postDelayed(timer, d.delayMs)
    }

    private fun onTimer() {
        when (val d = policy.onTimer(now(), autoEnabled, hasClipPeers())) {
            is ClipAutoPolicy.Decision.Wait -> Hub.main.postDelayed(timer, d.delayMs)
            ClipAutoPolicy.Decision.Read -> {
                // A direct read works while we are focused, and on ROMs that let background apps read.
                val clip = readClip()
                if (clip != null) onFocusedRead(clip, "auto") else autoPush()
            }
            else -> Log.d(TAG, "auto: $d")
        }
    }

    /** Take focus for a moment to read and send the clipboard (reason `"auto"`). */
    internal fun autoPush() = focusedRead("auto")

    /**
     * Accessibility events that mean "the user just copied", for ROMs where the clipboard listener is
     * not delivered to background apps (stock Android 10+): the SystemUI clipboard overlay (Android 13+)
     * appearing, or a tap on a "Copy"/"Cut" action. Only text-free signals are inspected (window root
     * content description, click label). Cheap; called for every event. Main thread.
     */
    fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == Hub.app.packageName) return
        val copied = when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> pkg == SYSTEM_UI && isClipboardOverlay(e)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = (e.text.firstOrNull() ?: e.contentDescription)?.toString()?.trim()
                label != null && label.length <= 16 && (label.equals(copyLabel, ignoreCase = true) || label.equals(cutLabel, ignoreCase = true))
            }
            else -> false
        }
        if (copied) onChangeSignal("a11y")
    }

    /** AOSP `clipboard_overlay.xml`: the root `clipboard_ui` carries `@string/clipboard_overlay_window_name`. */
    private fun isClipboardOverlay(e: AccessibilityEvent): Boolean = try {
        val root = e.source
        root != null && (isOverlayNode(root) || (0 until minOf(root.childCount, 3)).any { i -> root.getChild(i)?.let { isOverlayNode(it) } == true })
    } catch (_: Exception) { false }

    private fun isOverlayNode(n: AccessibilityNodeInfo) =
        n.contentDescription?.toString()?.equals(overlayName, ignoreCase = true) == true

    private val overlayName by lazy {
        try {
            val res = Hub.app.packageManager.getResourcesForApplication(SYSTEM_UI)
            res.getString(res.getIdentifier("clipboard_overlay_window_name", "string", SYSTEM_UI))
        } catch (_: Exception) { "Clipboard" }
    }
    private val copyLabel by lazy { Resources.getSystem().getString(android.R.string.copy) }
    private val cutLabel by lazy { Resources.getSystem().getString(android.R.string.cut) }

    /** E.g. the service was restarted by the system: copies made meanwhile were missed. */
    fun markPending(why: String) = Hub.main.post {
        policy.markPending(); syncPending()
        Log.i(TAG, "pending ($why)")
    }

    // ---------------------------------------------------------------- user-triggered push

    /**
     * Push the current clipboard to every connected peer. For `reason == "copy"` the read is
     * delayed slightly so the target app has finished writing the clipboard. Any thread.
     */
    @JvmStatic
    fun pushNow(reason: String = "manual") {
        val delay = if (reason == "copy") 300L else 0L
        Hub.main.postDelayed({ focusedRead(reason) }, delay)
    }

    /**
     * Read the clipboard with focus: overlay focus window first (no background-activity-start rules
     * apply), then [SyncActivity], then give up — marking the copy [pending] (and telling the user if
     * they asked for it). Main thread.
     */
    private fun focusedRead(reason: String) {
        val token = ++readToken
        val window = focusWindow ?: ClipFocusWindow(Hub.app).also { focusWindow = it }
        if (window.show { onFocusedRead(readClip(), reason) }) {
            Log.d(TAG, "$reason: focused read via window")
            Hub.main.postDelayed({
                if (token != readToken) return@postDelayed
                window.hide()
                Log.i(TAG, "$reason: window got no focus in ${WINDOW_FOCUS_TIMEOUT_MS}ms, trying activity")
                startSyncActivity(reason, token)
            }, WINDOW_FOCUS_TIMEOUT_MS)
        } else {
            startSyncActivity(reason, token)
        }
    }

    private fun startSyncActivity(reason: String, token: Int) {
        try {
            Hub.app.startActivity(
                Intent(Hub.app, SyncActivity::class.java)
                    .putExtra(EXTRA_REASON, reason)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
            Log.d(TAG, "$reason: focused read via activity")
        } catch (e: Exception) {
            // Blocked background starts are usually silent (no exception); the timeout below covers both.
            Log.w(TAG, "$reason: cannot start SyncActivity: ${e.javaClass.simpleName}")
        }
        Hub.main.postDelayed({
            if (token != readToken) return@postDelayed
            readToken++
            focusWindow?.hide()
            if (reason == "auto") policy.onReadFailed() else policy.markPending()
            syncPending()
            Log.i(TAG, "$reason: focused read blocked → pending (tap the bubble to send)")
            if (reason in FORCED) Hub.toast("Couldn't read the clipboard. Open AirBridge and try again.")
        }, policy.readTimeoutMs - WINDOW_FOCUS_TIMEOUT_MS)
    }

    /** One of our windows has focus and read [clip] (null = empty/unreadable). Main thread. */
    fun onFocusedRead(clip: ClipData?, reason: String) {
        readToken++ // cancels the pending timeouts
        val text = clip?.let { textOf(it) }
        if (reason == "auto") {
            val bytes = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
            val hash = text?.let { Protocol.sha256Hex(it) }
            val d = policy.onRead(clip?.let { metaOf(it.description, it) }, hash, bytes, Protocol.MAX_CLIP_BYTES)
            syncPending()
            Log.i(TAG, "auto: read len=${text?.length ?: 0} hash=${hash?.take(8)} → $d")
            if (d == ClipAutoPolicy.Decision.Send && text != null) pushText(text, "auto")
            return
        }
        when {
            text != null -> pushText(text, reason)
            reason != "copy" -> { policy.clearPending(); syncPending(); Hub.toast("Clipboard has no text") }
        }
    }

    /** Our activity just got focus: send if the clipboard changed since we last saw it (automatic). */
    fun onAppFocused(activity: Activity) {
        if (!activity.hasWindowFocus() || !autoEnabled) return
        val clip = readClip() ?: return
        val text = textOf(clip)
        val hash = text?.let { Protocol.sha256Hex(it) }
        val skip = policy.check(metaOf(clip.description, clip), hash, text?.toByteArray(Charsets.UTF_8)?.size ?: 0, Protocol.MAX_CLIP_BYTES)
        if (skip == null && text != null) pushText(text, "foreground")
    }

    // ---------------------------------------------------------------- reading

    /** Read the clipboard as text; only works while one of our windows has focus. */
    fun readText(): String? = readClip()?.let { textOf(it) }

    /** The raw clip; only readable while one of our windows has focus. */
    fun readClip(): ClipData? = try {
        cm.primaryClip?.takeIf { it.itemCount > 0 }
    } catch (e: Exception) { null }

    private fun textOf(clip: ClipData): String? = try {
        val item = clip.getItemAt(0)
        if (isFileList(clip.description, item)) null
        else item.coerceToText(Hub.app)?.toString()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    /** The clip description, if readable (null in the background on stock Android). */
    private fun describe(): ClipMeta? = try {
        cm.primaryClipDescription?.let { metaOf(it, null) }
    } catch (e: Exception) { null }

    private fun metaOf(desc: ClipDescription?, clip: ClipData?): ClipMeta? {
        desc ?: return null
        val extras = desc.extras
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        return ClipMeta(
            sensitive = extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true,
            remoteDevice = extras?.getBoolean(EXTRA_IS_REMOTE_DEVICE, false) == true,
            fileList = if (item != null) isFileList(desc, item)
                else desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) && !desc.hasMimeType("text/plain"),
            ownClip = desc.label?.toString() == OWN_LABEL,
        )
    }

    private fun isFileList(desc: ClipDescription?, item: ClipData.Item): Boolean {
        val uri = item.uri ?: return false
        return (desc?.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) == true || item.text == null) &&
            (uri.scheme == "content" || uri.scheme == "file")
    }

    // ---------------------------------------------------------------- sending

    /**
     * Send [text] to all ready peers with `clip`. Unless forced, skips content that we just
     * received (echo suppression) or already sent. Main thread.
     * @return number of peers it was sent to.
     */
    fun pushText(text: String, reason: String, force: Boolean = reason in FORCED): Int {
        val hash = Protocol.sha256Hex(text)
        if (!force && policy.isDuplicate(hash)) return 0
        if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_CLIP_BYTES) {
            if (force || reason == "copy" || reason == "auto") Hub.toast("Clipboard too large to sync (max 512 KB)")
            return 0
        }
        val targets = Hub.readySessions().filter { "clip" in it.peerCaps }
        if (targets.isEmpty()) {
            if (force) Hub.toast("No devices connected")
            return 0
        }
        val msg = Protocol.clip(text)
        val n = targets.count { it.send(msg) }
        if (n > 0) { policy.onPushed(hash); syncPending() }
        Log.i(TAG, "sent ($reason) len=${text.length} hash=${hash.take(8)} to $n peer(s)")
        if (force) Hub.toast(if (n == 1) "Copied to ${targets.first().peerName}" else "Copied to $n devices")
        return n
    }

    /** Share sheet: send text to one peer. */
    fun sendTextTo(peerId: String, text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_CLIP_BYTES) {
            Hub.toast("Text too large to send (max 512 KB)"); return false
        }
        return Hub.readySession(peerId)?.send(Protocol.clip(text)) ?: false
    }

    fun onRemote(s: Session, m: JSONObject) {
        if (m.str("kind") != "text") return // "image" reserved
        val text = m.str("text") ?: return
        if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_CLIP_BYTES) return
        val hash = Protocol.sha256Hex(text)
        Hub.main.post {
            policy.onRemoteSet(now(), hash)
            try { cm.setPrimaryClip(ClipData.newPlainText(OWN_LABEL, text)) }
            catch (e: Exception) { Log.w(TAG, "setPrimaryClip", e) }
        }
    }

    private fun hasClipPeers() = Hub.readySessions().any { "clip" in it.peerCaps }
    private fun syncPending() { _pending.value = policy.pending }
    private fun now() = SystemClock.elapsedRealtime()
}
