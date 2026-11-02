package com.shazan.airbridge.net

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.util.Log
import com.shazan.airbridge.SyncActivity
import org.json.JSONObject

/**
 * Clipboard sync (§5).
 *
 * Android 10+ only lets the focused app read the clipboard, so outgoing pushes happen when:
 *  - the user pushes (tile / bubble / share)  → [pushNow] or [pushText] with force = true
 *  - a controller asked for `copy`            → the input half calls `ClipboardSync.pushNow("copy")`
 *  - our app gains focus with changed content → [onAppFocused]
 *
 * [pushNow] briefly shows the invisible [SyncActivity] to get focus, read the clipboard and send.
 * It is safe to call from any thread.
 */
object ClipboardSync {
    private const val TAG = "AirBridge.Clip"
    const val EXTRA_REASON = "reason"

    /** Reasons for which the user explicitly asked to push (send even if unchanged, show feedback). */
    private val FORCED = setOf("tile", "bubble", "share", "manual")

    private val cm: ClipboardManager by lazy { Hub.app.getSystemService(ClipboardManager::class.java) }
    @Volatile private var lastRemoteHash: String? = null
    @Volatile private var lastSentHash: String? = null
    private var listening = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        readText()?.let { pushText(it, "local") }
    }

    fun startListening() = Hub.main.post {
        if (!listening) { cm.addPrimaryClipChangedListener(listener); listening = true }
    }

    fun stopListening() = Hub.main.post {
        if (listening) { cm.removePrimaryClipChangedListener(listener); listening = false }
    }

    /**
     * Push the current clipboard to every connected peer. For `reason == "copy"` the read is
     * delayed slightly so the target app has finished writing the clipboard.
     */
    @JvmStatic
    fun pushNow(reason: String = "manual") {
        val delay = if (reason == "copy") 300L else 0L
        Hub.main.postDelayed({
            try {
                Hub.app.startActivity(
                    Intent(Hub.app, SyncActivity::class.java)
                        .putExtra(EXTRA_REASON, reason)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            } catch (e: Exception) {
                Log.w(TAG, "cannot start SyncActivity for $reason", e)
            }
        }, delay)
    }

    /** Our activity just got focus: send if the clipboard changed since we last saw it. */
    fun onAppFocused(activity: Activity) {
        if (!activity.hasWindowFocus()) return
        readText()?.let { pushText(it, "foreground") }
    }

    /** Read the clipboard as text; only works while one of our windows has focus (or from the listener). */
    fun readText(): String? = try {
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else {
            val desc = clip.description
            val item = clip.getItemAt(0)
            val isFileList = desc?.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) == true && item.uri != null
            if (isFileList && (item.uri.scheme == "content" || item.uri.scheme == "file")) null
            else item.coerceToText(Hub.app)?.toString()?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) { null }

    /**
     * Send [text] to all ready peers with `clip`. Unless forced, skips content that we just
     * received (echo suppression) or already sent.
     * @return number of peers it was sent to.
     */
    fun pushText(text: String, reason: String, force: Boolean = reason in FORCED): Int {
        val hash = Protocol.sha256Hex(text)
        if (!force && (hash == lastRemoteHash || hash == lastSentHash)) return 0
        if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_CLIP_BYTES) {
            if (force || reason == "copy") Hub.toast("Clipboard too large to sync (max 512 KB)")
            return 0
        }
        val targets = Hub.readySessions().filter { "clip" in it.peerCaps }
        if (targets.isEmpty()) {
            if (force) Hub.toast("No devices connected")
            return 0
        }
        lastSentHash = hash
        val msg = Protocol.clip(text)
        val n = targets.count { it.send(msg) }
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
        lastRemoteHash = hash
        lastSentHash = hash
        Hub.main.post {
            try { cm.setPrimaryClip(ClipData.newPlainText("AirBridge", text)) }
            catch (e: Exception) { Log.w(TAG, "setPrimaryClip", e) }
        }
    }
}
