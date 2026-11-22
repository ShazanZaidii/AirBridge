package com.shazan.airbridge.control.accessibility

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shazan.airbridge.R
import com.shazan.airbridge.app.Notifs
import com.shazan.airbridge.payment.EnabledServices
import com.shazan.airbridge.payment.PaymentPause

/**
 * Keeps AirBridge's accessibility services on across app updates.
 *
 * Android (and some phone makers' versions of it) can drop an app's accessibility services when the app is replaced, and the
 * app can't see it happen because it isn't running then. So AirBridge remembers which of its services you had on, kept up to
 * date while it runs, and the first time it starts after an update (Android starts it right away) it looks for services that
 * went missing:
 *  - with the "auto turn on" permission (WRITE_SECURE_SETTINGS) it puts them back itself;
 *  - without it, one notification opens the exact setting to switch them back on.
 * A service you switched off yourself is never switched back on: that removal is seen while AirBridge runs and remembered.
 * Services AirBridge took out on purpose (payment apps, no device) are left to PaymentPause.
 */
object A11yKeeper {
    private const val TAG = "AirBridge.A11yKeeper"
    private const val PREFS = "AirBridgePrefs"
    private const val PREF_WANTED = "A11Y_WANTED"
    private const val PREF_SEEN_UPDATE = "A11Y_SEEN_UPDATE_TIME"
    private const val NOTIF_ID = 4417

    private val worker: Handler by lazy { Handler(HandlerThread("a11y-keeper").apply { start() }.looper) }
    @Volatile private var observing = false

    /** Services from [wanted] that are missing from [present], not counting the ones AirBridge took out itself ([removedByUs]). Pure. */
    fun missing(wanted: List<String>, present: List<String>, removedByUs: List<String>): List<String> {
        val have = (present + removedByUs).map(EnabledServices::normal).toSet()
        return wanted.filter { EnabledServices.normal(it) !in have }.distinctBy(EnabledServices::normal)
    }

    /** Whether this start is the first one after an update: the install time moved since the last start we saw. Pure. */
    fun isFirstStartAfterUpdate(seenUpdateTime: Long, lastUpdateTime: Long): Boolean = seenUpdateTime != 0L && seenUpdateTime != lastUpdateTime

    /** Call from Application.onCreate. Cheap; the work runs on its own thread. */
    fun onAppStart(ctx: Context) {
        val app = ctx.applicationContext
        worker.post {
            try { check(app) } catch (t: Throwable) { Log.w(TAG, "check", t) }
            observe(app)
        }
    }

    private fun check(ctx: Context) {
        val p = prefs(ctx)
        val lastUpdate = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        val wanted = EnabledServices.split(p.getString(PREF_WANTED, null))
        if (isFirstStartAfterUpdate(p.getLong(PREF_SEEN_UPDATE, 0L), lastUpdate)) {
            val gone = missing(wanted, present(ctx), PaymentPause.removedEntries(ctx))
            if (gone.isNotEmpty()) heal(ctx, gone)
        }
        p.edit().putLong(PREF_SEEN_UPDATE, lastUpdate).apply()
        // Remember what is on now (plus what AirBridge itself took out), but never forget a wanted service at start-up:
        // it may be the one the update dropped and the heal could not put back.
        record(ctx, keep = wanted)
    }

    private fun heal(ctx: Context, gone: List<String>) {
        if (PaymentPause.canSwitchSecurely(ctx)) {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val ok = try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, EnabledServices.restore(cur, gone)) &&
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } catch (t: Throwable) { Log.w(TAG, "put back refused", t); false }
            Log.i(TAG, "after the update, ${gone.size} service(s) were off: ${if (ok) "put back" else "could not put back"}")
            if (ok) return
        }
        Log.i(TAG, "after the update, ${gone.size} service(s) were off: asking the user")
        notifyTurnBackOn(ctx)
    }

    /** While AirBridge runs, every change you make in Settings is remembered (so switching a service off sticks). */
    private fun observe(ctx: Context) {
        if (observing) return
        observing = true
        val obs = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) { try { record(ctx, keep = emptyList()) } catch (t: Throwable) { Log.w(TAG, "record", t) } }
        }
        try {
            ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, obs)
        } catch (t: Throwable) { Log.w(TAG, "observe", t); observing = false }
    }

    private fun record(ctx: Context, keep: List<String>) {
        val now = (present(ctx) + PaymentPause.removedEntries(ctx) + keep).distinctBy(EnabledServices::normal)
        prefs(ctx).edit().putString(PREF_WANTED, now.joinToString(":")).apply()
    }

    private fun present(ctx: Context): List<String> =
        EnabledServices.ours(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ctx.packageName)

    private fun notifyTurnBackOn(ctx: Context) {
        try {
            val pi = PendingIntent.getActivity(
                ctx, NOTIF_ID, AccessibilityLink.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = ctx.getString(R.string.a11y_after_update_text)
            val n = NotificationCompat.Builder(ctx, Notifs.CH_CONTROL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(ctx.getString(R.string.pp_notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build()
            Notifs.notify(NOTIF_ID, n)
        } catch (t: Throwable) { Log.w(TAG, "notify", t) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
