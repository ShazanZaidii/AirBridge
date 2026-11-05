package com.shazan.airbridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shazan.airbridge.input.OncePer
import com.shazan.airbridge.net.Notifs
import com.shazan.airbridge.net.PairStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Makes "your computer can't control this phone" visible (§8.1 `ctl_leave reason=unavailable`):
 * the dismissible card on the device list and a rate-limited notification after a refused
 * `ctl_enter`. Both open the system accessibility settings.
 */
object RemoteControlSetup {
    private const val TAG = "AirBridgeSetup"
    private const val PREF_DISMISSED = "A11Y_CARD_DISMISSED"
    const val NOTIFY_EVERY_MS = 10 * 60_000L

    private var prefs: SharedPreferences? = null
    private val _dismissed = MutableStateFlow(false)
    /** The user closed the card; shown again after the next refused `ctl_enter`. */
    val cardDismissed: StateFlow<Boolean> = _dismissed
    private val notifyLimit = OncePer(NOTIFY_EVERY_MS)

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        prefs = p
        _dismissed.value = p.getBoolean(PREF_DISMISSED, false)
    }

    fun dismissCard() = setDismissed(true)

    private fun setDismissed(on: Boolean) {
        _dismissed.value = on
        prefs?.edit()?.putBoolean(PREF_DISMISSED, on)?.apply()
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** "Your Mac" / "Your PC" / "Your computer" for the paired devices (or the one that asked). */
    fun controllerNoun(plat: String? = null): String {
        val plats = plat?.let { setOf(it) } ?: try { PairStore.all().map { it.plat }.toSet() } catch (_: Throwable) { emptySet() }
        return when {
            "macos" in plats -> "Your Mac"
            "windows" in plats -> "Your PC"
            "linux" in plats -> "Your computer"
            plats.isNotEmpty() && plats.all { it == "android" } -> "Your other phone"
            else -> "Your computer"
        }
    }

    /** A `ctl_enter` from [plat] was answered `unavailable` because the service is off. */
    fun onRefused(ctx: Context, plat: String?) {
        try {
            setDismissed(false)
            if (!notifyLimit.tryAcquire(System.currentTimeMillis())) return
            val title = "${controllerNoun(plat)} can't control this phone yet"
            val text = "Turn on the AirBridge accessibility service"
            val open = PendingIntent.getActivity(
                ctx, 7, settingsIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n: Notification = NotificationCompat.Builder(ctx, Notifs.CH_CONTROL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text so the cursor can appear and your clicks and typing reach this phone."))
                .setContentIntent(open)
                .addAction(0, "Turn on", open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            Notifs.notify(Notifs.ID_CONTROL_UNAVAILABLE, n)
        } catch (t: Throwable) {
            Log.w(TAG, "unavailable notification failed", t)
        }
    }

    /** The service came on: the reminder is obsolete. */
    fun onServiceOn() {
        try { Notifs.cancel(Notifs.ID_CONTROL_UNAVAILABLE) } catch (_: Throwable) {}
    }
}
