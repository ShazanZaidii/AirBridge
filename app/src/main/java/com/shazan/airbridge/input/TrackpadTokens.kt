package com.shazan.airbridge.input

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Local design tokens for the trackpad / keyboard / screen UI, taken from
 * design-system/airbridge/MASTER.md ("dark tech + status green"). `ui/theme` does not define these
 * yet; when it does, point these at it. [Warning] and [KeyFace]/[KeyPressed] are not in MASTER
 * (defined here; reported to the theme owner).
 */
internal object Tp {
    val Background = Color(0xFF0F172A)
    val Surface = Color(0xFF1B2336) // MASTER "card"
    val SurfaceHigh = Color(0xFF272F42) // MASTER "muted"
    val Secondary = Color(0xFF334155)
    val Border = Color(0xFF475569)
    val Foreground = Color(0xFFF8FAFC)
    val MutedForeground = Color(0xFF94A3B8)
    val Accent = Color(0xFF22C55E)
    val OnAccent = Color(0xFF0F172A)
    val Destructive = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B) // not in MASTER
    val OnWarning = Color(0xFF0F172A)
    val KeyFace = Color(0xFF1E293B) // MASTER "primary" used as key cap
    val KeyPressed = Color(0xFF334155)
    val KeyboardBg = Color(0xFF0B1222) // not in MASTER: slightly below Background

    val TouchTarget = 48.dp
    val BarHeight = 56.dp
    val Radius = 12.dp
}

/** True when the user turned animations off (Settings › Accessibility › Remove animations). */
internal fun reducedMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Throwable) { false }
