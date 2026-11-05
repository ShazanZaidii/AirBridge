package com.shazan.airbridge

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shazan.airbridge.input.InputRouter
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.net.DiscoveredHost
import com.shazan.airbridge.net.DisconnectBy
import com.shazan.airbridge.net.Discovery
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.Identity
import com.shazan.airbridge.net.NearbyTransport
import com.shazan.airbridge.net.NetEvent
import com.shazan.airbridge.net.PairLink
import com.shazan.airbridge.net.PairMode
import com.shazan.airbridge.net.PairProgress
import com.shazan.airbridge.net.PairStage
import com.shazan.airbridge.net.PairStore
import com.shazan.airbridge.net.PairedPeer
import com.shazan.airbridge.net.PeerStatus
import com.shazan.airbridge.net.TransferUi
import com.shazan.airbridge.net.WsClient
import com.shazan.airbridge.net.formatSpeed
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.AirBridgeTheme
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.EmptyState
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.PlatformAvatar
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SecondaryButton
import com.shazan.airbridge.ui.theme.SectionHeader
import com.shazan.airbridge.ui.theme.StatusChip
import com.shazan.airbridge.ui.theme.SwitchRow
import com.shazan.airbridge.ui.theme.platformLabel
import com.shazan.airbridge.ui.theme.platformNoun
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/** An `airbridge://pair?...` link opened from another app (tapped link, shared QR text); consumed by [AirBridgeRoot]. */
private val pendingPairLink = mutableStateOf<String?>(null)

class MainActivity : ComponentActivity() {
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
            override fun onStart(owner: LifecycleOwner) { Discovery.acquire(); com.shazan.airbridge.net.LanHost.acquire() }
            override fun onStop(owner: LifecycleOwner) { com.shazan.airbridge.net.LanHost.release(); Discovery.release() }
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
        if (Identity.refreshDefault()) Hub.broadcastName()
        if (AirBridgeService.isRunning) WsClient.retryAllNow(unpause = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipboardSync.onAppFocused(this)
    }
}

private enum class Screen { DEVICES, ADD, SCANNER, SETTINGS, LINK_DEBUG }

/** A finished pairing attempt that failed, shown until dismissed. */
private data class PairError(val message: String)

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun pairFailText(reason: String, name: String): String = when (reason) {
    "rejected" -> "Pairing was declined on $name."
    "timeout" -> "Pairing timed out. Try again and confirm on both devices within a minute."
    "locked" -> "Too many attempts. Wait a minute, then try again."
    "not_pairing" -> "$name isn't accepting new devices right now. Open AirBridge on it and allow pairing."
    "bad_proof" -> "The two devices didn't agree on the code, so pairing was stopped. If you scanned a QR code, show a fresh one and scan again."
    "different device at that address" -> "A different device answered at that address. Try again from the list."
    "", "handshake timeout" -> "Couldn't reach $name. Make sure both devices are on the same Wi-Fi."
    else -> "Couldn't pair with $name ($reason)."
}

