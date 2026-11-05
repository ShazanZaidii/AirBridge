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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
 * F-row: media / system actions (§8.5) by default, F1–F12 HID keys in [fKeysMode]; holding `fn`
 * (one-shot or locked) inverts that for the next F-row key, like a Mac.
 *
 * Caps Lock: tapping it flips [capsOn] at once (optimistic) and opens a [CAPS_GUARD_MS] window.
 * Peer `key_state` reports (fed through [onPeerCaps], each with its own sequence number):
 * - inside the window, a report that agrees with the new state confirms it and closes the window;
 *   one that disagrees is ignored for now (it may describe the state before the tap);
 * - when the window ends, the latest report received since the tap wins if it still disagrees
 *   (the toggle really did not take, or the peer ended elsewhere);
 * - outside any window, every report is adopted at once (Caps Lock pressed on the computer itself).
 * A peer that never reports leaves the local toggle in place.
 */
class RemoteKeyboardController(
    private val out: KeyOut,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val timer: KeyTimer = MainKeyTimer,
    private val actionOut: (String) -> Unit = {},
) {
    constructor(sender: TrackpadSender) : this(
        out = { hid, down, mods, rep, text -> sender.key(hid, down, mods, rep, text) },
        actionOut = { sender.action(it) },
    )

    companion object {
        const val LOCK_WINDOW_MS = 350L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_EVERY_MS = 45L
        const val CAPS_GUARD_MS = 600L

        /** F1…F12 in media mode (§8.5 action names), Mac order. */
        val F_ACTIONS = listOf(
            "brightness_down", "brightness_up", "mission_control", "spotlight", "assistant", "dnd_toggle",
            "media_prev", "media_play_pause", "media_next", "vol_mute", "vol_down", "vol_up",
        )
    }

    private val states = mutableStateMapOf<ModKey, ModState>()

    /** F-row sends F1–F12 (true) or media / system actions (false). */
    var fKeysMode by mutableStateOf(false)

    /** Caps Lock as shown on the key's LED and sent in `mods`. */
    var capsOn by mutableStateOf(false)
        private set
    private var lastReportSeq: Long? = null
    /** Inside the post-tap window: disagreeing reports are held back. */
    private var capsGuarded = false
    /** Latest peer report received since the last local tap (null = none yet). */
    private var reportSinceTap: Boolean? = null

    private val endCapsGuard = Runnable {
        capsGuarded = false
        reportSinceTap?.let { if (it != capsOn) capsOn = it }
    }

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

    /** The F-row currently shows (and sends) F1–F12: [fKeysMode] xor fn. */
    val fRowIsFKeys: Boolean get() = fKeysMode xor fnActive

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
        capsGuarded = true
        reportSinceTap = null
        val b = bits()
        send(Hid.CAPS_LOCK, true, b)
        send(Hid.CAPS_LOCK, false, b)
        timer.cancel(endCapsGuard)
        timer.post(CAPS_GUARD_MS, endCapsGuard)
    }

    /**
     * The active peer's latest `key_state.caps` and the sequence number of that message (both null
     * = it never reported). Safe to call on every recomposition: a report is acted on once.
     */
    fun onPeerCaps(reported: Boolean?, seq: Long?) {
        if (seq == lastReportSeq) return
        lastReportSeq = seq
        if (reported == null) return
        if (!capsGuarded) { capsOn = reported; return }
        reportSinceTap = reported
        if (reported == capsOn) { // confirmed: stop guarding, later reports apply at once
            capsGuarded = false
            timer.cancel(endCapsGuard)
        }
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

    /**
     * F-row key [index] (0 = F1). Sends the HID F-key when [fRowIsFKeys] (returns it: pass it to
     * [release]), otherwise the media / system action (returns null). A one-shot fn is used up.
     */
    fun pressFunctionRow(index: Int): Int? {
        if (fRowIsFKeys) {
            // fn only selected the layer: the F-key must not carry it (fn has no HID usage anyway).
            if (state(ModKey.Fn) == ModState.OneShot) states[ModKey.Fn] = ModState.Off
            return press(Hid.F1 + index)
        }
        if (state(ModKey.Fn) == ModState.OneShot) states[ModKey.Fn] = ModState.Off
        actionOut(F_ACTIONS[index])
        return null
    }

    /** A §8.5 action (the lock key). */
    fun action(name: String) = actionOut(name)

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

private const val GAP = 5
private const val PAD_H = 6
private const val PAD_V = 5

/** Below this row height (Screen-mode sheet) faces drop their second line. */
private val DENSE_ROW = 40.dp
private val LocalDense = staticCompositionLocalOf { false }

/** Normal key; Latched = pressed look (one-shot modifier, Caps on); Locked = pressed look + bar. */
private enum class Look { Normal, Latched, Locked }

private data class Labels(
    val ctrl: Pair<String, String>, val alt: Pair<String, String>, val gui: Pair<String, String>,
    val delete: String, val enter: String,
)

private val F_ICONS: List<ImageVector> = listOf(
    Icons.Default.BrightnessLow, Icons.Default.BrightnessHigh, Icons.Default.GridView, Icons.Default.Search,
    Icons.Default.Mic, Icons.Default.DarkMode, Icons.Default.SkipPrevious, Icons.Default.PlayArrow,
    Icons.Default.SkipNext, Icons.AutoMirrored.Filled.VolumeOff, Icons.AutoMirrored.Filled.VolumeDown,
    Icons.AutoMirrored.Filled.VolumeUp,
)
private val F_DESCRIPTIONS = listOf(
    "brightness down", "brightness up", "Mission Control", "Spotlight", "dictation", "Do Not Disturb",
    "previous track", "play or pause", "next track", "mute", "volume down", "volume up",
)

/**
 * Full Mac layout in six rows, edge to edge within the space it is given: F-row (media / F-keys
 * switch, esc, F1–F12 or media icons, lock), number row, three letter rows, modifiers + space with
 * the inverted-T arrows at the far end. Caps Lock shows its state on its own LED; no header.
 *
 * The caller places it inside the display-cutout-safe area (the keys must never sit under a
 * punch-hole camera); the background can extend under the cutout.
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

    BoxWithConstraints(modifier.background(Tp.KeyboardBg).padding(horizontal = PAD_H.dp, vertical = PAD_V.dp)) {
    val dense = (maxHeight - (GAP * 5).dp) / 6 < DENSE_ROW
    CompositionLocalProvider(LocalDense provides dense) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
        KeyRow {
            FRowSwitch(c, 1.5f)
            HidKey(c, Hid.ESC, 1.2f, "esc")
            for (i in 0 until 12) FunctionKey(c, i)
            KeyCap(
                Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "lock screen"; role = Role.Button },
                onDown = { c.action("lock") },
            ) { fg -> Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)) }
        }
        KeyRow {
            CharKey(c, Hid.GRAVE)
            for (h in Hid.ONE..Hid.ZERO) CharKey(c, h)
            CharKey(c, Hid.MINUS)
            CharKey(c, Hid.EQUAL)
            HidKey(c, Hid.BACKSPACE, 1.6f, l.delete)
        }
        KeyRow {
            HidKey(c, Hid.TAB, 1.5f, "tab")
            for (ch in "QWERTYUIOP") CharKey(c, Hid.A + (ch - 'A'))
            CharKey(c, Hid.LBRACKET)
            CharKey(c, Hid.RBRACKET)
            CharKey(c, Hid.BACKSLASH)
        }
        KeyRow {
            CapsKey(c, 1.8f)
            for (ch in "ASDFGHJKL") CharKey(c, Hid.A + (ch - 'A'))
            CharKey(c, Hid.SEMICOLON)
            CharKey(c, Hid.QUOTE)
            HidKey(c, Hid.ENTER, 1.8f, l.enter)
        }
        KeyRow {
            ModifierKey(c, ModKey.Shift, 2.3f, "shift", "⇧")
            for (ch in "ZXCVBNM") CharKey(c, Hid.A + (ch - 'A'))
            CharKey(c, Hid.COMMA)
            CharKey(c, Hid.PERIOD)
            CharKey(c, Hid.SLASH)
            ModifierKey(c, ModKey.Shift, 2.3f, "shift", "⇧")
        }
        KeyRow {
            ModifierKey(c, ModKey.Fn, 1f, "fn")
            ModifierKey(c, ModKey.Ctrl, 1.2f, l.ctrl.second, l.ctrl.first)
            ModifierKey(c, ModKey.Alt, 1.2f, l.alt.second, l.alt.first)
            ModifierKey(c, ModKey.Gui, 1.5f, l.gui.second, l.gui.first)
            HidKey(c, Hid.SPACE, if (mac) 5f else 6.2f, "", description = "space")
            ModifierKey(c, ModKey.Gui, 1.5f, l.gui.second, l.gui.first)
            if (mac) ModifierKey(c, ModKey.Alt, 1.2f, l.alt.second, l.alt.first)
            // Inverted T in the last row, like a MacBook: ← and → half height at the bottom,
            // ↑ above ↓ in the middle column.
            Row(Modifier.weight(3f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.weight(1f))
                    ArrowKey(c, Hid.LEFT, Icons.AutoMirrored.Filled.KeyboardArrowLeft, "left", Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ArrowKey(c, Hid.UP, Icons.Default.KeyboardArrowUp, "up", Modifier.weight(1f))
                    ArrowKey(c, Hid.DOWN, Icons.Default.KeyboardArrowDown, "down", Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.weight(1f))
                    ArrowKey(c, Hid.RIGHT, Icons.AutoMirrored.Filled.KeyboardArrowRight, "right", Modifier.weight(1f))
                }
            }
        }
    }
    }
    }
}

@Composable
private fun ColumnScope.KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP.dp), content = content)
}

/**
 * The media / F-keys switch at the start of the F-row: a key-sized two-segment control, the active
 * segment in the inverted (latched) fill. Follows fn, so the F-row and the switch always agree.
 */
@Composable
private fun RowScope.FRowSwitch(c: RemoteKeyboardController, weight: Float) {
    val fKeys = c.fRowIsFKeys
    val view = LocalView.current
    val dense = LocalDense.current
    val m = Modifier.weight(weight).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Tp.KeyFace)
            .semantics {
                role = Role.Switch
                contentDescription = "Function row"
                stateDescription = if (fKeys) "F1 to F12" else "media keys"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    c.fKeysMode = !c.fKeysMode
                }
            }
            .padding(3.dp)
    // Stacked when there is room, side by side in the short Screen-mode sheet.
    if (dense) Row(m, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SwitchSegment("media", !fKeys, Modifier.weight(1f).fillMaxHeight())
        SwitchSegment("F1–12", fKeys, Modifier.weight(1f).fillMaxHeight())
    } else Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SwitchSegment("media", !fKeys, Modifier.fillMaxWidth().weight(1f))
        SwitchSegment("F1–F12", fKeys, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun SwitchSegment(text: String, selected: Boolean, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(5.dp))
            .background(if (selected) Tp.KeyLatched else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = if (LocalDense.current) 9.sp else 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
            color = if (selected) Tp.OnKeyLatched else Tp.MutedForeground,
        )
    }
}

