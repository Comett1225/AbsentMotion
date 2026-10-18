package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.absentmotion.editor.EditorState
import kotlin.math.exp
import kotlin.math.log10

private val MeterGreen = Color(0xFF34C759)
private val MeterYellow = Color(0xFFFFCC00)
private val MeterRed = Color(0xFFFF3B30)

@Composable
internal fun LevelMeter(state: EditorState, modifier: Modifier = Modifier) {
    var shown by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.playing) {
        shown = 0f; peak = 0f; flash = 0f
        if (!state.playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        var peakAge = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val level = state.engine.audio?.level ?: 0f
            val target = if (level > 0f) (1f + 20f * log10(level) / 48f).coerceIn(0f, 1f) else 0f
            shown = maxOf(target, shown * exp(-dt / 0.11f))
            if (shown >= peak) {
                peak = shown
                peakAge = 0f
            } else {
                peakAge += dt
                if (peakAge > 0.6f) peak = maxOf(shown, peak - 1.5f * dt)
            }
            flash = if (level >= 1f) 0.5f else maxOf(0f, flash - dt)
        }
    }
    Canvas(modifier) {
        if (flash > 0f) { drawRect(MeterRed); return@Canvas }
        val mid = size.width / 2
        if (shown > 0f) {
            val ramp = Brush.horizontalGradient(0f to MeterRed, 0.15f to MeterYellow, 0.5f to MeterGreen, 0.85f to MeterYellow, 1f to MeterRed)
            drawRect(ramp, Offset(mid - shown * mid, 0f), Size(2 * shown * mid, size.height))
        }
        if (peak > 0f) {
            val tick = 2.dp.toPx()
            for (x in floatArrayOf(mid - peak * mid, mid + peak * mid - tick)) {
                drawRect(Color.White.copy(alpha = 0.7f), Offset(x.coerceIn(0f, size.width - tick), 0f), Size(tick, size.height))
            }
        }
    }
}
