package com.shazan.airbridge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.shazan.airbridge.R
import com.shazan.airbridge.ui.common.rememberResumeTick
import com.shazan.airbridge.mirror.MirrorTrust
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SectionHeader
import com.shazan.airbridge.ui.theme.SwitchRow

/**
 * Settings › Screen mirroring › "Mirror without asking" ([MirrorTrust]). The switch shows on only once Android really
 * skips its prompt; while a computer is granting it, the row says so.
 */
@Composable
internal fun MirrorTrustSection() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val status by MirrorTrust.status.collectAsState()
    var wanted by remember { mutableStateOf(MirrorTrust.wanted(ctx)) }
    // Re-read on every return to the screen and every answer from the computer.
    val granted = remember(tick, status) { MirrorTrust.granted(ctx) }
    val on = wanted && granted

    SectionHeader(stringResource(R.string.mt_section))
    AbCard {
        val subtitle = when (val s = status) {
            is MirrorTrust.Status.Asking -> stringResource(R.string.mt_asking, s.computer)
            is MirrorTrust.Status.Setup -> stringResource(R.string.mt_setup, s.computer)
            is MirrorTrust.Status.Failed -> listOfNotNull(stringResource(R.string.mt_failed), s.msg).joinToString(" ")
            MirrorTrust.Status.Idle -> stringResource(if (on) R.string.mt_sub_on else R.string.mt_sub_off)
        }
        SwitchRow(stringResource(R.string.mt_title), subtitle, on, { v ->
            wanted = v
            MirrorTrust.setWanted(ctx, v)
        })
        // Wanted but not granted and no computer could do it: the one-time command, to run by hand.
        if (wanted && !granted && (status is MirrorTrust.Status.Failed)) {
            RowDivider()
            Column(Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm)) {
                Text(stringResource(R.string.mt_needs_computer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val cmd = stringResource(R.string.mt_adb_command)
                SelectionContainer {
                    Text(cmd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Dimens.xs))
                }
                TextButton(onClick = { MirrorTrust.requestGrant(ctx) }) { Text(stringResource(R.string.mt_retry)) }
                TextButton(onClick = { copy(ctx, cmd) }) { Text(stringResource(android.R.string.copy)) }
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("adb", text))
    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
