package com.shazan.airbridge.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.shazan.airbridge.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Android↔Android over Google Nearby Connections carrying the same v2 messages (§10).
 *
 * BYTES payloads are capped at 32 KiB, so each direction uses ONE long-lived STREAM payload as an
 * ordered byte channel framed as `u8 kind | u32 len | payload`. The requester is the protocol
 * client, the advertiser that accepted is the host.
 *
 * Only endpoints whose advertised id is already paired are auto-accepted. Anything else is shown
 * to the user with Nearby's authentication digits; once confirmed on a side, those digits are the
 * pairing secret S for that side.
 */
object NearbyTransport {
    private const val TAG = "AirBridge.Nearby"
    const val SERVICE_ID = "com.shazan.airbridge.p2p"
    const val PREF_VISIBLE = "P2P_VISIBLE"
    private val STRATEGY = Strategy.P2P_CLUSTER

    data class Found(val endpointId: String, val name: String, val peerId: String?)
    data class Pending(val endpointId: String, val name: String, val digits: String, val incoming: Boolean)

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending

    private class Endpoint(val id: String, val name: String, val peerIdHint: String?, val digits: String, val incoming: Boolean) {
        @Volatile var confirmed = false
        @Volatile var link: NearbyLink? = null
        /** Peer's stream that arrived before our onConnectionResult ran. */
        @Volatile var earlyStream: Pair<InputStream, Long>? = null
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(Hub.app) }
    private var serviceActive = false
    private var uiRefs = 0
    private var advertising = false
    private var discovering = false

