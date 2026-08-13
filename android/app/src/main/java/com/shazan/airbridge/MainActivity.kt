package com.shazan.airbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.Executors

// Issue 7 Fix: Setup VPN check on launch
fun isVpnActive(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    return false
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            // The nuclear fix to ignore system font scaling
            val currentDensity = LocalDensity.current
            val fontScaleOverride = Density(density = currentDensity.density, fontScale = 1f)

            CompositionLocalProvider(LocalDensity provides fontScaleOverride) {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AirBridgeApp()
                    }
                }
            }
        }
    }
}

@Composable
fun AirBridgeApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var connectedUrls by remember {
        mutableStateOf(
            prefs.getStringSet("WS_URLS", emptySet())?.toSet() ?: emptySet()
        )
    }
    var autoPairEnabled by remember { mutableStateOf(prefs.getBoolean("AUTO_CONNECT", true)) }

    var currentScreen by remember { mutableStateOf(if (connectedUrls.isEmpty()) "DISCOVERY" else "CONNECTED") }
    var showVpnWarning by remember { mutableStateOf(isVpnActive(context)) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
            hasCameraPermission = p[Manifest.permission.CAMERA] == true
        }

    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
        }

    // Auto-detect overlay permission when returning to the app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        permissionLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(connectedUrls, hasOverlayPermission, autoPairEnabled) {
        if (hasOverlayPermission) {
            val intent = Intent(context, AirBridgeService::class.java).apply {
                putStringArrayListExtra("WS_URLS", ArrayList(connectedUrls))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(
                intent
            )
            else context.startService(intent)
        }
    }

    // Issue 7 Fix: Inject VPN Warning dialog
    if (showVpnWarning) {
        AlertDialog(
            onDismissRequest = { showVpnWarning = false },
            title = { Text("VPN Detected") },
            text = { Text("You have a VPN active. AirBridge uses local network connections and may not work correctly unless your VPN allows local network access (split tunneling).") },
            confirmButton = {
                TextButton(onClick = { showVpnWarning = false }) { Text("I Understand") }
            }
        )
    }

    if (!hasOverlayPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E)), // Explicit dark background added here
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Overlay Permission Required",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "AirBridge requires the 'Display over other apps' permission to sync " +
                            "your Clipboard with PC (and to control your mobile from PC later if you want)",
                    color = Color.LightGray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Step 1: Restricted Settings Bypass
                Text(
                    "Step 1: Allow Restricted Settings",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Tap below to open App Info. Tap the 3 dots (⋮) in the top right, and select 'Allow restricted settings'.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Text("Open App Info", color = Color.White)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Step 2: Grant the Overlay Permission
                Text("Step 2: Grant Permission", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Once Step 1 is complete, tap below to turn on the toggle.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                ) {
                    Text("Enable Overlay", color = Color.White)
                }
            }
        }
    } else {
        when (currentScreen) {
            "CONNECTED" -> ConnectedScreen(
                urls = connectedUrls,
                onOpenSettings = { currentScreen = "SETTINGS" },
                autoPairEnabled = autoPairEnabled,
                onAutoPairChange = {
                    autoPairEnabled = it
                    prefs.edit().putBoolean("AUTO_CONNECT", it).apply()
                },
                onAddDevice = { currentScreen = "DISCOVERY" },
                onRemoveDevice = { url ->
                    val newUrls = connectedUrls - url
                    prefs.edit().putStringSet("WS_URLS", newUrls).apply()
                    connectedUrls = newUrls
                    if (newUrls.isEmpty()) {
                        context.stopService(Intent(context, AirBridgeService::class.java))
                        currentScreen = "DISCOVERY"
                    } else {
                        context.startService(
                            Intent(context, AirBridgeService::class.java).putExtra(
                                "DISCONNECT_URL",
                                url
                            )
                        )
                    }
                }
            )

            "SETTINGS" -> SettingsScreen(onBack = { currentScreen = "CONNECTED" })

            "DISCOVERY" -> DiscoveryScreen(
                connectedUrls = connectedUrls, // NEW PARAMETER
                onDevicePaired = { url ->
                    val newUri = Uri.parse(url)
                    val newHostIp = newUri.host

                    val filteredUrls = connectedUrls.filterNot { existingUrl ->
                        Uri.parse(existingUrl).host == newHostIp
                    }.toSet()

                    val newUrls = filteredUrls + url
                    prefs.edit().putStringSet("WS_URLS", newUrls).apply()
                    connectedUrls = newUrls
                    currentScreen = "CONNECTED"
                },
                onCancel = { if (connectedUrls.isNotEmpty()) currentScreen = "CONNECTED" },
                onOpenScanner = { currentScreen = "SCANNER" },
                showCancel = connectedUrls.isNotEmpty()
            )

            "SCANNER" -> {
                if (hasCameraPermission) {
                    CameraScannerScreen(
                        onCancel = { currentScreen = "DISCOVERY" },
                        onUrlScanned = { url ->
                            val newUri = Uri.parse(url)
                            val rawName = newUri.getQueryParameter("name") ?: "Mac"
                            val newHostIp = newUri.host

                            // Filter out any ghost devices with the same Mac name
                            val filteredUrls = connectedUrls.filterNot { existingUrl ->
                                Uri.parse(existingUrl).host == newHostIp
                            }.toSet()

                            val newUrls = filteredUrls + url
                            prefs.edit().putStringSet("WS_URLS", newUrls).apply()
                            connectedUrls = newUrls
                            currentScreen = "CONNECTED"
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { Text("Camera permission required", color = Color.White) }
                }
            }
        }
    }
}

data class DiscoveredMac(val name: String, val url: String)
data class DiscoveredAndroid(val name: String, val endpointId: String) // NEW

@Composable
fun DiscoveryScreen(
    connectedUrls: Set<String>,
    onDevicePaired: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenScanner: () -> Unit,
    showCancel: Boolean
) {
    // Handle Android hardware back button
    if (showCancel) {
        BackHandler { onCancel() }
    }

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)

    var discoveredDevices by remember { mutableStateOf(listOf<DiscoveredMac>()) }
    var discoveredAndroids by remember { mutableStateOf(listOf<DiscoveredAndroid>()) }

    DisposableEffect(Unit) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val hostIp = serviceInfo.host.hostAddress ?: return
                        val port = serviceInfo.port
                        val name = serviceInfo.serviceName

                        if (connectedUrls.any { Uri.parse(it).host == hostIp }) return

                        val cleanUrl = "ws://$hostIp:$port?name=${Uri.encode(name)}"
                        discoveredDevices = discoveredDevices.filterNot {
                            Uri.parse(it.url).host == hostIp
                        } + DiscoveredMac(name, cleanUrl)
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                discoveredDevices = discoveredDevices.filterNot { it.name == service.serviceName }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(
            "_airbridge._tcp.",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )

        val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                discoveredAndroids = discoveredAndroids + DiscoveredAndroid(info.endpointName, endpointId)
            }
            override fun onEndpointLost(endpointId: String) {
                discoveredAndroids = discoveredAndroids.filterNot { it.endpointId == endpointId }
            }
        }

        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(context).startDiscovery(
            "com.shazan.airbridge.p2p",
            endpointDiscoveryCallback,
            options
        )

        // --- NEW UX: Automatically make device visible while on this screen ---
        context.startService(Intent(context, AirBridgeService::class.java).apply { action = "START_P2P_RECEIVE" })

        onDispose {
            nsdManager.stopServiceDiscovery(discoveryListener)
            Nearby.getConnectionsClient(context).stopDiscovery()

            // Revert visibility back to the global preference state when leaving the screen
            if (!prefs.getBoolean("P2P_VISIBLE", false)) {
                context.startService(Intent(context, AirBridgeService::class.java).apply { action = "STOP_P2P_RECEIVE" })
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Looking for Devices...",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp, top = 32.dp)
        )
        Text(
            "Searching Wi-Fi and Nearby Bluetooth",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (discoveredDevices.isEmpty() && discoveredAndroids.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF4285F4))
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(discoveredDevices) { device ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clickable { onDevicePaired(device.url) }
                    ) {
                        Row(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                            Text(device.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                items(discoveredAndroids) { androidDevice ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clickable {
                                val intent = Intent(context, AirBridgeService::class.java).apply {
                                    action = "CONNECT_P2P_ENDPOINT"
                                    putExtra("ENDPOINT_ID", androidDevice.endpointId)
                                }
                                context.startService(intent)
                                onCancel()
                            }
                    ) {
                        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(androidDevice.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Nearby Android", color = Color(0xFF34A853), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onOpenScanner, modifier = Modifier.padding(top = 16.dp)) {
            Text("Scan QR Code Instead", color = Color(0xFF60A5FA))
        }

        if (showCancel) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Cancel", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun ConnectedScreen(
    urls: Set<String>,
    onOpenSettings: () -> Unit,
    autoPairEnabled: Boolean,
    onAutoPairChange: (Boolean) -> Unit,
    onAddDevice: () -> Unit,
    onRemoveDevice: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)

    var activeMacs by remember { mutableStateOf(AirBridgeService.getActiveUrls()) }
    var p2pDevices by remember { mutableStateOf(mapOf<String, String>()) }

    // NEW: Shared visibility state
    var isVisibleForP2P by remember { mutableStateOf(prefs.getBoolean("P2P_VISIBLE", false)) }

    var transferCurrentFiles by remember { mutableStateOf(0) }
    var transferTotalFiles by remember { mutableStateOf(0) }
    var transferSpeed by remember { mutableStateOf("") }
    var transferEta by remember { mutableStateOf("") }
    var transferProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            val liveUrls = AirBridgeService.getActiveUrls()
            if (activeMacs != liveUrls) {
                activeMacs = liveUrls
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    DisposableEffect(context) {
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    "AIRBRIDGE_STATE_UPDATE" -> {
                        val activeList = intent.getStringArrayListExtra("ACTIVE_URLS") ?: arrayListOf()
                        activeMacs = activeList.toSet()

                        val p2pList = intent.getStringArrayListExtra("P2P_ENDPOINTS") ?: arrayListOf()
                        p2pDevices = p2pList.associate {
                            val parts = it.split("|")
                            parts[0] to (parts.getOrNull(1) ?: "Android Device")
                        }
                    }
                    "AIRBRIDGE_TRANSFER_UPDATE" -> {
                        if (intent.getBooleanExtra("isComplete", false)) {
                            transferTotalFiles = 0
                        } else {
                            transferCurrentFiles = intent.getIntExtra("currentFiles", 0)
                            transferTotalFiles = intent.getIntExtra("totalFiles", 0)
                            transferSpeed = intent.getStringExtra("speed") ?: ""
                            transferEta = intent.getStringExtra("eta") ?: ""
                            transferProgress = intent.getFloatExtra("progress", 0f)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("AIRBRIDGE_STATE_UPDATE")
            addAction("AIRBRIDGE_TRANSFER_UPDATE")
        }

        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(stateReceiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp, top = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Your Devices",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        urls.forEach { url ->
            val uri = Uri.parse(url)
            val rawName = uri.getQueryParameter("name") ?: "Mac"
            val baseUrl = url.substringBefore("?")
            val isLive = activeMacs.any { it.startsWith(baseUrl) }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rawName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            if (isLive) "🟢 Active" else "⚠️ Disconnected",
                            color = if (isLive) Color.Green else Color.Yellow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    IconButton(onClick = { onRemoveDevice(url) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }
        }

        p2pDevices.forEach { (endpointId, name) ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "🟢 P2P Active",
                            color = Color(0xFF34A853),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    IconButton(onClick = {
                        context.startService(
                            Intent(context, AirBridgeService::class.java).putExtra("DISCONNECT_P2P", endpointId)
                        )
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Device Visibility", color = Color.White, fontSize = 16.sp)
                Text(
                    if (isVisibleForP2P) "Visible to nearby devices" else "Toggle on to make this device discoverable",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = isVisibleForP2P,
                onCheckedChange = { checked ->
                    isVisibleForP2P = checked
                    prefs.edit().putBoolean("P2P_VISIBLE", checked).apply()
                    val actionStr = if (checked) "START_P2P_RECEIVE" else "STOP_P2P_RECEIVE"
                    context.startService(Intent(context, AirBridgeService::class.java).apply { action = actionStr })
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF4285F4),
                    checkedTrackColor = Color(0xFF4285F4).copy(alpha = 0.5f)
                )
            )
        }

        Button(
            onClick = onAddDevice,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Add another Device",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CameraScannerScreen(onCancel: () -> Unit, onUrlScanned: (String) -> Unit) {
    // Handle Android hardware back button
    BackHandler { onCancel() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                Executors.newSingleThreadExecutor(),
                                QrCodeAnalyzer { url ->
                                    ContextCompat.getMainExecutor(ctx).execute { onUrlScanned(url) }
                                }
                            )
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AirBridge", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Scan the QR code on your PC",
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)

    var invertX by remember { mutableStateOf(prefs.getBoolean("INVERT_X", false)) }
    var invertY by remember { mutableStateOf(prefs.getBoolean("INVERT_Y", false)) }
    var autoConnect by remember { mutableStateOf(prefs.getBoolean("AUTO_CONNECT", true)) }

    var isMouseControlEnabled by remember { mutableStateOf(AirBridgeInputService.instance != null) }

    LaunchedEffect(Unit) {
        while(true) {
            isMouseControlEnabled = AirBridgeInputService.instance != null
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 32.dp, bottom = 24.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // --- Networking Section ---
        Text("Networking", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Reconnect", color = Color.White, fontSize = 16.sp)
                        Text("Connect to known devices nearby automatically", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = autoConnect, onCheckedChange = {
                        autoConnect = it
                        prefs.edit().putBoolean("AUTO_CONNECT", it).apply()
                    })
                }
            }
        }

        // --- Universal Control Section ---
        Text("Universal Control", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                if (!isMouseControlEnabled) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Enable Mouse Control", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        "🟢 Mouse Control Enabled",
                        color = Color.Green,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Invert Horizontal Arrows", color = Color.White, fontSize = 16.sp)
                        Text("Swap Left/Right key actions", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = invertX, onCheckedChange = {
                        invertX = it
                        prefs.edit().putBoolean("INVERT_X", it).apply()
                    })
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Invert Vertical Arrows", color = Color.White, fontSize = 16.sp)
                        Text("Swap Up/Down key actions", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = invertY, onCheckedChange = {
                        invertY = it
                        prefs.edit().putBoolean("INVERT_Y", it).apply()
                    })
                }
            }
        }
    }
}