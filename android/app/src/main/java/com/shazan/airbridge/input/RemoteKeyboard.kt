package com.shazan.airbridge.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ModKey(val bit: Int, val hid: Int) {
    Shift(Mods.SHIFT, Hid.LSHIFT),
    Ctrl(Mods.CTRL, Hid.LCTRL),
    Alt(Mods.ALT, Hid.LALT),
    Gui(Mods.GUI, Hid.LGUI),
    Fn(0, 0), // local layer only: no HID usage on page 0x07
}

enum class ModState { Off, OneShot, Locked }

/**
 * Remote keyboard logic (§8.3). Every key is a HID down … up pair. Modifiers are sticky:
 * tap = applies to the next key (sent down before it and up after it), double-tap = locked
 * (held on the peer) until tapped again. Main thread only.
 */
class RemoteKeyboardController(private val sender: TrackpadSender) {

    private companion object {
        const val LOCK_WINDOW_MS = 350L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_EVERY_MS = 45L
        val F_ACTIONS = listOf(
            "brightness_down", "brightness_up", "mission_control", "spotlight", "assistant", "dnd_toggle",
            "media_prev", "media_play_pause", "media_next", "vol_mute", "vol_down", "vol_up",
        )
    }

    private val states = mutableStateMapOf<ModKey, ModState>()
    var fKeysMode by mutableStateOf(false)
    private var localCaps by mutableStateOf(false)
    /** Caps state reported by the peer via key_state, or null if it never reported. */
    var reportedCaps: Boolean? by mutableStateOf(null)
    var onCapsToggled: ((Boolean) -> Unit)? = null

    val capsOn: Boolean get() = reportedCaps ?: localCaps

    private val lastModTap = HashMap<ModKey, Long>()
    private val held = LinkedHashMap<Int, List<ModKey>>() // pressed hid → one-shot mods wrapped around it
    private val main = Handler(Looper.getMainLooper())
    private var repeatHid = -1
    private val repeater = object : Runnable {
        override fun run() {
            if (repeatHid !in held) return
            sender.key(repeatHid, true, bits(), rep = true, text = KeyMap.text(repeatHid, bits()))
            main.postDelayed(this, REPEAT_EVERY_MS)
        }
    }

    fun state(m: ModKey): ModState = states[m] ?: ModState.Off
    private fun active(m: ModKey) = state(m) != ModState.Off
    val shiftActive: Boolean get() = active(ModKey.Shift)
    val fnActive: Boolean get() = active(ModKey.Fn)

    /** Current `mods` bitmask. */
    fun bits(): Int {
        var b = 0
        for (m in ModKey.entries) if (active(m)) b = b or m.bit
        if (capsOn) b = b or Mods.CAPS
        return b
    }

    fun tapModifier(m: ModKey) {
        val now = SystemClock.uptimeMillis()
        when (state(m)) {
            ModState.Off -> { states[m] = ModState.OneShot; lastModTap[m] = now }
            ModState.OneShot -> if (now - (lastModTap[m] ?: 0L) <= LOCK_WINDOW_MS && !isWrapped(m)) {
                states[m] = ModState.Locked
                if (m.hid != 0) sender.key(m.hid, true, bits())
            } else if (!isWrapped(m)) states[m] = ModState.Off
            ModState.Locked -> {
                states[m] = ModState.Off
                if (m.hid != 0) sender.key(m.hid, false, bits())
            }
        }
    }

    private fun isWrapped(m: ModKey) = held.values.any { m in it }

    fun capsLock() {
        val newCaps = !capsOn
        if (reportedCaps != null) onCapsToggled?.invoke(newCaps) else localCaps = newCaps
        val b = bits().let { if (newCaps) it or Mods.CAPS else it and Mods.CAPS.inv() }
        sender.key(Hid.CAPS_LOCK, true, b)
        sender.key(Hid.CAPS_LOCK, false, b)
    }

    /** Applies the fn layer; returns the HID actually pressed (pass it to [release]). */
    fun press(baseHid: Int): Int {
        val hid = if (fnActive) when (baseHid) {
            Hid.LEFT -> Hid.HOME
            Hid.RIGHT -> Hid.END
            Hid.UP -> Hid.PAGE_UP
            Hid.DOWN -> Hid.PAGE_DOWN
            Hid.BACKSPACE -> Hid.DELETE
            else -> baseHid
        } else baseHid
        if (hid in held) return hid
        val wrap = ModKey.entries.filter { it.hid != 0 && state(it) == ModState.OneShot }
        var b = bits()
        for (m in wrap) b = b and m.bit.inv()
        for (m in wrap) { b = b or m.bit; sender.key(m.hid, true, b) }
        held[hid] = wrap
        sender.key(hid, true, bits(), text = KeyMap.text(hid, bits()))
        if (hid != Hid.ESC && hid != Hid.TAB && hid != Hid.ENTER) {
            repeatHid = hid
            main.removeCallbacks(repeater)
            main.postDelayed(repeater, REPEAT_DELAY_MS)
        }
        return hid
    }

