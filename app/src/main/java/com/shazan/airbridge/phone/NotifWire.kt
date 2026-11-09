package com.shazan.airbridge.phone

/**
 * Phone notifications and calls on the computer (docs/PROTOCOL.md §13): the message shapes, without any
 * Android or org.json types so they can be unit-tested on the JVM. Messages are built as plain maps
 * (`JsonMaps.toJson` turns them into JSONObjects) and incoming ones are parsed from maps.
 */
object NotifWire {
    const val CAP_NOTIF_SOURCE = "notif_source"
    const val CAP_NOTIF_SINK = "notif_sink"
    const val CAP_CALL_SOURCE = "call_source"
    const val CAP_CALL_SINK = "call_sink"
    /** §13.4: where a call rings (presence, proximity beacon, ring decision). Both sides advertise it. */
    const val CAP_CALL_RING = "call_ring"

    const val MAX_TITLE = 200
    const val MAX_TEXT = 2000
    const val MAX_MSGS = 6
    const val MAX_MSG_TEXT = 1000
    const val MAX_ACTIONS = 3
    const val MAX_LABEL = 40
    const val MAX_REPLY = 4000
    const val MAX_KEY = 64

    /** One message of a conversation (MessagingStyle). `from` null = the phone's user. */
    data class Msg(val from: String?, val text: String, val ts: Long)

    data class Action(val index: Int, val label: String)

    /** One forwarded notification, already filtered and trimmed. */
    data class PhoneNotif(
        /** Opaque, stable for the lifetime of the notification (a hash of Android's key). */
        val key: String,
        val pkg: String,
        val appName: String,
        val title: String,
        val text: String,
        val sub: String = "",
        val ts: Long,
        /** Short category: "msg", "email", "call", "alarm", "event", "social", "other". */
        val cat: String = "other",
        /** Conversation title for group chats (MessagingStyle), else null. */
        val conv: String? = null,
        val msgs: List<Msg> = emptyList(),
        val canReply: Boolean = false,
        val replyLabel: String? = null,
        val actions: List<Action> = emptyList(),
        /** False for quiet updates and the resync after connecting: list only, no banner or sound. */
        val alert: Boolean = true,
    )

    enum class CallState(val wire: String) { RINGING("ringing"), ACTIVE("active"), HELD("held"), ENDED("ended") }

    data class Call(
        val id: String,
        val state: CallState,
        val name: String?,
        val number: String?,
        /** When the call was answered (Unix ms), for the duration on the computer. */
        val since: Long?,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        /** What the computer may offer right now: answer, decline, end, mute, speaker. */
        val can: List<String> = emptyList(),
        /** The number's label in the phone's contacts ("Mobile", "Work"), localised on the phone. */
        val label: String? = null,
        /** §13.5: where the phone can play the call, and which of them it uses now (an id from [routes]). */
        val routes: List<Route> = emptyList(),
        val route: String? = null,
    )

    /** One place the phone can play a call (§13.5): earpiece, speaker, wired, or a Bluetooth headset by name. */
    data class Route(val id: String, val kind: String, val name: String?, val computer: Boolean = false)

    /** What the phone knows about its user right now (§13.4), for "where should it ring". */
    data class Presence(
        val screenOn: Boolean,
        val locked: Boolean,
        /** Milliseconds since the user last touched or unlocked the phone; null = not known. */
        val idleMs: Long?,
        /** True when individual taps are seen (accessibility on), so [idleMs] is precise. */
        val touchKnown: Boolean,
    )

