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
import kotlin.math.floor

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
internal fun PanelIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(icon, null, modifier.size(size), tint = animatedColor(tint))
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

@Composable
internal fun RulerSpinner(
    lineColor: Color,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    tickSpacing: Dp = 5.dp,
    tickInset: Dp = 8.5.dp,
) {
    val wheelDensity = androidx.compose.ui.platform.LocalDensity.current.density
    var shift by remember { mutableFloatStateOf(0f) }
    val dragging by rememberUpdatedState(onDrag)
    val started by rememberUpdatedState(onStart)
    val ended by rememberUpdatedState(onEnd)
    val scope = rememberCoroutineScope()
    val running = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) { onDispose { Glide.stop(); if (running[0]) ended() } }
    Canvas(
        modifier.then(app.absentmotion.platform.wheel { delta, _, _ ->
            Glide.stop(); started()
            val dx = -(delta.x + delta.y) * 10f
            shift += dx * wheelDensity; dragging(dx); ended()
        }).pointerInput(Unit) {
            val decay = splineBasedDecay<Float>(this)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                Glide.stop()
                val tracker = VelocityTracker().apply { addPointerInputChange(down) }
                var change = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                if (change != null) {
                    if (!running[0]) { started(); running[0] = true }
                    horizontalDrag(change.id) { c ->
                        val dx = c.positionChange().x
                        c.consume()
                        tracker.addPointerInputChange(c)
                        shift += dx
                        dragging(dx / density)
                    }
                    val v = tracker.calculateVelocity().x.coerceIn(-viewConfiguration.maximumFlingVelocity, viewConfiguration.maximumFlingVelocity)
                    if (abs(v) > viewConfiguration.minimumFlingVelocity) {
                        var glide: Job? = null
                        glide = scope.launch {
                            var last = 0f
                            AnimationState(0f, v).animateDecay(decay) {
                                val dx = value - last
                                last = value
                                shift += dx
                                Glide.step { dragging(dx / density) }
                            }
                            Glide.finished(glide)
                            running[0] = false
                            ended()
                        }
                        Glide.start(glide) { running[0] = false; ended() }
                        return@awaitEachGesture
                    }
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
        drawLine(lineColor, Offset(half, 0f), Offset(half, size.height), 4.px.toPx())
    }
}
