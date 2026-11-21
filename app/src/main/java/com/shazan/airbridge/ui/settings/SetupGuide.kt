package com.shazan.airbridge.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.R
import com.shazan.airbridge.control.accessibility.ControlAccessibilityService
import com.shazan.airbridge.payment.PaymentPause
import com.shazan.airbridge.phone.PhoneRelay
import com.shazan.airbridge.phone.PhoneRelaySettings
import com.shazan.airbridge.ui.common.granted
import com.shazan.airbridge.ui.common.rememberResumeTick
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.RowDivider

/**
 * What AirBridge can ask Android for, and what each is for. Used by the "Get AirBridge ready" screen: a person who
 * trusts the app is walked through all of it, each step explained first. Nothing is asked unless they start it:
 * otherwise a permission is requested only where its own feature is turned on, and a permission never turns a feature on.
 */
internal object PermissionPlan {
    /** One pop-up permission, explained for the "Quick permissions" step: what it is, what it does, what happens without it. */
    class Group(val title: Int, val what: Int, val skip: Int, val perms: List<String>) {
        fun ready(ctx: Context) = perms.all { granted(ctx, it) }
    }

    /** The pop-up permissions in plain terms, only those this Android version has. */
    val groups: List<Group> = buildList {
        add(Group(R.string.q_phone_title, R.string.q_phone_what, R.string.q_phone_skip,
            listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)))
        add(Group(R.string.q_contacts_title, R.string.q_contacts_what, R.string.q_contacts_skip, listOf(Manifest.permission.READ_CONTACTS)))
        add(Group(R.string.q_calllog_title, R.string.q_calllog_what, R.string.q_calllog_skip,
            listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.PROCESS_OUTGOING_CALLS)))
        if (Build.VERSION.SDK_INT >= 33) add(Group(R.string.q_notif_title, R.string.q_notif_what, R.string.q_notif_skip, listOf(Manifest.permission.POST_NOTIFICATIONS)))
        add(Group(R.string.q_nearby_title, R.string.q_nearby_what, R.string.q_nearby_skip, buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            else if (Build.VERSION.SDK_INT < 31) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }))
    }

    fun runtime(): List<String> = groups.flatMap { it.perms }.distinct()

    fun runtimeMissing(ctx: Context): List<String> = runtime().filterNot { granted(ctx, it) }

    /** A page in Android's settings with a switch for us: what it is, why we want it, what to tap there. */
    class Page(
        val title: Int, val why: Int, val how: Int, val skip: Int, val accessibility: Boolean = false,
        val granted: (Context) -> Boolean, val intent: (Context) -> Intent,
    ) {
        fun howText(ctx: Context): String =
            if (accessibility) ctx.getString(how, ctx.getString(R.string.svc_core_name), ctx.getString(R.string.svc_text_name))
            else ctx.getString(how)

        /** Android's own page may not scroll to our row (it depends on the phone): where to look if it doesn't. */
        fun findText(ctx: Context): String? =
            if (accessibility) ctx.getString(R.string.setup_a11y_find, ctx.getString(R.string.setup_a11y_group_a), ctx.getString(R.string.setup_a11y_group_b)) else null
    }

    private fun pkg(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    val pages: List<Page> = buildList {
        add(Page(R.string.setup_notif_title, R.string.setup_notif_why, R.string.setup_notif_how, R.string.setup_notif_skip,
            granted = { PhoneRelaySettings.hasNotificationAccess(it) }, intent = { PhoneRelaySettings.accessIntent(it) }))
        add(Page(R.string.pp_usage, R.string.setup_usage_why, R.string.setup_usage_how, R.string.setup_usage_skip,
            granted = { PaymentPause.hasUsageAccess(it) }, intent = { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg(it)) }))
        add(Page(R.string.overlay_perm, R.string.setup_overlay_why, R.string.setup_overlay_how, R.string.setup_overlay_skip,
            granted = { Settings.canDrawOverlays(it) }, intent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(it)) }))
        add(Page(R.string.dnd_access, R.string.setup_dnd_why, R.string.setup_dnd_how, R.string.setup_dnd_skip,
            granted = { it.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
            intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }))
        if (com.shazan.airbridge.control.trackpad.OsGestureGuard.switchAvailable)
            add(Page(R.string.modify_settings, R.string.setup_write_why, R.string.setup_write_how, R.string.setup_write_skip,
                granted = { Settings.System.canWrite(it) }, intent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg(it)) }))
        add(Page(R.string.svc_core_name, R.string.setup_a11y_why, R.string.setup_a11y_how, R.string.setup_a11y_skip, accessibility = true,
            granted = { ControlAccessibilityService.isRunning }, intent = { com.shazan.airbridge.control.accessibility.AccessibilityLink.intent(it) }))
    }

    fun pagesMissing(ctx: Context): List<Page> = pages.filterNot { it.granted(ctx) }

    /** The one-time permission only a computer can give: it lets AirBridge switch its own remote control off and on. */
    fun autoGranted(ctx: Context): Boolean = PaymentPause.canSwitchSecurely(ctx)

    /** (ready, total): the quick pop-up permissions count as one step, each settings page as one, and the computer step as one. */
    fun progress(ctx: Context): Pair<Int, Int> {
        val ready = (if (runtimeMissing(ctx).isEmpty()) 1 else 0) + pages.count { it.granted(ctx) } + (if (autoGranted(ctx)) 1 else 0)
        return ready to (2 + pages.size)
    }
}

