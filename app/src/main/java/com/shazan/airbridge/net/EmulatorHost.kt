package com.shazan.airbridge.net

import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android emulator sits behind its own NAT: multicast from the real network does not reach it, and
 * its own address (10.0.2.x) is useless to anyone outside. It can, however, always reach the computer
 * it runs on at [HOST] — where the Mac / PC AirBridge usually is. While discovery runs on an emulator we
 * ask that address directly: open `/v2`, exchange `hello`, close (no pairing, nothing stored), and list
 * what answered like an mDNS result.
 */
object EmulatorHost {
    private const val TAG = "AirBridge.EmulatorHost"
    const val HOST = "10.0.2.2"

    val isEmulator: Boolean by lazy {
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk_gphone") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.startsWith("sdk_gphone")
    }

    /** True for an address only valid inside an emulator's NAT, seen from a device that is not in it. */
    fun unreachableFromHere(addr: String): Boolean =
        addr.startsWith("10.0.2.") && LanHost.localAddresses().none { it.startsWith("10.0.2.") }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /** Every AirBridge host answering on the emulator's host computer (ports of §2.2). Blocking. */
    fun scan(): List<DiscoveredHost> = (47632..47641).mapNotNull { peek(HOST, it, "emulator-host:$it") }

    /**
     * Opens `/v2` at [host]:[port], sends our `hello`, reads the other side's `hello`, closes: who is
     * there (no pairing, nothing stored). Blocking, at most ~3 s. Also used by [LanSweep].
     */
    fun peek(host: String, port: Int, serviceName: String): DiscoveredHost? {
        val done = CountDownLatch(1)
        var found: DiscoveredHost? = null
        val eph = Crypto.ephemeral()
        val req = try { Request.Builder().url("http://$host:$port${Protocol.WS_PATH}").build() } catch (_: Exception) { return null }
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val (w, h) = Identity.screenSize()
                webSocket.send(Protocol.hello(Identity.id, Identity.name, Protocol.randomBytes(16), eph.publicRaw, w, h, null).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val m = JSONObject(text)
                    val id = m.optString("id")
                    if (m.optString("t") == "hello" && id.isNotBlank() && id != Identity.id) {
                        found = DiscoveredHost(id, m.optString("name", "Device"), m.optString("plat"), host, port, serviceName)
                    }
                } catch (_: Exception) {}
                webSocket.close(1000, null)
                done.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })
        done.await(3, TimeUnit.SECONDS)
        ws.cancel()
        eph.destroy()
        found?.let { Log.i(TAG, "${it.name} (${it.plat}) answers at $host:$port") }
        return found
    }
}
