package com.shazan.airbridge.mirror

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.shazan.airbridge.connection.ConnectionHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * "Mirror without asking" (protocol.md §8.10): a computer you trust shows this phone's screen without Android's
 * "Start recording or casting?" prompt each time, like iPhone Mirroring.
 *
 * Android skips that prompt for an app holding the PROJECT_MEDIA app-op, which only a shell (adb) can grant. A Mac set
 * up for Pro mirroring already has an adb link to this phone, so the switch asks it to run the one command
 * (`mirror_trust` → `mirror_trust_result`). Afterwards nothing needs the computer: the app-op stays until AirBridge is
 * uninstalled.
 *
 * The switch is the user's choice; the app-op is what makes it work. Switched off while the app-op is still granted,
 * AirBridge shows its own Allow / Not now question before every share instead (the system no longer asks).
 */
object MirrorTrust {
    private const val TAG = "AirBridgeMirrorTrust"
    const val CAP = "mirror_trust"
    private const val PREFS = "mirror_trust"
    private const val KEY_ON = "on"
    private const val OP = "android:project_media"

    sealed class Status {
        object Idle : Status()
        /** Waiting for [computer] to run the command. */
        data class Asking(val computer: String) : Status()
        /** The computer opened its Pro mirroring setup: finish it there. */
        data class Setup(val computer: String, val msg: String?) : Status()
        data class Failed(val msg: String?) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's switch. */
    fun wanted(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    /** Android lets AirBridge capture the screen without asking (the PROJECT_MEDIA app-op is allowed). */
    fun granted(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(OP, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(OP, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        Log.w(TAG, "app-op check", t); false
    }

    /** Shares start without any question. */
    fun active(ctx: Context): Boolean = wanted(ctx) && granted(ctx)

    /** Computers that can grant it now. */
    fun helpers(): List<com.shazan.airbridge.connection.PeerInfo> = ConnectionHub.peers().filter { CAP in it.caps }

    fun setWanted(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        if (!on) { _status.value = Status.Idle; return }
        if (granted(ctx)) { _status.value = Status.Idle; return }
        requestGrant(ctx)
    }

    /** Asks a connected computer to grant the app-op. */
    fun requestGrant(ctx: Context) {
        val p = helpers().firstOrNull()
        if (p == null) { _status.value = Status.Failed(null); return }
        _status.value = Status.Asking(p.name)
        ConnectionHub.send(p.id, JSONObject().put("t", "mirror_trust").put("op", "grant").put("pkg", ctx.packageName))
    }

    /** `mirror_trust_result` from a computer (network thread). */
    fun onResult(peerId: String, m: JSONObject) {
        if (m.optString("op") != "grant") return
        val ctx = ConnectionHub.app
        val name = ConnectionHub.peers().firstOrNull { it.id == peerId }?.name ?: ""
        val msg = m.optString("msg").takeIf { it.isNotBlank() }
        _status.value = when {
            m.optBoolean("ok") && granted(ctx) -> Status.Idle
            m.optBoolean("ok") -> Status.Failed(msg)  // the computer thought it worked, Android disagrees
            m.optString("why") == "setup" -> Status.Setup(name, msg)
            else -> Status.Failed(msg)
        }
        Log.i(TAG, "grant from $name: ok=${m.optBoolean("ok")} granted=${granted(ctx)} why=${m.optString("why")}")
    }
}
