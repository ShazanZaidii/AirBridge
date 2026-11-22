package com.shazan.airbridge.payment

import android.Manifest
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shazan.airbridge.R
import com.shazan.airbridge.app.AirBridgeService
import com.shazan.airbridge.app.Notifs
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.PeerStatus
import com.shazan.airbridge.control.accessibility.ControlAccessibilityService
import com.shazan.airbridge.control.accessibility.TypingAccessibilityService
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UPI apps (Paytm shows "Suspicious App Detected: remove the app or disable its Accessibility permission")
 * refuse to pay while any accessibility service from outside the Play Store is on, and they look again before
 * every payment. AirBridge cannot make its two services invisible, but it can make sure they are off whenever
 * they are not needed:
 *
 *  1. **While a UPI app is in front** (and on again right after). Which app is in front comes from usage stats
 *     (Settings → Usage access), not from accessibility, because the services are the thing being switched off.
 *  2. **While no device has been connected for a minute** (and on again the moment one connects). Nothing can
 *     control this phone or type into it without a device, so the services are not needed, and the watcher sleeps:
 *     no polling, no timers, only a wake-up when a connection changes. Finding, pairing and reconnecting devices
 *     don't use the services and are untouched.
 *
 *  - Switching: with WRITE_SECURE_SETTINGS (`adb shell pm grant … android.permission.WRITE_SECURE_SETTINGS`, one
 *    time) the entries of our two services are taken out of `enabled_accessibility_services` and put back after,
 *    nothing else in that list is touched. Without it the services turn themselves off with `disableSelf()` for a
 *    payment app and a notification asks the user to turn them back on (Android gives an app no way to do that
 *    itself); the no-device switch-off needs the permission and is skipped without it.
 *  - What was taken out is stored on disk first, so a kill, crash or reboot in between is repaired at the next start.
 *
 * Off the table on purpose: hiding the services from the app or pretending to be installed from Google Play.
 * That would be defeating the check while leaving the thing it guards against switched on during a payment.
 */
object PaymentPause {
    private const val TAG = "AirBridgePayPause"
    const val PREF_ENABLED = "PAYMENT_PAUSE"
    private const val PREF_REMOVED = "PAYMENT_PAUSE_REMOVED"
    private const val PREF_REASON = "PAYMENT_PAUSE_REASON"
    /** Wall-clock time a device was last seen connected: a restarted watcher counts the minute from then, not from its own start. */
    private const val PREF_LAST_CONNECTED = "PAYMENT_PAUSE_LAST_CONNECTED"
    private const val REASON_PAYMENT = "payment"
    private const val REASON_IDLE = "idle"
    private const val PREFS = "AirBridgePrefs"
    private const val NOTIF_ID = 4732

    /** Back on this long after the payment app left, so moving between its screens doesn't flap the services. */
    private const val GRACE_MS = 1500L

    /** Usage events can become visible a moment after they happened, so each look re-reads a little of the last one. */
    private const val OVERLAP_MS = 10_000L

    /** How far back the first look goes, to know which app is already in front. */
    private const val LOOKBACK_MS = 10 * 60_000L

    /** No device connected for this long: the accessibility services go off. */
    const val IDLE_MS = 60_000L

    /** After a (re)start the watcher still waits this long for devices to reconnect by themselves before switching off. */
    private const val START_GRACE_MS = 15_000L

    /**
     * [paused]: off because a payment app is in front. [idleOff]: off because no device is connected.
     * Either way it is AirBridge that switched them off, not the user.
     */
    data class State(val paused: Boolean = false, val app: String? = null, val idleOff: Boolean = false) {
        val ownOff: Boolean get() = paused || idleOff
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    @Volatile private var supervisor: Job? = null
    @Volatile private var pollJob: Job? = null
    @Volatile private var fallbackNotify = false

    /** Something the coordinator reacts to changed (connection, services list, the switch). */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The services list changed: the poll loop may have something to do again. */
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_ENABLED, on).apply()
        ensureRunning(ctx)
        wake.trySend(Unit)
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

    private fun connected(): Boolean = ConnectionHub.status.value.values.any { it == PeerStatus.CONNECTED }

