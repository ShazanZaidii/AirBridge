package com.shazan.airbridge.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.R
import com.shazan.airbridge.connection.Identity
import com.shazan.airbridge.connection.pairing.PairStore
import com.shazan.airbridge.ui.theme.Dimens

/**
 * This phone's pairing QR code (mode qr, §2.4): the other phone scans it with "Scan QR code" and pairs
 * at once, no digits to compare. The secret is fresh each time and dies when the dialog closes.
 */
@Composable
internal fun ShowQrDialog(onDismiss: () -> Unit) {
    val link by com.shazan.airbridge.connection.transport.LanHost.qrLink.collectAsState()
    val pairedCount = PairStore.peers.collectAsState().value.size
    val startCount = remember { pairedCount }
    DisposableEffect(Unit) {
        com.shazan.airbridge.connection.transport.LanHost.openQr()
        onDispose { com.shazan.airbridge.connection.transport.LanHost.closeQr() }
    }
    LaunchedEffect(pairedCount) { if (pairedCount > startCount) onDismiss() } // paired: done
    val addr = remember(link) { com.shazan.airbridge.connection.transport.LanHost.localAddresses().firstOrNull() }
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
