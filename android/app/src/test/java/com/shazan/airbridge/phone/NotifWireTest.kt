package com.shazan.airbridge.phone

import com.shazan.airbridge.phone.NotifWire.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifWireTest {
    private val n = NotifWire.PhoneNotif(
        key = "abc123", pkg = "com.whatsapp", appName = "WhatsApp", title = "Asha", text = "See you at 6",
        ts = 1_762_070_400_000, cat = "msg", conv = "Family",
        msgs = listOf(NotifWire.Msg("Asha", "Hi", 1L), NotifWire.Msg(null, "Hello", 2L)),
        canReply = true, replyLabel = "Reply", actions = listOf(NotifWire.Action(1, "Mark as read")),
    )

    @Test fun postCarriesEverything() {
        val m = NotifWire.post(n)
        assertEquals("notif_post", m["t"])
        assertEquals("abc123", m["key"])
        assertEquals("com.whatsapp", m["pkg"])
        assertEquals("WhatsApp", m["app"])
        assertEquals("Asha", m["title"])
        assertEquals("See you at 6", m["text"])
        assertEquals(1_762_070_400_000, m["ts"])
        assertEquals("msg", m["cat"])
        assertEquals("Family", m["conv"])
        assertEquals(true, m["reply"])
        assertEquals("Reply", m["reply_label"])
        assertEquals(true, m["alert"])
        @Suppress("UNCHECKED_CAST") val msgs = m["msgs"] as List<Map<String, Any?>>
        assertEquals("Asha", msgs[0]["from"])
        assertEquals(true, msgs[1]["me"])
        assertFalse(msgs[1].containsKey("from"))
        @Suppress("UNCHECKED_CAST") val acts = m["acts"] as List<Map<String, Any?>>
        assertEquals(1, acts[0]["i"]); assertEquals("Mark as read", acts[0]["label"])
        assertFalse(m.containsKey("sub")) // empty fields are left out
    }

    @Test fun postTrimsLongFieldsAndLists() {
        val long = n.copy(
            title = "x".repeat(500), text = "y".repeat(5000),
            msgs = (1..20).map { NotifWire.Msg("p", "m$it", it.toLong()) },
            actions = (0..9).map { NotifWire.Action(it, "a$it") },
            canReply = false, conv = null,
        )
        val m = NotifWire.post(long)
        assertEquals(NotifWire.MAX_TITLE, (m["title"] as String).length)
        assertTrue((m["title"] as String).endsWith("…"))
        assertEquals(NotifWire.MAX_TEXT, (m["text"] as String).length)
        assertEquals(NotifWire.MAX_MSGS, (m["msgs"] as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("m20", ((m["msgs"] as List<Map<String, Any?>>).last())["text"]) // newest kept
        assertEquals(NotifWire.MAX_ACTIONS, (m["acts"] as List<*>).size)
        assertFalse(m.containsKey("reply_label"))
        assertFalse(m.containsKey("conv"))
    }

    @Test fun callStateShape() {
        val c = NotifWire.Call("c1-x", NotifWire.CallState.ACTIVE, "Asha", "+91 98", since = 5L, muted = true, can = listOf("end", "mute"))
        val m = NotifWire.call(c)
        assertEquals("call_state", m["t"]); assertEquals("active", m["state"]); assertEquals(5L, m["since"])
        assertEquals(true, m["muted"]); assertEquals(listOf("end", "mute"), m["can"])
        val ringing = NotifWire.call(c.copy(state = NotifWire.CallState.RINGING, since = null, name = null))
        assertFalse(ringing.containsKey("since")); assertFalse(ringing.containsKey("name"))
    }

    @Test fun stateAndResults() {
        assertEquals(mapOf("t" to "notif_state", "notifs" to false, "calls" to true, "why" to "no_access"), NotifWire.state(false, true, "no_access"))
        assertFalse(NotifWire.state(true, true, "off").containsKey("why"))
        assertEquals("gone", NotifWire.result("k", "reply", false, "gone")["reason"])
        assertFalse(NotifWire.result("k", "reply", true, "gone").containsKey("reason"))
    }

    @Test fun parsesCommands() {
        assertEquals(Command.Dismiss("k1"), NotifWire.parse(mapOf("t" to "notif_dismiss", "key" to "k1")))
        assertEquals(Command.Reply("k1", "on my way"), NotifWire.parse(mapOf("t" to "notif_reply", "key" to "k1", "text" to "  on my way ")))
        assertEquals(Command.Act("k1", 2), NotifWire.parse(mapOf("t" to "notif_action", "key" to "k1", "i" to 2)))
        assertEquals(Command.Open("k1"), NotifWire.parse(mapOf("t" to "notif_open", "key" to "k1")))
        assertEquals(Command.Subscribe(false, true), NotifWire.parse(mapOf("t" to "notif_sub", "notifs" to false)))
        assertEquals(Command.CallAction("c1", "answer"), NotifWire.parse(mapOf("t" to "call_action", "id" to "c1", "op" to "answer")))
    }

    @Test fun rejectsMalformedCommands() {
        assertNull(NotifWire.parse(mapOf("t" to "notif_reply", "key" to "k1", "text" to "   ")))
        assertNull(NotifWire.parse(mapOf("t" to "notif_reply", "text" to "hi")))
        assertNull(NotifWire.parse(mapOf("t" to "notif_dismiss", "key" to "k".repeat(200))))
        assertNull(NotifWire.parse(mapOf("t" to "notif_action", "key" to "k", "i" to -1)))
        assertNull(NotifWire.parse(mapOf("t" to "notif_action", "key" to "k", "i" to "2")))
        assertNull(NotifWire.parse(mapOf("t" to "call_action", "id" to "c1", "op" to "dial")))
        assertNull(NotifWire.parse(mapOf("t" to "notif_post", "key" to "k"))) // source → sink type, not a command
        assertNull(NotifWire.parse(mapOf("t" to "something_new")))
        val r = NotifWire.parse(mapOf("t" to "notif_reply", "key" to "k", "text" to "z".repeat(10_000))) as Command.Reply
        assertEquals(NotifWire.MAX_REPLY, r.text.length)
    }

    @Test fun categories() {
        assertEquals("msg", NotifWire.shortCategory(null, conversation = true))
        assertEquals("email", NotifWire.shortCategory("email", false))
        assertEquals("call", NotifWire.shortCategory("missed_call", false))
        assertEquals("other", NotifWire.shortCategory("promo", false))
    }
}
