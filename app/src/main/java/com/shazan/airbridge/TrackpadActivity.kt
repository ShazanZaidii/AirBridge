package com.shazan.airbridge

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shazan.airbridge.input.InputRouter
import com.shazan.airbridge.input.MirrorRouting
import com.shazan.airbridge.input.MirrorScreen
import com.shazan.airbridge.input.MirrorSink
import com.shazan.airbridge.input.NetHooks
import com.shazan.airbridge.input.Notice
import com.shazan.airbridge.input.RemoteKeyboard
import com.shazan.airbridge.input.RemoteKeyboardController
import com.shazan.airbridge.input.RemoteKeyboardMinHeight
import com.shazan.airbridge.input.Tp
import com.shazan.airbridge.input.TrackpadPrefs
import com.shazan.airbridge.input.TrackpadSender
import com.shazan.airbridge.input.reducedMotion
import com.shazan.airbridge.input.trackpadGestures
import com.shazan.airbridge.net.PeerInfo
import kotlinx.coroutines.delay

/**
 * The phone as an input device for a desktop peer: **Trackpad**, **Keyboard** and **Screen**
 * (mirror sink, §8.7) modes. Input is direct mode (§8.1); the peer's lease answers (`ctl_busy`,
 * §8.6) are shown as a non-blocking banner.
 */
class TrackpadActivity : ComponentActivity() {

    enum class Mode(val label: String, val icon: ImageVector) {
        Trackpad("Trackpad", Icons.Default.TouchApp),
        Keyboard("Keyboard", Icons.Default.Keyboard),
        Screen("Screen", Icons.AutoMirrored.Filled.ScreenShare),
    }

    private companion object {
        val DESKTOPS = setOf("macos", "windows", "linux")
        const val PREF_PEER = "TRACKPAD_PEER"
        const val PREF_NATURAL = "TP_NATURAL_SCROLL"
        const val PREF_MODE = "TP_MODE"
        const val BUSY_SHOW_MS = 3000L
    }

