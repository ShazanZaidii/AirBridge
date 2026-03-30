package com.shazan.airbridge

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class SyncActivity : Activity() {

    private var hasAttemptedRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasAttemptedRead) {
            hasAttemptedRead = true
            readClipboardAndFinish()
        }
    }

    private fun readClipboardAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this)?.toString()

                if (!text.isNullOrEmpty()) {
                    val serviceIntent = Intent(this, AirBridgeService::class.java).apply {
                        putExtra("SEND_TEXT", text)
                    }
                    startService(serviceIntent)
                } else {
                    Toast.makeText(this, "No text in clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading clipboard", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}