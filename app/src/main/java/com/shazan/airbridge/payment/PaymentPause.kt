package com.shazan.airbridge.payment

import android.Manifest
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shazan.airbridge.AirBridgeInputService
import com.shazan.airbridge.AirBridgeService
import com.shazan.airbridge.AirBridgeTextService
import com.shazan.airbridge.R
import com.shazan.airbridge.net.Notifs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UPI apps (Paytm shows "Suspicious App Detected: remove the app or disable its Accessibility permission")
 * refuse to pay while any accessibility service from outside the Play Store is on, and they look again before
 * every payment. AirBridge cannot make its two services invisible, but it can make sure they are off exactly
 * while such an app is in front, and on again right after.
 *
 *  - Which app is in front comes from usage stats (Settings → Usage access), not from accessibility, because
 *    the services are the thing being switched off.
 *  - Switching: with WRITE_SECURE_SETTINGS (`adb shell pm grant … android.permission.WRITE_SECURE_SETTINGS`, one
 *    time) the entries of our two services are taken out of `enabled_accessibility_services` and put back after,
 *    nothing else in that list is touched. Without it the services turn themselves off with `disableSelf()` and
 *    a notification asks the user to turn them back on (Android gives an app no way to do that itself).
 *  - What was taken out is stored on disk first, so a kill, crash or reboot in between is repaired at the next start.
 *
 * Off the table on purpose: hiding the services from the app or pretending to be installed from Google Play.
 * That would be defeating the check while leaving the thing it guards against switched on during a payment.
 */
object PaymentPause {
    private const val TAG = "AirBridgePayPause"
    const val PREF_ENABLED = "PAYMENT_PAUSE"
    private const val PREF_REMOVED = "PAYMENT_PAUSE_REMOVED"
    private const val PREFS = "AirBridgePrefs"
    private const val NOTIF_ID = 4732

    /** Back on this long after the payment app left, so moving between its screens doesn't flap the services. */
    private const val GRACE_MS = 1500L

    /** How far back the first look goes, to know which app is already in front. */
    /** Usage events can become visible a moment after they happened, so each look re-reads a little of the last one. */
    private const val OVERLAP_MS = 10_000L

    private const val LOOKBACK_MS = 10 * 60_000L

