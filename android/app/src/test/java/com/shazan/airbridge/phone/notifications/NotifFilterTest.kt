package com.shazan.airbridge.phone.notifications

import com.shazan.airbridge.phone.notifications.NotifFilter.Decision
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
        assertEquals(Decision.Forward(), NotifFilter.decide(base, unlocked))
    }

    private fun kind(f: NotifFilter.Facts) = (NotifFilter.decide(f, unlocked) as? Decision.Forward)?.kind

    @Test fun keepsOnlyWhatTheShadeCannotMirror() {
        assertEquals("muted", reason(base.copy(muted = true)))
        assertEquals("summary", reason(base.copy(groupSummary = true)))
        assertEquals("empty", reason(base.copy(hasContent = false)))
        assertEquals("empty", reason(base.copy(hasContent = false, ongoing = true)))
    }

    @Test fun statusNotificationsAreListedButNeverAlert() {
        // "VPN is active", USB, music, downloads: ongoing.
        assertEquals(NotifFilter.KIND_ONGOING, kind(base.copy(ongoing = true)))
        assertEquals(NotifFilter.KIND_ONGOING, kind(base.copy(foregroundService = true)))
        assertEquals(NotifFilter.KIND_ONGOING, kind(base.copy(mediaStyle = true)))
        assertEquals(NotifFilter.KIND_ONGOING, kind(base.copy(category = "transport", conversation = false)))
        assertEquals(NotifFilter.KIND_ONGOING, kind(base.copy(pkg = "android", ongoing = true)))
        // System chatter and status categories that can be swiped away: silent.
        assertEquals(NotifFilter.KIND_SILENT, kind(base.copy(pkg = "com.android.systemui")))
        assertEquals(NotifFilter.KIND_SILENT, kind(base.copy(category = "progress", conversation = false)))
        assertEquals(NotifFilter.KIND_ALERTING, kind(base))
        // AirBridge's own ("Connected · <the Mac's name>"): listed, never alerting.
        assertEquals(NotifFilter.KIND_ONGOING, kind(base.copy(pkg = "com.shazan.airbridge", ongoing = true, foregroundService = true)))
        assertEquals(NotifFilter.KIND_SILENT, kind(base.copy(pkg = "com.shazan.airbridge")))
    }

    @Test fun silentChannelsOnlyAlertForConversations() {
        assertEquals(NotifFilter.KIND_SILENT, kind(base.copy(importance = NotifFilter.IMPORTANCE_LOW, conversation = false, category = null)))
        assertEquals(NotifFilter.KIND_SILENT, kind(base.copy(importance = NotifFilter.IMPORTANCE_MIN, conversation = false, category = null)))
        assertEquals(Decision.Forward(), NotifFilter.decide(base.copy(importance = NotifFilter.IMPORTANCE_LOW), unlocked))
        assertEquals(Decision.Forward(), NotifFilter.decide(base.copy(importance = NotifFilter.IMPORTANCE_DEFAULT, conversation = false), unlocked))
    }

    @Test fun lockedStatusFollowsTheLockScreenToo() {
        val lockedHide = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = true)
        assertEquals(Decision.ForwardHidden(NotifFilter.KIND_ONGOING), NotifFilter.decide(base.copy(ongoing = true), lockedHide))
    }

    @Test fun callsGoToTheCallTrackerEvenWhenMutedOrOngoing() {
        assertEquals(Decision.Call, NotifFilter.decide(base.copy(isCall = true, ongoing = true, muted = true, pkg = "com.google.android.dialer"), unlocked))
        // …but never our own: that is listed like any other of ours.
        assertEquals(NotifFilter.KIND_SILENT, kind(base.copy(isCall = true, pkg = "com.shazan.airbridge")))
    }

    @Test fun lockScreenPrivacy() {
        val lockedHide = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = true)
        val lockedShow = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = false)
        val lockedUnknown = NotifFilter.Context(locked = true, hideSensitiveWhenLocked = null)
        // Hidden contents on the lock screen: forwarded with the contents hidden, not held back.
        assertEquals(Decision.ForwardHidden(), NotifFilter.decide(base, lockedHide))
        assertEquals(Decision.Forward(), NotifFilter.decide(base, lockedShow))
        assertEquals(Decision.Forward(), NotifFilter.decide(base, lockedUnknown))
        val secret = NotifFilter.decide(base.copy(visibility = NotifFilter.VISIBILITY_SECRET), lockedUnknown)
        assertTrue(secret is Decision.Skip && secret.later)
        // Unlocked: hiding doesn't apply.
        assertEquals(Decision.Forward(), NotifFilter.decide(base.copy(visibility = NotifFilter.VISIBILITY_SECRET), NotifFilter.Context(false, true)))
    }
}
