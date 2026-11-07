package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import app.absentmotion.editor.Glide
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun EffectRuler(
    value: Float, step: Float, min: Float, max: Float, enabled: Boolean,
    onStart: () -> Unit, onValue: (Float) -> Unit, onEnd: () -> Unit,
    modifier: Modifier = Modifier, default: Float? = null, vibrate: Boolean = true,
) {
    val density = LocalDensity.current.density
    val spacing = 5.5f * density
    val s = step.takeIf { it > 0f } ?: 1f
    val current by rememberUpdatedState(value)
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onValue)
    val end by rememberUpdatedState(onEnd)
    var carry by remember { mutableFloatStateOf(0f) }
    fun clamp(v: Float) = ((v / s).roundToInt() * s).coerceIn(min, max)
    Canvas(modifier
        .pointerInput(enabled, s, min, max) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { Glide.stop(); carry = current; start() },
                onDragEnd = { end() },
                onDragCancel = { end() },
            ) { change2, dx ->
                change2.consume()
                carry -= dx / spacing * s
                val next = clamp(carry)
                if (next != current) change(next)
            }
        }
        .pointerInput(enabled, default) {
            if (!enabled || default == null) return@pointerInput
            detectTapGestures(onDoubleTap = { start(); change(clamp(default)); end() })
        }
        .pointerInput(enabled, s, min, max) {
            if (!enabled) return@pointerInput
            coroutineScope {
                var pending = 0f
                var target = 0f
                var open = false
                var closing: Job? = null
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Scroll) continue
                            val d = e.changes.firstOrNull()?.scrollDelta ?: continue
                            val amount = if (abs(d.x) > abs(d.y)) d.x else d.y
                            if (amount == 0f) continue
                            e.changes.forEach { it.consume() }
                            pending += amount
                            val steps = pending.toInt()
                            if (steps == 0) continue
                            pending -= steps
                            if (!open) { start(); open = true; target = current }
                            target = clamp(target - steps * s)
                            change(target)
                            closing?.cancel()
                            closing = launch { delay(300); open = false; pending = 0f; end() }
                        }
                    }
                } finally { if (open) end() }
            }
        }
    ) {
        val mid = size.width / 2f
        val ticks = (size.width / spacing / 2).toInt() + 2
        val base = (value - min) / s
        val frac = base - base.toInt()
        for (i in -ticks..ticks) {
            val x = mid + (i - frac) * spacing
            if (x < 8f * density || x > size.width - 8f * density) continue
            val index = base.toInt() + i
            val at = min + index * s
            if (at < min - s / 2 || at > max + s / 2) continue
            val big = index % 10 == 0
            val h = size.height * if (big) 0.5f else 0.28f
            drawLine(if (enabled) Am.OnDarkDim else Am.Inactive, Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = 0.5f * density + if (big) 0.5f * density else 0f, cap = StrokeCap.Round)
        }
        drawLine(if (enabled) Am.Accent else Am.Inactive, Offset(mid, size.height * 0.15f), Offset(mid, size.height * 0.85f), strokeWidth = 1.5f * density, cap = StrokeCap.Round)
    }
}
