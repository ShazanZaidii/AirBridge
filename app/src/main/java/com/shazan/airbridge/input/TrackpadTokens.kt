package com.shazan.airbridge.input

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Local design tokens for the trackpad / keyboard / screen UI, taken from
 * design-system/airbridge/MASTER.md ("dark tech + status green"). `ui/theme` does not define these
 * yet; when it does, point these at it. [Warning] and [KeyboardBg] are not in MASTER
 * (defined here; reported to the theme owner).
 */
internal object Tp {
    // Same neutral graphite as the app (ui/theme/Color.kt), one blue accent, no green.
    val Background = Color(0xFF0F0F11)
    val Surface = Color(0xFF1B1B1E)
    val SurfaceHigh = Color(0xFF27272B)
    val Secondary = Color(0xFF34343A)
    val Border = Color(0xFF4A4A50)
    val Foreground = Color(0xFFF4F4F6)
    val MutedForeground = Color(0xFFA1A1A8)
    val Accent = Color(0xFF5B9DFF)
    val OnAccent = Color(0xFF0F0F11)
    val Destructive = Color(0xFFE5484D)
    val Warning = Color(0xFFFFC53D)
    val OnWarning = Color(0xFF0F0F11)
    val KeyFace = Color(0xFF242428)
    // Pressed key / Caps on: light key with a dark label, like a lit physical key. No accent on keys.
    val KeyLatched = Color(0xFFD9D9DE)
    val OnKeyLatched = Color(0xFF0F0F11)
    // Caps Lock LED: bright when lit (on the light fill), dim grey when off.
    val LedOn = Color(0xFF5B9DFF)
    val LedOff = Color(0xFF4A4A50)
    val KeyboardBg = Color(0xFF0A0A0C)
    /** Shortcut tiles beside the mirrored screen. */
    val Tile = Color(0xFF1B1B1E)
    val TilePressed = Color(0xFF303036)

    val TouchTarget = 48.dp
    val BarHeight = 56.dp
    val Radius = 12.dp
}

/** True when the user turned animations off (Settings › Accessibility › Remove animations). */
internal fun reducedMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Throwable) { false }
