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

/** How a session that has no stored key may pair (§3.3). */
sealed class PairMode {
    /** Authenticate with a stored key only. */
    object None : PairMode()
    /** Client: QR secret S scanned from the host. */
    class Qr(val secret: ByteArray) : PairMode()
    /**
     * Numeric comparison. [auto] = both users already compared digits out of band (Nearby's own
     * authentication), so neither side prompts again (§3.3 last rule, §10).
     */
    class Confirm(val auto: Boolean) : PairMode()
}

/**
 * Per-connection protocol state machine shared by WebSocket and Nearby (§3, §4), and the only place
 * that encrypts and decrypts (§3.6): callers hand it plaintext JSON / inner binary frames, and it
 * hands plaintext to [Hub] / [FileTransfer] / [PeerBus].
 *
 * Text/binary input arrives on one reader thread per transport; [tick] on the scheduler thread;
 * sends from any thread. Nothing in here ever throws back into the transport.
 */
class Session(
    val role: Role,
    val link: Link,
    /** Client side: the peer id we intend to reach (null = accept whoever answers, only while pairing). */
    private val expectedPeerId: String?,
    private val pairMode: PairMode,
    private val onEnd: (Session, Outcome) -> Unit,
) {
    private enum class State { NEW, HANDSHAKE, READY, CLOSED }

    private val tag = "AirBridge.Session"
    private val lock = Any()
    /** Held across seal + enqueue so envelope seq order equals wire order. */
    private val sendLock = Any()
    @Volatile private var state = State.NEW
    private val myNonce = Protocol.randomBytes(16)
    private val eph = Crypto.ephemeral()
    private var peerNonce: ByteArray? = null
    /** ECDH secret Z; wiped once session keys are derived. */
    private var z: ByteArray? = null
    /** Transcript T (§3.2). */
    private var transcript: ByteArray? = null
    private var key: ByteArray? = null
    @Volatile private var channel: Envelope? = null
    private val startedAt = System.currentTimeMillis()
    @Volatile private var pairStartedAt = 0L
    @Volatile private var lastRx = startedAt
    @Volatile private var lastTx = startedAt

    val isPairing: Boolean get() = pairMode !is PairMode.None
    val isReady: Boolean get() = state == State.READY
    val isClosed: Boolean get() = state == State.CLOSED
    /** True once the handshake completed (stays true after close). */
    @Volatile var everReady = false; private set

    /** Mode `confirm`, client: the 6 digits to show, once known. */
    @Volatile var confirmDigits: String? = null; private set
    /** Mode `confirm`, client: the user tapped Confirm and our proof is sent. */
    @Volatile var userConfirmed = false; private set

    @Volatile var peerId: String? = expectedPeerId; private set
    @Volatile var peerName: String = ""; private set
    @Volatile var peerPlat: String = ""; private set
    @Volatile var peerCaps: Set<String> = emptySet(); private set
    @Volatile var peerScreenW = 0; private set
    @Volatile var peerScreenH = 0; private set

    /** Whether our `hello` on this session advertised `ctl_target` (null until it was sent). */
    @Volatile var advertisedTarget: Boolean? = null; private set

    /** Per-session file transfer state (§6, rule 4). */
    val xfer by lazy { FileTransfer(this) }

    private val traffic = java.util.concurrent.atomic.AtomicLong()
    /** Inner binary bytes (file chunks, video) sent + received: link idle detection (§10.1). */
    val trafficBytes: Long get() = traffic.get()

    fun info(): PeerInfo = PeerInfo(peerId ?: "", peerName, peerPlat, peerCaps, peerScreenW, peerScreenH)

    /** Call once the transport is open. The client sends `hello`; the host waits for one. */
    fun start() {
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.HANDSHAKE
        }
        Hub.register(this)
        if (role == Role.CLIENT) sendHello(paired = null)
    }

    // ---- sending ----

    /** Send a message on a ready session (encrypted). False if not ready or the transport refused. */
    fun send(m: JSONObject): Boolean {
        if (state != State.READY) return false
        val text = try { normalizeNumbers(m).toString() } catch (e: Exception) { Log.w(tag, "bad outgoing json", e); return false }
        return transmitText(text)
    }

    /** Send an inner binary frame (starts with its own kind byte: 0x01 chunk, 0x03 video). */
    fun sendBinary(frame: ByteArray): Boolean {
        if (state != State.READY || frame.isEmpty()) return false
        // §9: peers close on oversized frames, so never send one.
        if (frame.size > Protocol.MAX_BINARY_FRAME) { Log.w(tag, "inner binary frame too large (${frame.size})"); return false }
        val ok = synchronized(sendLock) {
            val ch = channel ?: return false
            link.sendBinary(ch.seal(Envelope.INNER_BINARY, frame))
        }
        if (ok) { lastTx = System.currentTimeMillis(); traffic.addAndGet(frame.size.toLong()) }
        return ok
    }

    /** Handshake messages go in the clear; everything once [channel] exists is sealed. */
    private fun transmitText(text: String): Boolean {
        val ok = synchronized(sendLock) {
            val ch = channel
            if (ch != null) link.sendBinary(ch.seal(Envelope.INNER_TEXT, text.toByteArray(Charsets.UTF_8)))
            else link.sendText(text)
        }
        if (ok) lastTx = System.currentTimeMillis()
        return ok
    }

    private fun sendHandshake(m: JSONObject) {
        if (state == State.HANDSHAKE) transmitText(m.toString())
    }

    private fun sendHello(paired: Boolean?) {
        val (w, h) = Identity.screenSize()
        val caps = Protocol.caps()
        advertisedTarget = Protocol.CAP_TARGET in caps
        sendHandshake(Protocol.hello(Identity.id, Identity.name, myNonce, eph.publicRaw, w, h, paired, caps))
    }

    // ---- receiving ----

    fun onText(text: String) {
        lastRx = System.currentTimeMillis()
        when (state) {
            State.CLOSED -> return
            State.READY -> { violation("plaintext text frame after ready"); return }
            else -> Unit
        }
        if (text.length > Protocol.MAX_TEXT_FRAME) return
        val m = parseJson(text) ?: return
        val t = m.str("t") ?: return
        try {
            onHandshakeMessage(t, m)
        } catch (e: Exception) {
            Log.w(tag, "error handling '$t' from ${link.label}", e)
        }
    }

    fun onBinary(data: ByteArray) {
        lastRx = System.currentTimeMillis()
        if (state != State.READY || data.isEmpty()) return // rule 5: nothing binary before ready
        val ch = channel ?: return
        if ((data[0].toInt() and 0xFF) != Envelope.KIND) { violation("unencrypted binary frame after ready"); return }
        val opened = ch.open(data) ?: run { violation("bad envelope (seq/tag)"); return }
        peerId?.let { PeerBus.heard(it) }
        try {
            when (opened.innerKind) {
                Envelope.INNER_TEXT -> onInnerText(opened.payload)
                Envelope.INNER_BINARY -> onInnerBinary(opened.payload)
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(tag, "error handling frame from ${link.label}", e)
        }
    }

    private fun onInnerText(payload: ByteArray) {
        if (payload.size > Protocol.MAX_TEXT_FRAME) return
        val m = parseJson(String(payload, Charsets.UTF_8)) ?: return
        val t = m.str("t") ?: return
        onReadyMessage(t, m)
    }

    private fun onInnerBinary(frame: ByteArray) {
        if (frame.isEmpty()) return
        traffic.addAndGet(frame.size.toLong())
        when (frame[0].toInt() and 0xFF) {
            Protocol.KIND_FILE_CHUNK -> xfer.onChunk(frame)
            Protocol.KIND_VIDEO -> peerId?.let { id ->
                try { PeerBus.dispatchBinary(id, frame) } catch (e: Exception) { Log.w(tag, "binaryHandler", e) }
            }
            else -> Unit // unknown kind: ignore (rule 2)
        }
    }

    /** Transport reported close/failure. */
    fun onTransportClosed(why: String) = finish(Outcome.Lost(why), bye = null)

    /** Close on purpose. [bye] is sent first (best effort) if non-null. */
    fun close(outcome: Outcome, bye: String?) = finish(outcome, bye)

    private fun violation(why: String) {
        Log.w(tag, "protocol violation from ${link.label}: $why")
        finish(Outcome.Lost("protocol error: $why"), "error")
    }

    private fun onHandshakeMessage(t: String, m: JSONObject) {
        when (t) {
            "hello" -> onHello(m)
            "auth" -> if (role == Role.HOST) onAuth(m)
            "auth_ok" -> if (role == Role.CLIENT) onAuthOk(m)
            "auth_fail" -> if (role == Role.CLIENT) onAuthFail(m.str("reason") ?: "")
            "pair_start" -> if (role == Role.HOST) onPairStart(m)
            "pair" -> if (role == Role.HOST) onPair(m)
            "pair_ok" -> if (role == Role.CLIENT) onPairOk(m)
            "pair_fail" -> if (role == Role.CLIENT) finish(Outcome.PairFailed(m.str("reason") ?: "bad_proof"), null)
            "bye" -> finish(byeOutcome(m.str("reason")), null)
            else -> Unit // rule 5: nothing else before ready
        }
    }

    private fun onHello(m: JSONObject) {
        if (m.optInt("v", -1) != Protocol.VERSION) {
            sendHandshake(Protocol.withReason("auth_fail", "version"))
            finish(Outcome.Ended("version"), null)
            return
        }
        // Used byte-exact in T; §2.1 requires lowercase, so a mismatch here explains a later bad MAC.
        val id = m.str("id")
        if (id != null && id != id.lowercase()) Log.w(tag, "peer id '$id' is not lowercase (spec §2.1)")
        val nonce = Protocol.unb64(m.str("nonce"))
        val peerPkRaw = Protocol.unb64(m.str("pk"))
        val peerPk = Crypto.decodePoint(peerPkRaw)
        if (id.isNullOrBlank() || nonce == null || nonce.isEmpty() || id == Identity.id || peerPk == null || peerPkRaw == null) {
            Log.w(tag, "bad hello from ${link.label} (pk ${if (peerPk == null) "invalid" else "ok"})")
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

        // Z and T (§3.2/§3.3). The ephemeral private key is destroyed inside agree().
        val zz = try { eph.agree(peerPk) } catch (e: Exception) {
            Log.w(tag, "ECDH failed", e); finish(Outcome.Ended("bad hello"), null); return
        }
        z = zz
        transcript = if (role == Role.CLIENT) Crypto.transcript(myNonce, nonce, Identity.id, id, eph.publicRaw, peerPkRaw)
        else Crypto.transcript(nonce, myNonce, id, Identity.id, peerPkRaw, eph.publicRaw)

        if (role == Role.HOST) {
            sendHello(paired = PairStore.key(id) != null)
            return
        }
        val t = transcript!!
        val stored = PairStore.key(id)
        val hostKnowsUs = m.optBoolean("paired", false)
        when (val mode = pairMode) {
            is PairMode.Qr -> {
                val k = Crypto.pairKeyQr(mode.secret, Identity.id, id)
                key = k
                sendHandshake(Protocol.msg("pair").put("mode", "qr").put("proof", Protocol.b64(Crypto.mac(k, "pair|", t))))
            }
            is PairMode.Confirm -> {
                key = Crypto.pairKeyConfirm(zz, myNonce, nonce, Identity.id, id)
                confirmDigits = Crypto.confirmDigits(zz, t)
                pairStartedAt = System.currentTimeMillis()
                sendHandshake(Protocol.msg("pair_start").put("mode", "confirm"))
                if (mode.auto) sendConfirmProof() else Hub.onPairPrompt(this)
            }
            PairMode.None -> when {
                stored != null && hostKnowsUs -> {
                    key = stored
                    sendHandshake(Protocol.msg("auth").put("mac", Protocol.b64(Crypto.mac(stored, "auth|", t))))
                }
                stored != null -> finish(Outcome.Unpaired("host forgot us"), "error")
                else -> finish(Outcome.Ended("not paired"), "error")
            }
        }
    }

    /** Client, mode `confirm`: the user compared the digits. Safe to call from the UI thread. */
    fun userConfirm(accept: Boolean) {
        if (role != Role.CLIENT || pairMode !is PairMode.Confirm || state != State.HANDSHAKE) return
        if (!accept) { finish(Outcome.PairFailed("cancelled"), "user"); return }
        Hub.scheduler.execute { sendConfirmProof() }
    }

    private fun sendConfirmProof() {
        val k = key ?: return
        val t = transcript ?: return
        if (userConfirmed || state != State.HANDSHAKE) return
        userConfirmed = true
        sendHandshake(Protocol.msg("pair").put("mode", "confirm").put("proof", Protocol.b64(Crypto.mac(k, "pair|", t))))
        Hub.onPairPrompt(this)
    }

    private fun onAuth(m: JSONObject) {
        val id = peerId ?: return
        val t = transcript ?: return
        val k = PairStore.key(id)
        if (k == null) {
            sendHandshake(Protocol.withReason("auth_fail", "unpaired"))
            finish(Outcome.Ended("unpaired client"), null)
            return
        }
        if (!Protocol.constEq(Crypto.mac(k, "auth|", t), Protocol.unb64(m.str("mac")))) {
            sendHandshake(Protocol.withReason("auth_fail", "bad_mac"))
            finish(Outcome.Ended("bad mac"), null)
            return
        }
        key = k
        sendHandshake(Protocol.msg("auth_ok").put("mac", Protocol.b64(Crypto.mac(k, "auth_ack|", t))))
        becomeReady()
    }

    private fun onAuthOk(m: JSONObject) {
        val k = key ?: return
        val t = transcript ?: return
        if (!Protocol.constEq(Crypto.mac(k, "auth_ack|", t), Protocol.unb64(m.str("mac")))) {
            // Possible impostor or MITM: close, keep our key.
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

    /** Host side (Android only hosts over Nearby). */
    private fun refusePairing(id: String) {
        sendHandshake(Protocol.withReason("pair_fail", "not_pairing"))
        // The peer lost its key for us; ours is now useless. Forget it so both sides prompt next time.
        finish(if (PairStore.key(id) != null) Outcome.Unpaired("peer re-pairing") else Outcome.PairFailed("not_pairing"), null)
    }

    private fun onPairStart(m: JSONObject) {
        val id = peerId ?: return
        if (pairMode !is PairMode.Confirm || m.str("mode") != "confirm") { refusePairing(id); return }
        if (pairStartedAt == 0L) pairStartedAt = System.currentTimeMillis()
    }

    private fun onPair(m: JSONObject) {
        val id = peerId ?: return
        val pn = peerNonce ?: return
        val zz = z ?: return
        val t = transcript ?: return
        val mode = pairMode
        // Android hosts never show a QR, and only pair when Nearby's digits were confirmed (auto-accept).
        if (m.str("mode") != "confirm" || mode !is PairMode.Confirm || !mode.auto) { refusePairing(id); return }
        val k = Crypto.pairKeyConfirm(zz, pn, myNonce, id, Identity.id)
        if (!Protocol.constEq(Crypto.mac(k, "pair|", t), Protocol.unb64(m.str("proof")))) {
            sendHandshake(Protocol.withReason("pair_fail", "bad_proof"))
            finish(Outcome.PairFailed("bad_proof"), null)
            return
        }
        key = k
        PairStore.put(id, k, peerName, peerPlat, link.kind, null, 0)
        sendHandshake(Protocol.msg("pair_ok").put("proof", Protocol.b64(Crypto.mac(k, "pair_ack|", t))))
        becomeReady()
    }

    private fun onPairOk(m: JSONObject) {
        val k = key ?: return
        val t = transcript ?: return
        if (pairMode is PairMode.Confirm && !userConfirmed) return // host can't accept before our proof
        if (!Protocol.constEq(Crypto.mac(k, "pair_ack|", t), Protocol.unb64(m.str("proof")))) {
            finish(Outcome.PairFailed("bad_proof"), "error")
            return
        }
        PairStore.put(peerId ?: return, k, peerName, peerPlat, link.kind, null, 0)
        becomeReady()
    }

    /** Derive the session keys (§3.6), wipe Z, switch to the envelope. */
    private fun becomeReady() {
        val zz = z ?: return
        val pn = peerNonce ?: return
        val t = transcript ?: return
        val (cNonce, hNonce) = if (role == Role.CLIENT) myNonce to pn else pn to myNonce
        val (c2h, h2c) = Crypto.sessionKeys(zz, cNonce, hNonce, t)
        val env = if (role == Role.CLIENT) Envelope(c2h, h2c) else Envelope(h2c, c2h)
        java.util.Arrays.fill(zz, 0)
        z = null
        synchronized(lock) {
            if (state != State.HANDSHAKE) return
            synchronized(sendLock) { channel = env }
            state = State.READY
            everReady = true
        }
        Log.i(tag, "ready (encrypted): $peerName ($peerId) via ${link.label}")
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
            "error" -> {
                val code = m.str("code") ?: ""
                Log.i(tag, "peer error $code: ${m.str("msg")}")
                // Mirror errors (e.g. no screen-recording permission on the Mac) are shown by the input half.
                if (code.startsWith("mirror_")) peerId?.let { id ->
                    try { PeerBus.dispatchControl(id, m) } catch (e: Exception) { Log.w(tag, "controlHandler", e) }
                }
            }
            "hello", "auth", "auth_ok", "auth_fail", "pair_start", "pair", "pair_ok", "pair_fail" -> Unit
            else -> Hub.route(this, t, m)
        }
    }

    private fun byeOutcome(reason: String?): Outcome = when (reason) {
        "replaced", "user" -> Outcome.Ended("bye $reason")
        else -> Outcome.Lost("bye ${reason ?: ""}")
    }

    /** Liveness and handshake/pairing timeouts (§3, §3.3, §4). Called ~1/s. */
    fun tick(now: Long) {
        when (state) {
            State.NEW, State.HANDSHAKE -> {
                val ps = pairStartedAt
                if (ps > 0L) {
                    if (now - ps > Protocol.PAIR_TIMEOUT_MS) {
                        if (role == Role.HOST) sendHandshake(Protocol.withReason("pair_fail", "timeout"))
                        finish(Outcome.PairFailed("timeout"), null)
                    }
                } else if (now - startedAt > Protocol.HANDSHAKE_TIMEOUT_MS) finish(Outcome.Lost("handshake timeout"), null)
            }
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
        // Sealed if the channel is up, plaintext during the handshake.
        if (bye != null) try { transmitText(Protocol.withReason("bye", bye).toString()) } catch (_: Exception) {}
        eph.destroy()
        z?.let { java.util.Arrays.fill(it, 0) }
        z = null
        try { link.close() } catch (_: Exception) {}
        try { xfer.onSessionClosed() } catch (e: Exception) { Log.w(tag, "xfer cleanup", e) }
        Hub.onClosed(this, outcome)
        try { onEnd(this, outcome) } catch (e: Exception) { Log.w(tag, "onEnd", e) }
    }
}