/** Re-composes when the activity resumes (permission/settings state read inside). */
@Composable
private fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
private fun AirBridgeRoot() {
    val ctx = LocalContext.current
    val paired by PairStore.peers.collectAsState()
    val pending by NearbyTransport.pending.collectAsState()
    val progress by Hub.pairing.collectAsState()
    val resumeTick = rememberResumeTick()

    var screen by rememberSaveable { mutableStateOf(if (PairStore.isEmpty()) Screen.ADD else Screen.DEVICES) }
    var showVpnWarning by remember { mutableStateOf(isVpnActive(ctx)) }
    var pairError by remember { mutableStateOf<PairError?>(null) }
    val hasOverlay = remember(resumeTick) { Settings.canDrawOverlays(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Start once the overlay is granted; never re-triggered by device list changes.
    LaunchedEffect(hasOverlay) {
        if (hasOverlay && AirBridgeService.shouldRun(ctx)) AirBridgeService.start(ctx)
    }

    LaunchedEffect(Unit) {
        Hub.events.collect { e ->
            when (e) {
                is NetEvent.PairResult -> {
                    if (e.ok) {
                        pairError = null
                        screen = Screen.DEVICES
                        toast(ctx, "Paired with ${e.peerName}")
                    } else {
                        if (e.reason != "cancelled") pairError = PairError(pairFailText(e.reason, e.peerName.ifBlank { "the device" }))
                        AirBridgeService.stopIfIdle(ctx)
                    }
                }
                is NetEvent.PairAgain -> {
                    Toast.makeText(ctx, "${e.peerName} no longer recognises this phone. Pair again.", Toast.LENGTH_LONG).show()
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
            link == null -> { toast(ctx, "That isn't an AirBridge pairing code"); false }
            addr == null || addr.second !in 1..65535 -> { toast(ctx, "${link.name} isn't on this network"); false }
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
            title = { Text("VPN is on") },
            text = { Text("AirBridge talks to your computer over the local network. If your VPN blocks local traffic, allow local network access (split tunnelling) or turn it off.") },
            confirmButton = { TextButton(onClick = { showVpnWarning = false }) { Text("OK") } },
        )
    }

    progress?.let { p -> PairingDialog(p, onConfirm = { Hub.confirmPairing(true) }, onCancel = { Hub.cancelPairing() }) }
    if (progress == null) pairError?.let { err ->
        AlertDialog(
            onDismissRequest = { pairError = null },
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = AbTheme.status.danger) },
            title = { Text("Couldn't pair") },
            text = { Text(err.message) },
            confirmButton = { TextButton(onClick = { pairError = null }) { Text("OK") } },
        )
    }

    pending.firstOrNull()?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pair with ${p.name}?") },
            text = {
                Column {
                    Text("Make sure both phones show the same code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Dimens.md))
                    DigitsText(p.digits)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AirBridgeService.start(ctx)
                    NearbyTransport.confirm(p.endpointId, true)
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { NearbyTransport.confirm(p.endpointId, false) }) { Text("Reject") } },
        )
    }

    if (!hasOverlay) {
        OverlayPermissionScreen()
        return
    }

    when (screen) {
        Screen.DEVICES -> DevicesScreen(
            paired = paired,
            onOpenSettings = { screen = Screen.SETTINGS },
            onAddDevice = { screen = Screen.ADD },
            onForget = { peer ->
                Hub.forget(peer.id)
                if (PairStore.isEmpty()) {
                    AirBridgeService.stopIfIdle(ctx)
                    screen = Screen.ADD
                }
            },
        )

        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = if (paired.isEmpty()) Screen.ADD else Screen.DEVICES },
            onOpenLinkDebug = { screen = Screen.LINK_DEBUG },
        )

        Screen.LINK_DEBUG -> LinkDebugScreen(onBack = { screen = Screen.SETTINGS })

        Screen.ADD -> AddDeviceScreen(
            pairedIds = paired.map { it.id }.toSet(),
            onPickHost = { h -> startPair(h.name, h.plat, h.host, h.port, h.id, PairMode.Confirm(auto = false)) },
            onPickAddress = { host, port -> startPair(host, "", host, port, null, PairMode.Confirm(auto = false)) },
            onBack = if (paired.isNotEmpty()) ({ screen = Screen.DEVICES }) else null,
            onOpenScanner = { screen = Screen.SCANNER },
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.SCANNER -> CameraScannerScreen(
            onCancel = { screen = Screen.ADD },
            onScanned = { raw -> handlePairLink(raw) },
        )
    }
}

/** "483920" → "483 920", read digit by digit by screen readers. */
@Composable
private fun DigitsText(digits: String, modifier: Modifier = Modifier) {
    val shown = if (digits.length == 6) digits.substring(0, 3) + " " + digits.substring(3) else digits
    Text(
        shown,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Code " + digits.toCharArray().joinToString(" ") },
    )
}

