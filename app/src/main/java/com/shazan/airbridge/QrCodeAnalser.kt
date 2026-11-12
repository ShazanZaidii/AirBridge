package com.shazan.airbridge

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports the QR codes in view (all of them, so the screen can tell a pairing code from a web link). While it is
 * unpaused a code is reported once; call [reset] to listen again, [close] when done. [zoomTo] lets ML Kit zoom
 * the camera in by itself when a code is too small to read (return true if the zoom was applied).
 */
class QrCodeAnalyzer(
    maxZoom: Float = 5f,
    zoomTo: ((Float) -> Boolean)? = null,
    private val onCodes: (List<String>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).apply {
            if (zoomTo != null) setZoomSuggestionOptions(ZoomSuggestionOptions.Builder { r -> zoomTo(r) }.setMaxSupportedZoomRatio(maxZoom).build())
        }.build()
    )
    private val paused = AtomicBoolean(false)

    fun reset() = paused.set(false)
    fun close() = scanner.close()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (paused.get() || mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val values = codes.mapNotNull { it.rawValue }.filter { it.isNotBlank() }
                if (values.isNotEmpty() && paused.compareAndSet(false, true)) onCodes(values)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