    /** Start the watcher (idempotent) and repair a switch-off that a kill or reboot left behind. */
    fun ensureRunning(ctx: Context) {
        val app = ctx.applicationContext
        if (supervisor?.isActive == true) return
        synchronized(lock) {
            if (supervisor?.isActive == true) return
            if (leftover(app).isNotEmpty()) {
                _state.value = if (prefs(app).getString(PREF_REASON, null) == REASON_IDLE) State(idleOff = true) else State(paused = true)
            }
            supervisor = scope.launch {
                while (isActive) {
                    try { coordinate(app) }
                    catch (e: CancellationException) { throw e }
                    catch (t: Throwable) { Log.w(TAG, "watcher restarting", t); delay(5_000) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ coordinator: events only, no polling

    private suspend fun coordinate(ctx: Context) = coroutineScope {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { wake.trySend(Unit); pollWake.trySend(Unit) }
        }
        ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, obs)
        val watch = launch { ConnectionHub.status.collect { wake.trySend(Unit) } }
        try {
            var noDeviceSince = 0L
            // If the process was killed and restarted while no device was around, the minute has partly run already.
            var carriedAge = (System.currentTimeMillis() - prefs(ctx).getLong(PREF_LAST_CONNECTED, 0L)).coerceIn(0L, IDLE_MS)
            if (prefs(ctx).getLong(PREF_LAST_CONNECTED, 0L) == 0L) carriedAge = 0L
            var wasConnected = connected()
            if (wasConnected) prefs(ctx).edit().putLong(PREF_LAST_CONNECTED, System.currentTimeMillis()).apply()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val isConn = connected()
                if (isConn != wasConnected) {
                    // The moment a device went away (or came): what a restarted watcher counts from.
                    prefs(ctx).edit().putLong(PREF_LAST_CONNECTED, System.currentTimeMillis()).apply()
                    wasConnected = isConn
                    carriedAge = 0L
                }
                val st = _state.value
                val plan = IdleLogic.decide(
                    enabled = isEnabled(ctx), connected = connected(), paused = st.paused, idleOff = st.idleOff,
                    hasEntries = ourEntries(ctx).isNotEmpty(), noDeviceForMs = if (noDeviceSince == 0L) 0L else now - noDeviceSince,
                )
                when (plan) {
                    IdleLogic.Plan.Disabled -> { noDeviceSince = 0; stopPoll(); if (st.ownOff) restoreEntries(ctx); wake.receive() }
                    IdleLogic.Plan.Active -> {
                        noDeviceSince = 0
                        if (st.idleOff && !restoreEntries(ctx)) { withTimeoutOrNull(5_000) { wake.receive() }; continue }
                        armPoll(ctx)
                        wake.receive()
                    }
                    IdleLogic.Plan.AdoptManual -> { noDeviceSince = 0; adoptManual(ctx) }
                    IdleLogic.Plan.Sleep -> { noDeviceSince = 0; wake.receive() }
                    IdleLogic.Plan.Wait -> {
                        if (noDeviceSince == 0L) noDeviceSince = now - minOf(carriedAge, IDLE_MS - START_GRACE_MS)
                        val left = IDLE_MS - (now - noDeviceSince)
                        if (left > 0) withTimeoutOrNull(left) { wake.receive() }
                    }
                    IdleLogic.Plan.GoIdle -> {
                        noDeviceSince = 0; carriedAge = 0L
                        if (!enterIdleOff(ctx)) wake.receive()
                    }
                }
            }
        } finally {
            watch.cancel()
            try { ctx.contentResolver.unregisterContentObserver(obs) } catch (_: Exception) {}
        }
    }

    private fun armPoll(ctx: Context) {
        if (pollJob?.isActive == true) return
        synchronized(lock) {
            if (pollJob?.isActive == true) return
            pollJob = scope.launch {
                while (isActive) {
                    try { poll(ctx); return@launch }
                    catch (e: CancellationException) { throw e }
                    catch (t: Throwable) { Log.w(TAG, "poll restarting", t); delay(5_000) }
                }
            }
        }
    }

    private fun stopPoll() { pollJob?.cancel(); pollJob = null }

    // ------------------------------------------------------------------ poll: only while a device is connected

    /** Watches for a payment app in front. Returns (and the job ends) when the services went off for lack of a device or the switch is off. */
    private suspend fun poll(ctx: Context) {
        val power = ctx.getSystemService(PowerManager::class.java)
        val stats = ctx.getSystemService(UsageStatsManager::class.java)
        var fg = Front(null, null)
        var beat = 0L
        var since = System.currentTimeMillis() - LOOKBACK_MS
        var leftAt = 0L
        while (scope.isActive) {
            if (_state.value.idleOff || !isEnabled(ctx)) return
            val watching = hasUsageAccess(ctx)
            val paused = _state.value.paused
            val ours = ourEntries(ctx).isNotEmpty()
            val t0 = System.currentTimeMillis()
            if (t0 - beat > 30_000) {
                beat = t0
                Log.i(TAG, "watching=$watching front=${fg.pkg} ourServicesOn=$ours paused=$paused secure=${canSwitchSecurely(ctx)} serviceRunning=${AirBridgeService.isRunning} connected=${connected()}")
            }
            if (!watching) {
                if (paused) resume(ctx)
                fg = Front(null, null); since = System.currentTimeMillis() - LOOKBACK_MS
                delay(15_000)
                continue
            }
            if (!paused && !ours) {
                // Nothing to switch off: sleep until the services list changes (or a minute passes).
                fg = Front(null, null); since = System.currentTimeMillis() - LOOKBACK_MS
                withTimeoutOrNull(60_000) { pollWake.receive() }
                continue
            }
            PaymentApps.extra = prefs(ctx).getString("PAYMENT_PAUSE_EXTRA", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            if (power?.isInteractive == false) {
                // Screen off: nothing can be in front. Put the services back if they were off for a payment, then sleep
                // until the screen is on again instead of polling.
                if (paused) resume(ctx)
                fg = Front(null, null)
                awaitScreenOn(ctx, power)
                since = System.currentTimeMillis() - LOOKBACK_MS
                continue
            }
            val now = System.currentTimeMillis()
            fg = foreground(stats, since - OVERLAP_MS, now, fg)
            since = now
            val pay = PaymentApps.isPayment(fg.pkg)
            if (pay) {
                leftAt = 0
                // Every tick, not once: at process start the services may not have connected yet, and one may bind late.
                if (ours) pause(ctx, fg.pkg!!)
            } else if (_state.value.paused) {
                val n = System.currentTimeMillis()
                if (leftAt == 0L) leftAt = n
                if (n - leftAt >= GRACE_MS) { resume(ctx); leftAt = 0 }
            }
            delay(if (pay || _state.value.paused) 350 else 700)
        }
    }

    private suspend fun awaitScreenOn(ctx: Context, power: PowerManager?) {
        var cont: kotlinx.coroutines.CancellableContinuation<Unit>? = null
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { cont?.let { if (it.isActive) it.resume(Unit) } }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            suspendCancellableCoroutine { c ->
                cont = c
                // The screen may have come on between the check and the registration.
                if (power?.isInteractive != false && c.isActive) c.resume(Unit)
            }
        } finally {
            try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
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
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    1 /* ACTIVITY_RESUMED / MOVE_TO_FOREGROUND */ -> fg = Front(e.packageName, e.className)
                    2 /* ACTIVITY_PAUSED / MOVE_TO_BACKGROUND */ -> if (e.packageName == fg.pkg && e.className == fg.cls) fg = Front(null, null)
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "usage events", t) }
        return fg
    }

    // ------------------------------------------------------------------ switching

    private fun ourEntries(ctx: Context): List<String> =
        EnabledServices.ours(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ctx.packageName)

    private fun leftover(ctx: Context): List<String> = EnabledServices.split(prefs(ctx).getString(PREF_REMOVED, null))

    /** The entries AirBridge took out itself (payment app, no device) and will put back. */
    fun removedEntries(ctx: Context): List<String> = leftover(ctx)

    /** A payment app is in front: services off. */
    private fun pause(ctx: Context, app: String) = synchronized(lock) {
        val cr = ctx.contentResolver
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val (next, removed) = EnabledServices.remove(cur, ctx.packageName)
        if (removed.isEmpty()) return@synchronized
        // Switching our own entries only works while something keeps this process alive to switch them back.
        if (canSwitchSecurely(ctx) && AirBridgeService.isRunning) {
            // On disk first: if we die between the two steps the next start still knows what to put back.
            val all = (leftover(ctx) + removed).distinct()
            prefs(ctx).edit().putString(PREF_REMOVED, all.joinToString(":")).putString(PREF_REASON, REASON_PAYMENT).commit()
            val ok = try { Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next) } catch (_: Exception) { false }
            if (ok) {
                Log.i(TAG, "paused ${removed.size} service(s) for $app")
                _state.value = State(paused = true, app = app)
                return@synchronized
            }
            if (leftover(ctx).isEmpty() || all == removed) prefs(ctx).edit().remove(PREF_REMOVED).remove(PREF_REASON).apply()
        }
        // No way back by ourselves: turn them off, and tell the user how to turn them on once the payment is done.
        val a = ControlAccessibilityService.turnOff()
        val b = TypingAccessibilityService.turnOff()
        if (!a && !b) return@synchronized // not connected yet (process just started): try again on the next tick
        Log.i(TAG, "switched off for $app (user turns back on)")
        fallbackNotify = true
        _state.value = State(paused = true, app = app)
    }

