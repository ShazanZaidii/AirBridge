package com.shazan.airbridge.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import com.shazan.airbridge.BuildConfig
import com.shazan.airbridge.R
import com.shazan.airbridge.airweb.AirWebActivity
import com.shazan.airbridge.app.AirBridgeService
import com.shazan.airbridge.app.AppLanguage
import com.shazan.airbridge.clipboard.bubble.FloatingBubble
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.connection.Identity
import com.shazan.airbridge.connection.transport.NearbyTransport
import com.shazan.airbridge.connection.transport.WsClient
import com.shazan.airbridge.ui.common.granted
import com.shazan.airbridge.ui.common.rememberResumeTick
import com.shazan.airbridge.ui.common.toast
import com.shazan.airbridge.ui.pairing.rememberNearbyPermission
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SectionHeader
import com.shazan.airbridge.ui.theme.StatusChip
import com.shazan.airbridge.ui.theme.SwitchRow
import kotlinx.coroutines.launch

// ---- Settings ----

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onOpenLinkDebug: () -> Unit, onOpenSetup: () -> Unit, onOpenGuide: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE) }
    val resumeTick = rememberResumeTick()
    val scope = rememberCoroutineScope()
    val myName by Identity.nameFlow.collectAsState()

    var invertX by remember { mutableStateOf(prefs.getBoolean("INVERT_X", false)) }
    var invertY by remember { mutableStateOf(prefs.getBoolean("INVERT_Y", false)) }
    var bubble by remember { mutableStateOf(prefs.getBoolean(FloatingBubble.PREF_ENABLED, true)) }
    var autoConnect by remember { mutableStateOf(prefs.getBoolean(WsClient.PREF_AUTO_CONNECT, true)) }
    var visible by remember { mutableStateOf(prefs.getBoolean(NearbyTransport.PREF_VISIBLE, false)) }
    var renaming by remember { mutableStateOf(false) }
    var pickingLanguage by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(AppLanguage.current(ctx)) }

    val canWriteSettings = remember(resumeTick) { Settings.System.canWrite(ctx) }
    val hasOverlay = remember(resumeTick) { Settings.canDrawOverlays(ctx) }
    // Back from the overlay settings with it granted: show the bubble now.
    LaunchedEffect(hasOverlay) { if (hasOverlay && AirBridgeService.isRunning) AirBridgeService.start(ctx, AirBridgeService.ACTION_REFRESH_BUBBLE) }

    val (_, requestNearby) = rememberNearbyPermission { ok ->
        if (ok) {
            visible = true
            NearbyTransport.setVisible(true)
            AirBridgeService.start(ctx)
        } else toast(ctx, ctx.getString(R.string.nearby_needed))
    }

    fun open(intent: Intent) = scope.launch {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, ctx.getString(R.string.not_available)) }
    }
    fun openOverlaySettings() = open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))

    if (renaming) {
        var name by remember { mutableStateOf(Identity.customName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.phone_name)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, singleLine = true,
                    label = { Text(stringResource(R.string.shown_on_other)) }, placeholder = { Text(Identity.defaultName) })
            },
            confirmButton = { TextButton(onClick = { ConnectionHub.renameSelf(name); renaming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (pickingLanguage) {
        AlertDialog(
            onDismissRequest = { pickingLanguage = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for ((tag, label) in AppLanguage.choices) {
                        val shown = if (tag.isEmpty()) stringResource(R.string.language_system) else label
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable {
                                pickingLanguage = false
                                language = tag
                                AppLanguage.set(ctx, tag) { (ctx as? android.app.Activity)?.recreate() }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == language, onClick = null)
                            Spacer(Modifier.width(Dimens.md))
                            Text(shown, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingLanguage = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // Long-press the title: hidden Wi-Fi Direct test screen (device tests S-W2/S-W3/S-W4).
    AbScaffold(title = stringResource(R.string.settings), onBack = onBack, onTitleLongPress = onOpenLinkDebug) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(Dimens.sm))
            SetupEntryCard(onOpenSetup)

            SectionHeader(stringResource(R.string.this_phone))
            AbCard {
                InfoRow(title = stringResource(R.string.name), subtitle = myName, trailing = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename_phone)) }
                })
                RowDivider()
                SwitchRow(stringResource(R.string.visible_nearby), stringResource(R.string.visible_nearby_sub), visible, { on ->
                    if (on) requestNearby()
                    else {
                        visible = false
                        NearbyTransport.setVisible(false)
                        AirBridgeService.stopIfIdle(ctx)
                    }
                })
                RowDivider()
                InfoRow(title = stringResource(R.string.airweb), subtitle = stringResource(R.string.airweb_settings_sub), trailing = {
                    TextButton(onClick = { ctx.startActivity(AirWebActivity.intent(ctx, emptyList())) }, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.open)) }
                })
                RowDivider()
                InfoRow(
                    title = stringResource(R.string.language),
                    subtitle = AppLanguage.choices.firstOrNull { it.first == language && it.first.isNotEmpty() }?.second ?: stringResource(R.string.language_system),
                    modifier = Modifier.clickable { pickingLanguage = true },
                    trailing = { TextButton(onClick = { pickingLanguage = true }, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.change)) } },
                )
            }

            SectionHeader(stringResource(R.string.connection))
            AbCard {
                SwitchRow(stringResource(R.string.reconnect_auto), stringResource(R.string.reconnect_auto_sub), autoConnect, {
                    autoConnect = it
                    prefs.edit().putBoolean(WsClient.PREF_AUTO_CONNECT, it).apply()
                    if (it) WsClient.retryAllNow(unpause = true)
                })
            }

            SectionHeader(stringResource(R.string.clipboard))
            AbCard {
                SwitchRow(
                    stringResource(R.string.floating_bubble),
                    if (hasOverlay) stringResource(R.string.bubble_sub)
                    else stringResource(R.string.bubble_needs_overlay),
                    bubble && hasOverlay,
                    {
                        bubble = it
                        prefs.edit().putBoolean(FloatingBubble.PREF_ENABLED, it).apply()
                        if (it && !hasOverlay) openOverlaySettings()
                        else if (AirBridgeService.isRunning) AirBridgeService.start(ctx, AirBridgeService.ACTION_REFRESH_BUBBLE)
                    },
                )
            }
            com.shazan.airbridge.ui.settings.ClipboardAutoSyncCard()

            com.shazan.airbridge.ui.settings.PhoneNotifsSection()

            SectionHeader(stringResource(R.string.remote_control))
            AbCard {
                com.shazan.airbridge.ui.settings.RemoteControlServiceRows()
                RowDivider()
                SwitchRow(stringResource(R.string.invert_x), null, invertX, { invertX = it; prefs.edit().putBoolean("INVERT_X", it).apply() })
                RowDivider()
                SwitchRow(stringResource(R.string.invert_y), null, invertY, { invertY = it; prefs.edit().putBoolean("INVERT_Y", it).apply() })
                // Only where OemGestureSwitches has verified keys for this manufacturer.
                if (com.shazan.airbridge.control.trackpad.OsGestureGuard.switchAvailable) {
                    var osGesturesOff by remember { mutableStateOf(com.shazan.airbridge.control.trackpad.OsGestureGuard.switchEnabled(prefs)) }
                    RowDivider()
                    SwitchRow(
                        stringResource(R.string.os_gestures_off),
                        if (canWriteSettings) stringResource(R.string.os_gestures_off_sub) else stringResource(R.string.needs_modify_settings),
                        osGesturesOff,
                        { on ->
                            osGesturesOff = on
                            prefs.edit().putBoolean(com.shazan.airbridge.control.trackpad.OsGestureGuard.PREF_SWITCHES_OFF, on).apply()
                            if (on && !canWriteSettings) open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        },
                    )
                }
            }

            com.shazan.airbridge.ui.settings.MirrorTrustSection()

            SectionHeader(stringResource(R.string.pp_title))
            AbCard {
                var ppOn by remember { mutableStateOf(com.shazan.airbridge.payment.PaymentPause.isEnabled(ctx)) }
                val usageOk = remember(resumeTick) { com.shazan.airbridge.payment.PaymentPause.hasUsageAccess(ctx) }
                SwitchRow(
                    stringResource(R.string.pp_switch), stringResource(R.string.pp_switch_sub), ppOn,
                    { on -> ppOn = on; com.shazan.airbridge.payment.PaymentPause.setEnabled(ctx, on) },
                )
                // Without usage access the switch can't do its job: say so right here, only while that is true.
                if (ppOn && !usageOk) {
                    RowDivider()
                    AccessRow(stringResource(R.string.pp_usage), stringResource(R.string.pp_usage_sub), false) {
                        open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
                // The one-time step that needs a computer. Its natural home: right under the switch it makes work properly.
                RowDivider()
                val secureOk = remember(resumeTick) { com.shazan.airbridge.payment.PaymentPause.canSwitchSecurely(ctx) }
                InfoRow(title = stringResource(R.string.pp_secure), subtitle = stringResource(R.string.pp_secure_sub), trailing = {
                    if (secureOk) StatusChip(stringResource(R.string.allowed), ChipTone.SUCCESS)
                    else TextButton(onClick = onOpenGuide, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.auto_show_how)) }
                })
            }

            AdvancedSection()

            SectionHeader(stringResource(R.string.about))
            AbCard {
                InfoRow(title = stringResource(R.string.about_version, BuildConfig.VERSION_NAME), subtitle = stringResource(R.string.about_sub),
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenLinkDebug() }) })
            }
            Spacer(Modifier.height(Dimens.xl))
        }
    }
}
@Composable
private fun AccessRow(title: String, subtitle: String, granted: Boolean, onOpen: () -> Unit) {
    InfoRow(title = title, subtitle = subtitle, trailing = {
        if (granted) StatusChip(stringResource(R.string.allowed), ChipTone.SUCCESS)
        else TextButton(onClick = onOpen, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.allow)) }
    })
}
