package com.shazan.airbridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private val AirBridgeColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = OnSurface,
    secondary = Secondary,
    onSecondary = OnSurface,
    secondaryContainer = SurfaceHigh,
    onSecondaryContainer = OnSurface,
    tertiary = Info,
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
    CompositionLocalProvider(LocalStatusColors provides StatusColors()) {
        MaterialTheme(colorScheme = AirBridgeColors, typography = Typography, shapes = AirBridgeShapes, content = content)
    }
}

object AbTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
