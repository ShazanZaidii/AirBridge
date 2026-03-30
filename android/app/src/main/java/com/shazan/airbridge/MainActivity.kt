package com.shazan.airbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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

@Composable
fun AirBridgeApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var connectedUrls by remember { mutableStateOf(prefs.getStringSet("WS_URLS", emptySet())?.toSet() ?: emptySet()) }
    var isAddingDevice by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions -> hasCameraPermission = permissions[Manifest.permission.CAMERA] == true }
    )

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { hasOverlayPermission = Settings.canDrawOverlays(context) }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    LaunchedEffect(connectedUrls, hasOverlayPermission) {
        if (connectedUrls.isNotEmpty() && hasOverlayPermission) {
            val intent = Intent(context, AirBridgeService::class.java).apply {
                putStringArrayListExtra("WS_URLS", ArrayList(connectedUrls))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    if (!hasOverlayPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AirBridge needs Overlay Permission for the Floating Ball", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    overlayPermissionLauncher.launch(intent)
                }) { Text("Grant Permission") }
            }
        }
    } else if (connectedUrls.isNotEmpty() && !isAddingDevice) {
        ConnectedScreen(
            urls = connectedUrls,
            onAddDevice = { isAddingDevice = true },
            onRemoveDevice = { url ->
                val newUrls = connectedUrls - url
                prefs.edit().putStringSet("WS_URLS", newUrls).apply()
                connectedUrls = newUrls

                if (newUrls.isEmpty()) {
                    context.stopService(Intent(context, AirBridgeService::class.java))
                } else {
                    val intent = Intent(context, AirBridgeService::class.java).apply {
                        putExtra("DISCONNECT_URL", url)
                    }
                    context.startService(intent)
                }
            }
        )
    } else if (hasCameraPermission) {
        CameraScannerScreen(
            onCancel = { isAddingDevice = false },
            onUrlScanned = { scannedUrl ->
                val newUrls = connectedUrls + scannedUrl
                prefs.edit().putStringSet("WS_URLS", newUrls).apply()
                connectedUrls = newUrls
                isAddingDevice = false
            }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required.", color = Color.White)
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

@Composable
fun ConnectedScreen(urls: Set<String>, onAddDevice: () -> Unit, onRemoveDevice: (String) -> Unit) {
    val context = LocalContext.current

    var isLive by remember { mutableStateOf(true) }
    var transferCurrentFiles by remember { mutableStateOf(-1) }
    var transferTotalFiles by remember { mutableStateOf(-1) }
    var transferProgressPct by remember { mutableStateOf(0) }
    var transferSpeed by remember { mutableStateOf("") }
    var transferEta by remember { mutableStateOf("") }

    DisposableEffect(context) {
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isLive = intent?.getBooleanExtra("isConnected", false) ?: false
            }
        }
        val progressReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                transferCurrentFiles = intent?.getIntExtra("currentFiles", -1) ?: -1
                transferTotalFiles = intent?.getIntExtra("totalFiles", -1) ?: -1
                transferProgressPct = intent?.getIntExtra("progressPct", 0) ?: 0
                transferSpeed = intent?.getStringExtra("speed") ?: ""
                transferEta = intent?.getStringExtra("eta") ?: ""
            }
        }

        ContextCompat.registerReceiver(context, stateReceiver, IntentFilter("AIRBRIDGE_STATE_UPDATE"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, progressReceiver, IntentFilter("AIRBRIDGE_TRANSFER_PROGRESS"), ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            context.unregisterReceiver(stateReceiver)
            context.unregisterReceiver(progressReceiver)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your Devices", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 24.dp, top = 32.dp))

        urls.forEach { url ->
            val uri = Uri.parse(url)
            val rawName = uri.getQueryParameter("name") ?: "Mac"

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rawName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(if (isLive) "   Active" else "⚠️ Reconnecting...", color = if (isLive) Color.Green else Color.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { onRemoveDevice(url) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove Device", tint = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // LIVE PROGRESS TRACKER
        if (transferTotalFiles > 0) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                    Text("Transferring files...", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { transferProgressPct / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFF60A5FA),
                        trackColor = Color(0xFF3B82F6).copy(alpha = 0.3f),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$transferCurrentFiles / $transferTotalFiles files", color = Color.LightGray, fontSize = 12.sp)
                        Text(transferSpeed, color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(transferEta, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
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

        Spacer(modifier = Modifier.height(24.dp))
        Text("AirBridge is running in the background.\nClipboard changes will sync to all devices.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}