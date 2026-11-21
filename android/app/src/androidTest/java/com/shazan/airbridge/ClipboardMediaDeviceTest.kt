package com.shazan.airbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shazan.airbridge.clipboard.ClipboardSync
import com.shazan.airbridge.connection.ConnectionHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Clipboard images on a real Android runtime (docs/protocol.md 5.1). Needs a computer that is already paired with this
 * phone and connected (the protocol probe, `tools/v2probe.py`). The test that sends needs one with `clip_media`.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardMediaDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private val cm = ctx.getSystemService(ClipboardManager::class.java)

    private fun waitForPeer(): Boolean {
        val end = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < end) {
            if (ConnectionHub.readySessions().any { "clip_media" in it.peerCaps }) return true
            Thread.sleep(500)
        }
        return false
    }

    /** Runs [block] with one of our windows focused (the only time Android lets an app read the clipboard). */
    private fun <T> focused(block: () -> T): T {
        var out: T? = null
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { }
            Thread.sleep(1_500)
            inst.runOnMainSync { out = block() }
        }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    @Test fun aReceivedPictureIsOnThePhonesClipboardAsAnImage() {
        // The probe has just sent from-probe.png (1639 bytes) as a clipboard transfer; it must be on the clipboard.
        val clip = focused { cm.primaryClip }
        assertNotNull("clipboard is empty", clip)
        val item = clip!!.getItemAt(0)
        assertEquals("AirBridge", clip.description.label?.toString())
        assertTrue("not an image: ${clip.description.getMimeType(0)}", clip.description.hasMimeType("image/png"))
        assertEquals("content", item.uri.scheme)
        val bytes = ctx.contentResolver.openInputStream(item.uri)!!.use { it.readBytes() }
        assertEquals(1639, bytes.size)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xFF })
    }

    @Test fun aPictureCopiedOnThePhoneIsSentToTheComputer() {
        // The app must be open for it to connect, and focused for Android to let it read the clipboard.
        ActivityScenario.launch(MainActivity::class.java).use {
            val end = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < end && ConnectionHub.readySessions().none { "clip_media" in it.peerCaps }) Thread.sleep(500)
            assertTrue("no connected peer with clip_media", ConnectionHub.readySessions().any { "clip_media" in it.peerCaps })
            Thread.sleep(1_500)
            val dir = File(ctx.cacheDir, "clipboard/test").apply { mkdirs() }
            val png = File(dir, "from-phone.png")
            png.writeBytes(java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", png)
            inst.runOnMainSync {
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "copied picture", uri))
                ClipboardSync.onFocusedRead(cm.primaryClip, "manual")
            }
            Thread.sleep(4_000) // the transfer runs in the background
        }
    }
}
