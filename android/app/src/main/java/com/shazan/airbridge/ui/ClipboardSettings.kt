package com.shazan.airbridge.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SwitchRow

/**
 * Settings card for automatic phone → computer clipboard sync (§5): the on/off switch, a warning when
 * the overlay permission it relies on is missing, and a low-key hint for ROMs (vivo, Xiaomi, …) that
 * also gate pop-ups from the background. Self-contained so the Settings screen needs one line.
 */
@Composable
fun ClipboardAutoSyncCard() {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(ClipboardSync.autoEnabled) }
    val resumeTick = rememberResumeTickLocal()
    val hasOverlay = remember(resumeTick) { Settings.canDrawOverlays(ctx) }

    fun open(intent: Intent) {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(ctx, "Not available on this device", Toast.LENGTH_SHORT).show() }
    }

    AbCard(Modifier.padding(top = Dimens.sm)) {
        SwitchRow(
            "Send copies to my computers automatically",
            "When you copy text while AirBridge runs in the background. Passwords and other text marked sensitive are never sent automatically.",
            auto,
            { auto = it; ClipboardSync.autoEnabled = it },
        )
        if (auto && !hasOverlay) {
            RowDivider()
            InfoRow(
                title = "Needs permission",
                subtitle = "Automatic copy sync needs 'Display over other apps'",
                trailing = {
                    TextButton(
                        onClick = { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) },
                        modifier = Modifier.height(Dimens.touch),
                    ) { Text("Allow") }
                },
            )
        }
        if (auto) {
            RowDivider()
            Column(Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm)) {
                Text(
                    "If a copy can't be sent automatically, the bubble shows a dot. Tap it to send. " +
                        "Some phones only let AirBridge notice copies while 'Control from your computer' is on. " +
                        "If automatic sync does not work, allow 'Display pop-ups while running in the background' " +
                        "for AirBridge in your phone's app permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
                    modifier = Modifier.height(Dimens.touch),
                ) { Text("App permissions") }
            }
        }
    }
}

/** Re-composes when the activity resumes (permission state read inside). */
@Composable
private fun rememberResumeTickLocal(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}