    data class State(val paused: Boolean = false, val app: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var fallbackNotify = false
    @Volatile private var seen = 0
    @Volatile private var lastEvent = ""

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_ENABLED, on).apply()
        if (on) ensureRunning(ctx)
    }

    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun canSwitchSecurely(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Start the watcher (idempotent) and repair a pause that a kill or reboot left behind. */
    fun ensureRunning(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        synchronized(this) {
            if (job?.isActive == true) return
            if (leftover(app).isNotEmpty()) _state.value = State(paused = true)
            job = scope.launch { try { loop(app) } catch (t: Throwable) { Log.w(TAG, "watcher ended", t) } }
        }
    }

    private suspend fun loop(ctx: Context) {
        val power = ctx.getSystemService(PowerManager::class.java)
        val stats = ctx.getSystemService(UsageStatsManager::class.java)
        var fg = Front(null, null)
        var beat = 0L
        var since = System.currentTimeMillis() - LOOKBACK_MS
        var leftAt = 0L
        while (scope.isActive) {
            val watching = isEnabled(ctx) && hasUsageAccess(ctx)
            val paused = _state.value.paused
            val ours = ourEntries(ctx).isNotEmpty()
            val t0 = System.currentTimeMillis()
            if (t0 - beat > 15_000) {
                beat = t0
                Log.i(TAG, "watching=$watching front=${fg.pkg} ourServicesOn=$ours paused=$paused secure=${canSwitchSecurely(ctx)} serviceRunning=${AirBridgeService.isRunning} lastQueryEvents=$seen last=$lastEvent")
            }
            if (!watching) {
                if (paused) resume(ctx)
                fg = Front(null, null); since = System.currentTimeMillis() - LOOKBACK_MS
                delay(5_000)
                continue
            }
            if (!paused && !ours) {
                // Nothing to switch off: look again soon, and replay the last minutes when something is switched on.
                fg = Front(null, null); since = System.currentTimeMillis() - LOOKBACK_MS
                delay(3_000)
                continue
            }
            PaymentApps.extra = prefs(ctx).getString("PAYMENT_PAUSE_EXTRA", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            if (power?.isInteractive == false) {
                fg = Front(null, null)
            } else {
                val now = System.currentTimeMillis()
                fg = foreground(stats, since - OVERLAP_MS, now, fg)
                since = now
            }
            val pay = PaymentApps.isPayment(fg.pkg)
            if (pay) {
                leftAt = 0
                // Every tick, not once: at process start the services may not have connected yet, and one may bind late.
                if (ours) pause(ctx, fg.pkg!!)
            } else if (_state.value.paused) {
                val now = System.currentTimeMillis()
                if (leftAt == 0L) leftAt = now
                if (now - leftAt >= GRACE_MS) { resume(ctx); leftAt = 0 }
            }
            delay(if (pay || _state.value.paused) 350 else 700)
        }
    }

    /** The activity in front: its package and class. */
    private data class Front(val pkg: String?, val cls: String?)

    /**
     * The activity in front after replaying the usage events in [from]..[to] on top of [current]. A pause only
     * counts when it is the pause of the activity that is in front: an app's earlier screen is often paused
     * after its next one has resumed (Paytm does), and that must not read as "the app left".
     */
    private fun foreground(stats: UsageStatsManager?, from: Long, to: Long, current: Front): Front {
        stats ?: return current
        var fg = current
        try {
            val ev = stats.queryEvents(from, to)
            seen = 0
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                seen++; lastEvent = "${e.eventType} ${e.packageName}/${e.className?.substringAfterLast('.')}"
                when (e.eventType) {
                    1 /* ACTIVITY_RESUMED / MOVE_TO_FOREGROUND */ -> fg = Front(e.packageName, e.className)
                    2 /* ACTIVITY_PAUSED / MOVE_TO_BACKGROUND */ -> if (e.packageName == fg.pkg && e.className == fg.cls) fg = Front(null, null)
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "usage events", t) }
        return fg
    }

    private fun ourEntries(ctx: Context): List<String> =
        EnabledServices.ours(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ctx.packageName)

    private fun leftover(ctx: Context): List<String> = EnabledServices.split(prefs(ctx).getString(PREF_REMOVED, null))

    private fun pause(ctx: Context, app: String) {
        val cr = ctx.contentResolver
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val (next, removed) = EnabledServices.remove(cur, ctx.packageName)
        if (removed.isEmpty()) return
        // Switching our own entries only works while something keeps this process alive to switch them back.
        if (canSwitchSecurely(ctx) && AirBridgeService.isRunning) {
            // On disk first: if we die between the two steps the next start still knows what to put back.
            val all = (leftover(ctx) + removed).distinct()
            prefs(ctx).edit().putString(PREF_REMOVED, all.joinToString(":")).commit()
            val ok = try { Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next) } catch (_: Exception) { false }
            if (ok) {
                Log.i(TAG, "paused ${removed.size} service(s) for $app")
                _state.value = State(paused = true, app = app)
                return
            }
            if (leftover(ctx).isEmpty() || all == removed) prefs(ctx).edit().remove(PREF_REMOVED).apply()
        }
        // No way back by ourselves: turn them off, and tell the user how to turn them on once the payment is done.
        val a = AirBridgeInputService.turnOff()
        val b = AirBridgeTextService.turnOff()
        if (!a && !b) return // not connected yet (process just started): try again on the next tick
        Log.i(TAG, "switched off for $app (user turns back on)")
        fallbackNotify = true
        _state.value = State(paused = true, app = app)
    }

    private fun resume(ctx: Context) {
        val cr = ctx.contentResolver
        val removed = leftover(ctx)
        if (removed.isNotEmpty()) {
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val next = EnabledServices.restore(cur, removed)
            val ok = try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next) &&
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } catch (t: Throwable) { Log.w(TAG, "restore refused", t); false }
            if (ok) { prefs(ctx).edit().remove(PREF_REMOVED).apply(); Log.i(TAG, "restored ${removed.size} service(s)") }
            else if (!canSwitchSecurely(ctx)) { prefs(ctx).edit().remove(PREF_REMOVED).apply(); fallbackNotify = true }
            else return // try again on the next tick
        }
        if (fallbackNotify) { fallbackNotify = false; notifyTurnBackOn(ctx) }
        _state.value = State()
    }

    private fun notifyTurnBackOn(ctx: Context) {
        try {
            val pi = PendingIntent.getActivity(
                ctx, NOTIF_ID, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, Notifs.CH_CONTROL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(ctx.getString(R.string.pp_notif_title))
                .setContentText(ctx.getString(R.string.pp_notif_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.pp_notif_text)))
                .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(10 * 60_000L).build()
            Notifs.notify(NOTIF_ID, n)
        } catch (t: Throwable) { Log.w(TAG, "notify", t) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Packages whose apps refuse to pay while an accessibility service is on. */
object PaymentApps {
    val packages: Set<String> = setOf(
        "net.one97.paytm",                              // Paytm
        "com.phonepe.app",                              // PhonePe
        "com.google.android.apps.nbu.paisa.user",       // Google Pay
        "in.org.npci.upiapp",                           // BHIM
    )

    /** More packages to treat the same way (comma-separated pref `PAYMENT_PAUSE_EXTRA`: another UPI or bank app). */
    @Volatile var extra: Set<String> = emptySet()

    fun isPayment(pkg: String?): Boolean = pkg != null && (pkg in packages || pkg in extra)
}

/** Edits of the `enabled_accessibility_services` string ("a/b:c/.d"). Pure, unit-tested. */
object EnabledServices {
    fun split(s: String?): List<String> = s?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** "pkg/.Cls" and "pkg/pkg.Cls" name the same service. */
    fun normal(entry: String): String {
        val pkg = entry.substringBefore('/')
        val cls = entry.substringAfter('/', "")
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else entry
    }

    fun ours(enabled: String?, pkg: String): List<String> = split(enabled).filter { it.substringBefore('/') == pkg }

    /** [enabled] without the services of [pkg], and the entries that were taken out. */
    fun remove(enabled: String?, pkg: String): Pair<String, List<String>> {
        val all = split(enabled)
        val (gone, kept) = all.partition { it.substringBefore('/') == pkg }
        return kept.joinToString(":") to gone
    }

    /** [enabled] with [removed] added again where they are missing; the rest keeps its order. */
    fun restore(enabled: String?, removed: List<String>): String {
        val cur = split(enabled)
        val have = cur.map(::normal).toSet()
        return (cur + removed.filter { normal(it) !in have }).joinToString(":")
    }
}
