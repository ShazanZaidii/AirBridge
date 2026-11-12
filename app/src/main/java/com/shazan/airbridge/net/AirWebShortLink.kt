package com.shazan.airbridge.net

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * A short, typeable stand-in for the session link, for a computer where you can't scan the code or type 40 characters
 * (`http://10.0.0.5:47650/j/K3X9QP` instead of `…/?t=7ky5Rro0b…`). The full link stays the real one; this only
 * redirects to it.
 *
 * It is weaker than the full link by design, so it is fenced in:
 *  - nothing exists until a connected page asks for one, and it expires after [ttlMs] (10 minutes);
 *  - six characters from an alphabet without look-alikes (0/O, 1/I/L), about 30 bits;
 *  - wrong guesses are counted for the whole server: after [maxMisses] in a [windowMs] window every try is refused
 *    until the window ends, right or wrong, so guessing it takes years, not minutes.
 */
class AirWebShortLink(
    private val ttlMs: Long = 10 * 60_000L,
    private val maxMisses: Int = 8,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { MATCH, MISS, LOCKED }

    class Issued(val code: String, val secondsLeft: Long)

    private val rng = SecureRandom()
    private var code: String? = null
    private var until = 0L
    private var windowStart = Long.MIN_VALUE
    private var misses = 0

    /** The live code (made now if there is none or it ran out) and how long it still has. */
    @Synchronized
    fun issue(): Issued {
        val now = clock()
        var c = code
        if (c == null || now >= until) {
            c = buildString { repeat(LENGTH) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }
            code = c
            until = now + ttlMs
        }
        return Issued(c, (until - now + 999) / 1000)
    }

    /** Checks what someone typed after `/j/`. Letters in any case are accepted. */
    @Synchronized
    fun check(given: String?): Outcome {
        val now = clock()
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMs) { windowStart = now; misses = 0 }
        if (misses >= maxMisses) return Outcome.LOCKED
        val c = code
        val ok = c != null && now < until && given != null && given.length == LENGTH &&
            MessageDigest.isEqual(given.uppercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8), c.toByteArray(StandardCharsets.UTF_8))
        if (ok) return Outcome.MATCH
        misses++
        return Outcome.MISS
    }

    companion object {
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val LENGTH = 6
    }
}