    /** The payment app left. Back on, unless no device is connected: then they stay off (the no-device rule). */
    private fun resume(ctx: Context) = synchronized(lock) {
        if (!connected() && leftover(ctx).isNotEmpty() && canSwitchSecurely(ctx)) {
            prefs(ctx).edit().putString(PREF_REASON, REASON_IDLE).commit()
            _state.value = State(idleOff = true)
            Log.i(TAG, "payment app left, no device connected: services stay off")
            return@synchronized
        }
        if (!restoreEntries(ctx)) return@synchronized // try again on the next tick
        if (fallbackNotify) { fallbackNotify = false; notifyTurnBackOn(ctx) }
    }

    /** Puts our entries back (what [leftover] says) and clears the state. False if it could not and should be retried. */
    private fun restoreEntries(ctx: Context): Boolean = synchronized(lock) {
        val cr = ctx.contentResolver
        val removed = leftover(ctx)
        if (removed.isNotEmpty()) {
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val next = EnabledServices.restore(cur, removed)
            val ok = try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next) &&
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } catch (t: Throwable) { Log.w(TAG, "restore refused", t); false }
            if (ok) {
                prefs(ctx).edit().remove(PREF_REMOVED).remove(PREF_REASON).apply()
                Log.i(TAG, "restored ${removed.size} service(s)")
            } else if (!canSwitchSecurely(ctx)) {
                prefs(ctx).edit().remove(PREF_REMOVED).remove(PREF_REASON).apply(); fallbackNotify = true
            } else return@synchronized false
        }
        _state.value = State()
        true
    }

    /** No device for a minute: services off until one connects. False if nothing was done. */
    private fun enterIdleOff(ctx: Context): Boolean = synchronized(lock) {
        if (!canSwitchSecurely(ctx)) { Log.i(TAG, "no device for a minute, but switching back on needs WRITE_SECURE_SETTINGS: left as is"); return@synchronized false }
        if (connected() || _state.value.paused) return@synchronized false
        val cr = ctx.contentResolver
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val (next, removed) = EnabledServices.remove(cur, ctx.packageName)
        if (removed.isEmpty()) return@synchronized false
        val all = (leftover(ctx) + removed).distinct()
        prefs(ctx).edit().putString(PREF_REMOVED, all.joinToString(":")).putString(PREF_REASON, REASON_IDLE).commit()
        val ok = try { Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next) } catch (_: Exception) { false }
        if (!ok) { prefs(ctx).edit().remove(PREF_REMOVED).remove(PREF_REASON).apply(); return@synchronized false }
        Log.i(TAG, "no device connected for a minute: ${removed.size} service(s) off until one connects")
        _state.value = State(idleOff = true)
        stopPoll()
        true
    }

    /** The user switched our services on by hand while they were off for lack of a device: that is the new normal. */
    private fun adoptManual(ctx: Context) = synchronized(lock) {
        prefs(ctx).edit().remove(PREF_REMOVED).remove(PREF_REASON).apply()
        _state.value = State()
    }

    private fun notifyTurnBackOn(ctx: Context) {
        try {
            val pi = PendingIntent.getActivity(
                ctx, NOTIF_ID, com.shazan.airbridge.control.accessibility.AccessibilityLink.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
