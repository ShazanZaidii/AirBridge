package com.shazan.airbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.SecondaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The QR scanner: live camera that reads by itself (and zooms in on a small code), a shutter that takes a still
 * and reads that (better for a dense or distant code), a picker for a screenshot or photo, and a torch.
 * It tells a pairing code from an AirWeb link, a web link, a Wi-Fi code or plain text and says what it found
 * instead of staying silent.
 *
 * [onPair] gets a pairing link; it returns true when pairing started (and the screen is left).
 */
@Composable
fun QrScannerScreen(onCancel: () -> Unit, onPair: (String) -> Boolean) {
    BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val preview = remember { Preview.Builder().build() }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val still = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    val cameraHolder = remember { arrayOfNulls<Camera>(1) }
    val analyzerHolder = remember { arrayOfNulls<QrCodeAnalyzer>(1) }
    var note by remember { mutableStateOf<QrKind?>(null) }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    /** One look at what was read (live or from a still). */
    fun handle(codes: List<String>, fromStill: Boolean) {
        val kind = QrKind.best(codes)
        when (kind) {
            null -> {
                if (fromStill) Toast.makeText(ctx, ctx.getString(R.string.qr_none_found), Toast.LENGTH_SHORT).show()
                analyzerHolder[0]?.reset()
            }
            is QrKind.Pair -> if (!onPair(kind.raw)) scope.launch { delay(2_000); analyzerHolder[0]?.reset() } // onPair says why
            else -> note = kind // the live reader stays quiet until the card is dismissed
        }
    }

    val analysis = remember {
        val analyzer = QrCodeAnalyzer(
            zoomTo = { r ->
                val cam = cameraHolder[0]
                val max = cam?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                if (cam != null && max > 1f) { cam.cameraControl.setZoomRatio(r.coerceIn(1f, max)); true } else false
            },
            onCodes = { codes -> ContextCompat.getMainExecutor(ctx).execute { handle(codes, false) } },
        )
        analyzerHolder[0] = analyzer
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            .also { it.setAnalyzer(executor, analyzer) }
    }

    fun decode(img: InputImage) {
        still.process(img)
            .addOnSuccessListener { codes -> busy = false; handle(codes.mapNotNull { it.rawValue }, true) }
            .addOnFailureListener { busy = false; Toast.makeText(ctx, ctx.getString(R.string.qr_none_found), Toast.LENGTH_SHORT).show() }
    }

    fun shutter() {
        if (busy) return
        busy = true
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try { image.toBitmap() } catch (_: Exception) { null }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                ContextCompat.getMainExecutor(ctx).execute {
                    if (bmp == null) { busy = false; Toast.makeText(ctx, ctx.getString(R.string.camera_unavailable), Toast.LENGTH_SHORT).show() }
                    else decode(InputImage.fromBitmap(bmp, rotation))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                ContextCompat.getMainExecutor(ctx).execute { busy = false; Toast.makeText(ctx, ctx.getString(R.string.camera_unavailable), Toast.LENGTH_SHORT).show() }
            }
        })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            try { decode(InputImage.fromFilePath(ctx, uri)) }
            catch (_: Exception) { busy = false; Toast.makeText(ctx, ctx.getString(R.string.qr_none_found), Toast.LENGTH_SHORT).show() }
        }
    }

    // Unbind the camera and stop the analyzer thread when leaving the scanner.
    DisposableEffect(Unit) {
        onDispose {
            try { if (providerFuture.isDone) providerFuture.get().unbind(preview, analysis, capture) } catch (_: Exception) {}
            analysis.clearAnalyzer()
            analyzerHolder[0]?.close()
            still.close()
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
                            val cam = providerFuture.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                            cameraHolder[0] = cam
                            hasTorch = cam.cameraInfo.hasFlashUnit()
                        }
                    } catch (_: Exception) {
                        Toast.makeText(c, c.getString(R.string.camera_unavailable), Toast.LENGTH_SHORT).show()
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

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(Dimens.md),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
        ) {
            note?.let { k ->
                ScanNote(k, onAgain = { note = null; analyzerHolder[0]?.reset() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundAction(Icons.Outlined.PhotoLibrary, stringResource(R.string.qr_choose_photo)) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Shutter(enabled = !busy, onClick = ::shutter)
                if (hasTorch) RoundAction(
                    if (torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    stringResource(R.string.qr_torch),
                ) { torch = !torch; cameraHolder[0]?.cameraControl?.enableTorch(torch) }
                else Spacer(Modifier.size(52.dp))
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(52.dp).semantics { contentDescription = label },
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) } }
}

/** The big round button: takes a still and reads it. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.qr_capture)
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (enabled) 1f else 0.5f),
        border = BorderStroke(4.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.size(72.dp).semantics { contentDescription = label },
    ) {}
}

/** What was found when it is not a pairing code. */
@Composable
private fun ScanNote(kind: QrKind, onAgain: () -> Unit) {
    val ctx = LocalContext.current
    val (title, detail) = when (kind) {
        is QrKind.AirWeb -> stringResource(R.string.qr_airweb_title) to stringResource(R.string.qr_airweb_sub, kind.host)
        is QrKind.Web -> stringResource(R.string.qr_web_title) to kind.url
        is QrKind.Wifi -> stringResource(R.string.qr_wifi_title) to kind.ssid
        is QrKind.OtherAirBridge -> stringResource(R.string.qr_old_title) to stringResource(R.string.qr_old_sub)
        is QrKind.Other -> stringResource(R.string.qr_other_title) to kind.text
        is QrKind.Pair -> "" to ""
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                val link = (kind as? QrKind.AirWeb)?.url ?: (kind as? QrKind.Web)?.url
                val text = when (kind) { is QrKind.Other -> kind.text; is QrKind.Web -> kind.url; is QrKind.AirWeb -> kind.url; else -> null }
                if (link != null) PrimaryButton(stringResource(R.string.qr_open), modifier = Modifier.weight(1f), onClick = {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: Exception) { Toast.makeText(ctx, ctx.getString(R.string.not_available), Toast.LENGTH_SHORT).show() }
                })
                if (text != null && kind !is QrKind.AirWeb) SecondaryButton(stringResource(R.string.qr_copy), icon = Icons.Outlined.ContentCopy, modifier = Modifier.weight(1f), onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("QR", text))
                })
                SecondaryButton(stringResource(R.string.qr_scan_again), modifier = Modifier.weight(1f), onClick = onAgain)
            }
        }
    }
}
