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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.PeerInfo

/**
 * Invisible activity that reads the clipboard while focused and pushes it. This is the only
 * legal way to read the clipboard from the background on Android 10+ (tile, bubble, `copy`).
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
        val text = ClipboardSync.readText()
        if (text != null) ClipboardSync.pushText(text, reason)
        else if (reason != "copy") Toast.makeText(this, "Clipboard has no text", Toast.LENGTH_SHORT).show()
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
            MaterialTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheetState, containerColor = Color(0xFF1E1E1E)) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp, start = 24.dp, end = 24.dp)) {
                        Text("Send via AirBridge", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.padding(bottom = 16.dp))
                        connected.forEach { peer ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { share(peer); finish() },
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Image(painterResource(DirectShare.platformIcon(peer.platform)), null, Modifier.size(28.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(peer.name, color = Color.White, fontSize = 18.sp)
                                }
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
