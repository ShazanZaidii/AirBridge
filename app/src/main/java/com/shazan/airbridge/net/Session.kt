package com.shazan.airbridge.net

import android.util.Log
import org.json.JSONObject

/** An ordered duplex channel carrying discrete text/binary messages (§10). */
interface Link {
    /** "lan" or "nearby". */
    val kind: String
    /** Human-readable endpoint (for logs). */
    val label: String
    fun sendText(text: String): Boolean
    fun sendBinary(data: ByteArray): Boolean
    /** Bytes accepted by [sendText]/[sendBinary] but not yet written to the network. */
    fun queuedBytes(): Long
    fun close()
    /** Session authenticated with [peerId]. */
    fun onReady(peerId: String) {}
}

enum class Role { CLIENT, HOST }

/** Why a session ended; decides reconnect behaviour and UI messages. */
sealed class Outcome(val reconnect: Boolean) {
    /** Transport dropped / liveness timeout / peer shutdown: retry with backoff. */
    data class Lost(val why: String) : Outcome(true)
    /** Peer sent bye replaced/user, or we closed on purpose: do not retry. */
    data class Ended(val why: String) : Outcome(false)
    /** Peer no longer knows us (auth_fail unpaired/bad_mac, or peer said unpaired): forget key. */
    data class Unpaired(val why: String) : Outcome(false)
    data class PairFailed(val reason: String) : Outcome(false)
    /** Connected to a different device than expected at that address (stale address). */
    object WrongPeer : Outcome(true)
}

/**
 * Per-connection protocol state machine shared by WebSocket and Nearby (§3, §4).
 * Text/binary input may arrive on any single reader thread; [tick] on the scheduler thread.
 * Nothing in here ever throws back into the transport.
 */
