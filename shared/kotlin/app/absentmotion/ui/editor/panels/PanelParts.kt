package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskSp
import app.absentmotion.ui.adaptive.deskLayout
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

@Composable
internal fun PhoneFit(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (deskLayout) {
        Box(modifier) { content() }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val fit = maxWidth / 1080.px
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides if (fit >= 1f) density else androidx.compose.ui.unit.Density(density.density * fit, density.fontScale),
            content = content,
        )
    }
}

internal val PanelDark get() = Am.PreviewBg
internal val PanelCaption get() = Am.Caption
internal val PanelGreen get() = Am.AccentGlow
internal val PanelShape = RoundedCornerShape(4.dp)

internal fun Modifier.dimmed(): Modifier = alpha(0.35f)

internal fun formatFixed(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value).let { if (it.trimStart('-').all { c -> c == '0' || c == '.' }) it.removePrefix("-") else it }

internal fun formatPlain(value: Float): String =
    if (!value.isFinite()) value.toString()
    else java.math.BigDecimal(value.toDouble()).round(java.math.MathContext(6)).setScale(5, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

@Composable
internal fun PanelIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, description: String? = null) {
    if (description.isNullOrEmpty()) Icon(icon, description, modifier.size(size), tint = animatedColor(tint))
    else app.absentmotion.ui.editor.DeskTip(description) { Icon(icon, description, modifier.size(size), tint = animatedColor(tint)) }
}

@Composable
internal fun NumberBox(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    fontSize: Float = 15.5f,
    onSelect: () -> Unit = {},
    onType: (() -> Unit)? = null,
) {
    Box(
        modifier.clip(shape).background(Am.Field).then(if (onType != null) Modifier.typeOnDoubleTap(onSelect = onSelect, onType = onType) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = fontSize.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, Modifier.wrapContentHeight(unbounded = true), color = PanelCaption, fontSize = deskSp(6f), maxLines = 1, softWrap = false)
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
    atLimit: ((Float) -> Boolean)? = null,
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
    val limit by rememberUpdatedState(atLimit)
    val scope = rememberCoroutineScope()
    val running = remember { booleanArrayOf(false) }
    val lastTap = remember { longArrayOf(0L) }
    val wheelEnd = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) { onDispose { Glide.stop(); if (running[0]) ended() } }
    Canvas(
        modifier.then(app.absentmotion.platform.wheel { delta, _, _ ->
            Glide.stop()
            if (!running[0]) { started(); running[0] = true }
            val px = (delta.x + delta.y) * 10f * wheelDensity
            val t = RulerMath.travel(px, wheelDensity)
            if (limit?.invoke(t) != true) { shift += px; dragging(t) }
            wheelEnd[0]?.cancel()
            wheelEnd[0] = scope.launch { kotlinx.coroutines.delay(300); if (running[0]) { running[0] = false; ended() } }
        }).pointerInput(Unit) {
            val decay = splineBasedDecay<Float>(this)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                Glide.stop()
                if (wheelEnd[0]?.isActive == true) { wheelEnd[0]?.cancel(); running[0] = false; ended() }
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
                        val t = RulerMath.travel(dx, density)
                        if (limit?.invoke(t) != true) { shift += dx; dragging(t) }
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
                        val t = RulerMath.travel(dx, density)
                        if (limit?.invoke(t) != true) { shift += dx; dragging(t) }
                    }
                    val v = tracker.calculateVelocity().x.coerceIn(-viewConfiguration.maximumFlingVelocity, viewConfiguration.maximumFlingVelocity)
                    if (fling && abs(v) > viewConfiguration.minimumFlingVelocity) {
                        var glide: Job? = null
                        glide = scope.launch {
                            var last = 0f
                            AnimationState(0f, v).animateDecay(decay) {
                                val dx = value - last
                                last = value
                                val t = RulerMath.travel(dx, density)
                                if (limit?.invoke(t) != true) { shift += dx; Glide.step { dragging(t) } }
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

private var handedDown: PointerId? = null

internal fun Modifier.holdAndDoubleTap(timing: LongArray, slop: Dp? = null, onDoubleTap: (() -> Unit)?): Modifier = pointerInput(onDoubleTap != null) {
    var lastTap = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        timing[0] = down.uptimeMillis
        val near = slop == null || (down.position - lastTap).getDistance() <= slop.toPx()
        if (onDoubleTap != null && near && down.uptimeMillis - timing[1] < viewConfiguration.doubleTapTimeoutMillis) {
            timing[1] = 0L
            down.consume()
            handedDown = down.id
            awaitPointerEvent(PointerEventPass.Final)
            if (handedDown == down.id) onDoubleTap()
            handedDown = null
            return@awaitEachGesture
        }
        var moved = false
        while (true) {
            val change = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) {
                if (!moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) { timing[1] = change.uptimeMillis; lastTap = down.position }
                break
            }
        }
    }
}

internal fun LongArray.heldBeforeMoving(at: Long, longPressMillis: Long): Boolean = at - this[0] >= longPressMillis

internal class TapPair {
    private var down = 0L
    private var up = 0L
    private var last: Long? = null
    private var fresh = false

    fun pressed(at: Long) { down = at; fresh = true }

    fun released(at: Long) { up = at }

    fun cancelled() { fresh = false }

    fun second(timeoutMillis: Long): Boolean {
        val hit = !fresh || last?.let { down - it in 0 until timeoutMillis } == true
        last = if (hit) null else up
        fresh = false
        return hit
    }
}

@Composable
internal fun Modifier.typeOnDoubleTap(enabled: Boolean = true, role: Role? = null, onSelect: () -> Unit = {}, onType: () -> Unit): Modifier {
    val taps = remember { TapPair() }
    val timeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val click = { if (taps.second(timeout)) onType() else onSelect() }
    val latest by rememberUpdatedState(click)
    return pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            taps.pressed(down.uptimeMillis)
            val handed = enabled && handedDown == down.id
            if (handed) handedDown = null
            val up = waitForUpOrCancellation(PointerEventPass.Initial)
            if (up == null) taps.cancelled() else { taps.released(up.uptimeMillis); if (handed) latest() }
        }
    }.clickable(enabled = enabled, role = role, onClick = click)
}

@Composable
internal fun DoubleTapToType(
    value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, doubleTap: Boolean = true,
    field: @Composable (Modifier, TextFieldValue, (TextFieldValue) -> Unit) -> Unit,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(TextFieldValue(value)) }
    val kept = remember { arrayOf(typed) }
    var last by remember(value) { mutableStateOf(value) }
    val shown = when (value) { typed.text -> typed; kept[0].text -> kept[0]; else -> typed.copy(text = value) }
    SideEffect { kept[0] = shown }
    Box(modifier, propagateMinConstraints = true) {
        field(Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused }, shown) { next ->
            typed = next
            if (next.text != last) { last = next.text; onValue(next.text) }
        }
        if (doubleTap && !focused) Box(Modifier.matchParentSize().clearAndSetSemantics {}.focusProperties { canFocus = false }.typeOnDoubleTap(enabled) {
            typed = TextFieldValue(value, TextRange(0, value.length))
            requester.requestFocus()
            keyboard?.show()
        })
    }
}
