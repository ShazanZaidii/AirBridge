package com.shazan.airbridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.NearbyTransport
import com.shazan.airbridge.net.Notifs
import com.shazan.airbridge.net.PairStore
import com.shazan.airbridge.net.PairedPeer
import com.shazan.airbridge.net.PeerStatus
import com.shazan.airbridge.net.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps sessions alive. All protocol logic
 * lives in the `net` package; this class only owns lifetime, the status notification, the
 * clipboard bubble and Direct Share shortcuts.
 */
class AirBridgeService : Service() {

    companion object {
        private const val TAG = "AirBridge.Service"
        const val ACTION_STOP = "com.shazan.airbridge.action.STOP"
        const val ACTION_CANCEL_XFER = "com.shazan.airbridge.action.CANCEL_XFER"
        const val ACTION_SEND_FILES = "com.shazan.airbridge.action.SEND_FILES"
        const val ACTION_REFRESH_BUBBLE = "com.shazan.airbridge.action.REFRESH_BUBBLE"
        const val EXTRA_PEER = "peer"
        /** [ACTION_SEND_FILES]: every device to send the same files to (String array). */
        const val EXTRA_PEERS = "peers"

        @Volatile var isRunning = false
            private set

        /** Run while anything is paired or the phone is visible to nearby phones. */
        fun shouldRun(ctx: Context): Boolean =
            !PairStore.isEmpty() || Hub.isPairing() ||
                ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).getBoolean(NearbyTransport.PREF_VISIBLE, false)

        fun start(ctx: Context, action: String? = null) {
            try {
                val i = Intent(ctx, AirBridgeService::class.java).setAction(action)
                if (isRunning) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "cannot start service", e)
            }
        }

        fun stopIfIdle(ctx: Context) {
            if (!shouldRun(ctx)) ctx.stopService(Intent(ctx, AirBridgeService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bubble: FloatingBubble? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        if (!enterForeground(buildNotification(emptyList(), emptyMap()))) {
            stopSelf()
            return
        }
        isRunning = true
        Hub.start()
        ClipboardSync.startListening()
        bubble = FloatingBubble(this)
        BubbleController.onChange = { refreshBubble() }
        refreshBubble()
        scope.launch { ClipboardSync.pending.collect { bubble?.setPending(it) } }

        scope.launch {
            combine(PairStore.peers, Hub.status) { p, s -> p to s }.collect { (peers, status) ->
                Notifs.notify(Notifs.ID_SERVICE, buildNotification(peers, status))
            }
        }
        scope.launch {
            PairStore.peers.map { list -> list.map { Triple(it.id, it.name, it.plat) } }
                .distinctUntilChanged()
                .collect {
                    DirectShare.publish(this@AirBridgeService, PairStore.all())
                    NearbyTransport.refresh() // Nearby-paired peers keep discovery on
                }
        }
    }

    private fun enterForeground(n: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notifs.ID_SERVICE, n, type)
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) return START_NOT_STICKY
        // Every startForegroundService() must be answered with startForeground().
        enterForeground(buildNotification(PairStore.all(), Hub.status.value))
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CANCEL_XFER -> {
                val peer = intent.getStringExtra(EXTRA_PEER)
                if (peer != null) Hub.readySession(peer)?.xfer?.cancelLocal(intent.getIntExtra("id", -1), intent.getBooleanExtra("incoming", false))
            }
            ACTION_SEND_FILES -> {
                val peers = intent.getStringArrayExtra(EXTRA_PEERS)?.toList()
                    ?: listOfNotNull(intent.getStringExtra(EXTRA_PEER))
                val clip = intent.clipData
                val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
                // Each device gets its own transfer; they run side by side.
                val gone = peers.filter { id ->
                    val session = Hub.readySession(id)
                    session?.xfer?.send(uris)
                    session == null
                }
                if (gone.isNotEmpty()) {
                    val names = gone.map { id -> PairStore.all().firstOrNull { it.id == id }?.name ?: "Device" }
                    Hub.toast("${names.joinToString(", ")} ${if (names.size == 1) "is" else "are"} not connected")
                }
            }
            ACTION_REFRESH_BUBBLE -> refreshBubble()
            else -> {
                if (!shouldRun(this)) { stopSelf(); return START_NOT_STICKY }
                // Null intent = START_STICKY restart after the system killed us: copies made meanwhile were missed.
                if (intent == null) ClipboardSync.markPending("service restarted")
                WsClient.retryAllNow(unpause = true)
            }
        }
        return START_STICKY
    }

    /** Show the bubble only if the user wants it and nothing suppresses it (lease, trackpad, mirror — §5). */
    private fun refreshBubble() {
        val b = bubble ?: return
        val enabled = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).getBoolean(FloatingBubble.PREF_ENABLED, true)
        if (enabled && !BubbleController.isSuppressed && Settings.canDrawOverlays(this)) b.show() else b.hide()
    }

    private fun buildNotification(peers: List<PairedPeer>, status: Map<String, PeerStatus>): Notification {
        val connected = peers.filter { status[it.id] == PeerStatus.CONNECTED }.map { it.name }
        val (title, text) = when {
            connected.isNotEmpty() -> "Connected" to connected.joinToString(", ")
            peers.isNotEmpty() -> "Waiting for devices" to "${peers.size} paired, none reachable right now"
            else -> "Visible to nearby phones" to "Open AirBridge to pair"
        }
        val push = PendingIntent.getActivity(
            this, 1,
            Intent(this, SyncActivity::class.java).putExtra(ClipboardSync.EXTRA_REASON, "manual")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifs.openAppIntent(this))
            .apply { if (connected.isNotEmpty()) addAction(0, "Send clipboard", push) }
            .build()
    }

    /** Android 15+ FGS timeout; connectedDevice has none today, but never get killed for ignoring it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout (type $fgsType)")
        stopSelf()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        scope.cancel()
        BubbleController.onChange = null
        bubble?.hide()
        bubble = null
        if (isRunning) {
            ClipboardSync.stopListening()
            Hub.stop()
        }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
