package com.shazan.airbridge.ui.devices

import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.R
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.PrimaryButton

/**
 * Top-of-list warning while the core accessibility service ("AirBridge Universal Control") is off:
 * the computer would otherwise try to move its cursor onto a phone that can't show it (§8.1
 * `unavailable`). The optional typing service is never mentioned here.
 */
@Composable
internal fun RemoteControlSetupCard(noun: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val warn = AbTheme.status.warning
    AbCard(Modifier.border(1.dp, warn.copy(alpha = 0.6f), MaterialTheme.shapes.large)) {
        Row(Modifier.fillMaxWidth().padding(start = Dimens.md, top = Dimens.md, end = Dimens.xs), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = warn,
                modifier = Modifier.padding(top = 2.dp).size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(stringResource(R.string.cant_control_yet, noun), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cant_control_body),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.dismiss)) }
        }
        Row(Modifier.fillMaxWidth().padding(Dimens.md), horizontalArrangement = Arrangement.End) {
            PrimaryButton(stringResource(R.string.turn_on), onClick = onOpen, icon = Icons.Outlined.Settings)
        }
    }
}
