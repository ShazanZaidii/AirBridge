package com.shazan.airbridge.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.shazan.airbridge.net.link.LinkManager
import com.shazan.airbridge.net.link.WfdJoiner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class PeerStatus {
    CONNECTED, CONNECTING, RECONNECTING, OFFLINE,
    /** The user (or the peer, with `bye user`) disconnected it; no redial until Connect. */
    DISCONNECTED,
}

/** Where an interactive (LAN) pairing attempt is, for the pairing sheet. */
enum class PairStage { CONNECTING, CONFIRM, WAITING_PEER }

data class PairProgress(
    val peerName: String,
    val plat: String,
    /** "qr" or "confirm". */
    val mode: String,
    val stage: PairStage,
    /** Mode `confirm`: the 6 digits both screens must show. */
    val digits: String? = null,
    /** Where the other device is, e.g. "10.145.158.115" (shown so it is clear who is asking). */
    val address: String? = null,
    /** True when the other device dialled this one (this phone is the host). */
    val incoming: Boolean = false,
)

/** "Android phone", "Mac", "Linux computer"… for pairing prompts. */
fun deviceKind(plat: String): String = Hub.app.getString(when (plat) {
    "android" -> com.shazan.airbridge.R.string.kind_android; "ios" -> com.shazan.airbridge.R.string.plat_ios
    "macos" -> com.shazan.airbridge.R.string.plat_mac; "windows" -> com.shazan.airbridge.R.string.plat_windows
    "linux" -> com.shazan.airbridge.R.string.kind_linux; else -> com.shazan.airbridge.R.string.kind_unknown
})

private fun addressOf(s: Session): String = s.link.label.substringBefore(" (").substringBeforeLast(':')

/** One-shot UI notifications from the network layer. */
sealed class NetEvent {
    data class PairResult(val ok: Boolean, val peerName: String, val reason: String = "") : NetEvent()
    data class PairAgain(val peerName: String) : NetEvent()
}

/** Progress of one transfer, for the UI. */
data class TransferUi(
    val key: String,
    val incoming: Boolean,
    val peerName: String,
    val fileName: String,
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSec: Long,
    /** null while running; then "Sent"/"Received" or a failure text. */
    val result: String? = null,
    /** What a finished incoming transfer saved: the card opens it when tapped. */
    val received: ReceivedFiles? = null,
)

/**
 * Registry of live sessions; implements [PeerBus.Transport] and routes ready-session messages to
 * the input half, clipboard and file transfer. Process singleton, initialised by [init].
 * Discovery/dialing are only active between [start] and [stop] (the foreground service lifetime).
 */
object Hub : PeerBus.Transport {
    private const val TAG = "AirBridge.Hub"