    private val sender = TrackpadSender()
    private val keyboard by lazy { RemoteKeyboardController(sender) }
    private val mirror = MirrorSink()
    private var resumed by mutableStateOf(false)
    private var resumeCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InputRouter.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Used held sideways. Orientation requests may be ignored (Android 16 large screens,
        // multi-window); the layout adapts to the real window size either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setTranslucent(false)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            try { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE } catch (_: Throwable) {}
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Tp.Accent, surface = Tp.Surface, background = Tp.Background)) {
                TrackpadScreen(prefs)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        InputRouter.refreshPeers()
        resumed = true
        resumeCount++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        keyboard.releaseAll()
        sender.releaseAll()
        mirror.stop() // §8.7: mirror_stop on pause (restarted by the effect on resume)
        NetHooks.setBubbleSuppressed("trackpad", false)
        NetHooks.setBubbleSuppressed("mirror", false)
    }

    override fun onDestroy() {
        super.onDestroy()
        mirror.release()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Composable
    private fun TrackpadScreen(prefs: SharedPreferences) {
        val view = LocalView.current
        val context = LocalContext.current
        val noMotion = remember { reducedMotion(context) }
        val peers by InputRouter.peers.collectAsState()
        val capsMap by InputRouter.capsByPeer.collectAsState()
        val busyMap by InputRouter.busyByPeer.collectAsState()
        val desktops = peers.filter { it.platform in DESKTOPS }
        var chosenId by remember { mutableStateOf(prefs.getString(PREF_PEER, null)) }
        val active = desktops.firstOrNull { it.id == chosenId } ?: desktops.firstOrNull()
        var mode by remember {
            mutableStateOf(Mode.entries.firstOrNull { it.name == prefs.getString(PREF_MODE, null) } ?: Mode.Trackpad)
        }
        var natural by remember { mutableStateOf(prefs.getBoolean(PREF_NATURAL, true)) }
        var screenKeyboard by remember { mutableStateOf(false) }
        var showStats by remember { mutableStateOf(false) }
        var mirrorRun by remember { mutableStateOf(0) } // bump to restart the stream
        val trackpadPrefs by rememberUpdatedState(TrackpadPrefs(naturalScroll = natural))

        SideEffect {
            if (sender.peerId != active?.id) {
                keyboard.releaseAll() // to the previous peer, before switching
                sender.peerId = active?.id
            }
            keyboard.onPeerCaps(active?.let { capsMap[it.id] })
            keyboard.onCapsToggled = { on -> active?.let { InputRouter.setCapsOptimistic(it.id, on) } }
        }

        // §5: the clipboard bubble hides while these screens are in front.
        LaunchedEffect(resumed, mode) {
            NetHooks.setBubbleSuppressed("trackpad", resumed && mode != Mode.Screen)
            NetHooks.setBubbleSuppressed("mirror", resumed && mode == Mode.Screen)
        }

        val busy = active?.let { busyMap[it.id] }
        LaunchedEffect(busy) {
            if (busy != null) {
                // Pre-empted (§8.6): the peer drops our input; release anything we hold locally.
                keyboard.releaseAll()
                sender.releaseAll()
                delay((BUSY_SHOW_MS - (System.currentTimeMillis() - busy.atMillis)).coerceAtLeast(0))
                active?.id?.let(InputRouter::clearBusy)
            }
        }

        fun switchMode(m: Mode) {
            if (m == mode) return
            keyboard.releaseAll()
            sender.releaseAll()
            mode = m
            prefs.edit().putString(PREF_MODE, m.name).apply()
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        BackHandler(enabled = mode == Mode.Screen && screenKeyboard) {
            keyboard.releaseAll()
            screenKeyboard = false
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Tp.Background)) {
            val landscape = maxWidth > maxHeight
            val compact = maxWidth < 600.dp
            val portraitKeyboardHeight = min(maxWidth * 0.75f, maxHeight * 0.55f)
            val windowW = constraints.maxWidth
            val windowH = constraints.maxHeight

            // Screen mode stream lifecycle: start while resumed with a capable peer; stop otherwise.
            val mirrorPeer = active?.takeIf { mode == Mode.Screen && resumed && "mirror_source" in it.caps }
            if (mode == Mode.Screen) MirrorRouting(mirror)
            LaunchedEffect(mirrorPeer?.id, windowW, windowH, mirrorRun, resumeCount) {
                if (mirrorPeer != null) mirror.start(
                    mirrorPeer.id, minOf(windowW, MirrorSink.MAX_W), minOf(windowH, MirrorSink.MAX_H),
                    computerLabel = if (mirrorPeer.platform == "macos") "Mac" else "PC",
                )
                else mirror.stop()
            }

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                TopBar(
                    desktops = desktops,
                    active = active,
                    mode = mode,
                    compact = compact,
                    natural = natural,
                    screenKeyboard = screenKeyboard,
                    onChoose = { p ->
                        chosenId = p.id
                        prefs.edit().putString(PREF_PEER, p.id).apply()
                    },
                    onMode = ::switchMode,
                    onToggleStats = { showStats = !showStats },
                    onToggleNatural = {
                        natural = !natural
                        prefs.edit().putBoolean(PREF_NATURAL, natural).apply()
                    },
                    onToggleScreenKeyboard = {
                        if (screenKeyboard) keyboard.releaseAll()
                        screenKeyboard = !screenKeyboard
                    },
                    onClose = { finish() },
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (mode) {
                        Mode.Trackpad -> TrackpadSurface(active, trackpadPrefs, Modifier.fillMaxSize().padding(12.dp))
                        Mode.Keyboard -> Column(Modifier.fillMaxSize()) {
                            if (!landscape) TrackpadSurface(active, trackpadPrefs, Modifier.fillMaxWidth().weight(1f).padding(12.dp))
                            KeyboardPanel(
                                active,
                                if (landscape) Modifier.fillMaxWidth().weight(1f)
                                else Modifier.fillMaxWidth().height(portraitKeyboardHeight),
                            )
                        }
                        Mode.Screen -> Column(Modifier.fillMaxSize()) {
                            MirrorScreen(
                                sink = mirror,
                                sender = sender,
                                peerName = active?.name,
                                supported = active?.let { "mirror_source" in it.caps } ?: false,
                                showStats = showStats,
                                onRetry = { mirrorRun++ },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                            if (screenKeyboard) KeyboardPanel(active, Modifier.fillMaxWidth().heightIn(min = RemoteKeyboardMinHeight).fillMaxHeight(0.5f))
                        }
                    }
                    // Non-blocking lease banner: no pointer handlers, touches pass through.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = busy != null,
                        enter = if (noMotion) EnterTransition.None else fadeIn(tween(150)) + slideInVertically(tween(200)) { -it / 2 },
                        exit = if (noMotion) ExitTransition.None else fadeOut(tween(150)),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    ) {
                        BusyBanner(busy?.holder ?: "")
                    }
                }
            }
        }
    }

    @Composable
    private fun TrackpadSurface(active: PeerInfo?, prefs: TrackpadPrefs, modifier: Modifier) {
        val view = LocalView.current
        val current by rememberUpdatedState(prefs)
        Box(
            modifier.clip(RoundedCornerShape(16.dp)).background(Tp.Surface)
                .border(1.dp, Tp.SurfaceHigh, RoundedCornerShape(16.dp))
                .semantics { contentDescription = "Trackpad" }
                .pointerInput(Unit) { trackpadGestures(sender, { current }) { view.performHapticFeedback(it) } },
            contentAlignment = Alignment.Center,
        ) {
            if (active == null) {
                Notice("No computer connected", "Connect a computer from the AirBridge home screen.")
            } else {
                Text(
                    "Tap to click · two-finger tap to right-click · hold, then move to drag\n" +
                        "Two fingers to scroll · three-finger swipe for Mission Control and desktops",
                    color = Tp.MutedForeground.copy(alpha = 0.6f),
                    fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp).padding(24.dp),
                )
            }
        }
    }

    @Composable
    private fun KeyboardPanel(active: PeerInfo?, modifier: Modifier) {
        Column(modifier.background(Tp.KeyboardBg)) {
            RemoteKeyboard(
                controller = keyboard,
                platform = active?.platform ?: "macos",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    @Composable
    private fun BusyBanner(holder: String) {
        Row(
            Modifier.clip(RoundedCornerShape(Tp.Radius)).background(Tp.Warning)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PanTool, contentDescription = null, tint = Tp.OnWarning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Being used by $holder", color = Tp.OnWarning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun TopBar(
        desktops: List<PeerInfo>,
        active: PeerInfo?,
        mode: Mode,
        compact: Boolean,
        natural: Boolean,
        screenKeyboard: Boolean,
        onChoose: (PeerInfo) -> Unit,
        onMode: (Mode) -> Unit,
        onToggleStats: () -> Unit,
        onToggleNatural: () -> Unit,
        onToggleScreenKeyboard: () -> Unit,
        onClose: () -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().height(Tp.BarHeight).background(Tp.Background).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TargetChip(desktops, active, compact, onChoose, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(0.01f))
            ModeSwitcher(mode, compact, onMode, onToggleStats)
            Spacer(Modifier.weight(0.01f))
            when (mode) {
                Mode.Trackpad -> ToggleButton(
                    icon = Icons.Default.SwapVert, label = if (compact) null else "Natural scroll",
                    on = natural, description = "Natural scrolling", onClick = onToggleNatural,
                )
                Mode.Screen -> ToggleButton(
                    icon = if (screenKeyboard) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                    label = null, on = screenKeyboard,
                    description = if (screenKeyboard) "Hide keyboard" else "Show keyboard",
                    onClick = onToggleScreenKeyboard,
                )
                Mode.Keyboard -> Unit
            }
            IconButton48(Icons.Default.Close, "Close", onClose)
        }
    }

    @Composable
    private fun TargetChip(
        desktops: List<PeerInfo>,
        active: PeerInfo?,
        compact: Boolean,
        onChoose: (PeerInfo) -> Unit,
        modifier: Modifier,
    ) {
        var menu by remember { mutableStateOf(false) }
        Box(modifier) {
            Row(
                Modifier.height(Tp.TouchTarget).clip(RoundedCornerShape(Tp.Radius)).background(Tp.Surface)
                    .clickable(enabled = desktops.size > 1, role = Role.DropdownList) { menu = true }
                    .semantics(mergeDescendants = true) {
                        contentDescription = active?.let { "Controlling ${it.name}" } ?: "No computer connected"
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Icon(
                        platformIcon(active?.platform), contentDescription = null,
                        tint = if (active != null) Tp.Foreground else Tp.MutedForeground, modifier = Modifier.size(22.dp),
                    )
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(9.dp).clip(CircleShape).background(Tp.Background)
                            .padding(1.5.dp).clip(CircleShape)
                            .background(if (active != null) Tp.Accent else Tp.Destructive),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    active?.name ?: "No computer",
                    color = if (active != null) Tp.Foreground else Tp.MutedForeground,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 120.dp else 260.dp),
                )
                if (desktops.size > 1) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = Tp.MutedForeground, modifier = Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Tp.Surface) {
                desktops.forEach { p ->
                    DropdownMenuItem(
                        leadingIcon = { Icon(platformIcon(p.platform), contentDescription = null, tint = Tp.Foreground) },
                        text = {
                            Column {
                                Text(p.name, color = Tp.Foreground, fontWeight = if (p.id == active?.id) FontWeight.SemiBold else FontWeight.Normal)
                                Text(platformLabel(p.platform), color = Tp.MutedForeground, fontSize = 12.sp)
                            }
                        },
                        trailingIcon = if (p.id == active?.id) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected", tint = Tp.Accent) }
                        } else null,
                        onClick = { menu = false; onChoose(p) },
                        modifier = Modifier.height(Tp.BarHeight),
                    )
                }
            }
        }
    }

    /** Segmented control; long-press on "Screen" toggles the stream stats overlay. */
    @Composable
    private fun ModeSwitcher(mode: Mode, compact: Boolean, onMode: (Mode) -> Unit, onToggleStats: () -> Unit) {
        Row(
            Modifier.height(Tp.TouchTarget).clip(RoundedCornerShape(Tp.Radius)).background(Tp.Surface).padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mode.entries.forEach { m ->
                val selected = m == mode
                Row(
                    Modifier.fillMaxHeight().widthIn(min = Tp.TouchTarget)
                        .clip(RoundedCornerShape(Tp.Radius - 3.dp))
                        .background(if (selected) Tp.SurfaceHigh else Color.Transparent)
                        .combinedClickable(
                            role = Role.Tab,
                            onClick = { onMode(m) },
                            onLongClick = if (m == Mode.Screen) onToggleStats else null,
                        )
                        .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = m.label }
                        .padding(horizontal = if (compact) 10.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(m.icon, contentDescription = null, tint = if (selected) Tp.Accent else Tp.MutedForeground, modifier = Modifier.size(20.dp))
                    if (!compact) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            m.label, fontSize = 14.sp,
                            color = if (selected) Tp.Foreground else Tp.MutedForeground,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ToggleButton(icon: ImageVector, label: String?, on: Boolean, description: String, onClick: () -> Unit) {
        Row(
            Modifier.height(Tp.TouchTarget).widthIn(min = Tp.TouchTarget).clip(RoundedCornerShape(Tp.Radius))
                .background(if (on) Tp.Accent.copy(alpha = 0.18f) else Tp.Surface)
                .clickable(role = Role.Switch, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "$description, ${if (on) "on" else "off"}" }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (on) Tp.Accent else Tp.MutedForeground, modifier = Modifier.size(20.dp))
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 14.sp, color = if (on) Tp.Foreground else Tp.MutedForeground, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun IconButton48(icon: ImageVector, description: String, onClick: () -> Unit) {
        Box(
            Modifier.size(Tp.TouchTarget).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Tp.MutedForeground, modifier = Modifier.size(22.dp))
        }
    }

    private fun platformIcon(p: String?): ImageVector = when (p) {
        "macos" -> Icons.Default.LaptopMac
        "windows" -> Icons.Default.DesktopWindows
        else -> Icons.Default.Computer
    }

    private fun platformLabel(p: String) = when (p) {
        "macos" -> "Mac"; "windows" -> "Windows"; "linux" -> "Linux"; else -> p
    }
}
