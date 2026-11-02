package com.shazan.airbridge.net.link

/**
 * Thresholds and timeouts for link upgrades (PROTOCOL §10.1; docs/WIFI_DIRECT_ANDROID_WINDOWS.md §6.1, §6.4).
 * Pure Kotlin.
 */
object LinkPolicy {
    /** With a LAN session, upgrade an `xfer_offer` only above this many bytes (§6.1: "> 200 MB"). */
    const val XFER_UPGRADE_BYTES = 200L * 1000 * 1000

    /** Whole link ready within this long of `link_request` / `link_offer` (§10.1). */
    const val LINK_READY_MS = 30_000L
    /** `requestNetwork` timeout (§6.4). */
    const val JOIN_TIMEOUT_MS = 15_000L
    /** Dial attempts after the join; the address can be tentative (§4: "retry the dial 3 × 2 s"). */
    const val DIAL_ATTEMPTS = 3
    const val DIAL_RETRY_MS = 2_000L
    /** TCP connect timeout per attempt (§6.4: "3 × 2 s connect"); the handshake then has the usual 10 s (§3). */
    const val DIAL_CONNECT_MS = 2_000L
    /** Default `ttl` (s); extended to now + [TTL_EXTEND_MS] while bytes flow; `link_done` after [IDLE_MS] idle. */
    const val TTL_DEFAULT_S = 120
    const val TTL_EXTEND_MS = 60_000L
    const val IDLE_MS = 20_000L
    const val TICK_MS = 1_000L
    /** Teardown with the home Wi-Fi kept: how long to wait for the LAN session before dropping the link anyway. */
    const val BACK_DIAL_WAIT_MS = 5_000L
    /** §2.2 first port, used if an offer carries none. */
    const val DEFAULT_PORT = 47632

    /** Peer cap: can host a Wi-Fi Direct legacy group (Windows). */
    const val CAP_HOST = "wfd_host"
    /** Own cap: can join one (Android API ≥ 29). */
    const val CAP_JOIN = "wfd_join"

    /**
     * Should this phone ask [peerCaps]'s owner for a `wfd_legacy` link before sending?
     * [why] = "xfer" | "mirror" | "user"; [fast] = the user tapped "Send fast"; [linkKind] = the
     * current session's transport ("lan", "nearby", "wfd"); [auto] = automatic upgrades enabled.
     */
    fun shouldUpgrade(
        why: String,
        bytes: Long,
        fast: Boolean,
        peerCaps: Set<String>,
        linkKind: String,
        selfCanJoin: Boolean,
        auto: Boolean = true,
    ): Boolean {
        if (!selfCanJoin || CAP_HOST !in peerCaps) return false
        if (linkKind != "lan") return false // already on a link, or a Nearby peer (which upgrades itself)
        if (fast || why == "user") return true
        if (!auto) return false
        return why == "mirror" || bytes > XFER_UPGRADE_BYTES
    }
}
