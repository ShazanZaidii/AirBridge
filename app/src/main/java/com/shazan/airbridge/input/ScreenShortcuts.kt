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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
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
    private fun t(id: Int) = com.shazan.airbridge.net.Hub.app.getString(id)
    fun left(platform: String): List<Shortcut> = when (platform) {
        "android" -> listOf(
            Shortcut(KEYBOARD_TOGGLE, t(com.shazan.airbridge.R.string.ss_keyboard), Icons.Outlined.Keyboard, t(com.shazan.airbridge.R.string.ss_show_or_hide_the_keyboard_d)),
            Shortcut("back", t(com.shazan.airbridge.R.string.ss_back), Icons.AutoMirrored.Outlined.ArrowBack),
            Shortcut("home", t(com.shazan.airbridge.R.string.ss_home), Icons.Outlined.Home),
            Shortcut("recents", t(com.shazan.airbridge.R.string.ss_recents), Icons.Outlined.FilterNone, t(com.shazan.airbridge.R.string.ss_recent_apps_d)),
            Shortcut("app_drawer", t(com.shazan.airbridge.R.string.ss_apps), Icons.Outlined.Apps, t(com.shazan.airbridge.R.string.ss_all_apps_d)),
        )
        "windows" -> listOf(
            Shortcut(KEYBOARD_TOGGLE, t(com.shazan.airbridge.R.string.ss_keyboard), Icons.Outlined.Keyboard, t(com.shazan.airbridge.R.string.ss_show_or_hide_the_keyboard_d)),
            Shortcut("mission_control", t(com.shazan.airbridge.R.string.ss_task_view), Icons.Outlined.SpaceDashboard),
            Shortcut("app_expose", t(com.shazan.airbridge.R.string.ss_switch), Icons.Outlined.FilterNone, t(com.shazan.airbridge.R.string.ss_switch_windows_d)),
            Shortcut("desktop_left", t(com.shazan.airbridge.R.string.ss_desktop), Icons.Outlined.KeyboardDoubleArrowLeft, t(com.shazan.airbridge.R.string.ss_previous_desktop_d)),
            Shortcut("desktop_right", t(com.shazan.airbridge.R.string.ss_desktop), Icons.Outlined.KeyboardDoubleArrowRight, t(com.shazan.airbridge.R.string.ss_next_desktop_d)),
            Shortcut("app_drawer", t(com.shazan.airbridge.R.string.ss_start), Icons.Outlined.Apps, t(com.shazan.airbridge.R.string.ss_start_menu_d)),
        )
        else -> listOf(
            Shortcut(KEYBOARD_TOGGLE, t(com.shazan.airbridge.R.string.ss_keyboard), Icons.Outlined.Keyboard, t(com.shazan.airbridge.R.string.ss_show_or_hide_the_keyboard_d)),
            Shortcut("mission_control", t(com.shazan.airbridge.R.string.ss_mission), Icons.Outlined.SpaceDashboard, t(com.shazan.airbridge.R.string.ss_mission_control_d)),
            Shortcut("app_expose", t(com.shazan.airbridge.R.string.ss_windows), Icons.Outlined.FilterNone, t(com.shazan.airbridge.R.string.ss_app_expos_d)),
            Shortcut("desktop_left", t(com.shazan.airbridge.R.string.ss_space), Icons.Outlined.KeyboardDoubleArrowLeft, t(com.shazan.airbridge.R.string.ss_space_on_the_left_d)),
            Shortcut("desktop_right", t(com.shazan.airbridge.R.string.ss_space), Icons.Outlined.KeyboardDoubleArrowRight, t(com.shazan.airbridge.R.string.ss_space_on_the_right_d)),
            Shortcut("app_drawer", t(com.shazan.airbridge.R.string.ss_apps), Icons.Outlined.Apps, t(com.shazan.airbridge.R.string.ss_launchpad_d)),
        )
    }

    fun right(platform: String): List<Shortcut> = when (platform) {
        "android" -> listOf(
            Shortcut("notifications", t(com.shazan.airbridge.R.string.ss_alerts), Icons.Outlined.Notifications, t(com.shazan.airbridge.R.string.ss_notifications_d)),
            Shortcut("quick_settings", t(com.shazan.airbridge.R.string.ss_settings), Icons.Outlined.Tune, t(com.shazan.airbridge.R.string.ss_quick_settings_d)),
            Shortcut("vol_up", t(com.shazan.airbridge.R.string.ss_vol_up), Icons.AutoMirrored.Outlined.VolumeUp, t(com.shazan.airbridge.R.string.ss_volume_up_d)),
            Shortcut("vol_down", t(com.shazan.airbridge.R.string.ss_vol_down), Icons.AutoMirrored.Outlined.VolumeDown, t(com.shazan.airbridge.R.string.ss_volume_down_d)),
            Shortcut("lock", t(com.shazan.airbridge.R.string.ss_lock), Icons.Outlined.Lock, t(com.shazan.airbridge.R.string.ss_lock_screen_d)),
        )
        "windows" -> listOf(
            Shortcut("spotlight", t(com.shazan.airbridge.R.string.ss_search), Icons.Outlined.Search),
            Shortcut("notifications", t(com.shazan.airbridge.R.string.ss_alerts), Icons.Outlined.Notifications, t(com.shazan.airbridge.R.string.ss_notifications_d)),
            Shortcut("screenshot", t(com.shazan.airbridge.R.string.ss_capture), Icons.Outlined.Crop, t(com.shazan.airbridge.R.string.ss_screenshot_d)),
            Shortcut("lock", t(com.shazan.airbridge.R.string.ss_lock), Icons.Outlined.Lock, t(com.shazan.airbridge.R.string.ss_lock_screen_d)),
        )
        else -> listOf(
            Shortcut("spotlight", t(com.shazan.airbridge.R.string.ss_spotlight), Icons.Outlined.Search),
            Shortcut("dock_toggle", t(com.shazan.airbridge.R.string.ss_dock), Icons.Outlined.CallToAction, t(com.shazan.airbridge.R.string.ss_show_or_hide_the_dock_d)),
            Shortcut("menu_bar_toggle", t(com.shazan.airbridge.R.string.ss_menu_bar), Icons.Outlined.WebAsset, t(com.shazan.airbridge.R.string.ss_show_or_hide_the_menu_bar_d)),
            Shortcut("notifications", t(com.shazan.airbridge.R.string.ss_alerts), Icons.Outlined.Notifications, t(com.shazan.airbridge.R.string.ss_notification_center_d)),
            Shortcut("screenshot", t(com.shazan.airbridge.R.string.ss_capture), Icons.Outlined.Crop, t(com.shazan.airbridge.R.string.ss_screenshot_d)),
            Shortcut("lock", t(com.shazan.airbridge.R.string.ss_lock), Icons.Outlined.Lock, t(com.shazan.airbridge.R.string.ss_lock_screen_d)),
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
