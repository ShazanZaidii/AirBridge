package com.shazan.airbridge.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Calls into the network half without a compile-time dependency, so the halves merge trivially.
 * The network half may assign [clipboardPush] directly; otherwise `ClipboardSync.pushNow(reason)`
 * is looked up reflectively.
 */
object NetHooks {
    private const val TAG = "AirBridgeNetHooks"
    private val CANDIDATES = listOf(
        "com.shazan.airbridge.net.ClipboardSync",
        "com.shazan.airbridge.ClipboardSync",
    )

    /** Optional direct hook: push the local clipboard to peers (§5). */
    @Volatile var clipboardPush: ((reason: String) -> Unit)? = null

    @Volatile private var resolved = false
    private var method: Method? = null
    private var receiver: Any? = null

    fun pushClipboard(reason: String) {
        try {
            clipboardPush?.let { it(reason); return }
            resolve()
            val m = method ?: run { Log.d(TAG, "No clipboard push hook available"); return }
            if (m.parameterTypes.isEmpty()) m.invoke(receiver) else m.invoke(receiver, reason)
        } catch (t: Throwable) {
            Log.w(TAG, "Clipboard push failed", t)
        }
    }

    // ------------------------------------------------------------------ bubble suppression (§5)

    private const val BUBBLE_CLASS = "com.shazan.airbridge.BubbleController"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var bubbleResolved = false
    private var bubbleMethod: Method? = null
    private var bubbleReceiver: Any? = null
    private val bubbleState = HashMap<String, Boolean>()

    /**
     * Hide/show the floating clipboard bubble for [reason] (`"lease"`, `"trackpad"`, `"mirror"`):
     * `BubbleController.setSuppressed(reason, on)` from the network half, looked up reflectively.
     * Deduplicated per reason, posted to the main thread, never throws.
     */
    fun setBubbleSuppressed(reason: String, on: Boolean) {
        synchronized(bubbleState) {
            if (bubbleState[reason] == on) return
            bubbleState[reason] = on
        }
        main.post {
            try {
                resolveBubble()
                val m = bubbleMethod ?: return@post
                m.invoke(bubbleReceiver, reason, on)
            } catch (t: Throwable) {
                Log.w(TAG, "BubbleController.setSuppressed($reason, $on) failed", t)
            }
        }
    }

    @Synchronized
    private fun resolveBubble() {
        if (bubbleResolved) return
        bubbleResolved = true
        val cls = try { Class.forName(BUBBLE_CLASS) } catch (_: Throwable) {
            Log.d(TAG, "No BubbleController; bubble suppression disabled"); return
        }
        val m = cls.methods.firstOrNull {
            it.name == "setSuppressed" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == java.lang.Boolean.TYPE || it.parameterTypes[1] == java.lang.Boolean::class.java)
        } ?: return
        bubbleReceiver = if (Modifier.isStatic(m.modifiers)) null
        else try { cls.getField("INSTANCE").get(null) } catch (_: Throwable) { return }
        bubbleMethod = m
    }

    @Synchronized
    private fun resolve() {
        if (resolved) return
        resolved = true
        for (name in CANDIDATES) {
            val cls = try { Class.forName(name) } catch (_: Throwable) { continue }
            val m = cls.methods.firstOrNull {
                it.name == "pushNow" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            } ?: cls.methods.firstOrNull { it.name == "pushNow" && it.parameterTypes.isEmpty() } ?: continue
            receiver = if (Modifier.isStatic(m.modifiers)) null
            else try { cls.getField("INSTANCE").get(null) } catch (_: Throwable) { continue }
            method = m
            return
        }
    }
}
