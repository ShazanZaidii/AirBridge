package com.shazan.airbridge.net

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key exchange, key derivation and the encrypted envelope of Protocol v2 rev 2 (§3.1–§3.6).
 * Pure JVM (no Android APIs) so it is unit-testable; [Session] is its only production user.
 */
object Crypto {
    const val PK_LEN = 65
    private const val COORD = 32

    /** P-256 domain parameters, taken from a generated key so every provider agrees on them. */
    val p256: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** A per-connection ephemeral key (§3.1). The private half is dropped after [agree]. */
    class Ephemeral internal constructor(val publicRaw: ByteArray, private var priv: PrivateKey?) {
        /** Z = x-coordinate of ECDH(own private, peer public), 32 bytes. Destroys the private key. */
        fun agree(peer: ECPublicKey): ByteArray {
            val k = priv ?: throw IllegalStateException("ephemeral key already used")
            try { return ecdh(k, peer) } finally { destroy() }
        }

        fun destroy() {
            val k = priv ?: return
            priv = null
            // Most providers don't implement Destroyable for EC keys; dropping the reference is the best we can do.
            try { if (!k.isDestroyed) k.destroy() } catch (_: Exception) {}
        }
    }

    fun ephemeral(): Ephemeral {
        val kp = newKeyPair()
        return Ephemeral(encodePoint(kp.public as ECPublicKey), kp.private)
    }

    /** X9.63 uncompressed: 0x04 ‖ X ‖ Y, coordinates left-padded to 32 bytes. */
    fun encodePoint(pub: ECPublicKey): ByteArray {
        val out = ByteArray(PK_LEN)
        out[0] = 4
        fixed(pub.w.affineX).copyInto(out, 1)
        fixed(pub.w.affineY).copyInto(out, 1 + COORD)
        return out
    }

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == COORD -> b
            b.size > COORD -> b.copyOfRange(b.size - COORD, b.size) // sign byte
            else -> ByteArray(COORD - b.size) + b
        }
    }

    /** Parses and validates a peer point (on-curve, in range, not infinity). Null if invalid. */
    fun decodePoint(raw: ByteArray?): ECPublicKey? {
        if (raw == null || raw.size != PK_LEN || raw[0].toInt() != 4) return null
        return try {
            val x = BigInteger(1, raw.copyOfRange(1, 1 + COORD))
            val y = BigInteger(1, raw.copyOfRange(1 + COORD, PK_LEN))
            val params = p256
            val p = (params.curve.field as ECFieldFp).p
            if (x >= p || y >= p) return null
            // y² = x³ + ax + b (mod p). Cofactor is 1, so on-curve is sufficient.
            val lhs = y.multiply(y).mod(p)
            val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
            if (lhs != rhs) return null
            // The provider validates again when building the key.
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        } catch (_: Exception) { null }
    }

    fun ecdh(priv: PrivateKey, peer: ECPublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(peer, true)
        val z = ka.generateSecret()
        return if (z.size == COORD) z else fixed(BigInteger(1, z))
    }

    // ---- HMAC / HKDF (RFC 5869) ----

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream(length)
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t, 0, minOf(t.size, length - out.size()))
            i++
        }
        Arrays.fill(prk, 0)
        return out.toByteArray()
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)
    private val BAR = byteArrayOf('|'.code.toByte())

    /** T = cNonce | "|" | hNonce | "|" | clientId | "|" | hostId | "|" | cPk | "|" | hPk (§3.2). */
    fun transcript(cNonce: ByteArray, hNonce: ByteArray, clientId: String, hostId: String, cPk: ByteArray, hPk: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(cNonce); write(BAR); write(hNonce); write(BAR)
            write(utf8(clientId)); write(BAR); write(utf8(hostId)); write(BAR)
            write(cPk); write(BAR); write(hPk)
        }.toByteArray()

    /** HMAC(K, label | T) for auth/auth_ack/pair/pair_ack (label includes the trailing '|'). */
    fun mac(k: ByteArray, label: String, t: ByteArray): ByteArray = hmac(k, utf8(label), t)

    /** Mode `qr`: K = HMAC(S, "airbridge/v2/pair|" | clientId | "|" | hostId). */
    fun pairKeyQr(secret: ByteArray, clientId: String, hostId: String): ByteArray =
        hmac(secret, utf8("airbridge/v2/pair|$clientId|$hostId"))

    /** Mode `confirm`: K = HKDF(Z, cNonce | hNonce, "airbridge/v2/pairkey|" | clientId | "|" | hostId, 32). */
    fun pairKeyConfirm(z: ByteArray, cNonce: ByteArray, hNonce: ByteArray, clientId: String, hostId: String): ByteArray =
        hkdf(z, cNonce + hNonce, utf8("airbridge/v2/pairkey|$clientId|$hostId"), 32)

    /** The 6 digits both users compare in mode `confirm` (§3.3). */
    fun confirmDigits(z: ByteArray, t: ByteArray): String {
        val h = hmac(z, utf8("airbridge/v2/confirm|"), t)
        val v = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xFFFFFFFFL
        return (v % 1_000_000L).toString().padStart(6, '0')
    }

    /** (k_c2h, k_h2c) = HKDF(Z, cNonce | hNonce, "airbridge/v2/session|" | T, 64) split in halves (§3.6). */
    fun sessionKeys(z: ByteArray, cNonce: ByteArray, hNonce: ByteArray, t: ByteArray): Pair<ByteArray, ByteArray> {
        val keys = hkdf(z, cNonce + hNonce, utf8("airbridge/v2/session|") + t, 64)
        val pair = keys.copyOfRange(0, 32) to keys.copyOfRange(32, 64)
        Arrays.fill(keys, 0)
        return pair
    }
}

