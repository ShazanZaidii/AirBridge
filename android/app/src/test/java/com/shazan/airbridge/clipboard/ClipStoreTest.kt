package com.shazan.airbridge.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aReceivedFileLandsInTheCacheNotDownloads() {
        val cache = tmp.newFolder("cache")
        val taken = mutableSetOf<String>()
        val t = ClipStore.create(cache, "in-1", "clipboard.png", taken)
        t.out.write(byteArrayOf(1, 2, 3)); t.out.close()
        assertEquals("clipboard.png", t.name)
        assertEquals(File(cache, "clipboard/in-1/clipboard.png").canonicalPath, t.file!!.canonicalPath)
        assertEquals(listOf<Byte>(1, 2, 3), t.file!!.readBytes().toList())
        assertFalse(t.file!!.path.contains("Download"))
    }

    @Test fun twoFilesWithTheSameNameInOneTransferGetDifferentNames() {
        val cache = tmp.newFolder("cache")
        val taken = mutableSetOf<String>()
        val a = ClipStore.create(cache, "in-1", "photo.jpg", taken); a.out.close()
        val b = ClipStore.create(cache, "in-1", "photo.jpg", taken); b.out.close()
        val c = ClipStore.create(cache, "in-1", "photo.jpg", taken); c.out.close()
        assertEquals(listOf("photo.jpg", "photo (2).jpg", "photo (3).jpg"), listOf(a.name, b.name, c.name))
    }

    @Test fun differentTransfersNeverShareAFolder() {
        val cache = tmp.newFolder("cache")
        val a = ClipStore.create(cache, "in-1", "clipboard.png", mutableSetOf()); a.out.close()
        val b = ClipStore.create(cache, "in-2", "clipboard.png", mutableSetOf()); b.out.close()
        assertEquals("clipboard.png", a.name); assertEquals("clipboard.png", b.name)
        assertTrue(a.file!!.parentFile != b.file!!.parentFile)
    }

    @Test fun aNameThatTriesToLeaveTheFolderIsCleaned() {
        val cache = tmp.newFolder("cache")
        val t = ClipStore.create(cache, "in-1", "../../evil.txt", mutableSetOf()); t.out.close()
        assertEquals(File(cache, "clipboard/in-1").canonicalPath, t.file!!.parentFile!!.canonicalPath)
        assertFalse(t.name.contains(".."))
    }

    @Test fun oldClipboardDataIsDeletedAndRecentDataStays() {
        val cache = tmp.newFolder("cache")
        val old = ClipStore.create(cache, "in-old", "a.png", mutableSetOf()).also { it.out.close() }.file!!
        val new = ClipStore.create(cache, "in-new", "b.png", mutableSetOf()).also { it.out.close() }.file!!
        val now = System.currentTimeMillis()
        old.parentFile!!.setLastModified(now - 2 * ClipStore.DAY_MS)
        new.parentFile!!.setLastModified(now - 1_000)
        ClipStore.purgeOld(cache, now)
        assertFalse(old.exists())
        assertTrue(new.exists())
    }

    @Test fun purgingWithNoCacheFolderIsHarmless() {
        ClipStore.purgeOld(tmp.newFolder("empty"))
    }
}
