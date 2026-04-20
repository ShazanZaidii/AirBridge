package com.shazan.airbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.content.ClipboardManager

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
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action

        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        val urls = prefs.getStringSet("WS_URLS", emptySet()) ?: emptySet()

        var targetUrl = intent?.getStringExtra("TARGET_URL")

        // Android Direct Share passes the Shortcut ID instead of custom extras
        if (targetUrl == null) {
            val shortcutId = intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
            targetUrl = urls.firstOrNull { url ->
                val safeEncoded = android.util.Base64.encodeToString(
                    url.toByteArray(),
                    android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                ).replace("=", "")
                "device_$safeEncoded" == shortcutId
            }
        }

        // Fallback to first available ONLY if the generic app icon was tapped
        if (targetUrl == null || !urls.contains(targetUrl)) {
            targetUrl = urls.firstOrNull()
        }

        // Tell Android you just used this target so it maintains its top-row rank
        targetUrl?.let { url ->
            val safeEncoded = android.util.Base64.encodeToString(
                url.toByteArray(),
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            ).replace("=", "")
            androidx.core.content.pm.ShortcutManagerCompat.reportShortcutUsed(this, "device_$safeEncoded")
        }

        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {

            val uris = mutableListOf<Uri>()

            if (action == Intent.ACTION_SEND_MULTIPLE) {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list?.let { uris.addAll(it) }
            } else {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { uris.add(it) }
            }

            val text = intent.getStringExtra(Intent.EXTRA_TEXT)

            // Extract the actual device name from the URL
            val deviceName = targetUrl?.let {
                Uri.parse(it).getQueryParameter("name")
            } ?: "Device"

            if (uris.isNotEmpty()) {
                val msg = if (targetUrl != null) {
                    "Sending ${uris.size} file(s) to $deviceName..."
                } else {
                    "Sending to $deviceName"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

                val serviceIntent = Intent(this, AirBridgeService::class.java).apply {
                    this.action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra("FILE_URIS", ArrayList(uris))
                    if (targetUrl != null) putExtra("TARGET_URL", targetUrl)

                    val clipData = android.content.ClipData.newRawUri("SharedFiles", uris.first())
                    for (i in 1 until uris.size) {
                        clipData.addItem(android.content.ClipData.Item(uris[i]))
                    }
                    this.clipData = clipData

                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startService(serviceIntent)
            } else if (!text.isNullOrBlank()) {
                Toast.makeText(this, "Broadcasting text...", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(this, AirBridgeService::class.java).apply {
                    putExtra("SEND_TEXT", text)
                }
                startService(serviceIntent)
            } else {
                Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}