    fun release(hid: Int) {
        val wrap = held.remove(hid) ?: return
        if (repeatHid == hid) { main.removeCallbacks(repeater); repeatHid = -1 }
        sender.key(hid, false, bits())
        var b = bits()
        for (m in wrap.asReversed()) {
            if (state(m) != ModState.OneShot) continue // re-locked meanwhile: stays held
            b = b and m.bit.inv()
            states[m] = ModState.Off
            sender.key(m.hid, false, b)
        }
        if (state(ModKey.Fn) == ModState.OneShot) states[ModKey.Fn] = ModState.Off
    }

    /** F-row key: HID F-key when F-keys mode XOR fn, otherwise a media/system action. */
    fun pressFunctionRow(index: Int): Int? {
        val fKey = fKeysMode xor fnActive
        if (fKey) {
            if (fnActive) states[ModKey.Fn] = ModState.Off
            return press(Hid.F1 + index)
        }
        if (state(ModKey.Fn) == ModState.OneShot) states[ModKey.Fn] = ModState.Off
        sender.action(F_ACTIONS[index])
        return null
    }

    fun action(name: String) = sender.action(name)

    /** Release every key and modifier on the peer. Call on pause, peer switch and keyboard close. */
    fun releaseAll() {
        main.removeCallbacks(repeater)
        repeatHid = -1
        for ((hid, wrap) in held.entries.toList()) {
            sender.key(hid, false, 0)
            for (m in wrap.asReversed()) sender.key(m.hid, false, 0)
        }
        held.clear()
        for (m in ModKey.entries) {
            if (state(m) == ModState.Locked && m.hid != 0) sender.key(m.hid, false, 0)
            states[m] = ModState.Off
        }
    }
}

// ------------------------------------------------------------------------------------ UI

private val KeyBg = Color(0xFF161616)
private val KeyPressed = Color(0xFF3A3A3A)
private val Accent = Color(0xFF4285F4)
private val LedOn = Color(0xFF34C759)

private enum class Look { Normal, OneShot, Locked }

