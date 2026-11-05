package com.shazan.airbridge.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/*
 * Why three-finger swipes "don't work" on some phones, and what this file can and can't do.
 *
 * vivo / OriginOS (like MIUI/HyperOS, One UI, ColorOS) recognise three-finger swipes in the system
 * (screenshot, split screen) through an input monitor that sees every touch in parallel with the
 * app. Once it decides the gesture is its own it "pilfers" the pointers: our window gets
 * ACTION_CANCEL and nothing after it. No app-level API can stop that: consuming events, immersive
 * mode and Window.setSystemGestureExclusionRects only affect edge back/home gestures, not
 * multi-finger system gestures. Without root the options are:
 *
 * 1. Detect it and tell the user ([MultiFingerLossDetector] → banner on the trackpad screen with a
 *    button to the settings page). Always available.
 * 2. Turn the OEM switches off while the trackpad is open and back on afterwards
 *    ([GestureSwitchGuard] + [OemGestureSwitches]). Only works where the OEM keeps the switch in
 *    a settings table this app may write: Settings.System with "Modify system settings"
 *    (Settings.System.canWrite) — but stock SettingsProvider rejects non-public System keys for
 *    apps targeting API 23+, so it depends on the OEM's provider — or Settings.Secure with
 *    WRITE_SECURE_SETTINGS, which only `adb shell pm grant` can give. Every write is guarded: a
 *    refused write is rolled back from the backup and treated as "not supported". The table ships
 *    without active entries; a key is added only after it has been verified on a real device
 *    (before/after `settings list system|secure` diff of toggling the switch by hand).
 * 3. Not implemented, possible future route: an AccessibilityService with
 *    TouchInteractionController (API 33+, touch exploration / "request touch interaction") can
 *    receive raw touches on a region before gesture detection; it requires the user to enable
 *    an accessibility service with that capability and changes how the whole screen behaves, so
 *    it is not used here.
 */

/** Namespace of a [GestureSwitch] key. */
enum class SettingsNs { SYSTEM, SECURE }

/** One OEM settings key to force to [off] while the trackpad is open. */
data class GestureSwitch(val ns: SettingsNs, val key: String, val off: String)

/**
 * Manufacturer (lower-case `Build.MANUFACTURER`) → keys to turn off. Only verified keys belong here;
 * nothing outside this table is ever touched.
 */
object OemGestureSwitches {
    val table: Map<String, List<GestureSwitch>> = mapOf(
        // vivo: pending verification. Candidates seen on a V2404 (Android 15), NOT verified, do not enable
        // without a before/after diff: smartkey_shot_switch, vivo_three_fingers_switching_enabled,
        // three_intercepts_rom_support, com.vivo.smartmultiwindow.activesplit,
        // com.vivo.smartmultiwindow.audosplit, com.vivo.smartmultiwindow.suggestsplit.
        // Example of the one-line change once verified:
        // "vivo" to listOf(GestureSwitch(SettingsNs.SYSTEM, "<key>", "0")),
    )

    fun forManufacturer(manufacturer: String?): List<GestureSwitch> =
        table[manufacturer?.trim()?.lowercase().orEmpty()].orEmpty()

    fun forThisDevice(): List<GestureSwitch> = forManufacturer(Build.MANUFACTURER)

    /**
     * Settings pages to try for the banner's button, most specific first. Each is used only if it
     * resolves to an exported activity; add a matching `<queries>` entry to the manifest when adding
     * one (package visibility). vivo: pending verification.
     */
    fun settingsPages(@Suppress("UNUSED_PARAMETER") manufacturer: String?): List<Intent> = emptyList()
}

/** Settings tables (the real [Settings] provider in the app, a map in tests). */
interface SettingsStore {
    /** Current value, or null when the key does not exist. */
    fun get(ns: SettingsNs, key: String): String?
    /** True if the write was accepted. Must not throw. */
    fun put(ns: SettingsNs, key: String, value: String): Boolean
}

/** Durable backup of original values; [save] must be persisted before it returns. */
interface BackupStore {
    fun all(): Map<String, String>
    fun save(id: String, value: String): Boolean
    fun remove(id: String)
}

/**
 * Turns [GestureSwitch]es off and restores them. The original value is persisted BEFORE a key is
 * changed, so a crash or kill in between is repaired by [restore] at the next start. Keys that do
 * not exist are never created. A key whose value the user (or the OS) changed meanwhile is left
 * as it is on restore.
 */
