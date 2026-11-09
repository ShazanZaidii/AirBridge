package com.shazan.airbridge.net.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** State machine tests with a fake network/session environment and a manual clock. */
class LinkMachineTest {

    /** Stand-in for android.net.Network. */
    data class FakeNet(val id: Int)

    private class Timer(val at: Long, val task: () -> Unit) { var cancelled = false }

    private inner class Env : LinkEnv<FakeNet> {
        var clock = 1_000_000L
        val timers = mutableListOf<Timer>()
        val sent = mutableListOf<LinkMsg>()
        /** What the "current session" was when each message was sent. */
        val sentOn = mutableListOf<String>()
        var unsupported: String? = null
        var credsFor: WfdCreds? = WfdCreds("DIRECT-AB-AAAAAAAA", "p".repeat(32), 0)
        var sessionUp = true
        val joins = mutableListOf<Pair<WfdCreds, (JoinOutcome<FakeNet>) -> Unit>>()
        var joinLost: (() -> Unit)? = null
        var released = 0
        val dials = mutableListOf<Triple<String, Int, (DialEnd) -> Unit>>()
        var linkSessionClosed = 0
        val resumes = mutableListOf<Boolean>()
        var lan = true
        var link = false
        var bytes = 0L
        var xferBusy = false
        val logs = mutableListOf<String>()
        val failures = mutableListOf<Triple<String, Boolean, Boolean>>()

        override fun now() = clock
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val t = Timer(clock + delayMs, task); timers += t
            return Cancellable { t.cancelled = true }
        }
        override fun send(peerId: String, msg: LinkMsg): Boolean {
            if (!sessionUp) return false
            sent += msg; sentOn += if (lan) "lan" else if (link) "link" else "none"; return true
        }
        override fun joinUnsupported() = unsupported
        override fun creds(peerId: String, offer: LinkMsg.Offer) = credsFor
        override fun join(peerId: String, lid: Int, creds: WfdCreds, timeoutMs: Long, onResult: (JoinOutcome<FakeNet>) -> Unit, onLost: () -> Unit): Cancellable {
            joins += creds to onResult; joinLost = onLost
            return Cancellable { released++ }
        }
        override fun dial(peerId: String, host: String, port: Int, network: FakeNet, onEnd: (DialEnd) -> Unit) { dials += Triple(host, port, onEnd) }
        override fun closeLinkSession(peerId: String) { linkSessionClosed++; link = false }
        override fun resumeLan(peerId: String, overLink: Boolean) { resumes += overLink }
        override fun lanReady(peerId: String) = lan
        override fun linkReady(peerId: String) = link
        override fun traffic(peerId: String) = bytes
        override fun busy(peerId: String) = xferBusy
        override fun log(line: String) { logs += line }
        override fun failed(peerId: String, reason: String, byPeer: Boolean, local: Boolean) { failures += Triple(reason, byPeer, local) }

