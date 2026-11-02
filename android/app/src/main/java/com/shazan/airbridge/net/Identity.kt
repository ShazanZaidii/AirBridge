package com.shazan.airbridge.net

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/** This device's protocol identity (§2.1). Stored outside backups so a restore never clones the id. */
object Identity {
    private const val PREFS = "airbridge_identity"
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context

    lateinit var id: String
        private set

    private val _name = MutableStateFlow("")
    val nameFlow: StateFlow<String> = _name
    val name: String get() = _name.value

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        id = prefs.getString("id", null) ?: UUID.randomUUID().toString().lowercase(Locale.ROOT).also {
            prefs.edit().putString("id", it).apply()
        }
        _name.value = customName ?: Build.MODEL
    }

    val customName: String? get() = prefs.getString("name", null)?.takeIf { it.isNotBlank() }

    /** Blank resets to Build.MODEL. Returns true if the effective name changed. */
    fun setName(newName: String): Boolean {
        val trimmed = newName.trim().take(64)
        prefs.edit().putString("name", trimmed).apply()
        val effective = trimmed.ifBlank { Build.MODEL }
        if (effective == _name.value) return false
        _name.value = effective
        return true
    }

    /**
     * Pixel size of the built-in display in its natural orientation (portrait for phones),
     * so the value doesn't change with rotation.
     */
    fun screenSize(): Pair<Int, Int> = try {
        val dm = app.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mode = display.mode
            w = mode.physicalWidth; h = mode.physicalHeight
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") display.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        // Display.Mode is already natural; getRealMetrics follows the current rotation.
        val rotated = Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270)
        if (rotated) h to w else w to h
    } catch (_: Exception) { 0 to 0 }
}
