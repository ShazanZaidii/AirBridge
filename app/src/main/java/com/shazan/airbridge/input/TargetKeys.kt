package com.shazan.airbridge.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.shazan.airbridge.AirBridgeTextService

/**
 * `key` messages on the target (§8.3/§8.4). Android cannot inject raw keys, so navigation keys map
 * to accessibility actions / swipes and printable keys type `text` into the focused editor —
 * through the accessibility input method on API 33+, otherwise via `ACTION_SET_TEXT`.
 *
 * Anything that touches a text field (typing, Enter, Backspace/Delete, Tab, Home/End, caret moves,
 * Cmd/Ctrl+C/X/V/A) needs screen content, so it runs through the optional [AirBridgeTextService]
 * ([textSvc]). While that is off those keys are dropped with a one-time hint; Esc (back), Cmd+H,
 * Cmd/Alt+Tab, arrows and Page Up/Down (swipes) keep working through the core service ([svc]).
 * Main thread only.
 */
class TargetKeys(private val host: TargetController) {

    /** Core service: global actions, prefs, toasts. Has no access to screen content. */
    private val svc: AccessibilityService get() = host.service
    /** "AirBridge Typing & Clipboard", or null while the user has it off. */
    private val textSvc: AccessibilityService? get() = AirBridgeTextService.instance
    private val pressed = HashSet<Int>()
    private var lastArrowSwipe = 0L
    private var warnedNoField = false
    private var hintedNoText = false

    fun releaseAll() {
        // Nothing is held on the Android side (each injected key is a complete press).
        pressed.clear()
        warnedNoField = false
    }

    // ---- v1's one-key phone shortcuts: ⌘ / Win / Menu tapped on its own ----
    // left ⌘ (or Win) = Home; right ⌘ (or Menu / right Win) = notification shade, again = quick
    // settings (the full shade), again = close. Any other key in between makes it a normal modifier.
    private var dHeld = false
    private var soloKey = -1
    private var soloAt = 0L
    private var shadeStep = 0
    private var shadeAt = 0L

