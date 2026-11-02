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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.net.DiscoveredHost
import com.shazan.airbridge.net.Discovery
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.Identity
import com.shazan.airbridge.net.NearbyTransport
import com.shazan.airbridge.net.NetEvent
import com.shazan.airbridge.net.PairLink
import com.shazan.airbridge.net.PairStore
import com.shazan.airbridge.net.PairedPeer
import com.shazan.airbridge.net.PeerStatus
import com.shazan.airbridge.net.TransferUi
import com.shazan.airbridge.net.WsClient
import com.shazan.airbridge.net.formatSpeed
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

private val Bg = Color(0xFF1E1E1E)
private val CardBg = Color(0xFF2C2C2C)
private val Accent = Color(0xFF4285F4)
private val Good = Color(0xFF34A853)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Browse mDNS while visible (discovery list, QR address lookup); the service keeps its own reference.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = Discovery.acquire()
            override fun onStop(owner: LifecycleOwner) = Discovery.release()
        })
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                    Surface(Modifier.fillMaxSize(), color = Bg) { AirBridgeRoot() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AirBridgeService.isRunning) WsClient.retryAllNow(unpause = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipboardSync.onAppFocused(this)
    }
}

private enum class Screen { CONNECTED, DISCOVERY, SCANNER, SETTINGS }

private data class PairingUi(val name: String, val error: String? = null)

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun pairFailText(reason: String) = when (reason) {
    "bad_code" -> "Wrong code. Check the code shown on the computer."
    "locked" -> "Too many attempts. Wait a minute and try again."
    "not_pairing" -> "That device isn't accepting pairing right now."
    "bad_proof" -> "The device's reply didn't verify. Try again."
    "" -> "Couldn't connect."
    else -> "Couldn't connect ($reason)."
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
    val resumeTick = rememberResumeTick()

    var screen by rememberSaveable { mutableStateOf(if (PairStore.isEmpty()) Screen.DISCOVERY else Screen.CONNECTED) }
    var showVpnWarning by remember { mutableStateOf(isVpnActive(ctx)) }
    var pairing by remember { mutableStateOf<PairingUi?>(null) }
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
                        pairing = null
                        screen = Screen.CONNECTED
                        toast(ctx, "Paired with ${e.peerName}")
                    } else {
                        if (pairing != null) pairing = pairing?.copy(error = pairFailText(e.reason))
                        else toast(ctx, "Pairing failed: ${pairFailText(e.reason)}")
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
        if (paired.isEmpty() && screen == Screen.CONNECTED) screen = Screen.DISCOVERY
    }

    fun startPair(name: String, host: String, port: Int, id: String?, secret: ByteArray) {
        AirBridgeService.start(ctx)
        pairing = PairingUi(name)
        WsClient.pair(host, port, id, com.shazan.airbridge.net.PairMode.Qr(secret), name, "")
    }

    if (showVpnWarning) {
        AlertDialog(
            onDismissRequest = { showVpnWarning = false },
            title = { Text("VPN Detected") },
            text = { Text("You have a VPN active. AirBridge uses local network connections and may not work correctly unless your VPN allows local network access (split tunneling).") },
            confirmButton = { TextButton(onClick = { showVpnWarning = false }) { Text("I Understand") } },
        )
    }

    pairing?.let { p ->
        AlertDialog(
            onDismissRequest = { if (p.error != null) pairing = null },
            title = { Text(if (p.error == null) "Pairing with ${p.name}…" else "Pairing failed") },
            text = {
                if (p.error == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Accent)
                    Spacer(Modifier.width(16.dp))
                    Text("Confirming the code with ${p.name}")
                } else Text(p.error)
            },
            confirmButton = { if (p.error != null) TextButton(onClick = { pairing = null }) { Text("OK") } },
        )
    }

    pending.firstOrNull()?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pair with ${p.name}?") },
            text = {
                Column {
                    Text("Make sure both phones show the same code:")
                    Text(p.digits, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
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
        Screen.CONNECTED -> ConnectedScreen(
            paired = paired,
            onOpenSettings = { screen = Screen.SETTINGS },
            onAddDevice = { screen = Screen.DISCOVERY },
            onForget = { peer ->
                Hub.forget(peer.id)
                if (PairStore.isEmpty()) {
                    AirBridgeService.stopIfIdle(ctx)
                    screen = Screen.DISCOVERY
                }
            },
        )

        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.CONNECTED })

        Screen.DISCOVERY -> DiscoveryScreen(
            pairedIds = paired.map { it.id }.toSet(),
            onPairWithCode = { host, code -> startPair(host.name, host.host, host.port, host.id, code.toByteArray(Charsets.UTF_8)) },
            onCancel = { if (paired.isNotEmpty()) screen = Screen.CONNECTED },
            onOpenScanner = { screen = Screen.SCANNER },
            showCancel = paired.isNotEmpty(),
        )

        Screen.SCANNER -> CameraScannerScreen(
            onCancel = { screen = Screen.DISCOVERY },
            onScanned = { raw ->
                val link = PairLink.parse(raw)
                val addr = link?.let { Discovery.lookup(it.id) ?: it.addr?.let { a -> a to it.port } }
                when {
                    link == null -> { toast(ctx, "Not an AirBridge v2 pairing code"); false }
                    addr == null || addr.second !in 1..65535 -> { toast(ctx, "${link.name} not found on this network"); false }
                    else -> {
                        screen = Screen.DISCOVERY
                        startPair(link.name, addr.first, addr.second, link.id, link.secret)
                        true
                    }
                }
            },
        )
    }
}

