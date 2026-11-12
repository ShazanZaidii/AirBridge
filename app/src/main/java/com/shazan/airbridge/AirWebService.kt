package com.shazan.airbridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shazan.airbridge.net.Notifs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the one AirWeb session alive while no screen is open, so pressing Back doesn't end it and sharing more
 * files from the gallery or another app later joins the same server (same link, same pool, same port).
 *
 * It also holds the read grants of the files shared into AirWeb: a grant that came with the sharing screen would
 * end with it, one that came with this service's start intent lasts until the service stops.
 */
class AirWebService : Service() {
    companion object {
        private const val TAG = "AirBridge.AirWebSvc"
        private const val ACTION_STOP = "com.shazan.airbridge.action.AIRWEB_STOP"
        const val ID_NOTIFICATION = 4

        @Volatile var isRunning = false
            private set

        /** Starts (or joins) the session and hands over [uris] with their read grant. */
        fun start(ctx: Context, uris: List<Uri> = emptyList()) {
            val i = Intent(ctx, AirWebService::class.java)
            if (uris.isNotEmpty()) {
                i.clipData = ClipData.newRawUri("files", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                if (isRunning) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // e.g. started from the background without being allowed to: the screen still works without it.
                Log.w(TAG, "cannot start service", e)
            }
        }

        /** Ends the session: the server stops, the link dies and the shared list is emptied. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AirWebService::class.java))
            // No service (never started, or it was not allowed to): still end the session.
            if (!isRunning) AirWebSession.end()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Notifs.init(this)
        val model = AirWebSession.get(this)
        if (!enterForeground(notification(0, null))) { stopSelf(); return }
        model.ensureStarted()
        scope.launch {
            combine(model.peers, model.address, model.port, model.offered) { p, a, port, o -> Triple(p.size, a?.takeIf { port != 0 }?.let { "$it:$port" }, o.size) }
                .collect { (peers, addr, _) -> Notifs.notify(ID_NOTIFICATION, notification(peers, addr)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // Every startForegroundService() must be answered with startForeground().
        enterForeground(notification(AirWebSession.peek()?.peers?.value?.size ?: 0, null))
        val clip = intent?.clipData
        val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        if (uris.isNotEmpty()) AirWebSession.get(this).add(uris)
        return START_NOT_STICKY
    }

    private fun enterForeground(n: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID_NOTIFICATION, n, type)
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    private fun notification(peers: Int, addr: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 2, AirWebActivity.intent(this, emptyList()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, AirWebService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.aw_notif_title))
            .setContentText(
                if (peers > 0) resources.getQuantityString(R.plurals.aw_connected, peers, peers)
                else addr ?: getString(R.string.aw_notif_waiting)
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.aw_stop), stop)
            .build()
    }

    /** Android 15+ FGS timeout; connectedDevice has none today, but never get killed for ignoring it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout (type $fgsType)")
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        AirWebSession.end()
        super.onDestroy()
    }
}
