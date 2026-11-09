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
import androidx.compose.ui.res.stringResource
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

    // Calls on the computer are on but Android won't hand over the ringing number (Call logs off): some phones'
    // call screens show nothing else while ringing, so the computer would say "Unknown Caller". Asked once.
    val callLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        com.shazan.airbridge.phone.PhoneRelay.onSettingsChanged()
    }
    var askCallLog by remember {
        val prefs = ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        mutableStateOf(com.shazan.airbridge.phone.PhoneRelaySettings.callsEnabled &&
            com.shazan.airbridge.phone.PhoneRelaySettings.canWatchCalls(ctx) &&
            !com.shazan.airbridge.phone.PhoneRelaySettings.canSeeNumber(ctx) &&
            !prefs.getBoolean("CALL_LOG_ASKED", false))
    }
    if (askCallLog) {
        fun done() {
            askCallLog = false
            ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE).edit().putBoolean("CALL_LOG_ASKED", true).apply()
        }
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text(stringResource(R.string.ce_number_title)) },
            text = { Text(stringResource(R.string.ce_number_sub)) },
            confirmButton = {
                TextButton(onClick = { done(); callLogLauncher.launch(Manifest.permission.READ_CALL_LOG) }) { Text(stringResource(R.string.allow)) }
            },
            dismissButton = { TextButton(onClick = { done() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LaunchedEffect(Unit) {
        Hub.events.collect { e ->
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
        PairingDialog(p, onConfirm = { AirBridgeService.start(ctx); Hub.confirmPairing(true) }, onCancel = { Hub.cancelPairing() })
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
    val codeLabel = stringResource(R.string.code_label)
    val shown = if (digits.length == 6) digits.substring(0, 3) + " " + digits.substring(3) else digits
    Text(
        shown,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1, // the code must never wrap: shrink to fit narrow screens and big font settings
        autoSize = com.shazan.airbridge.ui.theme.fitOneLine(MaterialTheme.typography.displayMedium, 22f),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = codeLabel + " " + digits.toCharArray().joinToString(" ") },
    )
}

/** The LAN pairing sheet: connecting → compare digits → waiting for the computer → result (§3.3). */
@Composable
private fun PairingDialog(p: PairProgress, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val noun = platformNoun(p.plat)
    val atFmt = stringResource(R.string.at_address)
    val atAddress = { a: String -> atFmt.format(a) }
    AlertDialog(
        onDismissRequest = {}, // explicit Cancel only, so a stray tap never aborts a pairing
        title = {
            Text(
                when (p.stage) {
                    PairStage.CONNECTING -> stringResource(R.string.connecting_to, p.peerName)
                    PairStage.CONFIRM -> stringResource(R.string.pair_with_q, p.peerName)
                    PairStage.WAITING_PEER -> stringResource(R.string.confirm_on_your, noun)
                }
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (p.stage) {
                    PairStage.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(Dimens.iconMd), strokeWidth = 3.dp)
                        Spacer(Modifier.width(Dimens.md))
                        Text(if (p.mode == "qr") stringResource(R.string.checking_qr) else stringResource(R.string.securing),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairStage.CONFIRM -> {
                        // Who is this, exactly: kind of device and where it is.
                        Text(
                            listOfNotNull(com.shazan.airbridge.net.deviceKind(p.plat), p.address?.let { atAddress(it) }).joinToString(" "),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Dimens.sm))
                        Text(
                            if (p.incoming) stringResource(R.string.incoming_pair_body)
                            else stringResource(R.string.same_code_your, noun),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Dimens.lg))
                        DigitsText(p.digits ?: "")
                        Spacer(Modifier.height(Dimens.lg))
                        Text(stringResource(R.string.codes_differ), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairStage.WAITING_PEER -> {
                        DigitsText(p.digits ?: "")
                        Spacer(Modifier.height(Dimens.lg))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Dimens.md))
                            Text(stringResource(R.string.accept_on, p.peerName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { if (p.stage == PairStage.CONFIRM) TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
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
        if (com.shazan.airbridge.net.EmulatorHost.isEmulator) AlertDialog(
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
                val mine = remember { com.shazan.airbridge.net.LanHost.localAddresses() }
                val port = com.shazan.airbridge.net.LanHost.port
                AbCard(onClick = { askAddress = true }) {
                    InfoRow(
                        title = stringResource(R.string.connect_by_address),
                        subtitle = when {
                            // An emulator's address only exists inside the computer it runs on: nobody can dial it.
                            com.shazan.airbridge.net.EmulatorHost.isEmulator -> stringResource(R.string.by_address_emulator)
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
        title = { Text(stringResource(R.string.scan_to_pair)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
                val l = link
                if (l == null) {
                    Text(stringResource(R.string.qr_need_wifi),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    QrImage(l, 240.dp)
                    Text(stringResource(R.string.qr_how),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Text("${Identity.name} · ${addr ?: ""}", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
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
        bmp, contentDescription = stringResource(R.string.pairing_qr),
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
        title = { Text(stringResource(R.string.connect_by_address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text(stringResource(R.string.address_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("192.168.1.20") })
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onConnect(it.first, it.second) } }) { Text(stringResource(R.string.connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
        controllingYou -> stringResource(R.string.st_controlling_you) to ChipTone.INFO
        status == PeerStatus.CONNECTED -> stringResource(R.string.st_connected) to ChipTone.SUCCESS
        status == PeerStatus.CONNECTING -> stringResource(R.string.st_connecting) to ChipTone.WARNING
        status == PeerStatus.RECONNECTING -> stringResource(R.string.st_reconnecting) to ChipTone.WARNING
        disconnected && peer.disconnectedBy == DisconnectBy.PEER -> stringResource(R.string.st_disconnected_by, peer.name) to ChipTone.NEUTRAL
        disconnected -> stringResource(R.string.st_disconnected) to ChipTone.NEUTRAL
        else -> stringResource(R.string.st_offline) to ChipTone.NEUTRAL
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
                        TextButton(onClick = onConnect, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.connect)) }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options_for, peer.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if ((desktop || (peer.plat == "android" && com.shazan.airbridge.net.Protocol.CAP_TARGET in caps)) && status == PeerStatus.CONNECTED) DropdownMenuItem(
                        text = { Text(stringResource(R.string.use_as_trackpad)) },
                        onClick = { menu = false; onTrackpad(TrackpadActivity.Mode.Trackpad) },
                    )
                    if (canDisconnect) DropdownMenuItem(
                        text = { Text(stringResource(R.string.disconnect)) },
                        onClick = { menu = false; onDisconnect() },
                    )
                    if (canConnect) DropdownMenuItem(
                        text = { Text(stringResource(R.string.connect)) },
                        onClick = { menu = false; onConnect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.forget_device), color = AbTheme.status.danger) },
                        onClick = { menu = false; onForget() },
                    )
                }
            }
        }
        // A connected computer or phone: the ways to control it, one tap away. A phone is controllable
        // once its AirBridge Universal Control service is on (it then advertises ctl_target); Screen mode
        // needs it to advertise mirror_source as well (its user then allows each session there).
        val phone = peer.plat == "android"
        val drivable = desktop || (phone && com.shazan.airbridge.net.Protocol.CAP_TARGET in caps)
        if (status == PeerStatus.CONNECTED && !controllingYou && drivable) {
            Row(
                Modifier.fillMaxWidth().padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                for (m in TrackpadActivity.Mode.entries) {
                    if (phone && m == TrackpadActivity.Mode.Screen && "mirror_source" !in caps) continue
                    CardAction(stringResource(m.labelRes), m.icon, Modifier.weight(1f)) { onTrackpad(m) }
                }
            }
        } else if (status == PeerStatus.CONNECTED && phone && !controllingYou) {
            Text(
                stringResource(R.string.to_control_phone, peer.name),
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
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                autoSize = com.shazan.airbridge.ui.theme.fitOneLine(MaterialTheme.typography.labelMedium, 9f))
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
                Text(stringResource(R.string.cant_control_yet, noun), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cant_control_body),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.dismiss)) }
        }
        Row(Modifier.fillMaxWidth().padding(Dimens.md), horizontalArrangement = Arrangement.End) {
            PrimaryButton(stringResource(R.string.turn_on), onClick = onOpen, icon = Icons.Outlined.Settings)
        }
    }
}

@Composable
private fun TransferCard(t: TransferUi) {
    val ctx = LocalContext.current
    val open = t.received
    AbCard(onClick = open?.let { r -> { r.open(ctx) } }) {
        Column(Modifier.padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            Text(when {
                t.incoming && t.result != null -> stringResource(R.string.xf_received_from, t.peerName)
                t.incoming -> stringResource(R.string.receiving_from, t.peerName)
                else -> stringResource(R.string.sending_to, t.peerName)
            }, style = MaterialTheme.typography.titleSmall)
            Text(t.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.result == null) {
                val pctLabel = stringResource(R.string.percent_label)
                val p = if (t.bytesTotal > 0) (t.bytesDone.toFloat() / t.bytesTotal).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = pctLabel.format((p * 100).toInt()) },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(stringResource(R.string.xfer_progress, minOf(t.filesDone + 1, t.filesTotal), t.filesTotal, (p * 100).toInt(), formatSpeed(t.bytesPerSec)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val ok = t.result == stringResource(R.string.xfer_sent) || t.result == stringResource(R.string.xfer_received)
                val c = if (ok) AbTheme.status.success else AbTheme.status.danger
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, contentDescription = null, tint = c,
                        modifier = Modifier.size(Dimens.iconSm))
                    Spacer(Modifier.width(Dimens.sm))
                    Text(t.result, style = MaterialTheme.typography.labelLarge, color = c)
                    if (open != null) {
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.xfer_tap_open), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
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
                        toast(c, c.getString(R.string.camera_unavailable))
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
                IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Spacer(Modifier.width(Dimens.sm))
                Column {
                    Text(stringResource(R.string.scan_the_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.scan_the_code_sub), style = MaterialTheme.typography.bodySmall,
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
    val hasOverlay = remember(resumeTick) { Settings.canDrawOverlays(ctx) }
    // Back from the overlay settings with it granted: show the bubble now.
    LaunchedEffect(hasOverlay) { if (hasOverlay && AirBridgeService.isRunning) AirBridgeService.start(ctx, AirBridgeService.ACTION_REFRESH_BUBBLE) }

    val (_, requestNearby) = rememberNearbyPermission { ok ->
        if (ok) {
            visible = true
            NearbyTransport.setVisible(true)
            AirBridgeService.start(ctx)
        } else toast(ctx, ctx.getString(R.string.nearby_needed))
    }

    fun open(intent: Intent) = scope.launch {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, ctx.getString(R.string.not_available)) }
    }
    fun openOverlaySettings() = open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))

    if (renaming) {
        var name by remember { mutableStateOf(Identity.customName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.phone_name)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, singleLine = true,
                    label = { Text(stringResource(R.string.shown_on_other)) }, placeholder = { Text(Identity.defaultName) })
            },
            confirmButton = { TextButton(onClick = { Hub.renameSelf(name); renaming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // Long-press the title: hidden Wi-Fi Direct test screen (device tests S-W2/S-W3/S-W4).
    AbScaffold(title = stringResource(R.string.settings), onBack = onBack, onTitleLongPress = onOpenLinkDebug) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.this_phone))
            AbCard {
                InfoRow(title = stringResource(R.string.name), subtitle = myName, trailing = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename_phone)) }
                })
                RowDivider()
                SwitchRow(stringResource(R.string.visible_nearby), stringResource(R.string.visible_nearby_sub), visible, { on ->
                    if (on) requestNearby()
                    else {
                        visible = false
                        NearbyTransport.setVisible(false)
                        AirBridgeService.stopIfIdle(ctx)
                    }
                })
                RowDivider()
                InfoRow(title = stringResource(R.string.airweb), subtitle = stringResource(R.string.airweb_settings_sub), trailing = {
                    TextButton(onClick = { ctx.startActivity(AirWebActivity.intent(ctx, emptyList())) }, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.open)) }
                })
            }

            SectionHeader(stringResource(R.string.connection))
            AbCard {
                SwitchRow(stringResource(R.string.reconnect_auto), stringResource(R.string.reconnect_auto_sub), autoConnect, {
                    autoConnect = it
                    prefs.edit().putBoolean(WsClient.PREF_AUTO_CONNECT, it).apply()
                    if (it) WsClient.retryAllNow(unpause = true)
                })
            }

            SectionHeader(stringResource(R.string.clipboard))
            AbCard {
                SwitchRow(
                    stringResource(R.string.floating_bubble),
                    if (hasOverlay) stringResource(R.string.bubble_sub)
                    else stringResource(R.string.bubble_needs_overlay),
                    bubble && hasOverlay,
                    {
                        bubble = it
                        prefs.edit().putBoolean(FloatingBubble.PREF_ENABLED, it).apply()
                        if (it && !hasOverlay) openOverlaySettings()
                        else if (AirBridgeService.isRunning) AirBridgeService.start(ctx, AirBridgeService.ACTION_REFRESH_BUBBLE)
                    },
                )
            }
            com.shazan.airbridge.ui.ClipboardAutoSyncCard()

            com.shazan.airbridge.ui.PhoneNotifsSection()

            SectionHeader(stringResource(R.string.remote_control))
            AbCard {
                com.shazan.airbridge.ui.RemoteControlServiceRows()
                RowDivider()
                SwitchRow(stringResource(R.string.invert_x), null, invertX, { invertX = it; prefs.edit().putBoolean("INVERT_X", it).apply() })
                RowDivider()
                SwitchRow(stringResource(R.string.invert_y), null, invertY, { invertY = it; prefs.edit().putBoolean("INVERT_Y", it).apply() })
                // Only where OemGestureSwitches has verified keys for this manufacturer.
                if (com.shazan.airbridge.input.OsGestureGuard.switchAvailable) {
                    var osGesturesOff by remember { mutableStateOf(com.shazan.airbridge.input.OsGestureGuard.switchEnabled(prefs)) }
                    RowDivider()
                    SwitchRow(
                        stringResource(R.string.os_gestures_off),
                        if (canWriteSettings) stringResource(R.string.os_gestures_off_sub) else stringResource(R.string.needs_modify_settings),
                        osGesturesOff,
                        { on ->
                            osGesturesOff = on
                            prefs.edit().putBoolean(com.shazan.airbridge.input.OsGestureGuard.PREF_SWITCHES_OFF, on).apply()
                            if (on && !canWriteSettings) open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        },
                    )
                }
            }

            SectionHeader(stringResource(R.string.system_access))
            AbCard {
                AccessRow(stringResource(R.string.notifications), stringResource(R.string.notifications_sub), notificationsOn) {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                RowDivider()
                AccessRow(stringResource(R.string.overlay_perm), stringResource(R.string.overlay_perm_sub), hasOverlay) { openOverlaySettings() }
                RowDivider()
                AccessRow(stringResource(R.string.modify_settings), stringResource(R.string.modify_settings_sub), canWriteSettings) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                RowDivider()
                AccessRow(stringResource(R.string.dnd_access), stringResource(R.string.dnd_access_sub), dndAccess) {
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }

            SectionHeader(stringResource(R.string.about))
            AbCard {
                InfoRow(title = stringResource(R.string.about_version, BuildConfig.VERSION_NAME), subtitle = stringResource(R.string.about_sub),
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenLinkDebug() }) })
            }
            Spacer(Modifier.height(Dimens.xl))
        }
    }
}

@Composable
private fun AccessRow(title: String, subtitle: String, granted: Boolean, onOpen: () -> Unit) {
    InfoRow(title = title, subtitle = subtitle, trailing = {
        if (granted) StatusChip(stringResource(R.string.allowed), ChipTone.SUCCESS)
        else TextButton(onClick = onOpen, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.allow)) }
    })
}
