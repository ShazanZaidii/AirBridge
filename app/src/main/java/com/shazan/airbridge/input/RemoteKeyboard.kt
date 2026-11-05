package com.shazan.airbridge.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ModKey(val bit: Int, val hid: Int) {
    Shift(Mods.SHIFT, Hid.LSHIFT),
    Ctrl(Mods.CTRL, Hid.LCTRL),
    Alt(Mods.ALT, Hid.LALT),
    Gui(Mods.GUI, Hid.LGUI),
    Fn(0, 0), // local layer only (arrows → Home/End/PgUp/PgDn, delete → forward delete): no HID usage
}

enum class ModState { Off, OneShot, Locked }

/** Where the controller's `key` messages go (the [TrackpadSender] in the app, a fake in tests). */
fun interface KeyOut {
    fun key(hid: Int, down: Boolean, mods: Int, rep: Boolean, text: String?)
}

/** Delayed callbacks (key repeat, caps settle). Main-looper [Handler] in the app. */
interface KeyTimer {
    fun post(delayMs: Long, r: Runnable)
    fun cancel(r: Runnable)
}

private object MainKeyTimer : KeyTimer {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun post(delayMs: Long, r: Runnable) { handler.postDelayed(r, delayMs) }
    override fun cancel(r: Runnable) = handler.removeCallbacks(r)
}

/**
 * Remote keyboard logic (§8.3). Every key is a HID down … up pair. Modifiers are sticky:
 * tap = applies to the next key (sent down before it and up after it), double-tap = locked
 * (held on the peer) until tapped again. Main thread only.
 *
 * Caps Lock: tapping it flips [capsOn] at once (optimistic); the peer's `key_state.caps`, fed in
 * through [onPeerCaps], wins when it differs. Reports arriving within [CAPS_SETTLE_MS] of a local
 * tap may describe the state before it (rapid double taps), so they are applied when that window
 * ends rather than immediately, which keeps the LED from flickering. A peer that never reports
 * leaves the local toggle in place.
 */
