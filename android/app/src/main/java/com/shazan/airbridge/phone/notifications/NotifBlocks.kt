package com.shazan.airbridge.phone.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notifications the user never wants to see: one app, exactly this title and text (spaces and line breaks don't count, every
 * other character does). Not a type, channel or id: WhatsApp's "You may have new messages" is blocked while its real chats
 * still come through. The listener clears a blocked notification the moment it is posted, so it is gone from the phone and
 * never reaches a computer (Android doesn't let an app stop another app's notification before it is posted, so it may flash
 * or sound once).
 */
object NotifBlocks {
    data class Block(val pkg: String, val app: String, val title: String, val text: String) {
        fun matches(pkg: String, title: CharSequence?, text: CharSequence?): Boolean =
            pkg == this.pkg && normal(title) == this.title && normal(text) == this.text
    }

    /** Same words: surrounding and repeated whitespace ignored. */
    fun normal(s: CharSequence?): String = s?.toString()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: ""

    fun make(pkg: String, app: String, title: CharSequence?, text: CharSequence?) = Block(pkg, app, normal(title), normal(text))

    fun isBlocked(blocks: List<Block>, pkg: String, title: CharSequence?, text: CharSequence?): Boolean =
        blocks.any { it.matches(pkg, title, text) }

    /** The title and text a notification shows (what a block compares). */
    fun titleText(sbn: StatusBarNotification): Pair<CharSequence?, CharSequence?> {
        val x = sbn.notification?.extras ?: return null to null
        return x.getCharSequence(Notification.EXTRA_TITLE) to x.getCharSequence(Notification.EXTRA_TEXT)
    }

    fun blocks(sbn: StatusBarNotification): Boolean {
        val list = _flow.value
        if (list.isEmpty()) return false
        val (t, x) = titleText(sbn)
        return isBlocked(list, sbn.packageName ?: "", t, x)
    }

    // ---- stored in the app's preferences

    private const val PREFS = "AirBridgePrefs"
    private const val KEY = "NOTIF_BLOCKS"
    private val _flow = MutableStateFlow<List<Block>>(emptyList())
    val flow: StateFlow<List<Block>> = _flow
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _flow.value = try {
                val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Block(o.optString("pkg"), o.optString("app"), o.optString("title"), o.optString("text"))
                }.filter { it.pkg.isNotEmpty() }
            } catch (_: Throwable) { emptyList() }
            loaded = true
        }
    }

    fun add(ctx: Context, b: Block) {
        init(ctx)
        if (_flow.value.contains(b)) return
        save(ctx, _flow.value + b)
        // Clear the ones showing now too.
        PhoneNotificationListener.instance?.let { l ->
            l.snapshot().map { it.first }.filter { val (t, x) = titleText(it); b.matches(it.packageName ?: "", t, x) }
                .forEach { l.dismiss(it.key) }
        }
    }

    fun remove(ctx: Context, b: Block) {
        init(ctx)
        save(ctx, _flow.value - b)
    }

    private fun save(ctx: Context, list: List<Block>) {
        _flow.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("app", it.app).put("title", it.title).put("text", it.text)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
