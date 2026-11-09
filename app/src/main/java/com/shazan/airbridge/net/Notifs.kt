package com.shazan.airbridge.net

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.shazan.airbridge.MainActivity

object Notifs {
    const val CH_SERVICE = "airbridge_service"
    const val CH_XFER = "airbridge_transfers"
    const val CH_LIVE = "airbridge_transfers_live"
    const val CH_RESULT = "airbridge_results"
    const val CH_PAIR = "airbridge_pairing"
    const val CH_CONTROL = "airbridge_control"

    const val ID_SERVICE = 1
    const val ID_NEARBY_REQUEST = 2
    const val ID_CONTROL_UNAVAILABLE = 3

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, ctx.getString(com.shazan.airbridge.R.string.ch_service), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_XFER, ctx.getString(com.shazan.airbridge.R.string.ch_xfer), NotificationManager.IMPORTANCE_LOW),
                // Transfers in progress: pops up once when a transfer starts (no sound), then updates quietly.
                NotificationChannel(CH_LIVE, ctx.getString(com.shazan.airbridge.R.string.ch_live), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null); enableVibration(false)
                },
                NotificationChannel(CH_RESULT, ctx.getString(com.shazan.airbridge.R.string.ch_result), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_PAIR, ctx.getString(com.shazan.airbridge.R.string.ch_pair), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_CONTROL, ctx.getString(com.shazan.airbridge.R.string.ch_control), NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        // Channel ids used by v1 builds.
        listOf("AirBridgeChannel", "transfer_channel").forEach { nm.deleteNotificationChannel(it) }
    }

    @SuppressLint("MissingPermission")
    fun notify(id: Int, n: Notification) {
        try { NotificationManagerCompat.from(Hub.app).notify(id, n) } catch (_: SecurityException) {}
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(Hub.app).cancel(id)

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
