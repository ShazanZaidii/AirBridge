package com.shazan.airbridge.clipboard

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Invisible activity that reads the clipboard while focused and pushes it (tile, notification action,
 * and the fallback when [com.shazan.airbridge.clipboard.ClipFocusWindow] cannot take focus: bubble, `copy`,
 * automatic sync). Android 10+ only lets the focused app read the clipboard.
 */
class SyncActivity : Activity() {
    private var done = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val reason = intent.getStringExtra(ClipboardSync.EXTRA_REASON) ?: "manual"
        ClipboardSync.onFocusedRead(ClipboardSync.readClip(), reason)
        finishQuietly()
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }
}
