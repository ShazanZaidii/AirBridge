package com.shazan.airbridge.phone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.shazan.airbridge.R
import com.shazan.airbridge.net.Hub
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * §13.4: tells computers that carry call audio as this phone's Bluetooth hands-free unit (`call_audio` cap,
 * the Mac) what they need to route it well: whether Bluetooth is on, this phone's Bluetooth name (to find it
 * among the computer's paired devices) and which other Bluetooth headset the phone uses for calls, if any (the
 * computer then leaves the call audio on the phone and says so).
 *
 * Nothing here touches HFP itself: Android is the Audio Gateway and the computer connects to it.
 */
object CallAudioInfo {
    /** `hello.caps`: we send `call_audio_state` and take `call_audio_hello`. */
    const val CAP = "call_audio_info"
    /** A computer that carries call audio over Bluetooth hands-free. */
    const val CAP_SINK = "call_audio"

    private const val TAG = "CallAudioInfo"
    private val worker: Handler by lazy { Handler(HandlerThread("call-audio-info").apply { start() }.looper) }
    /** The computer's own Bluetooth name/address per peer (from `call_audio_hello`), to leave it out. */
    private val computers = ConcurrentHashMap<String, Computer>()
    private val lastSent = ConcurrentHashMap<String, String>()
    @Volatile private var started = false

    data class Computer(val name: String?, val address: String?)

    /** One audio output the phone could use for calls. */
    data class Output(val type: Int, val name: String?, val address: String?)

    fun onPeerReady(peerId: String) = worker.post {
        ensureStarted()
        send(peerId, force = true)
    }

    fun onPeerGone(peerId: String) {
        computers.remove(peerId)
        lastSent.remove(peerId)
    }

    fun onMessage(peerId: String, m: JSONObject) {
        if (m.optString("t") != "call_audio_hello") return
        val c = Computer(m.optString("bt_name").takeIf { it.isNotBlank() }?.take(100),
            m.optString("bt_addr").takeIf { it.isNotBlank() }?.take(32))
        computers[peerId] = c
        worker.post { send(peerId, force = true) }
    }

    private fun sinks() = Hub.peers().filter { CAP_SINK in it.caps }

    private val flush = Runnable { sinks().forEach { send(it.id, force = false) } }

    /** Bluetooth or audio devices changed: tell the computers (once things settle). */
    private fun changed() {
        worker.removeCallbacks(flush)
        worker.postDelayed(flush, 400)
    }

    private fun send(peerId: String, force: Boolean) {
        val p = Hub.peers().firstOrNull { it.id == peerId } ?: return
        if (CAP_SINK !in p.caps) return
        val m = try { state(computers[peerId]) } catch (t: Throwable) { Log.w(TAG, "state", t); return }
        val s = m.toString()
        if (!force && lastSent[peerId] == s) return
        if (Hub.send(peerId, m)) lastSent[peerId] = s
    }