        fun advance(ms: Long) {
            val end = clock + ms
            while (true) {
                val next = timers.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                clock = next.at
                timers.remove(next)
                next.task()
            }
            clock = end
        }
    }

    private lateinit var env: Env
    private lateinit var m: LinkMachine<FakeNet>
    private val peer = "22222222-2222-4222-8222-222222222222"

    @Before fun setUp() { env = Env(); m = LinkMachine(env) }

    private fun offer(lid: Int = 7, addr: List<String> = listOf("192.168.137.1"), ssid: String? = "DIRECT-AB-AAAAAAAA", type: String = "wfd_legacy") =
        LinkMsg.Offer(lid, type, ssid = ssid, addr = addr, port = 47632, ttl = 120)

    private fun joined(sta: String = "none") =
        JoinOutcome.Joined(FakeNet(1), "192.168.137.23", listOf("192.168.137.1", "192.168.137.254"), 5180, sta, false, 4210, 35)

    /** Offer → joined → dialled → session ready (the old LAN session is retired by Hub). */
    private fun bringUp(sta: String = "none", lid: Int = 7) {
        m.onMessage(peer, offer(lid))
        env.joins.last().second(joined(sta))
        assertEquals(LinkState.DIALING, m.state(peer))
        env.link = true; env.lan = false
        m.onLinkSessionReady(peer)
        assertEquals(LinkState.READY, m.state(peer))
    }

    // ---- PC-initiated ----

    @Test fun pcInitiatedHappyPathThenIdleDone() {
        m.onMessage(peer, offer())
        assertEquals(LinkState.JOINING, m.state(peer))
        assertEquals("DIRECT-AB-AAAAAAAA", env.joins.single().first.ssid)
        env.joins.single().second(joined())
        val ready = env.sent.single() as LinkMsg.Ready
        assertEquals(LinkMsg.Ready(7, "192.168.137.23", 5180, "none"), ready)
        assertEquals("192.168.137.1", env.dials.single().first)
        assertEquals(47632, env.dials.single().second)
        env.link = true; env.lan = false
        m.onLinkSessionReady(peer)
        assertEquals(LinkState.READY, m.state(peer))
        assertEquals(0, env.released)

        env.advance(19_000)
        assertEquals(LinkState.READY, m.state(peer))
        env.advance(2_000)
        assertEquals(LinkState.IDLE, m.state(peer))
        assertEquals(LinkMsg.Done(7, "idle"), env.sent.last())
        assertEquals("link", env.sentOn.last()) // sent before the link session closed
        assertEquals(1, env.released)
        assertEquals(1, env.linkSessionClosed)
        assertEquals(listOf(false), env.resumes) // replaced LAN session redialled
        assertTrue(env.logs.any { it.startsWith("lid=7 peer=22222222 type=wfd_legacy ev=offer ms=0") })
        assertTrue(env.logs.any { it.startsWith("lid=7 ev=joined ms=") && "freq=5180 sta=none dlg=false api=35" in it })
        assertTrue(env.logs.any { it.startsWith("lid=7 ev=done") && "reason=idle" in it })
    }

    @Test fun joinFailureSendsLinkFailAndReleases() {
        m.onMessage(peer, offer())
        env.joins.single().second(JoinOutcome.Failed("approval_denied"))
        assertEquals(LinkMsg.Fail(7, "approval_denied"), env.sent.single())
        assertEquals(1, env.released)
        assertTrue(env.dials.isEmpty())
        assertEquals(LinkState.IDLE, m.state(peer))
        assertEquals(listOf(false), env.resumes)
    }

    @Test fun dialRetriesRotateCandidatesThenReady() {
        m.onMessage(peer, offer())
        env.joins.single().second(joined())
        env.dials[0].third(DialEnd(connected = false, eperm = false, why = "timeout"))
        assertEquals(1, env.dials.size)
        env.advance(LinkPolicy.DIAL_RETRY_MS)
        assertEquals("192.168.137.254", env.dials[1].first) // gateway fallback after the offered addr
        env.dials[1].third(DialEnd(false, false, "refused"))
        env.advance(LinkPolicy.DIAL_RETRY_MS)
        assertEquals("192.168.137.1", env.dials[2].first)
        env.link = true
        m.onLinkSessionReady(peer)
        assertEquals(LinkState.READY, m.state(peer))
    }

    @Test fun dialExhaustedIsTimeout() {
        m.onMessage(peer, offer(addr = listOf("192.168.137.1")))
        env.joins.single().second(joined())
        repeat(LinkPolicy.DIAL_ATTEMPTS) { i ->
            env.dials[i].third(DialEnd(false, false, "unreachable"))
            env.advance(LinkPolicy.DIAL_RETRY_MS)
        }
        assertEquals(LinkMsg.Fail(7, "timeout"), env.sent.last())
        assertEquals(1, env.released)
        assertEquals(LinkState.IDLE, m.state(peer))
    }

    @Test fun epermIsVpn() {
        m.onMessage(peer, offer())
        env.joins.single().second(joined())
        env.dials[0].third(DialEnd(false, true, "EPERM"))
        assertEquals(LinkMsg.Fail(7, "vpn"), env.sent.last())
        assertEquals(1, env.released)
    }

    @Test fun handshakeFailureAfterConnectIsTimeout() {
        m.onMessage(peer, offer())
        env.joins.single().second(joined())
        env.dials[0].third(DialEnd(true, false, "handshake timeout"))
        assertEquals(LinkMsg.Fail(7, "timeout"), env.sent.last())
    }

    @Test fun wholeLinkTimesOutAt30s() {
        m.onMessage(peer, offer())
        env.advance(LinkPolicy.LINK_READY_MS - 1)
        assertEquals(LinkState.JOINING, m.state(peer))
        env.advance(1)
        assertEquals(LinkMsg.Fail(7, "timeout"), env.sent.single())
        assertEquals(1, env.released)
        // A late join result is ignored.
        env.joins.single().second(joined())
        assertTrue(env.dials.isEmpty())
    }

    @Test fun noAddressIsNoIp() {
        m.onMessage(peer, offer(addr = emptyList()))
        env.joins.single().second(JoinOutcome.Joined(FakeNet(1), "192.168.137.23", emptyList(), 2437, "kept", null, 900, 35))
        assertEquals(LinkMsg.Fail(7, "no_ip"), env.sent.last())
    }

    @Test fun refusalsAndBusy() {
        env.unsupported = "api_level"
        m.onMessage(peer, offer())
        assertEquals(LinkMsg.Fail(7, "api_level"), env.sent.single())
        assertTrue(env.joins.isEmpty())

        env.unsupported = null
        m.onMessage(peer, offer(lid = 8, type = "wfd_go"))
        assertEquals(LinkMsg.Fail(8, "no_go_support"), env.sent.last())

        m.onMessage(peer, offer(lid = 9))
        m.onMessage(peer, offer(lid = 10))
        assertEquals(LinkMsg.Fail(10, "busy"), env.sent.last())
        assertEquals(LinkState.JOINING, m.state(peer))

        m.onMessage(peer, LinkMsg.Request(3, "xfer", 1, listOf("wfd_go")))
        assertEquals(LinkMsg.Fail(3, "no_go_support"), env.sent.last())
    }

    @Test fun ssidMismatchNeverJoins() {
        m.onMessage(peer, offer(ssid = "DIRECT-AB-ZZZZZZZZ"))
        assertTrue(env.joins.isEmpty())
        assertEquals(LinkMsg.Fail(7, "join_failed"), env.sent.single())
        assertTrue(env.logs.any { "ssid_mismatch" in it })
    }

    @Test fun peerFailBeforeReadyIsNotEchoed() {
        m.onMessage(peer, offer())
        m.onMessage(peer, LinkMsg.Fail(7, "user"))
        assertTrue(env.sent.isEmpty())
        assertEquals(1, env.released)
        assertEquals(LinkState.IDLE, m.state(peer))
        // Stale lid is ignored.
        m.onMessage(peer, offer(lid = 11))
        m.onMessage(peer, LinkMsg.Fail(7, "user"))
        assertEquals(LinkState.JOINING, m.state(peer))
    }

    // ---- phone-initiated: hold and release of xfer_offer ----

    @Test fun holdReleasesOnlyWhenNewSessionReady() {
        val settled = mutableListOf<Boolean>()
        assertTrue(m.request(peer, "xfer", 734_003_200) { settled += it })
        val req = env.sent.single() as LinkMsg.Request
        assertEquals(listOf("wfd_legacy"), req.types)
        assertEquals(LinkState.REQUESTED, m.state(peer))
        // A second transfer joins the same upgrade.
        m.request(peer, "xfer", 300_000_000) { settled += it }

        m.onMessage(peer, offer(lid = req.lid))
        env.joins.single().second(joined())
        assertTrue(settled.isEmpty()) // still held while dialling
        env.link = true; env.lan = false
        m.onLinkSessionReady(peer)
        assertEquals(listOf(true, true), settled)
        // Already ready: a new request settles immediately.
        m.request(peer, "xfer", 1) { settled += it }
        assertEquals(listOf(true, true, true), settled)
    }

    @Test fun holdFallsBackOnPeerFail() {
        val settled = mutableListOf<Boolean>()
        m.request(peer, "xfer", 734_003_200) { settled += it }
        val lid = (env.sent.single() as LinkMsg.Request).lid
        m.onMessage(peer, LinkMsg.Fail(lid, "hotspot_active"))
        assertEquals(listOf(false), settled)
        assertEquals(1, env.sent.size) // no echo
        assertEquals(LinkState.IDLE, m.state(peer))
    }

    @Test fun holdFallsBackWhenNoOfferIn30s() {
        val settled = mutableListOf<Boolean>()
        m.request(peer, "mirror", 0) { settled += it }
        env.advance(LinkPolicy.LINK_READY_MS)
        assertEquals(listOf(false), settled)
        assertEquals("timeout", (env.sent.last() as LinkMsg.Fail).reason)
    }

    @Test fun requestWithoutCapabilityOrSessionSettlesFalse() {
        val settled = mutableListOf<Boolean>()
        env.unsupported = "api_level"
        assertFalse(m.request(peer, "xfer", 1) { settled += it })
        env.unsupported = null
        env.sessionUp = false
        assertFalse(m.request(peer, "xfer", 1) { settled += it })
        assertEquals(listOf(false, false), settled)
        assertEquals(LinkState.IDLE, m.state(peer))
    }

    @Test fun lidsIncreasePerPeer() {
        m.request(peer, "user", 0)
        val a = (env.sent.last() as LinkMsg.Request).lid
        m.end(peer)
        m.request(peer, "user", 0)
        val b = (env.sent.last() as LinkMsg.Request).lid
        assertTrue(b > a)
    }

    // ---- teardown and the replaced-session redial ----

    @Test fun peerDoneWithStaKeptRedialsWhileLinkOpen() {
        bringUp(sta = "kept")
        m.onMessage(peer, LinkMsg.Done(7, "idle"))
        assertEquals(listOf(true), env.resumes)
        assertFalse(env.sent.any { it is LinkMsg.Done })
        // The peer closes the link session with bye replaced before our LAN dial lands: nothing breaks.
        env.link = false
        m.onLinkSessionEnded(peer)
        env.advance(LinkPolicy.BACK_DIAL_WAIT_MS)
        assertEquals(1, env.released)
        assertEquals(listOf(true, false), env.resumes)
        assertEquals(LinkState.IDLE, m.state(peer))
    }

    @Test fun unsolicitedDesktopOfferWithHighLid() {
        val lid = 0x40000000 // desktops pick lids from 2^30 (§10.1)
        m.onMessage(peer, offer(lid = lid))
        assertEquals(LinkState.JOINING, m.state(peer))
        env.joins.single().second(joined())
        assertEquals(lid, (env.sent.single() as LinkMsg.Ready).lid)
    }

    @Test fun failureReportedForLocalRequestsOnly() {
        m.request(peer, "xfer", 734_003_200)
        m.onMessage(peer, LinkMsg.Fail((env.sent.single() as LinkMsg.Request).lid, "no_go_support"))
        m.onMessage(peer, offer(lid = 0x40000001))
        env.joins.single().second(JoinOutcome.Failed("join_failed"))
        assertEquals(listOf(Triple("no_go_support", true, true), Triple("join_failed", false, false)), env.failures)
        assertEquals(com.shazan.airbridge.R.string.lk_no_go, LinkPolicy.failRes("no_go_support"))
    }

    @Test fun peerDoneRedialsReplacedLanSession() {
        bringUp()
        m.onMessage(peer, LinkMsg.Done(7, "user"))
        assertEquals(LinkState.IDLE, m.state(peer))
        assertFalse(env.sent.any { it is LinkMsg.Done }) // not echoed
        assertEquals(1, env.released)
        assertEquals(listOf(false), env.resumes)
    }

    @Test fun staKeptMakesBeforeBreakBack() {
        bringUp(sta = "kept")
        env.advance(LinkPolicy.IDLE_MS + 1_000)
        assertEquals(LinkState.TEARDOWN, m.state(peer))
        // §10.1 (amended): link_done first, on the still-open link session, then redial the LAN over it.
        assertEquals(LinkMsg.Done(7, "idle"), env.sent.last())
        assertEquals("link", env.sentOn.last())
        assertEquals(listOf(true), env.resumes) // LAN dial allowed while the link session is ready
        assertEquals(0, env.released)
        assertEquals(0, env.linkSessionClosed)
        // LAN session ready → Hub retires the link session (bye replaced); then the network is released.
        env.lan = true
        m.onLanReady(peer)
        assertEquals(1, env.sent.count { it is LinkMsg.Done })
        assertEquals(1, env.released)
        assertEquals(listOf(true, false), env.resumes)
        assertEquals(LinkState.IDLE, m.state(peer))
    }

    @Test fun staKeptButLanSilentGivesUpAfterWait() {
        bringUp(sta = "kept")
        m.end(peer, "user")
        assertEquals(LinkMsg.Done(7, "user"), env.sent.last())
        assertEquals("link", env.sentOn.last())
        env.advance(LinkPolicy.BACK_DIAL_WAIT_MS - 1)
        assertEquals(0, env.released)
        env.advance(1) // 3 s window over: close the link session (bye replaced) and release
        assertEquals(1, env.linkSessionClosed)
        assertEquals(1, env.released)
        assertEquals(LinkState.IDLE, m.state(peer))
    }

    @Test fun trafficExtendsTtlThenIdleEnds() {
        bringUp()
        // Bytes flow for 200 s: well past ttl=120.
        repeat(200) { env.bytes += 1_000_000; env.advance(1_000) }
        assertEquals(LinkState.READY, m.state(peer))
        env.advance(LinkPolicy.IDLE_MS + 1_000)
        assertEquals(LinkMsg.Done(7, "idle"), env.sent.last())
    }

    @Test fun busyTransferCountsAsActivity() {
        bringUp()
        env.xferBusy = true // e.g. waiting on the PC user; counts as activity
        env.advance(119_000)
        assertEquals(LinkState.READY, m.state(peer))
        env.xferBusy = false
        env.advance(LinkPolicy.IDLE_MS + 1_000)
        assertEquals("idle", (env.sent.last() as LinkMsg.Done).reason)
    }

    @Test fun networkLostWhileReady() {
        bringUp()
        env.link = false
        env.joinLost!!()
        assertEquals(LinkMsg.Done(7, "lost"), env.sent.last())
        assertEquals(1, env.released)
        assertEquals(listOf(false), env.resumes)
    }

    @Test fun linkSessionDroppedWhileReady() {
        bringUp()
        env.link = false
        m.onLinkSessionEnded(peer)
        assertEquals(LinkState.IDLE, m.state(peer))
        assertEquals(1, env.released)
        assertEquals(listOf(false), env.resumes)
    }

    @Test fun networkLostWhileJoiningFails() {
        m.onMessage(peer, offer())
        env.joins.single().second(joined())
        env.joinLost!!()
        assertEquals(LinkMsg.Fail(7, "join_failed"), env.sent.last())
    }

    @Test fun endAllReleasesEverything() {
        m.onMessage(peer, offer())
        m.onMessage("other-peer", offer(lid = 1))
        m.endAll("user")
        assertEquals(2, env.released)
        assertNull(m.lid(peer))
        assertTrue(m.activePeers().isEmpty())
    }

    // ---- policy ----

    @Test fun policy() {
        val caps = setOf("clip", "xfer", "wfd_host")
        assertTrue(LinkPolicy.shouldUpgrade("xfer", 734_003_200, false, caps, "lan", true))
        assertFalse(LinkPolicy.shouldUpgrade("xfer", 200_000_000, false, caps, "lan", true))
        assertTrue(LinkPolicy.shouldUpgrade("xfer", 1, true, caps, "lan", true)) // Send fast
        assertTrue(LinkPolicy.shouldUpgrade("mirror", 0, false, caps, "lan", true))
        assertFalse(LinkPolicy.shouldUpgrade("xfer", 734_003_200, false, caps, "lan", true, auto = false))
        assertTrue(LinkPolicy.shouldUpgrade("xfer", 1, true, caps, "lan", true, auto = false))
        assertFalse(LinkPolicy.shouldUpgrade("xfer", 734_003_200, false, setOf("xfer"), "lan", true))
        assertFalse(LinkPolicy.shouldUpgrade("xfer", 734_003_200, false, caps, "wfd", true))
        assertFalse(LinkPolicy.shouldUpgrade("xfer", 734_003_200, false, caps, "nearby", true))
        assertFalse(LinkPolicy.shouldUpgrade("xfer", 734_003_200, true, caps, "lan", false))
    }
}