class Session(
    val role: Role,
    val link: Link,
    /** Client side: the peer id we intend to reach (null = accept whoever answers, only while pairing). */
    private val expectedPeerId: String?,
    /** Pairing secret S (QR bytes, typed code, or confirmed Nearby digits); null = auth only. */
    private val pairSecret: ByteArray?,
    private val onEnd: (Session, Outcome) -> Unit,
) {
    private enum class State { NEW, HANDSHAKE, READY, CLOSED }

    private val tag = "AirBridge.Session"
    private val lock = Any()
    @Volatile private var state = State.NEW
    private val myNonce = Protocol.randomBytes(16)
    private var peerNonce: ByteArray? = null
    private var key: ByteArray? = null
    private val startedAt = System.currentTimeMillis()
    @Volatile private var lastRx = startedAt
    @Volatile private var lastTx = startedAt

    val isPairing: Boolean get() = pairSecret != null
    val isReady: Boolean get() = state == State.READY
    val isClosed: Boolean get() = state == State.CLOSED
    /** True once the handshake completed (stays true after close). */
    @Volatile var everReady = false; private set

    @Volatile var peerId: String? = expectedPeerId; private set
    @Volatile var peerName: String = ""; private set
    @Volatile var peerPlat: String = ""; private set
    @Volatile var peerCaps: Set<String> = emptySet(); private set
    @Volatile var peerScreenW = 0; private set
    @Volatile var peerScreenH = 0; private set

    /** Per-session file transfer state (§6, rule 4). */
    val xfer by lazy { FileTransfer(this) }

    fun info(): PeerInfo = PeerInfo(peerId ?: "", peerName, peerPlat, peerCaps, peerScreenW, peerScreenH)

    /** Client: call once the transport is open. Host: call once the transport is open (waits for hello). */
    fun start() {
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.HANDSHAKE
        }
        Hub.register(this)
        if (role == Role.CLIENT) sendHello(paired = null)
    }

    // ---- sending ----

    fun send(m: JSONObject): Boolean {
        if (state == State.CLOSED) return false
        val text = try { normalizeNumbers(m).toString() } catch (e: Exception) { Log.w(tag, "bad outgoing json", e); return false }
        val ok = link.sendText(text)
        if (ok) lastTx = System.currentTimeMillis()
        return ok
    }

    fun sendBinary(b: ByteArray): Boolean {
        if (state != State.READY) return false
        val ok = link.sendBinary(b)
        if (ok) lastTx = System.currentTimeMillis()
        return ok
    }

    private fun sendHello(paired: Boolean?) {
        val (w, h) = Identity.screenSize()
        send(Protocol.hello(Identity.id, Identity.name, myNonce, w, h, paired))
    }

    // ---- receiving ----

    fun onText(text: String) {
        lastRx = System.currentTimeMillis()
        if (state == State.CLOSED) return
        if (text.length > Protocol.MAX_TEXT_FRAME) return
        val m = parseJson(text) ?: return
        val t = m.str("t") ?: return
        try {
            if (state == State.READY) onReadyMessage(t, m) else onHandshakeMessage(t, m)
        } catch (e: Exception) {
            Log.w(tag, "error handling '$t' from ${link.label}", e)
        }
    }

    fun onBinary(data: ByteArray) {
        lastRx = System.currentTimeMillis()
        if (state != State.READY || data.isEmpty()) return
        try {
            if ((data[0].toInt() and 0xFF) == Protocol.KIND_FILE_CHUNK) xfer.onChunk(data)
        } catch (e: Exception) {
            Log.w(tag, "error handling binary from ${link.label}", e)
        }
    }

    /** Transport reported close/failure. */
    fun onTransportClosed(why: String) = finish(Outcome.Lost(why), bye = null)

    /** Close on purpose. [bye] is sent first (best effort) if non-null. */
    fun close(outcome: Outcome, bye: String?) = finish(outcome, bye)

    private fun onHandshakeMessage(t: String, m: JSONObject) {
        when (t) {
            "hello" -> onHello(m)
            "auth" -> if (role == Role.HOST) onAuth(m)
            "auth_ok" -> if (role == Role.CLIENT) onAuthOk(m)
            "auth_fail" -> if (role == Role.CLIENT) onAuthFail(m.str("reason") ?: "")
            "pair" -> if (role == Role.HOST) onPair(m)
            "pair_ok" -> if (role == Role.CLIENT) onPairOk(m)
            "pair_fail" -> if (role == Role.CLIENT) finish(Outcome.PairFailed(m.str("reason") ?: "bad_code"), null)
            "ping" -> send(Protocol.msg("pong").put("ts", m.optLong("ts")))
            "bye" -> finish(byeOutcome(m.str("reason")), null)
            else -> Unit // rule 5: nothing else before ready
        }
    }

    private fun onHello(m: JSONObject) {
        if (m.optInt("v", -1) != Protocol.VERSION) {
            send(Protocol.withReason("auth_fail", "version"))
            finish(Outcome.Ended("version"), null)
            return
        }
        val id = m.str("id")?.lowercase()
        val nonce = Protocol.unb64(m.str("nonce"))
        if (id.isNullOrBlank() || nonce == null || nonce.isEmpty() || id == Identity.id) {
            finish(Outcome.Ended("bad hello"), null)
            return
        }
        synchronized(lock) {
            if (peerNonce != null) return // duplicate hello
            peerNonce = nonce
        }
        if (role == Role.CLIENT && expectedPeerId != null && expectedPeerId != id) {
            Log.w(tag, "expected $expectedPeerId but ${link.label} is $id")
            finish(Outcome.WrongPeer, "error")
            return
        }
        peerId = id
        peerName = m.str("name")?.take(128) ?: "Device"
        peerPlat = m.str("plat") ?: ""
        peerCaps = m.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        m.optJSONObject("screen")?.let { peerScreenW = it.optInt("w"); peerScreenH = it.optInt("h") }

        if (role == Role.HOST) {
            sendHello(paired = PairStore.key(id) != null)
            return
        }
        val stored = PairStore.key(id)
        val hostKnowsUs = m.optBoolean("paired", false)
        when {
            pairSecret != null -> {
                val k = Protocol.deriveKey(pairSecret, Identity.id, id)
                key = k
                val proof = Protocol.hmac(k, Protocol.macInput("pair|", myNonce, nonce, Identity.id))
                send(Protocol.msg("pair").put("proof", Protocol.b64(proof)))
            }
            stored != null && hostKnowsUs -> {
                key = stored
                val mac = Protocol.hmac(stored, Protocol.macInput("auth|", myNonce, nonce, Identity.id))
                send(Protocol.msg("auth").put("mac", Protocol.b64(mac)))
            }
            stored != null -> finish(Outcome.Unpaired("host forgot us"), "error")
            else -> finish(Outcome.Ended("not paired"), "error")
        }
    }

    private fun onAuth(m: JSONObject) {
        val id = peerId ?: return
        val pn = peerNonce ?: return
        val k = PairStore.key(id)
        if (k == null) {
            send(Protocol.withReason("auth_fail", "unpaired"))
            finish(Outcome.Ended("unpaired client"), null)
            return
        }
        val expect = Protocol.hmac(k, Protocol.macInput("auth|", pn, myNonce, id))
        if (!Protocol.constEq(expect, Protocol.unb64(m.str("mac")))) {
            send(Protocol.withReason("auth_fail", "bad_mac"))
            finish(Outcome.Ended("bad mac"), null)
            return
        }
        key = k
        val ack = Protocol.hmac(k, Protocol.macInput("auth_ack|", myNonce, pn, Identity.id))
        send(Protocol.msg("auth_ok").put("mac", Protocol.b64(ack)))
        becomeReady()
    }

    private fun onAuthOk(m: JSONObject) {
        val k = key ?: return
        val pn = peerNonce ?: return
        val id = peerId ?: return
        val expect = Protocol.hmac(k, Protocol.macInput("auth_ack|", pn, myNonce, id))
        if (!Protocol.constEq(expect, Protocol.unb64(m.str("mac")))) {
            // Possible impostor: close, keep our key.
            finish(Outcome.Ended("bad auth_ok mac"), "error")
            return
        }
        becomeReady()
    }

    private fun onAuthFail(reason: String) {
        when (reason) {
            "unpaired", "bad_mac" -> finish(Outcome.Unpaired(reason), null)
            "busy" -> finish(Outcome.Lost("busy"), null)
            else -> finish(Outcome.Ended(reason.ifBlank { "auth_fail" }), null)
        }
    }

    private fun onPair(m: JSONObject) {
        val id = peerId ?: return
        val pn = peerNonce ?: return
        val s = pairSecret
        if (s == null) {
            send(Protocol.withReason("pair_fail", "not_pairing"))
            finish(Outcome.PairFailed("not_pairing"), null)
            return
        }
        val k = Protocol.deriveKey(s, id, Identity.id)
        val expect = Protocol.hmac(k, Protocol.macInput("pair|", pn, myNonce, id))
        if (!Protocol.constEq(expect, Protocol.unb64(m.str("proof")))) {
            send(Protocol.withReason("pair_fail", "bad_code"))
            finish(Outcome.PairFailed("bad_code"), null)
            return
        }
        key = k
        PairStore.put(id, k, peerName, peerPlat, link.kind, null, 0)
        val ack = Protocol.hmac(k, Protocol.macInput("pair_ack|", myNonce, pn, Identity.id))
        send(Protocol.msg("pair_ok").put("proof", Protocol.b64(ack)))
        becomeReady()
    }

    private fun onPairOk(m: JSONObject) {
        val k = key ?: return
        val pn = peerNonce ?: return
        val id = peerId ?: return
        val expect = Protocol.hmac(k, Protocol.macInput("pair_ack|", pn, myNonce, id))
        if (!Protocol.constEq(expect, Protocol.unb64(m.str("proof")))) {
            finish(Outcome.PairFailed("bad_proof"), "error")
            return
        }
        PairStore.put(id, k, peerName, peerPlat, link.kind, null, 0)
        becomeReady()
    }

    private fun becomeReady() {
        synchronized(lock) {
            if (state != State.HANDSHAKE) return
            state = State.READY
            everReady = true
        }
        Log.i(tag, "ready: $peerName ($peerId) via ${link.label}")
        peerId?.let { try { link.onReady(it) } catch (_: Exception) {} }
        Hub.onReady(this)
    }

    private fun onReadyMessage(t: String, m: JSONObject) {
        when (t) {
            "ping" -> send(Protocol.msg("pong").put("ts", m.optLong("ts")))
            "pong" -> Unit
            "bye" -> finish(byeOutcome(m.str("reason")), null)
            "unpair" -> finish(Outcome.Unpaired("peer unpaired"), null)
            "name" -> m.str("name")?.takeIf { it.isNotBlank() }?.let {
                peerName = it.take(128)
                Hub.onPeerRenamed(this)
            }
            "error" -> Log.i(tag, "peer error ${m.str("code")}: ${m.str("msg")}")
            "hello", "auth", "auth_ok", "auth_fail", "pair", "pair_ok", "pair_fail" -> Unit
            else -> Hub.route(this, t, m)
        }
    }

    private fun byeOutcome(reason: String?): Outcome = when (reason) {
        "replaced", "user" -> Outcome.Ended("bye $reason")
        else -> Outcome.Lost("bye ${reason ?: ""}")
    }

    /** Liveness and handshake timeout (§3, §4). Called ~1/s. */
    fun tick(now: Long) {
        when (state) {
            State.NEW, State.HANDSHAKE ->
                if (now - startedAt > Protocol.HANDSHAKE_TIMEOUT_MS) finish(Outcome.Lost("handshake timeout"), null)
            State.READY -> {
                if (now - lastRx > Protocol.DEAD_MS) finish(Outcome.Lost("liveness timeout"), null)
                else if (now - lastTx >= Protocol.PING_IDLE_MS) send(Protocol.ping())
            }
            State.CLOSED -> Unit
        }
    }

    private fun finish(outcome: Outcome, bye: String?) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
        }
        Log.i(tag, "closed ${peerName.ifBlank { link.label }}: $outcome")
        if (bye != null) try { link.sendText(Protocol.withReason("bye", bye).toString()) } catch (_: Exception) {}
        try { link.close() } catch (_: Exception) {}
        try { xfer.onSessionClosed() } catch (e: Exception) { Log.w(tag, "xfer cleanup", e) }
        Hub.onClosed(this, outcome)
        try { onEnd(this, outcome) } catch (e: Exception) { Log.w(tag, "onEnd", e) }
    }
}
