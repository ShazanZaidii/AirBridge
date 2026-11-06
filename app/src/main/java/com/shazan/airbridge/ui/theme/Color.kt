package com.shazan.airbridge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * AirBridge palette: neutral graphite (no blue-slate tint), white primary actions, one calm blue
 * accent used sparingly (selection, links, "connected"). No green anywhere.
 * Raw ARGB values live only here; screens use MaterialTheme.colorScheme / [AbTheme.status].
 * Contrast (WCAG) on Surface #1B1B1E: OnSurface 16:1, OnSurfaceMuted 7.0:1, Accent 6.3:1,
 * Danger 6.0:1, Warning 11:1.
 */
object Palette {
    const val BACKGROUND = 0xFF0F0F11
    const val SURFACE = 0xFF1B1B1E
    const val SURFACE_HIGH = 0xFF27272B
    const val SECONDARY = 0xFF34343A
    const val ON_SURFACE = 0xFFF4F4F6
    const val ON_SURFACE_MUTED = 0xFFA1A1A8
    /** Control boundaries (3.3:1 on Surface). */
    const val OUTLINE = 0xFF6B6B73
    /** Decorative dividers. */
    const val OUTLINE_VARIANT = 0xFF2F2F34
    /** Filled primary buttons: light on dark, like the system's own. */
    const val PRIMARY = 0xFFF4F4F6
    const val ON_PRIMARY = 0xFF0F0F11
    /** The one accent: selection, links, switches, "connected". */
    const val ACCENT = 0xFF5B9DFF
    const val ON_ACCENT = 0xFF0F0F11
    const val DANGER = 0xFFFF7A7A
    const val DANGER_FILL = 0xFFE5484D
    const val WARNING = 0xFFFFC53D
    const val INFO = 0xFF5B9DFF
    const val SCRIM = 0xCC0F0F11

    fun argb(v: Long): Int = v.toInt()
}

val Background = Color(Palette.BACKGROUND)
val Surface = Color(Palette.SURFACE)
val SurfaceHigh = Color(Palette.SURFACE_HIGH)
val Secondary = Color(Palette.SECONDARY)
val OnSurface = Color(Palette.ON_SURFACE)
val OnSurfaceMuted = Color(Palette.ON_SURFACE_MUTED)
val Outline = Color(Palette.OUTLINE)
val OutlineVariant = Color(Palette.OUTLINE_VARIANT)
val Primary = Color(Palette.PRIMARY)
val OnPrimary = Color(Palette.ON_PRIMARY)
val Accent = Color(Palette.ACCENT)
val OnAccent = Color(Palette.ON_ACCENT)
val Danger = Color(Palette.DANGER)
val DangerFill = Color(Palette.DANGER_FILL)
val Warning = Color(Palette.WARNING)
val Info = Color(Palette.INFO)
val Scrim = Color(Palette.SCRIM)

/** Semantic status colours (device chips, transfer results). Always paired with a text label. */
@Immutable
data class StatusColors(
    val success: Color = Accent,
    val warning: Color = Warning,
    val info: Color = Info,
    val neutral: Color = OnSurfaceMuted,
    val danger: Color = Danger,
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }
