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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    private var activeEdge = "right"

    @Volatile private var isMouseDown = false
    @Volatile private var isDraggingActive = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragLastX = 0f
    private var dragLastY = 0f

    @Volatile private var pendingScrollY = 0f
    @Volatile private var pendingScrollX = 0f
    @Volatile private var isScrollDispatching = false

    private var notifState = 0
    private var lastArrowSwipeTime = 0L

    private var isRendering = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun clampX(x: Float): Float = max(0f, min(screenWidth.toFloat() - 1f, x))
    private fun clampY(y: Float): Float = max(0f, min(screenHeight.toFloat() - 1f, y))

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            cursorView?.translationX = cursorX
            cursorView?.translationY = cursorY
            if (cursorView?.visibility == View.VISIBLE) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                isRendering = false
            }
        }
    }

    private val longPressRunnable = Runnable {
        if (isMouseDown && !isDraggingActive) {
            isDraggingActive = true
            dragLastX = dragStartX
            dragLastY = dragStartY
            executeNextDragStroke()
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

        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

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
        // Find the text field that currently has keyboard focus
        var node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        // Fallback: check for general accessibility focus if no input focus is found
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

                    cursorX = clampX(when (activeEdge) {
                        "right" -> 5f; "left" -> screenWidth - 35f
                        "top", "bottom" -> screenWidth * relX; else -> cursorX
                    })
                    cursorY = clampY(when (activeEdge) {
                        "bottom" -> 5f; "top" -> screenHeight - 35f
                        "right", "left" -> screenHeight * relY; else -> cursorY
                    })

                    isMouseDown = false
                    isDraggingActive = false
                    mainHandler.removeCallbacks(longPressRunnable)

                    mainHandler.post {
                        cursorView?.translationX = cursorX
                        cursorView?.translationY = cursorY
                        cursorView?.visibility = View.VISIBLE
                        if (!isRendering) { isRendering = true; Choreographer.getInstance().postFrameCallback(frameCallback) }
                    }
                } else if (parts[2] == "MAC") {
                    mainHandler.post { cursorView?.visibility = View.GONE }
                }
            }
            "M" -> {
                val dx = parts[2].toFloatOrNull() ?: 0f
                val dy = parts[3].toFloatOrNull() ?: 0f
                val scrollY = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
                val scrollX = parts.getOrNull(5)?.toFloatOrNull() ?: 0f

                val newX = cursorX + (dx * 3.0f)
                val newY = cursorY + (dy * 3.0f)

                if (!isMouseDown) {
                    if ((activeEdge == "right" && newX <= -20f) || (activeEdge == "left" && newX >= screenWidth + 20f) ||
                        (activeEdge == "bottom" && newY <= -20f) || (activeEdge == "top" && newY >= screenHeight + 20f)) {
                        mainHandler.post { cursorView?.visibility = View.GONE }
                        AirBridgeService.instance?.broadcastToMac("INP:TRANSITION:MAC")
                        return
                    }
                }

                cursorX = clampX(newX)
                cursorY = clampY(newY)

                if (isMouseDown && !isDraggingActive) {
                    val dist = hypot((cursorX - dragStartX).toDouble(), (cursorY - dragStartY).toDouble())
                    if (dist > 15) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        isDraggingActive = true
                        dragLastX = dragStartX
                        dragLastY = dragStartY
                        executeNextDragStroke()
                    }
                }

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
                isDraggingActive = false
                dragStartX = cursorX
                dragStartY = cursorY
                mainHandler.postDelayed(longPressRunnable, 250)
            }
            "CU" -> {
                isMouseDown = false
                mainHandler.removeCallbacks(longPressRunnable)

                if (!isDraggingActive) {
                    performStandardTap(cursorX, cursorY)
                }
            }
            // 🚀 NEW: Dedicated Left Cmd logic from Mac Payload
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
                        // Changed from adjustStreamVolume to adjustVolume to let OS pick the active stream
                        when (parts[2]) {
                            "UP" -> audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                            "DOWN" -> audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                            "MUTE" -> audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                        }
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
                        notifState = 0 // Reset Left Cmd state when taking user home
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
                    53 -> performGlobalAction(GLOBAL_ACTION_BACK) // Esc key
                    126 -> performDirectionalSwipe("DOWN") // Up Arrow
                    125 -> performDirectionalSwipe("UP") // Down Arrow
                    123 -> performDirectionalSwipe("RIGHT") // Left Arrow
                    124 -> performDirectionalSwipe("LEFT") // Right Arrow
                }
            }
        }
    }

    private fun performStandardTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val cx = clampX(x)
        val cy = clampY(y)
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + 0.1f, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 45)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performDirectionalSwipe(direction: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val now = System.currentTimeMillis()
        if (now - lastArrowSwipeTime < 500) return
        lastArrowSwipeTime = now

        val startX = screenWidth / 2f
        val startY = screenHeight / 2f
        var endX = startX
        var endY = startY

        val swipeDist = min(screenWidth, screenHeight) * 0.4f

        when (direction) {
            "UP" -> endY -= swipeDist
            "DOWN" -> endY += swipeDist
            "LEFT" -> endX -= swipeDist
            "RIGHT" -> endX += swipeDist
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

    private fun executeNextDragStroke() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val startX = clampX(dragLastX)
        val startY = clampY(dragLastY)
        var endX = clampX(cursorX)
        var endY = clampY(cursorY)

        if (startX == endX && startY == endY) { endY = clampY(endY + 0.1f) }

        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80, isMouseDown)

        dragLastX = endX
        dragLastY = endY

        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isMouseDown) executeNextDragStroke() else isDraggingActive = false
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                isDraggingActive = false
            }
        }, null)
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

        // Dynamically update the screen bounds when orientation changes
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Clamp the cursor so it doesn't get trapped outside the new screen bounds
        cursorX = clampX(cursorX)
        cursorY = clampY(cursorY)
    }
}