package com.shazan.airbridge.clipboard

import com.shazan.airbridge.transfer.Saver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where clipboard images and files from other devices are kept (docs/protocol.md 5.1): a private cache folder, not
 * Downloads. Each transfer gets its own sub-folder so two clipboard files with the same name never collide. Everything
 * here takes plain folders, so it can be unit-tested without Android.
 */
internal object ClipStore {
    const val DIR = "clipboard"
    const val DAY_MS = 24L * 60 * 60 * 1000

    fun root(cacheDir: File) = File(cacheDir, DIR)

    /** A new file for one received clipboard file. [taken] holds the names already used by this transfer. */
    fun create(cacheDir: File, key: String, rawName: String, taken: MutableSet<String>): Saver.Target {
        val dir = File(root(cacheDir), key).apply { mkdirs() }
        val clean = Saver.sanitize(rawName)
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        var name = clean
        var n = 1
        while (name in taken || File(dir, name).exists()) { n++; name = clean.substring(0, dot) + " ($n)" + clean.substring(dot) }
        taken.add(name)
        val f = File(dir, name)
        if (f.canonicalFile.parentFile != dir.canonicalFile) throw IOException("bad path")
        return Saver.Target(FileOutputStream(f).buffered(256 * 1024), null, f, name)
    }

    /** Deletes clipboard data older than [maxAgeMs] (a day by default). */
    fun purgeOld(cacheDir: File, now: Long = System.currentTimeMillis(), maxAgeMs: Long = DAY_MS) {
        root(cacheDir).listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.deleteRecursively() }
    }
}
