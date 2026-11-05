package com.shazan.airbridge.input

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Screen mode (§8.7 mirror sink): the peer's screen, letterboxed to the stream aspect, with
 * touch-screen input mapped to the mirrored display.
 *
 * @param supported the peer advertises `mirror_source`.
 */
@Composable
fun MirrorScreen(
    sink: MirrorSink,
    sender: TrackpadSender,
    peerName: String?,
    supported: Boolean,
    showStats: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by sink.status.collectAsState()
    val info by sink.info.collectAsState()
    val view = LocalView.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Tp.Background)) {
        if (peerName == null) {
            Notice("No computer connected", "Connect a computer from the AirBridge home screen.")
            return@BoxWithConstraints
        }
        if (!supported) {
            Notice(
                "$peerName can't share its screen",
                "Update AirBridge on the computer and allow Screen Recording in its system settings.",
            )
            return@BoxWithConstraints
        }
        val aspect = info?.let { it.w.toFloat() / it.h } ?: (16f / 10f)
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val (vw, vh) = if (boxW / boxH > aspect) (boxH * aspect) to boxH else boxW to (boxW / aspect)
        val rect = Rect((boxW - vw) / 2f, (boxH - vh) / 2f, (boxW + vw) / 2f, (boxH + vh) / 2f)
        val currentRect by rememberUpdatedState(rect)
        val currentInfo by rememberUpdatedState(info)

        with(density) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = sink.setSurface(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) = sink.setSurface(null)
                        })
                    }
                },
                modifier = Modifier.align(Alignment.Center).size(vw.toDp(), vh.toDp()),
            )
        }
        // Touch layer over the whole area (letterbox included; points clamp to the picture edge).
        Box(
            Modifier.fillMaxSize().systemGestureExclusion().pointerInput(sender) {
                screenGestures(
                    sender, { currentRect }, { currentInfo },
                    haptic = { view.performHapticFeedback(it) },
                    onSystemTookGesture = OsGestureGuard::onLoss,
                )
            },
        )

        when (status.phase) {
            MirrorSink.Phase.Waiting, MirrorSink.Phase.Idle -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Tp.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text("Connecting to $peerName's screen…", color = Tp.MutedForeground, fontSize = 14.sp)
            }
            MirrorSink.Phase.Stopped, MirrorSink.Phase.Failed -> Notice(
                status.message ?: "Screen sharing stopped", null, actionLabel = "Try again", onAction = onRetry,
            )
            MirrorSink.Phase.Streaming -> Unit
        }
        if (showStats) StatsOverlay(sink, Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

@Composable
private fun StatsOverlay(sink: MirrorSink, modifier: Modifier) {
    val stats by sink.stats.collectAsState()
    val info by sink.info.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = SystemClock.uptimeMillis() } }
    val since = if (stats.lastFrameAt == 0L) "–" else "${max(0L, now - stats.lastFrameAt)} ms"
    Text(
        "${stats.fps} fps · last frame $since\n${info?.let { "${it.codec} ${it.w}×${it.h}" } ?: ""} ${stats.decoder}",
        color = Tp.Foreground, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Tp.Background.copy(alpha = 0.75f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun Notice(title: String, body: String?, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Tp.Foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (body != null) Text(body, color = Tp.MutedForeground, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = Tp.Accent, contentColor = Tp.OnAccent),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Keeps the sink attached to [InputRouter] while composed (screen mode active). */
@Composable
fun MirrorRouting(sink: MirrorSink) {
    DisposableEffect(sink) {
        val ctl: (String, org.json.JSONObject) -> Unit = sink::onControl
        val frames: (String, ByteArray) -> Unit = sink::onFrame
        InputRouter.mirrorControl = ctl
        InputRouter.mirrorFrames = frames
        onDispose {
            if (InputRouter.mirrorControl === ctl) InputRouter.mirrorControl = null
            if (InputRouter.mirrorFrames === frames) InputRouter.mirrorFrames = null
        }
    }
}

/**
 * Adapter: Compose pointer events → [ScreenGestureMachine] → `ptr_abs` / `ptr_click` / `ptr_btn` /
 * `ptr_scroll`. Positions are normalised to the picture rect (= the mirrored display, aspect kept).
 * Scroll deltas are converted to stream pixels so content tracks the finger.
 */
suspend fun PointerInputScope.screenGestures(
    sender: TrackpadSender,
    rect: () -> Rect,
    info: () -> MirrorInfo?,
    haptic: (Int) -> Unit,
    onSystemTookGesture: (fingers: Int) -> Unit = {},
) {
    fun nx(x: Float) = rect().let { (x - it.left) / max(1f, it.width) }
    fun ny(y: Float) = rect().let { (y - it.top) / max(1f, it.height) }
    val machine = ScreenGestureMachine(
        ScreenGestureMachine.Config(
            slopPx = viewConfiguration.touchSlop,
            longPressMs = viewConfiguration.longPressTimeoutMillis,
            doubleTapMs = viewConfiguration.doubleTapTimeoutMillis,
            doubleTapSlopPx = 40.dp.toPx(),
        ),
    ) { i ->
        when (i) {
            is ScreenIntent.Hover -> sender.pointAt(nx(i.x), ny(i.y))
            is ScreenIntent.Tap -> {
                sender.pointAt(nx(i.x), ny(i.y)) // flushed before the click: abs first, same order
                sender.click(i.button, i.count)
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            is ScreenIntent.Press -> {
                sender.pointAt(nx(i.x), ny(i.y))
                sender.button(true)
                haptic(HapticFeedbackConstants.LONG_PRESS)
            }
            is ScreenIntent.DragTo -> sender.pointAt(nx(i.x), ny(i.y))
            is ScreenIntent.Release -> {
                if (!i.x.isNaN()) sender.pointAt(nx(i.x), ny(i.y))
                sender.button(false)
            }
            is ScreenIntent.Scroll -> {
                val k = (info()?.w?.toFloat() ?: rect().width) / max(1f, rect().width)
                sender.scroll(i.dx * k, i.dy * k)
            }
            ScreenIntent.ResetAll -> sender.button(false)
        }
    }
    // Screen mode ignores 3+ fingers itself, so any such touch the system cancels was taken by it.
    val loss = MultiFingerLossDetector(onLoss = onSystemTookGesture)
    val hook: () -> Unit = { machine.reset(); loss.reset() }
    sender.addResetHook(hook)
    try {
        awaitPointerEventScope {
            while (true) {
                val now = SystemClock.uptimeMillis()
                val deadline = machine.nextDeadline()
                val wait: Long? = when {
                    sender.hasPending -> 8L
                    deadline != null -> max(1L, deadline - now)
                    else -> null
                }
                val event = if (wait == null) awaitPointerEvent()
                else withTimeoutOrNull(wait) { awaitPointerEvent() }
                if (event != null) feedPointerEvent(event) { id, a, x, y, t ->
                    loss.onTouch(id, a, t)
                    machine.onTouch(id, a, x, y, t)
                }
                loss.tick(SystemClock.uptimeMillis())
                machine.tick(SystemClock.uptimeMillis())
                sender.flush()
            }
        }
    } finally {
        sender.removeResetHook(hook)
        machine.reset()
        sender.flush(force = true)
    }
}