// ---- the entry in Settings ----

/** The way in, at the top of Settings: a ring with the count, and a line saying what it is. */
@Composable
internal fun SetupEntryCard(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val (ready, total) = remember(tick) { PermissionPlan.progress(ctx) }
    val done = ready == total
    AbCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth().padding(Dimens.md), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(ready, total)
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (done) R.string.setup_done_title else R.string.setup_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(if (done) R.string.setup_done_sub else R.string.setup_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How far along: a thin white ring on graphite with the count in the middle (a tick once complete). */
@Composable
private fun ProgressRing(ready: Int, total: Int, dim: Dp = 48.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    val desc = stringResource(R.string.setup_progress, ready, total)
    Box(Modifier.size(dim).clearAndSetSemantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (ready > 0) drawArc(fg, -90f, 360f * ready / total, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (ready == total) Icon(Icons.Filled.Check, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        else Text("$ready/$total", style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

// ---- the guided screen ----

private sealed interface SetupStep {
    data object Quick : SetupStep
    /** Needs a computer: opens its own guide instead of an Android page. */
    data object Auto : SetupStep
    class Settings(val page: PermissionPlan.Page) : SetupStep
}

/**
 * Overview of everything, then one step at a time: what it is for, a drawing of the Android screen it opens, and what to
 * tap there. Every step can be skipped and Back always returns to the overview. A step is shown once: coming back from
 * a page without its switch on goes on to the next, never reopens it.
 */
@Composable
internal fun SetupScreen(onBack: () -> Unit, onOpenGuide: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var steps by remember { mutableStateOf(emptyList<SetupStep>()) }
    var index by remember { mutableIntStateOf(-1) }
    val step = steps.getOrNull(index)

    BackHandler { if (index >= 0) index = -1 else onBack() }

    fun finish() {
        index = -1; steps = emptyList()
        PhoneRelay.onSettingsChanged() // call listeners that need a permission start now, not at the next launch
        val all = PermissionPlan.pagesMissing(ctx).isEmpty() && PermissionPlan.runtimeMissing(ctx).isEmpty()
        Toast.makeText(ctx, ctx.getString(if (all) R.string.perm_all_done else R.string.perm_all_partial), Toast.LENGTH_SHORT).show()
    }

    fun advance() {
        var i = index + 1
        while (i < steps.size) {
            val s = steps[i]
            val already = when (s) { is SetupStep.Quick -> PermissionPlan.runtimeMissing(ctx).isEmpty(); is SetupStep.Settings -> s.page.granted(ctx); is SetupStep.Auto -> PermissionPlan.autoGranted(ctx) }
            if (already) i++ else break
        }
        if (i >= steps.size) finish() else index = i
    }

    val pageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { advance() }
    val popups = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        PhoneRelay.onSettingsChanged()
        advance()
    }

    val (ready, total) = remember(tick, index) { PermissionPlan.progress(ctx) }
    val allDone = ready == total

    fun start() {
        val list = buildList<SetupStep> {
            if (PermissionPlan.runtimeMissing(ctx).isNotEmpty()) add(SetupStep.Quick)
            PermissionPlan.pagesMissing(ctx).forEach { add(SetupStep.Settings(it)) }
            if (!PermissionPlan.autoGranted(ctx)) add(SetupStep.Auto)
        }
        if (list.isEmpty()) return
        steps = list; index = 0
    }

    fun act(s: SetupStep) {
        when (s) {
            is SetupStep.Quick -> {
                val pop = PermissionPlan.runtimeMissing(ctx)
                if (pop.isEmpty()) advance() else popups.launch(pop.toTypedArray())
            }
            is SetupStep.Auto -> onOpenGuide()
            is SetupStep.Settings -> try {
                pageLauncher.launch(s.page.intent(ctx))
                // A short reminder over Android's page, except for Accessibility: Android scrolls our row to the bottom edge
                // there, exactly where a toast sits.
                if (!s.page.accessibility) Toast.makeText(ctx, s.page.howText(ctx), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { advance() }
        }
    }

    AbScaffold(
        title = stringResource(R.string.setup_title),
        onBack = { if (index >= 0) index = -1 else onBack() },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(horizontal = Dimens.md, vertical = Dimens.sm)) {
                if (step == null) {
                    if (allDone) PrimaryButton(stringResource(R.string.done), onClick = onBack)
                    else PrimaryButton(stringResource(if (ready > 0) R.string.setup_continue else R.string.setup_start), onClick = { start() })
                } else {
                    PrimaryButton(stringResource(when (step) { is SetupStep.Quick -> R.string.setup_continue; is SetupStep.Auto -> R.string.auto_show_how; else -> R.string.setup_open }), onClick = { act(step) })
                    TextButton(onClick = { advance() }, modifier = Modifier.fillMaxWidth().height(Dimens.touch)) { Text(stringResource(R.string.setup_skip)) }
                }
            }
        },
    ) {
        Crossfade(targetState = step, label = "setup", modifier = Modifier.fillMaxSize()) { s ->
            if (s == null) Overview(tick) { only -> steps = listOf(only); index = 0 } else StepContent(s, index, steps.size)
        }
    }
}

@Composable
private fun Overview(tick: Int, onPick: (SetupStep) -> Unit) {
    val ctx = LocalContext.current
    val quickReady = remember(tick) { PermissionPlan.runtimeMissing(ctx).isEmpty() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(Dimens.sm))
        Text(stringResource(R.string.setup_sheet_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Dimens.sm))
        Text(stringResource(R.string.setup_sheet_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dimens.lg))
        AbCard {
            ChecklistRow(1, stringResource(R.string.setup_quick_title), stringResource(R.string.setup_quick_why), quickReady,
                onClick = if (quickReady) null else ({ onPick(SetupStep.Quick) }))
            PermissionPlan.pages.forEachIndexed { i, p ->
                RowDivider()
                val ok = remember(tick) { p.granted(ctx) }
                ChecklistRow(i + 2, stringResource(p.title), stringResource(p.why), ok, onClick = if (ok) null else ({ onPick(SetupStep.Settings(p)) }))
            }
            RowDivider()
            val autoOk = remember(tick) { PermissionPlan.autoGranted(ctx) }
            ChecklistRow(PermissionPlan.pages.size + 2, stringResource(R.string.setup_auto_title), stringResource(R.string.setup_auto_short), autoOk,
                onClick = if (autoOk) null else ({ onPick(SetupStep.Auto) }))
        }
        Spacer(Modifier.height(Dimens.lg))
    }
}

/** A numbered row: a ring with the number, or a white disc with a tick once it is done. */
@Composable
private fun ChecklistRow(number: Int, title: String, why: String, done: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.md, vertical = Dimens.md),
        verticalAlignment = Alignment.Top,
    ) {
        val fg = MaterialTheme.colorScheme.onSurface
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .then(if (done) Modifier.background(fg) else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.allowed), tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(18.dp))
            else Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Dimens.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A step still to do opens on its own: tap it to do just this one.
        if (onClick != null) {
            Spacer(Modifier.width(Dimens.sm))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepContent(step: SetupStep, index: Int, count: Int) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(Dimens.sm))
        if (count > 1) {
            StepBars(index, count)
            Spacer(Modifier.height(Dimens.md))
            Text(stringResource(R.string.setup_step, index + 1, count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Dimens.xs))
        }
        when (step) {
            is SetupStep.Quick -> QuickStep()
            is SetupStep.Auto -> {
                Text(stringResource(R.string.setup_auto_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Dimens.lg))
                Labeled(stringResource(R.string.setup_label_for), stringResource(R.string.setup_auto_why))
                Spacer(Modifier.height(Dimens.md))
                Labeled(stringResource(R.string.setup_label_skip), stringResource(R.string.setup_auto_skip))
                Spacer(Modifier.height(Dimens.lg))
                CableArt()
                Spacer(Modifier.height(Dimens.lg))
                Labeled(stringResource(R.string.setup_what_to_do), stringResource(R.string.setup_auto_how))
            }
            is SetupStep.Settings -> {
                val page = step.page
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Dimens.lg))
                Labeled(stringResource(R.string.setup_label_for), stringResource(page.why))
                Spacer(Modifier.height(Dimens.md))
                Labeled(stringResource(R.string.setup_label_skip), stringResource(page.skip))
                Spacer(Modifier.height(Dimens.lg))
                if (page.accessibility) AccessibilityMock() else AppSwitchMock()
                Spacer(Modifier.height(Dimens.lg))
                Labeled(stringResource(R.string.setup_what_to_do), page.howText(ctx))
                val find = page.findText(ctx)
                if (find != null) {
                    Spacer(Modifier.height(Dimens.md))
                    Text(find, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(Dimens.lg))
    }
}

/**
 * The pop-up permissions, one by one: what each is, what it is used for, and what stops working without it, with a tick
 * on the ones already allowed. A new user should know exactly what they are agreeing to before tapping Allow.
 */
@Composable
private fun QuickStep() {
    val ctx = LocalContext.current
    Text(stringResource(R.string.setup_quick_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(Dimens.sm))
    Text(stringResource(R.string.q_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(Dimens.lg))
    PopupMock()
    Spacer(Modifier.height(Dimens.lg))
    AbCard {
        PermissionPlan.groups.forEachIndexed { i, g ->
            if (i > 0) RowDivider()
            val ok = remember { g.ready(ctx) }
            Column(Modifier.fillMaxWidth().padding(Dimens.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(g.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (ok) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.allowed), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(Dimens.xs))
                Text(stringResource(g.what), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Dimens.sm))
                Text(stringResource(R.string.setup_label_skip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(g.skip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small muted label with its text underneath. */
@Composable
private fun Labeled(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Dimens.xs))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

/** One segment per step: white for the ones reached, graphite for the rest. */
@Composable
private fun StepBars(index: Int, count: Int) {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.weight(1f).height(4.dp).clip(CircleShape)
                    .background(if (i <= index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
