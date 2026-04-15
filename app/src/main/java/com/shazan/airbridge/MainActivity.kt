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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

// Issue 7 Fix: Setup VPN check on launch
fun isVpnActive(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AirBridgeApp()
                }
            }
        }
    }
}

@Composable
fun AirBridgeApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)

    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var connectedUrls by remember { mutableStateOf(prefs.getStringSet("WS_URLS", emptySet())?.toSet() ?: emptySet()) }
    var autoPairEnabled by remember { mutableStateOf(prefs.getBoolean("AUTO_CONNECT", true)) }

    var currentScreen by remember { mutableStateOf(if (connectedUrls.isEmpty()) "DISCOVERY" else "CONNECTED") }
    var showVpnWarning by remember { mutableStateOf(isVpnActive(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[Manifest.permission.CAMERA] == true
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(connectedUrls, hasOverlayPermission, autoPairEnabled) {
        if (hasOverlayPermission) {
            val intent = Intent(context, AirBridgeService::class.java).apply {
                putStringArrayListExtra("WS_URLS", ArrayList(connectedUrls))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AirBridge needs Overlay Permission", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    overlayPermissionLauncher.launch(intent)
                }) { Text("Grant Permission") }
            }
        }
    } else {
        when (currentScreen) {
            "CONNECTED" -> ConnectedScreen(
                urls = connectedUrls,
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
                        context.startService(Intent(context, AirBridgeService::class.java).putExtra("DISCONNECT_URL", url))
                    }
                }
            )
            "DISCOVERY" -> DiscoveryScreen(
                onDevicePaired = { url ->
                    val newUri = Uri.parse(url)
                    val newHostIp = newUri.host

                    // Filter out any older connections from this exact same IP
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Camera permission required", color = Color.White) }
                }
            }
        }
    }
}

data class DiscoveredMac(val name: String, val url: String)

@Composable
fun DiscoveryScreen(onDevicePaired: (String) -> Unit, onCancel: () -> Unit, onOpenScanner: () -> Unit, showCancel: Boolean) {
    val context = LocalContext.current
    var discoveredDevices by remember { mutableStateOf(listOf<DiscoveredMac>()) }

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

                        val cleanUrl = "ws://$hostIp:$port?name=${Uri.encode(name)}"

                        // Deduplicate by IP Address instead of Name
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
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { nsdManager.stopServiceDiscovery(this) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { nsdManager.stopServiceDiscovery(this) }
        }

        nsdManager.discoverServices("_airbridge._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        onDispose { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Looking for Macs...", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp, top = 32.dp))
        Text("Ensure both devices are on the same Wi-Fi", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp))

        if (discoveredDevices.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF4285F4))
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(discoveredDevices) { mac ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onDevicePaired(mac.url) }
                    ) {
                        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(mac.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Cancel", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun ConnectedScreen(urls: Set<String>, autoPairEnabled: Boolean, onAutoPairChange: (Boolean) -> Unit, onAddDevice: () -> Unit, onRemoveDevice: (String) -> Unit) {
    val context = LocalContext.current

    var activeMacs by remember { mutableStateOf(AirBridgeService.getActiveUrls()) }
    var isMouseControlEnabled by remember { mutableStateOf(AirBridgeInputService.instance != null) }

    var transferCurrentFiles by remember { mutableStateOf(0) }
    var transferTotalFiles by remember { mutableStateOf(0) }
    var transferSpeed by remember { mutableStateOf("") }
    var transferEta by remember { mutableStateOf("") }
    var transferProgress by remember { mutableStateOf(0f) }

    // Bulletproof polling loop to ensure UI never gets stuck
    LaunchedEffect(Unit) {
        while(true) {
            isMouseControlEnabled = AirBridgeInputService.instance != null
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

        ContextCompat.registerReceiver(context, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(stateReceiver) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your Devices", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 24.dp, top = 32.dp))

        urls.forEach { url ->
            val uri = Uri.parse(url)
            val rawName = uri.getQueryParameter("name") ?: "Mac"

            // Match Base URL only (IP:Port) to avoid query parameter encoding mismatches
            val baseUrl = url.substringBefore("?")
            val isLive = activeMacs.any { it.startsWith(baseUrl) }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rawName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(if (isLive) "🟢 Active" else "⚠️ Disconnected", color = if (isLive) Color.Green else Color.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { onRemoveDevice(url) }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red.copy(alpha = 0.8f)) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Auto-Reconnect to known devices", color = Color.White, fontSize = 16.sp)
            Switch(checked = autoPairEnabled, onCheckedChange = onAutoPairChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4285F4), checkedTrackColor = Color(0xFF4285F4).copy(alpha = 0.5f)))
        }

        Button(
            onClick = onAddDevice,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add another Mac", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        if (!isMouseControlEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Enable Mouse Control (Accessibility)", color = Color(0xFFFFB74D), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CameraScannerScreen(onCancel: () -> Unit, onUrlScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                    } catch (exc: Exception) {}
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AirBridge", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Scan the QR code on your Mac", color = Color.White, modifier = Modifier.padding(top = 8.dp))

            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))) {
                Text("Cancel")
            }
        }
    }
}