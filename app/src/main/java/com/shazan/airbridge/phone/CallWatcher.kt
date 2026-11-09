package com.shazan.airbridge.phone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.shazan.airbridge.net.Hub
import java.util.concurrent.Executor

/**
 * Calls on the computer (PROTOCOL §13.3). Watches the phone's call state (TelephonyCallback with
 * READ_PHONE_STATE, else only the dialer's call notification), sends `call_state` through [PhoneRelay], and
 * answers / declines / ends / mutes on request.
 *
 * Call audio stays on the phone: Android doesn't let apps carry cellular call audio over the network.
 * Mute uses the system microphone mute; speaker uses the communication-device API. Both are best effort
 * (some phones ignore them for calls placed by the dialer), so the computer shows what the phone reports.
 * While a call is active the real mute and speaker state is watched (broadcast, communication-device listener
 * and a 1 s poll as a fallback) so a change made in the phone app reaches the computers too (§13.4).
 * The caller's name, label and photo come from [CallerInfo]. Runs on [PhoneRelay.worker].
 */
object CallWatcher {
    private const val TAG = "AirBridge.Calls"

    /** The dialer's call notification as the listener saw it. */
    class CallNotif(val sbnKey: String, val notification: Notification, val kind: CallMachine.NotifKind, val title: String, val text: String)

