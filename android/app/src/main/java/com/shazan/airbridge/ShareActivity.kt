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

        if (intent?.action == Intent.ACTION_SEND) {
            // 1. Safely extract the file URI
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            // 2. Extract plain text (if sharing text instead of a file)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)

            if (uri != null) {
                Toast.makeText(this, "Sending file to Mac...", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(this, AirBridgeService::class.java).apply {
                    action = Intent.ACTION_SEND
                    data = uri // Pass as data, not as a String extra
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // CRITICAL
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