    private fun soloTap(hid: Int) {
        val now = SystemClock.uptimeMillis()
        if (hid == Hid.LGUI) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            shadeStep = 0
            return
        }
        // right ⌘ / right Win / Menu / Ctrl (v1 on Windows: Ctrl = shade, Ctrl twice = quick settings)
        if (now - shadeAt > SHADE_RESET_MS) shadeStep = 0 // the user closed it some other way meanwhile
        shadeStep = (shadeStep + 1) % 3
        shadeAt = now
        when (shadeStep) {
            1 -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            2 -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            // Close it completely (from quick settings, Back would only step down to the shade).
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                host.main.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 200)
            }
        }
    }

    fun onKey(hid: Int, down: Boolean, mods: Int, rep: Boolean, text: String?) {
        val now = SystemClock.uptimeMillis()
        if (down) {
            if (hid in SOLO_KEYS) { if (!rep) { soloKey = hid; soloAt = now } }
            else if (!rep) soloKey = -1
        } else if (hid == soloKey) {
            soloKey = -1
            // Ctrl is pressed and let go all the time while writing: while typing, only ⌘ / Win / Menu
            // taps are shortcuts (they are never part of writing).
            val ctrl = hid == Hid.LCTRL || hid == Hid.RCTRL
            if (now - soloAt <= SOLO_TAP_MS && !(ctrl && typing())) soloTap(hid)
        }
        if (!down) {
            pressed.remove(hid)
            if (hid == Hid.D && dHeld) { dHeld = false; host.keyButton(false) }
            return
        }
        pressed.add(hid)
        if (hid == Hid.APPLICATION) return
        // v1 phone keys while nothing is being typed into (no focused text field):
        // delete = recent apps, hold H = long-press at the cursor, hold D = press and drag.
        if (mods and (Mods.CTRL or Mods.GUI or Mods.ALT) == 0 && !typing()) {
            when (hid) {
                Hid.BACKSPACE, Hid.DELETE -> { if (!rep) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); return }
                Hid.H -> { if (!rep) host.longPress(host.x, host.y); return }
                Hid.D -> { if (!rep && !dHeld) { dHeld = true; host.keyButton(true) }; return }
            }
        }
        if (Hid.isModifier(hid) || hid == Hid.CAPS_LOCK) return // caps state is ignored on Android

        val cmd = mods and (Mods.CTRL or Mods.GUI) != 0
        if (cmd || (hid == Hid.TAB && mods and Mods.ALT != 0)) {
            if (!rep) shortcut(hid, mods)
            return
        }
        val shift = mods and Mods.SHIFT != 0
        when (hid) {
            Hid.ESC -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Hid.ENTER, Hid.KP_ENTER -> if (textOn()) enter()
            Hid.BACKSPACE -> if (textOn()) deleteChar(forward = false, mods)
            Hid.DELETE -> if (textOn()) deleteChar(forward = true, mods)
            Hid.TAB -> if (textOn()) tab(shift)
            Hid.LEFT, Hid.RIGHT, Hid.UP, Hid.DOWN -> arrow(hid, mods) // scrolls without the text service
            Hid.HOME, Hid.END -> if (textOn()) homeEnd(hid, mods)
            Hid.PAGE_UP -> if (!imeKey(hid, mods)) host.swipe("down")
            Hid.PAGE_DOWN -> if (!imeKey(hid, mods)) host.swipe("up")
            else -> {
                val s = printable(text) ?: KeyMap.text(hid, mods)
                if (!s.isNullOrEmpty() && textOn()) type(s)
            }
        }
    }

    /**
     * The text service is running; otherwise tell the user once (per core-service connection) how
     * to turn it on and return false so the key is dropped.
     */
    private fun textOn(): Boolean {
        if (AirBridgeTextService.isRunning) { hintedNoText = false; return true }
        if (!hintedNoText) {
            hintedNoText = true
            Toast.makeText(svc, svc.getString(com.shazan.airbridge.R.string.tk_turn_on_typing, svc.getString(com.shazan.airbridge.R.string.svc_text_name)), Toast.LENGTH_LONG).show()
        }
        return false
    }

    // ---------------------------------------------------------------- shortcuts

    private fun shortcut(hid: Int, mods: Int) {
        val gui = mods and Mods.GUI != 0
        when {
            hid == Hid.TAB -> lastApp()
            hid == Hid.SPACE -> host.appDrawerSwipe() // ⌘ Space / Win Space = app drawer (v1)
            gui && hid == Hid.H -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            hid == Hid.C -> copy()
            hid == Hid.X -> cut()
            hid == Hid.V -> paste()
            hid == Hid.A -> selectAll()
            else -> imeKey(hid, mods) // e.g. Ctrl+Z: let the editor handle it if it can
        }
    }

    internal fun lastApp() {
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        host.main.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }, 250)
    }

    internal fun copy() {
        if (!textOn()) return
        if (!contextAction(android.R.id.copy, AccessibilityNodeInfo.ACTION_COPY)) return
        host.main.postDelayed({ NetHooks.pushClipboard("copy") }, 300)
    }

    private fun cut() {
        if (!textOn()) return
        if (!contextAction(android.R.id.cut, AccessibilityNodeInfo.ACTION_CUT)) return
        host.main.postDelayed({ NetHooks.pushClipboard("cut") }, 300)
    }

    internal fun paste() { if (textOn()) contextAction(android.R.id.paste, AccessibilityNodeInfo.ACTION_PASTE) }

    private fun selectAll() {
        if (!textOn()) return
        if (Build.VERSION.SDK_INT >= 33) ime()?.let { it.performContextMenuAction(android.R.id.selectAll); return }
        val n = editable() ?: return
        setSelection(n, 0, textOf(n).length)
    }

    private fun contextAction(menuId: Int, nodeAction: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33) ime()?.let { it.performContextMenuAction(menuId); return true }
        val t = textSvc ?: return false
        val n = t.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: t.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return false
        return n.performAction(nodeAction)
    }

    // ---------------------------------------------------------------- navigation / editing

    private fun enter() {
        if (imeKey(Hid.ENTER, 0)) return
        val t = textSvc ?: return
        val n = t.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n != null && n.isEditable) {
            n.refresh()
            when {
                n.isMultiLine -> insert(n, "\n")
                Build.VERSION.SDK_INT >= 30 ->
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                else -> n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return
        }
        (n ?: t.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun deleteChar(forward: Boolean, mods: Int) {
        if (imeKey(if (forward) Hid.DELETE else Hid.BACKSPACE, mods)) return
        val n = editable() ?: return
        val t = textOf(n)
        var (s, e) = selection(n, t)
        if (s == e) {
            if (forward) { if (e >= t.length) return; e = nextBoundary(t, e) }
            else { if (s <= 0) return; s = prevBoundary(t, s) }
        }
        if (setText(n, t.substring(0, s) + t.substring(e))) setSelection(n, s, s)
    }

    private fun tab(backward: Boolean) {
        val t = textSvc ?: return
        val n = t.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n != null && n.isEditable && imeKey(Hid.TAB, if (backward) Mods.SHIFT else 0)) return
        val cur = n ?: t.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val next = cur?.focusSearch(if (backward) View.FOCUS_BACKWARD else View.FOCUS_FORWARD)
            ?: if (cur == null) firstFocusable() else null
        next ?: return
        if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
    }

    private fun firstFocusable(): AccessibilityNodeInfo? {
        val root = textSvc?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 400) {
            val n = queue.removeFirst()
            if (n.isFocusable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun arrow(hid: Int, mods: Int) {
        // Without the text service we can't see a field: arrows always scroll.
        val n = textSvc?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n != null && n.isEditable) {
            if (imeKey(hid, mods)) return
            n.refresh()
            moveCaret(n, hid, mods and Mods.SHIFT != 0)
            return
        }
        // Not editing: arrows scroll like before (Up arrow = finger swipes down), honouring the
        // existing "Invert arrows" settings.
        val now = SystemClock.uptimeMillis()
        if (now - lastArrowSwipe < 300) return
        lastArrowSwipe = now
        val prefs = svc.getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        var dir = when (hid) { Hid.UP -> "down"; Hid.DOWN -> "up"; Hid.LEFT -> "right"; else -> "left" }
        if (prefs.getBoolean("INVERT_X", false)) dir = when (dir) { "left" -> "right"; "right" -> "left"; else -> dir }
        if (prefs.getBoolean("INVERT_Y", false)) dir = when (dir) { "up" -> "down"; "down" -> "up"; else -> dir }
        host.swipe(dir)
    }

    private fun homeEnd(hid: Int, mods: Int) {
        val n = editable() ?: return
        if (imeKey(hid, mods)) return
        val t = textOf(n)
        val (anchor, head) = rawSelection(n, t)
        val target = if (hid == Hid.HOME) t.lastIndexOf('\n', (head - 1).coerceAtLeast(0)).let { if (head == 0) 0 else it + 1 }
        else t.indexOf('\n', head).let { if (it < 0) t.length else it }
        if (mods and Mods.SHIFT != 0) setSelection(n, anchor, target) else setSelection(n, target, target)
    }

    private fun moveCaret(n: AccessibilityNodeInfo, hid: Int, extend: Boolean) {
        val t = textOf(n)
        val (anchor, head) = rawSelection(n, t)
        val lo = minOf(anchor, head)
        val hi = maxOf(anchor, head)
        val target = when (hid) {
            Hid.LEFT -> if (!extend && lo != hi) lo else prevBoundary(t, head)
            Hid.RIGHT -> if (!extend && lo != hi) hi else nextBoundary(t, head)
            Hid.UP -> lineMove(t, head, up = true)
            else -> lineMove(t, head, up = false)
        }
        if (extend) setSelection(n, anchor, target) else setSelection(n, target, target)
    }

    private fun lineMove(t: String, pos: Int, up: Boolean): Int {
        val lineStart = if (pos == 0) 0 else t.lastIndexOf('\n', pos - 1) + 1
        val col = pos - lineStart
        return if (up) {
            if (lineStart == 0) 0
            else {
                val prevStart = if (lineStart - 1 == 0) 0 else t.lastIndexOf('\n', lineStart - 2) + 1
                minOf(prevStart + col, lineStart - 1)
            }
        } else {
            val lineEnd = t.indexOf('\n', pos).let { if (it < 0) return t.length else it }
            val nextEnd = t.indexOf('\n', lineEnd + 1).let { if (it < 0) t.length else it }
            minOf(lineEnd + 1 + col, nextEnd)
        }
    }

    private fun type(s: String) {
        if (Build.VERSION.SDK_INT >= 33) ime()?.let { it.commitText(s, 1, null); return }
        val n = editable() ?: run { warnNoField(); return }
        insert(n, s)
    }

    private fun insert(n: AccessibilityNodeInfo, s: String) {
        val t = textOf(n)
        val (a, b) = selection(n, t)
        val newText = t.substring(0, a) + s + t.substring(b)
        if (n.maxTextLength in 1 until newText.length) return
        if (setText(n, newText)) setSelection(n, a + s.length, a + s.length)
    }

    private fun warnNoField() {
        if (warnedNoField) return
        warnedNoField = true
        Toast.makeText(svc, svc.getString(com.shazan.airbridge.R.string.tk_tap_field), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- helpers

    /** Drops control and private-use characters (e.g. macOS function-key glyphs U+F700…). */
    private fun printable(t: String?): String? {
        if (t.isNullOrEmpty()) return null
        val ok = t.all { !Character.isISOControl(it) && it !in '\uE000'..'\uF8FF' }
        return if (ok) t else null
    }

    private fun editable(): AccessibilityNodeInfo? {
        val n = textSvc?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        n.refresh() // the node cache can be stale for text/selection
        return if (n.isEditable) n else null
    }

    private fun textOf(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString() ?: ""

    /** (anchor, head) clamped into [t]; caret at end when unknown. */
    private fun rawSelection(n: AccessibilityNodeInfo, t: String): Pair<Int, Int> {
        var a = n.textSelectionStart
        var h = n.textSelectionEnd
        if (a < 0 || h < 0 || a > t.length || h > t.length) { a = t.length; h = t.length }
        return a to h
    }

    private fun selection(n: AccessibilityNodeInfo, t: String): Pair<Int, Int> {
        val (a, h) = rawSelection(n, t)
        return minOf(a, h) to maxOf(a, h)
    }

    private fun prevBoundary(t: String, i: Int): Int {
        if (i <= 0) return 0
        return if (i >= 2 && Character.isLowSurrogate(t[i - 1]) && Character.isHighSurrogate(t[i - 2])) i - 2 else i - 1
    }

    private fun nextBoundary(t: String, i: Int): Int {
        if (i >= t.length) return t.length
        return if (i + 1 < t.length && Character.isHighSurrogate(t[i]) && Character.isLowSurrogate(t[i + 1])) i + 2 else i + 1
    }

    private fun setText(n: AccessibilityNodeInfo, t: String): Boolean = n.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) },
    )

    private fun setSelection(n: AccessibilityNodeInfo, start: Int, end: Int): Boolean = n.performAction(
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        },
    )

    /**
     * Accessibility input connection to the focused editor (API 33+; the text service declares
     * flagInputMethodEditor, the core service does not).
     */
    private fun ime(): android.accessibilityservice.InputMethod.AccessibilityInputConnection? {
        if (Build.VERSION.SDK_INT < 33) return null
        val im = textSvc?.inputMethod ?: return null
        if (!im.currentInputStarted) return null
        return im.currentInputConnection
    }

    /** Send a full key press to the focused editor through the accessibility IME. */
    private fun imeKey(hid: Int, mods: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val ic = ime() ?: return false
        val code = KeyMap.keyCode(hid)
        if (code == KeyEvent.KEYCODE_UNKNOWN) return false
        val meta = KeyMap.metaState(mods and Mods.CAPS.inv())
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
        return true
    }

    /** A focused text field: keys type. Without one, the v1 phone shortcuts apply. */
    private fun typing(): Boolean = editable() != null

    private companion object {
        val SOLO_KEYS = setOf(Hid.LGUI, Hid.RGUI, Hid.APPLICATION, Hid.LCTRL, Hid.RCTRL)
        const val SOLO_TAP_MS = 600L
        const val SHADE_RESET_MS = 8_000L
    }
}