/**
 * The `0x02` encrypted envelope (§3.6): `0x02 | u64 seq | AES-256-GCM(innerKind | payload)`,
 * nonce = 0^4 ‖ seq, AAD = the 9 header bytes, one strictly increasing seq per direction.
 *
 * [seal] and [open] are each thread-safe and use separate locks (a large decrypt never stalls
 * sends); the caller must hand sealed frames to the transport in the order they were sealed
 * (Session holds one lock across seal + enqueue).
 */
class Envelope(sendKey: ByteArray, recvKey: ByteArray) {
    companion object {
        const val KIND: Int = 0x02
        const val HEADER = 9
        const val TAG = 16
        const val OVERHEAD = HEADER + 1 + TAG
        const val INNER_TEXT = 0
        const val INNER_BINARY = 1

        fun nonce(seq: Long): ByteArray = ByteArray(12).also { ByteBuffer.wrap(it, 4, 8).putLong(seq) }
    }

    class Opened(val innerKind: Int, val payload: ByteArray)

    private val sKey = SecretKeySpec(sendKey, "AES")
    private val rKey = SecretKeySpec(recvKey, "AES")
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendSeq = 0L
    private var recvSeq = 0L
    private val encLock = Any()
    private val decLock = Any()

    init {
        // SecretKeySpec keeps its own copy.
        Arrays.fill(sendKey, 0); Arrays.fill(recvKey, 0)
    }

    fun seal(innerKind: Int, payload: ByteArray): ByteArray = synchronized(encLock) {
        val seq = sendSeq++
        val out = ByteArray(OVERHEAD + payload.size)
        out[0] = KIND.toByte()
        ByteBuffer.wrap(out, 1, 8).putLong(seq)
        enc.init(Cipher.ENCRYPT_MODE, sKey, GCMParameterSpec(TAG * 8, nonce(seq)))
        enc.updateAAD(out, 0, HEADER)
        var n = enc.update(byteArrayOf(innerKind.toByte()), 0, 1, out, HEADER)
        n += enc.doFinal(payload, 0, payload.size, out, HEADER + n)
        check(n == 1 + payload.size + TAG)
        out
    }

    /** Decrypts the next frame. Null = wrong kind, wrong seq, or authentication failure: close. */
    fun open(frame: ByteArray): Opened? = synchronized(decLock) { openLocked(frame) }

    private fun openLocked(frame: ByteArray): Opened? {
        if (frame.size < OVERHEAD || (frame[0].toInt() and 0xFF) != KIND) return null
        val seq = ByteBuffer.wrap(frame, 1, 8).long
        if (seq != recvSeq) return null
        val plain = try {
            dec.init(Cipher.DECRYPT_MODE, rKey, GCMParameterSpec(TAG * 8, nonce(seq)))
            dec.updateAAD(frame, 0, HEADER)
            dec.doFinal(frame, HEADER, frame.size - HEADER)
        } catch (_: Exception) { return null }
        recvSeq++
        if (plain.isEmpty()) return null
        return Opened(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }
}