@Composable
fun RemoteKeyboard(
    controller: RemoteKeyboardController,
    platform: String,
    modifier: Modifier = Modifier,
) {
    val mac = platform == "macos" || platform == "ios"
    val labels = remember(platform) {
        when (platform) {
            "macos", "ios" -> listOf("control", "option\n⌥", "command\n⌘", "delete", "return")
            "windows" -> listOf("ctrl", "alt", "win", "⌫", "enter")
            else -> listOf("ctrl", "alt", "super", "⌫", "enter")
        }
    }
    val shift = controller.shiftActive
    val caps = controller.capsOn
    val fMode = controller.fKeysMode xor controller.fnActive

    Column(
        modifier = modifier.background(Color(0xFF2C2C2C)).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        KeyRow {
            HidKey(controller, Hid.ESC, 1.2f, "esc")
            val icons = listOf(
                Icons.Default.BrightnessLow, Icons.Default.BrightnessHigh, Icons.Default.GridView, Icons.Default.Search,
                Icons.Default.Mic, Icons.Default.DarkMode, Icons.Default.SkipPrevious, Icons.Default.PlayArrow,
                Icons.Default.SkipNext, Icons.AutoMirrored.Filled.VolumeOff, Icons.AutoMirrored.Filled.VolumeDown,
                Icons.AutoMirrored.Filled.VolumeUp,
            )
            for (i in 0 until 12) {
                var pressedHid by remember { mutableStateOf<Int?>(null) }
                KeyCap(
                    Modifier.weight(1f).fillMaxHeight(),
                    onDown = { pressedHid = controller.pressFunctionRow(i) },
                    onUp = { pressedHid?.let { controller.release(it) }; pressedHid = null },
                ) {
                    KeyFace(text = "F${i + 1}", icon = if (fMode) null else icons[i], small = true)
                }
            }
            KeyCap(Modifier.weight(1.2f).fillMaxHeight(), onDown = { controller.action("lock") }) {
                KeyFace(icon = Icons.Default.PowerSettingsNew)
            }
        }
        KeyRow {
            CharKey(controller, Hid.GRAVE, shift)
            for (h in Hid.ONE..Hid.ZERO) CharKey(controller, h, shift)
            CharKey(controller, Hid.MINUS, shift)
            CharKey(controller, Hid.EQUAL, shift)
            HidKey(controller, Hid.BACKSPACE, 1.6f, labels[3])
        }
        KeyRow {
            HidKey(controller, Hid.TAB, 1.5f, "tab")
            for (c in "QWERTYUIOP") CharKey(controller, Hid.A + (c - 'A'), shift, caps)
            CharKey(controller, Hid.LBRACKET, shift)
            CharKey(controller, Hid.RBRACKET, shift)
            CharKey(controller, Hid.BACKSLASH, shift)
        }
        KeyRow {
            KeyCap(Modifier.weight(1.8f).fillMaxHeight(), onDown = { controller.capsLock() }) {
                Box(Modifier.fillMaxWidth().fillMaxHeight()) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(5.dp).size(6.dp).clip(CircleShape)
                            .background(if (caps) LedOn else Color(0xFF444444)),
                    )
                    Box(Modifier.align(Alignment.Center)) { KeyFace(text = "caps lock") }
                }
            }
            for (c in "ASDFGHJKL") CharKey(controller, Hid.A + (c - 'A'), shift, caps)
            CharKey(controller, Hid.SEMICOLON, shift)
            CharKey(controller, Hid.QUOTE, shift)
            HidKey(controller, Hid.ENTER, 1.8f, labels[4])
        }
        KeyRow {
            ModifierKey(controller, ModKey.Shift, 2.2f, "shift")
            for (c in "ZXCVBNM") CharKey(controller, Hid.A + (c - 'A'), shift, caps)
            CharKey(controller, Hid.COMMA, shift)
            CharKey(controller, Hid.PERIOD, shift)
            CharKey(controller, Hid.SLASH, shift)
            ModifierKey(controller, ModKey.Shift, 2.2f, "shift")
        }
        KeyRow {
            ModifierKey(controller, ModKey.Fn, 1f, "fn")
            ModifierKey(controller, ModKey.Ctrl, 1.2f, labels[0])
            ModifierKey(controller, ModKey.Alt, 1.2f, labels[1])
            ModifierKey(controller, ModKey.Gui, 1.5f, labels[2])
            HidKey(controller, Hid.SPACE, 5f, "")
            ModifierKey(controller, ModKey.Gui, 1.5f, labels[2])
            if (mac) ModifierKey(controller, ModKey.Alt, 1.2f, labels[1])
            Row(Modifier.weight(2.6f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Spacer(Modifier.weight(1f))
                    ArrowKey(controller, Hid.LEFT, Icons.AutoMirrored.Filled.KeyboardArrowLeft, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ArrowKey(controller, Hid.UP, Icons.Default.KeyboardArrowUp, Modifier.weight(1f))
                    ArrowKey(controller, Hid.DOWN, Icons.Default.KeyboardArrowDown, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f)) {
                    Spacer(Modifier.weight(1f))
                    ArrowKey(controller, Hid.RIGHT, Icons.AutoMirrored.Filled.KeyboardArrowRight, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
}

@Composable
private fun RowScope.CharKey(c: RemoteKeyboardController, hid: Int, shift: Boolean, caps: Boolean = false) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        Modifier.weight(1f).fillMaxHeight(),
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) {
        if (Hid.isLetter(hid)) {
            KeyFace(text = KeyMap.label(hid, shift xor caps) ?: "", large = true)
        } else {
            val lo = KeyMap.label(hid, false) ?: ""
            val hi = KeyMap.label(hid, true) ?: ""
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(hi, color = if (shift) Color.White else Color.Gray, fontSize = 11.sp, lineHeight = 12.sp)
                Text(lo, color = if (shift) Color.Gray else Color.White, fontSize = 13.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun RowScope.HidKey(c: RemoteKeyboardController, hid: Int, weight: Float, label: String) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        Modifier.weight(weight).fillMaxHeight(),
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { KeyFace(text = label) }
}

@Composable
private fun ArrowKey(c: RemoteKeyboardController, hid: Int, icon: ImageVector, modifier: Modifier) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        modifier.fillMaxWidth(),
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { KeyFace(icon = icon) }
}

@Composable
private fun RowScope.ModifierKey(c: RemoteKeyboardController, m: ModKey, weight: Float, label: String) {
    val look = when (c.state(m)) {
        ModState.Off -> Look.Normal
        ModState.OneShot -> Look.OneShot
        ModState.Locked -> Look.Locked
    }
    KeyCap(Modifier.weight(weight).fillMaxHeight(), look = look, onDown = { c.tapModifier(m) }) {
        KeyFace(text = label)
    }
}

/** A key surface: down on touch, up on release or cancellation (so keys can never stick). */
@Composable
private fun KeyCap(
    modifier: Modifier,
    look: Look = Look.Normal,
    onDown: () -> Unit,
    onUp: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        look == Look.Locked -> Accent
        pressed -> KeyPressed
        else -> KeyBg
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (look == Look.OneShot) Modifier.border(2.dp, Accent, shape) else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    d.consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    try {
                        down()
                        while (true) {
                            val e = awaitPointerEvent()
                            val mine = e.changes.firstOrNull { it.id == d.id }
                            mine?.consume()
                            if (mine == null || !mine.pressed) break
                        }
                    } finally {
                        pressed = false
                        up()
                    }
                }
            },
    ) { content() }
}

@Composable
private fun KeyFace(text: String = "", icon: ImageVector? = null, small: Boolean = false, large: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = text.ifEmpty { null }, tint = Color.White,
                modifier = Modifier.size(if (text.isNotEmpty()) 13.dp else 18.dp))
        }
        if (icon != null && text.isNotEmpty()) Spacer(Modifier.height(1.dp))
        if (text.isNotEmpty()) {
            Text(
                text = text,
                color = if (icon != null) Color.Gray else Color.White,
                fontSize = when {
                    large -> 16.sp
                    small || text.contains("\n") -> 9.sp
                    text.length > 2 -> 11.sp
                    else -> 14.sp
                },
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 11.sp,
                maxLines = 2,
            )
        }
    }
}
