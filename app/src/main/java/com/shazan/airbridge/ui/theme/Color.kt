package com.shazan.airbridge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * AirBridge palette (design-system/airbridge/MASTER.md: "dark tech + status green").
 * Raw ARGB values live only here; screens use MaterialTheme.colorScheme / [AbTheme.status].
 * Contrast (WCAG) on Surface #1B2336: OnSurface 15:1, OnSurfaceMuted 6.1:1, Accent 6.9:1,
 * Danger 5.7:1, Warning 9.4:1, Info 6.2:1.
 */
object Palette {
    const val BACKGROUND = 0xFF0F172A
    const val SURFACE = 0xFF1B2336
    const val SURFACE_HIGH = 0xFF272F42
    const val SECONDARY = 0xFF334155
    const val ON_SURFACE = 0xFFF8FAFC
    const val ON_SURFACE_MUTED = 0xFF94A3B8
    /** Control boundaries (3.3:1 on Surface). */
    const val OUTLINE = 0xFF64748B
    /** Decorative dividers. */
    const val OUTLINE_VARIANT = 0xFF334155
    const val ACCENT = 0xFF22C55E
    const val ON_ACCENT = 0xFF0F172A
    const val DANGER = 0xFFF87171
    const val DANGER_FILL = 0xFFEF4444
    const val WARNING = 0xFFFBBF24
    const val INFO = 0xFF60A5FA
    const val SCRIM = 0xCC0F172A

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
