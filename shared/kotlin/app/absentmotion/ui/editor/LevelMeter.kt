package app.absentmotion.ui.editor

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.absentmotion.editor.EditorState
import kotlin.math.floor

private val MeterGreen = Color(0xFF34C759)
private val MeterYellow = Color(0xFFFFCC00)
private val MeterRed = Color(0xFFFF3B30)
private val PeakTick = Color.White.copy(alpha = 0.7f)

@Composable
internal fun LevelMeter(state: EditorState, modifier: Modifier = Modifier) {
    val left = remember { MeterBallistics() }
    val right = remember { MeterBallistics() }
    LaunchedEffect(state.playing) {
        left.reset()
        right.reset()
        if (!state.playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val mix = state.engine.audio?.mix
            left.step(mix?.masterLeft ?: 0f, dt)
            right.step(mix?.masterRight ?: 0f, dt)
        }
    }
    Spacer(modifier.drawWithCache {
        val ramp = Brush.horizontalGradient(0f to MeterRed, 0.15f to MeterYellow, 0.5f to MeterGreen, 0.85f to MeterYellow, 1f to MeterRed, startX = 0f, endX = size.width)
        val tick = 2.dp.toPx()
        onDrawBehind {
            val mid = floor(size.width / 2)
            val half = size.width - mid
            val h = size.height
            if (left.clip > 0f) drawRect(MeterRed, Offset.Zero, Size(mid, h))
            else {
                val shown = left.shown
                if (shown > 0f) drawRect(ramp, Offset(mid - shown * mid, 0f), Size(shown * mid, h))
                val peak = left.peak
                if (peak > 0f) drawRect(PeakTick, Offset((mid - peak * mid).coerceIn(0f, size.width - tick), 0f), Size(tick, h))
            }
            if (right.clip > 0f) drawRect(MeterRed, Offset(mid, 0f), Size(size.width - mid, h))
            else {
                val shown = right.shown
                if (shown > 0f) drawRect(ramp, Offset(mid, 0f), Size(shown * half, h))
                val peak = right.peak
                if (peak > 0f) drawRect(PeakTick, Offset((mid + peak * half - tick).coerceIn(0f, size.width - tick), 0f), Size(tick, h))
            }
        }
    })
}
