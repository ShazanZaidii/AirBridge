package com.shazan.airbridge.input

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shortcut beside the mirrored screen: a §8.5 action with its icon and a short label. */
data class Shortcut(val action: String, val label: String, val icon: ImageVector, val description: String = label)

/** Not sent to the computer: shows / hides the phone's full keyboard over the mirrored screen. */
const val KEYBOARD_TOGGLE = "@keyboard"

/**
 * The two shortcut groups shown in the empty bands beside the mirrored screen: the keyboard toggle
 * first (always reachable, even when the keyboard makes the rail scroll), desktops and windows on
 * the left, system on the right. Only actions the target understands are listed.
 */
object ScreenShortcuts {
    fun left(platform: String): List<Shortcut> = when (platform) {
        "windows" -> listOf(
            Shortcut(KEYBOARD_TOGGLE, "Keyboard", Icons.Outlined.Keyboard, "Show or hide the keyboard"),
            Shortcut("mission_control", "Task View", Icons.Outlined.SpaceDashboard),
            Shortcut("app_expose", "Switch", Icons.Outlined.FilterNone, "Switch windows"),
            Shortcut("desktop_left", "Desktop", Icons.Outlined.KeyboardDoubleArrowLeft, "Previous desktop"),
            Shortcut("desktop_right", "Desktop", Icons.Outlined.KeyboardDoubleArrowRight, "Next desktop"),
            Shortcut("app_drawer", "Start", Icons.Outlined.Apps, "Start menu"),
        )
        else -> listOf(
            Shortcut(KEYBOARD_TOGGLE, "Keyboard", Icons.Outlined.Keyboard, "Show or hide the keyboard"),
            Shortcut("mission_control", "Mission", Icons.Outlined.SpaceDashboard, "Mission Control"),
            Shortcut("app_expose", "Windows", Icons.Outlined.FilterNone, "App Exposé"),
            Shortcut("desktop_left", "Space", Icons.Outlined.KeyboardDoubleArrowLeft, "Space on the left"),
            Shortcut("desktop_right", "Space", Icons.Outlined.KeyboardDoubleArrowRight, "Space on the right"),
            Shortcut("app_drawer", "Apps", Icons.Outlined.Apps, "Launchpad"),
        )
    }

    fun right(platform: String): List<Shortcut> = when (platform) {
        "windows" -> listOf(
            Shortcut("spotlight", "Search", Icons.Outlined.Search),
            Shortcut("notifications", "Alerts", Icons.Outlined.Notifications, "Notifications"),
            Shortcut("screenshot", "Capture", Icons.Outlined.Crop, "Screenshot"),
            Shortcut("lock", "Lock", Icons.Outlined.Lock, "Lock screen"),
        )
        else -> listOf(
            Shortcut("spotlight", "Spotlight", Icons.Outlined.Search),
            Shortcut("dock_toggle", "Dock", Icons.Outlined.CallToAction, "Show or hide the Dock"),
            Shortcut("menu_bar_toggle", "Menu bar", Icons.Outlined.WebAsset, "Show or hide the menu bar"),
            Shortcut("notifications", "Alerts", Icons.Outlined.Notifications, "Notification Center"),
            Shortcut("screenshot", "Capture", Icons.Outlined.Crop, "Screenshot"),
            Shortcut("lock", "Lock", Icons.Outlined.Lock, "Lock screen"),
        )
    }
}

/**
 * A vertical rail of shortcut tiles (landscape: beside the picture). The tiles share the height
 * evenly (up to 72 dp each); when even 44 dp each does not fit (keyboard open), the rail scrolls.
 */
@Composable
fun ShortcutRail(items: List<Shortcut>, width: Dp, enabled: Boolean, onAction: (String) -> Unit, modifier: Modifier = Modifier, active: Set<String> = emptySet()) {
    BoxWithConstraints(modifier.width(width).fillMaxHeight()) {
        val gap = 6.dp
        val pad = 8.dp
        val each = (maxHeight - pad * 2 - gap * (items.size - 1)) / items.size
        val fits = each >= 44.dp
        Column(
            Modifier.fillMaxSize().then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(vertical = pad, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val h = if (fits) each.coerceAtMost(72.dp) else 56.dp
            for (s in items) ShortcutTile(s, enabled || s.action == KEYBOARD_TOGGLE, onAction, Modifier.fillMaxWidth().height(h), compact = h < 52.dp, on = s.action in active)
        }
    }
}

/** A horizontal strip of shortcut tiles (portrait: under the picture). */
@Composable
fun ShortcutStrip(items: List<Shortcut>, enabled: Boolean, onAction: (String) -> Unit, modifier: Modifier = Modifier, active: Set<String> = emptySet()) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (s in items) ShortcutTile(s, enabled || s.action == KEYBOARD_TOGGLE, onAction, Modifier.width(72.dp), on = s.action in active)
    }
}

/**
 * Tile: icon over a one-line label. Fires on touch-down (as instant as a key), with a haptic tick
 * and a pressed fill; works with other fingers on the mirror.
 */
@Composable
private fun ShortcutTile(s: Shortcut, enabled: Boolean, onAction: (String) -> Unit, modifier: Modifier, compact: Boolean = false, on: Boolean = false) {
    val view = LocalView.current
    val context = LocalContext.current
    val noMotion = remember { reducedMotion(context) }
    val fire by rememberUpdatedState { if (enabled) onAction(s.action) }
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when { pressed -> Tp.TilePressed; on -> Tp.Accent.copy(alpha = 0.22f); else -> Tp.Tile },
        if (noMotion) snap() else tween(90), label = "tile",
    )
    val fg: Color = when { !enabled -> Tp.MutedForeground.copy(alpha = 0.5f); on -> Tp.Accent; else -> Tp.Foreground }
    Column(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .semantics(mergeDescendants = true) {
                role = if (s.action == KEYBOARD_TOGGLE) Role.Switch else Role.Button
                contentDescription = s.description
                onClick { fire(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    fire()
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .padding(vertical = if (compact) 4.dp else 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(s.icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 20.dp else 22.dp))
        if (!compact) Spacer(Modifier.height(4.dp))
        Text(s.label, color = if (enabled) Tp.MutedForeground else fg, fontSize = 11.sp, lineHeight = 13.sp,
            maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}