    lateinit var app: Context
        private set
    val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "airbridge-net").apply { isDaemon = true }
    }
    val main = Handler(Looper.getMainLooper())

    private val sessions = CopyOnWriteArraySet<Session>()
    private val ready = ConcurrentHashMap<String, Session>()

    private val _status = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    val status: StateFlow<Map<String, PeerStatus>> = _status
    private val _events = MutableSharedFlow<NetEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NetEvent> = _events
    private val _transfers = MutableStateFlow<Map<String, TransferUi>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferUi>> = _transfers
    private val _pairing = MutableStateFlow<PairProgress?>(null)
    /** The interactive pairing in progress (QR or confirm), or null. */
    val pairing: StateFlow<PairProgress?> = _pairing
    @Volatile private var pairingSession: Session? = null
    private val _controlledBy = MutableStateFlow<String?>(null)
    /** Peer id that most recently sent pointer/key/action input to this phone (for "Controlling you"). */
    val lastInputPeer: StateFlow<String?> = _controlledBy

    @Volatile var running = false
        private set
    @Volatile private var accessibilityToastShown = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var netDebounce: ScheduledFuture<*>? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        scheduler.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            sessions.forEach { s -> try { s.tick(now) } catch (e: Exception) { Log.w(TAG, "tick", e) } }
        }, 1, 1, TimeUnit.SECONDS)
        PeerBus.transport = this
        (app as? Application)?.let { WfdJoiner.Foreground.install(it) }
    }

    // ---- service lifetime ----

    @Synchronized
    fun start() {
        if (running) return
        running = true
        Discovery.acquire()
        LanHost.acquire() // other phones on this Wi-Fi can find and reach this one
        registerNetworkCallback()
        WsClient.start()
        NearbyTransport.onServiceStart()
        recomputeStatus()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        unregisterNetworkCallback()
        LinkManager.stopAll() // releases Wi-Fi Direct network requests
        WsClient.stop()
        NearbyTransport.onServiceStop()
        LanHost.release()
        Discovery.release()
        sessions.forEach { it.close(Outcome.Ended("shutdown"), "shutdown") }
        recomputeStatus()
    }

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val registeredAt = System.currentTimeMillis()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
            private fun changed() {
                if (System.currentTimeMillis() - registeredAt < 2000) return // initial callbacks
                netDebounce?.cancel(false)
                netDebounce = scheduler.schedule({
                    Log.i(TAG, "network changed: re-resolving")
                    Discovery.restart()
                    WsClient.retryAllNow(networkChanged = true)
                }, 1500, TimeUnit.MILLISECONDS)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { Log.w(TAG, "network callback", e) }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        try { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    // ---- session callbacks ----

    fun register(s: Session) { sessions.add(s) }

    fun onReady(s: Session) {
        val id = s.peerId ?: return
        val old = ready.put(id, s)
        if (s.isClosed) { ready.remove(id, s); return } // finished between becomeReady and here
        if (old != null && old !== s) old.close(Outcome.Ended("replaced"), "replaced") // §3.4
        PairStore.updateMeta(id, name = s.peerName, plat = s.peerPlat)
        if (pairingSession === s) { pairingSession = null; _pairing.value = null; Notifs.cancel(NOTIF_INCOMING_PAIR) }
        if (s.isPairing) _events.tryEmit(NetEvent.PairResult(true, s.peerName))
        peersChanged()
        // ctl_target / type_target flipped during this handshake: its hello is already stale.
        if (s.advertisedDynamic.let { it != null && it != Protocol.dynamicCaps() }) CapsRefresh.poke()
        LinkManager.onSessionReady(s) // after the old session was retired: a held xfer_offer goes on [s]
        com.shazan.airbridge.phone.PhoneRelay.onPeerReady(id) // §13: notification state, resync, current call
        com.shazan.airbridge.phone.CallAudioInfo.onPeerReady(id) // §13.4: Bluetooth facts for call audio
    }

    fun onClosed(s: Session, outcome: Outcome) {
        sessions.remove(s)
        if (pairingSession === s) { pairingSession = null; _pairing.value = null; Notifs.cancel(NOTIF_INCOMING_PAIR) }
        val id = s.peerId
        val wasReady = id != null && ready.remove(id, s)
        when (outcome) {
            is Outcome.Unpaired -> if (id != null && PairStore.get(id) != null) {
                val name = PairStore.get(id)?.name ?: s.peerName
                PairStore.remove(id)
                _events.tryEmit(NetEvent.PairAgain(name))
            }
            is Outcome.PairFailed -> if (s.isPairing) _events.tryEmit(NetEvent.PairResult(false, s.peerName, outcome.reason))
            else -> if (s.isPairing && !s.everReady) {
                val why = when (outcome) {
                    is Outcome.Lost -> outcome.why
                    is Outcome.Ended -> outcome.why
                    Outcome.WrongPeer -> "different device at that address"
                    else -> ""
                }
                _events.tryEmit(NetEvent.PairResult(false, s.peerName, why))
            }
        }
        if (wasReady && id != null) com.shazan.airbridge.mirror.PhoneMirror.onPeerGone(id)
        if (wasReady && id != null) com.shazan.airbridge.phone.PhoneRelay.onPeerGone(id)
        if (wasReady && id != null) com.shazan.airbridge.phone.CallAudioInfo.onPeerGone(id)
        if (wasReady) peersChanged() else recomputeStatus()
    }

    // ---- interactive pairing (§3.3) ----

    /** True while an interactive pairing is in flight (keeps the service alive before the first peer is stored). */
    fun isPairing(): Boolean = pairingSession?.isClosed == false

    /**
     * Any connection still open or being set up, paired or not: an incoming pairing that hasn't shown its
     * code yet, a Nearby pairing (never [pairingSession]). Stopping the service closes every session, so it
     * must not stop while this is true.
     */
    fun hasLiveSessions(): Boolean = sessions.any { !it.isClosed }

    /** A LAN pairing attempt was dialled; [s] becomes the one the pairing sheet follows. */
    fun beginPairing(s: Session, name: String, plat: String, mode: String) {
        pairingSession?.takeIf { it !== s }?.close(Outcome.Ended("superseded"), "user")
        if (s.isClosed) return
        pairingSession = s
        _pairing.value = PairProgress(name, plat, mode, PairStage.CONNECTING, address = addressOf(s))
        if (s.confirmDigits != null) onPairPrompt(s) // digits arrived before we got here
    }

    /**
     * LAN host: another phone dialled us to pair (§3.3 confirm). Show the same 6 digits it shows, in the
     * pairing sheet and as a notification (the app may be in the background).
     */
    fun onIncomingPairing(s: Session) {
        if (s.isClosed) return
        pairingSession?.takeIf { it !== s && !it.isClosed }?.let { // one pairing at a time
            s.close(Outcome.PairFailed("busy"), null)
            return
        }
        pairingSession = s
        val addr = addressOf(s)
        _pairing.value = PairProgress(s.peerName.ifBlank { app.getString(com.shazan.airbridge.R.string.unnamed_device) }, s.peerPlat, "confirm", PairStage.CONFIRM, s.confirmDigits,
            address = addr, incoming = true)
        val who = app.getString(com.shazan.airbridge.R.string.hub_who, s.peerName.ifBlank { app.getString(com.shazan.airbridge.R.string.unnamed_device) }, deviceKind(s.peerPlat), addr)
        val n = androidx.core.app.NotificationCompat.Builder(app, Notifs.CH_PAIR)
            .setSmallIcon(com.shazan.airbridge.R.drawable.ic_stat_name)
            .setContentTitle(app.getString(com.shazan.airbridge.R.string.hub_wants_pair, who))
            .setContentText(app.getString(com.shazan.airbridge.R.string.hub_only_accept, s.confirmDigits ?: app.getString(com.shazan.airbridge.R.string.hub_same_code)))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(Protocol.PAIR_TIMEOUT_MS)
            .setContentIntent(Notifs.openAppIntent(app))
            .build()
        Notifs.notify(NOTIF_INCOMING_PAIR, n)
    }

    private const val NOTIF_INCOMING_PAIR = 4711

    /** Session callback: digits are known, or the user's confirmation went out. */
    fun onPairPrompt(s: Session) {
        if (pairingSession !== s) return
        val cur = _pairing.value ?: return
        _pairing.value = cur.copy(
            peerName = s.peerName.ifBlank { cur.peerName },
            plat = s.peerPlat.ifBlank { cur.plat },
            stage = if (s.userConfirmed) PairStage.WAITING_PEER else PairStage.CONFIRM,
            digits = s.confirmDigits,
        )
    }

    /** User tapped Confirm (true) or Cancel (false) on the digits. */
    fun confirmPairing(accept: Boolean) {
        val s = pairingSession
        if (s == null) { _pairing.value = null; return }
        s.userConfirm(accept)
    }

    /** User dismissed the pairing sheet at any stage. */
    fun cancelPairing() {
        val s = pairingSession
        pairingSession = null
        _pairing.value = null
        s?.close(Outcome.PairFailed("cancelled"), "user")
    }

    fun onPeerRenamed(s: Session) {
        s.peerId?.let { PairStore.updateMeta(it, name = s.peerName) }
        peersChanged()
    }

    private fun peersChanged() {
        recomputeStatus()
        val list = peers()
        try { PeerBus.notifyPeers(list) } catch (e: Exception) { Log.w(TAG, "peersHandler", e) }
    }

    fun recomputeStatus() {
        _status.value = PairStore.all().associate { p ->
            p.id to when {
                ready.containsKey(p.id) -> PeerStatus.CONNECTED
                p.via == "nearby" -> NearbyTransport.statusOf(p.id)
                else -> WsClient.statusOf(p.id)
            }
        }
    }

    /** Input that takes a control lease on a target (§8.6), as opposed to state/mirror/busy messages. */
    private val LEASE_INPUT = setOf("ctl_enter", "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click", "ptr_abs", "key", "action")

    /**
     * Ready-session messages not handled by [Session] itself. Clipboard and file transfer stay here;
     * every control type — and any type this half doesn't know (future input/mirror messages) —
     * goes to the input half, which ignores what it doesn't understand (rule 1).
     */
    fun route(s: Session, t: String, m: JSONObject) {
        val id = s.peerId ?: return
        when {
            t == "clip" -> ClipboardSync.onRemote(s, m)
            t.startsWith("xfer_") -> s.xfer.onControl(t, m)
            // §10.1 link upgrades are network-half business, never routed to the input half.
            t.startsWith("link_") -> LinkManager.onMessage(s, t, m)
            // §13 phone notifications and calls: network-half business too, never the input half.
            t.startsWith("call_audio_") -> com.shazan.airbridge.phone.CallAudioInfo.onMessage(id, m)
            t.startsWith("notif_") || t.startsWith("call_") -> com.shazan.airbridge.phone.PhoneRelay.onMessage(id, m)
            // §8.9 Pro mirroring helper: report / open Wireless debugging. Network half, no input lease.
            t == "adb_wifi" -> com.shazan.airbridge.promirror.AdbWifi.onMessage(s, m)
            // §8.7 phone as source: works without the accessibility service (view-only), so it never
            // depends on the input half being installed.
            t.startsWith("mirror_") && com.shazan.airbridge.mirror.PhoneMirror.onControl(id, m) -> Unit
            else -> {
                if (t in LEASE_INPUT && _controlledBy.value != id) _controlledBy.value = id
                if (PeerBus.controlHandler == null) {
                    // §8.1: never leave a controller waiting for a ctl_ack we can't give.
                    if (t == "ctl_enter") s.send(Protocol.withReason("ctl_leave", "unavailable"))
                    if (t in PeerBus.CONTROL_TYPES && !accessibilityToastShown) {
                        accessibilityToastShown = true
                        toast(app.getString(com.shazan.airbridge.R.string.hub_turn_on_control, app.getString(com.shazan.airbridge.R.string.svc_core_name)), long = true)
                    }
                } else {
                    try { PeerBus.dispatchControl(id, m) } catch (e: Exception) { Log.w(TAG, "controlHandler", e) }
                }
            }
        }
    }

    // ---- PeerBus.Transport ----

    override fun send(peerId: String, msg: JSONObject): Boolean = ready[peerId]?.send(msg) ?: false
    override fun broadcast(msg: JSONObject) {
        normalizeNumbers(msg) // once, before fan-out across threads
        ready.values.forEach { it.send(msg) }
    }
    override fun sendBinary(peerId: String, frame: ByteArray): Boolean = ready[peerId]?.sendBinary(frame) ?: false
    override fun peers(): List<PeerInfo> = ready.values.map { it.info() }

    fun readySession(peerId: String): Session? = ready[peerId]
    fun readySessions(): Collection<Session> = ready.values

    // ---- user actions ----

    /**
     * The user tapped Disconnect, whichever way the device is reached. A Wi-Fi device we dial is handled by
     * [WsClient]; one that dialled us (we are its host) or a Nearby one is closed here, with `bye user` so it
     * does not come straight back, and stays that way until Connect.
     */
    fun disconnect(peerId: String) {
        val p = PairStore.get(peerId) ?: return
        if (p.via == "lan") { WsClient.disconnect(peerId); return }
        PairStore.setUserPause(peerId, DisconnectBy.SELF)
        val o = Outcome.Ended("user disconnect")
        ready[peerId]?.close(o, "user")
        sessions.filter { it.peerId == peerId }.forEach { it.close(o, "user") }
        NearbyTransport.dropEndpoints(peerId)
        recomputeStatus()
    }

    /**
     * The user tapped Connect. A device we dial is dialled now; a Nearby one is asked again; one that dials us
     * (this phone is its host) can only be let back in: the lift here means it is welcome, but the other device
     * has to reconnect. Returns false for that last case so the screen can say so.
     */
    fun connect(peerId: String): Boolean {
        val p = PairStore.get(peerId) ?: return false
        return when (p.via) {
            "lan" -> { if (!WsClient.connect(peerId)) com.shazan.airbridge.AirBridgeService.start(app); true }
            "nearby" -> { PairStore.setUserPause(peerId, null); NearbyTransport.reconnect(peerId); recomputeStatus(); true }
            else -> { PairStore.setUserPause(peerId, null); recomputeStatus(); false }
        }
    }

    /** Forget a device: send `unpair` if connected, then delete locally (§3.5). */
    fun forget(peerId: String) {
        ready[peerId]?.let { s ->
            s.send(Protocol.msg("unpair"))
            s.close(Outcome.Ended("forgotten"), null)
        }
        sessions.filter { it.peerId == peerId }.forEach { it.close(Outcome.Ended("forgotten"), null) }
        WsClient.forget(peerId)
        NearbyTransport.forget(peerId)
        PairStore.remove(peerId)
        recomputeStatus()
    }

    fun renameSelf(name: String) {
        if (Identity.setName(name)) broadcastName()
    }

    /** Tell every ready peer our current name (§7 `name`). */
    fun broadcastName() {
        broadcast(Protocol.msg("name").put("name", Identity.name))
        NearbyTransport.onNameChanged()
    }

    // ---- transfer UI ----

    fun putTransfer(t: TransferUi) {
        _transfers.value = _transfers.value + (t.key to t)
        // A finished card stays a while; one with files to open long enough to tap it.
        if (t.result != null) scheduler.schedule({ _transfers.value = _transfers.value - t.key }, if (t.received != null) 20L else 5L, TimeUnit.SECONDS)
    }

    fun removeTransfer(key: String) {
        if (_transfers.value.containsKey(key)) _transfers.value = _transfers.value - key
    }

    fun toast(msg: String, long: Boolean = false) {
        main.post { Toast.makeText(app, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}
