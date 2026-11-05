package com.shazan.airbridge.net

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
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
        // Builds before the readable default stored nothing or the raw model code ("V2404"); neither
        // was chosen by the user, so both follow the system device name from now on.
        val stored = prefs.getString("name", null)
        if (stored != null && DeviceNames.isUnsetName(stored, Build.MODEL)) prefs.edit().remove("name").apply()
        _name.value = customName ?: defaultName
    }

    val customName: String? get() = prefs.getString("name", null)?.takeIf { it.isNotBlank() }

    /**
     * The name used when the user never set one: the phone's own device name (Settings → About
     * phone, the one Quick Share shows, e.g. "Shazan's phone"), else "Vivo V2404"-style make + model.
     */
    val defaultName: String
        get() = DeviceNames.defaultName(systemDeviceName(), Build.MANUFACTURER, Build.MODEL)

    private fun systemDeviceName(): String? = try {
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
    } catch (_: Exception) { null }

    /** Blank resets to [defaultName]. Returns true if the effective name changed. */
    fun setName(newName: String): Boolean {
        val trimmed = newName.trim().take(64)
        if (trimmed.isBlank() || DeviceNames.isUnsetName(trimmed, Build.MODEL)) prefs.edit().remove("name").apply()
        else prefs.edit().putString("name", trimmed).apply()
        return publish(customName ?: defaultName)
    }

    /** Re-read the system device name (the user may have renamed the phone). True if our name changed. */
    fun refreshDefault(): Boolean = if (customName != null) false else publish(defaultName)

    private fun publish(effective: String): Boolean {
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

/** Pure naming rules (unit-tested in DeviceNamesTest). */
object DeviceNames {
    private const val MAX = 64

    /** The system device name if it is set, else a cleaned make + model. */
    fun defaultName(systemName: String?, manufacturer: String?, model: String?): String {
        clean(systemName)?.let { return it }
        return makeAndModel(manufacturer, model)
    }

    /** "vivo" + "V2404" → "Vivo V2404"; "Google" + "Pixel 9" → "Google Pixel 9"; no repeated make. */
    fun makeAndModel(manufacturer: String?, model: String?): String {
        val make = clean(manufacturer)?.let { m ->
            if (m == m.lowercase(Locale.ROOT)) m.replaceFirstChar { it.titlecase(Locale.ROOT) } else m
        }
        val mdl = clean(model)
        return when {
            mdl == null -> make ?: "Android phone"
            make == null || make.equals("unknown", ignoreCase = true) -> mdl
            mdl.startsWith(make, ignoreCase = true) -> mdl
            else -> "$make $mdl"
        }.take(MAX)
    }

    /** A stored name that the user never chose: blank, or exactly the raw model code. */
    fun isUnsetName(stored: String?, model: String?): Boolean {
        val s = stored?.trim().orEmpty()
        return s.isEmpty() || (model != null && s == model.trim())
    }

    private fun clean(v: String?): String? =
        v?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX)
}