    private val visiblePref: Boolean
        get() = Hub.app.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).getBoolean(PREF_VISIBLE, false)

    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    fun hasPermissions(ctx: Context = Hub.app): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- lifecycle (service + discovery screen) ----

    @Synchronized fun onServiceStart() { serviceActive = true; refresh() }

    @Synchronized fun onServiceStop() {
        serviceActive = false
        refresh()
        if (uiRefs == 0) {
            endpoints.values.forEach { it.link?.session?.close(Outcome.Ended("shutdown"), "shutdown") }
            try { client.stopAllEndpoints() } catch (_: Exception) {}
            endpoints.clear()
            _pending.value = emptyList()
        }
    }

    @Synchronized fun acquireUi() { uiRefs++; refresh() }
    @Synchronized fun releaseUi() { if (uiRefs > 0) uiRefs--; refresh() }

    @Synchronized fun setVisible(visible: Boolean) {
        Hub.app.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_VISIBLE, visible).apply()
        refresh()
    }

    @Synchronized fun onNameChanged() {
        if (advertising) { stopAdvertising(); refresh() }
    }

    /**
     * Advertise + discover while the discovery screen is open, or while the service runs and the
     * phone is visible or has a Nearby-paired peer (so paired phones find each other again).
     */
    @Synchronized fun refresh() {
        val pairedNearby = PairStore.all().any { it.via == "nearby" }
        val want = (serviceActive && (visiblePref || pairedNearby) || uiRefs > 0) && hasPermissions()
        if (want) { startAdvertising(); startDiscovery() }
        else { stopAdvertising(); stopDiscovery() }
    }

    private fun endpointName(): String {
        // "<id>|<name>" so peers can auto-accept paired ids before any protocol traffic.
        var name = Identity.name
        while ((Identity.id + "|" + name).toByteArray().size > 120 && name.isNotEmpty()) name = name.dropLast(1)
        return Identity.id + "|" + name
    }

    private fun parseName(raw: String): Pair<String?, String> {
        val id = raw.substringBefore('|', "")
        return if (id.length == 36 && id.count { it == '-' } == 4) id to raw.substringAfter('|')
        else null to raw
    }

    private fun startAdvertising() {
        if (advertising) return
        advertising = true
        client.startAdvertising(endpointName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { e ->
                Log.w(TAG, "advertising failed: ${e.message}")
                synchronized(this) { advertising = false }
            }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try { client.stopAdvertising() } catch (_: Exception) {}
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        client.startDiscovery(SERVICE_ID, discoveryCb, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { e ->
                Log.w(TAG, "discovery failed: ${e.message}")
                synchronized(this) { discovering = false }
            }
    }

    private fun stopDiscovery() {
        if (discovering) { discovering = false; try { client.stopDiscovery() } catch (_: Exception) {} }
        _found.value = emptyList()
    }

    // ---- UI actions ----

    fun requestConnection(endpointId: String) {
        client.requestConnection(endpointName(), endpointId, lifecycle)
            .addOnFailureListener { e ->
                Log.w(TAG, "requestConnection failed: ${e.message}")
                _found.value.firstOrNull { it.endpointId == endpointId }?.peerId?.let { scheduleRetry(it) }
            }
    }

    /** User confirmed (or rejected) the digits for a pending endpoint. */
    fun confirm(endpointId: String, accept: Boolean) {
        _pending.value = _pending.value.filterNot { it.endpointId == endpointId }
        Notifs.cancel(Notifs.ID_NEARBY_REQUEST)
        val ep = endpoints[endpointId] ?: return
        if (accept) {
            ep.confirmed = true
            client.acceptConnection(endpointId, payloadCb)
        } else {
            endpoints.remove(endpointId)
            client.rejectConnection(endpointId)
        }
    }

    fun forget(peerId: String) {
        endpoints.values.filter { it.link?.session?.peerId == peerId || it.peerIdHint == peerId }.forEach {
            try { client.disconnectFromEndpoint(it.id) } catch (_: Exception) {}
        }
    }

    fun statusOf(peerId: String): PeerStatus = when {
        endpoints.values.any { it.link?.session?.peerId == peerId || it.peerIdHint == peerId } -> PeerStatus.CONNECTING
        discovering && _found.value.any { it.peerId == peerId } -> PeerStatus.RECONNECTING
        else -> PeerStatus.OFFLINE
    }

    // ---- Nearby callbacks ----

    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val (hint, name) = parseName(info.endpointName)
            _found.value = _found.value.filterNot { it.endpointId == endpointId } + Found(endpointId, name, hint)
            maybeAutoConnect(endpointId, hint)
        }

        override fun onEndpointLost(endpointId: String) {
            _found.value = _found.value.filterNot { it.endpointId == endpointId }
        }
    }

    private fun maybeAutoConnect(endpointId: String, hint: String?) {
        if (hint == null || !serviceActive || PairStore.get(hint)?.via != "nearby") return
        if (Hub.readySession(hint) != null || endpoints.values.any { it.peerIdHint == hint }) return
        // Tie-break so two paired phones don't dial each other at the same time.
        if (Identity.id < hint) requestConnection(endpointId)
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (hint, name) = parseName(info.endpointName)
            @Suppress("DEPRECATION") val digits = info.authenticationDigits ?: info.authenticationToken ?: ""
            val ep = Endpoint(endpointId, name, hint, digits, info.isIncomingConnection)
            endpoints[endpointId] = ep
            if (hint != null && PairStore.key(hint) != null) {
                client.acceptConnection(endpointId, payloadCb)
                return
            }
            _pending.value = _pending.value.filterNot { it.endpointId == endpointId } +
                Pending(endpointId, name, digits, info.isIncomingConnection)
            if (uiRefs == 0) showRequestNotification(name)
            Hub.scheduler.schedule({
                if (_pending.value.any { it.endpointId == endpointId }) confirm(endpointId, false)
            }, 60, TimeUnit.SECONDS)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            _pending.value = _pending.value.filterNot { it.endpointId == endpointId }
            val ep = endpoints[endpointId] ?: return
            if (!result.status.isSuccess) {
                endpoints.remove(endpointId)
                Log.i(TAG, "connection to ${ep.name} failed: ${result.status.statusCode}")
                ep.peerIdHint?.let { scheduleRetry(it) }
                return
            }
            open(ep)
        }

        override fun onDisconnected(endpointId: String) {
            endpoints.remove(endpointId)?.link?.transportClosed("nearby disconnected")
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val ep = endpoints[endpointId]
            val stream = payload.asStream()
            if (payload.type == Payload.Type.STREAM && ep != null && stream != null) {
                val link = ep.link
                if (link != null) link.startReader(stream.asInputStream(), payload.id)
                else ep.earlyStream = stream.asInputStream() to payload.id
            } else if (payload.type != Payload.Type.BYTES) {
                // v1 FILE payloads etc.: not part of v2.
                try { client.cancelPayload(payload.id) } catch (_: Exception) {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                val link = endpoints[endpointId]?.link ?: return
                if (update.payloadId == link.outPayloadId || update.payloadId == link.inPayloadId) {
                    link.transportClosed("stream payload ${update.status}")
                }
            }
        }
    }

    private fun open(ep: Endpoint) {
        val role = if (ep.incoming) Role.HOST else Role.CLIENT
        val secret = if (ep.confirmed && ep.digits.isNotEmpty()) ep.digits.toByteArray(Charsets.UTF_8) else null
        val expected = if (role == Role.CLIENT && secret == null) ep.peerIdHint else null
        val link = NearbyLink(ep.id)
        val session = Session(role, link, expected, secret) { s, outcome -> onSessionEnd(ep, s, outcome) }
        link.session = session
        ep.link = link
        try {
            link.startWriter(client)
        } catch (e: IOException) {
            Log.w(TAG, "pipe failed", e)
            session.onTransportClosed("pipe")
            return
        }
        session.start()
        ep.earlyStream?.let { (input, id) -> ep.earlyStream = null; link.startReader(input, id) }
    }

    private fun onSessionEnd(ep: Endpoint, s: Session, outcome: Outcome) {
        endpoints.remove(ep.id, ep)
        val link = ep.link
        Thread({
            // Let bye/unpair/auth_fail reach the pipe, then give Nearby a moment to forward it.
            if (link?.awaitDrained(1500) == true) Thread.sleep(300)
            try { client.disconnectFromEndpoint(ep.id) } catch (_: Exception) {}
        }, "airbridge-nearby-close").apply { isDaemon = true }.start()
        val peer = s.peerId ?: ep.peerIdHint ?: return
        if (s.everReady) backoff.remove(peer)
        if (outcome.reconnect) scheduleRetry(peer)
    }

    /** Re-request a paired peer later if it is still discoverable (backoff 2 s → 30 s). */
    private fun scheduleRetry(peer: String) {
        if (!serviceActive || PairStore.get(peer)?.via != "nearby") return
        val delay = backoff[peer] ?: 2000L
        backoff[peer] = (delay * 2).coerceAtMost(30_000L)
        Hub.scheduler.schedule({
            _found.value.firstOrNull { it.peerId == peer }?.let { maybeAutoConnect(it.endpointId, peer) }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun showRequestNotification(name: String) {
        val n = NotificationCompat.Builder(Hub.app, Notifs.CH_PAIR)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Pairing request")
            .setContentText("$name wants to connect via Nearby. Tap to confirm the code.")
            .setContentIntent(Notifs.openAppIntent(Hub.app))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        Notifs.notify(Notifs.ID_NEARBY_REQUEST, n)
    }

    /** One STREAM payload per direction; frames `u8 kind | u32 len | payload` (§10). */
    private class NearbyLink(val endpointId: String) : Link {
        override val kind = "nearby"
        override val label = "nearby:$endpointId"
        lateinit var session: Session
        @Volatile var outPayloadId = 0L
        @Volatile var inPayloadId = 0L

        private class Frame(val kind: Int, val data: ByteArray)
        private val poison = Frame(-1, ByteArray(0))
        private val queue = LinkedBlockingQueue<Frame>()
        private val queued = AtomicLong()
        @Volatile private var closed = false
        private var pipe: Array<ParcelFileDescriptor>? = null
        @Volatile private var readerStarted = false
        private val drained = java.util.concurrent.CountDownLatch(1)

        /** Waits (bounded) until frames queued before [close] — e.g. bye/unpair/auth_fail — are written. */
        fun awaitDrained(ms: Long) = drained.await(ms, TimeUnit.MILLISECONDS)

        fun startWriter(client: ConnectionsClient) {
            val p = ParcelFileDescriptor.createPipe()
            pipe = p
            val payload = Payload.fromStream(p[0])
            outPayloadId = payload.id
            client.sendPayload(endpointId, payload)
            Thread({
                try {
                    DataOutputStream(BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(p[1]), 64 * 1024)).use { out ->
                        // Runs until the poison frame so everything queued before close() still goes out.
                        while (true) {
                            val f = queue.take()
                            if (f === poison) { out.flush(); break }
                            out.writeByte(f.kind)
                            out.writeInt(f.data.size)
                            out.write(f.data)
                            queued.addAndGet(-f.data.size.toLong())
                            if (queue.isEmpty()) out.flush()
                        }
                    }
                } catch (e: Exception) {
                    if (!closed) session.onTransportClosed("nearby write: ${e.message}")
                } finally {
                    try { p[0].close() } catch (_: Exception) {}
                    drained.countDown()
                }
            }, "airbridge-nearby-w").apply { isDaemon = true }.start()
        }

        fun startReader(input: InputStream, payloadId: Long) {
            if (readerStarted) return
            readerStarted = true
            inPayloadId = payloadId
            Thread({
                try {
                    DataInputStream(BufferedInputStream(input, 64 * 1024)).use { din ->
                        while (!closed) {
                            val kind = din.read()
                            if (kind < 0) break
                            val len = din.readInt()
                            if (len < 0 || len > Protocol.MAX_BINARY_FRAME) throw IOException("frame too large: $len")
                            val buf = ByteArray(len)
                            din.readFully(buf)
                            when (kind) {
                                Protocol.FRAME_TEXT -> session.onText(String(buf, Charsets.UTF_8))
                                Protocol.FRAME_BINARY -> session.onBinary(buf)
                                else -> Unit
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "reader ended: ${e.message}")
                }
                if (!closed) session.onTransportClosed("nearby stream ended")
            }, "airbridge-nearby-r").apply { isDaemon = true }.start()
        }

        private fun enqueue(kind: Int, data: ByteArray): Boolean {
            if (closed) return false
            queued.addAndGet(data.size.toLong())
            return queue.offer(Frame(kind, data))
        }

        override fun sendText(text: String) = enqueue(Protocol.FRAME_TEXT, text.toByteArray(Charsets.UTF_8))
        override fun sendBinary(data: ByteArray) = enqueue(Protocol.FRAME_BINARY, data)
        override fun queuedBytes() = queued.get()

        override fun close() {
            if (closed) return
            closed = true
            queue.offer(poison)
        }

        fun transportClosed(why: String) {
            if (::session.isInitialized) session.onTransportClosed(why)
        }
    }
}
