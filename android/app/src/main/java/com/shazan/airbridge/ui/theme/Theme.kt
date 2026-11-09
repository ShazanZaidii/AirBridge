package com.shazan.airbridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.util.DisplayMetrics
import androidx.compose.ui.unit.dp

private val AirBridgeColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = OnSurface,
    secondary = Secondary,
    onSecondary = OnSurface,
    secondaryContainer = SurfaceHigh,
    onSecondaryContainer = OnSurface,
    tertiary = Accent,
    onTertiary = OnAccent,
    background = Background,
    onBackground = OnSurface,
    surface = Background,
    onSurface = OnSurface,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface,
    surfaceContainerHighest = SurfaceHigh,
    inverseSurface = OnSurface,
    inverseOnSurface = Background,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Danger,
    onError = OnAccent,
    errorContainer = SurfaceHigh,
    onErrorContainer = Danger,
    scrim = Scrim,
)

private val AirBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Spacing (4/8 dp rhythm), touch and icon size tokens. */
object Dimens {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val touch = 48.dp
    val buttonHeight = 52.dp
    val iconSm = 20.dp
    val iconMd = 24.dp
    val avatar = 44.dp
}

/** Always dark: AirBridge is a calm utility that sits next to a desktop. */
@Composable
fun AirBridgeTheme(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val density = remember(base.density, base.fontScale, widthDp) { usableDensity(base, widthDp) }
    val locale = LocalConfiguration.current.locales[0]
    val typography = remember(locale) { typographyFor(locale) }
    CompositionLocalProvider(LocalDensity provides density, LocalStatusColors provides StatusColors()) {
        MaterialTheme(colorScheme = AirBridgeColors, typography = typography, shapes = AirBridgeShapes, content = content)
    }
}

/**
 * Keeps every screen usable whatever the phone's "Display size" and "Font size" are set to.
 *
 * - Display size zooms the whole UI. We follow it up to 10 % past the phone's built-in density, and never
 *   so far that the screen is narrower than 360 dp: past that, cards, buttons side by side and titles
 *   no longer fit and words get broken in the middle.
 * - Font size is followed between 0.85× and 1.15×. Larger settings made titles huge and pushed text
 *   off cards; 1.15× still reads clearly on every screen here.
 */
internal fun usableDensity(base: Density, screenWidthDp: Int): Density {
    val builtIn = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
    val widthPx = screenWidthDp * base.density
    var d = minOf(base.density, builtIn * 1.10f)
    if (widthPx > 0f) d = minOf(d, widthPx / MIN_WIDTH_DP)
    d = maxOf(d, builtIn * 0.80f)
    return Density(d, base.fontScale.coerceIn(0.85f, 1.15f))
}

/** The narrowest layout the screens are designed for. */
private const val MIN_WIDTH_DP = 360f

object AbTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