class GestureSwitchGuard(private val store: SettingsStore, private val backup: BackupStore) {

    /** Returns how many keys were switched off now. */
    fun engage(switches: List<GestureSwitch>): Int {
        var changed = 0
        for (s in switches) {
            val id = id(s)
            val cur = store.get(s.ns, s.key) ?: continue // absent: never touch
            if (cur == s.off) continue // already off (by the user, or still off from a crash: backup kept)
            if (!backup.save(id, encode(s.off, cur))) continue // no durable backup, no change
            if (store.put(s.ns, s.key, s.off)) changed++
            else backup.remove(id) // refused: nothing to restore
        }
        return changed
    }

    /** Puts every backed-up key back. Returns how many were written. Idempotent. */
    fun restore(): Int {
        var restored = 0
        for ((id, value) in backup.all()) {
            val nsKey = parseId(id)
            val values = decode(value)
            if (nsKey == null || values == null) { backup.remove(id); continue } // corrupt entry
            val (ns, key) = nsKey
            val (off, original) = values
            val cur = store.get(ns, key)
            if (cur == off) {
                if (!store.put(ns, key, original)) continue // e.g. permission revoked: retry next time
                restored++
            }
            backup.remove(id)
        }
        return restored
    }

    val hasPending: Boolean get() = backup.all().isNotEmpty()

    private fun id(s: GestureSwitch) = "${s.ns.name}|${s.key}"
    private fun parseId(id: String): Pair<SettingsNs, String>? {
        val i = id.indexOf('|')
        if (i <= 0) return null
        val ns = SettingsNs.entries.firstOrNull { it.name == id.substring(0, i) } ?: return null
        return ns to id.substring(i + 1)
    }

    // "<len(off)>:<off><original>": no separator can clash with either value.
    private fun encode(off: String, original: String) = "${off.length}:$off$original"
    private fun decode(v: String): Pair<String, String>? {
        val c = v.indexOf(':')
        val n = v.substring(0, c.coerceAtLeast(0)).toIntOrNull() ?: return null
        if (c + 1 + n > v.length) return null
        return v.substring(c + 1, c + 1 + n) to v.substring(c + 1 + n)
    }
}

/**
 * Sees raw touches next to a recogniser and reports when a touch with ≥ 3 fingers was taken away
 * (ACTION_CANCEL, or every pointer going silent for [silenceMs]) before any gesture was recognised:
 * the signature of the system stealing a multi-finger gesture. External resets are not counted.
 */
class MultiFingerLossDetector(
    private val silenceMs: Long = 1500,
    private val onLoss: (fingers: Int) -> Unit,
) {
    private val down = HashSet<Long>()
    private var maxFingers = 0
    private var handled = false
    private var lastEvent = 0L

    fun onTouch(id: Long, action: TouchAction, t: Long) {
        lastEvent = t
        when (action) {
            TouchAction.DOWN -> {
                if (down.isEmpty()) { maxFingers = 0; handled = false }
                down += id
                maxFingers = maxOf(maxFingers, down.size)
            }
            TouchAction.MOVE -> Unit
            TouchAction.UP -> { down -= id; if (down.isEmpty()) clear() }
            TouchAction.CANCEL -> lost()
        }
    }

    /** The recogniser emitted a multi-finger gesture in this touch: a later cancel is not a loss. */
    fun markHandled() { handled = true }

    fun tick(t: Long) {
        if (down.size >= 3 && t - lastEvent >= silenceMs) lost()
    }

    /** Pause / mode switch / peer change: forget the touch without counting it. */
    fun reset() = clear()

    private fun lost() {
        val n = maxFingers
        val h = handled
        clear()
        if (n >= 3 && !h) onLoss(n)
    }

    private fun clear() { down.clear(); maxFingers = 0; handled = false }
}

/**
 * App side of the above: the per-session loss count behind the banner, the banner prefs, the
 * settings button, and the temporary switch-off while the trackpad is open.
 */
object OsGestureGuard {
    private const val TAG = "OsGestureGuard"
    const val PREF_BANNER_OFF = "OS_GESTURE_BANNER_OFF"
    const val PREF_SWITCHES_OFF = "OS_GESTURES_OFF_WHILE_TRACKPAD"
    private const val BACKUP_FILE = "os_gesture_backup"
    /** Losses in one trackpad session before the banner appears. */
    const val SHOW_AFTER = 2