/** F-row key: media icon with a small "F n" caption, or the F-key name alone in F-keys mode. */
@Composable
private fun RowScope.FunctionKey(c: RemoteKeyboardController, index: Int) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    val fKeys = c.fRowIsFKeys
    KeyCap(
        Modifier.weight(1f).fillMaxHeight().semantics {
            contentDescription = if (fKeys) "F${index + 1}" else F_DESCRIPTIONS[index]
        },
        onDown = { pressed = c.pressFunctionRow(index) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { fg ->
        if (fKeys) {
            Text("F${index + 1}", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        } else if (LocalDense.current) {
            Icon(F_ICONS[index], contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(F_ICONS[index], contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Text("F${index + 1}", color = fg.copy(alpha = 0.62f), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, softWrap = false)
            }
        }
    }
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
        val dense = LocalDense.current
        if (Hid.isLetter(hid)) {
            Text(main, color = fg, fontSize = if (dense) 16.sp else 19.sp, fontWeight = FontWeight.Medium)
        } else if (dense) {
            Text(main, color = fg, fontSize = 15.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        } else {
            // The other layer's glyph as a small hint above, like a printed key cap.
            val other = KeyMap.label(hid, !c.shiftActive) ?: ""
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(other, color = fg.copy(alpha = 0.62f), fontSize = 10.sp, lineHeight = 11.sp)
                Text(main, color = fg, fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
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
private fun ArrowKey(c: RemoteKeyboardController, hid: Int, icon: ImageVector, description: String, modifier: Modifier) {
    var pressed by remember { mutableStateOf<Int?>(null) }
    KeyCap(
        modifier.fillMaxWidth(),
        radius = 5.dp,
        onDown = { pressed = c.press(hid) },
        onUp = { pressed?.let { c.release(it) }; pressed = null },
    ) { fg -> Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(18.dp)) }
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
                Modifier.align(Alignment.TopStart)
                    .padding(start = 7.dp, top = if (LocalDense.current) 4.dp else 6.dp).size(7.dp)
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
    radius: Dp = 8.dp,
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
            .clip(RoundedCornerShape(radius))
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
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (LocalDense.current) 2.dp else 4.dp).width(16.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(fg),
            )
        }
    }
}

/** Word label for special keys, with an optional symbol above it (⌘ command, ⇧ shift). */
@Composable
private fun WordFace(text: String, fg: Color, symbol: String = "") {
    if (LocalDense.current) { // one line: "⇧ shift"
        Text(
            if (symbol.isNotEmpty() && text.isNotEmpty()) "$symbol $text" else symbol + text,
            color = fg, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false,
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (symbol.isNotEmpty()) {
            Text(symbol, color = fg, fontSize = 14.sp, lineHeight = 15.sp)
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                color = fg,
                fontSize = if (text.length > 6) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
