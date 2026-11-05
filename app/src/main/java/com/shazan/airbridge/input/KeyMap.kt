package com.shazan.airbridge.input

import android.view.KeyEvent

/** USB HID usage IDs, keyboard page 0x07 (PROTOCOL.md §8.4). */
object Hid {
    const val A = 0x04
    const val Z = 0x1D
    const val C = 0x06
    const val H = 0x0B
    const val V = 0x19
    const val X = 0x1B
    const val ONE = 0x1E
    const val ZERO = 0x27
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUAL = 0x2E
    const val LBRACKET = 0x2F
    const val RBRACKET = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val QUOTE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val CAPS_LOCK = 0x39
    const val F1 = 0x3A
    const val F12 = 0x45
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val KP_ENTER = 0x58
    const val LCTRL = 0xE0
    const val LSHIFT = 0xE1
    const val LALT = 0xE2
    const val LGUI = 0xE3
    const val RCTRL = 0xE4
    const val RSHIFT = 0xE5
    const val RALT = 0xE6
    const val RGUI = 0xE7
    /** Keyboard "Application" / Menu key (Windows keyboards, often instead of a right Win key). */
    const val APPLICATION = 0x65

    fun isModifier(hid: Int) = hid in LCTRL..RGUI
    fun isLetter(hid: Int) = hid in A..Z
}

/** `mods` bitmask of the `key` message (§8.3). */
object Mods {
    const val SHIFT = 1
    const val CTRL = 2
    const val ALT = 4
    const val GUI = 8
    const val CAPS = 16
}

/** HID usage → Android `KeyEvent` keycode, and HID → printable character (US layout). */
object KeyMap {

