package com.shazan.airbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.AirBridgeInputService
import com.shazan.airbridge.AirBridgeTextService
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.StatusChip

private fun openSafely(ctx: android.content.Context, intent: Intent) {
    try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { Toast.makeText(ctx, "Not available on this device", Toast.LENGTH_SHORT).show() }
}

/**
 * The two accessibility services, for the "Remote control" card: the required core service
 * ("AirBridge Universal Control", gestures only) and the optional typing/clipboard service, shown as a
 * smaller secondary row. Both open the system accessibility settings (apps can't toggle them). The
 * optional one is never nagged about anywhere else.
 */
@Composable
fun RemoteControlServiceRows() {
    val ctx = LocalContext.current
    val coreOn by AirBridgeInputService.runningFlow.collectAsState()
    val textOn by AirBridgeTextService.runningFlow.collectAsState()
    val a11y = { openSafely(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    InfoRow(
        title = "Control from your computer",
        subtitle = "Required. \"AirBridge Universal Control\" shows the cursor, taps, scrolls and does back, home and volume. It can't read your screen.",
        trailing = {
            if (coreOn) StatusChip("On", ChipTone.SUCCESS)
            else TextButton(onClick = a11y, modifier = Modifier.height(Dimens.touch)) { Text("Turn on") }
        },
    )
    RowDivider()
    // Secondary, switch-style row: the switch shows the real state, tapping opens the system page.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = textOn, role = Role.Switch, onValueChange = { a11y() })
            .padding(start = Dimens.md * 2, end = Dimens.md, top = Dimens.sm, bottom = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Typing & clipboard (optional) · ${if (textOn) "On" else "Off"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "\"AirBridge Typing & Clipboard\" can read the text field you're typing in, so your computer can type, " +
                    "copy and paste there, and it notices when you copy so the text is sent automatically. " +
                    "Banking and UPI apps may warn about accessibility services that read the screen: switch it off while paying. " +
                    "Without it, remote control still works and the bubble sends your clipboard.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Dimens.md))
        Switch(checked = textOn, onCheckedChange = null)
    }
    if (coreOn || textOn) {
        RowDivider()
        KeepAliveHint()
    }
}

/**
 * Android removes an app's accessibility services from the enabled list when the app is force-stopped,
 * which aggressive background cleaners (vivo/iQOO OriginOS and Funtouch OS among them) do. Only the
 * standard app-details page is linked: OEM-specific intents can't be verified here.
 */
@Composable
private fun KeepAliveHint() {
    val ctx = LocalContext.current
    val m = (Build.MANUFACTURER ?: "").lowercase()
    val vivo = m.contains("vivo") || m.contains("iqoo")
    val text = if (vivo) {
        "Service keeps turning off? On vivo / iQOO phones the system closes background apps and Android then " +
            "switches their accessibility services off. In Settings › Battery › Background power consumption " +
            "management, allow high background power consumption for AirBridge, and lock AirBridge in Recents " +
            "(swipe its card down or tap the lock). Menu names differ between OriginOS / Funtouch versions."
    } else {
        "Service keeps turning off? Some phones close background apps, and Android then switches their " +
            "accessibility services off. Set AirBridge's battery use to unrestricted and lock it in Recents if your phone offers that."
    }
    Column(Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = { openSafely(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
            modifier = Modifier.height(Dimens.touch),
        ) { Text("App settings") }
    }
}