    private fun canConnect(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun state(computer: Computer?): JSONObject {
        val ctx = Hub.app
        val m = JSONObject().put("t", "call_audio_state")
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val on = try { adapter?.isEnabled ?: false } catch (_: SecurityException) { null }
        if (on != null) m.put("bt", on)
        if (adapter != null && canConnect(ctx)) {
            try { adapter.name?.takeIf { it.isNotBlank() }?.let { m.put("bt_name", it) } } catch (_: SecurityException) {}
        }
        if (on != false) headset(ctx, computer)?.let { m.put("headset", it) }
        return m
    }

    /** Android's hands-free (HFP) connections, kept for as long as the app runs. */
    @Volatile private var headsetProxy: BluetoothHeadset? = null

    /**
     * Bluetooth devices connected to this phone as hands-free units, or null when Android won't say. Stable for
     * the whole connection, unlike the audio device list (a Bluetooth headset is listed there only while it has
     * the call audio, which made "is there a headset?" flip back and forth), and with real names and addresses
     * (some phones label every Bluetooth audio device with the phone's own model name).
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun hfpDevices(ctx: Context): List<android.bluetooth.BluetoothDevice>? {
        val proxy = headsetProxy ?: return null
        if (!canConnect(ctx)) return null
        return try { proxy.connectedDevices } catch (_: Throwable) { null }
    }

    /** A Bluetooth call device is one of the computers: by address, by name, or because it is the only HFP connection and that is a computer. */
    @android.annotation.SuppressLint("MissingPermission")
    fun isComputerAudio(name: String?, address: String?, type: Int): Boolean {
        if (isComputer(name, address)) return true
        if (type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return false
        val hfp = hfpDevices(Hub.app) ?: return false
        return hfp.isNotEmpty() && hfp.all { d -> isComputer(try { d.name } catch (_: Throwable) { null }, d.address) }
    }

    /** Name of another Bluetooth headset the phone would use for calls (null if none). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun headset(ctx: Context, computer: Computer?): String? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            Output(it.type, it.productName?.toString(), it.address)
        }
        hfpDevices(ctx)?.let { hfp ->
            // Hands-free headsets from Android's own list; LE Audio and hearing aids still from the audio devices.
            val other = hfp.firstOrNull { d -> !isComputer(try { d.name } catch (_: Throwable) { null }, d.address) }
            if (other != null) {
                return (try { other.name } catch (_: Throwable) { null })?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.call_audio_a_headset)
            }
            val le = outputs.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.type in CALL_TYPES && !isComputer(it.name, it.address) }
            return le?.let { it.name?.takeIf { n -> n.isNotBlank() && n != Build.MODEL } ?: ctx.getString(R.string.call_audio_a_headset) }
        }
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.let { Output(it.type, it.productName?.toString(), it.address) }
        } else null
        val pick = pickHeadset(outputs, current, computer) ?: return null
        return pick.name?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.call_audio_a_headset)
    }

    /** Bluetooth outputs that carry call audio (HFP, LE Audio, hearing aids). */
    private val CALL_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        23, // TYPE_HEARING_AID (API 28)
        26, // TYPE_BLE_HEADSET (API 31)
    )

    /**
     * The headset that takes this phone's call audio, leaving out the computer itself (while it is connected
     * as a hands-free unit it shows up as a headset too). The device the phone uses for calls right now wins;
     * otherwise any connected call headset, since Android sends new calls to it.
     */
    fun pickHeadset(outputs: List<Output>, current: Output?, computer: Computer?): Output? {
        fun isComputer(o: Output): Boolean {
            val c = computer ?: return false
            val a = norm(o.address)
            val ca = norm(c.address)
            if (a.isNotEmpty() && ca.isNotEmpty()) {
                // Apps may only see the last bytes of an address ("XX:XX:XX:XX:AB:CD").
                if (a == ca || (o.address?.contains('X', ignoreCase = true) == true && ca.endsWith(a.takeLast(4)))) return true
            }
            val n = o.name?.trim()
            return !n.isNullOrEmpty() && c.name?.trim().equals(n, ignoreCase = true)
        }
        if (current != null && current.type in CALL_TYPES && !isComputer(current)) return current
        return outputs.firstOrNull { it.type in CALL_TYPES && !isComputer(it) }
    }

    /** §13.5: this Bluetooth device is one of the connected computers (its hands-free unit), not a headset. */
    fun isComputer(name: String?, address: String?): Boolean = computers.values.any { c ->
        val a = norm(address)
        val ca = norm(c.address)
        if (a.isNotEmpty() && ca.isNotEmpty() && (a == ca || (address?.contains('X', ignoreCase = true) == true && ca.endsWith(a.takeLast(4))))) return@any true
        val n = name?.trim()
        !n.isNullOrEmpty() && c.name?.trim().equals(n, ignoreCase = true)
    }

    private fun norm(a: String?): String = a.orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    private fun ensureStarted() {
        if (started) return
        started = true
        val ctx = Hub.app
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = changed()
            }
            ContextCompat.registerReceiver(ctx, r, filter, null, worker, ContextCompat.RECEIVER_NOT_EXPORTED)
            val am = ctx.getSystemService(AudioManager::class.java)
            am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = changed()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = changed()
            }, worker)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am?.addOnCommunicationDeviceChangedListener({ worker.post(it) }) { changed() }
            }
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.getProfileProxy(ctx, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile?) {
                    headsetProxy = proxy as? BluetoothHeadset
                    changed()
                }
                override fun onServiceDisconnected(profile: Int) { headsetProxy = null }
            }, android.bluetooth.BluetoothProfile.HEADSET)
        } catch (t: Throwable) {
            Log.w(TAG, "listeners", t)
        }
    }
}
