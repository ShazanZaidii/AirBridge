package com.shazan.airbridge.mirror

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service of type `mediaProjection` that exists only while this phone shows its screen on a
 * computer (§8.7 "Phone as source"). Started from [MirrorConsentActivity] right after the user allowed the
 * capture; it calls `startForeground` first and only then turns the consent token into a MediaProjection
 * (required on Android 14+). The ongoing notification says where the screen goes and has a Stop action.
 */
class MirrorService : Service() {
    companion object {
        private const val TAG = "AirBridgeMirrorSvc"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, resultCode: Int, data: Intent, computer: String) {
            val i = Intent(ctx, MirrorService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_NAME, computer)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, MirrorService::class.java)) } catch (_: Throwable) {}
        }
    }

    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        if (data == null || started || Build.VERSION.SDK_INT < 29) {
            if (!started) { PhoneMirror.onProjectionFailed(); stopSelf() }
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, PhoneMirror.NOTIF_ONGOING, PhoneMirror.ongoingNotification(this, name),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground(mediaProjection) failed", t)
            PhoneMirror.onProjectionFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        val mp = try {
            getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, data)
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaProjection failed", t)
            null
        }
        if (mp == null) {
            PhoneMirror.onProjectionFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        PhoneMirror.onProjection(mp)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (started) PhoneMirror.onServiceDestroyed()
        super.onDestroy()
    }
}
