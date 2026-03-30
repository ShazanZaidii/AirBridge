package com.shazan.airbridge

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
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
import okhttp3.*
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AirBridgeService : Service() {

    private val transferQueue = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private lateinit var clipboardManager: ClipboardManager

    private var lastClipboardText: String = ""
    private var isUpdatingFromMac = false
    private var isWsConnected = false
    private var currentUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var closeZoneView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val bubbleSize = 130
    private var isHoveringClose = false

    override fun onCreate() {
        super.onCreate()
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
        val url = intent?.getStringExtra("WS_URL")

        if (url != null && (webSocket == null || currentUrl != url)) {
            currentUrl = url
            updateDirectShareShortcut(url)
            connectWebSocket(url)
        }

        val textToSend = intent?.getStringExtra("SEND_TEXT")
        if (textToSend != null) {
            if (isWsConnected && webSocket != null) {
                val sent = webSocket?.send(textToSend) == true
                if (sent) showToast("Copied to Mac!") else showToast("Failed to send")
            } else {
                showToast("Not connected to Mac")
            }
        }

        // UPDATED: Handle single and multiple files
        val action = intent?.action
        if (action == Intent.ACTION_SEND) {
            val fileUri = intent.data
            if (fileUri != null && isWsConnected && webSocket != null) {
                streamFileToMac(fileUri)
            } else if (fileUri != null) {
                showToast("Not connected to Mac")
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val fileUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FILE_URIS", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>("FILE_URIS")
            }

            if (fileUris != null && isWsConnected && webSocket != null) {
                fileUris.forEach { uri -> streamFileToMac(uri) }
            } else if (fileUris != null) {
                showToast("Not connected to Mac")
            }
        }

        return START_STICKY
    }

    private fun streamFileToMac(uri: Uri) {
        // UPDATED: Use the queue instead of a raw Thread
        transferQueue.execute {
            try {
                var fileName = "SharedFile_${System.currentTimeMillis()}.dat"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                }

                webSocket?.send("START_FILE:$fileName")

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(1024 * 1024 * 5)
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val byteString = buffer.toByteString(0, bytesRead)
                        webSocket?.send(byteString)

                        while ((webSocket?.queueSize() ?: 0L) > 25_000_000L) {
                            Thread.sleep(50)
                        }
                    }
                }

                webSocket?.send("END_FILE:$fileName")
                showToast("Sent to Mac!")

            } catch (e: Exception) {
                showToast("Failed to send file")
                e.printStackTrace()
            }
        }
    }

    fun getShortMacName(fullName: String): String {
        return fullName
            .replace("MacBook Pro", "MBP", ignoreCase = true)
            .replace("MacBook Air", "MBA", ignoreCase = true)
            .replace("Mac mini", "McM", ignoreCase = true)
            .replace("Mac Studio", "McS", ignoreCase = true)
            .replace("Mac Pro", "McP", ignoreCase = true)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun updateDirectShareShortcut(wsUrl: String) {
        try {
            val uri = Uri.parse(wsUrl)
            val macName = uri.getQueryParameter("name") ?: "Mac"
            val shortName = getShortMacName(macName)

            val shareIntent = Intent(this, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND
            }

            // 1. Create a Person object. Android prioritizes "People" in the top row.
            val person = Person.Builder()
                .setName(macName)
                .build()

            // 2. Use ShortcutInfoCompat instead of the raw framework class
            val shortcut = ShortcutInfoCompat.Builder(this, "mac_target")
                .setShortLabel(macName)
                .setLongLabel("Send to $macName")
                .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_share))
                .setCategories(setOf(
                    "com.shazan.airbridge.category.TEXT_SHARE_TARGET",
                    "com.shazan.airbridge.category.FILE_SHARE_TARGET"
                ))
                .setIntent(shareIntent)
                .setPerson(person) // CRITICAL: This puts it in the Direct Share row
                .setLongLived(true)
                .build()

            // 3. Push it using the Compat manager
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (e: Exception) {
            Log.e("AirBridge", "Failed to update share shortcut", e)
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
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

    private fun connectWebSocket(url: String) {
        webSocket?.cancel()
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWsConnected = true
                broadcastConnectionState(true) // ADD THIS
                Log.d("AirBridge", "Connected to Mac!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
                broadcastConnectionState(false) // ADD THIS
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
                broadcastConnectionState(false) // ADD THIS
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        currentUrl?.let { url ->
            mainHandler.postDelayed({
                if (!isWsConnected) {
                    Log.d("AirBridge", "Attempting reconnect...")
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

                // 1. Differentiate between a File Copy and a String Copy
                // File managers use MIMETYPE_TEXT_URILIST when actually copying a file.
                // Text editors/browsers use MIMETYPE_TEXT_PLAIN even if the text is a URL/URI.
                val isExplicitlyFile = description?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST) == true

                // 2. Intercept actual File Copies
                if (isExplicitlyFile && item.uri != null) {
                    val uriScheme = item.uri.scheme
                    if (uriScheme == "content" || uriScheme == "file") {
                        if (isWsConnected) {
                            showToast("Sending copied file to Mac...")
                            streamFileToMac(item.uri)
                        }
                        return@addPrimaryClipChangedListener // Stop here so we don't send the URI string as text
                    }
                }

                // 3. Fallback to standard Text Copying (handles plain strings and raw URIs copied as text)
                val newText = item.coerceToText(this)?.toString() ?: return@addPrimaryClipChangedListener

                if (newText != lastClipboardText) {
                    lastClipboardText = newText
                    if (isWsConnected) webSocket?.send(newText)
                }
            }
        }
    }

    private fun broadcastConnectionState(connected: Boolean) {
        val intent = Intent("AIRBRIDGE_STATE_UPDATE").putExtra("isConnected", connected)
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
            .setContentText("Syncing clipboard with Mac")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentUrl = null
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service destroyed")
        isWsConnected = false
        if (floatingView != null) windowManager?.removeView(floatingView)
        hideCloseZone()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}