package com.shazan.airbridge.ui.pairing

import android.Manifest
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import com.shazan.airbridge.R
import com.shazan.airbridge.airweb.AirWebActivity
import com.shazan.airbridge.app.AirBridgeService
import com.shazan.airbridge.connection.discovery.DiscoveredHost
import com.shazan.airbridge.connection.discovery.Discovery
import com.shazan.airbridge.connection.transport.NearbyTransport
import com.shazan.airbridge.ui.common.SearchingCard
import com.shazan.airbridge.ui.common.granted
import com.shazan.airbridge.ui.common.toast
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.PlatformAvatar
import com.shazan.airbridge.ui.theme.SecondaryButton
import com.shazan.airbridge.ui.theme.SectionHeader
import com.shazan.airbridge.ui.theme.StatusChip
import com.shazan.airbridge.ui.theme.platformLabel
import kotlinx.coroutines.launch

/** Requests Nearby permissions; returns current grant state and a request function. */
@Composable
internal fun rememberNearbyPermission(onResult: (Boolean) -> Unit = {}): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(NearbyTransport.hasPermissions(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ok = NearbyTransport.hasPermissions(ctx)
        onResult(ok)
    }
    return ok to { if (ok) onResult(true) else launcher.launch(NearbyTransport.requiredPermissions()) }
}
// ---- Add a device ----

@Composable
internal fun AddDeviceScreen(
    pairedIds: Set<String>,
    onPickHost: (DiscoveredHost) -> Unit,
    onPickAddress: (host: String, port: Int) -> Unit,
    onBack: (() -> Unit)?,
    onOpenScanner: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val hosts by Discovery.hosts.collectAsState()
    val androids by NearbyTransport.found.collectAsState()

    // Asked only from the "Nearby phones" card: phones on the same Wi-Fi pair without it.
    val (nearbyOk, requestNearby) = rememberNearbyPermission()
    // Nearby discovery only after permissions are granted.
    DisposableEffect(nearbyOk) {
        if (nearbyOk) NearbyTransport.acquireUi()
        onDispose { if (nearbyOk) NearbyTransport.releaseUi() }
    }
    // While this screen is open: this phone is listening + announced on Wi-Fi (LanHost, held by the
    // activity) and visible over Nearby (above), and it also sweeps the Wi-Fi subnet for devices whose
    // announcements the network drops (LanSweep).
    DisposableEffect(Unit) {
        Discovery.acquireSweep()
        onDispose { Discovery.releaseSweep() }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onOpenScanner() else toast(ctx, ctx.getString(R.string.camera_needed))
    }

    val hostList = hosts.values.sortedBy { it.name.lowercase() }
    // A phone already listed on this Wi-Fi (faster) is not listed again under Nearby.
    val androidList = androids.filter { (it.peerId == null || it.peerId !in pairedIds) && it.peerId !in hosts.keys }
    var askAddress by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    if (showQr) {
        if (com.shazan.airbridge.connection.discovery.EmulatorHost.isEmulator) AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(stringResource(R.string.emu_qr_title)) },
            text = { Text(stringResource(R.string.emu_qr_body)) },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(stringResource(R.string.ok)) } },
        ) else ShowQrDialog(onDismiss = { showQr = false })
    }
    if (askAddress) AddressDialog(onDismiss = { askAddress = false }, onConnect = { h, p -> askAddress = false; onPickAddress(h, p) })

    AbScaffold(
        title = stringResource(R.string.add_a_device),
        onBack = onBack,
        actions = { if (onBack == null) IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings)) } },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(Dimens.md), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                SecondaryButton(stringResource(R.string.scan_qr), icon = Icons.Outlined.QrCodeScanner, modifier = Modifier.weight(1f), onClick = {
                    if (granted(ctx, Manifest.permission.CAMERA)) onOpenScanner() else cameraLauncher.launch(Manifest.permission.CAMERA)
                })
                SecondaryButton(stringResource(R.string.show_qr), icon = Icons.Outlined.QrCode2, modifier = Modifier.weight(1f), onClick = { showQr = true })
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            item {
                Text(
                    stringResource(R.string.add_intro),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                // The other device doesn't have the app: AirWeb works through any browser, nothing to pair.
                AbCard(onClick = { ctx.startActivity(AirWebActivity.intent(ctx, emptyList())) }) {
                    InfoRow(
                        title = stringResource(R.string.airweb),
                        subtitle = stringResource(R.string.airweb_card_sub),
                        leading = {
                            Box(Modifier.size(Dimens.avatar).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(Dimens.iconMd))
                            }
                        },
                        trailing = { Text(stringResource(R.string.open), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.on_this_wifi)) }
            if (hostList.isEmpty()) item {
                SearchingCard(Icons.Outlined.Wifi, stringResource(R.string.looking_wifi), stringResource(R.string.looking_wifi_sub))
            }
            items(hostList, key = { it.id }) { host ->
                val isPaired = host.id in pairedIds
                AbCard(onClick = if (isPaired) null else ({ onPickHost(host) })) {
                    InfoRow(
                        title = host.name,
                        subtitle = platformLabel(host.plat),
                        leading = { PlatformAvatar(host.plat) },
                        trailing = {
                            if (isPaired) StatusChip(stringResource(R.string.paired), ChipTone.SUCCESS)
                            else Text(stringResource(R.string.pair), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                        },
                    )
                }
            }
            item {
                // Some networks hide devices from each other (and an emulator can't see the network at all):
                // the other device can still dial this one directly.
                val mine = remember { com.shazan.airbridge.connection.transport.LanHost.localAddresses() }
                val port = com.shazan.airbridge.connection.transport.LanHost.port
                AbCard(onClick = { askAddress = true }) {
                    InfoRow(
                        title = stringResource(R.string.connect_by_address),
                        subtitle = when {
                            // An emulator's address only exists inside the computer it runs on: nobody can dial it.
                            com.shazan.airbridge.connection.discovery.EmulatorHost.isEmulator -> stringResource(R.string.by_address_emulator)
                            mine.isNotEmpty() && port > 0 -> stringResource(R.string.by_address_mine, mine.first() + if (port != 47632) ":$port" else "")
                            else -> stringResource(R.string.by_address_sub)
                        },
                        leading = { PlatformAvatar("") },
                        trailing = { Text(stringResource(R.string.enter), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.nearby_phones)) }
            if (!nearbyOk) item {
                AbCard {
                    InfoRow(
                        title = stringResource(R.string.find_nearby),
                        subtitle = stringResource(R.string.find_nearby_sub),
                        leading = { PlatformAvatar("android") },
                    )
                    Box(Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md)) {
                        SecondaryButton(stringResource(R.string.allow_nearby), icon = Icons.Outlined.NearMe, onClick = requestNearby)
                    }
                }
            } else if (androidList.isEmpty()) item {
                SearchingCard(Icons.Outlined.NearMe, stringResource(R.string.looking_phones), stringResource(R.string.looking_phones_sub))
            }
            if (nearbyOk) items(androidList, key = { "nearby:" + it.endpointId }) { a ->
                AbCard(onClick = {
                    AirBridgeService.start(ctx)
                    NearbyTransport.requestConnection(a.endpointId)
                    toast(ctx, ctx.getString(R.string.asking, a.name))
                }) {
                    InfoRow(
                        title = a.name,
                        subtitle = stringResource(R.string.android_nearby),
                        leading = { PlatformAvatar("android") },
                        trailing = { Text(stringResource(R.string.pair), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) },
                    )
                }
            }
            item { Spacer(Modifier.height(Dimens.md)) }
        }
    }
}
