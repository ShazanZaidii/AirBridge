package com.shazan.airbridge.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shazan.airbridge.R
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.StatusChip
import kotlin.math.PI
import kotlin.math.atan2

/**
 * The pictures of the "turn control back on automatically" guide: each plays a short loop of what the person is about to do
 * (tap Build number seven times, switch on USB debugging, plug in the cable, type a line into a terminal). Drawn, not
 * recorded, so they stay sharp, small and in the app's own colours. With animations turned off in Android's developer
 * options they show their finished state and keep still.
 */

/** 0 → 1 over [ms], forever (or [still] when animations are off): phases are slices of it. */
@Composable
internal fun rememberTimeline(ms: Int, still: Float = 0.9f): Float {
    val ctx = LocalContext.current
    val off = remember { try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Throwable) { false } }
    if (off) return still
    val t = rememberInfiniteTransition(label = "timeline")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "t")
    return v
}

/** 0 before [a], 1 after [b], eased in between. */
internal fun smooth(t: Float, a: Float, b: Float): Float {
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** "Build number" being tapped: a counter climbs to 7, then Android says you are a developer. */
@Composable
internal fun BuildNumberArt() {
    val t = rememberTimeline(6500)
    val n = when { t < 0.08f -> 0; t >= 0.58f -> 7; else -> (((t - 0.08f) / 0.5f) * 7f).toInt() + 1 }.coerceAtMost(7)
    val pill = smooth(t, 0.60f, 0.66f) * (1f - smooth(t, 0.93f, 0.98f))
    Frame {
        MockRowView(MockRow())
        MockRowView(MockRow(label = stringResource(R.string.guide_art_build), marked = true, badge = if (n > 0) "$n / 7" else null))
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.graphicsLayer { alpha = pill }.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text(stringResource(R.string.guide_art_dev), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.background) }
        }
    }
}

/** "USB debugging" in Developer options, being switched on. */
@Composable
internal fun UsbDebuggingArt() = SettingsMock(
    listOf(MockRow(), MockRow(label = stringResource(R.string.guide_art_usb), marked = true, toggle = true), MockRow()),
)

/**
 * Laptop and phone. A cable is drawn from the laptop to the phone, plugs in with a ripple, and the phone asks to allow USB
 * debugging: its Allow button is pressed.
 */
