package com.shazan.airbridge.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared building blocks for AirBridge screens. Colours come only from the theme. */

fun platformIcon(plat: String): ImageVector = when (plat) {
    "macos" -> Icons.Outlined.LaptopMac
    "windows" -> Icons.Outlined.DesktopWindows
    "linux" -> Icons.Outlined.Computer
    "android" -> Icons.Outlined.PhoneAndroid
    "ios" -> Icons.Outlined.PhoneIphone
    else -> Icons.Outlined.Devices
}

fun platformLabel(plat: String): String = when (plat) {
    "macos" -> "Mac"; "windows" -> "Windows PC"; "linux" -> "Linux"; "android" -> "Android"; "ios" -> "iPhone or iPad"
    else -> "Device"
}

/** "your Mac" / "your PC" for instructions. */
fun platformNoun(plat: String): String = when (plat) {
    "macos" -> "Mac"; "windows" -> "PC"; "android" -> "phone"; "ios" -> "iPhone"; else -> "computer"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /** Hidden gesture on the title (Settings → Wi-Fi Direct test screen). */
    onTitleLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    val m = Modifier.semantics { heading() }.let { base ->
                        if (onTitleLongPress == null) base
                        else base.pointerInput(onTitleLongPress) { detectTapGestures(onLongPress = { onTitleLongPress() }) }
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = m)
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = Dimens.md),
            content = content,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Dimens.xs, top = Dimens.lg, bottom = Dimens.sm).semantics { heading() },
    )
}

@Composable
fun AbCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    if (onClick != null) {
        Card(onClick = onClick, shape = MaterialTheme.shapes.large, colors = colors, modifier = modifier.fillMaxWidth(), content = content)
    } else {
        Card(shape = MaterialTheme.shapes.large, colors = colors, modifier = modifier.fillMaxWidth(), content = content)
    }
}

/** Divider between rows inside one [AbCard]. */
@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = Dimens.md), color = MaterialTheme.colorScheme.outlineVariant)

/** Platform glyph in a soft rounded tile. Decorative when the name is shown beside it. */
@Composable
fun PlatformAvatar(plat: String, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        Modifier.size(Dimens.avatar).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(platformIcon(plat), contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.iconMd))
    }
}

enum class ChipTone { SUCCESS, WARNING, INFO, NEUTRAL, DANGER }

@Composable
fun toneColor(tone: ChipTone): Color {
    val s = AbTheme.status
    return when (tone) {
        ChipTone.SUCCESS -> s.success; ChipTone.WARNING -> s.warning; ChipTone.INFO -> s.info
        ChipTone.NEUTRAL -> s.neutral; ChipTone.DANGER -> s.danger
    }
}

/** Live status pill: dot + label (never colour alone). Colour changes animate (200 ms). */
@Composable
fun StatusChip(label: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val target = toneColor(tone)
    val c by animateColorAsState(target, tween(200), label = "chip")
    Row(
        modifier
            .background(c.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 10.dp, vertical = Dimens.xs)
            .clearAndSetSemantics { contentDescription = "Status: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Dimens.sm).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = Dimens.lg),
        modifier = modifier.fillMaxWidth().heightIn(min = Dimens.buttonHeight),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSm)); Spacer(Modifier.width(Dimens.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)),
        contentPadding = PaddingValues(horizontal = Dimens.lg),
        modifier = modifier.fillMaxWidth().heightIn(min = Dimens.buttonHeight),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSm)); Spacer(Modifier.width(Dimens.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A whole-row switch (row is the touch target, ≥ 48 dp tall). */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Dimens.md, vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Dimens.md))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** Title/subtitle row with a trailing slot (status text or a button). */
@Composable
fun InfoRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Dimens.md, vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(Dimens.md)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Dimens.sm))
        trailing()
    }
}

/** Calm empty/error state: icon, title, one line of help, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Dimens.xl, horizontal = Dimens.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(Dimens.xs))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp))
        if (action != null) { Spacer(Modifier.height(Dimens.sm)); action() }
    }
}
