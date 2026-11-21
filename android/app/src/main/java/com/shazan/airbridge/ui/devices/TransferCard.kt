package com.shazan.airbridge.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.shazan.airbridge.R
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.TransferUi
import com.shazan.airbridge.transfer.formatSpeed
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.Dimens

@Composable
internal fun TransferCard(t: TransferUi) {
    val ctx = LocalContext.current
    val open = t.received
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(if (t.incoming) R.string.xfer_cancel_recv_q else R.string.xfer_cancel_send_q, t.fileName)) },
            confirmButton = { TextButton(onClick = { asking = false; ConnectionHub.cancelTransfer(t.key) }) { Text(stringResource(R.string.xfer_cancel_yes), color = AbTheme.status.danger) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.xfer_cancel_keep)) } },
        )
    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (t.cancelling) stringResource(R.string.xfer_cancelling)
                        else stringResource(R.string.xfer_progress, minOf(t.filesDone + 1, t.filesTotal), t.filesTotal, (p * 100).toInt(), formatSpeed(t.bytesPerSec)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (!t.cancelling) TextButton(onClick = { asking = true }, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.cancel)) }
                }
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
