package com.shazan.airbridge.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shazan.airbridge.net.Notifs

/**
 * Invisible screen that shows Android's own screen-capture consent dialog for a pending `mirror_start`
 * (required for every session) and hands the result to [PhoneMirror]. Opened directly when Android allows
 * it, otherwise from the "wants to show this phone's screen" notification.
 */
class MirrorConsentActivity : ComponentActivity() {
    companion object {
        private const val TAG = "AirBridgeMirrorConsent"
        fun intent(ctx: Context): Intent = Intent(ctx, MirrorConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) PhoneMirror.onConsent(this, r.resultCode, data)
        else PhoneMirror.onConsentDenied()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated while the system dialog is up: the result comes here
        if (PhoneMirror.pendingComputer() == null) { finish(); return }
        try { Notifs.cancel(PhoneMirror.NOTIF_REQUEST) } catch (_: Throwable) {}
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) { PhoneMirror.onConsentDenied(); finish(); return }
        // Whole screen only: mirroring a single app makes no sense for remote control.
        val ask = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        try {
            capture.launch(ask)
        } catch (t: Throwable) {
            Log.w(TAG, "screen capture prompt failed", t)
            PhoneMirror.onConsentDenied()
            finish()
        }
    }
}
