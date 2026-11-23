package com.shazan.airbridge.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import com.shazan.airbridge.R
import com.shazan.airbridge.app.AirBridgeService
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.NetEvent
import com.shazan.airbridge.connection.discovery.Discovery
import com.shazan.airbridge.connection.pairing.PairLink
import com.shazan.airbridge.connection.pairing.PairStore
import com.shazan.airbridge.connection.session.PairMode
import com.shazan.airbridge.connection.transport.NearbyTransport
import com.shazan.airbridge.connection.transport.WsClient
import com.shazan.airbridge.debug.LinkDebugScreen
import com.shazan.airbridge.isVpnActive
import com.shazan.airbridge.pendingPairLink
import com.shazan.airbridge.ui.common.granted
import com.shazan.airbridge.ui.common.rememberResumeTick
import com.shazan.airbridge.ui.common.toast
import com.shazan.airbridge.ui.devices.DevicesScreen
import com.shazan.airbridge.ui.pairing.AddDeviceScreen
import com.shazan.airbridge.ui.pairing.DigitsText
import com.shazan.airbridge.ui.pairing.PairingDialog
import com.shazan.airbridge.ui.pairing.scan.QrScannerScreen
import com.shazan.airbridge.ui.settings.AutoControlGuideScreen
import com.shazan.airbridge.ui.settings.SettingsScreen
import com.shazan.airbridge.ui.settings.SetupScreen
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.Dimens
import kotlinx.coroutines.launch

