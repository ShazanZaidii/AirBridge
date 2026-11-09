package com.shazan.airbridge.promirror

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shazan.airbridge.R
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.Notifs
import com.shazan.airbridge.net.Session
import org.json.JSONObject

/**
 * Helper for the computer's "Pro mirroring" (PROTOCOL.md §8.9): the computer mirrors this phone over Android's
 * own Wireless debugging (adb), not through AirBridge. This phone app only tells the computer whether
 * Developer options and Wireless debugging are on, and opens the right Settings page when asked, so the
 * user doesn't have to dig for it (many phones switch Wireless debugging off after a restart or a Wi-Fi change).
 *
 * `{"t":"adb_wifi","op":"status"|"open"}` → `{"t":"adb_wifi_state","dev":…,"wifi":…,"sdk":…,"opened":…}`.
 */
object AdbWifi {
    private const val TAG = "AirBridgeAdbWifi"
    const val CAP = "adb_wifi"
    private const val NOTIF_ID = 4731
    /** Highlights the Wireless debugging row in AOSP Settings (ignored where unsupported). */
    private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"

    /** Wireless debugging exists from Android 11. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    fun onMessage(s: Session, m: JSONObject) {
        if (!supported) return
        val ctx = Hub.app
        when (m.optString("op")) {
            "status" -> s.send(state(ctx, opened = null))
            "open" -> {
                val opened = open(ctx)
                s.send(state(ctx, opened))
            }
        }
    }

    private fun state(ctx: Context, opened: String?): JSONObject {
        val dev = developerOptions(ctx)
        val wifi = wirelessDebugging(ctx)
        return JSONObject().put("t", "adb_wifi_state").put("dev", dev).put("sdk", Build.VERSION.SDK_INT).apply {
            if (wifi != null) put("wifi", wifi)
            if (opened != null) put("opened", opened)
        }
    }

    private fun developerOptions(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) { false }

    /** `adb_wifi_enabled` is not public API: null when this Android won't tell us. */
    private fun wirelessDebugging(ctx: Context): Boolean? = try {
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled") == 1
    } catch (_: Settings.SettingNotFoundException) { false } catch (_: Exception) { null }

    /** Opens Developer options (or About phone while they are still hidden). Returns how it reached the user. */
    private fun open(ctx: Context): String {
        val intent = settingsIntent(developerOptions(ctx))
        val plan = AdbWifiPolicy.openPlan(
            canDrawOverlays = try { Settings.canDrawOverlays(ctx) } catch (_: Exception) { false },
            accessibilityRunning = try { com.shazan.airbridge.AirBridgeInputService.isRunning } catch (_: Throwable) { false },
        )
        if (plan == AdbWifiPolicy.Open.DIRECT) {
            try {
                ctx.startActivity(intent)
                return "direct"
            } catch (e: Exception) {
                Log.w(TAG, "direct open failed", e)
            }
        }
        notifyOpen(ctx, intent)
        return "notification"
    }

    private fun settingsIntent(devOptions: Boolean): Intent =
        (if (devOptions) Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY)
         else Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    @SuppressLint("MissingPermission")
    private fun notifyOpen(ctx: Context, intent: Intent) {
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, Notifs.CH_CONTROL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(ctx.getString(R.string.promirror_notif_title))
            .setContentText(ctx.getString(R.string.promirror_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        Notifs.notify(NOTIF_ID, n)
    }
}

/** Pure decisions, unit-tested. */
object AdbWifiPolicy {
    enum class Open { DIRECT, NOTIFICATION }

    /**
     * Android blocks activity starts from the background unless the app may draw over other apps or has a
     * bound accessibility service; otherwise a notification the user taps is the only reliable way.
     */
    fun openPlan(canDrawOverlays: Boolean, accessibilityRunning: Boolean): Open =
        if (canDrawOverlays || accessibilityRunning) Open.DIRECT else Open.NOTIFICATION
}
