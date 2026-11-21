package com.shazan.airbridge.ui.devices

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.shazan.airbridge.R
import com.shazan.airbridge.airweb.AirWebActivity
import com.shazan.airbridge.airweb.AirWebRunningCard
import com.shazan.airbridge.airweb.AirWebSession
import com.shazan.airbridge.app.AirBridgeService
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.Identity
import com.shazan.airbridge.connection.PeerStatus
import com.shazan.airbridge.connection.pairing.PairedPeer
import com.shazan.airbridge.control.InputRouter
import com.shazan.airbridge.control.RemoteControlSetup
import com.shazan.airbridge.control.accessibility.ControlAccessibilityService
import com.shazan.airbridge.control.trackpad.TrackpadActivity
import com.shazan.airbridge.ui.common.rememberResumeTick
import com.shazan.airbridge.ui.common.toast
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.EmptyState
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.SecondaryButton
import com.shazan.airbridge.ui.theme.SectionHeader

// ---- Devices ----

@Composable
internal fun DevicesScreen(
    paired: List<PairedPeer>,
    onOpenSettings: () -> Unit,
    onAddDevice: () -> Unit,
    onForget: (PairedPeer) -> Unit,
) {
    val ctx = LocalContext.current
    val status by ConnectionHub.status.collectAsState()
    val transfers by ConnectionHub.transfers.collectAsState()
    val myName by Identity.nameFlow.collectAsState()
    // The lease holder as the input half reports it: cleared the moment its session ends (§8.6).
    val controller by InputRouter.controlledBy.collectAsState()
    var forgetting by remember { mutableStateOf<PairedPeer?>(null) }
    val airWeb by AirWebSession.current.collectAsState()
    val readyPeers by InputRouter.peers.collectAsState()
    val resumeTick = rememberResumeTick()
    val serviceRunning by ControlAccessibilityService.runningFlow.collectAsState()
    val setupDismissed by RemoteControlSetup.cardDismissed.collectAsState()
    // Re-checked on every resume (the user comes back from the system settings).
    val ownOff by com.shazan.airbridge.payment.PaymentPause.state.collectAsState()
    // Off because AirBridge itself switched it off (payment app, or no device for a minute) is not "needs setup".
    val accessibilityOff = remember(resumeTick, serviceRunning, ownOff) { !ControlAccessibilityService.isRunning && !ownOff.ownOff }

    forgetting?.let { p ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.forget_q, p.name)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = { TextButton(onClick = { forgetting = null; onForget(p) }) { Text(stringResource(R.string.forget), color = AbTheme.status.danger) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    AbScaffold(
        title = stringResource(R.string.app_brand),
        actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings)) } },
        bottomBar = {
            val ctx = LocalContext.current
            Row(Modifier.navigationBarsPadding().padding(Dimens.md), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                // AirWeb: send or receive with a device that doesn't have AirBridge, through its browser.
                SecondaryButton(stringResource(R.string.airweb), icon = Icons.Outlined.QrCode2, modifier = Modifier.weight(1f),
                    onClick = { ctx.startActivity(AirWebActivity.intent(ctx, emptyList())) })
                PrimaryButton(stringResource(R.string.add_device), icon = Icons.Outlined.Add, modifier = Modifier.weight(1f), onClick = onAddDevice)
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            // Only nag when a computer is paired: phone-to-phone file transfer never needs remote control
            // (it is still in Settings for anyone who wants one phone to drive another).
            if (accessibilityOff && !setupDismissed && paired.any { it.plat != "android" }) item(key = "a11y") {
                RemoteControlSetupCard(
                    noun = RemoteControlSetup.controllerNoun(),
                    onOpen = {
                        try { ctx.startActivity(RemoteControlSetup.settingsIntent()) }
                        catch (_: Exception) { toast(ctx, ctx.getString(R.string.not_available)) }
                    },
                    onDismiss = { RemoteControlSetup.dismissCard() },
                )
            }
            item {
                Text(stringResource(R.string.this_phone_named, myName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // AirWeb goes on after its screen closes: show that it is on, and let it be stopped from here too.
            airWeb?.let { m -> item(key = "airweb-running") { AirWebRunningCard(m) } }
            val list = transfers.values.sortedBy { it.key }
            if (list.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.transfers)) }
                items(list, key = { "xfer:" + it.key }) { TransferCard(it) }
            }
            item { SectionHeader(stringResource(R.string.devices)) }
            if (paired.isEmpty()) item {
                AbCard { EmptyState(Icons.Outlined.Devices, stringResource(R.string.no_devices), stringResource(R.string.no_devices_sub)) }
            }
            items(paired, key = { it.id }) { peer ->
                val st = status[peer.id] ?: PeerStatus.OFFLINE
                DeviceCard(
                    peer = peer,
                    caps = readyPeers.firstOrNull { it.id == peer.id }?.caps.orEmpty(),
                    status = st,
                    controllingYou = st == PeerStatus.CONNECTED && controller == peer.id,
                    onForget = { forgetting = peer },
                    onTrackpad = { mode ->
                        ctx.startActivity(
                            Intent(ctx, TrackpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(TrackpadActivity.EXTRA_PEER, peer.id)
                                .putExtra(TrackpadActivity.EXTRA_MODE, mode.name),
                        )
                    },
                    onDisconnect = { ConnectionHub.disconnect(peer.id) },
                    onConnect = {
                        AirBridgeService.start(ctx)
                        // A device that dials this phone can't be dialled back from here: say what to do.
                        if (!ConnectionHub.connect(peer.id)) toast(ctx, ctx.getString(R.string.connect_from_other, peer.name))
                    },
                )
            }
            item { Spacer(Modifier.height(Dimens.md)) }
        }
    }
}
