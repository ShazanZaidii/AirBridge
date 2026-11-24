package com.shazan.airbridge.transfer

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import com.shazan.airbridge.R
import com.shazan.airbridge.app.Notifs
import java.util.concurrent.ConcurrentHashMap

/**
 * The notification shown while a file is being sent or received.
 *
 * - It pops up (heads-up) once when the transfer starts, then updates silently.
 * - On Android 16+ it is a Live Update: a progress chip in the status bar and on the lock screen
 *   (promoted ongoing notification with ProgressStyle), like a live activity.
 * - It can't be cleared while the transfer runs: it is ongoing, and if the system lets the user swipe
 *   it away anyway (Android 14+ allows that for ongoing notifications) it is posted again. Once the
 *   transfer ends it turns into a normal, dismissible result.
 */
object TransferNotifs {
    private const val ACTION_DELETED = "com.shazan.airbridge.action.XFER_NOTIF_DELETED"
    private const val EXTRA_ID = "id"

    /** Last progress notification per id, while the transfer runs (to put it back if swiped away). */
    private val live = ConcurrentHashMap<Int, Notification>()
    private val lastPost = ConcurrentHashMap<Int, Long>()
    /** Ids whose Cancel was confirmed: no more progress notifications for them, only "Cancelling…" then the result. */
    private val cancelling = ConcurrentHashMap.newKeySet<Int>()

    fun progress(
        ctx: Context, id: Int, incoming: Boolean, peer: String, file: String, filesDone: Int, filesTotal: Int,
        done: Long, total: Long, bps: Long, cancel: NotificationCompat.Action,
    ) {
        if (id in cancelling) return
        val first = !live.containsKey(id)
        // Updates at most once a second (fewer re-posts, and a Cancel tap never lands on a notification being rebuilt);
        // the first one and 100 % always go out.
        val now = System.currentTimeMillis()
        if (!first && done < total && now - (lastPost[id] ?: 0L) < 1000) return
        lastPost[id] = now

        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val title = ctx.getString(if (incoming) R.string.receiving_from else R.string.sending_to, peer)
        val counter = if (filesTotal > 1) ctx.getString(R.string.xn_file_of, minOf(filesDone + 1, filesTotal), filesTotal) + " · " else ""
        val text = file + " · " + counter + formatSpeed(bps) + eta(ctx, done, total, bps)

        val b = NotificationCompat.Builder(ctx, Notifs.CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("$pct%")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // pop up once, then update quietly
            .setContentIntent(Notifs.openAppIntent(ctx))
            .setDeleteIntent(deleteIntent(ctx, id))
            .addAction(cancel)
            .setRequestPromotedOngoing(true) // Android 16 Live Update (status bar chip, lock screen)
            .setShortCriticalText("$pct%")
        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(pct)
                    .setProgressIndeterminate(total == 0L)
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(ACCENT)))
                    .setProgressTrackerIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.drawable.ic_stat_name)),
            )
        } else {
            b.setProgress(100, pct, total == 0L)
        }
        val n = b.build()
        live[id] = n
        Notifs.notify(id, n)
    }

    /** Cancel was confirmed: show it at once (the partial file is deleted afterwards), without the Cancel button. */
    fun cancelling(ctx: Context, id: Int, incoming: Boolean, peer: String) {
        cancelling.add(id)
        val n = NotificationCompat.Builder(ctx, Notifs.CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(ctx.getString(if (incoming) R.string.receiving_from else R.string.sending_to, peer))
            .setContentText(ctx.getString(R.string.xfer_cancelling))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(Notifs.openAppIntent(ctx))
            .setDeleteIntent(deleteIntent(ctx, id))
            .build()
        live[id] = n
        Notifs.notify(id, n)
    }

    /** The transfer ended: replace the live notification with a dismissible result. */
    /** [detail] is a second line shown when the notification is expanded (where received files went). */
    fun finished(ctx: Context, id: Int, title: String, text: String, open: PendingIntent?, detail: String? = null) {
        cancelling.remove(id)
        live.remove(id)
        lastPost.remove(id)
        val b = NotificationCompat.Builder(ctx, Notifs.CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
        if (detail != null) b.setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$detail"))
        val n = b
            .setAutoCancel(true)
            .setContentIntent(open ?: Notifs.openAppIntent(ctx))
            .build()
        Notifs.notify(id, n)
    }

    /** "Report.pdf · 1.1 GB · 22 sec" for a finished transfer: what, how big, how long it took (in the user's language). */
    fun summary(ctx: Context, what: String, bytes: Long, tookMs: Long): String =
        listOf(what, android.text.format.Formatter.formatShortFileSize(ctx, bytes), took(tookMs)).joinToString(" · ")

    /** "4.2 sec", "22 sec", "1 min, 5 sec", "1 hr, 2 min". */
    fun took(ms: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val f = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT)
        val sec = android.icu.util.MeasureUnit.SECOND
        val min = android.icu.util.MeasureUnit.MINUTE
        val s = ms.coerceAtLeast(0) / 1000.0
        if (s < 10) return f.format(android.icu.util.Measure(java.math.BigDecimal(s).setScale(1, java.math.RoundingMode.HALF_UP), sec))
        val whole = Math.round(s)
        return when {
            whole < 60 -> f.format(android.icu.util.Measure(whole, sec))
            whole < 3600 -> if (whole % 60 == 0L) f.format(android.icu.util.Measure(whole / 60, min))
                else f.formatMeasures(android.icu.util.Measure(whole / 60, min), android.icu.util.Measure(whole % 60, sec))
            else -> f.formatMeasures(android.icu.util.Measure(whole / 3600, android.icu.util.MeasureUnit.HOUR), android.icu.util.Measure(whole % 3600 / 60, min))
        }
    }

    /** "· 12 s left" once the speed is known. */
    private fun eta(ctx: Context, done: Long, total: Long, bps: Long): String {
        if (bps <= 0 || total <= 0 || done >= total) return ""
        val s = ((total - done) / bps).coerceAtLeast(1)
        return " · " + if (s < 60) ctx.getString(R.string.xn_sec_left, s.toInt()) else ctx.getString(R.string.xn_min_left, (s / 60).toInt() + 1)
    }

    private fun deleteIntent(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, Deleted::class.java).setAction(ACTION_DELETED).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Swiped away while still running: put it straight back (quietly). */
    class Deleted : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DELETED) return
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val n = live[id] ?: return
            Notifs.notify(id, n)
        }
    }

    private val ACCENT = "#E6E6E6".toColorInt()
}