    private val keyCodes: Map<Int, Int> = HashMap<Int, Int>().apply {
        for (i in 0 until 26) put(Hid.A + i, KeyEvent.KEYCODE_A + i)
        for (i in 0 until 9) put(Hid.ONE + i, KeyEvent.KEYCODE_1 + i)
        put(Hid.ZERO, KeyEvent.KEYCODE_0)
        put(Hid.ENTER, KeyEvent.KEYCODE_ENTER)
        put(Hid.ESC, KeyEvent.KEYCODE_ESCAPE)
        put(Hid.BACKSPACE, KeyEvent.KEYCODE_DEL)
        put(Hid.TAB, KeyEvent.KEYCODE_TAB)
        put(Hid.SPACE, KeyEvent.KEYCODE_SPACE)
        put(Hid.MINUS, KeyEvent.KEYCODE_MINUS)
        put(Hid.EQUAL, KeyEvent.KEYCODE_EQUALS)
        put(Hid.LBRACKET, KeyEvent.KEYCODE_LEFT_BRACKET)
        put(Hid.RBRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET)
        put(Hid.BACKSLASH, KeyEvent.KEYCODE_BACKSLASH)
        put(Hid.SEMICOLON, KeyEvent.KEYCODE_SEMICOLON)
        put(Hid.QUOTE, KeyEvent.KEYCODE_APOSTROPHE)
        put(Hid.GRAVE, KeyEvent.KEYCODE_GRAVE)
        put(Hid.COMMA, KeyEvent.KEYCODE_COMMA)
        put(Hid.PERIOD, KeyEvent.KEYCODE_PERIOD)
        put(Hid.SLASH, KeyEvent.KEYCODE_SLASH)
        put(Hid.CAPS_LOCK, KeyEvent.KEYCODE_CAPS_LOCK)
        for (i in 0 until 12) put(Hid.F1 + i, KeyEvent.KEYCODE_F1 + i)
        put(0x46, KeyEvent.KEYCODE_SYSRQ)
        put(0x47, KeyEvent.KEYCODE_SCROLL_LOCK)
        put(0x48, KeyEvent.KEYCODE_BREAK)
        put(Hid.INSERT, KeyEvent.KEYCODE_INSERT)
        put(Hid.HOME, KeyEvent.KEYCODE_MOVE_HOME)
        put(Hid.PAGE_UP, KeyEvent.KEYCODE_PAGE_UP)
        put(Hid.DELETE, KeyEvent.KEYCODE_FORWARD_DEL)
        put(Hid.END, KeyEvent.KEYCODE_MOVE_END)
        put(Hid.PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)
        put(Hid.RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(Hid.LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
        put(Hid.DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        put(Hid.UP, KeyEvent.KEYCODE_DPAD_UP)
        put(0x53, KeyEvent.KEYCODE_NUM_LOCK)
        put(0x54, KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        put(0x55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY)
        put(0x56, KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        put(0x57, KeyEvent.KEYCODE_NUMPAD_ADD)
        put(Hid.KP_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        for (i in 0 until 9) put(0x59 + i, KeyEvent.KEYCODE_NUMPAD_1 + i)
        put(0x62, KeyEvent.KEYCODE_NUMPAD_0)
        put(0x63, KeyEvent.KEYCODE_NUMPAD_DOT)
        put(Hid.LCTRL, KeyEvent.KEYCODE_CTRL_LEFT)
        put(Hid.LSHIFT, KeyEvent.KEYCODE_SHIFT_LEFT)
        put(Hid.LALT, KeyEvent.KEYCODE_ALT_LEFT)
        put(Hid.LGUI, KeyEvent.KEYCODE_META_LEFT)
        put(Hid.RCTRL, KeyEvent.KEYCODE_CTRL_RIGHT)
        put(Hid.RSHIFT, KeyEvent.KEYCODE_SHIFT_RIGHT)
        put(Hid.RALT, KeyEvent.KEYCODE_ALT_RIGHT)
        put(Hid.RGUI, KeyEvent.KEYCODE_META_RIGHT)
    }

    /** (unshifted, shifted) for non-letter printable keys. */
    private val chars: Map<Int, Pair<String, String>> = HashMap<Int, Pair<String, String>>().apply {
        val digits = "1234567890"
        val shifted = "!@#$%^&*()"
        for (i in 0 until 10) put(Hid.ONE + i, digits[i].toString() to shifted[i].toString())
        put(Hid.SPACE, " " to " ")
        put(Hid.MINUS, "-" to "_")
        put(Hid.EQUAL, "=" to "+")
        put(Hid.LBRACKET, "[" to "{")
        put(Hid.RBRACKET, "]" to "}")
        put(Hid.BACKSLASH, "\\" to "|")
        put(Hid.SEMICOLON, ";" to ":")
        put(Hid.QUOTE, "'" to "\"")
        put(Hid.GRAVE, "`" to "~")
        put(Hid.COMMA, "," to "<")
        put(Hid.PERIOD, "." to ">")
        put(Hid.SLASH, "/" to "?")
        put(0x54, "/" to "/")
        put(0x55, "*" to "*")
        put(0x56, "-" to "-")
        put(0x57, "+" to "+")
        for (i in 0 until 9) put(0x59 + i, (i + 1).toString() to (i + 1).toString())
        put(0x62, "0" to "0")
        put(0x63, "." to ".")
    }

    /** Android keycode for [hid], or `KEYCODE_UNKNOWN`. */
    fun keyCode(hid: Int): Int = keyCodes[hid] ?: KeyEvent.KEYCODE_UNKNOWN

    /** Unshifted / shifted label for a printable key (letters lowercase / uppercase). */
    fun label(hid: Int, shift: Boolean): String? = when {
        Hid.isLetter(hid) -> ('a' + (hid - Hid.A)).let { if (shift) it.uppercaseChar() else it }.toString()
        else -> chars[hid]?.let { if (shift) it.second else it.first }
    }

    /**
     * The text a key produces with [mods], or null if not printable. Ctrl/Alt/Gui combinations
     * produce no text (they are shortcuts; Option-characters depend on the OS layout).
     */
    fun text(hid: Int, mods: Int): String? {
        if (mods and (Mods.CTRL or Mods.ALT or Mods.GUI) != 0) return null
        val shift = mods and Mods.SHIFT != 0
        if (Hid.isLetter(hid)) return label(hid, shift xor (mods and Mods.CAPS != 0))
        return label(hid, shift)
    }

    /** Android `KeyEvent` meta state for a `mods` bitmask. */
    fun metaState(mods: Int): Int {
        var m = 0
        if (mods and Mods.SHIFT != 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (mods and Mods.CTRL != 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (mods and Mods.ALT != 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (mods and Mods.GUI != 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (mods and Mods.CAPS != 0) m = m or KeyEvent.META_CAPS_LOCK_ON
        return m
    }
}
