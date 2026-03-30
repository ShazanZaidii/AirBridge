package com.shazan.airbridge

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

    var connectedUrl by remember { mutableStateOf(prefs.getString("WS_URL", null)) }

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

    LaunchedEffect(connectedUrl, hasOverlayPermission) {
        if (connectedUrl != null && hasOverlayPermission) {
            val intent = Intent(context, AirBridgeService::class.java).apply { putExtra("WS_URL", connectedUrl) }
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
    } else if (connectedUrl != null) {
        ConnectedScreen(
            url = connectedUrl!!,
            onDisconnect = {
                prefs.edit().remove("WS_URL").apply()
                connectedUrl = null
                context.stopService(Intent(context, AirBridgeService::class.java))
            }
        )
    } else if (hasCameraPermission) {
        CameraScannerScreen { scannedUrl ->
            prefs.edit().putString("WS_URL", scannedUrl).apply()
            connectedUrl = scannedUrl
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required.", color = Color.White)
        }
    }
}

@Composable
fun CameraScannerScreen(onUrlScanned: (String) -> Unit) {
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
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        onUrlScanned(url)
                                    }
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
        }
    }
}

@Composable
fun ConnectedScreen(url: String, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))
        ) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✅ Connected", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Green)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Syncing clipboard to:", color = Color.LightGray, fontSize = 14.sp)
                Text(url, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("You can safely close this app.\nAirBridge is running in the background.", color = Color.Gray, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Text("Disconnect & Stop Bubble", color = Color.White)
                }
            }
        }
    }
}