/** The LAN pairing sheet: connecting → compare digits → waiting for the computer → result (§3.3). */
@Composable
private fun PairingDialog(p: PairProgress, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val noun = platformNoun(p.plat)
    AlertDialog(
        onDismissRequest = {}, // explicit Cancel only, so a stray tap never aborts a pairing
        title = {
            Text(
                when (p.stage) {
                    PairStage.CONNECTING -> "Connecting to ${p.peerName}"
                    PairStage.CONFIRM -> "Pair with ${p.peerName}?"
                    PairStage.WAITING_PEER -> "Confirm on your $noun"
                }
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (p.stage) {
                    PairStage.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(Dimens.iconMd), strokeWidth = 3.dp)
                        Spacer(Modifier.width(Dimens.md))
                        Text(if (p.mode == "qr") "Checking the QR code…" else "Setting up a secure connection…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairStage.CONFIRM -> {
                        // Who is this, exactly: kind of device and where it is.
                        Text(
                            listOfNotNull(com.shazan.airbridge.net.deviceKind(p.plat), p.address?.let { "at $it" }).joinToString(" "),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Dimens.sm))
                        Text(
                            if (p.incoming) "It is asking to pair with this phone. Only confirm if it shows the same code and you started this."
                            else "Make sure your $noun shows the same code.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Dimens.lg))
                        DigitsText(p.digits ?: "")
                        Spacer(Modifier.height(Dimens.lg))
                        Text("If the codes are different, tap Cancel.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairStage.WAITING_PEER -> {
                        DigitsText(p.digits ?: "")
                        Spacer(Modifier.height(Dimens.lg))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Dimens.md))
                            Text("Accept the request on ${p.peerName} to finish.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { if (p.stage == PairStage.CONFIRM) TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun OverlayPermissionScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(Dimens.lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        Spacer(Modifier.height(Dimens.xl))
        Icon(Icons.Outlined.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("One permission first", style = MaterialTheme.typography.headlineSmall)
        Text(
            "AirBridge needs \"Display over other apps\" for the clipboard bubble and to show a cursor when your computer controls this phone.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AbCard {
            InfoRow(
                title = "1. Allow restricted settings",
                subtitle = "Only needed if the switch in step 2 is greyed out. In App info, open the menu at the top right and choose \"Allow restricted settings\".",
            )
            Box(Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md)) {
                SecondaryButton("Open App info", onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                })
            }
            RowDivider()
            InfoRow(title = "2. Turn on \"Display over other apps\"", subtitle = "Then come back here.")
            Box(Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md)) {
                PrimaryButton("Grant permission", onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                })
            }
        }
    }
}

