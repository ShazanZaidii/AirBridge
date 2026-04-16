package com.shazan.airbridge

import android.media.AudioManager
import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import android.content.Intent
import android.provider.Settings

class AirBridgeInputService : AccessibilityService() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: AirBridgeInputService? = null
        fun processCommand(command: String) { instance?.handleCommand(command) }
    }

    private var windowManager: WindowManager? = null
    private var containerView: FrameLayout? = null
    private var cursorView: ImageView? = null

    private var screenWidth = 0
    private var screenHeight = 0

    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    private var activeEdge = "right"

    @Volatile private var isMouseDown = false
    @Volatile private var isDraggingMode = false

    private var dragStartX = 0f
    private var dragStartY = 0f

    @Volatile private var pendingScrollY = 0f
    @Volatile private var pendingScrollX = 0f
    @Volatile private var isScrollDispatching = false

    private var notifState = 0
    private var lastArrowSwipeTime = 0L

    private var isRendering = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastMediaCommandTime = 0L
    private var lastVolumeUiTime = 0L


    private fun clampX(x: Float): Float = max(0f, min(screenWidth.toFloat() - 1f, x))
    private fun clampY(y: Float): Float = max(0f, min(screenHeight.toFloat() - 1f, y))

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            cursorX += (targetX - cursorX) * 0.45f
            cursorY += (targetY - cursorY) * 0.45f

            cursorView?.translationX = cursorX
            cursorView?.translationY = cursorY

            if (cursorView?.visibility == View.VISIBLE) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                isRendering = false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setupVirtualCursor()
    }

    private fun setupVirtualCursor() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        targetX = screenWidth / 2f
        targetY = screenHeight / 2f
        cursorX = targetX
        cursorY = targetY

        containerView = FrameLayout(this)
        cursorView = ImageView(this).apply {
            val shape = ShapeDrawable(OvalShape())
            shape.paint.color = Color.parseColor("#4285F4")
            shape.intrinsicWidth = 35
            shape.intrinsicHeight = 35
            background = shape
            elevation = 20f
            visibility = View.GONE
        }

        containerView?.addView(cursorView, FrameLayout.LayoutParams(35, 35))

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager?.addView(containerView, layoutParams)
    }

    private fun performCopyPaste(action: Int) {
        var node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }
        if (node != null) {
            node.performAction(action)
            node.recycle()
        }
    }

    private fun handleCommand(command: String) {
        val parts = command.split(":")
        if (parts.size < 2) return

        when (parts[1]) {
            "TRANSITION" -> {
                if (parts[2] == "PHONE") {
                    activeEdge = parts.getOrNull(3) ?: "right"
                    val relX = parts.getOrNull(4)?.toFloatOrNull() ?: 0.5f
                    val relY = parts.getOrNull(5)?.toFloatOrNull() ?: 0.5f

                    targetX = clampX(when (activeEdge) {
                        "right" -> 5f; "left" -> screenWidth - 35f
                        "top", "bottom" -> screenWidth * relX; else -> targetX
                    })
                    targetY = clampY(when (activeEdge) {
                        "bottom" -> 5f; "top" -> screenHeight - 35f
                        "right", "left" -> screenHeight * relY; else -> targetY
                    })

                    cursorX = targetX
                    cursorY = targetY

                    isMouseDown = false
                    isDraggingMode = false

                    mainHandler.post {
                        cursorView?.translationX = cursorX
                        cursorView?.translationY = cursorY
                        cursorView?.visibility = View.VISIBLE
                        if (!isRendering) {
                            isRendering = true
                            Choreographer.getInstance().postFrameCallback(frameCallback)
                        }
                    }
                } else if (parts[2] == "MAC") {
                    mainHandler.post {
                        cursorView?.visibility = View.GONE
                        isRendering = false // Issue 1 Fix: Kill the render loop immediately
                    }
                }
            }
            "M" -> {
                val dx = parts[2].toFloatOrNull() ?: 0f
                val dy = parts[3].toFloatOrNull() ?: 0f
                val scrollY = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
                val scrollX = parts.getOrNull(5)?.toFloatOrNull() ?: 0f

                val newX = targetX + (dx * 1.6f)
                val newY = targetY + (dy * 1.6f)

                if (!isMouseDown && !isDraggingMode) {
                    if ((activeEdge == "right" && newX <= -20f) || (activeEdge == "left" && newX >= screenWidth + 20f) ||
                        (activeEdge == "bottom" && newY <= -20f) || (activeEdge == "top" && newY >= screenHeight + 20f)) {

                        mainHandler.post {
                            cursorView?.visibility = View.GONE
                            isRendering = false // Issue 1 Fix: Kill the render loop immediately
                        }
                        AirBridgeService.instance?.broadcastToMac("INP:TRANSITION:MAC")
                        return
                    }
                }

                targetX = clampX(newX)
                targetY = clampY(newY)

                if ((scrollY != 0f || scrollX != 0f) && !isMouseDown) {
                    pendingScrollY += scrollY
                    pendingScrollX += scrollX
                    if (!isScrollDispatching && (Math.abs(pendingScrollY) > 1.5f || Math.abs(pendingScrollX) > 1.5f)) {
                        executeNextScrollFlick()
                    }
                }
            }
            "CD" -> {
                isMouseDown = true
            }
            "CU" -> {
                isMouseDown = false
                performStandardTap(targetX, targetY)
            }
            "DRAG" -> {
                isDraggingMode = true
                dragStartX = targetX
                dragStartY = targetY
            }
            "DRAG_RELEASE" -> {
                isDraggingMode = false
                val dist = hypot((targetX - dragStartX).toDouble(), (targetY - dragStartY).toDouble())
                if (dist > 15f) {
                    val path = Path().apply {
                        moveTo(clampX(dragStartX), clampY(dragStartY))
                        lineTo(clampX(targetX), clampY(targetY))
                    }
                    val duration = max(300L, min(800L, (dist * 1.5f).toLong()))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                    }
                } else {
                    performStandardTap(targetX, targetY)
                }
            }
            "HOLD" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val cx = clampX(targetX)
                    val cy = clampY(targetY)
                    val path = Path().apply { moveTo(cx, cy); lineTo(cx + 0.1f, cy + 0.1f) }
                    val stroke = GestureDescription.StrokeDescription(path, 0, 600)
                    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                }
            }
            "CMD" -> {
                when (parts[2]) {
                    "LEFT" -> {
                        if (parts.getOrNull(3) == "UP") {
                            notifState = (notifState + 1) % 3
                            when (notifState) {
                                1 -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                                2 -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                                0 -> performGlobalAction(GLOBAL_ACTION_BACK)
                            }
                        }
                    }
                    "C" -> performCopyPaste(AccessibilityNodeInfo.ACTION_COPY)
                    "V" -> performCopyPaste(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
            "VOL" -> {
                mainHandler.post {
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                        // 1. Manually calculate absolute volume to bypass OEM step logic
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                        val newVol = when (parts[2]) {
                            "UP" -> min(maxVol, currentVol + 1)
                            "DOWN" -> max(0, currentVol - 1)
                            else -> currentVol
                        }

                        // The function that actually executes the change
                        val applyVolume = {
                            if (parts[2] == "MUTE") {
                                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                            } else {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val now = System.currentTimeMillis()

                            // If it has been more than 2 seconds since the last press, the panel is likely closed
                            if (now - lastVolumeUiTime > 2000) {
                                val panelIntent = Intent(Settings.Panel.ACTION_VOLUME).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                startActivity(panelIntent)

                                // DELAY: Give the OS 200ms to make the panel the active foreground window
                                // before we strike. This completely disarms the background-execution blocker!
                                mainHandler.postDelayed({ applyVolume() }, 200)

                                lastVolumeUiTime = System.currentTimeMillis()
                                return@post
                            }

                            // Extend the timer so rapid presses don't keep re-launching the Intent
                            lastVolumeUiTime = now
                        }

                        // If the panel is already established on screen, apply immediately
                        applyVolume()

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            "BRIGHTNESS" -> {
                mainHandler.post {
                    // Android 6.0+ requires explicit permission to modify system settings
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this@AirBridgeInputService)) {
                        Toast.makeText(this@AirBridgeInputService, "Grant 'Modify System Settings' to AirBridge in Android Settings", Toast.LENGTH_LONG).show()
                        return@post
                    }

                    try {
                        val cr = contentResolver

                        // Disable auto-brightness temporarily so our manual changes actually stick
                        val currentMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        if (currentMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        }

                        val currentBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                        val step = 20 // Standard adjustment step out of a max 255

                        val newBrightness = when (parts[2]) {
                            "UP" -> min(255, currentBrightness + step)
                            "DOWN" -> max(0, currentBrightness - step)
                            else -> currentBrightness
                        }

                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, newBrightness)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            "DND" -> {
                mainHandler.post {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

                    // Android requires explicit permission to toggle Do Not Disturb
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
                        Toast.makeText(this@AirBridgeInputService, "Grant 'Do Not Disturb Access' to AirBridge in Android Settings", Toast.LENGTH_LONG).show()
                        return@post
                    }

                    try {
                        val currentFilter = notificationManager.currentInterruptionFilter

                        // Toggle between Priority Only (DND on) and All (DND off)
                        if (currentFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                            notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                            Toast.makeText(this@AirBridgeInputService, "DND: ON", Toast.LENGTH_SHORT).show()
                        } else {
                            notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                            Toast.makeText(this@AirBridgeInputService, "DND: OFF", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            "MIC" -> {
                mainHandler.post {
                    try {
                        // Fires the global intent to wake up Google Assistant or Gemini
                        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@AirBridgeInputService, "No Assistant App found", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            "MEDIA" -> {
                // Debounce rapid double-fires from the Mac keyboard so Android doesn't interpret it as "Skip Track"
                val now = System.currentTimeMillis()
                if (now - lastMediaCommandTime < 300) return
                lastMediaCommandTime = now

                mainHandler.post {
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val keyCode = when (parts[2]) {
                            "PLAY_PAUSE" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            "NEXT" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                            "PREV" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            else -> return@post
                        }

                        // Dispatch key down, then key up to simulate a full physical press
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            "SYS" -> {
                when (parts[2]) {
                    "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "HOME" -> {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        notifState = 0
                    }
                    "NOTIF" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "DRAWER" -> openAppDrawerPhysicalSwipe()
                    "LAST_APP" -> {
                        performGlobalAction(GLOBAL_ACTION_RECENTS)
                        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 250)
                    }
                }
            }
            "KD" -> {
                val keyCode = parts[2].toIntOrNull() ?: return
                when (keyCode) {
                    53 -> performGlobalAction(GLOBAL_ACTION_BACK)
                    126 -> performDirectionalSwipe("DOWN")
                    125 -> performDirectionalSwipe("UP")
                    123 -> performDirectionalSwipe("RIGHT")
                    124 -> performDirectionalSwipe("LEFT")
                }
            }
        }
    }

    private fun performStandardTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val cx = clampX(x)
        val cy = clampY(y)
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + 0.1f, cy + 0.1f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 20)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performDirectionalSwipe(direction: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val now = System.currentTimeMillis()
        if (now - lastArrowSwipeTime < 300) return
        lastArrowSwipeTime = now

        val startX = clampX(cursorX)
        val startY = clampY(cursorY)
        var endX = startX
        var endY = startY

        // Increased to 50% for a stronger fling
        val swipeDist = min(screenWidth, screenHeight) * 0.5f

        when (direction) {
            "UP" -> endY = max(0f, startY - swipeDist)
            "DOWN" -> endY = min(screenHeight.toFloat(), startY + swipeDist)
            "LEFT" -> endX = max(0f, startX - swipeDist)
            "RIGHT" -> endX = min(screenWidth.toFloat(), startX + swipeDist)
        }

        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun openAppDrawerPhysicalSwipe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.9f
        val endY = screenHeight * 0.1f

        val path = Path().apply { moveTo(startX, startY); lineTo(startX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun executeNextScrollFlick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        isScrollDispatching = true
        val moveY = pendingScrollY * 15f
        val moveX = pendingScrollX * 15f
        pendingScrollY = 0f
        pendingScrollX = 0f

        val startX = clampX(cursorX)
        val startY = clampY(cursorY)
        var endX = clampX(startX + moveX)
        var endY = clampY(startY + moveY)

        if (startX == endX && startY == endY) {
            endY = clampY(endY + 0.1f)
            endX = clampX(endX + 0.1f)
        }

        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }

        val stroke = GestureDescription.StrokeDescription(path, 0, 80, false)

        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (Math.abs(pendingScrollY) > 1.5f || Math.abs(pendingScrollX) > 1.5f) {
                    executeNextScrollFlick()
                } else {
                    pendingScrollY = 0f
                    pendingScrollX = 0f
                    isScrollDispatching = false
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                pendingScrollY = 0f
                pendingScrollX = 0f
                isScrollDispatching = false
            }
        }, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        containerView?.let { windowManager?.removeView(it) }
        instance = null
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        targetX = clampX(targetX)
        targetY = clampY(targetY)
        cursorX = clampX(cursorX)
        cursorY = clampY(cursorY)
    }
}