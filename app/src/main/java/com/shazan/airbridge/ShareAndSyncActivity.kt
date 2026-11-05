package com.shazan.airbridge

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutManagerCompat
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.PeerInfo
import com.shazan.airbridge.ui.theme.AirBridgeTheme
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.PlatformAvatar
import com.shazan.airbridge.ui.theme.platformLabel

/**
 * Invisible activity that reads the clipboard while focused and pushes it (tile, notification action,
 * and the fallback when [com.shazan.airbridge.net.ClipFocusWindow] cannot take focus: bubble, `copy`,
 * automatic sync). Android 10+ only lets the focused app read the clipboard.
 */
class SyncActivity : Activity() {
    private var done = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val reason = intent.getStringExtra(ClipboardSync.EXTRA_REASON) ?: "manual"
        ClipboardSync.onFocusedRead(ClipboardSync.readClip(), reason)
        finishQuietly()
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }
}

/** Share sheet target: send files (xfer) or text (clip) to one connected peer. */
class ShareActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connected = Hub.peers()
        val direct = DirectShare.peerIdFromShortcut(intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        if (direct != null) {
            val peer = connected.firstOrNull { it.id == direct }
            if (peer != null) share(peer)
            else {
                toast("Device is not connected right now")
                if (AirBridgeService.shouldRun(this)) AirBridgeService.start(this)
            }
            finish(); return
        }
        when (connected.size) {
            0 -> {
                toast("No devices connected")
                if (AirBridgeService.shouldRun(this)) AirBridgeService.start(this)
                finish(); return
            }
            1 -> { share(connected[0]); finish(); return }
        }

        setContent {
            AirBridgeTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { finish() },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.xl),
                        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                    ) {
                        Text("Send to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Dimens.sm, bottom = Dimens.sm))
                        connected.forEach { peer ->
                            Surface(
                                onClick = { share(peer); finish() },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                InfoRow(title = peer.name, subtitle = platformLabel(peer.platform), leading = { PlatformAvatar(peer.platform) })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun share(peer: PeerInfo) {
        ShortcutManagerCompat.reportShortcutUsed(this, DirectShare.shortcutId(peer.id))
        val uris = sharedUris()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uris.isNotEmpty() -> {
                if ("xfer" !in peer.caps) { toast("${peer.name} doesn't accept files"); return }
                toast("Sending ${uris.size} file(s) to ${peer.name}…")
                // Hand the URIs (and our read grant) to the long-lived service.
                val clip = ClipData.newRawUri("files", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                startService(
                    Intent(this, AirBridgeService::class.java)
                        .setAction(AirBridgeService.ACTION_SEND_FILES)
                        .putExtra(AirBridgeService.EXTRA_PEER, peer.id)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .also { it.clipData = clip }
                )
            }
            !text.isNullOrBlank() ->
                if (ClipboardSync.sendTextTo(peer.id, text)) toast("Sent to ${peer.name}") else toast("${peer.name} is not connected")
            else -> toast("Nothing to share")
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
        Intent.ACTION_SEND ->
            listOfNotNull(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM))
        else -> emptyList()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
