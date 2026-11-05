package com.shazan.airbridge.net

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** A host found via mDNS with a v2 TXT record (§2.3). */
data class DiscoveredHost(
    val id: String,
    val name: String,
    val plat: String,
    val host: String,
    val port: Int,
    val serviceName: String,
)

/**
 * Continuous `_airbridge._tcp` browse, reference-counted (service + discovery screen).
 * Resolves with registerServiceInfoCallback on API 34+ (live updates), else a serialised
 * resolveService queue because concurrent resolves fail with FAILURE_ALREADY_ACTIVE.
 */
object Discovery {
    private const val TAG = "AirBridge.Discovery"

    private val _hosts = MutableStateFlow<Map<String, DiscoveredHost>>(emptyMap())
    val hosts: StateFlow<Map<String, DiscoveredHost>> = _hosts

    private val nsd: NsdManager? by lazy { Hub.app.getSystemService(NsdManager::class.java) }
    private var refs = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    // API 34+: serviceName -> callback
    private val infoCallbacks = HashMap<String, Any>()
    // API < 34
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun lookup(id: String): Pair<String, Int>? = _hosts.value[id]?.let { it.host to it.port }

    @Synchronized fun acquire() {
        if (refs++ == 0) startBrowse()
    }

    @Synchronized fun release() {
        if (refs == 0) return
        if (--refs == 0) stopBrowse(clear = true)
    }

    /** Network changed: flush caches and browse again. */
    @Synchronized fun restart() {
        if (refs == 0) return
        stopBrowse(clear = true)
        startBrowse()
    }

