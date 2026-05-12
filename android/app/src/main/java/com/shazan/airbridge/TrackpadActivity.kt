package com.shazan.airbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class TrackpadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            var isHolding by remember { mutableStateOf(false) }
            var fourFingerTriggered by remember { mutableStateOf(false) }
            var initialFourFingerPos by remember { mutableStateOf<Offset?>(null) }
            var fabRect by remember { mutableStateOf(Rect.Zero) }

            var showKeyboard by remember { mutableStateOf(false) }

            BackHandler(enabled = showKeyboard) {
                showKeyboard = false
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                if (showKeyboard) {
                    val configuration = LocalConfiguration.current
                    val screenWidth = configuration.screenWidthDp.dp
                    val screenHeight = configuration.screenHeightDp.dp

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF2C2C2C)),
                        contentAlignment = Alignment.Center
                    ) {
                        MacKeyboardOverlay(
                            modifier = Modifier
                                .requiredWidth(screenHeight)
                                .requiredHeight(screenWidth)
                                .rotate(90f),
                            onKeyPress = { key -> AirBridgeService.broadcastCommand("MAC_INP:TYPE:$key") },
                            onSpecialKey = { code -> AirBridgeService.broadcastCommand("MAC_INP:KD:$code") }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(fabRect) {
                                detectTapGestures(
                                    onTap = {
                                        val safeZone = Rect(fabRect.left - 50f, fabRect.top - 50f, fabRect.right + 50f, fabRect.bottom + 50f)
                                        if (!safeZone.contains(it)) AirBridgeService.broadcastCommand("MAC_INP:TAP")
                                    },
                                    onDoubleTap = {
                                        val safeZone = Rect(fabRect.left - 50f, fabRect.top - 50f, fabRect.right + 50f, fabRect.bottom + 50f)
                                        if (!safeZone.contains(it)) AirBridgeService.broadcastCommand("MAC_INP:DOUBLE_TAP")
                                    },
                                    onLongPress = {
                                        val safeZone = Rect(fabRect.left - 50f, fabRect.top - 50f, fabRect.right + 50f, fabRect.bottom + 50f)
                                        if (!safeZone.contains(it)) {
                                            isHolding = true
                                            AirBridgeService.broadcastCommand("MAC_INP:CD")
                                        }
                                    },
                                    onPress = {
                                        val safeZone = Rect(fabRect.left - 50f, fabRect.top - 50f, fabRect.right + 50f, fabRect.bottom + 50f)
                                        if (safeZone.contains(it)) return@detectTapGestures

                                        try {
                                            awaitRelease()
                                        } finally {
                                            if (isHolding) {
                                                AirBridgeService.broadcastCommand("MAC_INP:CU")
                                                isHolding = false
                                            }
                                        }
                                    }
                                )
                            }
                            .pointerInput(fabRect) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val changes = event.changes

                                        if (changes.all { !it.pressed }) {
                                            fourFingerTriggered = false
                                            initialFourFingerPos = null
                                        }

                                        val safeZone = Rect(fabRect.left - 50f, fabRect.top - 50f, fabRect.right + 50f, fabRect.bottom + 50f)

                                        if (changes.isNotEmpty() && safeZone.contains(changes.first().position)) {
                                            continue
                                        }

                                        if (changes.any { it.pressed && it.positionChanged() }) {
                                            if (changes.size == 1) {
                                                val change = changes.first()
                                                val dx = change.position.x - change.previousPosition.x
                                                val dy = change.position.y - change.previousPosition.y
                                                AirBridgeService.broadcastCommand("MAC_INP:M:${dy}:${-dx}")
                                                change.consume()
                                            } else if (changes.size == 2) {
                                                val change = changes.first()
                                                val dx = change.position.x - change.previousPosition.x
                                                val dy = change.position.y - change.previousPosition.y
                                                AirBridgeService.broadcastCommand("MAC_INP:S:${dy}:${-dx}")
                                                change.consume()
                                            } else if (changes.size == 4) {
                                                val change = changes.first()
                                                if (initialFourFingerPos == null) initialFourFingerPos = change.position

                                                val dx = change.position.x - initialFourFingerPos!!.x
                                                val dy = change.position.y - initialFourFingerPos!!.y

                                                if (!fourFingerTriggered) {
                                                    if (abs(dx) > 100f || abs(dy) > 100f) {
                                                        fourFingerTriggered = true
                                                        if (abs(dx) > abs(dy)) {
                                                            if (dx > 0f) AirBridgeService.broadcastCommand("MAC_INP:MISSION_CONTROL")
                                                            else AirBridgeService.broadcastCommand("MAC_INP:APP_EXPOSE")
                                                        } else {
                                                            if (dy < 0f) AirBridgeService.broadcastCommand("MAC_INP:SPACE_LEFT")
                                                            else AirBridgeService.broadcastCommand("MAC_INP:SPACE_RIGHT")
                                                        }
                                                    }
                                                }
                                                changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                }
                            }
                    )

                    FloatingActionButton(
                        onClick = { showKeyboard = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(32.dp)
                            .onGloballyPositioned { coordinates ->
                                fabRect = coordinates.boundsInParent()
                            },
                        containerColor = Color.Black.copy(0.4f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Open Mac Keyboard",
                            tint = Color.White.copy(0.3f),
                            modifier = Modifier
                                .size(36.dp)
                                .rotate(180f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MacKeyboardOverlay(
    modifier: Modifier = Modifier,
    onKeyPress: (String) -> Unit,
    onSpecialKey: (String) -> Unit
) {
    Column(
        modifier = modifier
            .background(Color(0xFF2C2C2C))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {

        KeyboardRow {
            MacKey("esc", weight = 1.2f) { onSpecialKey("53") }
            MacKey("F1", Icons.Default.BrightnessLow, 1f) { onSpecialKey("145") }
            MacKey("F2", Icons.Default.BrightnessHigh, 1f) { onSpecialKey("144") }
            MacKey("F3", Icons.Default.GridView, 1f) { onSpecialKey("160") }
            MacKey("F4", Icons.Default.Search, 1f) { onSpecialKey("131") }
            MacKey("F5", Icons.Default.Mic, 1f) { onSpecialKey("96") }
            MacKey("F6", Icons.Default.DarkMode, 1f) { onSpecialKey("97") }
            MacKey("F7", Icons.Default.SkipPrevious, 1f) { onSpecialKey("98") }
            MacKey("F8", Icons.Default.PlayArrow, 1f) { onSpecialKey("100") }
            MacKey("F9", Icons.Default.SkipNext, 1f) { onSpecialKey("101") }
            MacKey("F10", Icons.Default.VolumeOff, 1f) { onSpecialKey("109") }
            MacKey("F11", Icons.Default.VolumeDown, 1f) { onSpecialKey("103") }
            MacKey("F12", Icons.Default.VolumeUp, 1f) { onSpecialKey("104") }
            MacKey("", Icons.Default.PowerSettingsNew, 1.2f) { onSpecialKey("127") }
        }

        KeyboardRow {
            MacKey("~\n`", weight = 1f) { onKeyPress("`") }
            listOf("!\n1", "@\n2", "#\n3", "$\n4", "%\n5", "^\n6", "&\n7", "*\n8", "(\n9", ")\n0", "_\n-", "+\n=").forEach { key ->
                MacKey(key, weight = 1f) { onKeyPress(key.split("\n").last()) }
            }
            MacKey("delete", weight = 1.5f) { onSpecialKey("51") }
        }

        KeyboardRow {
            MacKey("tab", weight = 1.5f) { onSpecialKey("48") }
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{\n[", "}\n]", "|\n\\").forEach { key ->
                MacKey(key, weight = 1f) { onKeyPress(key.split("\n").last().lowercase()) }
            }
        }

        KeyboardRow {
            MacKey("caps lock", weight = 1.8f) { onSpecialKey("57") }
            listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", ":\n;", "\"\n'").forEach { key ->
                MacKey(key, weight = 1f) { onKeyPress(key.split("\n").last().lowercase()) }
            }
            MacKey("return", weight = 1.8f) { onSpecialKey("36") }
        }

        KeyboardRow {
            MacKey("shift", weight = 2.2f) { onSpecialKey("56") }
            listOf("Z", "X", "C", "V", "B", "N", "M", "<\n,", ">\n.", "?\n/").forEach { key ->
                MacKey(key, weight = 1f) { onKeyPress(key.split("\n").last().lowercase()) }
            }
            MacKey("shift", weight = 2.2f) { onSpecialKey("56") }
        }

        KeyboardRow {
            MacKey("fn", weight = 1f) { onSpecialKey("63") }
            MacKey("control", weight = 1.2f) { onSpecialKey("59") }
            MacKey("option\n⌥", weight = 1.2f) { onSpecialKey("58") }
            MacKey("command\n⌘", weight = 1.5f) { onSpecialKey("55") }
            MacKey("", weight = 5.5f) { onKeyPress(" ") } // Spacebar
            MacKey("command\n⌘", weight = 1.5f) { onSpecialKey("55") }
            MacKey("option\n⌥", weight = 1.2f) { onSpecialKey("58") }

            Row(modifier = Modifier.weight(2.5f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Spacer(modifier = Modifier.weight(1f))
                    BaseMacKey(icon = Icons.Default.KeyboardArrowLeft, modifier = Modifier.weight(1f).fillMaxWidth()) { onSpecialKey("123") }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BaseMacKey(icon = Icons.Default.KeyboardArrowUp, modifier = Modifier.weight(1f).fillMaxWidth()) { onSpecialKey("126") }
                    BaseMacKey(icon = Icons.Default.KeyboardArrowDown, modifier = Modifier.weight(1f).fillMaxWidth()) { onSpecialKey("125") }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Spacer(modifier = Modifier.weight(1f))
                    BaseMacKey(icon = Icons.Default.KeyboardArrowRight, modifier = Modifier.weight(1f).fillMaxWidth()) { onSpecialKey("124") }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
fun RowScope.MacKey(text: String = "", icon: ImageVector? = null, weight: Float, onClick: () -> Unit) {
    BaseMacKey(
        text = text,
        icon = icon,
        modifier = Modifier.weight(weight).fillMaxHeight(),
        onClick = onClick
    )
}

@Composable
fun BaseMacKey(
    text: String = "",
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF161616))
            .clickable { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (text.isNotEmpty()) 14.dp else 18.dp)
                )
            }
            if (icon != null && text.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
            }
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = if (text.contains("\n")) 10.sp else if (text.length > 2) 12.sp else 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 12.sp
                )
            }
        }
    }
}


