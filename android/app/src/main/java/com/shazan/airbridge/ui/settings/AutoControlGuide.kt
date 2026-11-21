package com.shazan.airbridge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shazan.airbridge.R
import com.shazan.airbridge.payment.PaymentPause
import com.shazan.airbridge.ui.common.rememberResumeTick
import com.shazan.airbridge.ui.common.toast
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.StatusChip

/**
 * "Turn control back on automatically": the one-time computer step that gives AirBridge the permission to switch its own
 * accessibility services on and off (WRITE_SECURE_SETTINGS, which only adb can grant). Written for someone who has never
 * used a terminal: why it is needed, exactly what to do, in what order, with a picture of each step. Skippable, and always
 * reachable again from Settings → Payment apps.
 */
@Composable
internal fun AutoControlGuideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val done = remember(tick) { PaymentPause.canSwitchSecurely(ctx) }
    val command = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
    BackHandler(onBack = onBack)

    AbScaffold(
        title = stringResource(R.string.guide_title),
        onBack = onBack,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(horizontal = Dimens.md, vertical = Dimens.sm)) {
                if (done) PrimaryButton(stringResource(R.string.done), onClick = onBack)
                else TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(Dimens.touch)) { Text(stringResource(R.string.guide_not_now)) }
            }
        },
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(Dimens.sm))
            AbCard {
                InfoRow(
                    title = stringResource(R.string.pp_secure),
                    subtitle = stringResource(if (done) R.string.guide_status_done else R.string.guide_status_todo_sub),
                    trailing = { StatusChip(stringResource(if (done) R.string.allowed else R.string.guide_status_todo), if (done) ChipTone.SUCCESS else ChipTone.NEUTRAL) },
                )
            }

            Heading(stringResource(R.string.guide_why_h))
            Body(stringResource(R.string.guide_why_1))
            Body(stringResource(R.string.guide_why_2))
            Body(stringResource(R.string.guide_why_3), muted = true)

            Heading(stringResource(R.string.guide_need_h))
            Bullet(stringResource(R.string.guide_need_1))
            Bullet(stringResource(R.string.guide_need_2))
            Bullet(stringResource(R.string.guide_need_3))

            Spacer(Modifier.height(Dimens.lg))
            Step(1, stringResource(R.string.g1_title), stringResource(R.string.g1_body), art = { BuildNumberArt() })
            Step(2, stringResource(R.string.g2_title), stringResource(R.string.g2_body), art = { UsbDebuggingArt() })
            Step(3, stringResource(R.string.g3_title), stringResource(R.string.g3_body), art = { CableArt() })
            Step(4, stringResource(R.string.g4_title), stringResource(R.string.g4_body), art = { TerminalArt("adb version", "Android Debug Bridge version 1.0.41") }) {
                OsTip("Mac", stringResource(R.string.g4_mac), "brew install android-platform-tools")
                OsTip("Windows", stringResource(R.string.g4_win), "winget install Google.PlatformTools")
                OsTip("Linux", stringResource(R.string.g4_linux), "sudo apt install adb")
                Body(stringResource(R.string.g4_zip), muted = true)
            }
            Step(5, stringResource(R.string.g5_title), stringResource(R.string.g5_body), art = { TerminalArt(command) }) {
                CodeBlock(command, copy = true)
                Body(stringResource(R.string.g5_note), muted = true)
            }
            Step(6, stringResource(R.string.g6_title), stringResource(R.string.g6_body), art = { DoneArt() })

            Heading(stringResource(R.string.trouble_h))
            Bullet(stringResource(R.string.trouble_1))
            Bullet(stringResource(R.string.trouble_2))
            Bullet(stringResource(R.string.trouble_3))

            Spacer(Modifier.height(Dimens.lg))
            AbCard {
                Column(Modifier.padding(Dimens.md)) {
                    Text(stringResource(R.string.guide_where), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(Dimens.xl))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Dimens.lg, bottom = Dimens.sm))
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Dimens.sm),
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = Dimens.xs)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A numbered step: its title, what to do, a picture of it, and anything extra (commands) underneath. */
@Composable
private fun Step(number: Int, title: String, body: String, art: @Composable () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(bottom = Dimens.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Dimens.md))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(Dimens.sm))
        Body(body)
        Spacer(Modifier.height(Dimens.xs))
        art()
        Spacer(Modifier.height(Dimens.md))
        extra()
    }
}

/** One computer system's way of getting adb: what to do, and the line to type. */
@Composable
private fun OsTip(os: String, text: String, command: String) {
    Column(Modifier.padding(bottom = Dimens.md)) {
        Text(os, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Dimens.xs))
        CodeBlock(command)
    }
}

/** A line to type, in a typewriter font, selectable; optionally with a button that copies it. */
@Composable
private fun CodeBlock(text: String, copy: Boolean = false) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(Dimens.md)) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurface)
        }
        if (copy) {
            TextButton(
                onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", text))
                    toast(ctx, ctx.getString(R.string.pp_copied))
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                modifier = Modifier.height(Dimens.touch),
            ) { Text(stringResource(R.string.pp_copy)) }
        }
    }
}