internal enum class Screen { DEVICES, ADD, SCANNER, SETTINGS, SETUP, AUTO_GUIDE, LINK_DEBUG }
/** A finished pairing attempt that failed, shown until dismissed. */
private data class PairError(val message: String)
private fun pairFailText(ctx: Context, reason: String, name: String): String = when (reason) {
    "rejected" -> ctx.getString(R.string.pair_fail_rejected, name)
    "timeout" -> ctx.getString(R.string.pair_fail_timeout)
    "locked" -> ctx.getString(R.string.pair_fail_locked)
    "not_pairing" -> ctx.getString(R.string.pair_fail_not_pairing, name)
    "bad_proof" -> ctx.getString(R.string.pair_fail_bad_proof)
    "different device at that address" -> ctx.getString(R.string.pair_fail_wrong_device)
    "", "handshake timeout" -> ctx.getString(R.string.pair_fail_unreachable, name)
    else -> ctx.getString(R.string.pair_fail_other, name, reason)
}
@Composable
internal fun AirBridgeRoot() {
    val ctx = LocalContext.current
    val paired by PairStore.peers.collectAsState()
    val pending by NearbyTransport.pending.collectAsState()
    val progress by ConnectionHub.pairing.collectAsState()
    val resumeTick = rememberResumeTick()

    var screen by rememberSaveable { mutableStateOf(if (PairStore.isEmpty()) Screen.ADD else Screen.DEVICES) }
    // Where the computer guide was opened from (Settings or the setup screen), so Back returns there.
    var guideFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    // "VPN is on" only when the VPN actually gets in the way: a VPN is up, there are paired devices, and none of them has
    // connected after a fair wait. With split tunnelling or a VPN that allows the local network (Proton, …) devices
    // connect fine, and warning on every launch was just noise.
    var showVpnWarning by remember { mutableStateOf(false) }
    val linkStatus by ConnectionHub.status.collectAsState()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(15_000)
        val anyConnected = ConnectionHub.status.value.values.any { it == com.shazan.airbridge.connection.PeerStatus.CONNECTED }
        showVpnWarning = isVpnActive(ctx) && !PairStore.isEmpty() && !anyConnected
    }
    // A device connecting proves the local network gets through: take the warning away.
    LaunchedEffect(linkStatus) {
        if (linkStatus.values.any { it == com.shazan.airbridge.connection.PeerStatus.CONNECTED }) showVpnWarning = false
    }
    var pairError by remember { mutableStateOf<PairError?>(null) }

    // Nothing is asked up front: notifications are asked once something is paired (incoming files and
    // pairing requests show there), "Display over other apps" only when the clipboard bubble is turned on
    // (Settings), Nearby only from its own card, the camera only for "Scan QR code".
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) return
        val prefs = ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("NOTIF_ASKED", false)) return
        prefs.edit().putBoolean("NOTIF_ASKED", true).apply()
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        if (AirBridgeService.shouldRun(ctx)) AirBridgeService.start(ctx)
        if (!PairStore.isEmpty()) askNotificationsOnce()
    }

    LaunchedEffect(Unit) {
        ConnectionHub.events.collect { e ->
            when (e) {
                is NetEvent.PairResult -> {
                    if (e.ok) {
                        pairError = null
                        screen = Screen.DEVICES
                        toast(ctx, ctx.getString(R.string.paired_with, e.peerName))
                        // Both sides (also the phone that only accepted) keep the new connection alive.
                        AirBridgeService.start(ctx)
                        askNotificationsOnce()
                    } else {
                        if (e.reason != "cancelled") pairError = PairError(pairFailText(ctx, e.reason, e.peerName.ifBlank { ctx.getString(R.string.the_device) }))
                        AirBridgeService.stopIfIdle(ctx)
                    }
                }
                is NetEvent.PairAgain -> {
                    Toast.makeText(ctx, ctx.getString(R.string.pair_again, e.peerName), Toast.LENGTH_LONG).show()
                    AirBridgeService.stopIfIdle(ctx)
                }
            }
        }
    }
    LaunchedEffect(paired.isEmpty()) {
        if (paired.isEmpty() && screen == Screen.DEVICES) screen = Screen.ADD
    }

    fun startPair(name: String, plat: String, host: String, port: Int, id: String?, mode: PairMode) {
        AirBridgeService.start(ctx)
        pairError = null
        WsClient.pair(host, port, id, mode, name, plat)
    }

    /** Shared by the QR scanner and opened `airbridge://pair` links. Returns true if pairing started. */
    fun handlePairLink(raw: String): Boolean {
        val link = PairLink.parse(raw)
        val addr = link?.let { Discovery.lookup(it.id) ?: it.addr?.let { a -> a to it.port } }
        return when {
            link == null -> { toast(ctx, ctx.getString(R.string.not_a_pairing_code)); false }
            addr == null || addr.second !in 1..65535 -> { toast(ctx, ctx.getString(R.string.not_on_network, link.name)); false }
            else -> {
                screen = Screen.ADD
                startPair(link.name, link.plat, addr.first, addr.second, link.id, PairMode.Qr(link.secret))
                true
            }
        }
    }

    val openedLink by pendingPairLink
    LaunchedEffect(openedLink) {
        val raw = openedLink ?: return@LaunchedEffect
        pendingPairLink.value = null
        handlePairLink(raw)
    }

    if (showVpnWarning) {
        AlertDialog(
            onDismissRequest = { showVpnWarning = false },
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.vpn_title)) },
            text = { Text(stringResource(R.string.vpn_body)) },
            confirmButton = { TextButton(onClick = { showVpnWarning = false }) { Text(stringResource(R.string.ok)) } },
        )
    }

    progress?.let { p ->
        PairingDialog(p, onConfirm = { AirBridgeService.start(ctx); ConnectionHub.confirmPairing(true) }, onCancel = { ConnectionHub.cancelPairing() })
    }
    if (progress == null) pairError?.let { err ->
        AlertDialog(
            onDismissRequest = { pairError = null },
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = AbTheme.status.danger) },
            title = { Text(stringResource(R.string.pair_failed_title)) },
            text = { Text(err.message) },
            confirmButton = { TextButton(onClick = { pairError = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    pending.firstOrNull()?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pair_with_q, p.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.same_code_phones), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Dimens.md))
                    DigitsText(p.digits)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AirBridgeService.start(ctx)
                    NearbyTransport.confirm(p.endpointId, true)
                }) { Text(stringResource(R.string.pair)) }
            },
            dismissButton = { TextButton(onClick = { NearbyTransport.confirm(p.endpointId, false) }) { Text(stringResource(R.string.reject)) } },
        )
    }

    when (screen) {
        Screen.DEVICES -> DevicesScreen(
            paired = paired,
            onOpenSettings = { screen = Screen.SETTINGS },
            onAddDevice = { screen = Screen.ADD },
            onForget = { peer ->
                ConnectionHub.forget(peer.id)
                if (PairStore.isEmpty()) {
                    AirBridgeService.stopIfIdle(ctx)
                    screen = Screen.ADD
                }
            },
        )

        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = if (paired.isEmpty()) Screen.ADD else Screen.DEVICES },
            onOpenLinkDebug = { screen = Screen.LINK_DEBUG },
            onOpenSetup = { screen = Screen.SETUP },
            onOpenGuide = { guideFrom = Screen.SETTINGS; screen = Screen.AUTO_GUIDE },
        )

        Screen.SETUP -> SetupScreen(onBack = { screen = Screen.SETTINGS }, onOpenGuide = { guideFrom = Screen.SETUP; screen = Screen.AUTO_GUIDE })
        Screen.AUTO_GUIDE -> AutoControlGuideScreen(onBack = { screen = guideFrom })
        Screen.LINK_DEBUG -> LinkDebugScreen(onBack = { screen = Screen.SETTINGS })

        Screen.ADD -> AddDeviceScreen(
            pairedIds = paired.map { it.id }.toSet(),
            onPickHost = { h -> startPair(h.name, h.plat, h.host, h.port, h.id, PairMode.Confirm(auto = false)) },
            onPickAddress = { host, port -> startPair(host, "", host, port, null, PairMode.Confirm(auto = false)) },
            onBack = if (paired.isNotEmpty()) ({ screen = Screen.DEVICES }) else null,
            onOpenScanner = { screen = Screen.SCANNER },
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.SCANNER -> QrScannerScreen(
            onCancel = { screen = Screen.ADD },
            onPair = { raw -> handlePairLink(raw) },
        )
    }
}
