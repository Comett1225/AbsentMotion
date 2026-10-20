package app.absentmotion.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun rememberHaptic(state: EditorState): (HapticFeedbackType) -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(state, haptic) { { type -> if (state.settings.vibration) haptic.performHapticFeedback(type) } }
}

@Composable
internal fun rememberReorder(state: EditorState, scroll: ScrollState, top: Dp, pitch: Dp = 43.5.dp): Reorder {
    val scope = rememberCoroutineScope()
    val tick = rememberHaptic(state)
    val density = LocalDensity.current
    return remember(scroll, density, top, pitch) { with(density) { Reorder(scope, scroll, pitch.toPx(), top.toPx(), tick) } }
}

@Stable
internal class Reorder(
    private val scope: CoroutineScope,
    private val scroll: ScrollState,
    val pitch: Float,
    private val top: Float,
    private val tick: (HapticFeedbackType) -> Unit,
) {
    var held by mutableIntStateOf(-1)
        private set
    var target by mutableIntStateOf(-1)
        private set
    var generation by mutableIntStateOf(0)
        private set
    val lift = Animatable(0f)
    private var count = 0
    private var grab = 0f
    private var start = 0f
    private var anchor by mutableFloatStateOf(0f)
    private var settle by mutableStateOf<Float?>(null)
    private var job: Job? = null

    val offset: Float get() = settle ?: RowDrag.follow(anchor + scroll.value, grab, held, count, pitch)

    fun pick(index: Int, y: Float, size: Int) {
        if (held >= 0 || size < 2 || index !in 0 until size) return
        count = size
        grab = y
        anchor = y - scroll.value
        start = finger()
        held = index
        target = index
        tick(HapticFeedbackType.GestureThresholdActivate)
        scope.launch { lift.animateTo(1f, tween(LIFT_MS, easing = Motion.EaseOut)) }
        job = scope.launch { push() }
    }

    fun move(y: Float) {
        if (held < 0 || settle != null) return
        anchor = y - scroll.value
        update()
    }

    fun drop(commit: (Int, Int) -> Unit) {
        if (held < 0 || settle != null) return
        job?.cancel()
        val from = held
        val to = target
        val at = offset
        settle = at
        scope.launch {
            try {
                launch { lift.animateTo(0f, tween(DROP_MS, easing = Motion.EaseOut)) }
                animate(at, (to - from) * pitch, animationSpec = tween(DROP_MS, easing = Motion.EaseOut)) { v, _ -> settle = v }
            } finally {
                if (to != from) commit(from, to)
                held = -1
                target = -1
                settle = null
                generation++
            }
        }
    }

    private fun finger() = top + held.coerceAtLeast(0) * pitch + anchor

    private fun update() {
        val to = RowDrag.target(held, offset, pitch, count)
        if (to == target) return
        target = to
        tick(HapticFeedbackType.SegmentTick)
    }

    private suspend fun push() {
        var last = withFrameNanos { it }
        while (settle == null && held >= 0) {
            val now = withFrameNanos { it }
            val step = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            val y = finger()
            val speed = RowDrag.toward(RowDrag.edgeSpeed(y, 0f, scroll.viewportSize.toFloat(), pitch, pitch, pitch * 10f), y, start, pitch / 4)
            if (speed != 0f && scroll.dispatchRawDelta(speed * step) != 0f) update()
        }
    }
}

@Composable
internal fun Modifier.reorderSlot(reorder: Reorder, index: Int): Modifier {
    val held = reorder.held == index
    val shift = animateFloatAsState(
        if (reorder.held < 0 || held) 0f else RowDrag.shift(index, reorder.held, reorder.target) * reorder.pitch,
        tween(SLIDE_MS, easing = Motion.EaseOut), label = "reorder-shift",
    )
    return zIndex(if (held) 1f else 0f).drawWithContent {
        if (held) {
            val grow = 1f + (LIFT_SCALE - 1f) * reorder.lift.value
            translate(top = reorder.offset) { scale(grow) { this@drawWithContent.drawContent() } }
        } else {
            translate(top = shift.value) { this@drawWithContent.drawContent() }
        }
    }
}

@Composable
internal fun Modifier.reorderLift(reorder: Reorder, index: Int, corner: Dp): Modifier {
    if (reorder.held != index) return this
    return dropShadow(RoundedCornerShape(corner)) {
        val l = reorder.lift.value
        radius = 14.dp.toPx() * l
        offset = Offset(0f, 5.dp.toPx() * l)
        color = Color.Black
        alpha = 0.55f * l
    }.drawWithContent {
        drawContent()
        val l = reorder.lift.value
        if (l <= 0f) return@drawWithContent
        val w = 1.5.dp.toPx()
        drawRoundRect(Am.Accent, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(corner.toPx() - w / 2), alpha = l, style = Stroke(w))
    }
}

@Composable
internal fun Modifier.reorderGrip(reorder: Reorder, index: Int, count: Int, commit: (Int, Int) -> Unit): Modifier {
    val at by rememberUpdatedState(index)
    val size by rememberUpdatedState(count)
    val moved by rememberUpdatedState(commit)
    return pointerInput(reorder) {
        detectDragGesturesAfterLongPress(
            onDragStart = { reorder.pick(at, it.y, size) },
            onDragEnd = { reorder.drop(moved) },
            onDragCancel = { reorder.drop(moved) },
        ) { change, _ ->
            change.consume()
            reorder.move(change.position.y)
        }
    }
}

private const val LIFT_SCALE = 1.03f
private const val LIFT_MS = 100
private const val SLIDE_MS = 120
private const val DROP_MS = 150
