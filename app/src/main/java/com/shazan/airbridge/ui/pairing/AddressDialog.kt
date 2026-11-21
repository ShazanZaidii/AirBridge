package com.shazan.airbridge.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.shazan.airbridge.R
import com.shazan.airbridge.ui.theme.Dimens

/** "192.168.1.20" or "192.168.1.20:47633" → pair with whatever AirBridge device answers there. */
@Composable
internal fun AddressDialog(onDismiss: () -> Unit, onConnect: (host: String, port: Int) -> Unit) {
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
