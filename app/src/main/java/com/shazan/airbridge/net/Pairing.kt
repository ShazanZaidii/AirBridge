package com.shazan.airbridge.net

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Parsed `airbridge://pair?...` link (§2.4). */
data class PairLink(
    val id: String,
    val name: String,
    val plat: String,
    val addr: String?,
    val port: Int,
    val secret: ByteArray,
) {
    companion object {
        fun parse(raw: String): PairLink? = try {
            val uri = Uri.parse(raw.trim())
            if (uri.scheme != "airbridge" || uri.host != "pair") null
            else if (uri.getQueryParameter("v") != "2") null
            else {
                val id = uri.getQueryParameter("id")
                val k = Protocol.unb64url(uri.getQueryParameter("k"))
                if (id.isNullOrBlank() || k == null || k.size < 16) null
                else PairLink(
                    id = id,
                    name = uri.getQueryParameter("name") ?: "Computer",
                    plat = uri.getQueryParameter("plat") ?: "macos",
                    addr = uri.getQueryParameter("addr")?.takeIf { it.isNotBlank() },
                    port = uri.getQueryParameter("port")?.toIntOrNull() ?: 0,
                    secret = k,
                )
            }
        } catch (_: Exception) { null }
    }
}

/** UI-safe view of a paired peer (no key material). */
data class PairedPeer(
    val id: String,
    val name: String,
    val plat: String,
    /** "lan" (WebSocket) or "nearby". */
    val via: String,
    val lastHost: String?,
    val lastPort: Int,
)

/**
 * Per-peer long-term keys (§3.3). Stored in app-private SharedPreferences `airbridge_pairs`
 * (excluded from backup). Each value is JSON: {name, plat, via, host, port, kw | k}.
 * `kw` = K wrapped with an Android Keystore AES-GCM key ("ivB64:ctB64"); `k` = plain base64
 * fallback used only if the Keystore is unusable on this device.
 */
object PairStore {
    private const val TAG = "AirBridge.PairStore"
    private const val PREFS = "airbridge_pairs"
    private const val KS_ALIAS = "airbridge_pair_wrap"

    private lateinit var prefs: SharedPreferences
    private val keyCache = ConcurrentHashMap<String, ByteArray>()
    private val _peers = MutableStateFlow<List<PairedPeer>>(emptyList())
    val peers: StateFlow<List<PairedPeer>> = _peers

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        publish()
    }

    fun all(): List<PairedPeer> = _peers.value
    fun get(id: String): PairedPeer? = _peers.value.firstOrNull { it.id == id }
    fun isEmpty(): Boolean = _peers.value.isEmpty()

    fun key(id: String): ByteArray? {
        keyCache[id]?.let { return it }
        val j = record(id) ?: return null
        val k = j.str("kw")?.let { unwrap(it) } ?: Protocol.unb64(j.str("k"))
        if (k == null) {
            Log.w(TAG, "Key for $id unreadable; treating as unpaired")
            return null
        }
        keyCache[id] = k
        return k
    }

    @Synchronized
    fun put(id: String, key: ByteArray, name: String, plat: String, via: String, host: String?, port: Int) {
        val j = JSONObject().put("name", name).put("plat", plat).put("via", via)
            .put("host", host ?: "").put("port", port).put("added", System.currentTimeMillis())
        val wrapped = wrap(key)
        if (wrapped != null) j.put("kw", wrapped) else j.put("k", Protocol.b64(key))
        keyCache[id] = key
        prefs.edit().putString(id, j.toString()).apply()
        publish()
    }

    @Synchronized
    fun updateMeta(id: String, name: String? = null, plat: String? = null, host: String? = null, port: Int? = null) {
        val j = record(id) ?: return
        var changed = false
        fun set(k: String, v: Any?) { if (v != null && j.opt(k) != v) { j.put(k, v); changed = true } }
        set("name", name?.takeIf { it.isNotBlank() }); set("plat", plat); set("host", host); set("port", port)
        if (changed) { prefs.edit().putString(id, j.toString()).apply(); publish() }
    }

    @Synchronized
    fun remove(id: String) {
        keyCache.remove(id)
        prefs.edit().remove(id).apply()
        publish()
    }

    private fun record(id: String): JSONObject? = prefs.getString(id, null)?.let { parseJson(it) }

    private fun publish() {
        _peers.value = prefs.all.mapNotNull { (id, v) ->
            val j = (v as? String)?.let { parseJson(it) } ?: return@mapNotNull null
            PairedPeer(
                id = id,
                name = j.optString("name", "Device"),
                plat = j.optString("plat", "macos"),
                via = j.optString("via", "lan"),
                lastHost = j.optString("host").takeIf { it.isNotBlank() },
                lastPort = j.optInt("port", 0),
            ) to j.optLong("added")
        }.sortedBy { it.second }.map { it.first }
    }

    // ---- Keystore wrapping ----

    private fun wrapKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
    } catch (e: Exception) {
        Log.w(TAG, "Keystore unavailable", e); null
    }

    private fun wrap(plain: ByteArray): String? = try {
        val sk = wrapKey() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sk) }
        val ct = c.doFinal(plain)
        Protocol.b64(c.iv) + ":" + Protocol.b64(ct)
    } catch (e: Exception) {
        Log.w(TAG, "wrap failed", e); null
    }

    private fun unwrap(s: String): ByteArray? = try {
        val iv = Protocol.unb64(s.substringBefore(':'))
        val ct = Protocol.unb64(s.substringAfter(':', ""))
        val sk = wrapKey()
        if (iv == null || ct == null || sk == null) null
        else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, iv))
            doFinal(ct)
        }
    } catch (e: Exception) {
        Log.w(TAG, "unwrap failed", e); null
    }
}
