package com.shazan.airbridge

import android.app.UiAutomation
import android.content.Intent
import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shazan.airbridge.net.PeerBus
import com.shazan.airbridge.net.PeerInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real touches, end to end: multi-pointer MotionEvents are injected through the system's input
 * dispatcher (the same path a finger takes), go through the trackpad / screen recognisers, and the
 * messages are checked on their way to a real peer over the network.
 *
 * Needs a paired desktop peer that is ready (e.g. `tools/v2probe.py host --auto-accept`, paired from
 * the emulator); skipped otherwise. Run: ./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.shazan.airbridge.GestureEndToEndTest
 */
@RunWith(AndroidJUnit4::class)
class GestureEndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ua: UiAutomation get() = inst.uiAutomation
    private val sent = CopyOnWriteArrayList<JSONObject>()
    private var real: PeerBus.Transport? = null
    private lateinit var peer: PeerInfo
    private var scenario: ActivityScenario<*>? = null

    @Before fun setUp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val until = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < until && PeerBus.peers().none { it.platform in DESKTOPS }) SystemClock.sleep(250)
        }
        val p = PeerBus.peers().firstOrNull { it.platform in DESKTOPS }
        assumeTrue("no ready desktop peer to drive", p != null)
        peer = p!!
        val r = PeerBus.transport!!
        real = r
        // Records what goes to the peer and still sends it. Also says the peer can share its screen,
        // so Screen mode puts its touch layer up even against the probe.
        PeerBus.transport = object : PeerBus.Transport by r {
            override fun send(peerId: String, msg: JSONObject): Boolean {
                if (peerId == peer.id) sent += JSONObject(msg.toString())
                return r.send(peerId, msg)
            }
            override fun peers(): List<PeerInfo> =
                r.peers().map { if (it.id == peer.id) it.copy(caps = it.caps + "mirror_source") else it }
        }
        ua.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @After fun tearDown() {
        scenario?.close()
        real?.let { PeerBus.transport = it }
        ua.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    // ------------------------------------------------------------------------------- helpers

    private fun open(mode: String) {
        scenario?.close()
        scenario = ActivityScenario.launch<TrackpadActivity>(
            Intent(inst.targetContext, TrackpadActivity::class.java)
                .putExtra(TrackpadActivity.EXTRA_PEER, peer.id)
                .putExtra(TrackpadActivity.EXTRA_MODE, mode),
        )
        SystemClock.sleep(1_500)
        compose.waitForIdle()
        sent.clear()
    }

    /** The trackpad window's real size (it is full screen, so window = display coordinates). */
    private fun display(): Pair<Int, Int> {
        var size = 0 to 0
        scenario!!.onActivity { a -> size = a.window.decorView.width to a.window.decorView.height }
        return size
    }

    private fun rotate(rotation: Int, landscape: Boolean) {
        ua.setRotation(rotation)
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            val (w, h) = display()
            if (w > 0 && (w > h) == landscape) break
            SystemClock.sleep(200)
        }
        SystemClock.sleep(800)
        compose.waitForIdle()
        sent.clear()
    }

    private fun actions(): List<String> = sent.filter { it.optString("t") == "action" }.map { it.getString("name") }
    private fun types(): Set<String> = sent.map { it.optString("t") }.toSet()

    private fun settle() { SystemClock.sleep(400); compose.waitForIdle() }

    /**
     * One touch: [n] fingers land 12 ms apart at [start], move through [steps] frames (16 ms apart)
     * where frame s has finger positions [at] (s, finger), then lift one by one.
     */
    private fun touch(start: List<PointF>, steps: Int, pressureAt: (Int) -> Float = { 0.3f }, at: (Int, Int) -> PointF) {
        val n = start.size
        val down = SystemClock.uptimeMillis()
        var t = down
        fun send(action: Int, count: Int, pos: (Int) -> PointF, pr: Float) {
            val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { val p = pos(i); x = p.x; y = p.y; pressure = pr; size = 0.1f } }
            val e = MotionEvent.obtain(down, t, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            ua.injectInputEvent(e, true)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1, { start[it] }, pressureAt(0))
        for (i in 1 until n) {
            t += 12; SystemClock.sleep(12)
            send(MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), i + 1, { start[it] }, pressureAt(0))
        }
        var last: (Int) -> PointF = { start[it] }
        for (s in 1..steps) {
            t += 16; SystemClock.sleep(16)
            val frame = s
            last = { at(frame, it) }
            send(MotionEvent.ACTION_MOVE, n, last, pressureAt(s))
        }
        for (i in n - 1 downTo 1) {
            t += 8; SystemClock.sleep(8)
            send(MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), i + 1, last, pressureAt(steps))
        }
        t += 8
        send(MotionEvent.ACTION_UP, 1, last, 0.1f)
    }

    private fun hand(n: Int, cx: Float, cy: Float, r: Float) =
        List(n) { k -> val a = 2 * Math.PI * k / n; PointF(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()) }

    private fun swipe(n: Int, dx: Float, dy: Float, cx: Float, cy: Float) {
        val h = hand(n, cx, cy, 130f)
        touch(h, 14) { s, i -> PointF(h[i].x + dx * s, h[i].y + dy * s) }
        settle()
    }

    private fun pinch(n: Int, inward: Boolean, cx: Float, cy: Float) {
        val r0 = if (inward) 300f else 110f
        val h = hand(n, cx, cy, r0)
        touch(h, 12) { s, i ->
            val k = (r0 + (if (inward) -18f else 18f) * s) / r0
            PointF(cx + (h[i].x - cx) * k, cy + (h[i].y - cy) * k)
        }
        settle()
    }

    private fun swipesAt(cx: Float, cy: Float, step: Float, label: String) {
        val expect = mapOf(
            Triple(4, 0f, -step) to "mission_control",
            Triple(4, 0f, step) to "app_expose",
            Triple(4, -step, 0f) to "desktop_right",
            Triple(4, step, 0f) to "desktop_left",
            Triple(5, 0f, -step) to "mission_control",
            Triple(5, step, 0f) to "desktop_left",
        )
        for ((g, name) in expect) {
            sent.clear()
            swipe(g.first, g.second, g.third, cx, cy)
            assertEquals("$label: ${g.first} fingers (${g.second}, ${g.third})", listOf(name), actions())
        }
        sent.clear(); pinch(5, inward = true, cx, cy)
        assertEquals("$label: 5-finger pinch", listOf("app_drawer"), actions())
        sent.clear(); pinch(4, inward = false, cx, cy)
        assertEquals("$label: 4-finger spread", listOf("show_desktop"), actions())
        sent.clear(); swipe(3, 0f, -step, cx, cy)
        assertTrue("$label: three fingers must send nothing, got ${actions()}", actions().isEmpty())
    }

    // ------------------------------------------------------------------------------- tests

    @Test fun trackpadStaysLandscapeWithThePhoneUpright() {
        open("Trackpad")
        // The phone itself is upright (portrait, rotation locked): the controller is landscape anyway,
        // and every direction is the landscape screen's.
        ua.setRotation(UiAutomation.ROTATION_FREEZE_0)
        SystemClock.sleep(1_500); compose.waitForIdle(); sent.clear()
        val (w, h) = display()
        assertTrue("landscape window while the phone is portrait (${w}x$h)", w > h)
        swipesAt(w / 2f, h * 0.6f, 22f, "phone upright")

        sent.clear()
        touch(listOf(PointF(w / 2f, h * 0.6f)), 0) { _, _ -> PointF(w / 2f, h * 0.6f) }
        settle()
        assertTrue("tap clicks, got $sent", sent.any { it.optString("t") == "ptr_click" && it.optInt("b") == 0 })

        sent.clear()
        val a = PointF(w / 2f - 120, h * 0.6f); val b = PointF(w / 2f + 120, h * 0.6f)
        touch(listOf(a, b), 14) { s, i -> val p = if (i == 0) a else b; PointF(p.x, p.y - 22f * s) }
        settle()
        assertTrue("two fingers scroll", "ptr_scroll" in types())
        assertTrue(actions().isEmpty())

        sent.clear()
        val c = PointF(w / 2f - 220, h * 0.6f); val d = PointF(w / 2f + 220, h * 0.6f)
        touch(listOf(c, d), 12) { s, i -> val p = if (i == 0) c else d; PointF(p.x + (if (i == 0) 18f else -18f) * s, p.y) }
        settle()
        assertTrue("two-finger pinch zooms out, got ${actions()}", actions().isNotEmpty() && actions().all { it == "zoom_out" })
    }

    @Test fun trackpadGesturesFollowLandscape() {
        open("Trackpad")
        rotate(UiAutomation.ROTATION_FREEZE_90, landscape = true)
        val (w, h) = display()
        assertTrue("landscape window", w > h)
        // "Up" is up on the screen as it is held now.
        swipesAt(w / 2f, h * 0.6f, 22f, "landscape")
    }

    @Test fun firmPressIsForceClick() {
        open("Trackpad")
        val (w, h) = display()
        // Ordinary taps teach the calibrator this hand's normal pressure.
        repeat(10) { i ->
            val p = PointF(w / 2f + (i % 3) * 30, h * 0.55f + (i % 4) * 30)
            touch(listOf(p), 2, pressureAt = { 0.30f + (i % 3) * 0.02f }) { _, _ -> p }
            SystemClock.sleep(350)
        }
        settle()
        assertTrue("ordinary taps stay clicks", actions().isEmpty())
        sent.clear()
        val p = PointF(w / 2f, h * 0.6f)
        // A finger resting, then pressing harder over ~0.2 s (a firm press is a rise, not a hard landing).
        val ramp = listOf(0.3f, 0.3f, 0.32f, 0.34f, 0.36f, 0.4f, 0.45f, 0.52f, 0.6f, 0.7f, 0.8f, 0.85f)
        // A real fingertip pressed harder wobbles by a fraction of a pixel (well under the touch slop);
        // the system drops move events whose position did not change at all.
        touch(listOf(p), ramp.size - 1, pressureAt = { ramp[it] }) { s, _ -> PointF(p.x + (s % 2) * 0.6f, p.y + (s % 3) * 0.4f) }
        settle()
        assertEquals("firm press = force click", listOf("look_up"), actions())
        assertTrue("and no click", sent.none { it.optString("t") == "ptr_click" })
    }

    @Test fun screenModeGesturesAndShortcuts() {
        open("Screen")
        rotate(UiAutomation.ROTATION_FREEZE_90, landscape = true)
        sent.clear()
        val (w, h) = display()
        // Over the picture but above its middle, where the "Try again" button of a stalled stream
        // sits (the probe never streams video).
        val cy = h * 0.36f
        swipe(4, 0f, -14f, w / 2f, cy + 120f)
        assertEquals("screen mode: 4-finger swipe up", listOf("mission_control"), actions())
        sent.clear(); swipe(4, 22f, 0f, w / 2f, cy)
        assertEquals("screen mode: 4-finger swipe right", listOf("desktop_left"), actions())
        sent.clear(); pinch(5, inward = true, w / 2f, h * 0.45f)
        assertEquals("screen mode: 5-finger pinch", listOf("app_drawer"), actions())
        sent.clear(); swipe(3, 0f, -14f, w / 2f, cy + 120f)
        assertTrue("screen mode: three fingers send nothing", actions().isEmpty())

        for ((desc, action) in listOf("Mission Control" to "mission_control", "Show or hide the Dock" to "dock_toggle",
            "Spotlight" to "spotlight", "Show or hide the menu bar" to "menu_bar_toggle", "Launchpad" to "app_drawer")) {
            if (peer.platform == "windows" && action in setOf("dock_toggle", "menu_bar_toggle")) continue
            sent.clear()
            compose.onNodeWithContentDescription(desc).performClick()
            settle()
            assertEquals("shortcut $desc", listOf(action), actions())
        }

        // The Keyboard tile shows the phone's full keyboard over the picture and sends nothing itself.
        sent.clear()
        compose.onNodeWithContentDescription("Show or hide the keyboard").performClick()
        settle()
        assertTrue("keyboard tile sends no action", actions().isEmpty())
        assertTrue("keyboard is up", compose.onAllNodesWithText("esc").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Show or hide the keyboard").performClick()
        settle()
        assertTrue("keyboard hidden again", compose.onAllNodesWithText("esc").fetchSemanticsNodes().isEmpty())
    }

    @Test fun commandHeldWhileTappingTabLikeAHardwareKeyboard() {
        open("Keyboard")
        rotate(UiAutomation.ROTATION_FREEZE_90, landscape = true)
        val gui = if (peer.platform == "macos") "command" else if (peer.platform == "windows") "win" else "super"
        val g = compose.onAllNodesWithText(gui)[0].fetchSemanticsNode().boundsInRoot
        val tb = compose.onAllNodesWithText("tab")[0].fetchSemanticsNode().boundsInRoot
        val cmd = PointF(g.center.x, g.center.y)
        val tab = PointF(tb.center.x, tb.center.y)
        sent.clear()
        // Finger 0 holds ⌘; finger 1 taps tab three times.
        val down = SystemClock.uptimeMillis()
        var t = down
        fun ev(action: Int, pts: List<PointF>) {
            val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].x; y = pts[i].y; pressure = 0.3f } }
            val e = MotionEvent.obtain(down, t, action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            ua.injectInputEvent(e, true); e.recycle()
        }
        ev(MotionEvent.ACTION_DOWN, listOf(cmd))
        repeat(3) {
            t += 120; SystemClock.sleep(120)
            ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(cmd, tab))
            t += 80; SystemClock.sleep(80)
            ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(cmd, tab))
        }
        settle()
        val keys = sent.filter { it.optString("t") == "key" }
        val guiHid = 0xE3
        assertEquals("⌘ down once and still held", 1, keys.count { it.getInt("hid") == guiHid && it.getBoolean("down") })
        assertTrue("⌘ not released yet", keys.none { it.getInt("hid") == guiHid && !it.getBoolean("down") })
        val tabs = keys.filter { it.getInt("hid") == 0x2B && it.getBoolean("down") }
        assertEquals(3, tabs.size)
        assertTrue("every tab carries ⌘", tabs.all { it.getInt("mods") and 0x08 != 0 })
        t += 300; SystemClock.sleep(300)
        ev(MotionEvent.ACTION_UP, listOf(cmd))
        settle()
        val after = sent.filter { it.optString("t") == "key" }
        assertTrue("lifting ⌘ releases it", after.last().let { it.getInt("hid") == guiHid && !it.getBoolean("down") })

        // Tapping a modifier alone latches nothing: the next letter is plain.
        sent.clear()
        compose.onAllNodesWithText(gui)[0].performClick()
        val a = compose.onAllNodesWithText("a")[0].fetchSemanticsNode().boundsInRoot
        touch(listOf(PointF(a.center.x, a.center.y)), 0) { _, _ -> PointF(a.center.x, a.center.y) }
        settle()
        val aDown = sent.last { it.optString("t") == "key" && it.getInt("hid") == 0x04 && it.getBoolean("down") }
        assertEquals("no stuck modifier", 0, aDown.getInt("mods") and 0x0F)
    }

    private companion object {
        val DESKTOPS = setOf("macos", "windows", "linux")
        @Suppress("unused") val ROT = Surface.ROTATION_0
    }
}