    private lateinit var app: Context
    private val machine = CallMachine()
    private var lastNotif: CallNotif? = null
    private var registered: Any? = null
    private var numberReceiver: BroadcastReceiver? = null
    /** The caller's photo (base64 JPEG) for the current call, and which call it was last sent for. */
    private var photo: String? = null
    private var photoSentFor: String? = null
    /** The call id whose ringing start was already announced (presence snapshot first). */
    private var ringAnnounced: String? = null
    /** Telephony said "ringing" before the dialer named the caller: wait briefly so the computer never shows a blank caller. */
    private val ringHold = Runnable { publish() }
    private var holding = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        PhoneRelay.worker.post {
            RingSilencer.restore(app) // left over if the app stopped during a call
            refresh()
        }
    }

    private val worker get() = PhoneRelay.worker
    private val executor = Executor { r -> worker.post(r) }

    /** Calls forwarding is possible right now (switch on, and some way to see calls). */
    fun available(): Boolean = PhoneRelaySettings.callsEnabled &&
        (PhoneRelaySettings.canWatchCalls(app) || PhoneNotifListener.instance != null)

    fun current(): NotifWire.Call? = if (PhoneRelaySettings.callsEnabled) machine.current?.takeIf { it.state != NotifWire.CallState.ENDED } else null

    /** (Un)register the telephony listener to match the setting and permission. Worker thread. */
    fun refresh() {
        val want = PhoneRelaySettings.callsEnabled && PhoneRelaySettings.canWatchCalls(app)
        if (want && registered == null) register()
        else if (!want && registered != null) unregister()
        if (!PhoneRelaySettings.callsEnabled && machine.current != null) {
            // Switched off mid-call: the computers close their panel.
            machine.onTelephony(CallMachine.Telephony.IDLE)
            publish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onTelephony(state)
                }
                tm.registerTelephonyCallback(executor, cb)
                registered = cb
            } else {
                // Created on the worker's looper, so its callbacks arrive there.
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onTelephony(state, phoneNumber)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                registered = l
            }
            machine.telephonyAuthoritative = true
            registerNumber()
            Log.i(TAG, "watching call state")
        } catch (t: Throwable) {
            Log.w(TAG, "call state listener", t)
            registered = null
            machine.telephonyAuthoritative = false
        }
    }

    private fun unregister() {
        val tm = app.getSystemService(TelephonyManager::class.java)
        try {
            val r = registered
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && r is TelephonyCallback) tm?.unregisterTelephonyCallback(r)
            else if (r is PhoneStateListener) @Suppress("DEPRECATION") tm?.listen(r, PhoneStateListener.LISTEN_NONE)
        } catch (t: Throwable) { Log.w(TAG, "unregister", t) }
        registered = null
        machine.telephonyAuthoritative = false
        numberReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Throwable) {} }
        numberReceiver = null
    }

    /**
     * The ringing call's number straight from Android, the moment it rings. Some phones' call screens (vivo's, for
     * one) post no notification while ringing, so this is the only early source of the caller there; the number
     * then goes through the contacts for the name and photo before the computer shows the call. Android includes
     * the number only when Call logs is granted (without it the broadcast just has no number). Worker thread.
     */
    @Suppress("DEPRECATION")
    private fun registerNumber() {
        if (numberReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() } ?: return
                onTelephony(TelephonyManager.CALL_STATE_RINGING, number)
            }
        }
        try {
            // A protected system broadcast: only Android can send it.
            ContextCompat.registerReceiver(app, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), null, worker,
                ContextCompat.RECEIVER_EXPORTED)
            numberReceiver = r
        } catch (t: Throwable) { Log.w(TAG, "number receiver", t) }
    }

    private fun onTelephony(state: Int, number: String? = null) {
        val s = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallMachine.Telephony.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallMachine.Telephony.OFFHOOK
            else -> CallMachine.Telephony.IDLE
        }
        val wasNew = machine.current == null || machine.current?.state == NotifWire.CallState.ENDED
        Log.i(TAG, "telephony $s (number ${if (number.isNullOrBlank()) "no" else "yes"})")
        if (s == CallMachine.Telephony.RINGING && wasNew) PhonePresence.markRing()
        if (s == CallMachine.Telephony.IDLE) PhonePresence.onRingEnd()
        var changed = machine.onTelephony(s)
        if (!number.isNullOrBlank() && machine.current?.number.isNullOrBlank()) {
            val who = CallerInfo.resolve(app, null, number.replace(BIDI, "").trim(), lastNotif?.notification)
            if (who.photoJpgB64 != null) photo = who.photoJpgB64
            changed = machine.onCallNotification(lastNotif?.kind ?: CallMachine.NotifKind.INCOMING, who.name, who.number, who.label) || changed
        }
        if (s == CallMachine.Telephony.IDLE) resetAudio()
        if (!changed) return
        val c = machine.current
        if (s == CallMachine.Telephony.RINGING && wasNew && c != null && c.name.isNullOrBlank() && c.number.isNullOrBlank()) {
            // The dialer's notification (with the caller) usually follows within a few hundred ms. Wait for it
            // (it publishes at once when it comes). With Call logs the number arrives within milliseconds instead.
            holding = true
            worker.removeCallbacks(ringHold)
            // With Call logs the number surely follows (usually within milliseconds; slower right after a cold
            // start), so it is worth a little longer than a notification that may never come (vivo).
            worker.postDelayed(ringHold, if (PhoneRelaySettings.canSeeNumber(app)) 2000 else 800)
            return
        }
        publish()
    }

    /** From [PhoneRelay]: the dialer's call notification (null = removed). */
    fun onCallNotification(n: CallNotif?) {
        if (!PhoneRelaySettings.callsEnabled) { lastNotif = null; return }
        lastNotif = n
        if (n == null) {
            if (machine.onCallNotification(null, null, null)) publish()
            return
        }
        // Dialers wrap numbers in invisible direction marks ("\u202A555-9876\u202C"): drop them first.
        val title = n.title.replace(BIDI, "").trim()
        val who = CallerInfo.resolve(app, title.takeIf { it.isNotBlank() }, null, n.notification)
        Log.i(TAG, "call notification ${n.kind} from ${n.sbnKey.substringAfter('|').substringBefore('|')}: name ${if (who.name.isNullOrBlank()) "no" else "yes"}, number ${if (who.number.isNullOrBlank()) "no" else "yes"}, buttons ${n.notification.actions?.map { it.title } ?: "none"}")
        if (who.photoJpgB64 != null) photo = who.photoJpgB64
        if (machine.onCallNotification(n.kind, who.name, who.number, who.label) || holding) publish()
    }

    private fun publish() {
        if (holding) { holding = false; worker.removeCallbacks(ringHold) }
        val c = machine.current ?: return
        if (c.state == NotifWire.CallState.RINGING && ringAnnounced != c.id) {
            ringAnnounced = c.id
            // The computers decide where it rings from this snapshot, so it goes out first.
            PhonePresence.onRingStart()
        }
        if (c.state != NotifWire.CallState.RINGING) {
            PhonePresence.onRingEnd()
            RingSilencer.restore(app)
        }
        // The photo goes before the call so the banner shows it from the start.
        if (c.state != NotifWire.CallState.ENDED && photo != null && photoSentFor != c.id) {
            photoSentFor = c.id
            photoMessage(c.id)?.let { PhoneRelay.sendToCallSinks(it) }
        }
        Log.i(TAG, "send ${c.state}: name ${if (c.name.isNullOrBlank()) "no" else "yes"}, number ${if (c.number.isNullOrBlank()) "no" else "yes"}")
        PhoneRelay.sendCall(c)
        when (c.state) {
            NotifWire.CallState.ACTIVE, NotifWire.CallState.HELD -> startAudioWatch()
            NotifWire.CallState.ENDED -> {
                stopAudioWatch()
                machine.clearEnded()
                photo = null; photoSentFor = null; ringAnnounced = null
            }
            NotifWire.CallState.RINGING -> {}
        }
    }

    private fun photoMessage(id: String): Map<String, Any?>? = photo?.let { NotifWire.callPhoto(id, it) }

    /** For a computer that connects during a call: the caller's photo, if any (sent before the call). */
    fun currentPhoto(): Map<String, Any?>? = current()?.let { photoMessage(it.id) }

    /** §13.4: a computer rings for call [id] instead of the phone. */
    fun onRing(id: String, silencePhone: Boolean) {
        val c = machine.current ?: return
        if (c.id != id || c.state != NotifWire.CallState.RINGING || !silencePhone) return
        RingSilencer.silence(app)
    }

    // ---- the real mute / speaker state while a call is active ----

    private var muteReceiver: BroadcastReceiver? = null
    private var commListener: Any? = null
    private val poll = object : Runnable {
        override fun run() { syncAudio(); worker.postDelayed(this, 1000) }
    }

    private fun startAudioWatch() {
        if (muteReceiver != null) { syncAudio(); return }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { worker.post { syncAudio() } }
        }
        try {
            val f = IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED).apply {
                @Suppress("DEPRECATION") addAction(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            muteReceiver = r
        } catch (t: Throwable) { Log.w(TAG, "mute receiver", t) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val l = AudioManager.OnCommunicationDeviceChangedListener { worker.post { syncAudio() } }
                audio()?.addOnCommunicationDeviceChangedListener(executor, l)
                commListener = l
            } catch (t: Throwable) { Log.w(TAG, "comm device listener", t) }
        }
        worker.removeCallbacks(poll)
        worker.post(poll)
    }

    private fun stopAudioWatch() {
        worker.removeCallbacks(poll)
        muteReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Throwable) {} }
        muteReceiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                try { audio()?.removeOnCommunicationDeviceChangedListener(it) } catch (_: Throwable) {}
            }
        }
        commListener = null
    }


    private fun speakerOn(am: AudioManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else @Suppress("DEPRECATION") am.isSpeakerphoneOn

    /** Reads what the phone really does now; sends `call_state` when it changed (from here or the phone app). */
    private fun syncAudio() {
        val c = machine.current ?: return
        if (c.state != NotifWire.CallState.ACTIVE && c.state != NotifWire.CallState.HELD) return
        val am = audio() ?: return
        val muted = (try { am.isMicrophoneMute } catch (_: Throwable) { c.muted }) || dialerMuted() == true
        val speaker = try { speakerOn(am) } catch (_: Throwable) { c.speaker }
        val a = machine.setMuted(muted)
        val b = machine.setSpeaker(speaker)
        val (routes, route) = try { readRoutes(am) } catch (t: Throwable) { c.routes to c.route }
        val r = machine.setRoutes(routes, route)
        if (a || b || r) publish()
    }

    // ---- call audio devices (§13.5) ----

    /** The phone's call audio devices and the one in use. */
    @SuppressLint("MissingPermission")
    private fun readRoutes(am: AudioManager): Pair<List<NotifWire.Route>, String?> {
        val isComputer = { d: CallRoutes.Dev -> CallAudioInfo.isComputerAudio(d.name, d.address, d.type) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devs = am.availableCommunicationDevices.map { CallRoutes.Dev(it.type, it.productName?.toString(), it.address) }
            val cur = am.communicationDevice?.let { CallRoutes.id(CallRoutes.Dev(it.type, it.productName?.toString(), it.address)) }
            return CallRoutes.routes(devs, isComputer) to cur
        }
        // Before Android 12 there is no list of call devices: the usual ones, from what is plugged in.
        @Suppress("DEPRECATION")
        val wired = am.isWiredHeadsetOn
        @Suppress("DEPRECATION")
        val sco = am.isBluetoothScoOn
        val devs = buildList {
            if (!wired) add(CallRoutes.Dev(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, null, null))
            add(CallRoutes.Dev(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null, null))
            if (wired) add(CallRoutes.Dev(AudioDeviceInfo.TYPE_WIRED_HEADSET, null, null))
            if (sco) add(CallRoutes.Dev(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null, null))
        }
        @Suppress("DEPRECATION")
        val cur = when {
            am.isSpeakerphoneOn -> "phone:speaker"
            sco -> "phone:bt"
            wired -> "phone:wired"
            else -> "phone:earpiece"
        }
        return CallRoutes.routes(devs, isComputer) to cur
    }

    /**
     * §13.5 `dev:"computer"`: send the call audio to the computer's hands-free link, as picking it in the phone's
     * own call screen does. A computer asks for this when the user picks it there: phones often refuse audio that
     * the hands-free side opens itself, never the phone's own choice.
     */
    @SuppressLint("MissingPermission")
    private fun routeToComputer(am: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = am.availableCommunicationDevices.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && CallAudioInfo.isComputerAudio(d.productName?.toString(), d.address, d.type)
            } ?: return false.also { Log.i(TAG, "call audio to the computer: not an audio device yet") }
            if (am.communicationDevice?.id == dev.id) return true
            if (speakerOn(am)) setSpeaker(false)
            return am.setCommunicationDevice(dev).also { if (it) forcedSpeaker = true; Log.i(TAG, "call audio to the computer: $it") }
        }
        @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false; am.startBluetoothSco(); am.isBluetoothScoOn = true }
        return true
    }

    /**
     * Moves the call to route [id]. Speaker goes through [setSpeaker] (the dialer's own Speaker button when it has
     * one). Earpiece first undoes anything we forced (back to the phone's own choice) and only forces the earpiece
     * when a headset would otherwise keep the call. A headset is chosen with setCommunicationDevice — with the
     * priority caveat of [setSpeaker]: the phone app can't change it until the call ends or another pick here.
     */
    @SuppressLint("MissingPermission")
    private fun selectRoute(id: String): Boolean {
        val am = audio() ?: return false
        if (id == "computer") {
            if (routeToComputer(am)) return true
            // Asked the moment the computer's link came up: Android may list it as an audio device a moment later.
            for (delay in listOf(500L, 1000L, 2000L, 4000L)) worker.postDelayed({
                if (machine.current?.state == NotifWire.CallState.ACTIVE && audio()?.let { routeToComputer(it) } == true) syncAudio()
            }, delay)
            return true
        }
        return when (val p = CallRoutes.parse(id)) {
            null -> false
            CallRoutes.Pick.Speaker -> setSpeaker(true)
            CallRoutes.Pick.Earpiece -> {
                if (speakerOn(am)) setSpeaker(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (forcedSpeaker) { am.clearCommunicationDevice(); forcedSpeaker = false }
                    if (am.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        val ear = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        ear != null && am.setCommunicationDevice(ear).also { if (it) forcedSpeaker = true }
                    } else true
                } else {
                    @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false; if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false } }
                    true
                }
            }
            is CallRoutes.Pick.Device -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val dev = am.availableCommunicationDevices.firstOrNull { d ->
                        CallRoutes.kind(d.type) == p.kind && (p.address == null || d.address.equals(p.address, ignoreCase = true))
                    }
                    dev != null && am.setCommunicationDevice(dev).also { if (it) forcedSpeaker = true }
                } else {
                    @Suppress("DEPRECATION")
                    when (p.kind) {
                        "bt" -> { am.isSpeakerphoneOn = false; am.startBluetoothSco(); am.isBluetoothScoOn = true; true }
                        "wired" -> { am.isSpeakerphoneOn = false; if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }; true }
                        else -> false
                    }
                }
            }
        }
    }

    // ---- actions from a computer ----

    fun act(peerId: String, id: String, op: String, dev: String? = null) {
        if (!PhoneRelaySettings.callsEnabled) { PhoneRelay.sendCallResult(peerId, NotifWire.callResult(id, op, false, "off")); return }
        if (!machine.accepts(id, op)) {
            Log.i(TAG, "action $op refused: no such call now (${machine.current?.state})")
            PhoneRelay.sendCallResult(peerId, NotifWire.callResult(id, op, false, "no_call")); return
        }
        val ok = try {
            when (op) {
                "answer" -> answer()
                "decline" -> hangUp(EXTRA_DECLINE_INTENT)
                "end" -> hangUp(EXTRA_HANG_UP_INTENT)
                "mute", "unmute" -> setMuted(op == "mute")
                "speaker_on", "speaker_off" -> setSpeaker(op == "speaker_on")
                "route" -> dev?.let { selectRoute(it) } ?: false
                else -> false
            }
        } catch (t: Throwable) { Log.w(TAG, op, t); false }
        val reason = if (ok) null else when (op) {
            "answer", "decline", "end" -> if (PhoneRelaySettings.canAnswer(app)) "failed" else "no_permission"
            else -> "failed"
        }
        Log.i(TAG, "action $op${dev?.let { " ($it)" } ?: ""}: ${if (ok) "done" else "failed: $reason"}" +
            (if (op == "mute" || op == "unmute") ", mic muted now ${audio()?.isMicrophoneMute}" else ""))
        PhoneRelay.sendCallResult(peerId, NotifWire.callResult(id, op, ok, reason))
        if (op in setOf("mute", "unmute", "speaker_on", "speaker_off", "route")) {
            // What the phone really did (it may refuse): read back now and once more after the route settles.
            syncAudio()
            worker.postDelayed({ syncAudio() }, 400)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun answer(): Boolean {
        if (PhoneRelaySettings.canAnswer(app)) {
            try { app.getSystemService(TelecomManager::class.java)?.acceptRingingCall(); return true } catch (t: Throwable) { Log.w(TAG, "acceptRingingCall", t) }
        }
        return notifIntent(EXTRA_ANSWER_INTENT)?.let { PhoneRelay.sendPi(it, null) } ?: false
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun hangUp(extra: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && PhoneRelaySettings.canAnswer(app)) {
            try { if (app.getSystemService(TelecomManager::class.java)?.endCall() == true) return true } catch (t: Throwable) { Log.w(TAG, "endCall", t) }
        }
        val pi = notifIntent(extra) ?: notifIntent(if (extra == EXTRA_DECLINE_INTENT) EXTRA_HANG_UP_INTENT else EXTRA_DECLINE_INTENT)
        return pi?.let { PhoneRelay.sendPi(it, null) } ?: false
    }

    /** The CallStyle (Android 12+) answer / decline / hang-up intent of the dialer's notification. */
    @Suppress("DEPRECATION")
    private fun notifIntent(extra: String): PendingIntent? = try {
        lastNotif?.notification?.extras?.getParcelable(extra) as? PendingIntent
    } catch (_: Throwable) { null }

    private fun audio(): AudioManager? = app.getSystemService(AudioManager::class.java)

    /**
     * Mute / unmute. Android's microphone mute first (the phone's call screen follows it on most phones). Some
     * phones (vivo) ignore it from apps: then the dialer's own Mute button from its call notification, as for the
     * speaker.
     */
    private fun setMuted(on: Boolean): Boolean {
        val am = audio() ?: return false
        try { am.isMicrophoneMute = on } catch (_: Throwable) {}
        if (am.isMicrophoneMute == on) return true
        if (dialerMuted() == on) return true
        val pi = dialerAction(if (on) MUTE_WORDS else UNMUTE_WORDS + MUTE_WORDS) ?: return false
        return try { pi.send(); Log.i(TAG, "mute through the call screen's own button"); true } catch (_: Throwable) { false }
    }

    /** The dialer's call notification says whether the call is muted (its button reads "Unmute"), if it says. */
    private fun dialerMuted(): Boolean? {
        val titles = lastNotif?.notification?.actions?.mapNotNull { it.title?.toString()?.lowercase() } ?: return null
        if (titles.any { t -> UNMUTE_WORDS.any { t.contains(it) } }) return true
        if (titles.any { t -> MUTE_WORDS.any { t.contains(it) } }) return false
        return null
    }

    private fun dialerAction(words: List<String>): PendingIntent? =
        lastNotif?.notification?.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase() ?: return@firstOrNull false
            words.any { t.contains(it) }
        }?.actionIntent

    private val UNMUTE_WORDS = listOf("unmute", "अनम्यूट", "réactiver le micro", "stummschaltung aufheben", "activar micrófono")
    private val MUTE_WORDS = listOf("mute", "म्यूट", "মিউট", "મ્યૂટ", "ம்யூட்", "మ్యూట్", "ಮ್ಯೂಟ್", "muet", "couper le micro", "stumm", "silenciar")

    /**
     * Speaker on/off. Prefer pressing the dialer's own Speaker button (its call notification action): then the
     * phone's call screen stays in charge and its own Speaker button keeps working. Only if the dialer offers no
     * such button do we set the route ourselves — and then Android gives our choice priority for the rest of the
     * call, so the phone's own Speaker button can't change it until the call ends (released in resetAudio).
     */
    private fun setSpeaker(on: Boolean): Boolean {
        val am = audio() ?: return false
        if (speakerOn(am) == on) return true
        dialerSpeakerAction()?.let { pi ->
            try { pi.send(); return true } catch (_: Throwable) {}
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                dev != null && am.setCommunicationDevice(dev).also { if (it) forcedSpeaker = true }
            } else { am.clearCommunicationDevice(); forcedSpeaker = false; true }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn == on
        }
        return ok
    }

    /** True while the call device (speaker, earpiece or a headset) is one we set, not the dialer's choice. */
    private var forcedSpeaker = false

    /**
     * The dialer's "Speaker" toggle in its ongoing-call notification (Pixel, Samsung, many others put one there).
     * Matched by the action label in the phone's language — most dialers just say "Speaker".
     */
    private fun dialerSpeakerAction(): PendingIntent? {
        val actions = lastNotif?.notification?.actions ?: return null
        return actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase() ?: return@firstOrNull false
            SPEAKER_WORDS.any { t.contains(it) }
        }?.actionIntent
    }

    private val SPEAKER_WORDS = listOf("speaker", "spkr", "स्पीकर", "स्पीकर", "স্পিকার", "સ્પીકર", "ஸ்பீக்கர்", "స్పీకర్", "ಸ್ಪೀಕರ್",
        "haut-parleur", "lautsprecher", "altavoz", "alto-falante", "altoparlante")

    /** After the call: never leave the phone's microphone muted or the speaker forced by us. */
    private fun resetAudio() {
        val c = machine.current
        try {
            val am = audio() ?: return
            if (c?.muted == true || am.isMicrophoneMute) am.isMicrophoneMute = false
            if (forcedSpeaker && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            forcedSpeaker = false
        } catch (_: Throwable) {}
    }

    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** `Notification.EXTRA_ANSWER_INTENT` etc. (API 31 CallStyle). */
    private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
    private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
    private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
}
