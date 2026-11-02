package com.shazan.airbridge.net.link

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A join that did not produce a network. [reason] is a §10.1 `link_fail` reason. */
class JoinFailed(val reason: String, val detail: String = "") : Exception("$reason $detail".trim())

/**
 * Joins the peer's Wi-Fi Direct legacy group as a local-only Wi-Fi network (T1, docs/WIFI_DIRECT_ANDROID_WINDOWS.md
 * §2, §4): `WifiNetworkSpecifier(ssid, wpa2)` → `requestNetwork(TRANSPORT_WIFI, no INTERNET)`. The process is never
 * bound to the network; callers bind individual sockets with [Joined.network]`.socketFactory`.
 *
 * Android shows a one-time approval dialog per SSID + security type (API 33+); on API 29–32 it shows one per request
 * unless `setBssid` names a previously approved BSSID, which is cached here after each join.
 */
class WfdJoiner(context: Context) {

    /** A joined network. Call [release] exactly when the link ends; it is idempotent. */
    class Joined internal constructor(
        val network: Network,
        val ssid: String,
        /** This phone's IPv4 address on the group. */
        val localAddr: String?,
        /** DHCP server (API 30+) and default-route gateways: dial fallbacks if the offer's `addr` fails. */
        val gateways: List<String>,
        val freq: Int,
        /** "kept" | "dropped" | "none": did the phone keep its home Wi-Fi? */
        val sta: String,
        /** Best guess whether the approval dialog appeared (null = unknown). */
        val dialogShown: Boolean?,
        val ms: Long,
        val bssid: String?,
        /** `isStaConcurrencyForLocalOnlyConnectionsSupported()` (API 31+, else false). */
        val staConcurrency: Boolean,
        private val req: Req,
    ) {
        val staKept: Boolean get() = sta == "kept"
        fun release() = req.release()
        /** Called once if the network is lost after the join (immediately if it already was). */
        fun onLost(cb: () -> Unit) = req.setOnLost(cb)
        override fun toString() =
            "Joined(ssid=$ssid, ip=$localAddr, gw=$gateways, freq=$freq, sta=$sta, dlg=${dialogShown ?: "unknown"}, ms=$ms, bssid=$bssid, concurrency=$staConcurrency)"
    }

    /** One `requestNetwork` registration. */
    internal class Req(private val cm: ConnectivityManager, private val wm: WifiManager) {
        var callback: ConnectivityManager.NetworkCallback? = null
        var failListener: Any? = null
        private val released = AtomicBoolean(false)
        @Volatile private var lost = false
        @Volatile private var onLost: (() -> Unit)? = null

        fun release() {
            if (!released.compareAndSet(false, true)) return
            callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
            if (Build.VERSION.SDK_INT >= 34) (failListener as? WifiManager.LocalOnlyConnectionFailureListener)?.let {
                try { wm.removeLocalOnlyConnectionFailureListener(it) } catch (_: Exception) {}
            }
            Log.i(TAG, "network request released")
        }

        fun lost() {
            lost = true
            if (!released.get()) onLost?.invoke()
        }

        fun setOnLost(cb: () -> Unit) {
            onLost = cb
            if (lost && !released.get()) cb()
        }
    }

    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Join [c]. Throws [JoinFailed] (`api_level`, `radio_off`, `join_failed`, `approval_denied`, `no_ip`).
     * Cancelling the coroutine releases the request.
     */
    suspend fun join(c: WfdCreds, timeoutMs: Long = LinkPolicy.JOIN_TIMEOUT_MS): Joined {
        if (Build.VERSION.SDK_INT < 29) throw JoinFailed("api_level")
        unsupportedReason(app)?.let { throw JoinFailed(it) }
        if (!wm.isWifiEnabled) throw JoinFailed("radio_off")
        return joinQ(c, timeoutMs)
    }

    @RequiresApi(29)
    private suspend fun joinQ(c: WfdCreds, timeoutMs: Long): Joined {
        val t0 = SystemClock.elapsedRealtime()
        val wifiBefore = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } catch (_: Exception) { false }
        val concurrency = Build.VERSION.SDK_INT >= 31 && try { wm.isStaConcurrencyForLocalOnlyConnectionsSupported } catch (_: Exception) { false }
        val seenBefore = prefs.getBoolean(KEY_JOINED + c.ssid, false)
        val fgAtStart = Foreground.resumed > 0
        val pausesAtStart = Foreground.pauses
        val cachedBssid = if (Build.VERSION.SDK_INT <= 32) prefs.getString(KEY_BSSID + c.ssid, null) else null

        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(c.ssid)
            .setWpa2Passphrase(c.psk)
            .apply { cachedBssid?.let { b -> try { setBssid(MacAddress.fromString(b)) } catch (_: Exception) {} } }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        Log.i(TAG, "join ${c.ssid} timeout=${timeoutMs}ms api=${Build.VERSION.SDK_INT} concurrency=$concurrency wifiBefore=$wifiBefore bssid=${cachedBssid ?: "-"}")