@Composable
internal fun CableArt() {
    val t = rememberTimeline(7000)
    val outline = MaterialTheme.colorScheme.outline
    val body = MaterialTheme.colorScheme.surfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val draw = smooth(t, 0.10f, 0.45f)
    val ripple = smooth(t, 0.47f, 0.64f)
    val popup = smooth(t, 0.56f, 0.62f) * (1f - smooth(t, 0.93f, 0.98f))
    val press = smooth(t, 0.78f, 0.84f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(200.dp).clearAndSetSemantics { }
            .clip(FrameShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FrameShape),
    ) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val W = size.width; val H = size.height
            val edge = Stroke(2.dp.toPx())
            // laptop: a screen with a few grey lines on it, and its base
            val lx = W * 0.07f; val lw = W * 0.34f; val lt = H * 0.16f; val lh = H * 0.48f
            drawRoundRect(body, Offset(lx, lt), Size(lw, lh), CornerRadius(10.dp.toPx()))
            drawRoundRect(outline, Offset(lx, lt), Size(lw, lh), CornerRadius(10.dp.toPx()), style = edge)
            for (i in 0..2) drawRoundRect(muted.copy(alpha = 0.3f), Offset(lx + 14.dp.toPx(), lt + 16.dp.toPx() + i * 14.dp.toPx()), Size(lw * (0.7f - i * 0.15f), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            val bx = lx - W * 0.03f; val bw = lw + W * 0.06f; val by = lt + lh + 5.dp.toPx(); val bh = 9.dp.toPx()
            drawRoundRect(body, Offset(bx, by), Size(bw, bh), CornerRadius(5.dp.toPx()))
            drawRoundRect(outline, Offset(bx, by), Size(bw, bh), CornerRadius(5.dp.toPx()), style = edge)
            // phone
            val pw = 76.dp.toPx(); val ph = 132.dp.toPx(); val px = W * 0.80f - pw / 2; val pt = H * 0.09f
            drawRoundRect(body, Offset(px, pt), Size(pw, ph), CornerRadius(14.dp.toPx()))
            drawRoundRect(strong.copy(alpha = 0.10f * ripple), Offset(px + 4.dp.toPx(), pt + 4.dp.toPx()), Size(pw - 8.dp.toPx(), ph - 8.dp.toPx()), CornerRadius(11.dp.toPx()))
            drawRoundRect(outline, Offset(px, pt), Size(pw, ph), CornerRadius(14.dp.toPx()), style = edge)
            // cable: from the end of the laptop's base round to the phone's bottom edge
            val a = Offset(bx + bw, by + bh / 2)
            val b = Offset(px + pw / 2, pt + ph)
            val path = Path().apply { moveTo(a.x, a.y); cubicTo(a.x + W * 0.16f, a.y, b.x, b.y + H * 0.16f, b.x, b.y) }
            val pm = PathMeasure().apply { setPath(path, false) }
            val len = pm.length
            if (draw > 0f) {
                val seg = Path()
                pm.getSegment(0f, len * draw, seg, true)
                drawPath(seg, strong, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val head = pm.getPosition(len * draw); val dir = pm.getTangent(len * draw)
                rotate(atan2(dir.y, dir.x) * (180f / PI.toFloat()), head) {
                    drawRoundRect(strong, Offset(head.x - 8.dp.toPx(), head.y - 5.dp.toPx()), Size(16.dp.toPx(), 10.dp.toPx()), CornerRadius(2.5.dp.toPx()))
                }
            }
            if (ripple in 0.001f..0.999f) drawCircle(strong.copy(alpha = (1f - ripple) * 0.5f), 10.dp.toPx() + 46.dp.toPx() * ripple, b, style = Stroke(2.dp.toPx()))
        }
        // the phone's question: two grey lines and its Allow button, pressed
        Column(
            Modifier.offset(x = w * 0.80f - 33.dp, y = 62.dp).width(66.dp).graphicsLayer { alpha = popup }
                .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Bar(0.95f, thin = true); Bar(0.65f, thin = true)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().clip(CircleShape).background(lerp(Color.Transparent, strong, press))
                    .border(1.dp, strong, CircleShape).padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.allow), style = MaterialTheme.typography.labelSmall, color = lerp(strong, MaterialTheme.colorScheme.background, press)) }
        }
    }
}

/** A terminal window: [command] is typed, Enter is pressed, then [output] (if any) and a fresh prompt appear. */
@Composable
internal fun TerminalArt(command: String, output: String? = null) {
    val t = rememberTimeline(8500)
    val typed = (smooth(t, 0.06f, 0.62f) * command.length).toInt()
    val entered = t >= 0.68f
    val blink = ((t * 40f).toInt() % 2) == 0
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 150.dp).clearAndSetSemantics { }
            .clip(FrameShape).background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, FrameShape).padding(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline)) }
        }
        Spacer(Modifier.height(10.dp))
        Text("$ " + command.take(typed) + if (!entered && blink) "▌" else "", style = mono, color = MaterialTheme.colorScheme.onSurface)
        if (entered && output != null) Text(output, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entered) Text("$ " + if (blink) "▌" else "", style = mono, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The finished result: the setting's row flips from "Not set up" to "Allowed". */
@Composable
internal fun DoneArt() {
    val t = rememberTimeline(5200)
    val done = t in 0.42f..0.95f
    Frame {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.pp_secure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Crossfade(targetState = done, label = "done") { d ->
                if (d) StatusChip(stringResource(R.string.allowed), ChipTone.SUCCESS) else StatusChip(stringResource(R.string.guide_status_todo), ChipTone.NEUTRAL)
            }
        }
    }
}
