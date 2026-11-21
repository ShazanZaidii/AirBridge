package com.shazan.airbridge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.shazan.airbridge.app.AirBridgeService
import com.shazan.airbridge.app.AppLanguage
import com.shazan.airbridge.clipboard.ClipboardSync
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.Identity
import com.shazan.airbridge.connection.discovery.Discovery
import com.shazan.airbridge.connection.transport.WsClient
import com.shazan.airbridge.ui.AirBridgeRoot
import com.shazan.airbridge.ui.theme.AirBridgeTheme

fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
/** An `airbridge://pair?...` link opened from another app (tapped link, shared QR text); consumed by [AirBridgeRoot]. */
internal val pendingPairLink = mutableStateOf<String?>(null)
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark: light status / navigation bar icons whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // A pairing link is one-shot: only a fresh launch carries it, and it is removed from the activity's intent so a
        // process restart, rotation or recreate never re-runs an old link (which would fail and drop the connection).
        if (savedInstanceState == null) {
            intent?.dataString?.takeIf { it.startsWith("airbridge://") }?.let { pendingPairLink.value = it }
        }
        intent?.data = null
        // Browse mDNS while visible (discovery list, QR address lookup); the service keeps its own reference.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { Discovery.acquire(); com.shazan.airbridge.connection.transport.LanHost.acquire() }
            override fun onStop(owner: LifecycleOwner) { com.shazan.airbridge.connection.transport.LanHost.release(); Discovery.release() }
        })
        setContent {
            AirBridgeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AirBridgeRoot() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.startsWith("airbridge://") }?.let { pendingPairLink.value = it }
        intent.data = null
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The phone may have been renamed in system settings while we were away.
        if (Identity.refreshDefault()) ConnectionHub.broadcastName()
        if (AirBridgeService.isRunning) WsClient.retryAllNow(unpause = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipboardSync.onAppFocused(this)
    }
}