    /** Emulator only: the periodic ask-the-host-computer scan ([EmulatorHost]), on its own thread (it blocks). */
    private var emuScan: java.util.concurrent.ScheduledFuture<*>? = null
    private val emuExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "airbridge-emuhost").apply { isDaemon = true } }
    }

    private fun startEmulatorScan() {
        if (!EmulatorHost.isEmulator || emuScan != null) return
        emuScan = emuExecutor.scheduleWithFixedDelay({
            val got = try { EmulatorHost.scan() } catch (e: Exception) { Log.w(TAG, "emulator scan", e); emptyList() }
            synchronized(this) {
                if (emuScan == null) return@synchronized
                // mDNS results win (they are real network addresses); the host-computer answer fills the gap.
                val keep = _hosts.value.filterValues { !it.serviceName.startsWith("emulator-host:") || got.any { g -> g.id == it.id } }
                val fresh = got.filter { keep[it.id] == null }
                _hosts.value = keep + fresh.associateBy { it.id }
                fresh.forEach { WsClient.onDiscovered(it.id) }
            }
        }, 0, 15, TimeUnit.SECONDS)
    }

    private fun stopEmulatorScan() { emuScan?.cancel(false); emuScan = null }

    @Synchronized private fun startBrowse() {
        startEmulatorScan()
        val mgr = nsd ?: return
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { synchronized(this@Discovery) { discovering = true } }
            override fun onDiscoveryStopped(serviceType: String) { synchronized(this@Discovery) { discovering = false } }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                synchronized(this@Discovery) {
                    if (listener === this) { listener = null; discovering = false }
                }
                // Never call stopServiceDiscovery here: the listener isn't registered.
                Hub.scheduler.schedule({ synchronized(this@Discovery) { if (refs > 0) startBrowse() } }, 5, TimeUnit.SECONDS)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed: $errorCode")
            }
            override fun onServiceFound(info: NsdServiceInfo) = found(info)
            override fun onServiceLost(info: NsdServiceInfo) = lost(info.serviceName)
        }
        listener = l
        try {
            mgr.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            listener = null
        }
    }

    @Synchronized private fun stopBrowse(clear: Boolean) {
        stopEmulatorScan()
        val mgr = nsd ?: return
        val l = listener
        listener = null
        if (l != null) {
            // Only stop a listener we actually registered, exactly once.
            try { mgr.stopServiceDiscovery(l) } catch (e: IllegalArgumentException) { Log.d(TAG, "listener not registered") }
        }
        discovering = false
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.keys.toList().forEach { unregisterInfo(it) }
        resolveQueue.clear()
        if (clear) _hosts.value = emptyMap()
    }

    private fun found(info: NsdServiceInfo) = synchronized(this) {
        if (listener == null) return
        if (Build.VERSION.SDK_INT >= 34) registerInfo(info)
        else {
            if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.add(info)
            pumpResolve()
        }
    }

    private fun lost(serviceName: String) {
        synchronized(this) {
            if (Build.VERSION.SDK_INT >= 34) unregisterInfo(serviceName)
            resolveQueue.removeAll { it.serviceName == serviceName }
        }
        val before = _hosts.value
        val after = before.filterValues { it.serviceName != serviceName }
        if (after.size != before.size) { _hosts.value = after; Hub.recomputeStatus() }
    }

    @RequiresApi(34)
    private fun registerInfo(info: NsdServiceInfo) {
        val mgr = nsd ?: return
        if (infoCallbacks.containsKey(info.serviceName)) return
        val name = info.serviceName
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "info callback failed for $name: $errorCode")
                synchronized(this@Discovery) { infoCallbacks.remove(name) }
            }
            override fun onServiceUpdated(si: NsdServiceInfo) = resolved(si, name)
            override fun onServiceLost() = lost(name)
            override fun onServiceInfoCallbackUnregistered() {}
        }
        infoCallbacks[name] = cb
        try {
            mgr.registerServiceInfoCallback(info, { it.run() }, cb)
        } catch (e: Exception) {
            Log.w(TAG, "registerServiceInfoCallback", e)
            infoCallbacks.remove(name)
        }
    }

    @RequiresApi(34)
    private fun unregisterInfo(serviceName: String) {
        val cb = infoCallbacks.remove(serviceName) as? NsdManager.ServiceInfoCallback ?: return
        try { nsd?.unregisterServiceInfoCallback(cb) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun pumpResolve() {
        val mgr = nsd ?: return
        if (resolving || listener == null) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        try {
            mgr.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@Discovery) {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            resolveQueue.addFirst(next)
                            Hub.scheduler.schedule({ synchronized(this@Discovery) { pumpResolve() } }, 300, TimeUnit.MILLISECONDS)
                        } else pumpResolve()
                    }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    resolved(si, next.serviceName)
                    synchronized(this@Discovery) { resolving = false; pumpResolve() }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService", e)
            resolving = false
        }
    }

    private fun resolved(si: NsdServiceInfo, serviceName: String) {
        val attrs = si.attributes ?: return
        fun txt(k: String) = attrs[k]?.toString(Charsets.UTF_8)
        if (txt("v") != "2") return
        val id = txt("id")?.takeIf { it.isNotBlank() } ?: return
        if (id == Identity.id) return
        val addr = pickAddress(si) ?: return
        // An emulator's own NAT address leaks into mDNS but can't be reached from a real network.
        if (EmulatorHost.unreachableFromHere(addr)) { Log.i(TAG, "ignored ${txt("name")}: $addr is inside an emulator"); return }
        val port = si.port.takeIf { it in 1..65535 } ?: return
        val host = DiscoveredHost(id, txt("name") ?: si.serviceName, txt("plat") ?: "", addr, port, serviceName)
        val before = _hosts.value
        if (before[id] == host) return
        _hosts.value = before.filterValues { it.serviceName != serviceName || it.id == id } + (id to host)
        Log.i(TAG, "resolved ${host.name} ($id) at $addr:$port")
        WsClient.onDiscovered(id)
    }

    /** IPv4 preferred; IPv6 only if not link-local (zone ids don't survive URLs). */
    private fun pickAddress(si: NsdServiceInfo): String? {
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses
        else @Suppress("DEPRECATION") listOfNotNull(si.host)
        all.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
        return all.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress?.substringBefore('%')
    }
}
