package com.shazan.airbridge.net

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds AirBridge devices on the local network without multicast: many routers (and every emulator)
 * drop the mDNS packets that [Discovery] relies on, while plain TCP still works. Every address of
 * this phone's /24 Wi-Fi subnet is tried on the default port (§2.2) with a short connect timeout;
 * whatever accepts is asked who it is with a `hello` ([EmulatorHost.peek]) and listed like an mDNS
 * result. Runs only while the "Add a device" screen is open (see [Discovery.acquireSweep]).
 *
 * On an emulator its own subnet (10.0.2.0/24) is useless, so the subnets of addresses already known
 * for paired peers / mDNS results are swept instead (they are on the real network the computer is on).
 */
object LanSweep {
    private const val TAG = "AirBridge.LanSweep"
    private const val PORT = 47632
    private const val CONNECT_MS = 350

    private val pool by lazy {
        Executors.newFixedThreadPool(48) { r -> Thread(r, "airbridge-sweep").apply { isDaemon = true } }
    }

    /** "10.145.158" style prefixes to sweep. */
    fun subnets(): Set<String> {
        val mine = LanHost.localAddresses()
        val known = PairStore.all().mapNotNull { it.lastHost } + Discovery.hosts.value.values.map { it.host }
        val candidates = (if (EmulatorHost.isEmulator) known else mine + known)
            .filter { isPrivateV4(it) && !it.startsWith("10.0.2.") }
        return candidates.map { it.substringBeforeLast('.') }.toSet()
    }

    /** Blocking: a few seconds per subnet at most. */
    fun sweep(skipIds: Set<String>): List<DiscoveredHost> {
        val mine = LanHost.localAddresses().toSet()
        val targets = subnets().flatMap { p -> (1..254).map { "$p.$it" } }.filter { it !in mine }
        if (targets.isEmpty()) return emptyList()
        val open = pool.invokeAll(targets.map { ip -> Callable { if (portOpen(ip)) ip else null } }, 20, TimeUnit.SECONDS)
            .mapNotNull { f -> try { f.get() } catch (_: Exception) { null } }
        val found = pool.invokeAll(open.map { ip -> Callable { EmulatorHost.peek(ip, PORT, "sweep:$ip") } }, 20, TimeUnit.SECONDS)
            .mapNotNull { f -> try { f.get() } catch (_: Exception) { null } }
            .filter { it.id !in skipIds && it.id != Identity.id }
        Log.i(TAG, "swept ${targets.size} addresses in ${subnets()}: ${open.size} answered on $PORT, " +
            "new: ${found.joinToString { "${it.name} (${it.plat}) at ${it.host}" }.ifEmpty { "none" }}")
        return found
    }

    private fun portOpen(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, PORT), CONNECT_MS); true }
    } catch (_: Exception) { false }

    private fun isPrivateV4(a: String): Boolean {
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }
}
