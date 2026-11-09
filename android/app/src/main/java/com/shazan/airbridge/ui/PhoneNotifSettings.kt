package com.shazan.airbridge.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shazan.airbridge.R
import com.shazan.airbridge.phone.PhoneRelay
import com.shazan.airbridge.phone.PhoneRelaySettings
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SectionHeader
import com.shazan.airbridge.ui.theme.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun openSafely(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { Toast.makeText(ctx, ctx.getString(R.string.not_available), Toast.LENGTH_SHORT).show() }
}

private fun appInfoIntent(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

/**
 * Settings section for phone notifications and calls on the computer (PROTOCOL §13). Both are off until
 * turned on here, and each explains what it does before sending the user to a system screen or permission
 * prompt. Self-contained so the Settings screen needs one line.
 */
@Composable
fun PhoneNotifsSection() {
    val ctx = LocalContext.current
    val tick = rememberResumeTickPn()
    val notifsOn by PhoneRelaySettings.notifsFlow.collectAsState()
    val callsOn by PhoneRelaySettings.callsFlow.collectAsState()
    val muted by PhoneRelaySettings.mutedFlow.collectAsState()
    val access = remember(tick) { PhoneRelaySettings.hasNotificationAccess(ctx) }
    val phonePerm = remember(tick) { PhoneRelaySettings.canWatchCalls(ctx) }
    val numberPerm = remember(tick) { PhoneRelaySettings.canSeeNumber(ctx) }
    var speaker by remember { mutableStateOf(PhoneRelaySettings.speakerOnAnswer) }
    var explainAccess by remember { mutableStateOf(false) }
    var explainCalls by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    val listenerName = stringResource(R.string.pn_listener_name)

    // Access granted while the switch was on: the listener connects by itself; refresh the computers' hint.
    androidx.compose.runtime.LaunchedEffect(access, phonePerm, numberPerm) { PhoneRelay.onSettingsChanged() }
    val numberLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { PhoneRelay.onSettingsChanged() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // On even if Phone was denied: the dialer's call notification still shows calls (limited, explained below).
        PhoneRelaySettings.callsEnabled = true
        PhoneRelay.onSettingsChanged()
    }

    if (explainAccess) {
        AlertDialog(
            onDismissRequest = { explainAccess = false },
            title = { Text(stringResource(R.string.pn_access_dialog_title)) },
            text = { Text(stringResource(R.string.pn_access_dialog_body, listenerName)) },
            confirmButton = {
                TextButton(onClick = {
                    explainAccess = false
                    PhoneRelaySettings.notifsEnabled = true
                    PhoneRelay.onSettingsChanged()
                    openSafely(ctx, PhoneRelaySettings.accessIntent(ctx))
                }) { Text(stringResource(R.string.pn_continue)) }
            },
            dismissButton = { TextButton(onClick = { explainAccess = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (explainCalls) {
        AlertDialog(
            onDismissRequest = { explainCalls = false },
            title = { Text(stringResource(R.string.pn_calls_title)) },
            text = { Text(stringResource(R.string.ce_calls_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    explainCalls = false
                    permLauncher.launch(PhoneRelaySettings.CALL_PERMISSIONS)
                }) { Text(stringResource(R.string.pn_continue)) }
            },
            dismissButton = { TextButton(onClick = { explainCalls = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showApps) AppMuteDialog(onDismiss = { showApps = false })

    SectionHeader(stringResource(R.string.pn_section))
    AbCard {
        SwitchRow(
            stringResource(R.string.pn_notifs_title),
            stringResource(R.string.pn_notifs_sub),
            notifsOn && access,
            { on ->
                if (on) {
                    if (access) { PhoneRelaySettings.notifsEnabled = true; PhoneRelay.onSettingsChanged() }
                    else explainAccess = true
                } else {
                    PhoneRelaySettings.notifsEnabled = false
                    PhoneRelay.onSettingsChanged()
                }
            },
        )
        if (notifsOn && !access) {
            RowDivider()
            InfoRow(
                title = stringResource(R.string.pn_access_off_title),
                subtitle = stringResource(R.string.pn_access_off_sub, listenerName),
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { openSafely(ctx, PhoneRelaySettings.accessIntent(ctx)) }, modifier = Modifier.height(Dimens.touch)) {
                            Text(stringResource(R.string.allow))
                        }
                        TextButton(onClick = { openSafely(ctx, appInfoIntent(ctx)) }, modifier = Modifier.height(Dimens.touch)) {
                            Text(stringResource(R.string.pn_app_info))
                        }
                    }
                },
            )
        }
        if (notifsOn && access) {
            RowDivider()
            InfoRow(
                title = stringResource(R.string.pn_apps_title),
                subtitle = if (muted.isEmpty()) stringResource(R.string.pn_apps_all)
                else pluralStringResource(R.plurals.pn_apps_muted, muted.size, muted.size),
                trailing = {
                    TextButton(onClick = { showApps = true }, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.open)) }
                },
            )
        }
        RowDivider()
        SwitchRow(
            stringResource(R.string.pn_calls_title),
            stringResource(R.string.pn_calls_sub),
            callsOn,
            { on ->
                if (on) explainCalls = true
                else { PhoneRelaySettings.callsEnabled = false; PhoneRelay.onSettingsChanged() }
            },
        )
        if (callsOn && !phonePerm) {
            RowDivider()
            InfoRow(
                title = stringResource(R.string.pn_calls_limited_title),
                subtitle = stringResource(R.string.pn_calls_limited_sub),
                trailing = {
                    TextButton(onClick = { openSafely(ctx, appInfoIntent(ctx)) }, modifier = Modifier.height(Dimens.touch)) {
                        Text(stringResource(R.string.allow))
                    }
                },
            )
        }
        if (callsOn && phonePerm && !numberPerm) {
            RowDivider()
            InfoRow(
                title = stringResource(R.string.ce_number_title),
                subtitle = stringResource(R.string.ce_number_sub),
                trailing = {
                    TextButton(onClick = { numberLauncher.launch(android.Manifest.permission.READ_CALL_LOG) }, modifier = Modifier.height(Dimens.touch)) {
                        Text(stringResource(R.string.allow))
                    }
                },
            )
        }
        if (callsOn) {
            RowDivider()
            SwitchRow(stringResource(R.string.pn_speaker_title), stringResource(R.string.pn_speaker_sub), speaker, {
                speaker = it; PhoneRelaySettings.speakerOnAnswer = it
            })
        }
    }
}

private class AppRow(val pkg: String, val label: String, val seen: Boolean)

/** Full-screen list of apps with a switch each: off = that app's notifications stay on the phone. */
@Composable
private fun AppMuteDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val muted by PhoneRelaySettings.mutedFlow.collectAsState()
    val apps by produceState<List<AppRow>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AbScaffold(title = stringResource(R.string.pn_apps_title), onBack = onDismiss) {
            val list = apps
            if (list == null) {
                Row(Modifier.fillMaxWidth().padding(Dimens.md), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Dimens.md))
                    Text(stringResource(R.string.pn_apps_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(horizontal = Dimens.xs, vertical = Dimens.sm)) {
                            Text(stringResource(R.string.pn_apps_header), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Dimens.xs))
                            Text(stringResource(R.string.pn_private_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.pkg }) { app ->
                        val on = app.pkg !in muted
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .toggleable(value = on, role = Role.Switch, onValueChange = { v ->
                                    PhoneRelaySettings.setMuted(app.pkg, !v)
                                    PhoneRelay.onMuteChanged(app.pkg, !v)
                                })
                                .padding(horizontal = Dimens.xs, vertical = Dimens.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.pkg)
                            Spacer(Modifier.width(Dimens.md))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                            Switch(checked = on, onCheckedChange = null)
                        }
                    }
                    item { Spacer(Modifier.height(Dimens.xl)) }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Throwable) { null }
        }
    }
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        img?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
    }
}

/** Launcher apps plus the apps that posted recently (those first), without AirBridge itself. */
private fun loadApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    val seen = PhoneRelaySettings.seenApps()
    val pkgs = LinkedHashSet<String>()
    pkgs += seen
    try {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0).forEach { pkgs += it.activityInfo.packageName }
    } catch (_: Throwable) {}
    pkgs -= ctx.packageName
    return pkgs.mapNotNull { p ->
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(p, PackageManager.GET_META_DATA)).toString() } catch (_: Throwable) { null }
            ?: return@mapNotNull null
        AppRow(p, label, p in seen)
    }.sortedWith(compareBy<AppRow>({ !it.seen }, { it.label.lowercase() }))
}

@Composable
private fun rememberResumeTickPn(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}
