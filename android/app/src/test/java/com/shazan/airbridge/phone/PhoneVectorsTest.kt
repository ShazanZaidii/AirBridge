package com.shazan.airbridge.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The exact JSON this phone sends for §13, shared with the macOS (tools/notifcheck) and Windows
 * (AirBridge.PhoneTests) parsers through docs/test-vectors/phone-notifs.jsonl.
 */
class PhoneVectorsTest {
    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> json(k.toString()) + ":" + json(x) }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> error("unexpected ${v::class}")
    }

    private val built = listOf(
        NotifWire.post(NotifWire.PhoneNotif(
            key = "9f2c01ab34cd56ef78", pkg = "com.whatsapp", appName = "WhatsApp", title = "Asha", text = "See you at 6",
            ts = 1_762_070_400_000, cat = "msg", conv = "Family",
            msgs = listOf(NotifWire.Msg("Asha", "Hi", 1_762_070_300_000), NotifWire.Msg(null, "Hello", 1_762_070_350_000)),
            canReply = true, replyLabel = "Reply", actions = listOf(NotifWire.Action(1, "Mark as read")),
        )),
        NotifWire.call(NotifWire.Call("c3-mxk2a1", NotifWire.CallState.ACTIVE, "Asha", "+91 98765 43210", 1_762_070_412_000,
            muted = false, speaker = true, can = listOf("end", "mute", "speaker"))),
        NotifWire.state(notifs = false, calls = true, notifsWhy = "no_access"),
        NotifWire.remove("9f2c01ab34cd56ef78"),
    ).map(::json)

    @Test fun matchesSharedVectors() {
        val f = listOf(File("../docs/test-vectors/phone-notifs.jsonl"), File("docs/test-vectors/phone-notifs.jsonl")).first { it.exists() }
        val lines = f.readLines().filter { it.isNotBlank() }
        assertEquals(built.size, lines.size)
        built.zip(lines).forEach { (b, l) -> assertEquals(l, b) }
    }
}
