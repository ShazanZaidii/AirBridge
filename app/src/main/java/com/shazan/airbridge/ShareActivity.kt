package com.shazan.airbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {

            val uris = mutableListOf<Uri>()

            // Extract single or multiple URIs
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

            if (uris.isNotEmpty()) {
                Toast.makeText(this, "Sending ${uris.size} file(s) to Mac...", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(this, AirBridgeService::class.java).apply {
                    this.action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra("FILE_URIS", ArrayList(uris))

                    // CRITICAL BUG FIX: Expose URIs to OS via ClipData to persist read permissions
                    val clipData = android.content.ClipData.newRawUri("SharedFiles", uris.first())
                    for (i in 1 until uris.size) {
                        clipData.addItem(android.content.ClipData.Item(uris[i]))
                    }
                    this.clipData = clipData

                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startService(serviceIntent)
            } else if (!text.isNullOrBlank()) {
                Toast.makeText(this, "Sending text to Mac...", Toast.LENGTH_SHORT).show()
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