class RemoteKeyboardController(
    private val out: KeyOut,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val timer: KeyTimer = MainKeyTimer,
) {
    constructor(sender: TrackpadSender) : this(KeyOut { hid, down, mods, rep, text -> sender.key(hid, down, mods, rep, text) })

    companion object {
        const val LOCK_WINDOW_MS = 350L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_EVERY_MS = 45L
        const val CAPS_SETTLE_MS = 400L
    }

    private val states = mutableStateMapOf<ModKey, ModState>()

    /** Caps Lock as shown on the key's LED and sent in `mods`. */
    var capsOn by mutableStateOf(false)
        private set
    /** Last `key_state.caps` seen from the peer (null = never reported). */
    private var peerCaps: Boolean? = null
    private var lastCapsTap = Long.MIN_VALUE / 2
    /** Told about every local Caps toggle (keeps the router's per-peer state in step). */
    var onCapsToggled: ((Boolean) -> Unit)? = null

    private val settleCaps = Runnable { peerCaps?.let { capsOn = it } }

    private val lastModTap = HashMap<ModKey, Long>()
    private val held = LinkedHashMap<Int, List<ModKey>>() // pressed hid → one-shot mods wrapped around it
    private var repeatHid = -1
    private val repeater = object : Runnable {
        override fun run() {
            if (repeatHid !in held) return
            send(repeatHid, true, bits(), rep = true, text = KeyMap.text(repeatHid, bits()))
            timer.post(REPEAT_EVERY_MS, this)
        }
    }

    private fun send(hid: Int, down: Boolean, mods: Int, rep: Boolean = false, text: String? = null) =
        out.key(hid, down, mods, rep, text)

    fun state(m: ModKey): ModState = states[m] ?: ModState.Off
    private fun active(m: ModKey) = state(m) != ModState.Off
    val shiftActive: Boolean get() = active(ModKey.Shift)
    val fnActive: Boolean get() = active(ModKey.Fn)

    /** Letter faces (and letter text) are upper case: Shift xor Caps Lock, as on a real keyboard. */
    val upperLetters: Boolean get() = shiftActive xor capsOn

    /** What a printable key's face shows right now (letters by [upperLetters], symbols by Shift). */
    fun faceLabel(hid: Int): String? =
        KeyMap.label(hid, if (Hid.isLetter(hid)) upperLetters else shiftActive)

    /** Current `mods` bitmask. */
    fun bits(): Int {
        var b = 0
        for (m in ModKey.entries) if (active(m)) b = b or m.bit
        if (capsOn) b = b or Mods.CAPS
        return b
    }

    fun tapModifier(m: ModKey) {
        val now = clock()
        when (state(m)) {
            ModState.Off -> { states[m] = ModState.OneShot; lastModTap[m] = now }
            ModState.OneShot -> if (now - (lastModTap[m] ?: 0L) <= LOCK_WINDOW_MS && !isWrapped(m)) {
                states[m] = ModState.Locked
                if (m.hid != 0) send(m.hid, true, bits())
            } else if (!isWrapped(m)) states[m] = ModState.Off
            ModState.Locked -> {
                states[m] = ModState.Off
                if (m.hid != 0) send(m.hid, false, bits())
            }
        }
    }

    private fun isWrapped(m: ModKey) = held.values.any { m in it }

    /** Caps Lock key: optimistic local flip, then the HID tap. */
    fun capsLock() {
        capsOn = !capsOn
        lastCapsTap = clock()
        val b = bits()
        send(Hid.CAPS_LOCK, true, b)
        send(Hid.CAPS_LOCK, false, b)
        onCapsToggled?.invoke(capsOn)
        timer.cancel(settleCaps)
        timer.post(CAPS_SETTLE_MS, settleCaps)
    }

    /**
     * The active peer's latest `key_state.caps` (null = it never reported). Safe to call on every
     * recomposition: only a changed value is acted on.
     */
    fun onPeerCaps(reported: Boolean?) {
        if (reported == peerCaps) return
        peerCaps = reported
        if (reported == null) return
        if (clock() - lastCapsTap < CAPS_SETTLE_MS) return // in flight: [settleCaps] reconciles
        capsOn = reported
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
        for (m in wrap) { b = b or m.bit; send(m.hid, true, b) }
        held[hid] = wrap
        send(hid, true, bits(), text = KeyMap.text(hid, bits()))
        if (hid != Hid.ESC && hid != Hid.TAB && hid != Hid.ENTER) {
            repeatHid = hid
            timer.cancel(repeater)
            timer.post(REPEAT_DELAY_MS, repeater)
        }
        return hid
    }

    fun release(hid: Int) {
        val wrap = held.remove(hid) ?: return
        if (repeatHid == hid) { timer.cancel(repeater); repeatHid = -1 }
        send(hid, false, bits())
        var b = bits()
        for (m in wrap.asReversed()) {
            if (state(m) != ModState.OneShot) continue // re-locked meanwhile: stays held
            b = b and m.bit.inv()
            states[m] = ModState.Off
            send(m.hid, false, b)
        }
        if (state(ModKey.Fn) == ModState.OneShot) states[ModKey.Fn] = ModState.Off
    }

    /** Release every key and modifier on the peer. Call on pause, peer switch and keyboard close. */
    fun releaseAll() {
        timer.cancel(repeater)
        repeatHid = -1
        for ((hid, wrap) in held.entries.toList()) {
            send(hid, false, 0)
            for (m in wrap.asReversed()) send(m.hid, false, 0)
        }
        held.clear()
        for (m in ModKey.entries) {
            if (state(m) == ModState.Locked && m.hid != 0) send(m.hid, false, 0)
            states[m] = ModState.Off
        }
    }
}

// ------------------------------------------------------------------------------------ UI

private const val ROW_GAP = 5
private const val PAD_H = 6
private const val PAD_V = 5

/** Smallest height that still gives every key a 48 dp target (5 rows, gaps, padding). */
val RemoteKeyboardMinHeight: Dp = Tp.TouchTarget * 5 + (ROW_GAP * 4 + PAD_V * 2).dp

/** Normal key; Latched = pressed look (one-shot modifier, Caps on); Locked = pressed look + bar. */
private enum class Look { Normal, Latched, Locked }

private data class Labels(
    val ctrl: Pair<String, String>, val alt: Pair<String, String>, val gui: Pair<String, String>,
    val delete: String, val enter: String,
)

