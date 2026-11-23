package com.shazan.airbridge.phone.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifBlocksTest {
    private val mayHave = NotifBlocks.make("com.whatsapp", "WhatsApp", "WhatsApp", "You may have new messages")

    @Test fun theExactNotificationIsBlocked() =
        assertTrue(mayHave.matches("com.whatsapp", "WhatsApp", "You may have new messages"))

    @Test fun spacesAndLineBreaksDontMatter() =
        assertTrue(mayHave.matches("com.whatsapp", " WhatsApp ", "You may have\nnew  messages"))

    @Test fun realChatsFromTheSameAppStillComeThrough() {
        assertFalse(mayHave.matches("com.whatsapp", "Aarav", "On my way"))
        assertFalse(mayHave.matches("com.whatsapp", "WhatsApp", "Checking for new messages"))
        assertFalse(mayHave.matches("com.whatsapp", "WhatsApp", "You may have new messages."))
    }

    @Test fun caseCountsOnlyExactText() = assertFalse(mayHave.matches("com.whatsapp", "WhatsApp", "you may have new messages"))

    @Test fun theSameWordsFromAnotherAppAreNotBlocked() =
        assertFalse(mayHave.matches("com.whatsapp.w4b", "WhatsApp", "You may have new messages"))

    @Test fun aListOfBlocks() {
        val battery = NotifBlocks.make("com.android.systemui", "System UI", "Battery Saver is on", "Some features are limited")
        assertTrue(NotifBlocks.isBlocked(listOf(battery, mayHave), "com.whatsapp", "WhatsApp", "You may have new messages"))
        assertFalse(NotifBlocks.isBlocked(listOf(battery, mayHave), "com.whatsapp", "Mom", "Dinner is ready"))
    }

    @Test fun normalising() {
        assertEquals("a b", NotifBlocks.normal("  a \n\t b "))
        assertEquals("", NotifBlocks.normal(null))
    }
}
