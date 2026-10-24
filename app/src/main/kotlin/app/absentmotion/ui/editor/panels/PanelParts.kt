package app.absentmotion.ui.editor.panels

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.Glide
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

internal val Int.px: Dp get() = (this / 2.8125f).dp
internal val Float.px: Dp get() = (this / 2.8125f).dp

internal val PanelDark get() = Am.PreviewBg
internal val PanelCaption get() = Am.Caption
internal val PanelGreen get() = Am.AccentGlow
internal val PanelShape = RoundedCornerShape(4.dp)

internal fun Modifier.dimmed(): Modifier = alpha(0.35f)

internal fun formatFixed(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value).let { if (it.trimStart('-').all { c -> c == '0' || c == '.' }) it.removePrefix("-") else it }

internal fun formatPlain(value: Float): String =
    String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.').let { if (it == "-0" || it.isEmpty()) "0" else it }

@Composable
internal fun PanelIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, description: String? = null) {
    Icon(icon, description, modifier.size(size), tint = animatedColor(tint))
}

@Composable
internal fun NumberBox(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    fontSize: Float = 15.5f,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier.clip(shape).background(Am.Field).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = fontSize.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = PanelCaption, fontSize = 6.sp, maxLines = 1, softWrap = false)
    }
}

internal object RulerMath {
    private val K = ln(1.25f) / 100f
    private const val MIN_SCALE = 1e-4f

    fun scale(base: Float, travel: Float): Float {
        val from = max(base, MIN_SCALE)
        val at = (if (from <= 1f) ln(from) else from - 1f) / K + travel
        return if (at <= 0f) exp(K * at) else 1f + K * at
    }

    fun value(base: Float, travel: Float, minStep: Float): Float = base + travel * max(minStep, abs(base) * K)

    fun travel(tickShiftPx: Float, density: Float): Float = -tickShiftPx / density
}

internal val LocalVibration = androidx.compose.runtime.compositionLocalOf { true }

@Composable
internal fun RulerSpinner(
    lineColor: Color,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    tickSpacing: Dp = 5.dp,
    tickInset: Dp = 8.5.dp,
    fling: Boolean = true,
    onReset: (() -> Unit)? = null,
) {
    val wheelDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val vibrate by rememberUpdatedState(LocalVibration.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    var shift by remember { mutableFloatStateOf(0f) }
    var fine by remember { androidx.compose.runtime.mutableStateOf(false) }
    val dragging by rememberUpdatedState(onDrag)
    val started by rememberUpdatedState(onStart)
    val ended by rememberUpdatedState(onEnd)
    val reset by rememberUpdatedState(onReset)
    val scope = rememberCoroutineScope()
    val running = remember { booleanArrayOf(false) }
    val lastTap = remember { longArrayOf(0L) }
    DisposableEffect(Unit) { onDispose { Glide.stop(); if (running[0]) ended() } }
    Canvas(
        modifier.then(app.absentmotion.platform.wheel { delta, _, _ ->
            Glide.stop(); started()
            val px = (delta.x + delta.y) * 10f * wheelDensity
            shift += px; dragging(RulerMath.travel(px, wheelDensity)); ended()
        }).pointerInput(Unit) {
            val decay = splineBasedDecay<Float>(this)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                Glide.stop()
                val undo = reset
                if (undo != null && down.uptimeMillis - lastTap[0] < viewConfiguration.doubleTapTimeoutMillis) {
                    lastTap[0] = 0L
                    down.consume()
                    started(); undo(); ended()
                    if (vibrate) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    return@awaitEachGesture
                }
                val tracker = VelocityTracker().apply { addPointerInputChange(down) }
                var waited = true
                var change = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }.also { waited = false }
                }
                if (waited) {
                    fine = true
                    if (vibrate) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (!running[0]) { started(); running[0] = true }
                    horizontalDrag(down.id) { c ->
                        val dx = c.positionChange().x * FINE_FACTOR
                        c.consume()
                        shift += dx
                        dragging(RulerMath.travel(dx, density))
                    }
                    fine = false
                    running[0] = false
                    ended()
                    rulerTip(context)
                    return@awaitEachGesture
                }
                if (change == null) {
                    lastTap[0] = currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                } else {
                    if (!running[0]) { started(); running[0] = true }
                    horizontalDrag(change.id) { c ->
                        val dx = c.positionChange().x
                        c.consume()
                        tracker.addPointerInputChange(c)
                        shift += dx
                        dragging(RulerMath.travel(dx, density))
                    }
                    val v = tracker.calculateVelocity().x.coerceIn(-viewConfiguration.maximumFlingVelocity, viewConfiguration.maximumFlingVelocity)
                    if (fling && abs(v) > viewConfiguration.minimumFlingVelocity) {
                        var glide: Job? = null
                        glide = scope.launch {
                            var last = 0f
                            AnimationState(0f, v).animateDecay(decay) {
                                val dx = value - last
                                last = value
                                shift += dx
                                Glide.step { dragging(RulerMath.travel(dx, density)) }
                            }
                            Glide.finished(glide)
                            running[0] = false
                            ended()
                        }
                        Glide.start(glide) { running[0] = false; ended() }
                        rulerTip(context)
                        return@awaitEachGesture
                    }
                    rulerTip(context)
                }
                if (running[0]) { running[0] = false; ended() }
            }
        },
    ) {
        val step = tickSpacing.toPx()
        val half = size.width / 2f
        val whole = floor(shift / step)
        val frac = shift - whole * step
        val ticks = (half / step).toInt() + 2
        for (i in -ticks..ticks) {
            val x = half + frac + i * step
            val distance = abs(x - half)
            if (distance >= half) continue
            val major = if (Math.floorMod(i - whole.toInt(), 5) == 0) 0.06f else 0f
            val alpha = (0.36f * (1f - distance / half) + major).coerceIn(0f, 1f)
            drawLine(Am.OnDark.copy(alpha = alpha), Offset(x, tickInset.toPx()), Offset(x, size.height - tickInset.toPx()), 2.px.toPx())
        }
        drawLine(if (fine) Am.Gold else lineColor, Offset(half, 0f), Offset(half, size.height), (if (fine) 7 else 4).px.toPx())
    }
}

internal const val FINE_FACTOR = 0.1f

internal fun rulerTip(context: android.content.Context) {
    val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("rulerTip", false)) return
    prefs.edit().putBoolean("rulerTip", true).apply()
    android.widget.Toast.makeText(context, context.getString(app.absentmotion.R.string.ruler_tip), android.widget.Toast.LENGTH_LONG).show()
}

internal fun Modifier.holdAndDoubleTap(timing: LongArray, onDoubleTap: (() -> Unit)?): Modifier = pointerInput(onDoubleTap != null) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        timing[0] = down.uptimeMillis
        if (onDoubleTap != null && down.uptimeMillis - timing[1] < viewConfiguration.doubleTapTimeoutMillis) {
            timing[1] = 0L
            down.consume()
            onDoubleTap()
            return@awaitEachGesture
        }
        var moved = false
        while (true) {
            val change = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) {
                if (!moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) timing[1] = change.uptimeMillis
                break
            }
        }
    }
}

internal fun LongArray.heldBeforeMoving(at: Long, longPressMillis: Long): Boolean = at - this[0] >= longPressMillis
