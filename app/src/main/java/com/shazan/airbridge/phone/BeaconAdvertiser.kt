package com.shazan.airbridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.UUID

/**
 * A small Bluetooth LE beacon so the computers can tell how close the phone is (§13.4). Advertises while calls
 * are on, a computer that decides where calls ring is connected, and Bluetooth "Nearby devices" is allowed.
 * Low-power mode (about one packet a second), non-connectable; the payload is only a rotating id
 * ([BeaconId]) that just the paired computers can recognise. Runs on [PhoneRelay.worker].
 */
object BeaconAdvertiser {
    private const val TAG = "AirBridge.Beacon"
    private const val PREFS = "AirBridgePrefs"
    private const val K_KEY = "CE_BEACON_KEY"

    private lateinit var app: Context
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null
    private var withTxPower = true
    private var btReceiver: BroadcastReceiver? = null
    private val worker get() = PhoneRelay.worker
    private val rotate = Runnable { if (callback != null) { stop(); refresh() } }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        worker.post { registerBtState() }
    }

    /** The phone's beacon key (random, made once, never leaves the phone except to paired computers). */
    fun keyBase64(): String {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(K_KEY, null)?.let { return it }
        val k = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val b64 = java.util.Base64.getEncoder().encodeToString(k)
        p.edit().putString(K_KEY, b64).apply()
        return b64
    }

    private fun key(): ByteArray = java.util.Base64.getDecoder().decode(keyBase64())

    /** The phone can advertise at all (hardware and permission); the adapter may still be off. */
    fun possible(): Boolean {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PhoneRelaySettings.granted(app, Manifest.permission.BLUETOOTH_ADVERTISE) else true
        if (!ok) return false
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try { adapter.isMultipleAdvertisementSupported || !adapter.isEnabled } catch (_: Throwable) { false }
    }

    /** `call_beacon` for the computers: the key while we advertise (or would, once Bluetooth is on). */
    fun message(): Map<String, Any?> =
        if (PhoneRelaySettings.callsEnabled && possible()) NotifWire.beacon(keyBase64(), BeaconId.SERVICE_UUID, BeaconId.PERIOD_S)
        else NotifWire.beacon(null, BeaconId.SERVICE_UUID, BeaconId.PERIOD_S)

    /** Start or stop to match the settings, permission, Bluetooth and connected computers. Worker thread. */
    fun refresh() {
        val want = PhoneRelaySettings.callsEnabled && possible() && PhoneRelay.ringPeers().isNotEmpty()
        if (want && callback == null) start() else if (!want && callback != null) stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        val id = BeaconId.derive(key(), BeaconId.epoch(System.currentTimeMillis()))
        val uuid = ParcelUuid(UUID.fromString(BeaconId.SERVICE_UUID))
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(uuid).setIncludeTxPowerLevel(withTxPower).setIncludeDeviceName(false).build()
        val scan = AdvertiseData.Builder().addServiceData(uuid, id).setIncludeDeviceName(false).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.i(TAG, "advertising") }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
                worker.post {
                    if (callback !== this) return@post
                    callback = null; advertiser = null
                    // Some stacks count the tx power field against a smaller budget: retry once without it.
                    if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && withTxPower) { withTxPower = false; refresh() }
                }
            }
        }
        try {
            adv.startAdvertising(settings, data, scan, cb)
            advertiser = adv; callback = cb
            worker.removeCallbacks(rotate)
            worker.postDelayed(rotate, BeaconId.msToNext(System.currentTimeMillis()) + 500)
        } catch (t: Throwable) { Log.w(TAG, "start", t) }
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        worker.removeCallbacks(rotate)
        val cb = callback ?: return
        try { advertiser?.stopAdvertising(cb) } catch (t: Throwable) { Log.w(TAG, "stop", t) }
        callback = null; advertiser = null
    }

    private fun registerBtState() {
        if (btReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                worker.post {
                    val s = i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (s == BluetoothAdapter.STATE_OFF) { callback = null; advertiser = null; worker.removeCallbacks(rotate) }
                    refresh()
                }
            }
        }
        try {
            ContextCompat.registerReceiver(app, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            btReceiver = r
        } catch (t: Throwable) { Log.w(TAG, "bt receiver", t) }
    }
}
