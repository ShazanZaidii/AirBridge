package com.shazan.airbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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


class ShareActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // REPLACE THE PREFS READ WITH THIS LINE:
        val urls = AirBridgeService.getActiveUrls()

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

        if (targetUrl != null && urls.contains(targetUrl)) {
            // A specific Direct Share target was tapped; execute immediately
            executeShare(targetUrl)
            finish()
        } else {
            // Generic App Icon was tapped; show the Compose BottomSheet
            if (urls.isEmpty()) {
                Toast.makeText(this, "No devices connected", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            setContent {
                MaterialTheme {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    ModalBottomSheet(
                        onDismissRequest = { finish() },
                        sheetState = sheetState,
                        containerColor = Color(0xFF1E1E1E)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
                        ) {
                            Text(
                                "Send via AirBridge",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            urls.forEach { url ->
                                val deviceName = Uri.parse(url).getQueryParameter("name") ?: "Device"
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .clickable {
                                            executeShare(url)
                                            finish()
                                        }
                                ) {
                                    Text(
                                        text = deviceName,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun executeShare(targetUrl: String) {
        val safeEncoded = android.util.Base64.encodeToString(
            targetUrl.toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        ).replace("=", "")
        androidx.core.content.pm.ShortcutManagerCompat.reportShortcutUsed(this, "device_$safeEncoded")

        val uris = mutableListOf<Uri>()
        val action = intent?.action

        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            list?.let { uris.addAll(it) }
        } else if (action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { uris.add(it) }
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val deviceName = Uri.parse(targetUrl).getQueryParameter("name") ?: "Device"

        if (uris.isNotEmpty()) {
            Toast.makeText(this, "Sending ${uris.size} file(s) to $deviceName...", Toast.LENGTH_SHORT).show()

            val serviceIntent = Intent(this, AirBridgeService::class.java).apply {
                this.action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra("FILE_URIS", ArrayList(uris))
                putExtra("TARGET_URL", targetUrl)

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
}