    private val _losses = MutableStateFlow(0)
    /** Multi-finger touches the system took this session. */
    val losses: StateFlow<Int> = _losses
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed

    /** A new trackpad session (activity created). */
    fun startSession() { _losses.value = 0; _dismissed.value = false }
    fun onLoss(fingers: Int) {
        Log.i(TAG, "system took a $fingers-finger touch")
        _losses.update { it + 1 }
    }
    fun dismiss() { _dismissed.value = true }
    fun dontShowAgain(prefs: SharedPreferences) {
        prefs.edit().putBoolean(PREF_BANNER_OFF, true).apply()
        _dismissed.value = true
    }
    fun shouldShowBanner(losses: Int, dismissed: Boolean, prefs: SharedPreferences): Boolean =
        losses >= SHOW_AFTER && !dismissed && !prefs.getBoolean(PREF_BANNER_OFF, false)

    /** Most specific settings page that resolves to an exported activity, else the Settings app. */
    fun settingsIntent(context: Context): Intent {
        val pm = context.packageManager
        for (i in OemGestureSwitches.settingsPages(Build.MANUFACTURER)) {
            val ri = try { pm.resolveActivity(i, 0) } catch (_: Throwable) { null }
            if (ri?.activityInfo?.exported == true) return i
        }
        return Intent(Settings.ACTION_SETTINGS)
    }

    // ---------------------------------------------------------------- temporary switch-off

    /** The user switch is offered only where the table has an entry for this manufacturer. */
    val switchAvailable: Boolean get() = OemGestureSwitches.forThisDevice().isNotEmpty()

    fun switchEnabled(prefs: SharedPreferences): Boolean =
        switchAvailable && prefs.getBoolean(PREF_SWITCHES_OFF, true)

    /** Trackpad in front: switch the OEM gestures off if the user wants that and we may. */
    fun engage(context: Context, prefs: SharedPreferences) {
        if (!switchEnabled(prefs)) return
        val usable = OemGestureSwitches.forThisDevice().filter { canWrite(context, it.ns) }
        if (usable.isEmpty()) return
        try { guard(context).engage(usable) } catch (t: Throwable) { Log.w(TAG, "engage", t) }
    }

    /** Trackpad left / app start after a crash: put back whatever we changed. Cheap when nothing is pending. */
    fun restore(context: Context) {
        try {
            val g = guard(context)
            if (g.hasPending) g.restore()
        } catch (t: Throwable) { Log.w(TAG, "restore", t) }
    }

    private fun canWrite(context: Context, ns: SettingsNs): Boolean = when (ns) {
        SettingsNs.SYSTEM -> Settings.System.canWrite(context)
        SettingsNs.SECURE -> context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun guard(context: Context): GestureSwitchGuard {
        val app = context.applicationContext
        return GestureSwitchGuard(AndroidSettingsStore(app), PrefsBackupStore(app.getSharedPreferences(BACKUP_FILE, Context.MODE_PRIVATE)))
    }

    private class AndroidSettingsStore(private val context: Context) : SettingsStore {
        override fun get(ns: SettingsNs, key: String): String? = try {
            when (ns) {
                SettingsNs.SYSTEM -> Settings.System.getString(context.contentResolver, key)
                SettingsNs.SECURE -> Settings.Secure.getString(context.contentResolver, key)
            }
        } catch (_: Throwable) { null }

        override fun put(ns: SettingsNs, key: String, value: String): Boolean = try {
            when (ns) {
                SettingsNs.SYSTEM -> Settings.System.putString(context.contentResolver, key, value)
                SettingsNs.SECURE -> Settings.Secure.putString(context.contentResolver, key, value)
            }
        } catch (t: Throwable) {
            // Stock SettingsProvider: "You cannot change private secure settings." for non-public keys.
            Log.w(TAG, "write refused: $key", t)
            false
        }
    }

    private class PrefsBackupStore(private val prefs: SharedPreferences) : BackupStore {
        override fun all(): Map<String, String> =
            prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        override fun save(id: String, value: String): Boolean = prefs.edit().putString(id, value).commit()
        override fun remove(id: String) { prefs.edit().remove(id).commit() }
    }
}
