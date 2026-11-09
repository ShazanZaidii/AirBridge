package com.shazan.airbridge.phone

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.PeerInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Phone notifications on the computers (PROTOCOL §13): takes the listener's events, filters them
 * ([NotifFilter]), rate-limits them ([FloodGate]) and sends them to every ready peer that advertises
 * `notif_sink` (and didn't unsubscribe with `notif_sub`). Carries out the computers' dismiss / reply / action /
 * open requests. All work runs on one worker thread.
 */
object PhoneRelay {
    private const val TAG = "AirBridge.Relay"
    private const val ICON_PX = 96

    private val thread = HandlerThread("airbridge-notifs").apply { start() }
    val worker = Handler(thread.looper)

    /** What we need to act on a forwarded notification. */
    class Entry(val sbnKey: String, val pkg: String, val notification: Notification, val sent: NotifWire.PhoneNotif)

    private val active = NotifIndex<Entry>()
    /** Skipped only because the phone was locked with private content hidden: forwarded on unlock. */
    private val heldWhileLocked = HashSet<String>()
    private val gate = FloodGate<NotifWire.PhoneNotif>()
    private val sentIcons = HashMap<String, MutableSet<String>>()
    private val subs = HashMap<String, NotifWire.Command.Subscribe>()
    private val iconCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }
    private val labelCache = HashMap<String, String>()
    private var unlockReceiver: BroadcastReceiver? = null
    private val flush = Runnable { flushParked() }
    /** Forwarding is on and the listener is connected (what [onSettingsChanged] compares against). */
    private var forwarding = false

    private val ctx: Context get() = Hub.app

    // ---- lifecycle ----

    fun init(app: Context) {
        PhoneRelaySettings.init(app)
        CallWatcher.init(app)
        PhonePresence.init(app)
        BeaconAdvertiser.init(app)
    }

    fun onListenerConnected() = worker.post {
        registerUnlock()
        forwarding = PhoneRelaySettings.notifsEnabled
        if (forwarding) resyncAll()
        broadcastState()
    }

    fun onListenerDisconnected() = worker.post {
        forwarding = false
        clearAll(sendRemovals = true)
        broadcastState()
    }

    /**
     * A switch or permission may have changed (Settings calls this freely, e.g. on every return to the screen):
     * only a real on/off transition resyncs or clears the computers' lists.
     */
    fun onSettingsChanged() = worker.post {
        val now = PhoneRelaySettings.notifsEnabled && PhoneNotifListener.instance != null
        if (now && !forwarding) resyncAll() else if (!now && forwarding) clearAll(sendRemovals = true)
        forwarding = now
        CallWatcher.refresh()
        broadcastState()
        // §13.4: the beacon follows the calls switch (and the Bluetooth permission asked with it).
        val beacon = JsonMaps.toJson(BeaconAdvertiser.message())
        ringPeers(ignoreSwitch = true).forEach { Hub.send(it.id, beacon) }
        BeaconAdvertiser.refresh()
        PhonePresence.publish(force = true)
    }

    /** A muted app's notifications leave the computers at once; an unmuted app's come back. */
    fun onMuteChanged(pkg: String, muted: Boolean) = worker.post {
        if (!PhoneRelaySettings.notifsEnabled) return@post
        if (muted) {
            active.keys().filter { active.get(it)?.pkg == pkg }.forEach { k ->
                active.remove(k); gate.forget(k); sendToSinks(NotifWire.remove(k))
            }
        } else {
            PhoneNotifListener.instance?.snapshot()?.filter { it.first.packageName == pkg }?.forEach { (sbn, imp) ->
                handlePosted(sbn, imp, quiet = true)
            }
        }
    }

    // ---- peers ----

    fun onPeerReady(peerId: String) = worker.post {
        val p = Hub.readySession(peerId)?.info() ?: return@post
        if (NotifWire.CAP_NOTIF_SINK !in p.caps && NotifWire.CAP_CALL_SINK !in p.caps) return@post
        send(peerId, stateMsg())
        if (NotifWire.CAP_NOTIF_SINK in p.caps) resyncPeer(peerId)
        if (NotifWire.CAP_CALL_SINK in p.caps && wantsCalls(p) && NotifWire.CAP_CALL_RING in p.caps) {
            send(peerId, BeaconAdvertiser.message())
            PhonePresence.sendTo(peerId)
            BeaconAdvertiser.refresh()
        }
        if (NotifWire.CAP_CALL_SINK in p.caps) CallWatcher.current()?.let {
            if (wantsCalls(p)) {
                CallWatcher.currentPhoto()?.let { m -> send(peerId, m) }
                send(peerId, NotifWire.call(it))
            }
        }
    }

    fun onPeerGone(peerId: String) = worker.post {
        sentIcons.remove(peerId)
        subs.remove(peerId)
        BeaconAdvertiser.refresh()
    }

    /** `notif_*` / `call_*` from a peer (network thread). */
    fun onMessage(peerId: String, msg: JSONObject) {
        val cmd = NotifWire.parse(JsonMaps.fromJson(msg)) ?: return
        worker.post { handle(peerId, cmd) }
    }

    private fun wantsNotifs(p: PeerInfo) = NotifWire.CAP_NOTIF_SINK in p.caps && subs[p.id]?.notifs != false
    private fun wantsCalls(p: PeerInfo) = NotifWire.CAP_CALL_SINK in p.caps && subs[p.id]?.calls != false

    private fun notifSinks(): List<PeerInfo> = Hub.peers().filter { wantsNotifs(it) }
    fun callSinks(): List<PeerInfo> = Hub.peers().filter { wantsCalls(it) }

    /** §13.4: computers that decide where calls ring (they get presence and the beacon key). */
    fun ringPeers(ignoreSwitch: Boolean = false): List<PeerInfo> =
        if (!ignoreSwitch && !PhoneRelaySettings.callsEnabled) emptyList()
        else Hub.peers().filter { wantsCalls(it) && NotifWire.CAP_CALL_RING in it.caps }

    fun sendRaw(peerId: String, json: JSONObject) {
        try { Hub.send(peerId, json) } catch (t: Throwable) { Log.w(TAG, "send", t) }
    }

    fun sendToCallSinks(m: Map<String, Any?>) {
        val json = JsonMaps.toJson(m)
        callSinks().forEach { Hub.send(it.id, json) }
    }

    private fun send(peerId: String, m: Map<String, Any?>) {
        try { Hub.send(peerId, JsonMaps.toJson(m)) } catch (t: Throwable) { Log.w(TAG, "send", t) }
    }

    private fun sendToSinks(m: Map<String, Any?>) {
        val sinks = notifSinks()
        if (sinks.isEmpty()) return
        val json = JsonMaps.toJson(m)
        sinks.forEach { Hub.send(it.id, json) }
    }

    fun sendCall(c: NotifWire.Call) {
        if (!PhoneRelaySettings.callsEnabled && c.state != NotifWire.CallState.ENDED) return
        val json = JsonMaps.toJson(NotifWire.call(c))
        callSinks().forEach { Hub.send(it.id, json) }
    }

    fun sendCallResult(peerId: String, m: Map<String, Any?>) = send(peerId, m)

    private fun stateMsg(): Map<String, Any?> {
        val access = PhoneNotifListener.instance != null
        val notifs = PhoneRelaySettings.notifsEnabled && access
        val why = when {
            !PhoneRelaySettings.notifsEnabled -> "off"
            !access -> "no_access"
            else -> null
        }
        return NotifWire.state(notifs, CallWatcher.available(), why)
    }

    fun broadcastState() {
        val m = JsonMaps.toJson(stateMsg())
        Hub.peers().filter { NotifWire.CAP_NOTIF_SINK in it.caps || NotifWire.CAP_CALL_SINK in it.caps }.forEach { Hub.send(it.id, m) }
    }

    // ---- notifications in ----

    fun onPosted(sbn: StatusBarNotification, importance: Int) = worker.post { handlePosted(sbn, importance, quiet = false) }

    fun onRemoved(sbn: StatusBarNotification) = worker.post {
        val n = sbn.notification
        if (n != null && isCallNotification(n)) { CallWatcher.onCallNotification(null); return@post }
        val k = NotifActions.shortKey(sbn.key)
        heldWhileLocked.remove(sbn.key)
        gate.forget(k)
        if (active.remove(k) != null) sendToSinks(NotifWire.remove(k))
    }

    private fun isCallNotification(n: Notification): Boolean {
        if (n.category != Notification.CATEGORY_CALL) return false
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0
        return n.extras?.containsKey(EXTRA_CALL_TYPE) == true || ongoing || n.fullScreenIntent != null
    }

    private fun handlePosted(sbn: StatusBarNotification, importance: Int, quiet: Boolean) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName ?: return
        val call = isCallNotification(n) && pkg != ctx.packageName
        if (!call && !PhoneRelaySettings.notifsEnabled) return
        val extras = n.extras ?: Bundle()
        val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) } catch (_: Throwable) { null }
        val title = cs(extras, Notification.EXTRA_TITLE_BIG) ?: cs(extras, Notification.EXTRA_TITLE) ?: ""
        val text = cs(extras, Notification.EXTRA_BIG_TEXT) ?: cs(extras, Notification.EXTRA_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: ""
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val facts = NotifFilter.Facts(
            pkg = pkg,
            ownPkg = ctx.packageName,
            category = n.category,
            ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            mediaStyle = template.contains("MediaStyle") || extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            importance = importance,
            conversation = style != null,
            hasContent = title.isNotBlank() || text.isNotBlank() || style?.messages?.isNotEmpty() == true,
            isCall = call,
            visibility = n.visibility,
            muted = PhoneRelaySettings.isMuted(pkg),
        )
        val k = NotifActions.shortKey(sbn.key)
        val d = NotifFilter.decide(facts, lockContext())
        if (n.category == Notification.CATEGORY_CALL && pkg != ctx.packageName) {
            // Which dialer notifications count as the call (no caller details in the log).
            Log.i(TAG, "call-category notification from $pkg: call=$call type=${extras.getInt(EXTRA_CALL_TYPE, -1)} " +
                "fullScreen=${n.fullScreenIntent != null} ongoing=${facts.ongoing} title=${title.isNotBlank()} → ${d::class.simpleName ?: d}")
        }
        when (d) {
            NotifFilter.Decision.Call -> {
                val type = extras.getInt(EXTRA_CALL_TYPE, 0)
                val incoming = type == CALL_TYPE_INCOMING || type == CALL_TYPE_SCREENING || (type == 0 && n.fullScreenIntent != null)
                CallWatcher.onCallNotification(
                    CallWatcher.CallNotif(sbn.key, n, if (incoming) CallMachine.NotifKind.INCOMING else CallMachine.NotifKind.ONGOING, title, text),
                )
            }
            is NotifFilter.Decision.Skip -> {
                // Held until unlock: the computer keeps the version it already has.
                if (d.later) heldWhileLocked.add(sbn.key)
                // It was forwarded before and isn't forwardable any more (became ongoing, app muted…).
                else if (active.remove(k) != null) { gate.forget(k); sendToSinks(NotifWire.remove(k)) }
            }
            NotifFilter.Decision.Forward -> {
                heldWhileLocked.remove(sbn.key)
                PhoneRelaySettings.markSeen(pkg)
                val previous = active.get(k)
                val onlyAlertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
                val alert = !quiet && !(previous != null && onlyAlertOnce)
                val built = build(k, pkg, n, title, text, extras, style, alert)
                // A conversation whose newest message is the user's own (a reply just sent): update quietly.
                val pn = if (built.msgs.lastOrNull()?.from == null && built.msgs.isNotEmpty()) built.copy(alert = false) else built
                if (previous != null && previous.sent.copy(alert = pn.alert) == pn) return // same content again
                active.put(k, Entry(sbn.key, pkg, n, pn))
                if (gate.offer(pkg, k, pn, System.currentTimeMillis())) deliver(pn) else scheduleFlush()
            }
        }
    }

    private fun build(
        key: String, pkg: String, n: Notification, title: String, text: String, extras: Bundle,
        style: NotificationCompat.MessagingStyle?, alert: Boolean,
    ): NotifWire.PhoneNotif {
        val infos = actionInfos(n)
        val reply = NotifActions.pickReply(infos)
        val me = style?.user
        val msgs = style?.messages?.mapNotNull { m ->
            val body = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val p = m.person
            // The phone's user: no sender, or the style's own user (same key, or same name when there is no key).
            val isMe = p == null || (me != null && ((me.key != null && p.key == me.key) || (me.key == null && p.key == null && p.name?.toString() == me.name?.toString())))
            NotifWire.Msg(if (isMe) null else (p?.name?.toString() ?: ""), body, m.timestamp)
        } ?: emptyList()
        val convTitle = style?.conversationTitle?.toString()?.takeIf { style.isGroupConversation }
        val lastText = msgs.lastOrNull()?.text
        return NotifWire.PhoneNotif(
            key = key,
            pkg = pkg,
            appName = appLabel(pkg),
            title = title.ifBlank { convTitle ?: "" },
            text = text.ifBlank { lastText ?: "" },
            sub = cs(extras, Notification.EXTRA_SUB_TEXT) ?: "",
            ts = if (n.`when` > 0) n.`when` else System.currentTimeMillis(),
            cat = NotifWire.shortCategory(n.category, style != null),
            conv = convTitle,
            msgs = msgs,
            canReply = reply != null,
            replyLabel = reply?.label,
            actions = NotifActions.others(infos),
            alert = alert,
        )
    }

    private fun actionInfos(n: Notification): List<NotifActions.Info> {
        val out = ArrayList<NotifActions.Info>()
        val count = try { NotificationCompat.getActionCount(n) } catch (_: Throwable) { 0 }
        for (i in 0 until count) {
            val a = try { NotificationCompat.getAction(n, i) } catch (_: Throwable) { null } ?: continue
            out += NotifActions.Info(i, a.title?.toString() ?: "", a.remoteInputs?.any { it.allowFreeFormInput } == true, a.semanticAction, a.isContextual)
        }
        try {
            NotificationCompat.WearableExtender(n).actions.forEachIndexed { i, a ->
                out += NotifActions.Info(i, a.title?.toString() ?: "", a.remoteInputs?.any { it.allowFreeFormInput } == true, a.semanticAction, a.isContextual, wearable = true)
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun compatAction(n: Notification, info: NotifActions.Info): NotificationCompat.Action? = try {
        if (info.wearable) NotificationCompat.WearableExtender(n).actions.getOrNull(info.index)
        else NotificationCompat.getAction(n, info.index)
    } catch (_: Throwable) { null }

    // ---- sending ----

    private fun deliver(pn: NotifWire.PhoneNotif) {
        val sinks = notifSinks()
        if (sinks.isEmpty()) return
        val post = JsonMaps.toJson(NotifWire.post(pn))
        for (p in sinks) {
            val have = sentIcons.getOrPut(p.id) { HashSet() }
            if (pn.pkg !in have) {
                iconFor(pn.pkg)?.let { send(p.id, NotifWire.icon(pn.pkg, it)) }
                have += pn.pkg
            }
            Hub.send(p.id, post)
        }
    }

    private fun scheduleFlush() {
        worker.removeCallbacks(flush)
        gate.nextDueInMs(System.currentTimeMillis())?.let { worker.postDelayed(flush, it) }
    }

    private fun flushParked() {
        gate.due(System.currentTimeMillis()).forEach { pn -> if (active.get(pn.key) != null) deliver(pn) }
        scheduleFlush()
    }

    /** Everything currently shown, quietly, to one computer that just connected. */
    private fun resyncPeer(peerId: String) {
        send(peerId, NotifWire.reset())
        if (!PhoneRelaySettings.notifsEnabled || PhoneNotifListener.instance == null) return
        val p = Hub.readySession(peerId)?.info() ?: return
        if (!wantsNotifs(p)) return
        val have = sentIcons.getOrPut(peerId) { HashSet() }
        active.values().sortedBy { it.sent.ts }.forEach { e ->
            if (e.pkg !in have) { iconFor(e.pkg)?.let { send(peerId, NotifWire.icon(e.pkg, it)) }; have += e.pkg }
            send(peerId, NotifWire.post(e.sent.copy(alert = false)))
        }
    }

    /** Rebuild from the phone's current notifications (forwarding turned on, listener reconnected). */
    private fun resyncAll() {
        active.clear(); gate.clear(); heldWhileLocked.clear()
        sendToSinks(NotifWire.reset())
        PhoneNotifListener.instance?.snapshot()?.forEach { (sbn, imp) -> handlePosted(sbn, imp, quiet = true) }
    }

    private fun clearAll(sendRemovals: Boolean) {
        val had = active.size > 0
        active.clear(); gate.clear(); heldWhileLocked.clear()
        worker.removeCallbacks(flush)
        if (sendRemovals && had) sendToSinks(NotifWire.reset())
    }

    // ---- commands from the computer ----

    private fun handle(peerId: String, cmd: NotifWire.Command) {
        val peer = Hub.readySession(peerId)?.info() ?: return
        when (cmd) {
            is NotifWire.Command.Subscribe -> {
                val before = subs[peerId]
                subs[peerId] = cmd
                if (cmd.notifs && before?.notifs == false && NotifWire.CAP_NOTIF_SINK in peer.caps) resyncPeer(peerId)
                if (cmd.calls && before?.calls == false) CallWatcher.current()?.let {
                    CallWatcher.currentPhoto()?.let { m -> send(peerId, m) }
                    send(peerId, NotifWire.call(it))
                }
                if (cmd.calls != (before?.calls ?: true)) BeaconAdvertiser.refresh()
            }
            is NotifWire.Command.CallAction -> if (NotifWire.CAP_CALL_SINK in peer.caps) CallWatcher.act(peerId, cmd.id, cmd.op, cmd.dev)
            is NotifWire.Command.Ring -> if (NotifWire.CAP_CALL_RING in peer.caps) CallWatcher.onRing(cmd.id, cmd.silencePhone)
            is NotifWire.Command.Dismiss -> withEntry(peerId, peer, cmd.key, "dismiss") { e ->
                val ok = PhoneNotifListener.instance?.dismiss(e.sbnKey) == true
                if (ok) { active.remove(cmd.key); gate.forget(cmd.key); sendToSinksExcept(peerId, NotifWire.remove(cmd.key)) }
                ok to (if (ok) null else "failed")
            }
            is NotifWire.Command.Reply -> withEntry(peerId, peer, cmd.key, "reply") { e -> reply(e, cmd.text) }
            is NotifWire.Command.Act -> withEntry(peerId, peer, cmd.key, "action") { e -> action(e, cmd.index) }
            is NotifWire.Command.Open -> withEntry(peerId, peer, cmd.key, "open") { e -> open(e) }
        }
    }

    private inline fun withEntry(
        peerId: String, peer: PeerInfo, key: String, op: String, block: (Entry) -> Pair<Boolean, String?>,
    ) {
        if (NotifWire.CAP_NOTIF_SINK !in peer.caps) return
        if (!PhoneRelaySettings.notifsEnabled) { send(peerId, NotifWire.result(key, op, false, "off")); return }
        val e = active.get(key)
        if (e == null) { send(peerId, NotifWire.result(key, op, false, "gone")); return }
        val (ok, why) = try { block(e) } catch (t: Throwable) { Log.w(TAG, op, t); false to "failed" }
        send(peerId, NotifWire.result(key, op, ok, why))
    }

    private fun sendToSinksExcept(peerId: String, m: Map<String, Any?>) {
        val json = JsonMaps.toJson(m)
        notifSinks().filter { it.id != peerId }.forEach { Hub.send(it.id, json) }
        send(peerId, m)
    }

    /** The standard way: fill the reply action's RemoteInput and fire its PendingIntent. */
    private fun reply(e: Entry, text: String): Pair<Boolean, String?> {
        val info = NotifActions.pickReply(actionInfos(e.notification)) ?: return false to "no_reply"
        val a = compatAction(e.notification, info) ?: return false to "no_reply"
        val inputs = a.remoteInputs ?: return false to "no_reply"
        val intent = Intent()
        val results = Bundle()
        inputs.forEach { if (it.allowFreeFormInput) results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val pi = a.actionIntent ?: return false to "no_reply"
        return if (sendPi(pi, intent)) true to null else false to "failed"
    }

    private fun action(e: Entry, index: Int): Pair<Boolean, String?> {
        val a = try { NotificationCompat.getAction(e.notification, index) } catch (_: Throwable) { null }
        val pi = a?.actionIntent ?: return false to "no_action"
        return if (sendPi(pi, null)) true to null else false to "failed"
    }

    private fun open(e: Entry): Pair<Boolean, String?> {
        e.notification.contentIntent?.let { return if (sendPi(it, null)) true to null else false to "failed" }
        val launch = ctx.packageManager.getLaunchIntentForPackage(e.pkg) ?: return false to "no_action"
        return try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true to null
        } catch (t: Throwable) { false to "failed" }
    }

    /** Fires a PendingIntent of another app; activity starts are allowed where Android lets us (API 34+ opt-in). */
    @Suppress("DEPRECATION")
    fun sendPi(pi: PendingIntent, fill: Intent?): Boolean = try {
        val opts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        pi.send(ctx, 0, fill, null, null, null, opts)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "PendingIntent failed", t); false
    }

    // ---- device state ----

    private fun lockContext(): NotifFilter.Context {
        val locked = try { ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Throwable) { false }
        return NotifFilter.Context(locked, if (locked) hideSensitive() else null)
    }

    /**
     * The lock-screen privacy choice: "Hide sensitive content" (`lock_screen_allow_private_notifications` = 0) or
     * no notifications on the lock screen at all (`lock_screen_show_notifications` = 0). null when the ROM
     * doesn't let us read it; then only notifications marked secret are held back.
     */
    private fun hideSensitive(): Boolean? = try {
        val cr = ctx.contentResolver
        val showAny = Settings.Secure.getInt(cr, "lock_screen_show_notifications", 1)
        val showPrivate = Settings.Secure.getInt(cr, "lock_screen_allow_private_notifications", 1)
        showAny == 0 || showPrivate == 0
    } catch (_: Throwable) { null }

    private fun registerUnlock() {
        if (unlockReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                worker.post {
                    if (heldWhileLocked.isEmpty() || !PhoneRelaySettings.notifsEnabled) return@post
                    val held = HashSet(heldWhileLocked)
                    heldWhileLocked.clear()
                    PhoneNotifListener.instance?.snapshot()?.filter { it.first.key in held }?.forEach { (sbn, imp) ->
                        handlePosted(sbn, imp, quiet = true)
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            unlockReceiver = r
        } catch (t: Throwable) { Log.w(TAG, "unlock receiver", t) }
    }

    // ---- app labels & icons ----

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
    }

    /** The app's launcher icon as a small PNG (base64), cached per app. */
    private fun iconFor(pkg: String): String? {
        iconCache[pkg]?.let { return it }
        return try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, ICON_PX, ICON_PX)
            d.draw(c)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).also { iconCache[pkg] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "icon for $pkg", t); null
        }
    }

    private fun cs(b: Bundle, k: String): String? = b.getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() }

    /** `Notification.EXTRA_CALL_TYPE` and its values (API 31; read by name so older SDKs need nothing). */
    const val EXTRA_CALL_TYPE = "android.callType"
    const val CALL_TYPE_INCOMING = 1
    const val CALL_TYPE_ONGOING = 2
    const val CALL_TYPE_SCREENING = 3
}
