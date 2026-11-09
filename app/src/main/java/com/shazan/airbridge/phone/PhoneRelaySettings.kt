package com.shazan.airbridge.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's choices for phone notifications and calls on the computer. Everything is off until the user
 * turns it on in Settings; nothing leaves the phone except to its own paired, connected computers.
 */
object PhoneRelaySettings {
    private const val PREFS = "AirBridgePrefs"
    const val PREF_NOTIFS = "PHONE_NOTIFS_ON"
    const val PREF_CALLS = "PHONE_CALLS_ON"
    const val PREF_MUTED = "PHONE_NOTIFS_MUTED_APPS"
    const val PREF_SEEN = "PHONE_NOTIFS_SEEN_APPS"

    /**
     * Runtime permissions for calls. Contacts are optional (the caller's name and photo); Call logs is optional (the
     * ringing call's number, which some phones' call screens never show in a notification); Nearby devices is
     * optional (the proximity beacon that decides where calls ring, §13.4).
     */
    val CALL_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
    ) + (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray())

    private var prefs: SharedPreferences? = null

    private val _notifs = MutableStateFlow(false)
    val notifsFlow: StateFlow<Boolean> = _notifs
    private val _calls = MutableStateFlow(false)
    val callsFlow: StateFlow<Boolean> = _calls
    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    val mutedFlow: StateFlow<Set<String>> = _muted

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _notifs.value = p.getBoolean(PREF_NOTIFS, false)
        _calls.value = p.getBoolean(PREF_CALLS, false)
        _muted.value = p.getStringSet(PREF_MUTED, emptySet())?.toSet() ?: emptySet()
    }

    var notifsEnabled: Boolean
        get() = _notifs.value
        set(v) { _notifs.value = v; prefs?.edit()?.putBoolean(PREF_NOTIFS, v)?.apply() }

    var callsEnabled: Boolean
        get() = _calls.value
        set(v) { _calls.value = v; prefs?.edit()?.putBoolean(PREF_CALLS, v)?.apply() }


    fun isMuted(pkg: String): Boolean = pkg in _muted.value

    fun setMuted(pkg: String, muted: Boolean) {
        val next = if (muted) _muted.value + pkg else _muted.value - pkg
        _muted.value = next
        prefs?.edit()?.putStringSet(PREF_MUTED, next)?.apply()
    }

    /** Apps that posted a notification since forwarding was turned on (listed first in the app list). */
    fun seenApps(): Set<String> = prefs?.getStringSet(PREF_SEEN, emptySet())?.toSet() ?: emptySet()

    fun markSeen(pkg: String) {
        val cur = seenApps()
        if (pkg in cur || cur.size >= 300) return
        prefs?.edit()?.putStringSet(PREF_SEEN, cur + pkg)?.apply()
    }

    /** The system "Notification access" switch for our listener. */
    fun hasNotificationAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun listenerComponent(ctx: Context): ComponentName = ComponentName(ctx, PhoneNotifListener::class.java)

    /** The page with our listener's switch (its own page on Android 11+, else the list of all listeners). */
    fun accessIntent(ctx: Context): android.content.Intent =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent(ctx).flattenToString())
        } else android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun canWatchCalls(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_PHONE_STATE)
    fun canAnswer(ctx: Context): Boolean = granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)
    fun canReadContacts(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_CONTACTS)
    /** Android hands over the ringing call's number only with Call logs. */
    fun canSeeNumber(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_CALL_LOG)
}