        val req = Req(cm, wm)
        val failCode = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        if (Build.VERSION.SDK_INT >= 34) {
            val l = WifiManager.LocalOnlyConnectionFailureListener { _, code ->
                failCode.set(code)
                Log.i(TAG, "local-only connection failure code=$code")
            }
            try { wm.addLocalOnlyConnectionFailureListener(app.mainExecutor, l); req.failListener = l } catch (e: Exception) { Log.w(TAG, "failure listener: ${e.message}") }
        }

        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun fail(reason: String, detail: String) {
                req.release()
                if (done.compareAndSet(false, true)) cont.resumeWithException(JoinFailed(reason, detail))
            }
            // Ask for unredacted WifiInfo (BSSID) where it matters for the dialog cache (API 31–32).
            val flags = if (Build.VERSION.SDK_INT in 31..32) ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
            val cb = object : ConnectivityManager.NetworkCallback(flags) {
                private var net: Network? = null
                private var caps: NetworkCapabilities? = null
                private var lp: LinkProperties? = null

                override fun onAvailable(network: Network) { net = network; maybeJoined() }
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { caps = nc; maybeJoined() }
                override fun onLinkPropertiesChanged(network: Network, p: LinkProperties) { lp = p; maybeJoined() }

                private fun maybeJoined() {
                    val n = net ?: return
                    val nc = caps ?: return
                    val p = lp ?: return
                    if (done.get()) return
                    val joined = try {
                        describe(n, nc, p, c, t0, wifiBefore, concurrency, seenBefore, fgAtStart, pausesAtStart, req)
                    } catch (e: Exception) {
                        Log.w(TAG, "describe", e); fail("join_failed", "describe: ${e.message}"); return
                    }
                    if (done.compareAndSet(false, true)) {
                        Log.i(TAG, "joined: $joined")
                        cont.resume(joined)
                    }
                }

                override fun onUnavailable() {
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    val dialogSeen = fgAtStart && Foreground.pauses > pausesAtStart
                    // Give the API 34+ failure listener a moment to report why.
                    main.postDelayed({
                        val code = failCode.get()
                        val reason = when (code) {
                            STATUS_USER_REJECT -> "approval_denied"
                            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING -> "no_ip"
                            null -> if (dialogSeen && elapsed < timeoutMs - 1_000) "approval_denied" else "join_failed"
                            else -> "join_failed"
                        }
                        fail(reason, "unavailable after ${elapsed}ms code=${code ?: "-"} dialog=$dialogSeen")
                    }, if (Build.VERSION.SDK_INT >= 34) 300L else 0L)
                }

                override fun onLost(network: Network) {
                    if (done.get()) req.lost() else fail("join_failed", "lost before ready")
                }
            }
            req.callback = cb
            cont.invokeOnCancellation { req.release() }
            try {
                cm.requestNetwork(request, cb, timeoutMs.toInt().coerceAtLeast(1))
            } catch (e: SecurityException) {
                fail("join_failed", "security: ${e.message}")
            } catch (e: Exception) {
                fail("join_failed", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @RequiresApi(29)
    private fun describe(
        n: Network, nc: NetworkCapabilities, p: LinkProperties, c: WfdCreds, t0: Long, wifiBefore: Boolean,
        concurrency: Boolean, seenBefore: Boolean, fgAtStart: Boolean, pausesAtStart: Int, req: Req,
    ): Joined {
        @Suppress("DEPRECATION")
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= 31) nc.transportInfo as? WifiInfo else wm.connectionInfo
        val freq = info?.frequency ?: -1
        val bssid = info?.bssid?.takeIf { it != REDACTED_BSSID && it.isNotBlank() }
        val local = p.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
        val gateways = buildList {
            if (Build.VERSION.SDK_INT >= 30) p.dhcpServerAddress?.hostAddress?.let { add(it) }
            p.routes.mapNotNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress }.forEach { add(it) }
        }.distinct()
        val staNow = otherInternetWifi(n)
        val sta = when {
            !wifiBefore -> "none"
            concurrency && staNow -> "kept"
            else -> "dropped"
        }
        val dialog = when {
            fgAtStart -> Foreground.pauses > pausesAtStart
            seenBefore -> false
            else -> null
        }
        prefs.edit().apply {
            putBoolean(KEY_JOINED + c.ssid, true)
            if (Build.VERSION.SDK_INT <= 32 && bssid != null) putString(KEY_BSSID + c.ssid, bssid)
        }.apply()
        return Joined(n, c.ssid, local, gateways, freq, sta, dialog, SystemClock.elapsedRealtime() - t0, bssid, concurrency, req)
    }

    @Suppress("DEPRECATION")
    private fun otherInternetWifi(ours: Network): Boolean = try {
        cm.allNetworks.any { net ->
            net != ours && cm.getNetworkCapabilities(net)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    } catch (_: Exception) { false }

    /** Counts this app's resumed activities and pauses: the approval dialog pauses a foreground activity. */
    object Foreground : Application.ActivityLifecycleCallbacks {
        @Volatile var resumed = 0; private set
        @Volatile var pauses = 0; private set
        private var installed = false

        @Synchronized fun install(app: Application) {
            if (installed) return
            installed = true
            app.registerActivityLifecycleCallbacks(this)
        }
        override fun onActivityResumed(a: Activity) { resumed++ }
        override fun onActivityPaused(a: Activity) { if (resumed > 0) resumed--; pauses++ }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    companion object {
        private const val TAG = "AirBridge.Wfd"
        private const val PREFS = "AirBridgeLink"
        private const val KEY_JOINED = "joined:"
        private const val KEY_BSSID = "bssid:"
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
        /** WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_USER_REJECT (added after API 34). */
        private const val STATUS_USER_REJECT = 6

        /** null if this device can join a legacy group (§10.1 `wfd_join`): API ≥ 29 and Wi-Fi hardware. */
        fun unsupportedReason(ctx: Context): String? = when {
            Build.VERSION.SDK_INT < 29 -> "api_level"
            !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) -> "api_level"
            else -> null
        }

        fun supported(ctx: Context): Boolean = unsupportedReason(ctx) == null

        fun wifiOn(ctx: Context): Boolean = try {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Exception) { true }
    }
}