/**
 * Mac-style layout, edge to edge: number row (with esc), three letter rows, modifiers + space,
 * full-height inverted-T arrows. No header and no F-row: Caps Lock shows its state on its own LED.
 */
@Composable
fun RemoteKeyboard(
    controller: RemoteKeyboardController,
    platform: String,
    modifier: Modifier = Modifier,
) {
    val mac = platform == "macos" || platform == "ios"
    val l = remember(platform) {
        when (platform) {
            "macos", "ios" -> Labels("⌃" to "control", "⌥" to "option", "⌘" to "command", "delete", "return")
            "windows" -> Labels("" to "ctrl", "" to "alt", "" to "win", "backspace", "enter")
            else -> Labels("" to "ctrl", "" to "alt", "" to "super", "backspace", "enter")
        }
    }
    val c = controller

    Column(
        modifier = modifier.background(Tp.KeyboardBg).padding(horizontal = PAD_H.dp, vertical = PAD_V.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
    ) {
        KeyRow(Modifier.weight(1f)) {
            HidKey(c, Hid.ESC, 1f, "esc")
            CharKey(c, Hid.GRAVE)
            for (h in Hid.ONE..Hid.ZERO) CharKey(c, h)
            CharKey(c, Hid.MINUS)
            CharKey(c, Hid.EQUAL)
            HidKey(c, Hid.BACKSPACE, 1.6f, l.delete)
        }
        KeyRow(Modifier.weight(1f)) {
            HidKey(c, Hid.TAB, 1.8f, "tab")
            for (ch in "QWERTYUIOP") CharKey(c, Hid.A + (ch - 'A'))
            CharKey(c, Hid.LBRACKET)
            CharKey(c, Hid.RBRACKET)
            CharKey(c, Hid.BACKSLASH, 1.8f)
        }
        KeyRow(Modifier.weight(1f)) {
            CapsKey(c, 2f)
            for (ch in "ASDFGHJKL") CharKey(c, Hid.A + (ch - 'A'))
            CharKey(c, Hid.SEMICOLON)
            CharKey(c, Hid.QUOTE)
            HidKey(c, Hid.ENTER, 2.6f, l.enter)
        }
        // Bottom two rows share the arrow cluster so ↑ sits exactly above ↓ at full key height.
        Row(Modifier.fillMaxWidth().weight(2f), horizontalArrangement = Arrangement.spacedBy(ROW_GAP.dp)) {
            Column(Modifier.weight(12.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp)) {
                KeyRow(Modifier.weight(1f)) {
                    ModifierKey(c, ModKey.Shift, 2.6f, "shift", "⇧")
                    for (ch in "ZXCVBNM") CharKey(c, Hid.A + (ch - 'A'))
                    CharKey(c, Hid.COMMA)
                    CharKey(c, Hid.PERIOD)
                    CharKey(c, Hid.SLASH)
                }
                KeyRow(Modifier.weight(1f)) {
                    ModifierKey(c, ModKey.Fn, 1f, "fn")
                    ModifierKey(c, ModKey.Ctrl, 1.2f, l.ctrl.second, l.ctrl.first)
                    ModifierKey(c, ModKey.Alt, 1.2f, l.alt.second, l.alt.first)
                    ModifierKey(c, ModKey.Gui, 1.5f, l.gui.second, l.gui.first)
                    HidKey(c, Hid.SPACE, if (mac) 5f else 6.2f, "", description = "space")
                    ModifierKey(c, ModKey.Gui, 1.5f, l.gui.second, l.gui.first)
                    if (mac) ModifierKey(c, ModKey.Alt, 1.2f, l.alt.second, l.alt.first)
                }
            }
            Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp)) {
                KeyRow(Modifier.weight(1f)) {
                    ModifierKey(c, ModKey.Shift, 1f, "shift", "⇧")
                    ArrowKey(c, Hid.UP, Icons.Default.KeyboardArrowUp, "up")
                    Spacer(Modifier.weight(1f))
                }
                KeyRow(Modifier.weight(1f)) {
                    ArrowKey(c, Hid.LEFT, Icons.AutoMirrored.Filled.KeyboardArrowLeft, "left")
                    ArrowKey(c, Hid.DOWN, Icons.Default.KeyboardArrowDown, "down")
                    ArrowKey(c, Hid.RIGHT, Icons.AutoMirrored.Filled.KeyboardArrowRight, "right")
                }
            }
        }
    }
}

