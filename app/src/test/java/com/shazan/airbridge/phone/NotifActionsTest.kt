package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.NotifActions.Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifActionsTest {
    @Test fun picksTheReplyAction() {
        val actions = listOf(
            Info(0, "Mark as read", freeFormInput = false, semantic = 2),
            Info(1, "Quick reply", freeFormInput = true),
            Info(2, "Reply", freeFormInput = true, semantic = NotifActions.SEMANTIC_REPLY),
        )
        assertEquals(2, NotifActions.pickReply(actions)!!.index) // the one marked as Reply wins
        assertEquals(1, NotifActions.pickReply(actions.take(2))!!.index)
        assertNull(NotifActions.pickReply(actions.take(1)))
    }

    @Test fun phoneActionBeatsWearableAndSmartRepliesAreIgnored() {
        val actions = listOf(
            Info(0, "Reply", freeFormInput = true, wearable = true),
            Info(3, "Reply", freeFormInput = true),
            Info(4, "Smart reply", freeFormInput = true, contextual = true),
        )
        val r = NotifActions.pickReply(actions)!!
        assertEquals(3, r.index); assertEquals(false, r.wearable)
        // Old apps: reply only in the wearable extender.
        assertEquals(true, NotifActions.pickReply(listOf(actions[0], actions[2]))!!.wearable)
        assertNull(NotifActions.pickReply(listOf(actions[2])))
    }

    @Test fun otherButtonsExcludeInputsWearablesAndSmartActions() {
        val actions = listOf(
            Info(0, "Reply", freeFormInput = true),
            Info(1, " Mark as read ", freeFormInput = false),
            Info(2, "Archive", freeFormInput = false, wearable = true),
            Info(3, "Open map", freeFormInput = false, contextual = true),
            Info(4, "", freeFormInput = false),
            Info(5, "Delete", freeFormInput = false),
            Info(6, "Mute", freeFormInput = false),
            Info(7, "Block", freeFormInput = false),
        )
        val o = NotifActions.others(actions)
        assertEquals(listOf(1 to "Mark as read", 5 to "Delete", 6 to "Mute"), o.map { it.index to it.label })
    }

    @Test fun shortKeysAreStableOpaqueAndDistinct() {
        val k = "0|com.whatsapp|1|null|10123"
        assertEquals(NotifActions.shortKey(k), NotifActions.shortKey(k))
        assertEquals(18, NotifActions.shortKey(k).length)
        assertNotEquals(NotifActions.shortKey(k), NotifActions.shortKey("0|com.whatsapp|2|null|10123"))
        assert(!NotifActions.shortKey(k).contains("whatsapp"))
    }

    @Test fun indexIsBounded() {
        val idx = NotifIndex<Int>(max = 3)
        (1..5).forEach { idx.put("k$it", it) }
        assertEquals(listOf("k3", "k4", "k5"), idx.keys())
        idx.put("k3", 33) // refresh moves it to the end
        idx.put("k6", 6)
        assertEquals(listOf("k5", "k3", "k6"), idx.keys())
        assertEquals(33, idx.get("k3"))
        assertNull(idx.get("k1"))
    }
}