    private fun clip(s: String?, max: Int): String {
        val t = (s ?: "").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /** Category from Android's `Notification.category` (null-safe). */
    fun shortCategory(androidCategory: String?, conversation: Boolean): String = when {
        conversation -> "msg"
        androidCategory == "msg" -> "msg"
        androidCategory == "email" -> "email"
        androidCategory == "call" || androidCategory == "missed_call" -> "call"
        androidCategory == "alarm" || androidCategory == "reminder" -> "alarm"
        androidCategory == "event" -> "event"
        androidCategory == "social" -> "social"
        else -> "other"
    }

    // ---- source → sink ----

    fun post(n: PhoneNotif): Map<String, Any?> = linkedMapOf<String, Any?>(
        "t" to "notif_post",
        "key" to n.key,
        "pkg" to n.pkg,
        "app" to clip(n.appName, 80),
        "title" to clip(n.title, MAX_TITLE),
        "text" to clip(n.text, MAX_TEXT),
        "ts" to n.ts,
        "cat" to n.cat,
        "reply" to n.canReply,
        "alert" to n.alert,
    ).apply {
        if (n.sub.isNotBlank()) put("sub", clip(n.sub, MAX_TITLE))
        if (!n.conv.isNullOrBlank()) put("conv", clip(n.conv, MAX_TITLE))
        if (n.canReply && !n.replyLabel.isNullOrBlank()) put("reply_label", clip(n.replyLabel, MAX_LABEL))
        if (n.msgs.isNotEmpty()) put("msgs", n.msgs.takeLast(MAX_MSGS).map { m ->
            linkedMapOf<String, Any?>("text" to clip(m.text, MAX_MSG_TEXT), "ts" to m.ts).apply {
                if (m.from == null) put("me", true) else put("from", clip(m.from, 80))
            }
        })
        if (n.actions.isNotEmpty()) put("acts", n.actions.take(MAX_ACTIONS).map {
            linkedMapOf<String, Any?>("i" to it.index, "label" to clip(it.label, MAX_LABEL))
        })
    }

    fun remove(key: String): Map<String, Any?> = linkedMapOf("t" to "notif_remove", "key" to key)

    /** Everything this phone showed before is stale (sent before a resync and when forwarding stops). */
    fun reset(): Map<String, Any?> = linkedMapOf("t" to "notif_reset")

    fun icon(pkg: String, pngBase64: String): Map<String, Any?> =
        linkedMapOf("t" to "notif_icon", "pkg" to pkg, "png" to pngBase64)

    /** Whether this phone forwards right now, and why not, so the computer can show a hint. */
    fun state(notifs: Boolean, calls: Boolean, notifsWhy: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to "notif_state", "notifs" to notifs, "calls" to calls).apply {
            if (!notifs && notifsWhy != null) put("why", notifsWhy)
        }

    fun result(key: String, op: String, ok: Boolean, reason: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to "notif_result", "key" to key, "op" to op, "ok" to ok).apply {
            if (!ok && reason != null) put("reason", reason)
        }

    fun call(c: Call): Map<String, Any?> = linkedMapOf<String, Any?>(
        "t" to "call_state",
        "id" to c.id,
        "state" to c.state.wire,
        "muted" to c.muted,
        "speaker" to c.speaker,
        "can" to c.can,
    ).apply {
        if (!c.name.isNullOrBlank()) put("name", clip(c.name, 100))
        if (!c.number.isNullOrBlank()) put("number", clip(c.number, 40))
        if (!c.label.isNullOrBlank()) put("label", clip(c.label, MAX_LABEL))
        if (c.routes.isNotEmpty()) put("routes", c.routes.take(12).map { r ->
            linkedMapOf<String, Any?>("id" to r.id, "kind" to r.kind).apply {
                if (!r.name.isNullOrBlank()) put("name", r.name)
                if (r.computer) put("computer", true)
            }
        })
        if (c.route != null) put("route", c.route)
        if (c.since != null) put("since", c.since)
    }

    /** Largest base64 contact photo we send (a 128 px JPEG is ~6 KB). */
    const val MAX_PHOTO_B64 = 60_000

    /** The caller's contact photo, once per call (§13.4). Null when too big. */
    fun callPhoto(id: String, jpgBase64: String): Map<String, Any?>? =
        if (jpgBase64.length > MAX_PHOTO_B64) null else linkedMapOf("t" to "call_photo", "id" to id, "jpg" to jpgBase64)

    fun presence(p: Presence): Map<String, Any?> = linkedMapOf<String, Any?>(
        "t" to "call_presence",
        "screen" to p.screenOn,
        "locked" to p.locked,
        "touch" to p.touchKnown,
    ).apply { if (p.idleMs != null) put("idle_ms", p.idleMs.coerceIn(0, 86_400_000)) }

    /**
     * The proximity beacon's key (§13.4). `key` null = this phone doesn't advertise now (the computer stops looking).
     * [uuid] is the service UUID, [period] the rotation in seconds.
     */
    fun beacon(keyBase64: String?, uuid: String, period: Int): Map<String, Any?> =
        if (keyBase64 == null) linkedMapOf("t" to "call_beacon", "on" to false)
        else linkedMapOf("t" to "call_beacon", "on" to true, "key" to keyBase64, "uuid" to uuid, "period" to period)

    fun callResult(id: String, op: String, ok: Boolean, reason: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to "call_result", "id" to id, "op" to op, "ok" to ok).apply {
            if (!ok && reason != null) put("reason", reason)
        }

    // ---- sink → source ----

    sealed class Command {
        data class Dismiss(val key: String) : Command()
        data class Reply(val key: String, val text: String) : Command()
        data class Act(val key: String, val index: Int) : Command()
        data class Open(val key: String) : Command()
        data class Subscribe(val notifs: Boolean, val calls: Boolean) : Command()
        /** [dev] = the route id for op `route` (§13.5). */
        data class CallAction(val id: String, val op: String, val dev: String? = null) : Command()
        /** §13.4: the computer decided where call [id] rings; [silencePhone] = it rings there instead. */
        data class Ring(val id: String, val silencePhone: Boolean) : Command()
    }

    val CALL_OPS = setOf("answer", "decline", "end", "mute", "unmute", "speaker_on", "speaker_off", "route")

    private fun Map<String, Any?>.s(k: String): String? = (this[k] as? String)?.takeIf { it.isNotEmpty() }
    private fun Map<String, Any?>.key(): String? = s("key")?.takeIf { it.length <= MAX_KEY }
    private fun Map<String, Any?>.bool(k: String, def: Boolean): Boolean = this[k] as? Boolean ?: def

    /** Parses one sink → source message; null = unknown or malformed (ignored, design rule 1). */
    fun parse(m: Map<String, Any?>): Command? = when (m["t"]) {
        "notif_dismiss" -> m.key()?.let { Command.Dismiss(it) }
        "notif_reply" -> {
            val k = m.key()
            val text = m.s("text")?.trim()?.take(MAX_REPLY)
            if (k != null && !text.isNullOrEmpty()) Command.Reply(k, text) else null
        }
        "notif_action" -> {
            val k = m.key()
            val i = (m["i"] as? Number)?.toInt()
            if (k != null && i != null && i in 0 until 16) Command.Act(k, i) else null
        }
        "notif_open" -> m.key()?.let { Command.Open(it) }
        "notif_sub" -> Command.Subscribe(m.bool("notifs", true), m.bool("calls", true))
        "call_action" -> {
            val id = m.s("id")?.takeIf { it.length <= MAX_KEY }
            val op = m.s("op")
            val dev = m.s("dev")?.takeIf { it.length <= 80 }
            when {
                id == null || op !in CALL_OPS -> null
                op == "route" -> dev?.let { Command.CallAction(id, op!!, it) }
                else -> Command.CallAction(id, op!!)
            }
        }
        "call_ring" -> {
            val id = m.s("id")?.takeIf { it.length <= MAX_KEY }
            when {
                id == null -> null
                m["phone"] == "silent" -> Command.Ring(id, true)
                m["phone"] == "ring" -> Command.Ring(id, false)
                else -> null
            }
        }
        else -> null
    }
}
