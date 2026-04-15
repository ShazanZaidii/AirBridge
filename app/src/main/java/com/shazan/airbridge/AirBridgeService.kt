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
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.driftui.core.Time
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    private var isUpdatingFromMac = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var closeZoneView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val bubbleSize = 130
    private var isHoveringClose = false

    // Incoming File Tracking
    private var isReceivingFile = false
    private var incomingFileName = ""
    private var incomingFileOutputStream: java.io.OutputStream? = null
    private var incomingTotalFiles = 0
    private var incomingFilesReceived = 0
    private var incomingTotalBytes = 0L
    private var incomingReceivedBytes = 0L
    private var incomingStartTime = 0L
    private var incomingLastUpdate = 0L

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: AirBridgeService? = null
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

        val urls = intent?.getStringArrayListExtra("WS_URLS")
        if (urls != null) {
            savedUrls = urls.toSet()
            updateDirectShareShortcuts(savedUrls)

            // 🚀 Respect Auto-Pair toggle here
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
                showToast("Copied to Mac(s)!")
            } else {
                showToast("No Macs connected")
            }
        }

        val action = intent?.action
        val targetUrl = intent?.getStringExtra("TARGET_URL")

        if (action == Intent.ACTION_SEND) {
            val fileUri = intent.data
            if (fileUri != null && activeSockets.isNotEmpty()) {
                streamBatchToMac(listOf(fileUri), targetUrl)
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val fileUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FILE_URIS", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>("FILE_URIS")
            }

            if (fileUris != null && activeSockets.isNotEmpty()) {
                streamBatchToMac(fileUris, targetUrl)
            }
        }

        return START_STICKY
    }

    // FIXED: Function is now at the class level so other classes can use it
    fun broadcastToMac(text: String) {
        activeSockets.values.forEach { it.send(text) }
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
        val notification = NotificationCompat.Builder(this, "AirBridgeChannel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    private fun streamBatchToMac(uris: List<Uri>, targetUrl: String?) {
        transferQueue.execute {
            var targetSocket = if (targetUrl != null) activeSockets[targetUrl] else activeSockets.values.firstOrNull()
            if (targetSocket == null) {
                showToast("Mac disconnected. Aborting.")
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
                } catch (e: Exception) { e.printStackTrace() }
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
                        targetSocket = if (targetUrl != null) activeSockets[targetUrl] else activeSockets.values.firstOrNull()
                        var waitCount = 0
                        while (targetSocket == null && waitCount < 40) {
                            Thread.sleep(50)
                            targetSocket = if (targetUrl != null) activeSockets[targetUrl] else activeSockets.values.firstOrNull()
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
                                    val speed = if (elapsed > 0) (bytesSent / elapsed).toLong() else 0L
                                    val remainingBytes = totalBytes - bytesSent
                                    val eta = if (speed > 0) remainingBytes / speed else 0L
                                    val progressPct = if (totalBytes > 0) ((bytesSent.toDouble() / totalBytes) * 100).toInt() else 0

                                    broadcastTransferProgress(filesSent, totalFiles, progressPct, formatSpeed(speed), formatEta(eta))
                                    updateTransferNotification(progressPct, "Sending $totalFiles files to Mac", "${formatSpeed(speed)} • ${formatEta(eta)}")

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

            getSystemService(NotificationManager::class.java).notify(1, createNotification())
            mainHandler.postDelayed({ broadcastTransferProgress(-1, -1, 0, "", "") }, 2000)
        }
    }

    private fun broadcastTransferProgress(currentFiles: Int, totalFiles: Int, progressPct: Int, speed: String, eta: String) {
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
            val shortcuts = urls.mapIndexed { index, url ->
                val uri = Uri.parse(url)
                val rawName = uri.getQueryParameter("name") ?: "Mac $index"
                val shortName = getShortMacName(rawName)

                val shareIntent = Intent(this, ShareActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra("TARGET_URL", url)
                }

                val person = Person.Builder().setName(shortName).build()

                ShortcutInfoCompat.Builder(this, "mac_target_$index")
                    .setShortLabel(shortName)
                    .setLongLabel("Send to $rawName")
                    .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_share))
                    .setCategories(setOf(
                        "com.shazan.airbridge.category.TEXT_SHARE_TARGET",
                        "com.shazan.airbridge.category.FILE_SHARE_TARGET"
                    ))
                    .setIntent(shareIntent)
                    .setPerson(person)
                    .setLongLived(true)
                    .build()
            }

            ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts)
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
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 1. Handle File Transfer Commands
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
                // 1. Handle File Transfer Commands from Mac
                if (text.startsWith("START_FILE:")) {
                    val myDate = Time.now
                    val customFormat = myDate.format("hh-mm a, EEEE, dd MMM yy")
                    isReceivingFile = true
                    val rawFileName = text.substringAfter("START_FILE:")
                    var cleanedName = rawFileName.replace(Regex("^[A-Fa-f0-9]{6}_"), "")
                    val ext = cleanedName.substringAfterLast(".", "dat")
                    cleanedName = "AirBridge ${customFormat}_${System.currentTimeMillis()}.$ext"

                    // Intercept macOS generic Share Extension names
//                    if (cleanedName.startsWith("PNG image") || cleanedName.startsWith("JPEG image") || cleanedName.startsWith("SharedFile")) {
//                        val ext = cleanedName.substringAfterLast(".", "dat")
//                        cleanedName = "AirBridge_${System.currentTimeMillis()}.$ext"
//                    }

                    incomingFileName = cleanedName

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = contentResolver
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, incomingFileName)
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/AirBridge")
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1) // Locks file from OS indexing during transfer
                        }
                        incomingFileUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        incomingFileOutputStream = incomingFileUri?.let { resolver.openOutputStream(it) }
                    } else {
                        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AirBridge")
                        dir.mkdirs()
                        val file = java.io.File(dir, incomingFileName)
                        incomingFileOutputStream = java.io.FileOutputStream(file)
                    }
                    updateIncomingTransferUI(false)
                    return
                }

                if (text.startsWith("END_FILE:")) {
                    isReceivingFile = false
                    try { incomingFileOutputStream?.close() } catch (e: Exception) { }
                    incomingFileOutputStream = null

                    // Unlock the file so it appears in the Gallery/Files app properly
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && incomingFileUri != null) {
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        contentResolver.update(incomingFileUri!!, contentValues, null, null)
                        incomingFileUri = null
                    }

                    incomingFilesReceived++
                    updateIncomingTransferUI(incomingFilesReceived >= incomingTotalFiles)
                    return
                }

                // 2. Intercept Mac Inputs
                if (text.startsWith("INP:")) {
                    if (AirBridgeInputService.instance == null) {
                        mainHandler.post { Toast.makeText(this@AirBridgeService, "Enable AirBridge in Android Accessibility Settings!", Toast.LENGTH_SHORT).show() }
                    } else {
                        AirBridgeInputService.processCommand(text)
                    }
                    return
                }

                // 3. Normal Clipboard text
                if (text != lastClipboardText) {
                    isUpdatingFromMac = true
                    mainHandler.post {
                        val clip = ClipData.newPlainText("AirBridge", text)
                        clipboardManager.setPrimaryClip(clip)
                        lastClipboardText = text
                        isUpdatingFromMac = false
                    }
                }
            }

            // NEW: Handle Incoming Binary Data
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (isReceivingFile) {
                    try {
                        incomingFileOutputStream?.write(bytes.toByteArray())
                        incomingReceivedBytes += bytes.size

                        val now = System.currentTimeMillis()
                        // Only update UI every 500ms to prevent lagging the phone
                        if (now - incomingLastUpdate > 500) {
                            incomingLastUpdate = now
                            updateIncomingTransferUI(false)
                        }
                    } catch (e: Exception) {
                        Log.e("AirBridge", "Error writing binary chunk", e)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeSockets.remove(url)
                broadcastConnectionState()
                scheduleReconnect(url)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeSockets.remove(url)
                broadcastConnectionState()
                scheduleReconnect(url)
            }
        })
    }

    private fun scheduleReconnect(url: String) {
        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        val autoConnect = prefs.getBoolean("AUTO_CONNECT", true)

        // Only loop reconnects if the user hasn't turned off Auto-Pair
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
            if (isUpdatingFromMac) return@addPrimaryClipChangedListener

            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val description = clip.description
                val item = clip.getItemAt(0)

                val isExplicitlyFile = description?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST) == true

                if (isExplicitlyFile && item.uri != null) {
                    val uriScheme = item.uri.scheme
                    if (uriScheme == "content" || uriScheme == "file") {
                        if (activeSockets.isNotEmpty()) {
                            showToast("Select a specific Mac using Direct Share")
                        }
                        return@addPrimaryClipChangedListener
                    }
                }

                val newText = item.coerceToText(this)?.toString() ?: return@addPrimaryClipChangedListener

                if (newText != lastClipboardText) {
                    lastClipboardText = newText
                    activeSockets.values.forEach { socket ->
                        socket.send(newText)
                    }
                }
            }
        }
    }

    private fun broadcastConnectionState() {
        val intent = Intent("AIRBRIDGE_STATE_UPDATE").putExtra("isConnected", activeSockets.isNotEmpty())
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        val channelId = "AirBridgeChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Clipboard Sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AirBridge Active")
            .setContentText("Syncing clipboard with Macs")
            .setSmallIcon(android.R.drawable.ic_menu_share)
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
            setImageResource(android.R.drawable.ic_menu_share)
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

                    view.alpha = 1.0f
                    view.elevation = 15f
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()

                    showCloseZone()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) isClick = false

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
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    view.elevation = 0f
                    hideCloseZone()

                    if (isHoveringClose) {
                        stopSelf()
                        return@setOnTouchListener true
                    }

                    if (isClick) {
                        view.alpha = 0.3f
                        val intent = Intent(this, SyncActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } else {
                        val isLeftHalf = (layoutParams.x + (bubbleSize / 2)) < (realScreenWidth / 2)
                        val targetX = if (isLeftHalf) -(bubbleSize / 2) else realScreenWidth - (bubbleSize / 2)

                        val animator = ValueAnimator.ofInt(layoutParams.x, targetX)
                        animator.duration = 250
                        animator.interpolator = DecelerateInterpolator()
                        animator.addUpdateListener { animation ->
                            layoutParams.x = animation.animatedValue as Int
                            windowManager?.updateViewLayout(floatingView, layoutParams)
                        }

                        animator.doOnEnd { view.alpha = 0.3f }
                        animator.start()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingView, layoutParams)
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

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        this.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    private fun updateIncomingTransferUI(isComplete: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("transfer_channel", "File Transfers", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        if (isComplete) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(this, "transfer_channel")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Transfer Complete")
                .setContentText("Received $incomingTotalFiles file(s) from Mac.")
                .setContentIntent(pendingIntent)
                .setOngoing(false)             // 🚀 CRITICAL: Removes the un-swipeable state
                .setProgress(0, 0, false)      // 🚀 CRITICAL: Removes the progress bar UI
                .setAutoCancel(true)           // Allows it to be dismissed on tap

            notificationManager.notify(2002, builder.build())

            mainHandler.post {
                Toast.makeText(this, "Received $incomingTotalFiles files from Mac", Toast.LENGTH_LONG).show()
            }

            sendBroadcast(Intent("AIRBRIDGE_TRANSFER_UPDATE").apply {
                putExtra("isComplete", true)
            })

        } else {
            val progress = if (incomingTotalBytes > 0) ((incomingReceivedBytes.toDouble() / incomingTotalBytes) * 100).toInt() else 0
            val elapsed = (System.currentTimeMillis() - incomingStartTime) / 1000.0
            val speedBps = if (elapsed > 0) incomingReceivedBytes / elapsed else 0.0
            val speedMb = String.format("%.1f MB/s", speedBps / (1024 * 1024))

            val remainingBytes = incomingTotalBytes - incomingReceivedBytes
            val etaSecs = if (speedBps > 0) (remainingBytes / speedBps).toInt() else 0
            val etaStr = if (etaSecs > 60) "${etaSecs/60}m ${etaSecs%60}s" else "${etaSecs}s"

            val builder = NotificationCompat.Builder(this, "transfer_channel")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Receiving from Mac: $incomingFileName")
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
        mainHandler.removeCallbacksAndMessages(null)
        activeSockets.values.forEach { it.close(1000, "Service destroyed") }
        activeSockets.clear()
        if (floatingView != null) windowManager?.removeView(floatingView)
        hideCloseZone()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}