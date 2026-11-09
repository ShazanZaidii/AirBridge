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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

/**
 * Share sheet target: send files (xfer) or text (clip) to one or more connected devices. A Direct
 * Share shortcut sends straight away; so does text with a single connected device. Files always open
 * the sheet, which also offers AirWeb (a link for a device without the app). In the sheet a tap sends
 * to that device; touch and hold starts picking several, and the same items then go to each at once.
 */
class ShareActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connected = Hub.peers()
        val direct = DirectShare.peerIdFromShortcut(intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        if (direct != null) {
            val peer = connected.firstOrNull { it.id == direct }
            if (peer != null) share(listOf(peer))
            else {
                toast(getString(R.string.sh_not_connected))
                if (AirBridgeService.shouldRun(this)) AirBridgeService.start(this)
            }
            finish(); return
        }
        // With files the sheet always opens, so AirWeb (no app needed on the other side) is always there.
        val hasFiles = sharedUris().isNotEmpty()
        if (connected.isEmpty() && AirBridgeService.shouldRun(this)) AirBridgeService.start(this)
        if (!hasFiles) when (connected.size) {
            0 -> {
                toast(getString(R.string.sh_no_devices))
                finish(); return
            }
            1 -> { share(connected); finish(); return }
        }

        setContent {
            AirBridgeTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val haptics = LocalHapticFeedback.current
                // Empty = normal mode (a tap sends). Touch and hold a device to start picking several.
                var chosen by remember { mutableStateOf(emptySet<String>()) }
                val picking = chosen.isNotEmpty()
                ModalBottomSheet(
                    onDismissRequest = { finish() },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.xl),
                        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                    ) {
                        // The Send button lives in the header so the list never moves while picking.
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Dimens.sm, bottom = Dimens.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.sh_send_to), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    when {
                                        picking -> pluralStringResource(R.plurals.sh_selected, chosen.size, chosen.size)
                                        connected.isEmpty() -> stringResource(R.string.sh_no_devices)
                                        connected.size == 1 -> stringResource(R.string.sh_tap_to_send)
                                        else -> stringResource(R.string.sh_hold_to_choose)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (picking) {
                                Button(
                                    onClick = { share(connected.filter { it.id in chosen }); finish() },
                                    shape = MaterialTheme.shapes.medium,
                                ) { Text(stringResource(R.string.sh_send), style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                        connected.forEach { peer ->
                            val on = peer.id in chosen
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .combinedClickable(
                                        onClick = {
                                            if (picking) chosen = if (on) chosen - peer.id else chosen + peer.id
                                            else { share(listOf(peer)); finish() }
                                        },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            chosen = if (on) chosen - peer.id else chosen + peer.id
                                        },
                                        onLongClickLabel = stringResource(R.string.sh_choose_several),
                                    )
                                    .semantics { if (picking) selected = on },
                            ) {
                                InfoRow(title = peer.name, subtitle = platformLabel(peer.platform), leading = { PlatformAvatar(peer.platform) }) {
                                    if (picking) {
                                        Icon(
                                            if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                            contentDescription = null,
                                            tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                        // AirWeb: the other phone or computer opens a link from a QR code; no app needed there.
                        if (hasFiles) {
                            Surface(
                                onClick = { openAirWeb() },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth().padding(top = if (connected.isEmpty()) 0.dp else Dimens.sm),
                            ) {
                                InfoRow(
                                    title = stringResource(R.string.airweb),
                                    subtitle = stringResource(R.string.sh_airweb_sub),
                                    leading = {
                                        Box(
                                            Modifier.size(Dimens.avatar).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Outlined.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(Dimens.iconMd))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openAirWeb() {
        try {
            startActivity(AirWebActivity.intent(this, sharedUris()))
        } catch (e: SecurityException) {
            // The sharing app didn't let us pass on what it shared.
            toast(getString(R.string.sh_cant_read_files))
        }
        finish()
    }

    private fun share(peers: List<PeerInfo>) {
        if (peers.isEmpty()) return
        peers.forEach { ShortcutManagerCompat.reportShortcutUsed(this, DirectShare.shortcutId(it.id)) }
        val uris = sharedUris()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val names = peers.joinToString(", ") { it.name }
        when {
            uris.isNotEmpty() -> {
                val (ok, no) = peers.partition { "xfer" in it.caps }
                if (no.isNotEmpty()) toast(getString(R.string.sh_cant_receive_files, no.joinToString(", ") { it.name }))
                if (ok.isEmpty()) return
                val files = resources.getQuantityString(R.plurals.sh_files, uris.size, uris.size)
                // Hand the URIs (and our read grant) to the long-lived service, which sends to each device.
                val clip = ClipData.newRawUri("files", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                try {
                    startService(
                        Intent(this, AirBridgeService::class.java)
                            .setAction(AirBridgeService.ACTION_SEND_FILES)
                            .putExtra(AirBridgeService.EXTRA_PEERS, ok.map { it.id }.toTypedArray())
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .also { it.clipData = clip }
                    )
                    toast(getString(R.string.sh_sending, files, ok.joinToString(", ") { it.name }))
                } catch (e: SecurityException) {
                    // The sharing app didn't let us read what it shared.
                    toast(getString(if (uris.size == 1) R.string.sh_cant_read_file else R.string.sh_cant_read_files))
                }
            }
            !text.isNullOrBlank() -> {
                val failed = peers.filterNot { ClipboardSync.sendTextTo(it.id, text) }
                when {
                    failed.isEmpty() -> toast(getString(R.string.sh_sent_to, names))
                    failed.size == peers.size -> toast(getString(R.string.sh_send_failed, failed.joinToString(", ") { it.name }))
                    else -> toast(getString(R.string.sh_sent_except, failed.joinToString(", ") { it.name }))
                }
            }
            else -> toast(getString(R.string.sh_nothing))
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
