package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.NotifWire.Call
import com.shazan.airbridge.phone.NotifWire.CallState

/**
 * The phone's call as the computers see it (pure, unit-tested; callers serialise).
 *
 * Two inputs, either of which may be missing:
 * - the telephony call state (IDLE / RINGING / OFFHOOK), when READ_PHONE_STATE was granted. It is
 *   authoritative for the state when present;
 * - the dialer's call notification (seen by the notification listener): incoming or ongoing, with the
 *   caller as the dialer shows it. It gives the name, and the state when telephony is not available.
 *
 * Each input method returns true when [current] changed and should be sent. An ended call is reported
 * once ([CallState.ENDED]) and then forgotten.
 */
class CallMachine(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Telephony { IDLE, RINGING, OFFHOOK }
    enum class NotifKind { INCOMING, ONGOING }

    var current: Call? = null
        private set

    /** True once a telephony callback was registered: notifications then only add names. */
    var telephonyAuthoritative = false

    private var seq = 0
    private var lastTelephony = Telephony.IDLE
    private var notifName: String? = null
    private var notifNumber: String? = null
    private var notifLabel: String? = null
    private var notifKind: NotifKind? = null

    private fun newId(): String = "c${++seq}-${(clock() / 1000).toString(36)}"

    private fun canFor(state: CallState): List<String> = when (state) {
        CallState.RINGING -> listOf("answer", "decline")
        CallState.ACTIVE, CallState.HELD -> listOf("end", "mute", "speaker", "route")
        CallState.ENDED -> emptyList()
    }

    private fun set(c: Call?): Boolean {
        val changed = c != current
        current = c
        return changed
    }

    private fun start(state: CallState): Boolean {
        val now = clock()
        return set(Call(newId(), state, notifName, notifNumber, since = if (state == CallState.ACTIVE) now else null, can = canFor(state), label = notifLabel))
    }

    private fun toState(state: CallState): Boolean {
        val c = current?.takeIf { it.state != CallState.ENDED } ?: return start(state)
        if (c.state == state) return false
        val since = if (state == CallState.ACTIVE) c.since ?: clock() else c.since
        return set(c.copy(state = state, since = since, can = canFor(state)))
    }

    private fun end(): Boolean {
        val c = current ?: return false
        if (c.state == CallState.ENDED) return false
        current = c.copy(state = CallState.ENDED, can = emptyList())
        return true
    }

    /** After an ENDED snapshot went out: forget the call (the next one starts fresh). */
    fun clearEnded() {
        if (current?.state == CallState.ENDED) {
            current = null
            notifName = null; notifNumber = null; notifLabel = null; notifKind = null
        }
    }

    fun onTelephony(state: Telephony): Boolean {
        val prev = lastTelephony
        lastTelephony = state
        if (state == prev && current != null) return false
        return when (state) {
            Telephony.RINGING -> toState(CallState.RINGING)
            Telephony.OFFHOOK -> toState(CallState.ACTIVE)
            Telephony.IDLE -> end()
        }
    }

    /**
     * The dialer's call notification was posted/updated ([kind] non-null) or removed (null). [name], [number] and
     * [label] are the caller as best known (contacts first, then what the dialer shows).
     */
    fun onCallNotification(kind: NotifKind?, name: String?, number: String?, label: String? = null): Boolean {
        if (kind != null) {
            notifKind = kind
            if (!name.isNullOrBlank()) notifName = name
            if (!number.isNullOrBlank()) notifNumber = number
            if (!label.isNullOrBlank()) notifLabel = label
        } else notifKind = null

        if (telephonyAuthoritative) {
            // Names only. A call that telephony already knows gets the caller filled in.
            val c = current ?: return false
            if (kind == null || c.state == CallState.ENDED) return false
            return set(c.copy(name = notifName ?: c.name, number = notifNumber ?: c.number, label = notifLabel ?: c.label))
        }
        val changedState = when (kind) {
            NotifKind.INCOMING -> toState(CallState.RINGING)
            NotifKind.ONGOING -> toState(CallState.ACTIVE)
            null -> end()
        }
        val c = current
        val changedName = if (c != null && c.state != CallState.ENDED &&
            (c.name != notifName && notifName != null || c.number != notifNumber && notifNumber != null || c.label != notifLabel && notifLabel != null)) {
            set(c.copy(name = notifName ?: c.name, number = notifNumber ?: c.number, label = notifLabel ?: c.label))
        } else false
        return changedState || changedName
    }

    fun setMuted(muted: Boolean): Boolean {
        val c = current ?: return false
        if (c.state == CallState.ENDED) return false
        return set(c.copy(muted = muted))
    }

    /** §13.5: the phone's call audio devices and the one in use. */
    fun setRoutes(routes: List<NotifWire.Route>, route: String?): Boolean {
        val c = current ?: return false
        if (c.state == CallState.ENDED) return false
        return set(c.copy(routes = routes, route = route))
    }

    fun setSpeaker(on: Boolean): Boolean {
        val c = current ?: return false
        if (c.state == CallState.ENDED) return false
        return set(c.copy(speaker = on))
    }

    /**
     * Whether `call_action` [op] for call [id] applies to the current call. Stale ids (a call that already
     * ended, a double click on Answer) are refused so the phone never answers or ends the wrong call.
     */
    fun accepts(id: String, op: String): Boolean {
        val c = current ?: return false
        if (c.id != id) return false
        return when (op) {
            "answer", "decline" -> c.state == CallState.RINGING
            "end" -> c.state == CallState.ACTIVE || c.state == CallState.HELD || c.state == CallState.RINGING
            "mute", "unmute", "speaker_on", "speaker_off", "route" -> c.state == CallState.ACTIVE || c.state == CallState.HELD
            else -> false
        }
    }
}