@Composable
private fun OverlayPermissionScreen() {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp).verticalScroll(rememberScrollState())) {
            Text("Overlay Permission Required", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                "AirBridge requires the 'Display over other apps' permission to sync your clipboard " +
                    "(floating bubble) and to receive control from your computer.",
                color = Color.LightGray, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Text("Step 1: Allow Restricted Settings", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Tap below to open App Info. Tap the 3 dots (⋮) in the top right, and select 'Allow restricted settings'.",
                color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            ) { Text("Open App Info", color = Color.White) }
            Spacer(Modifier.height(24.dp))
            Text("Step 2: Grant Permission", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Once Step 1 is complete, tap below to turn on the toggle.",
                color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("Enable Overlay", color = Color.White) }
        }
    }
}

@Composable
private fun PlatformIcon(plat: String, size: Int = 36) {
    Image(painterResource(DirectShare.platformIcon(plat)), contentDescription = plat, modifier = Modifier.size(size.dp))
}

private fun platLabel(plat: String) = when (plat) {
    "macos" -> "Mac"; "windows" -> "Windows"; "linux" -> "Linux"; "android" -> "Android"; "ios" -> "iPhone/iPad"; else -> plat
}

@Composable
private fun DarkCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) { content() }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.5f)))
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

@Composable
private fun DiscoveryScreen(
    pairedIds: Set<String>,
    onPairWithCode: (DiscoveredHost, String) -> Unit,
    onCancel: () -> Unit,
    onOpenScanner: () -> Unit,
    showCancel: Boolean,
) {
    if (showCancel) BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val hosts by Discovery.hosts.collectAsState()
    val androids by NearbyTransport.found.collectAsState()
    var codeFor by remember { mutableStateOf<DiscoveredHost?>(null) }

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

    codeFor?.let { host ->
        var code by remember(host) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { codeFor = null },
            title = { Text("Pair with ${host.name}") },
            text = {
                Column {
                    Text("Enter the 6-digit code shown on ${host.name}.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = MaterialTheme.typography.headlineMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == 6, onClick = { codeFor = null; onPairWithCode(host, code) }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { codeFor = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Looking for Devices...", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp, top = 32.dp))
        Text(if (nearbyOk) "Searching Wi-Fi and nearby phones" else "Searching Wi-Fi", color = Color.Gray, fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 24.dp))

        val hostList = hosts.values.sortedBy { it.name.lowercase() }
        val androidList = androids.filter { it.peerId == null || it.peerId !in pairedIds }
        if (hostList.isEmpty() && androidList.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(hostList, key = { it.id }) { host ->
                    val isPaired = host.id in pairedIds
                    DarkCard(onClick = if (isPaired) null else ({ codeFor = host })) {
                        Row(Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlatformIcon(host.plat)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(host.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(if (isPaired) "Already paired" else "${platLabel(host.plat)} · tap to enter code",
                                    color = if (isPaired) Good else Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }
                items(androidList, key = { "nearby:" + it.endpointId }) { a ->
                    DarkCard(onClick = {
                        AirBridgeService.start(ctx)
                        NearbyTransport.requestConnection(a.endpointId)
                        toast(ctx, "Requesting ${a.name}…")
                    }) {
                        Row(Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlatformIcon("android")
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(a.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Nearby Android", color = Good, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = { if (granted(ctx, Manifest.permission.CAMERA)) onOpenScanner() else cameraLauncher.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Scan QR Code Instead", color = Color(0xFF60A5FA)) }

        if (!nearbyOk) TextButton(onClick = requestNearby) { Text("Allow nearby phones", color = Color(0xFF60A5FA)) }

        if (showCancel) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cancel", color = Color.White, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun ConnectedScreen(
    paired: List<PairedPeer>,
    onOpenSettings: () -> Unit,
    onAddDevice: () -> Unit,
    onForget: (PairedPeer) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE) }
    val status by Hub.status.collectAsState()
    val transfers by Hub.transfers.collectAsState()
    val myName by Identity.nameFlow.collectAsState()
    var autoConnect by remember { mutableStateOf(prefs.getBoolean(WsClient.PREF_AUTO_CONNECT, true)) }
    var visible by remember { mutableStateOf(prefs.getBoolean(NearbyTransport.PREF_VISIBLE, false)) }
    var renaming by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<PairedPeer?>(null) }

    val (_, requestNearby) = rememberNearbyPermission { ok ->
        if (ok) {
            visible = true
            NearbyTransport.setVisible(true)
            AirBridgeService.start(ctx)
        } else toast(ctx, "Nearby permissions are needed for phone-to-phone")
    }

    if (renaming) {
        var name by remember { mutableStateOf(Identity.customName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename this phone") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, singleLine = true,
                    placeholder = { Text(Build.MODEL) })
            },
            confirmButton = { TextButton(onClick = { Hub.renameSelf(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    forgetting?.let { p ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${p.name}?") },
            text = { Text("You'll need to pair again to reconnect.") },
            confirmButton = { TextButton(onClick = { forgetting = null; onForget(p) }) { Text("Forget", color = Color(0xFFE57373)) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your Devices", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
        }

        DarkCard {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This phone", color = Color.Gray, fontSize = 13.sp)
                    Text(myName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.White) }
            }
        }

        transfers.values.sortedBy { it.key }.forEach { TransferCard(it) }

        paired.forEach { peer ->
            val st = status[peer.id] ?: PeerStatus.OFFLINE
            val (label, color) = when (st) {
                PeerStatus.CONNECTED -> "● Connected" to Good
                PeerStatus.CONNECTING -> "Connecting…" to Color(0xFFFFD54F)
                PeerStatus.RECONNECTING -> "Reconnecting…" to Color(0xFFFFB74D)
                PeerStatus.OFFLINE -> "Offline" to Color.Gray
            }
            DarkCard {
                Row(Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PlatformIcon(peer.plat)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("$label · ${if (peer.via == "nearby") "Nearby" else "Wi-Fi"}", color = color, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { forgetting = peer }) {
                        Icon(Icons.Default.Delete, contentDescription = "Forget", tint = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }
        }

        SwitchRow("Auto-Reconnect", "Connect to paired devices automatically", autoConnect) {
            autoConnect = it
            prefs.edit().putBoolean(WsClient.PREF_AUTO_CONNECT, it).apply()
            if (it) WsClient.retryAllNow(unpause = true)
        }
        SwitchRow("Device Visibility", if (visible) "Visible to nearby phones" else "Turn on so nearby phones can find this one", visible) { on ->
            if (on) requestNearby()
            else {
                visible = false
                NearbyTransport.setVisible(false)
                AirBridgeService.stopIfIdle(ctx)
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddDevice, colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Add another Device", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TransferCard(t: TransferUi) {
    DarkCard {
        Column(Modifier.padding(20.dp)) {
            Text(
                (if (t.incoming) "Receiving from " else "Sending to ") + t.peerName,
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            )
            Text(t.fileName, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            if (t.result == null) {
                val p = if (t.bytesTotal > 0) (t.bytesDone.toFloat() / t.bytesTotal).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Spacer(Modifier.height(6.dp))
                Text("${minOf(t.filesDone + 1, t.filesTotal)}/${t.filesTotal} files · ${(p * 100).toInt()}% · ${formatSpeed(t.bytesPerSec)}",
                    color = Color.Gray, fontSize = 12.sp)
            } else {
                val ok = t.result == "Sent" || t.result == "Received"
                Text(t.result, color = if (ok) Good else Color(0xFFE57373), fontWeight = FontWeight.Bold)
            }
        }
    }
}

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
        Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AirBridge", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Scan the QR code on your computer", color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))) { Text("Cancel") }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE) }
    val resumeTick = rememberResumeTick()
    val scope = rememberCoroutineScope()

    var invertX by remember { mutableStateOf(prefs.getBoolean("INVERT_X", false)) }
    var invertY by remember { mutableStateOf(prefs.getBoolean("INVERT_Y", false)) }
    var bubble by remember { mutableStateOf(prefs.getBoolean(FloatingBubble.PREF_ENABLED, true)) }

    val accessibilityOn = remember(resumeTick) {
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.startsWith(ctx.packageName + "/") }
    }
    val canWriteSettings = remember(resumeTick) { Settings.System.canWrite(ctx) }
    val dndAccess = remember(resumeTick) { ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true }
    val notificationsOn = remember(resumeTick) { ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true }

    fun open(intent: Intent) = scope.launch {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, "Not available on this device") }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 32.dp, bottom = 24.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        SectionTitle("Universal Control")
        DarkCard {
            Column(Modifier.padding(20.dp)) {
                if (!accessibilityOn) {
                    Button(
                        onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 12.dp), shape = RoundedCornerShape(12.dp),
                    ) { Text("Enable Mouse Control", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold) }
                } else {
                    Text("🟢 Mouse Control Enabled", color = Color.Green, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                SwitchRow("Invert Horizontal Arrows", "Swap Left/Right key actions", invertX) {
                    invertX = it; prefs.edit().putBoolean("INVERT_X", it).apply()
                }
                SwitchRow("Invert Vertical Arrows", "Swap Up/Down key actions", invertY) {
                    invertY = it; prefs.edit().putBoolean("INVERT_Y", it).apply()
                }
            }
        }

        SectionTitle("Clipboard")
        DarkCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SwitchRow("Floating bubble", "Tap the bubble to send your clipboard", bubble) {
                    bubble = it
                    prefs.edit().putBoolean(FloatingBubble.PREF_ENABLED, it).apply()
                    if (AirBridgeService.isRunning) AirBridgeService.start(ctx, AirBridgeService.ACTION_REFRESH_BUBBLE)
                }
            }
        }

        SectionTitle("System access")
        DarkCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                AccessRow("Notifications", "Transfer progress and pairing requests", notificationsOn) {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                AccessRow("Modify system settings", "Brightness keys from your computer", canWriteSettings) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                AccessRow("Do Not Disturb access", "Toggle Do Not Disturb from your computer", dndAccess) {
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp, top = 8.dp))
}

@Composable
private fun AccessRow(title: String, subtitle: String, granted: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        if (granted) Text("Allowed", color = Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        else TextButton(onClick = onOpen) { Text("Allow", color = Color(0xFF60A5FA)) }
    }
}