/** Requests Nearby permissions; returns current grant state and a request function. */
@Composable
private fun rememberNearbyPermission(onResult: (Boolean) -> Unit = {}): Pair<Boolean, () -> Unit> {
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
private fun AddDeviceScreen(
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

    val (nearbyOk, requestNearby) = rememberNearbyPermission()
    LaunchedEffect(Unit) { requestNearby() }
    // Nearby discovery only after permissions are granted.
    DisposableEffect(nearbyOk) {
        if (nearbyOk) NearbyTransport.acquireUi()
        onDispose { if (nearbyOk) NearbyTransport.releaseUi() }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onOpenScanner() else toast(ctx, "Camera permission is needed to scan the QR code")
    }

    val hostList = hosts.values.sortedBy { it.name.lowercase() }
    // A phone already listed on this Wi-Fi (faster) is not listed again under Nearby.
    val androidList = androids.filter { (it.peerId == null || it.peerId !in pairedIds) && it.peerId !in hosts.keys }
    var askAddress by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    if (showQr) ShowQrDialog(onDismiss = { showQr = false })
    if (askAddress) AddressDialog(onDismiss = { askAddress = false }, onConnect = { h, p -> askAddress = false; onPickAddress(h, p) })

    AbScaffold(
        title = "Add a device",
        onBack = onBack,
        actions = { if (onBack == null) IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") } },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(Dimens.md), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                SecondaryButton("Scan QR code", icon = Icons.Outlined.QrCodeScanner, modifier = Modifier.weight(1f), onClick = {
                    if (granted(ctx, Manifest.permission.CAMERA)) onOpenScanner() else cameraLauncher.launch(Manifest.permission.CAMERA)
                })
                SecondaryButton("Show QR code", icon = Icons.Outlined.QrCode2, modifier = Modifier.weight(1f), onClick = { showQr = true })
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            item {
                Text(
                    "Pick a computer or phone to pair. You'll check that both screens show the same code.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader("On this Wi-Fi") }
            if (hostList.isEmpty()) item {
                SearchingCard(Icons.Outlined.Wifi, "Looking on this Wi-Fi…", "Open AirBridge on your Mac, PC or other phone. Both need to be on the same Wi-Fi.")
            }
            items(hostList, key = { it.id }) { host ->
                val isPaired = host.id in pairedIds
                AbCard(onClick = if (isPaired) null else ({ onPickHost(host) })) {
                    InfoRow(
                        title = host.name,
                        subtitle = platformLabel(host.plat),
                        leading = { PlatformAvatar(host.plat) },
                        trailing = {
                            if (isPaired) StatusChip("Paired", ChipTone.SUCCESS)
                            else Text("Pair", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                        },
                    )
                }
            }
            item {
                // Some networks hide devices from each other (and an emulator can't see the network at all):
                // the other device can still dial this one directly.
                val mine = remember { com.shazan.airbridge.net.LanHost.localAddresses() }
                val port = com.shazan.airbridge.net.LanHost.port
                AbCard(onClick = { askAddress = true }) {
                    InfoRow(
                        title = "Connect by address",
                        subtitle = when {
                            // An emulator's address only exists inside the computer it runs on: nobody can dial it.
                            com.shazan.airbridge.net.EmulatorHost.isEmulator -> "Can't see it? Enter its address. This is an emulator: start pairing from here, other devices can't reach it."
                            mine.isNotEmpty() && port > 0 -> "Can't see it? Enter its address. This phone is ${mine.first()}${if (port != 47632) ":$port" else ""}"
                            else -> "Can't see it? Enter its address."
                        },
                        leading = { PlatformAvatar("") },
                        trailing = { Text("Enter", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) },
                    )
                }
            }
            item { SectionHeader("Nearby phones") }
            if (!nearbyOk) item {
                AbCard {
                    InfoRow(
                        title = "Find nearby phones",
                        subtitle = "Pair with another Android phone directly. No Wi-Fi network needed.",
                        leading = { PlatformAvatar("android") },
                    )
                    Box(Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md)) {
                        SecondaryButton("Allow nearby devices", icon = Icons.Outlined.NearMe, onClick = requestNearby)
                    }
                }
            } else if (androidList.isEmpty()) item {
                SearchingCard(Icons.Outlined.NearMe, "Looking for phones…", "Open AirBridge on the other phone. Wi-Fi and Bluetooth must be on; no shared network is needed.")
            }
            if (nearbyOk) items(androidList, key = { "nearby:" + it.endpointId }) { a ->
                AbCard(onClick = {
                    AirBridgeService.start(ctx)
                    NearbyTransport.requestConnection(a.endpointId)
                    toast(ctx, "Asking ${a.name}…")
                }) {
                    InfoRow(
                        title = a.name,
                        subtitle = "Android · Nearby",
                        leading = { PlatformAvatar("android") },
                        trailing = { Text("Pair", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) },
                    )
                }
            }
            item { Spacer(Modifier.height(Dimens.md)) }
        }
    }
}

/**
 * This phone's pairing QR code (mode qr, §2.4): the other phone scans it with "Scan QR code" and pairs
 * at once, no digits to compare. The secret is fresh each time and dies when the dialog closes.
 */
@Composable
private fun ShowQrDialog(onDismiss: () -> Unit) {
    val link by com.shazan.airbridge.net.LanHost.qrLink.collectAsState()
    val pairedCount = PairStore.peers.collectAsState().value.size
    val startCount = remember { pairedCount }
    DisposableEffect(Unit) {
        com.shazan.airbridge.net.LanHost.openQr()
        onDispose { com.shazan.airbridge.net.LanHost.closeQr() }
    }
    LaunchedEffect(pairedCount) { if (pairedCount > startCount) onDismiss() } // paired: done
    val addr = remember(link) { com.shazan.airbridge.net.LanHost.localAddresses().firstOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to pair") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
                val l = link
                if (l == null) {
                    Text("Connect this phone to Wi-Fi first: the other device reaches it over the network.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    QrImage(l, 240.dp)
                    Text("On the other phone: AirBridge › Add a device › Scan QR code.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Text("${Identity.name} · ${addr ?: ""}", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** A QR code drawn with ZXing, black on white with a quiet zone (scanners need the contrast). */
@Composable
private fun QrImage(text: String, size: androidx.compose.ui.unit.Dp) {
    val bmp = remember(text) {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
        )
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    androidx.compose.foundation.Image(
        bmp, contentDescription = "Pairing QR code",
        modifier = Modifier.size(size).background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.medium).padding(Dimens.sm),
    )
}

/** "192.168.1.20" or "192.168.1.20:47633" → pair with whatever AirBridge device answers there. */
@Composable
private fun AddressDialog(onDismiss: () -> Unit, onConnect: (host: String, port: Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) {
        val t = text.trim()
        val host = t.substringBeforeLast(':', t).trim()
        val port = if (':' in t) t.substringAfterLast(':').toIntOrNull() else 47632
        if (host.isNotEmpty() && port != null && port in 1..65535 && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) host to port else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text("The other device's IP address, as shown on its Add a device screen.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("192.168.1.20") })
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onConnect(it.first, it.second) } }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SearchingCard(icon: ImageVector, title: String, body: String) {
    AbCard {
        InfoRow(
            title = title,
            subtitle = body,
            leading = {
                Box(
                    Modifier.size(Dimens.avatar).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.iconSm))
                }
            },
            trailing = { CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

// ---- Devices ----

@Composable
private fun DevicesScreen(
    paired: List<PairedPeer>,
    onOpenSettings: () -> Unit,
    onAddDevice: () -> Unit,
    onForget: (PairedPeer) -> Unit,
) {
    val ctx = LocalContext.current
    val status by Hub.status.collectAsState()
    val transfers by Hub.transfers.collectAsState()
    val myName by Identity.nameFlow.collectAsState()
    // The lease holder as the input half reports it: cleared the moment its session ends (§8.6).
    val controller by InputRouter.controlledBy.collectAsState()
    var forgetting by remember { mutableStateOf<PairedPeer?>(null) }
    val readyPeers by InputRouter.peers.collectAsState()
    val resumeTick = rememberResumeTick()
    val serviceRunning by AirBridgeInputService.runningFlow.collectAsState()
    val setupDismissed by RemoteControlSetup.cardDismissed.collectAsState()
    // Re-checked on every resume (the user comes back from the system settings).
    val accessibilityOff = remember(resumeTick, serviceRunning) { !AirBridgeInputService.isRunning }

    forgetting?.let { p ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${p.name}?") },
            text = { Text("It will be disconnected and removed from both devices. You'll need to pair again to reconnect.") },
            confirmButton = { TextButton(onClick = { forgetting = null; onForget(p) }) { Text("Forget", color = AbTheme.status.danger) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }

    AbScaffold(
        title = "AirBridge",
        actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") } },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(Dimens.md)) {
                PrimaryButton("Add device", icon = Icons.Outlined.Add, onClick = onAddDevice)
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            if (accessibilityOff && !setupDismissed && paired.isNotEmpty()) item(key = "a11y") {
                RemoteControlSetupCard(
                    noun = RemoteControlSetup.controllerNoun(),
                    onOpen = {
                        try { ctx.startActivity(RemoteControlSetup.settingsIntent()) }
                        catch (_: Exception) { toast(ctx, "Not available on this device") }
                    },
                    onDismiss = { RemoteControlSetup.dismissCard() },
                )
            }
            item {
                Text("This phone · $myName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val list = transfers.values.sortedBy { it.key }
            if (list.isNotEmpty()) {
                item { SectionHeader("Transfers") }
                items(list, key = { "xfer:" + it.key }) { TransferCard(it) }
            }
            item { SectionHeader("Devices") }
            if (paired.isEmpty()) item {
                AbCard { EmptyState(Icons.Outlined.Devices, "No devices yet", "Pair your Mac, PC or another phone to share the clipboard and files.") }
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
                    onDisconnect = { WsClient.disconnect(peer.id) },
                    onConnect = {
                        if (!WsClient.connect(peer.id)) AirBridgeService.start(ctx)
                    },
                )
            }
            item { Spacer(Modifier.height(Dimens.md)) }
        }
    }
}

@Composable
private fun DeviceCard(
    peer: PairedPeer,
    caps: Set<String>,
    status: PeerStatus,
    controllingYou: Boolean,
    onForget: () -> Unit,
    onTrackpad: (TrackpadActivity.Mode) -> Unit,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val disconnected = status == PeerStatus.DISCONNECTED
    val (label, tone) = when {
        controllingYou -> "Controlling you" to ChipTone.INFO
        status == PeerStatus.CONNECTED -> "Connected" to ChipTone.SUCCESS
        status == PeerStatus.CONNECTING -> "Connecting" to ChipTone.WARNING
        status == PeerStatus.RECONNECTING -> "Reconnecting" to ChipTone.WARNING
        disconnected && peer.disconnectedBy == DisconnectBy.PEER -> "Disconnected by ${peer.name}" to ChipTone.NEUTRAL
        disconnected -> "Disconnected" to ChipTone.NEUTRAL
        else -> "Offline" to ChipTone.NEUTRAL
    }
    val desktop = peer.plat == "macos" || peer.plat == "windows" || peer.plat == "linux"
    val lan = peer.via == "lan"
    val canDisconnect = lan && (status == PeerStatus.CONNECTED || status == PeerStatus.CONNECTING || status == PeerStatus.RECONNECTING)
    val canConnect = lan && (disconnected || status == PeerStatus.OFFLINE)
    AbCard {
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.md, top = Dimens.md, bottom = Dimens.md, end = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformAvatar(peer.plat)
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${platformLabel(peer.plat)} · ${if (peer.via == "nearby") "Nearby" else "Wi-Fi"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(label, tone, modifier = Modifier.weight(1f, fill = false))
                    if (disconnected) {
                        Spacer(Modifier.width(Dimens.xs))
                        TextButton(onClick = onConnect, modifier = Modifier.height(Dimens.touch)) { Text("Connect") }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${peer.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if ((desktop || (peer.plat == "android" && com.shazan.airbridge.net.Protocol.CAP_TARGET in caps)) && status == PeerStatus.CONNECTED) DropdownMenuItem(
                        text = { Text("Use as trackpad") },
                        onClick = { menu = false; onTrackpad(TrackpadActivity.Mode.Trackpad) },
                    )
                    if (canDisconnect) DropdownMenuItem(
                        text = { Text("Disconnect") },
                        onClick = { menu = false; onDisconnect() },
                    )
                    if (canConnect) DropdownMenuItem(
                        text = { Text("Connect") },
                        onClick = { menu = false; onConnect() },
                    )
                    DropdownMenuItem(
                        text = { Text("Forget device", color = AbTheme.status.danger) },
                        onClick = { menu = false; onForget() },
                    )
                }
            }
        }
        // A connected computer or phone: the ways to control it, one tap away. A phone is controllable
        // once its AirBridge Universal Control service is on (it then advertises ctl_target); it can't
        // share its screen, so it gets Trackpad and Keyboard only.
        val phone = peer.plat == "android"
        val drivable = desktop || (phone && com.shazan.airbridge.net.Protocol.CAP_TARGET in caps)
        if (status == PeerStatus.CONNECTED && !controllingYou && drivable) {
            Row(
                Modifier.fillMaxWidth().padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                for (m in TrackpadActivity.Mode.entries) {
                    if (phone && m == TrackpadActivity.Mode.Screen && "mirror_source" !in caps) continue
                    CardAction(m.label, m.icon, Modifier.weight(1f)) { onTrackpad(m) }
                }
            }
        } else if (status == PeerStatus.CONNECTED && phone && !controllingYou) {
            Text(
                "To control ${peer.name} from here, turn on AirBridge Universal Control in its Accessibility settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
            )
        }
    }
}

/** Compact tonal action inside a device card: icon over a short label, 56 dp tall. */
@Composable
private fun CardAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(56.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSm))
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * Top-of-list warning while the core accessibility service ("AirBridge Universal Control") is off:
 * the computer would otherwise try to move its cursor onto a phone that can't show it (§8.1
 * `unavailable`). The optional typing service is never mentioned here.
 */
@Composable
private fun RemoteControlSetupCard(noun: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val warn = AbTheme.status.warning
    AbCard(Modifier.border(1.dp, warn.copy(alpha = 0.6f), MaterialTheme.shapes.large)) {
        Row(Modifier.fillMaxWidth().padding(start = Dimens.md, top = Dimens.md, end = Dimens.xs), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = warn,
                modifier = Modifier.padding(top = 2.dp).size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text("$noun can't control this phone yet", style = MaterialTheme.typography.titleMedium)
                Text("Turn on \"AirBridge Universal Control\" in Accessibility settings so the cursor can appear and your clicks reach this phone. It can't read your screen.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Dismiss") }
        }
        Row(Modifier.fillMaxWidth().padding(Dimens.md), horizontalArrangement = Arrangement.End) {
            PrimaryButton("Turn on", onClick = onOpen, icon = Icons.Outlined.Settings)
        }
    }
}

@Composable
private fun TransferCard(t: TransferUi) {
    AbCard {
        Column(Modifier.padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            Text((if (t.incoming) "Receiving from " else "Sending to ") + t.peerName, style = MaterialTheme.typography.titleSmall)
            Text(t.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.result == null) {
                val p = if (t.bytesTotal > 0) (t.bytesDone.toFloat() / t.bytesTotal).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${(p * 100).toInt()} percent" },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${minOf(t.filesDone + 1, t.filesTotal)} of ${t.filesTotal} · ${(p * 100).toInt()}% · ${formatSpeed(t.bytesPerSec)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val ok = t.result == "Sent" || t.result == "Received"
                val c = if (ok) AbTheme.status.success else AbTheme.status.danger
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, contentDescription = null, tint = c,
                        modifier = Modifier.size(Dimens.iconSm))
                    Spacer(Modifier.width(Dimens.sm))
                    Text(t.result, style = MaterialTheme.typography.labelLarge, color = c)
                }
            }
        }
    }
}

// ---- QR scanner ----

@Composable
private fun CameraScannerScreen(onCancel: () -> Unit, onScanned: (String) -> Boolean) {
    BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val preview = remember { Preview.Builder().build() }
    val analyzerHolder = remember { arrayOfNulls<QrCodeAnalyzer>(1) }
    val analysis = remember {
        val analyzer = QrCodeAnalyzer { raw ->
            ContextCompat.getMainExecutor(ctx).execute { if (!onScanned(raw)) analyzerHolder[0]?.reset() }
        }
        analyzerHolder[0] = analyzer
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            .also { it.setAnalyzer(executor, analyzer) }
    }
    // Unbind the camera and stop the analyzer thread when leaving the scanner.
    DisposableEffect(Unit) {
        onDispose {
            try { if (providerFuture.isDone) providerFuture.get().unbind(preview, analysis) } catch (_: Exception) {}
            analysis.clearAnalyzer()
            analyzerHolder[0]?.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { c ->
                val view = PreviewView(c)
                preview.setSurfaceProvider(view.surfaceProvider)
                providerFuture.addListener({
                    try {
                        if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
                            providerFuture.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    } catch (_: Exception) {
                        toast(c, "Camera unavailable")
                    }
                }, ContextCompat.getMainExecutor(c))
                view
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Viewfinder frame.
        Box(
            Modifier.align(Alignment.Center).size(240.dp)
                .border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge),
        )
        Surface(color = MaterialTheme.colorScheme.scrim, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            Row(Modifier.statusBarsPadding().padding(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.width(Dimens.sm))
                Column {
                    Text("Scan the pairing code", style = MaterialTheme.typography.titleMedium)
                    Text("Shown by AirBridge on your computer when you add a device.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- Settings ----

@Composable
private fun SettingsScreen(onBack: () -> Unit, onOpenLinkDebug: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE) }
    val resumeTick = rememberResumeTick()
    val scope = rememberCoroutineScope()
    val myName by Identity.nameFlow.collectAsState()

    var invertX by remember { mutableStateOf(prefs.getBoolean("INVERT_X", false)) }
    var invertY by remember { mutableStateOf(prefs.getBoolean("INVERT_Y", false)) }
    var bubble by remember { mutableStateOf(prefs.getBoolean(FloatingBubble.PREF_ENABLED, true)) }
    var autoConnect by remember { mutableStateOf(prefs.getBoolean(WsClient.PREF_AUTO_CONNECT, true)) }
    var visible by remember { mutableStateOf(prefs.getBoolean(NearbyTransport.PREF_VISIBLE, false)) }
    var renaming by remember { mutableStateOf(false) }

    val canWriteSettings = remember(resumeTick) { Settings.System.canWrite(ctx) }
    val dndAccess = remember(resumeTick) { ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true }
    val notificationsOn = remember(resumeTick) { ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true }

    val (_, requestNearby) = rememberNearbyPermission { ok ->
        if (ok) {
            visible = true
            NearbyTransport.setVisible(true)
            AirBridgeService.start(ctx)
        } else toast(ctx, "Nearby permissions are needed to pair with phones")
    }

    fun open(intent: Intent) = scope.launch {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, "Not available on this device") }
    }

    if (renaming) {
        var name by remember { mutableStateOf(Identity.customName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Phone name") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, singleLine = true,
                    label = { Text("Shown on your other devices") }, placeholder = { Text(Identity.defaultName) })
            },
            confirmButton = { TextButton(onClick = { Hub.renameSelf(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    // Long-press the title: hidden Wi-Fi Direct test screen (device tests S-W2/S-W3/S-W4).
    AbScaffold(title = "Settings", onBack = onBack, onTitleLongPress = onOpenLinkDebug) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("This phone")
            AbCard {
                InfoRow(title = "Name", subtitle = myName, trailing = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = "Rename this phone") }
                })
                RowDivider()
                SwitchRow("Visible to nearby phones", "Lets other Android phones find and pair with this one", visible, { on ->
                    if (on) requestNearby()
                    else {
                        visible = false
                        NearbyTransport.setVisible(false)
                        AirBridgeService.stopIfIdle(ctx)
                    }
                })
            }

            SectionHeader("Connection")
            AbCard {
                SwitchRow("Reconnect automatically", "Connect to paired devices whenever they're reachable", autoConnect, {
                    autoConnect = it
                    prefs.edit().putBoolean(WsClient.PREF_AUTO_CONNECT, it).apply()
                    if (it) WsClient.retryAllNow(unpause = true)
                })
            }

            SectionHeader("Clipboard")
            AbCard {
                SwitchRow(
                    "Floating bubble",
                    "Tap it to send your clipboard. Hidden while you use the trackpad or another device controls this phone.",
                    bubble,
                    {
                        bubble = it
                        prefs.edit().putBoolean(FloatingBubble.PREF_ENABLED, it).apply()
                        if (AirBridgeService.isRunning) AirBridgeService.start(ctx, AirBridgeService.ACTION_REFRESH_BUBBLE)
                    },
                )
            }
            com.shazan.airbridge.ui.ClipboardAutoSyncCard()

            SectionHeader("Remote control")
            AbCard {
                com.shazan.airbridge.ui.RemoteControlServiceRows()
                RowDivider()
                SwitchRow("Invert left/right arrows", null, invertX, { invertX = it; prefs.edit().putBoolean("INVERT_X", it).apply() })
                RowDivider()
                SwitchRow("Invert up/down arrows", null, invertY, { invertY = it; prefs.edit().putBoolean("INVERT_Y", it).apply() })
                // Only where OemGestureSwitches has verified keys for this manufacturer.
                if (com.shazan.airbridge.input.OsGestureGuard.switchAvailable) {
                    var osGesturesOff by remember { mutableStateOf(com.shazan.airbridge.input.OsGestureGuard.switchEnabled(prefs)) }
                    RowDivider()
                    SwitchRow(
                        "Turn off Android's multi-finger gestures while using the trackpad",
                        if (canWriteSettings) "Turned back on when you leave the trackpad" else "Needs “Modify system settings”",
                        osGesturesOff,
                        { on ->
                            osGesturesOff = on
                            prefs.edit().putBoolean(com.shazan.airbridge.input.OsGestureGuard.PREF_SWITCHES_OFF, on).apply()
                            if (on && !canWriteSettings) open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        },
                    )
                }
            }

            SectionHeader("System access")
            AbCard {
                AccessRow("Notifications", "Transfer progress and pairing requests", notificationsOn) {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                RowDivider()
                AccessRow("Modify system settings", "Brightness keys from your computer", canWriteSettings) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                RowDivider()
                AccessRow("Do Not Disturb access", "Toggle Do Not Disturb from your computer", dndAccess) {
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }

            SectionHeader("About")
            AbCard {
                InfoRow(title = "AirBridge ${BuildConfig.VERSION_NAME}", subtitle = "Protocol v2 · encrypted end to end",
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenLinkDebug() }) })
            }
            Spacer(Modifier.height(Dimens.xl))
        }
    }
}

@Composable
private fun AccessRow(title: String, subtitle: String, granted: Boolean, onOpen: () -> Unit) {
    InfoRow(title = title, subtitle = subtitle, trailing = {
        if (granted) StatusChip("Allowed", ChipTone.SUCCESS)
        else TextButton(onClick = onOpen, modifier = Modifier.height(Dimens.touch)) { Text("Allow") }
    })
}
