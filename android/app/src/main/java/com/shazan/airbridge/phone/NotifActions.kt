package com.shazan.airbridge.phone

import java.security.MessageDigest

/**
 * Choosing the reply action and the other buttons of a notification, and mapping the short keys sent to the
 * computers back to Android's notification keys (pure, unit-tested).
 */
object NotifActions {
    /** `Notification.Action.SEMANTIC_ACTION_REPLY`. */
    const val SEMANTIC_REPLY = 1

    /** What the listener knows about one action of a notification. */
    data class Info(
        /** Index in `Notification.actions`, or in the wearable extender's list when [wearable]. */
        val index: Int,
        val label: String,
        /** Has a RemoteInput that takes free-form text. */
        val freeFormInput: Boolean,
        val semantic: Int = 0,
        /** System-generated smart action (`isContextual`): never shown on the computer. */
        val contextual: Boolean = false,
        val wearable: Boolean = false,
    )

    /**
     * The action a reply goes to: a free-form input action, preferring the one marked as Reply, then the
     * phone's own actions over the wearable extender's (older apps keep their reply only there).
     */
    fun pickReply(actions: List<Info>): Info? =
        actions.filter { it.freeFormInput && !it.contextual }
            .sortedWith(compareBy<Info>({ if (it.semantic == SEMANTIC_REPLY) 0 else 1 }, { if (it.wearable) 1 else 0 }, { it.index }))
            .firstOrNull()

    /** The other buttons, as the computer shows them (phone actions only, no text inputs, no smart actions). */
    fun others(actions: List<Info>, max: Int = NotifWire.MAX_ACTIONS): List<NotifWire.Action> =
        actions.filter { !it.wearable && !it.freeFormInput && !it.contextual && it.label.isNotBlank() }
            .take(max)
            .map { NotifWire.Action(it.index, it.label.trim()) }

    /** Short opaque key for Android's [sbnKey] (it contains the user id, package and tag). */
    fun shortKey(sbnKey: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(sbnKey.toByteArray(Charsets.UTF_8))
        return d.take(9).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The notifications currently shown on the computers, by short key, bounded (oldest dropped first).
 * [V] is whatever the listener needs to act on one (its Android key and Notification).
 */
class NotifIndex<V>(private val max: Int = 300) {
    private val map = LinkedHashMap<String, V>()

    @Synchronized fun put(shortKey: String, v: V) {
        map.remove(shortKey)
        map[shortKey] = v
        while (map.size > max) map.remove(map.keys.first())
    }

    @Synchronized fun get(shortKey: String): V? = map[shortKey]
    @Synchronized fun remove(shortKey: String): V? = map.remove(shortKey)
    @Synchronized fun clear() = map.clear()
    @Synchronized fun values(): List<V> = map.values.toList()
    @Synchronized fun keys(): List<String> = map.keys.toList()
    val size: Int @Synchronized get() = map.size
}
