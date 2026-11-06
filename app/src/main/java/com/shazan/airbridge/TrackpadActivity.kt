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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.ui.input.pointer.motionEventSpy
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
import com.shazan.airbridge.input.FirmPress
import com.shazan.airbridge.input.InputRouter
import com.shazan.airbridge.input.MirrorRouting
import com.shazan.airbridge.input.MirrorScreen
import com.shazan.airbridge.input.MirrorSink
import com.shazan.airbridge.input.NetHooks
import com.shazan.airbridge.input.Notice
import com.shazan.airbridge.input.OsGestureGuard
import com.shazan.airbridge.input.RemoteKeyboard
import com.shazan.airbridge.input.RemoteKeyboardController
import com.shazan.airbridge.input.ScreenShortcuts
import com.shazan.airbridge.input.ShortcutRail
import com.shazan.airbridge.input.ShortcutStrip
import com.shazan.airbridge.input.Tp
import com.shazan.airbridge.input.TrackpadPrefs
import com.shazan.airbridge.input.TrackpadSender
import com.shazan.airbridge.input.reducedMotion
import com.shazan.airbridge.input.trackpadGestures
import com.shazan.airbridge.net.PairStore
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

    companion object {
        /** Peer id to drive (from the device card's "Use as trackpad"). */
        const val EXTRA_PEER = "peer"
        /** [Mode] name to open in (the device card's Trackpad / Keyboard / Screen buttons). */
        const val EXTRA_MODE = "mode"
        private val DESKTOPS = setOf("macos", "windows", "linux")
        private const val PREF_PEER = "TRACKPAD_PEER"
        private const val PREF_NATURAL = "TP_NATURAL_SCROLL"
        private const val PREF_MODE = "TP_MODE"
        private const val PREF_FIRM = "TP_FIRM_PRESS"
        private const val BUSY_SHOW_MS = 3000L
    }

    private val sender = TrackpadSender()
    private val keyboard by lazy { RemoteKeyboardController(sender) }
    private val mirror = MirrorSink()
    private var resumed by mutableStateOf(false)
    private var resumeCount by mutableStateOf(0)
    /** Peer picked by the user (or passed in [EXTRA_PEER]); input only ever goes to this one. */
    private var chosenId by mutableStateOf<String?>(null)
    /** Mode asked for by an intent that arrived while open. */
    private var modeRequest by mutableStateOf<Mode?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InputRouter.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Always landscape, whichever way the phone is held and whatever auto-rotate is set to (the sensor
        // only picks which landscape side). Touch coordinates are the window's, so swipe directions always
        // match the landscape screen. The layout still adapts if a large screen ignores the request.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setTranslucent(false)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            try { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE } catch (_: Throwable) {}
        }
        // Works over the lock screen like v1 (the manifest says so too; this covers older releases).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Edge to edge with the bars hidden: the window always reaches into the cutout band on the
        // short edges (the default for targetSdk 35+ on Android 15+, explicit for older releases) and
        // the content avoids it through WindowInsets.displayCutout. Consistent on every release.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        OsGestureGuard.restore(this) // leftovers from a crash, before anything is switched again
        OsGestureGuard.startSession()
        chosenId = prefs.getString(PREF_PEER, null)
        takePeerExtra(intent, prefs)
        intent?.getStringExtra(EXTRA_MODE)?.let { m -> Mode.entries.firstOrNull { it.name == m } }
            ?.let { prefs.edit().putString(PREF_MODE, it.name).apply() }
        FirmPress.calibrator.load(prefs)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Tp.Accent, surface = Tp.Surface, background = Tp.Background)) {
                TrackpadScreen(prefs)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takePeerExtra(intent, getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE))
        intent.getStringExtra(EXTRA_MODE)?.let { m -> Mode.entries.firstOrNull { it.name == m } }?.let { modeRequest = it }
    }

    private fun takePeerExtra(i: android.content.Intent?, prefs: SharedPreferences) {
        val id = i?.getStringExtra(EXTRA_PEER)?.takeIf { it.isNotBlank() } ?: return
        chosenId = id
        prefs.edit().putString(PREF_PEER, id).apply()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // OEM multi-finger gestures off while we are in front (only verified keys; see OsGestureGuard).
        OsGestureGuard.engage(this, getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE))
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
        InputRouter.driving = null
        keyboard.releaseAll()
        sender.releaseAll()
        mirror.stop() // §8.7: mirror_stop on pause (restarted by the effect on resume)
        NetHooks.setBubbleSuppressed("trackpad", false)
        NetHooks.setBubbleSuppressed("mirror", false)
        OsGestureGuard.restore(this)
        FirmPress.calibrator.save(getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE))
    }

    override fun onStop() {
        super.onStop()
        OsGestureGuard.restore(this) // idempotent backstops
    }

    override fun onDestroy() {
        super.onDestroy()
        OsGestureGuard.restore(this)
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
        val controlledBy by InputRouter.controlledBy.collectAsState()
        val paired by PairStore.peers.collectAsState()
        // Ready peers this phone can drive (PeerBus lists ready sessions): computers, and phones whose
        // AirBridge Universal Control service is on (they advertise ctl_target).
        val desktops = peers.filter { canDrive(it) }
        val chosenPaired = paired.firstOrNull { it.id == chosenId && (it.plat in DESKTOPS || it.plat == "android") }
        // §8.6 "No loops": never drive the peer that is controlling this phone. Input only goes to the
        // chosen peer while it is ready; without a (still paired) choice, the first ready desktop.
        val active = if (chosenPaired != null) desktops.firstOrNull { it.id == chosenPaired.id && it.id != controlledBy }
        else desktops.firstOrNull { it.id != controlledBy }
        val wanted: String? = chosenPaired?.name ?: desktops.firstOrNull()?.name
        val blocker: PeerInfo? = desktops.firstOrNull { it.id == controlledBy }
            ?.takeIf { active == null && (chosenPaired == null || it.id == chosenPaired.id) }
        var mode by remember {
            mutableStateOf(Mode.entries.firstOrNull { it.name == prefs.getString(PREF_MODE, null) } ?: Mode.Trackpad)
        }
        var natural by remember { mutableStateOf(prefs.getBoolean(PREF_NATURAL, true)) }
        var firm by remember { mutableStateOf(prefs.getBoolean(PREF_FIRM, true)) }
        var screenKeyboard by remember { mutableStateOf(false) }
        var showStats by remember { mutableStateOf(false) }
        val gestureLog by com.shazan.airbridge.input.GestureDebug.enabled.collectAsState()
        LaunchedEffect(Unit) { com.shazan.airbridge.input.GestureDebug.setEnabled(prefs.getBoolean(com.shazan.airbridge.input.GestureDebug.PREF_OVERLAY, false)) }
        var mirrorRun by remember { mutableStateOf(0) } // bump to restart the stream
        val trackpadPrefs by rememberUpdatedState(TrackpadPrefs(naturalScroll = natural, firmPress = firm))

        SideEffect {
            FirmPress.enabled = firm
            InputRouter.driving = if (resumed) active?.id else null
            sender.targetPlatform = active?.platform ?: "macos"
            if (sender.peerId != active?.id) {
                keyboard.releaseAll() // to the previous peer, before switching
                sender.peerId = active?.id
            }
            val caps = active?.let { capsMap[it.id] }
            keyboard.onPeerCaps(caps?.on, caps?.seq)
        }

        // §5: the clipboard bubble hides while these screens are in front.
        LaunchedEffect(resumed, mode) {
            NetHooks.setBubbleSuppressed("trackpad", resumed && mode != Mode.Screen)
            NetHooks.setBubbleSuppressed("mirror", resumed && mode == Mode.Screen)
        }

        val osLosses by OsGestureGuard.losses.collectAsState()
        val osBannerDismissed by OsGestureGuard.dismissed.collectAsState()
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

        LaunchedEffect(modeRequest) {
            modeRequest?.let { switchMode(it); modeRequest = null }
        }

        BackHandler(enabled = mode == Mode.Screen && screenKeyboard) {
            keyboard.releaseAll()
            screenKeyboard = false
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Tp.Background)) {
            val landscape = maxWidth > maxHeight
            val compact = maxWidth < 600.dp
            val narrow = maxWidth < 480.dp
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

            // Cutout handling: the bar and every surface stay out of the camera band; the keyboard's
            // background runs under it while its keys fill exactly the camera-free width.
            val safeSides = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
                    desktops = desktops,
                    choices = targetChoices(peers, paired, controlledBy),
                    active = active,
                    wanted = wanted,
                    controlledBy = controlledBy,
                    mode = mode,
                    compact = compact,
                    narrow = narrow,
                    natural = natural,
                    firm = firm,
                    screenKeyboard = screenKeyboard,
                    onChoose = { p ->
                        chosenId = p.id
                        prefs.edit().putString(PREF_PEER, p.id).apply()
                    },
                    onMode = ::switchMode,
                    onToggleStats = { showStats = !showStats },
                    onToggleGestureLog = {
                        val on = !gestureLog
                        com.shazan.airbridge.input.GestureDebug.setEnabled(on)
                        prefs.edit().putBoolean(com.shazan.airbridge.input.GestureDebug.PREF_OVERLAY, on).apply()
                    },
                    onToggleNatural = {
                        natural = !natural
                        prefs.edit().putBoolean(PREF_NATURAL, natural).apply()
                    },
                    onToggleFirm = {
                        firm = !firm
                        prefs.edit().putBoolean(PREF_FIRM, firm).apply()
                    },
                    onToggleScreenKeyboard = {
                        if (screenKeyboard) keyboard.releaseAll()
                        screenKeyboard = !screenKeyboard
                    },
                    onClose = { finish() },
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (mode) {
                        Mode.Trackpad -> TrackpadSurface(active, wanted, blocker, trackpadPrefs, Modifier.fillMaxSize().windowInsetsPadding(safeSides).padding(12.dp))
                        Mode.Keyboard -> Column(Modifier.fillMaxSize()) {
                            if (!landscape) TrackpadSurface(active, wanted, blocker, trackpadPrefs, Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(safeSides).padding(12.dp))
                            KeyboardPanel(
                                active,
                                if (landscape) Modifier.fillMaxWidth().weight(1f)
                                else Modifier.fillMaxWidth().height(portraitKeyboardHeight),
                                safeSides,
                            )
                        }
                        Mode.Screen -> Column(Modifier.fillMaxSize().then(if (screenKeyboard) Modifier else Modifier.windowInsetsPadding(safeSides))) {
                            val platform = active?.platform ?: "macos"
                            val left = remember(platform) { ScreenShortcuts.left(platform) }
                            val right = remember(platform) { ScreenShortcuts.right(platform) }
                            val onShortcut: (String) -> Unit = { a ->
                                if (a == com.shazan.airbridge.input.KEYBOARD_TOGGLE) {
                                    if (screenKeyboard) keyboard.releaseAll()
                                    screenKeyboard = !screenKeyboard
                                } else sender.action(a) // logged by the gesture trace
                            }
                            val activeTiles = if (screenKeyboard) setOf(com.shazan.airbridge.input.KEYBOARD_TOGGLE) else emptySet()
                            val mirrorInfo by mirror.info.collectAsState()
                            val picture: @Composable (Modifier) -> Unit = { m ->
                                MirrorScreen(
                                    sink = mirror,
                                    sender = sender,
                                    peerName = active?.name,
                                    supported = active?.let { "mirror_source" in it.caps } ?: false,
                                    showStats = showStats,
                                    platform = active?.platform ?: "",
                                    onRetry = { mirrorRun++ },
                                    modifier = m,
                                )
                            }
                            BoxWithConstraints(
                                Modifier.fillMaxWidth().weight(1f)
                                    .then(if (screenKeyboard) Modifier.windowInsetsPadding(safeSides.only(WindowInsetsSides.Horizontal)) else Modifier),
                            ) {
                                val aspect = mirrorInfo?.let { it.w.toFloat() / it.h } ?: (16f / 10f)
                                if (maxWidth > maxHeight) {
                                    // The picture is letterboxed left and right: the shortcuts fill those bands.
                                    val gap = (maxWidth - maxHeight * aspect) / 2
                                    val railW = gap.coerceIn(76.dp, 112.dp)
                                    Row(Modifier.fillMaxSize()) {
                                        ShortcutRail(left, railW, active != null, onShortcut, active = activeTiles)
                                        picture(Modifier.weight(1f).fillMaxHeight())
                                        ShortcutRail(right, railW, active != null, onShortcut, active = activeTiles)
                                    }
                                } else Column(Modifier.fillMaxSize()) {
                                    picture(Modifier.fillMaxWidth().weight(1f))
                                    ShortcutStrip(left + right, active != null, onShortcut, active = activeTiles)
                                }
                            }
                            if (screenKeyboard) KeyboardPanel(active, Modifier.fillMaxWidth().fillMaxHeight(0.5f), safeSides)
                        }
                    }
                    if (gestureLog && mode != Mode.Keyboard) GestureLogOverlay(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 16.dp))
                    // Keyboard / Screen modes: say clearly that nothing is being sent.
                    if (active == null && mode != Mode.Trackpad && !(mode == Mode.Keyboard && !landscape)) {
                        StatusBanner(
                            when {
                                blocker != null -> "Being controlled by ${blocker.name}"
                                wanted != null -> "Not connected to $wanted"
                                else -> "No computer connected"
                            },
                            Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        )
                    }
                    // The system keeps taking 3-finger touches (OEM screenshot / split screen): say so, once.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = mode != Mode.Keyboard && OsGestureGuard.shouldShowBanner(osLosses, osBannerDismissed, prefs),
                        enter = if (noMotion) EnterTransition.None else fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 2 },
                        exit = if (noMotion) ExitTransition.None else fadeOut(tween(150)),
                        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(safeSides).padding(16.dp),
                    ) {
                        OsGestureBanner(
                            onOpenSettings = {
                                OsGestureGuard.dismiss()
                                try { startActivity(OsGestureGuard.settingsIntent(this@TrackpadActivity)) } catch (_: Throwable) {
                                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
                                }
                            },
                            onDontShowAgain = { OsGestureGuard.dontShowAgain(prefs) },
                            onDismiss = { OsGestureGuard.dismiss() },
                        )
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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    private fun TrackpadSurface(active: PeerInfo?, wanted: String?, blocker: PeerInfo?, prefs: TrackpadPrefs, modifier: Modifier) {
        val view = LocalView.current
        val current by rememberUpdatedState(prefs)
        val pressure = remember { com.shazan.airbridge.input.PressureFeed() }
        Box(
            modifier.clip(RoundedCornerShape(16.dp)).background(Tp.Surface)
                .border(1.dp, Tp.SurfaceHigh, RoundedCornerShape(16.dp))
                .semantics { contentDescription = "Trackpad" }
                // Keeps edge back/home swipes off the pad where Android allows it (≤ 200 dp per edge).
                // It cannot stop OEM three-finger gestures; see OsGestureGuard.
                .systemGestureExclusion()
                .motionEventSpy(pressure::onMotionEvent) // raw pressure for firm press
                .pointerInput(Unit) {
                    trackpadGestures(
                        sender, { current },
                        haptic = { view.performHapticFeedback(it) },
                        onSystemTookGesture = OsGestureGuard::onLoss,
                        pressure = pressure,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (active == null && blocker != null) {
                Notice(
                    "Being controlled by ${blocker.name}",
                    "${blocker.name} is controlling this phone, so this phone can't control it back.",
                    actionLabel = "Stop being controlled",
                    onAction = { InputRouter.stopBeingControlled() },
                )
            } else if (active == null && wanted != null) {
                Notice("Not connected to $wanted", "Open AirBridge on $wanted and make sure both are on the same Wi-Fi. Input is sent only while it's connected.")
            } else if (active == null) {
                Notice("No computer connected", "Connect a computer from the AirBridge home screen.")
            } else {
                Text(
                    if (active.platform == "android")
                        "Tap to tap · hold, then move to drag · two fingers to scroll\n" +
                            "Three to five fingers: swipe up for recent apps, down for notifications, sideways for the previous app, " +
                            "pinch for all apps, spread for home"
                    else
                        "Tap to click · two-finger tap to right-click · hold, then move to drag · press firmly to force click\n" +
                            "Two fingers: scroll, pinch to zoom · Three to five fingers: swipe up for Mission Control, " +
                            "down for App Exposé, left / right for spaces · four or five: pinch for Launchpad, spread for the desktop",
                    color = Tp.MutedForeground.copy(alpha = 0.6f),
                    fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp).padding(24.dp),
                )
            }
        }
    }

    /** Live gesture log (GestureDebug): fingers on the pad now + the last touches. Takes no touches. */
    @Composable
    private fun GestureLogOverlay(modifier: Modifier) {
        val lines by com.shazan.airbridge.input.GestureDebug.lines.collectAsState()
        val fingers by com.shazan.airbridge.input.GestureDebug.fingers.collectAsState()
        Column(
            modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("Gesture log · fingers now: $fingers · long-press Trackpad to hide", color = Tp.Accent, fontSize = 11.sp, lineHeight = 14.sp)
            for (l in lines.asReversed()) {
                Text(l, color = Tp.Foreground.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 14.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }

    @Composable
    private fun KeyboardPanel(active: PeerInfo?, modifier: Modifier, safe: WindowInsets) {
        // Background edge to edge (under the camera band too), keys only in the safe width.
        Column(modifier.background(Tp.KeyboardBg)) {
            RemoteKeyboard(
                controller = keyboard,
                platform = active?.platform ?: "macos",
                modifier = Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(safe),
            )
        }
    }

    /** Non-blocking hint: only its own buttons take touches; the pad around it keeps working. */
    @Composable
    private fun OsGestureBanner(onOpenSettings: () -> Unit, onDontShowAgain: () -> Unit, onDismiss: () -> Unit) {
        Row(
            Modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(Tp.Radius)).background(Tp.Surface)
                .border(1.dp, Tp.Border, RoundedCornerShape(Tp.Radius))
                .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Gesture, contentDescription = null, tint = Tp.Warning, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    "Your phone took that touch for itself",
                    color = Tp.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
                )
                Text(
                    "AirBridge uses four and five fingers. If those are taken too, search Settings for “three-finger” and turn off the screenshot and split-screen gestures.",
                    color = Tp.MutedForeground, fontSize = 12.sp, lineHeight = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BannerButton("Open settings", Tp.Accent, onOpenSettings)
                    BannerButton("Don't show again", Tp.MutedForeground, onDontShowAgain)
                }
            }
            IconButton48(Icons.Default.Close, "Dismiss", onDismiss)
        }
    }

    @Composable
    private fun BannerButton(label: String, color: Color, onClick: () -> Unit) {
        Box(
            Modifier.height(Tp.TouchTarget).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun StatusBanner(text: String, modifier: Modifier) {
        Row(
            modifier.clip(RoundedCornerShape(Tp.Radius)).background(Tp.Surface)
                .border(1.dp, Tp.SurfaceHigh, RoundedCornerShape(Tp.Radius))
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Tp.Destructive))
            Spacer(Modifier.width(10.dp))
            Text(text, color = Tp.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
        modifier: Modifier,
        desktops: List<PeerInfo>,
        choices: List<TargetChoice>,
        active: PeerInfo?,
        wanted: String?,
        controlledBy: String?,
        mode: Mode,
        compact: Boolean,
        narrow: Boolean,
        natural: Boolean,
        firm: Boolean,
        screenKeyboard: Boolean,
        onChoose: (PeerInfo) -> Unit,
        onMode: (Mode) -> Unit,
        onToggleStats: () -> Unit,
        onToggleGestureLog: () -> Unit,
        onToggleNatural: () -> Unit,
        onToggleFirm: () -> Unit,
        onToggleScreenKeyboard: () -> Unit,
        onClose: () -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().background(Tp.Background).then(modifier).height(Tp.BarHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TargetChip(desktops, choices, active, wanted, compact, narrow, onChoose, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(0.01f))
            ModeSwitcher(mode, compact, onMode, onToggleStats, onToggleGestureLog)
            Spacer(Modifier.weight(0.01f))
            if (mode != Mode.Keyboard) ToggleButton(
                icon = Icons.Default.Compress, label = if (compact || mode == Mode.Screen) null else "Firm press",
                on = firm, description = "Firm press for force click", onClick = onToggleFirm,
            )
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

    /** One row of the device picker. */
    data class TargetChoice(val id: String, val name: String, val plat: String, val state: ChoiceState)
    enum class ChoiceState { READY, CONTROLLING_YOU, NEEDS_SERVICE, OFFLINE }

    private fun canDrive(p: PeerInfo) =
        p.platform in DESKTOPS || (p.platform == "android" && com.shazan.airbridge.net.Protocol.CAP_TARGET in p.caps)

    /** Every paired computer and phone, with whether it can be driven right now (ready ones first). */
    private fun targetChoices(peers: List<PeerInfo>, paired: List<com.shazan.airbridge.net.PairedPeer>, controlledBy: String?): List<TargetChoice> {
        val ready = peers.associateBy { it.id }
        return paired.filter { it.plat in DESKTOPS || it.plat == "android" }.map { p ->
            val r = ready[p.id]
            val state = when {
                r == null -> ChoiceState.OFFLINE
                p.id == controlledBy -> ChoiceState.CONTROLLING_YOU
                !canDrive(r) -> ChoiceState.NEEDS_SERVICE
                else -> ChoiceState.READY
            }
            TargetChoice(p.id, r?.name ?: p.name, p.plat, state)
        }.sortedBy { it.state.ordinal }
    }

    @Composable
    private fun TargetChip(
        desktops: List<PeerInfo>,
        choices: List<TargetChoice>,
        active: PeerInfo?,
        wanted: String?,
        compact: Boolean,
        narrow: Boolean,
        onChoose: (PeerInfo) -> Unit,
        modifier: Modifier,
    ) {
        var menu by remember { mutableStateOf(false) }
        Box(modifier) {
            Row(
                Modifier.height(Tp.TouchTarget).clip(RoundedCornerShape(Tp.Radius)).background(Tp.Surface)
                    // Always opens the picker: shows what can be controlled, even when there is one device.
                    .clickable(role = Role.DropdownList) { menu = true }
                    .semantics(mergeDescendants = true) {
                        contentDescription = (active?.let { "Controlling ${it.name}" }
                            ?: wanted?.let { "Not connected to $it" } ?: "No computer connected") + ". Tap to choose a device"
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
                // Narrow (portrait phone): the icon and its status dot only; the name is in the menu.
                if (!narrow) Spacer(Modifier.width(10.dp))
                if (!narrow) Text(
                    active?.name ?: wanted ?: "No device",
                    color = if (active != null) Tp.Foreground else Tp.MutedForeground,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 120.dp else 260.dp),
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = Tp.MutedForeground, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Tp.Surface) {
                if (choices.isEmpty()) DropdownMenuItem(
                    enabled = false,
                    text = { Text("No paired computers or phones yet. Pair one from the AirBridge home screen.", color = Tp.MutedForeground) },
                    onClick = {},
                )
                choices.forEach { c ->
                    val peer = desktops.firstOrNull { it.id == c.id }
                    val selectable = c.state == ChoiceState.READY && peer != null
                    DropdownMenuItem(
                        enabled = selectable,
                        leadingIcon = { Icon(platformIcon(c.plat), contentDescription = null, tint = if (selectable) Tp.Foreground else Tp.MutedForeground) },
                        text = {
                            Column {
                                Text(c.name, color = if (selectable) Tp.Foreground else Tp.MutedForeground,
                                    fontWeight = if (c.id == active?.id) FontWeight.SemiBold else FontWeight.Normal)
                                Text(
                                    when (c.state) {
                                        ChoiceState.READY -> platformLabel(c.plat)
                                        ChoiceState.CONTROLLING_YOU -> "Controlling this phone"
                                        ChoiceState.NEEDS_SERVICE -> "Turn on AirBridge Universal Control on it"
                                        ChoiceState.OFFLINE -> "Not connected"
                                    },
                                    color = Tp.MutedForeground, fontSize = 12.sp,
                                )
                            }
                        },
                        trailingIcon = if (c.id == active?.id) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected", tint = Tp.Accent) }
                        } else null,
                        onClick = { menu = false; peer?.let(onChoose) },
                        modifier = Modifier.height(Tp.BarHeight),
                    )
                }
            }
        }
    }

    /** Segmented control; long-press on "Screen" toggles the stream stats, on "Trackpad" the gesture log. */
    @Composable
    private fun ModeSwitcher(mode: Mode, compact: Boolean, onMode: (Mode) -> Unit, onToggleStats: () -> Unit, onToggleGestureLog: () -> Unit) {
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
                            onLongClick = when (m) { Mode.Screen -> onToggleStats; Mode.Trackpad -> onToggleGestureLog; else -> null },
                        )
                        .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = m.label }
                        .padding(horizontal = if (compact) 10.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(m.icon, contentDescription = null, tint = if (selected) Tp.Foreground else Tp.MutedForeground, modifier = Modifier.size(20.dp))
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
        "android" -> Icons.Default.PhoneAndroid
        else -> Icons.Default.Computer
    }

    private fun platformLabel(p: String) = when (p) {
        "macos" -> "Mac"; "windows" -> "Windows"; "linux" -> "Linux"; "android" -> "Android phone"; else -> p
    }
}
