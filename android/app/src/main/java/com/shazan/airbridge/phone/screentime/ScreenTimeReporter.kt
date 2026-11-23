package com.shazan.airbridge.phone.screentime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Base64
import android.util.Log
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.PeerInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sends this phone's screen time to computers that show it (docs/protocol.md §15): the AirBridge widget on a Mac.
 *
 * Only to computers that advertise [CAP_SINK], once when they connect and then every 5 minutes while the numbers change.
 * It needs Android's usage access (the same permission the payment-app pause already asks for); without it nothing is sent.
 * The last 7 days, today with hours and the top apps, and the icons of today's top apps once per connection.
 */
object ScreenTimeReporter {
    private const val TAG = "AirBridge.ScreenTime"
    const val CAP_SINK = "screentime_sink"
    /** We answer `screen_time_request` (§15) with a fresh report at once. */
    const val CAP_SOURCE = "screentime_source"
    /** A computer can ask at most this often (it asks when someone opens its Screen Time page or menu). */
    private const val REQUEST_GAP_MS = 10_000L
    private val lastRequest = HashMap<String, Long>()
    private const val PERIOD_MS = 5L * 60 * 1000
    private const val DAYS = 7
    private const val ICONS = 16
    private const val ICON_PX = 96

    private val worker: Handler by lazy { Handler(HandlerThread("screen-time").apply { start() }.looper) }
    private lateinit var app: Context
    private var lastSent: String? = null
    /** Icons each computer already has in its current connection. */
    private val sentIcons = HashMap<String, MutableSet<String>>()
    private val tick = object : Runnable {
        override fun run() {
            try { sendAll(force = false) } catch (t: Throwable) { Log.w(TAG, "tick", t) }
            worker.postDelayed(this, PERIOD_MS)
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        worker.postDelayed(tick, PERIOD_MS)
    }

    /** A computer finished connecting: it gets the current numbers and icons at once. */
    fun onPeerReady(peerId: String) = worker.post {
        if (ConnectionHub.readySession(peerId)?.info()?.caps?.contains(CAP_SINK) != true) return@post
        sentIcons.remove(peerId)
        try { report()?.let { send(peerId, it) } } catch (t: Throwable) { Log.w(TAG, "ready", t) }
    }

    /** A computer wants the numbers now (someone is looking at them): send a fresh report straight away. */
    fun onRequest(peerId: String) = worker.post {
        if (ConnectionHub.readySession(peerId)?.info()?.caps?.contains(CAP_SINK) != true) return@post
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastRequest[peerId] ?: 0L) < REQUEST_GAP_MS) return@post
        lastRequest[peerId] = now
        try { report()?.let { send(peerId, it) } } catch (t: Throwable) { Log.w(TAG, "request", t) }
    }

    fun hasPermission(): Boolean = try {
        val ops = app.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun sinks(): List<PeerInfo> = ConnectionHub.peers().filter { CAP_SINK in it.caps }

    private fun sendAll(force: Boolean) {
        val peers = sinks()
        if (peers.isEmpty()) return
        val r = report() ?: return
        val text = r.message.toString()
        if (!force && text == lastSent) return // nothing changed since the last report
        lastSent = text
        peers.forEach { send(it.id, r) }
    }

    private class Report(val message: JSONObject, val top: List<String>)

    private fun report(): Report? {
        if (!hasPermission()) return null
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val begin = today.minusDays((DAYS - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val list = ArrayList<ScreenTimeCompute.Event>()
        val it = usm.queryEvents(begin, now)
        val e = UsageEvents.Event()
        while (it.hasNextEvent()) {
            it.getNextEvent(e)
            list.add(ScreenTimeCompute.Event(e.timeStamp, e.packageName ?: "", e.eventType, e.className ?: ""))
        }
        val days = ScreenTimeCompute.compute(list, now, zone, ignored(), untilStopped = android.os.Build.VERSION.SDK_INT >= 29)
        val key = today.toString()
        val pm = app.packageManager
        val msg = ScreenTimeCompute.message(days, key, { pkg -> label(pm, pkg) }, maxDays = DAYS, category = { pkg -> category(pm, pkg) })
        return Report(msg, ScreenTimeCompute.topApps(days, key, ICONS))
    }

    /** The home screen and the system UI are not screen time. */
    private fun ignored(): Set<String> {
        val out = mutableSetOf("com.android.systemui")
        try {
            // The home *screens* ("pkg/Class"), not their whole apps: some phones list Settings as a backup home, and its
            // other screens (App Lock, Wi-Fi…) are ordinary use, not "went home".
            app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNullTo(out) { r -> r.activityInfo?.let { "${it.packageName}/${it.name}" } }
        } catch (_: Throwable) {}
        return out
    }

    private fun category(pm: PackageManager, pkg: String): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 26) ScreenTimeCompute.categoryName(pm.getApplicationInfo(pkg, 0).category) else null
    } catch (_: Throwable) { null }

    private fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().take(80)
    } catch (_: Throwable) { pkg }

    private fun send(peerId: String, r: Report) {
        try {
            ConnectionHub.send(peerId, r.message)
            val have = sentIcons.getOrPut(peerId) { mutableSetOf() }
            for (pkg in r.top) {
                if (pkg in have) continue
                val png = iconPng(pkg) ?: continue
                ConnectionHub.send(peerId, JSONObject().put("t", "screen_time_icon").put("id", pkg).put("png", Base64.encodeToString(png, Base64.NO_WRAP)))
                have.add(pkg)
            }
        } catch (t: Throwable) { Log.w(TAG, "send", t) }
    }

    private fun iconPng(pkg: String): ByteArray? = try {
        val d = app.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray().takeIf { it.size <= 96 * 1024 }
    } catch (_: Throwable) { null }
}