@Composable
private fun KeyRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ROW_GAP.dp), content = content)
}

/** Letter / symbol key. Letters follow Shift xor Caps; symbols swap to their shifted glyph with Shift. */
@Composable
private fun RowScope.CharKey(c: RemoteKeyboardController, hid: Int, weight: Float = 1f) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        Modifier.weight(weight).fillMaxHeight(),
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { fg ->
        val main = c.faceLabel(hid) ?: ""
        if (Hid.isLetter(hid)) {
            Text(main, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        } else {
            // The other layer's glyph as a small hint above, like a printed key cap.
            val other = KeyMap.label(hid, !c.shiftActive) ?: ""
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(other, color = fg.copy(alpha = 0.62f), fontSize = 11.sp, lineHeight = 12.sp)
                Text(main, color = fg, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RowScope.HidKey(c: RemoteKeyboardController, hid: Int, weight: Float, label: String, description: String? = null) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        Modifier.weight(weight).fillMaxHeight().then(
            if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        ),
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { fg -> WordFace(label, fg) }
}

@Composable
private fun RowScope.ArrowKey(c: RemoteKeyboardController, hid: Int, icon: ImageVector, description: String) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        Modifier.weight(1f).fillMaxHeight(),
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { fg -> Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun RowScope.ModifierKey(c: RemoteKeyboardController, m: ModKey, weight: Float, label: String, symbol: String = "") {
    val st = c.state(m)
    val look = when (st) {
        ModState.Off -> Look.Normal
        ModState.OneShot -> Look.Latched
        ModState.Locked -> Look.Locked
    }
    KeyCap(
        Modifier.weight(weight).fillMaxHeight().semantics {
            role = Role.Button
            stateDescription = when (st) {
                ModState.Off -> "off"
                ModState.OneShot -> "on for next key"
                ModState.Locked -> "locked"
            }
        },
        look = look,
        onDown = { c.tapModifier(m) },
    ) { fg -> WordFace(label, fg, symbol) }
}

@Composable
private fun RowScope.CapsKey(c: RemoteKeyboardController, weight: Float) {
    val on = c.capsOn
    KeyCap(
        Modifier.weight(weight).fillMaxHeight().semantics {
            role = Role.Button
            contentDescription = "Caps Lock"
            stateDescription = if (on) "on" else "off"
        },
        look = if (on) Look.Latched else Look.Normal,
        onDown = { c.capsLock() },
    ) { fg ->
        Box(Modifier.fillMaxSize()) {
            // The LED: a small dot, bright when on, dim when off. No halo.
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp).size(7.dp)
                    .clip(CircleShape).background(if (on) Tp.LedOn else Tp.LedOff),
            )
            Box(Modifier.align(Alignment.Center)) { WordFace("caps lock", fg) }
        }
    }
}

/**
 * A key surface: down on touch, up on release or cancellation (so keys can never stick). Finger
 * down, latched and locked all show the same inverted fill (light neutral key, dark label);
 * locked adds a short bar at the bottom. 90 ms colour transition, none with reduced motion.
 */
@Composable
private fun KeyCap(
    modifier: Modifier,
    look: Look = Look.Normal,
    onDown: () -> Unit,
    onUp: () -> Unit = {},
    content: @Composable (fg: Color) -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val noMotion = remember { reducedMotion(context) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    val filled = pressed || look != Look.Normal
    val spec = if (noMotion) snap<Color>() else tween(durationMillis = 90)
    val bg by animateColorAsState(if (filled) Tp.KeyLatched else Tp.KeyFace, spec, label = "keyBg")
    val fg by animateColorAsState(if (filled) Tp.OnKeyLatched else Tp.Foreground, spec, label = "keyFg")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
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
    ) {
        content(fg)
        if (look == Look.Locked) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(16.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(fg),
            )
        }
    }
}

/** Word label for special keys, with an optional symbol above it (⌘ command, ⇧ shift). */
@Composable
private fun WordFace(text: String, fg: Color, symbol: String = "") {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (symbol.isNotEmpty()) {
            Text(symbol, color = fg, fontSize = 15.sp, lineHeight = 16.sp)
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                color = fg,
                fontSize = if (text.length > 6) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
