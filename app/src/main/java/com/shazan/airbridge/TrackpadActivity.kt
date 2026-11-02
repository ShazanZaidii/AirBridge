package com.shazan.airbridge

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
import com.shazan.airbridge.input.RemoteKeyboard
import com.shazan.airbridge.input.RemoteKeyboardController
import com.shazan.airbridge.input.TrackpadPrefs
import com.shazan.airbridge.input.TrackpadSender
import com.shazan.airbridge.input.trackpadGestures
import com.shazan.airbridge.net.PeerInfo

/** Phone as trackpad + keyboard for a desktop peer (§8.1 direct mode). */
class TrackpadActivity : ComponentActivity() {

    private companion object {
        val DESKTOPS = setOf("macos", "windows", "linux")
        const val PREF_PEER = "TRACKPAD_PEER"
        const val PREF_NATURAL = "TP_NATURAL_SCROLL"
    }

    private val sender = TrackpadSender()
    private val keyboard by lazy { RemoteKeyboardController(sender) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InputRouter.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The trackpad is used held sideways. Orientation requests may be ignored (Android 16 large
        // screens, multi-window); the layout adapts to the real window size either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setTranslucent(false)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            try { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE } catch (_: Throwable) {}
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        setContent { TrackpadScreen(prefs) }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        InputRouter.refreshPeers()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        keyboard.releaseAll()
        sender.releaseAll()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Composable
    private fun TrackpadScreen(prefs: android.content.SharedPreferences) {
        val view = LocalView.current
        val peers by InputRouter.peers.collectAsState()
        val capsMap by InputRouter.capsByPeer.collectAsState()
        val desktops = peers.filter { it.platform in DESKTOPS }
        var chosenId by remember { mutableStateOf(prefs.getString(PREF_PEER, null)) }
        val active = desktops.firstOrNull { it.id == chosenId } ?: desktops.firstOrNull()
        var showKeyboard by rememberSaveable { mutableStateOf(false) }
        var natural by remember { mutableStateOf(prefs.getBoolean(PREF_NATURAL, true)) }
        val trackpadPrefs by rememberUpdatedState(TrackpadPrefs(naturalScroll = natural))

        SideEffect {
            if (sender.peerId != active?.id) {
                keyboard.releaseAll() // to the previous peer, before switching
                sender.peerId = active?.id
            }
            keyboard.reportedCaps = active?.let { capsMap[it.id] }
            keyboard.onCapsToggled = { on -> active?.let { InputRouter.setCapsOptimistic(it.id, on) } }
        }

        LaunchedEffect(Unit) {
            while (true) { withFrameMillis { }; sender.flush() }
        }

        BackHandler(enabled = showKeyboard) {
            keyboard.releaseAll()
            showKeyboard = false
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val landscape = maxWidth > maxHeight
            val portraitKeyboardHeight = min(maxWidth * 0.62f, maxHeight * 0.55f)
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                TopBar(
                    desktops = desktops,
                    active = active,
                    keyboardShown = showKeyboard,
                    capsOn = keyboard.capsOn,
                    fKeys = keyboard.fKeysMode,
                    natural = natural,
                    onChoose = { p ->
                        chosenId = p.id
                        prefs.edit().putString(PREF_PEER, p.id).apply()
                    },
                    onToggleFKeys = { keyboard.fKeysMode = !keyboard.fKeysMode },
                    onToggleNatural = {
                        natural = !natural
                        prefs.edit().putBoolean(PREF_NATURAL, natural).apply()
                    },
                    onToggleKeyboard = {
                        if (showKeyboard) keyboard.releaseAll()
                        showKeyboard = !showKeyboard
                    },
                    onClose = { finish() },
                )
                if (!showKeyboard || !landscape) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .pointerInput(Unit) {
                                trackpadGestures(sender, { trackpadPrefs }) { view.performHapticFeedback(it) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (active == null) "No computer connected"
                            else "Tap to click · two-finger tap = right click · hold to drag\nTwo fingers scroll · three-finger swipe for Mission Control / desktops",
                            color = Color.White.copy(alpha = if (active == null) 0.35f else 0.14f),
                            fontSize = if (active == null) 16.sp else 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                if (showKeyboard) {
                    RemoteKeyboard(
                        controller = keyboard,
                        platform = active?.platform ?: "macos",
                        modifier = if (landscape) Modifier.fillMaxWidth().weight(1f)
                        else Modifier.fillMaxWidth().height(portraitKeyboardHeight),
                    )
                }
            }
            if (!showKeyboard) {
                FloatingActionButton(
                    onClick = { showKeyboard = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
                    containerColor = Color.White.copy(alpha = 0.08f),
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Show keyboard", tint = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }

    @Composable
    private fun TopBar(
        desktops: List<PeerInfo>,
        active: PeerInfo?,
        keyboardShown: Boolean,
        capsOn: Boolean,
        fKeys: Boolean,
        natural: Boolean,
        onChoose: (PeerInfo) -> Unit,
        onToggleFKeys: () -> Unit,
        onToggleNatural: () -> Unit,
        onToggleKeyboard: () -> Unit,
        onClose: () -> Unit,
    ) {
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Color(0xFF0E0E0E)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f))
                        .clickable(enabled = desktops.size > 1) { menu = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (active != null) Color(0xFF34C759) else Color(0xFFFF453A)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        active?.let { "${it.name} · ${platformLabel(it.platform)}" } ?: "Not connected",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    if (desktops.size > 1) Icon(Icons.Default.ArrowDropDown, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    desktops.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name} (${platformLabel(p.platform)})", fontWeight = if (p.id == active?.id) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { menu = false; onChoose(p) },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (keyboardShown) {
                Chip(if (capsOn) "⇪ CAPS" else "⇪ caps", on = capsOn, onColor = Color(0xFF34C759), onClick = null)
                Chip("F1–F12", on = fKeys, onClick = onToggleFKeys)
            } else {
                Chip("Natural scroll", on = natural, onClick = onToggleNatural)
            }
            Icon(
                if (keyboardShown) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                contentDescription = if (keyboardShown) "Hide keyboard" else "Show keyboard",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onToggleKeyboard).padding(5.dp),
            )
            Icon(
                Icons.Default.Close, contentDescription = "Close trackpad", tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose).padding(6.dp),
            )
        }
    }

    @Composable
    private fun Chip(text: String, on: Boolean, onColor: Color = Color(0xFF4285F4), onClick: (() -> Unit)?) {
        Text(
            text,
            color = if (on) Color.White else Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (on) onColor.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }

    private fun platformLabel(p: String) = when (p) {
        "macos" -> "Mac"; "windows" -> "Windows"; "linux" -> "Linux"; else -> p
    }
}
