package com.shazan.airbridge

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class AirBridgeService : Service() {

    private var incomingFileUri: android.net.Uri? = null
    private val transferQueue = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val activeSockets = ConcurrentHashMap<String, WebSocket>()
    private var savedUrls = setOf<String>()

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private lateinit var clipboardManager: ClipboardManager

    private var lastClipboardText: String = ""
    private var isUpdatingFromDevice = false
    private var hasShownAccessibilityToast = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var closeZoneView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val bubbleSize = 130
    private var isHoveringClose = false

    // --- NEW TILE STATE VARIABLES ---
    private var isPositionLocked = true
    private var menuView: View? = null

    private val autoLockRunnable = Runnable {
        isPositionLocked = true
        showToast("Tile Position Locked")
        mainHandler.post {
            floatingView?.alpha = 0.3f
            // Cast to FrameLayout to access getChildAt
            ((floatingView as? FrameLayout)?.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(Color.parseColor("#B0B0B0"))
        }
    }

    private var isReceivingFile = false
    private var incomingFileName = ""
    private var incomingFileOutputStream: java.io.OutputStream? = null
    private var incomingTotalFiles = 0
    private var incomingFilesReceived = 0
    private var incomingTotalBytes = 0L
    private var incomingReceivedBytes = 0L
    private var incomingStartTime = 0L
    private var incomingLastUpdate = 0L

    private val OUTGOING_NOTIF_ID = 2003

    // For P2P:
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.shazan.airbridge.p2p"
    private var p2pConnectedEndpoints = ConcurrentHashMap<String, String>() // Maps ID -> Name
    private var pendingEndpoints = ConcurrentHashMap<String, String>() // Temp storage during handshake

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = String(payload.asBytes()!!)
                    if (text.startsWith("CLIP:")) {
                        val clipText = text.removePrefix("CLIP:")
                        if (clipText != lastClipboardText) {
                            isUpdatingFromDevice = true
                            mainHandler.post {
                                val clip = ClipData.newPlainText("AirBridge", clipText)
                                clipboardManager.setPrimaryClip(clip)
                                lastClipboardText = clipText
                                isUpdatingFromDevice = false
                            }
                        }
                    }
                }
                Payload.Type.FILE -> {
                    val payloadFile = payload.asFile()?.asJavaFile()
                    if (payloadFile != null && payloadFile.exists()) {
                        transferQueue.execute {
                            val fileName = "AirBridge_P2P_${System.currentTimeMillis()}"
                            val ext = ".dat"

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val contentValues = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$fileName$ext")
                                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/AirBridge Transfers")
                                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                uri?.let { destUri ->
                                    contentResolver.openOutputStream(destUri)?.use { out ->
                                        payloadFile.inputStream().use { input -> input.copyTo(out) }
                                    }
                                    contentValues.clear()
                                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                                    contentResolver.update(destUri, contentValues, null, null)
                                }
                            } else {
                                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AirBridge Transfers")
                                dir.mkdirs()
                                payloadFile.copyTo(java.io.File(dir, "$fileName$ext"), overwrite = true)
                            }

                            payloadFile.delete()
                            mainHandler.post { Toast.makeText(applicationContext, "P2P File Saved to Downloads", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                mainHandler.post { Toast.makeText(applicationContext, "P2P Transfer Complete", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingEndpoints[endpointId] = info.endpointName
            Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val name = pendingEndpoints[endpointId] ?: "Android Device"
                p2pConnectedEndpoints[endpointId] = name
                pendingEndpoints.remove(endpointId)
                broadcastConnectionState()
                showToast("Connected to $name via P2P")
            }
        }

        override fun onDisconnected(endpointId: String) {
            p2pConnectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            broadcastConnectionState()
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: AirBridgeService? = null

        fun getActiveUrls(): Set<String> {
            return instance?.activeSockets?.keys?.map {
                if (it.endsWith("/")) it.dropLast(1) else it
            }?.toSet() ?: emptySet()
        }

        fun broadcastCommand(command: String) {
            instance?.activeSockets?.values?.forEach { it.send(command) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, createNotification())
        }

        if (Settings.canDrawOverlays(this)) setupFloatingButton()
        setupClipboardListener()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val disconnectUrl = intent?.getStringExtra("DISCONNECT_URL")
        if (disconnectUrl != null) {
            activeSockets[disconnectUrl]?.close(1000, "User disconnected")
            activeSockets.remove(disconnectUrl)
            savedUrls = savedUrls - disconnectUrl
            updateDirectShareShortcuts(savedUrls)
            return START_STICKY
        }

        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        val autoConnect = prefs.getBoolean("AUTO_CONNECT", true)

        val disconnectP2p = intent?.getStringExtra("DISCONNECT_P2P")
        if (disconnectP2p != null) {
            Nearby.getConnectionsClient(this).disconnectFromEndpoint(disconnectP2p)
            p2pConnectedEndpoints.remove(disconnectP2p)
            broadcastConnectionState()
            return START_STICKY
        }

        val urls = intent?.getStringArrayListExtra("WS_URLS")
        if (urls != null) {
            savedUrls = urls.toSet()
            updateDirectShareShortcuts(savedUrls)

            val currentActiveUrls = activeSockets.keys.toList()
            for (url in currentActiveUrls) {
                if (!savedUrls.contains(url)) {
                    activeSockets[url]?.close(1000, "Replaced by new connection")
                    activeSockets.remove(url)
                }
            }

            if (autoConnect) {
                savedUrls.forEach { url ->
                    if (!activeSockets.containsKey(url)) {
                        connectWebSocket(url)
                    }
                }
            }
        }

        val textToSend = intent?.getStringExtra("SEND_TEXT")
        if (textToSend != null) {
            if (activeSockets.isNotEmpty()) {
                activeSockets.values.forEach { it.send(textToSend) }
                showToast("Copied to Device(s)!")
            } else {
                showToast("No Devices connected")
            }
        }

        val action = intent?.action
        val targetUrl = intent?.getStringExtra("TARGET_URL")

        when (action) {
            "START_P2P_RECEIVE" -> {
                startP2PAdvertising()
                return START_STICKY
            }
            "STOP_P2P_RECEIVE" -> {
                Nearby.getConnectionsClient(this).stopAdvertising()
                return START_STICKY
            }
            "CONNECT_P2P_ENDPOINT" -> {
                val endpointId = intent.getStringExtra("ENDPOINT_ID")
                if (endpointId != null) {
                    Nearby.getConnectionsClient(this)
                        .requestConnection(android.os.Build.MODEL, endpointId, connectionLifecycleCallback)
                }
                return START_STICKY
            }
        }

        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val fileUris = mutableListOf<Uri>()

            if (action == Intent.ACTION_SEND) {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uri?.let { fileUris.add(it) }
            } else {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra("FILE_URIS", Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>("FILE_URIS")
                }
                list?.let { fileUris.addAll(it) }
            }

            if (fileUris.isNotEmpty()) {
                if (activeSockets.isNotEmpty()) {
                    streamBatchToMac(fileUris, targetUrl)
                }

                if (p2pConnectedEndpoints.isNotEmpty()) {
                    transferQueue.execute {
                        fileUris.forEach { uri ->
                            try {
                                val pfd = contentResolver.openFileDescriptor(uri, "r")
                                if (pfd != null) {
                                    val payload = Payload.fromFile(pfd)
                                    Nearby.getConnectionsClient(this@AirBridgeService)
                                        .sendPayload(p2pConnectedEndpoints.keys.toList(), payload)
                                }
                            } catch (e: Exception) { Log.e("AirBridge", "P2P File send failed", e) }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    fun broadcastToMac(text: String) {
        activeSockets.values.forEach { it.send(text) }
    }

    private fun startP2PAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this)
            .startAdvertising(android.os.Build.MODEL, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener { Log.d("AirBridge", "P2P Advertising started") }
            .addOnFailureListener { e -> Log.e("AirBridge", "P2P Advertising failed", e) }
    }

    private fun formatEta(etaSeconds: Long): String {
        if (etaSeconds < 60) return "${etaSeconds}s remaining"
        val mins = etaSeconds / 60
        val secs = etaSeconds % 60
        return "${mins}m ${secs}s remaining"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return String.format("%.1f MB/s", mb)
    }

    private fun updateTransferNotification(progress: Int, title: String, text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("transfer_channel", "File Transfers", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, "transfer_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(OUTGOING_NOTIF_ID, notification)
    }

    private fun streamBatchToMac(uris: List<Uri>, targetUrl: String?) {
        transferQueue.execute {
            var targetSocket: WebSocket? = null
            if (targetUrl != null) {
                val baseTarget = targetUrl.substringBefore("?")
                targetSocket = activeSockets.entries.firstOrNull { it.key.startsWith(baseTarget) }?.value
            }
            if (targetSocket == null) {
                targetSocket = activeSockets.values.firstOrNull()
            }
            if (targetSocket == null) {
                showToast("Device disconnected. Aborting.")
                return@execute
            }

            val totalFiles = uris.size
            var filesSent = 0
            var totalBytes = 0L

            uris.forEach { uri ->
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && cursor.moveToFirst()) {
                            totalBytes += cursor.getLong(sizeIndex)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            targetSocket.send("BATCH_START:$totalFiles:$totalBytes")

            var bytesSent = 0L
            val startTime = System.currentTimeMillis()
            var lastNotifTime = 0L

            uris.forEach { uri ->
                var retryCount = 0
                var success = false

                Thread.sleep(50)

                while (!success && retryCount < 3) {
                    try {
                        if (targetUrl != null) {
                            val baseTarget = targetUrl.substringBefore("?")
                            targetSocket = activeSockets.entries.firstOrNull { it.key.startsWith(baseTarget) }?.value
                        } else {
                            targetSocket = activeSockets.values.firstOrNull()
                        }

                        var waitCount = 0
                        while (targetSocket == null && waitCount < 40) {
                            Thread.sleep(50)
                            if (targetUrl != null) {
                                val baseTarget = targetUrl.substringBefore("?")
                                targetSocket = activeSockets.entries.firstOrNull { it.key.startsWith(baseTarget) }?.value
                            } else {
                                targetSocket = activeSockets.values.firstOrNull()
                            }
                            waitCount++
                        }
                        if (targetSocket == null) return@execute

                        var fileName = "SharedFile_${System.currentTimeMillis()}.dat"
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                            }
                        }

                        if (!targetSocket.send("START_FILE:$fileName")) throw Exception("Socket closed at start")

                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            val buffer = ByteArray(256 * 1024)
                            var bytesRead: Int

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val byteString = buffer.toByteString(0, bytesRead)
                                if (!targetSocket!!.send(byteString)) throw Exception("Socket closed mid-transfer")

                                bytesSent += bytesRead
                                val now = System.currentTimeMillis()

                                if (now - lastNotifTime > 1000) {
                                    val elapsed = (now - startTime) / 1000.0
                                    val speed =
                                        if (elapsed > 0) (bytesSent / elapsed).toLong() else 0L
                                    val remainingBytes = totalBytes - bytesSent
                                    val eta = if (speed > 0) remainingBytes / speed else 0L
                                    val progressPct =
                                        if (totalBytes > 0) ((bytesSent.toDouble() / totalBytes) * 100).toInt() else 0

                                    broadcastTransferProgress(
                                        filesSent,
                                        totalFiles,
                                        progressPct,
                                        formatSpeed(speed),
                                        formatEta(eta)
                                    )
                                    updateTransferNotification(
                                        progressPct,
                                        "Sending $totalFiles files to Device",
                                        "${formatSpeed(speed)} • ${formatEta(eta)}"
                                    )

                                    lastNotifTime = now
                                }

                                while ((targetSocket?.queueSize() ?: 0L) > 8_000_000L) {
                                    Thread.sleep(5)
                                }
                            }
                        }

                        if (!targetSocket.send("END_FILE:$fileName")) throw Exception("Socket closed at end")

                        success = true
                        filesSent++

                    } catch (e: Exception) {
                        retryCount++
                        Log.e("AirBridge", "Glitch on $uri, retry ($retryCount/3)", e)
                    }
                }
            }
            showToast("Sent $totalFiles files!")

            getSystemService(NotificationManager::class.java).cancel(OUTGOING_NOTIF_ID)

            mainHandler.postDelayed({ broadcastTransferProgress(-1, -1, 0, "", "") }, 2000)
        }
    }

    private fun broadcastTransferProgress(
        currentFiles: Int,
        totalFiles: Int,
        progressPct: Int,
        speed: String,
        eta: String
    ) {
        val intent = Intent("AIRBRIDGE_TRANSFER_PROGRESS")
            .putExtra("currentFiles", currentFiles)
            .putExtra("totalFiles", totalFiles)
            .putExtra("progressPct", progressPct)
            .putExtra("speed", speed)
            .putExtra("eta", eta)
        sendBroadcast(intent)
    }

    private fun getShortMacName(fullName: String): String {
        return fullName
            .replace("MacBook Pro", "MBP", ignoreCase = true)
            .replace("MacBook Air", "MBA", ignoreCase = true)
            .replace("Mac mini", "McM", ignoreCase = true)
            .replace("Mac Studio", "McS", ignoreCase = true)
            .replace("Mac Pro", "McP", ignoreCase = true)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun updateDirectShareShortcuts(urls: Set<String>) {
        try {
            if (urls.isEmpty()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(this)
                return
            }

            val activeShortcuts = mutableListOf<ShortcutInfoCompat>()

            urls.sorted().forEachIndexed { index, url ->
                val uri = Uri.parse(url)
                val rawName = uri.getQueryParameter("name") ?: "Device $index"
                val shortName = getShortMacName(rawName)

                val safeEncoded = android.util.Base64.encodeToString(
                    url.toByteArray(),
                    android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                ).replace("=", "")
                val safeId = "device_$safeEncoded"

                val iconRes = when {
                    rawName.contains("Mac", ignoreCase = true) -> R.drawable.ic_mac
                    rawName.contains("Windows", ignoreCase = true) || rawName.contains("PC", ignoreCase = true) -> R.drawable.ic_windows
                    else -> R.drawable.ic_phone
                }

                val drawable = androidx.core.content.ContextCompat.getDrawable(this, iconRes)!!
                val bitmap = android.graphics.Bitmap.createBitmap(150, 150, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#2C2C2C")
                    isAntiAlias = true
                }
                canvas.drawCircle(75f, 75f, 75f, bgPaint)

                val pad = 35
                drawable.setBounds(pad, pad, 150 - pad, 150 - pad)
                drawable.draw(canvas)

                val paddedIcon = IconCompat.createWithBitmap(bitmap)

                val shareIntent = Intent(this, ShareActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra("TARGET_URL", url)
                    identifier = safeId
                    data = Uri.parse(url)
                }

                val categoryFile = "com.shazan.airbridge.category.FILE_SHARE_TARGET"
                val categoryText = "com.shazan.airbridge.category.TEXT_SHARE_TARGET"

                val mainComponent = android.content.ComponentName(
                    this@AirBridgeService,
                    ShareActivity::class.java
                )

                val person = androidx.core.app.Person.Builder()
                    .setName(shortName)
                    .setKey(safeId)
                    .setIcon(paddedIcon)
                    .build()

                val shortcut = ShortcutInfoCompat.Builder(this, safeId)
                    .setShortLabel(shortName)
                    .setLongLabel(rawName)
                    .setIcon(paddedIcon)
                    .setCategories(setOf(categoryFile, categoryText))
                    .setIntent(shareIntent)
                    .setPerson(person)
                    .setActivity(mainComponent)
                    .setLongLived(true)
                    .setRank(index)
                    .build()

                activeShortcuts.add(shortcut)
            }

            ShortcutManagerCompat.addDynamicShortcuts(this, activeShortcuts)

            activeShortcuts.forEach { shortcut ->
                for (i in 1..10) {
                    ShortcutManagerCompat.reportShortcutUsed(this, shortcut.id)
                }
            }

            val activeIds = activeShortcuts.map { it.id }
            val allShortcuts = ShortcutManagerCompat.getDynamicShortcuts(this)
            val staleIds = allShortcuts.map { it.id }.filterNot { activeIds.contains(it) }

            if (staleIds.isNotEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(this, staleIds)
            }

        } catch (e: Exception) {
            Log.e("AirBridge", "Failed to update share shortcuts", e)
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectWebSocket(url: String) {
        val request = Request.Builder().url(url).build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSockets[url] = webSocket
                broadcastConnectionState()
                Log.d("AirBridge", "Connected to $url")
                webSocket.send("DEVICE_NAME:${android.os.Build.MODEL}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("BATCH_START:")) {
                    val parts = text.split(":")
                    incomingTotalFiles = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    incomingTotalBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    incomingFilesReceived = 0
                    incomingReceivedBytes = 0L
                    incomingStartTime = System.currentTimeMillis()
                    incomingLastUpdate = 0L
                    updateIncomingTransferUI(false)
                    return
                }

                if (text.startsWith("START_FILE:")) {
                    if (isReceivingFile) {
                        try { incomingFileOutputStream?.close() } catch (e: Exception) {}
                        incomingFileOutputStream = null

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && incomingFileUri != null) {
                            try {
                                val contentValues = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                                }
                                contentResolver.update(incomingFileUri!!, contentValues, null, null)
                            } catch (e: Exception) { Log.e("AirBridge", "MediaStore update failed", e) }
                            incomingFileUri = null
                        }
                        incomingFilesReceived++
                    }

                    isReceivingFile = true
                    val rawFileName = text.substringAfter("START_FILE:")

                    incomingFileName = rawFileName.replace(Regex("^[A-Fa-f0-9]{6}_"), "")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = contentResolver
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, incomingFileName)
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/AirBridge Transfers")
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        incomingFileUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        incomingFileOutputStream = incomingFileUri?.let { resolver.openOutputStream(it) }
                    } else {
                        val dir = java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            "AirBridge Transfers"
                        )
                        dir.mkdirs()
                        var file = java.io.File(dir, incomingFileName)

                        var counter = 1
                        val nameWithoutExt = incomingFileName.substringBeforeLast(".")
                        val ext = if (incomingFileName.contains(".")) ".${incomingFileName.substringAfterLast(".")}" else ""

                        while (file.exists()) {
                            file = java.io.File(dir, "${nameWithoutExt}_$counter$ext")
                            counter++
                        }
                        incomingFileName = file.name
                        incomingFileOutputStream = java.io.FileOutputStream(file)
                    }
                    return
                }

                if (text.startsWith("END_FILE:")) {
                    if (!isReceivingFile) return

                    isReceivingFile = false
                    try { incomingFileOutputStream?.close() } catch (e: Exception) {}
                    incomingFileOutputStream = null

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && incomingFileUri != null) {
                        try {
                            val contentValues = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            contentResolver.update(incomingFileUri!!, contentValues, null, null)
                        } catch (e: Exception) { Log.e("AirBridge", "MediaStore update failed", e) }
                        incomingFileUri = null
                    }

                    incomingFilesReceived++

                    if (incomingFilesReceived >= incomingTotalFiles) {
                        mainHandler.postDelayed({
                            updateIncomingTransferUI(true)
                        }, 200)
                    }
                    return
                }

                if (text.startsWith("INP:")) {
                    if (AirBridgeInputService.instance == null) {
                        if (!hasShownAccessibilityToast) {
                            hasShownAccessibilityToast = true
                            mainHandler.post {
                                Toast.makeText(
                                    this@AirBridgeService,
                                    "Enable Accessibility Service in Android Settings to simulate Mouse (or from the App)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        AirBridgeInputService.processCommand(text)
                    }
                    return
                }

                if (text != lastClipboardText) {
                    isUpdatingFromDevice = true
                    mainHandler.post {
                        val clip = ClipData.newPlainText("AirBridge", text)
                        clipboardManager.setPrimaryClip(clip)
                        lastClipboardText = text
                        isUpdatingFromDevice = false
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (isReceivingFile) {
                    try {
                        incomingFileOutputStream?.write(bytes.toByteArray())
                        incomingReceivedBytes += bytes.size

                        val now = System.currentTimeMillis()
                        if (now - incomingLastUpdate > 500) {
                            incomingLastUpdate = now
                            updateIncomingTransferUI(false)
                        }

                        if (incomingTotalBytes > 0L && incomingReceivedBytes >= incomingTotalBytes) {
                            isReceivingFile = false
                            try { incomingFileOutputStream?.close() } catch (e: Exception) {}
                            incomingFileOutputStream = null

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && incomingFileUri != null) {
                                try {
                                    val contentValues = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                                    }
                                    contentResolver.update(incomingFileUri!!, contentValues, null, null)
                                } catch (e: Exception) { Log.e("AirBridge", "MediaStore final update failed", e) }
                                incomingFileUri = null
                            }

                            incomingFilesReceived = incomingTotalFiles
                            mainHandler.postDelayed({
                                updateIncomingTransferUI(true)
                            }, 200)
                        }

                    } catch (e: Exception) {
                        Log.e("AirBridge", "Error writing binary chunk", e)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(url, webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect(url, webSocket)
            }

            private fun handleDisconnect(url: String, dyingSocket: WebSocket) {
                if (activeSockets[url] === dyingSocket) {
                    activeSockets.remove(url)
                    broadcastConnectionState()

                    if (isReceivingFile) {
                        isReceivingFile = false
                        try {
                            incomingFileOutputStream?.close()
                        } catch (e: Exception) {}
                        incomingFileOutputStream = null

                        val notificationManager =
                            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(2002)

                        mainHandler.post {
                            Toast.makeText(
                                this@AirBridgeService,
                                "Transfer interrupted (Device disconnected)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        sendBroadcast(Intent("AIRBRIDGE_TRANSFER_UPDATE").apply {
                            putExtra("isComplete", true)
                        })
                    }

                    scheduleReconnect(url)
                }
            }
        })
    }

    private fun scheduleReconnect(url: String) {
        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        val autoConnect = prefs.getBoolean("AUTO_CONNECT", true)

        if (savedUrls.contains(url) && autoConnect) {
            mainHandler.postDelayed({
                if (!activeSockets.containsKey(url)) {
                    connectWebSocket(url)
                }
            }, 5000)
        }
    }

    private fun setupClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            if (isUpdatingFromDevice) return@addPrimaryClipChangedListener

            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val description = clip.description
                val item = clip.getItemAt(0)

                val isExplicitlyFile =
                    description?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST) == true

                if (isExplicitlyFile && item.uri != null) {
                    val uriScheme = item.uri.scheme
                    if (uriScheme == "content" || uriScheme == "file") {
                        if (activeSockets.isNotEmpty()) {
                            showToast("Select a specific Device using Direct Share")
                        }
                        return@addPrimaryClipChangedListener
                    }
                }

                val newText =
                    item.coerceToText(this)?.toString() ?: return@addPrimaryClipChangedListener

                if (newText != lastClipboardText) {
                    lastClipboardText = newText

                    activeSockets.values.forEach { socket ->
                        socket.send(newText)
                    }

                    if (p2pConnectedEndpoints.isNotEmpty()) {
                        val bytesPayload = Payload.fromBytes("CLIP:$newText".toByteArray())
                        Nearby.getConnectionsClient(this@AirBridgeService)
                            .sendPayload(p2pConnectedEndpoints.keys.toList(), bytesPayload)
                    }
                }
            }
        }
    }

    private fun updateForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    private fun broadcastConnectionState() {
        val intent = Intent("AIRBRIDGE_STATE_UPDATE").apply {
            setPackage(packageName)
        }
        intent.putStringArrayListExtra("ACTIVE_URLS", ArrayList(getActiveUrls()))

        val p2pList = p2pConnectedEndpoints.map { "${it.key}|${it.value}" }
        intent.putStringArrayListExtra("P2P_ENDPOINTS", ArrayList(p2pList))

        sendBroadcast(intent)
        updateForegroundNotification()
    }

    private fun createNotification(): Notification {
        val channelId = "AirBridgeChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Clipboard Sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val activeCount = activeSockets.size
        val (title, text) = if (activeCount > 0) {
            "AirBridge Active" to "Syncing clipboard with $activeCount Device(s)"
        } else {
            "AirBridge Disconnected" to "Not syncing with Devices"
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(this).apply {
            elevation = 0f
            outlineProvider = ViewOutlineProvider.BACKGROUND
            alpha = 0.3f
        }

        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_name)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#B0B0B0"))
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#801A1A1A"))
            }
        }

        val buttonParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        container.addView(button, buttonParams)
        floatingView = container

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false
        var longPressRunnable: Runnable? = null

        floatingView?.setOnTouchListener { view, event ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            val realScreenWidth = metrics.widthPixels
            val realScreenHeight = metrics.heightPixels

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true

                    view.alpha = if (isPositionLocked) 1.0f else 0.8f
                    view.elevation = 15f
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()

                    longPressRunnable = Runnable {
                        isClick = false
                        showMoonMenu()
                    }
                    mainHandler.postDelayed(longPressRunnable!!, 400)

                    if (!isPositionLocked) showCloseZone()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    }

                    if (!isPositionLocked) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager?.updateViewLayout(floatingView, layoutParams)

                        val isNearBottom = event.rawY > (realScreenHeight - 400)
                        val isNearCenter = abs(event.rawX - (realScreenWidth / 2)) < 250

                        if (isNearBottom && isNearCenter) {
                            isHoveringClose = true
                            closeZoneView?.alpha = 1.0f
                            (closeZoneView?.background as? GradientDrawable)?.setColor(Color.parseColor("#E53935"))
                        } else {
                            isHoveringClose = false
                            closeZoneView?.alpha = 0.5f
                            (closeZoneView?.background as? GradientDrawable)?.setColor(Color.parseColor("#80000000"))
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }

                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    view.elevation = 0f
                    hideCloseZone()

                    if (isHoveringClose && !isPositionLocked) {
                        stopSelf()
                        return@setOnTouchListener true
                    }

                    if (isClick) {
                        hideMoonMenu()
                        view.alpha = 0.3f
                        val intent = Intent(this, SyncActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } else if (!isPositionLocked) {
                        val isLeftHalf = (layoutParams.x + (bubbleSize / 2)) < (realScreenWidth / 2)
                        val targetX = if (isLeftHalf) -(bubbleSize / 2) else realScreenWidth - (bubbleSize / 2)

                        val animator = ValueAnimator.ofInt(layoutParams.x, targetX)
                        animator.duration = 250
                        animator.interpolator = DecelerateInterpolator()
                        animator.addUpdateListener { animation ->
                            layoutParams.x = animation.animatedValue as Int
                            windowManager?.updateViewLayout(floatingView, layoutParams)
                        }
                        animator.start()
                    } else {
                        view.alpha = 0.3f
                    }
                    true
                }
                else -> false
            }
        }
        windowManager?.addView(floatingView, layoutParams)
    }

    private fun showMoonMenu() {
        if (menuView != null) return

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }

        // Helper to convert dp to pixels
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 60f
                setStroke(2, Color.parseColor("#333333"))
            }
            setPadding(dp(12), dp(16), dp(12), dp(16))
            elevation = 20f
        }

        val trackpadBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_mac)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                setMargins(0, 0, 0, dp(16))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                hideMoonMenu()
                val intent = Intent(this@AirBridgeService, TrackpadActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }
        }

        val unlockBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#FFD700"))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(56))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                hideMoonMenu()
                isPositionLocked = false
                showToast("Position Unlocked (60s)")

                mainHandler.post {
                    floatingView?.alpha = 0.8f
                    ((floatingView as? FrameLayout)?.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(Color.parseColor("#4285F4"))
                }

                mainHandler.removeCallbacks(autoLockRunnable)
                mainHandler.postDelayed(autoLockRunnable, 60000)
            }
        }

        container.addView(trackpadBtn)
        container.addView(unlockBtn)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val isLeftHalf = (layoutParams.x + (bubbleSize / 2)) < (metrics.widthPixels / 2)

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isLeftHalf) layoutParams.x + bubbleSize + 20 else layoutParams.x - 160
            y = layoutParams.y - 40
        }

        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideMoonMenu()
                true
            } else false
        }

        menuView = container
        windowManager?.addView(menuView, menuParams)

        mainHandler.postDelayed({ hideMoonMenu() }, 4000)
    }

    private fun hideMoonMenu() {
        menuView?.let {
            windowManager?.removeView(it)
            menuView = null
        }
    }

    private fun showCloseZone() {
        if (closeZoneView != null) return

        val container = FrameLayout(this).apply {
            alpha = 0.5f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#80000000"))
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(80, 80, Gravity.CENTER)
        }
        container.addView(icon)

        val closeParams = WindowManager.LayoutParams(
            180, 180,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 150
        }

        closeZoneView = container
        windowManager?.addView(closeZoneView, closeParams)
    }

    private fun hideCloseZone() {
        closeZoneView?.let {
            windowManager?.removeView(it)
            closeZoneView = null
        }
    }

    private fun updateIncomingTransferUI(isComplete: Boolean) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "transfer_channel",
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        if (isComplete) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this, "transfer_channel")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Transfer Complete")
                .setContentText("Received $incomingTotalFiles file(s) from Device")
                .setContentIntent(pendingIntent)
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setAutoCancel(true)

            notificationManager.notify(2002, builder.build())

            mainHandler.post {
                Toast.makeText(
                    this,
                    "Received $incomingTotalFiles files from your Device",
                    Toast.LENGTH_LONG
                ).show()
            }

            sendBroadcast(Intent("AIRBRIDGE_TRANSFER_UPDATE").apply {
                putExtra("isComplete", true)
            })

        } else {
            val progress =
                if (incomingTotalBytes > 0) ((incomingReceivedBytes.toDouble() / incomingTotalBytes) * 100).toInt() else 0
            val elapsed = (System.currentTimeMillis() - incomingStartTime) / 1000.0
            val speedBps = if (elapsed > 0) incomingReceivedBytes / elapsed else 0.0
            val speedMb = String.format("%.1f MB/s", speedBps / (1024 * 1024))

            val remainingBytes = incomingTotalBytes - incomingReceivedBytes
            val etaSecs = if (speedBps > 0) (remainingBytes / speedBps).toInt() else 0
            val etaStr = if (etaSecs > 60) "${etaSecs / 60}m ${etaSecs % 60}s" else "${etaSecs}s"

            val builder = NotificationCompat.Builder(this, "transfer_channel")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Receiving from your Device: $incomingFileName")
                .setContentText("$incomingFilesReceived / $incomingTotalFiles files ($speedMb)")
                .setProgress(100, progress, false)
                .setOngoing(true)

            notificationManager.notify(2002, builder.build())

            sendBroadcast(Intent("AIRBRIDGE_TRANSFER_UPDATE").apply {
                putExtra("isComplete", false)
                putExtra("currentFiles", incomingFilesReceived)
                putExtra("totalFiles", incomingTotalFiles)
                putExtra("speed", speedMb)
                putExtra("eta", etaStr)
                putExtra("progress", progress / 100f)
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        mainHandler.removeCallbacks(autoLockRunnable)
        hideMoonMenu()
        mainHandler.removeCallbacksAndMessages(null)
        activeSockets.values.forEach { it.close(1000, "Service destroyed") }
        activeSockets.clear()
        if (floatingView != null) windowManager?.removeView(floatingView)
        hideCloseZone()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}