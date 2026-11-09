package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.NotifFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifFilterTest {
    private val base = NotifFilter.Facts(
        pkg = "com.whatsapp", ownPkg = "com.shazan.airbridge", category = "msg", ongoing = false,
        foregroundService = false, groupSummary = false, mediaStyle = false, importance = 4,
        conversation = true, hasContent = true, isCall = false, visibility = 0, muted = false,
    )
    private val unlocked = NotifFilter.Context(locked = false, hideSensitiveWhenLocked = null)

    private fun reason(f: NotifFilter.Facts, c: NotifFilter.Context = unlocked) = (NotifFilter.decide(f, c) as? Decision.Skip)?.reason

    @Test fun forwardsAnOrdinaryMessage() {
        assertEquals(Decision.Forward, NotifFilter.decide(base, unlocked))
    }

    @Test fun skipsNoise() {
        assertEquals("own", reason(base.copy(pkg = "com.shazan.airbridge")))
        assertEquals("muted", reason(base.copy(muted = true)))
        assertEquals("system", reason(base.copy(pkg = "com.android.systemui")))
        assertEquals("summary", reason(base.copy(groupSummary = true)))
        assertEquals("fgs", reason(base.copy(foregroundService = true)))
        assertEquals("media", reason(base.copy(mediaStyle = true)))
        assertEquals("media", reason(base.copy(category = "transport", conversation = false)))
        assertEquals("ongoing", reason(base.copy(ongoing = true)))
        assertEquals("noise", reason(base.copy(category = "progress", conversation = false)))
        assertEquals("empty", reason(base.copy(hasContent = false)))
    }

    @Test fun silentChannelsOnlyForConversations() {
        assertEquals("silent", reason(base.copy(importance = NotifFilter.IMPORTANCE_LOW, conversation = false, category = null)))
        assertEquals("silent", reason(base.copy(importance = NotifFilter.IMPORTANCE_MIN, conversation = false, category = null)))
        assertEquals(Decision.Forward, NotifFilter.decide(base.copy(importance = NotifFilter.IMPORTANCE_LOW), unlocked))
        assertEquals(Decision.Forward, NotifFilter.decide(base.copy(importance = NotifFilter.IMPORTANCE_DEFAULT, conversation = false), unlocked))
    }

    @Test fun callsGoToTheCallTrackerEvenWhenMutedOrOngoing() {
        assertEquals(Decision.Call, NotifFilter.decide(base.copy(isCall = true, ongoing = true, muted = true, pkg = "com.google.android.dialer"), unlocked))
        // …but never our own.
        assertEquals("own", reason(base.copy(isCall = true, pkg = "com.shazan.airbridge")))
    }

    @Test fun lockScreenPrivacy() {
        val lockedHide = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = true)
        val lockedShow = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = false)
        val lockedUnknown = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = null)
        val d = NotifFilter.decide(base, lockedHide)
        assertTrue(d is Decision.Skip && d.later && d.reason == "locked")
        assertEquals(Decision.Forward, NotifFilter.decide(base, lockedShow))
        assertEquals(Decision.Forward, NotifFilter.decide(base, lockedUnknown))
        val secret = NotifFilter.decide(base.copy(visibility = NotifFilter.VISIBILITY_SECRET), lockedUnknown)
        assertTrue(secret is Decision.Skip && secret.later)
        // Unlocked: hiding doesn't apply.
        assertEquals(Decision.Forward, NotifFilter.decide(base.copy(visibility = NotifFilter.VISIBILITY_SECRET), NotifFilter.Context(false, true)))
    }
}
