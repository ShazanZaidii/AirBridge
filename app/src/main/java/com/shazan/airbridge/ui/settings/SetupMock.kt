package com.shazan.airbridge.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.shazan.airbridge.R
import com.shazan.airbridge.ui.theme.Dimens

/**
 * A small drawn picture of the Android screen a setup step is about to open, with the thing to tap picked out (its
 * outline breathes slowly). Only the AirBridge rows carry real words: everything else is grey placeholder bars, so it
 * stays true on every phone make and language. Decorative: the step's own text says the same.
 */
internal data class MockRow(
    val header: String? = null,
    val label: String? = null,
    val marked: Boolean = false,
    val toggle: Boolean? = null,
    val chevron: Boolean = false,
    val tag: String? = null,
    val badge: String? = null,
)

internal val FrameShape = RoundedCornerShape(20.dp)
private val RowShape = RoundedCornerShape(14.dp)

@Composable
internal fun Frame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { }
            .clip(FrameShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FrameShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A list page of Android's settings: placeholder rows, and ours picked out with its switch. */
@Composable
internal fun SettingsMock(rows: List<MockRow>) = Frame { rows.forEach { MockRowView(it) } }

/** The list page for one switch: our app between two others. */
@Composable
internal fun AppSwitchMock() = SettingsMock(
    listOf(MockRow(), MockRow(label = stringResource(R.string.app_name), marked = true, toggle = true), MockRow()),
)

/** Accessibility: our service sits in the "Installed apps" group; the typing service below it is optional and stays off. */
@Composable
internal fun AccessibilityMock() = Frame {
    MockRowView(MockRow())
    Text(
        stringResource(R.string.setup_a11y_group_a),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
    MockRowView(MockRow(label = stringResource(R.string.svc_core_name), marked = true, toggle = true))
    MockRowView(MockRow(label = stringResource(R.string.svc_text_name), toggle = false, tag = stringResource(R.string.setup_optional)))
}

/** The system's permission pop-up, with its Allow button picked out. */
@Composable
internal fun PopupMock() = Frame {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Bar(0.85f); Bar(0.6f)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Bar(0.3f)
            Spacer(Modifier.width(14.dp))
            val pulse = rememberPulse()
            val press = rememberToggleProgress()
            val fill = lerp(Color.Transparent, MaterialTheme.colorScheme.onSurface, press)
            Box(
                Modifier.clip(CircleShape).background(fill)
                    .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = pulse + (1f - pulse) * press), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Text(stringResource(R.string.allow), style = MaterialTheme.typography.labelLarge,
                    color = lerp(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.background, press))
            }
        }
    }
}

@Composable
internal fun rememberPulse(): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    return v
}

@Composable
internal fun Bar(fraction: Float, thin: Boolean = false) {
    Box(
        Modifier.fillMaxWidth(fraction).height(if (thin) 6.dp else 9.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)),
    )
}

/** 0 → 1 → 0, forever: the switch is off, gets switched on, stays on a moment, then the picture starts over. */
@Composable
internal fun rememberToggleProgress(): Float {
    val ctx = LocalContext.current
    // Animations turned off in Android's developer options: show the finished state and keep still.
    val still = remember { try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Throwable) { false } }
    val p = remember { Animatable(if (still) 1f else 0f) }
    if (!still) LaunchedEffect(Unit) {
        while (true) {
            p.snapTo(0f); delay(1000)
            p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)); delay(2200)
            p.animateTo(0f, tween(280, easing = FastOutSlowInEasing)); delay(700)
        }
    }
    return p.value
}

@Composable
internal fun MockRowView(r: MockRow) {
    if (r.header != null) {
        Text(r.header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        return
    }
    val pulse = if (r.marked) rememberPulse() else 1f
    // The row to tap: its switch is shown being switched on.
    val on = if (r.marked && r.toggle == true) rememberToggleProgress() else if (r.toggle == true) 1f else 0f
    val ring = pulse + (1f - pulse) * (if (r.marked) on else 1f)
    var m = Modifier.fillMaxWidth().height(58.dp).clip(RowShape).background(MaterialTheme.colorScheme.surfaceVariant)
    if (r.marked) m = m.border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = ring), RowShape)
    Row(m.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(if (r.marked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (r.label != null) {
                Text(r.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.tag != null) Text(r.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Bar(0.55f); Bar(0.32f, thin = true)
            }
        }
        if (r.badge != null) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(r.badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (r.toggle != null) { Spacer(Modifier.width(10.dp)); MockSwitch(on) }
        if (r.chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Same look as the app's own switches: white track and dark thumb when on, graphite when off; [on] runs 0 → 1. */
@Composable
private fun MockSwitch(on: Float) {
    val track = lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurface, on)
    val edge = lerp(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.onSurface, on)
    val thumb = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.background, on)
    val size = androidx.compose.ui.unit.lerp(14.dp, 20.dp, on)
    Box(
        Modifier.width(46.dp).height(28.dp).clip(CircleShape).background(track).border(1.5.dp, edge, CircleShape).padding(5.dp),
        contentAlignment = BiasAlignment(-1f + 2f * on, 0f),
    ) { Box(Modifier.size(size).clip(CircleShape).background(thumb)) }
}
