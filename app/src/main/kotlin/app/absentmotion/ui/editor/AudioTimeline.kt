package app.absentmotion.ui.editor

import app.absentmotion.model.finiteDouble
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.absentmotion.audio.Waveforms
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import kotlin.math.max
import kotlin.math.min
import app.absentmotion.ui.theme.Am

private val AudioBar get() = Am.AudioBar
private val AudioWave get() = Am.AudioWave
private val AudioShadow get() = Am.PreviewBg

@Composable
internal fun LoadWaveforms(scene: Scene) {
    val context = LocalContext.current
    val uris = scene.layers.filter { it.kind == "audio" }.mapNotNull { it.node["src"] }.toSet()
    LaunchedEffect(uris) { uris.forEach { Waveforms.request(context, it) } }
}

private val timings = HashMap<Long, Pair<XmlNode, VideoTiming>>()

internal fun timingOf(layer: Layer): VideoTiming {
    val cached = timings[layer.id]
    if (cached != null && cached.first === layer.node) return cached.second
    return VideoTiming(layer).also { timings[layer.id] = layer.node to it }
}

internal fun DrawScope.drawAudioBar(outline: Path, layer: Layer, left: Float, right: Float, top: Float, bottom: Float, pxPerMs: Float) {
    val radius = 2.dp.toPx()
    val from = max(left, -2 * radius)
    val to = min(right, size.width + 2 * radius)
    if (from >= to) return
    drawRect(AudioShadow, Offset(from, bottom - radius), Size(to - from, radius + 2.dp.toPx()))
    outline.rewind()
    outline.addRoundRect(RoundRect(from, top, to, bottom, CornerRadius(radius)))
    clipPath(outline) {
        drawRect(AudioBar, Offset(from, top), Size(to - from, bottom - top))
        val centre = (top + bottom) / 2
        drawLine(AudioWave.copy(alpha = 0.6f), Offset(from, centre), Offset(to, centre), 1.dp.toPx())
        val peaks = layer.node["src"]?.let { Waveforms.peaks(it) } ?: return@clipPath
        val timing = timingOf(layer)
        val inMs = layer.node["inTime"]?.finiteDouble() ?: 0.0
        val outMs = layer.node["outTime"]?.finiteDouble() ?: Double.MAX_VALUE
        val column = 2.dp.toPx()
        val room = (bottom - top) / 2 - 3.dp.toPx()
        var x = left + column * kotlin.math.floor((from - left) / column)
        while (x < to) {
            val a = timing.mediaMs((x - left) / pxPerMs.toDouble())
            val b = timing.mediaMs((x + column - left) / pxPerMs.toDouble())
            if (a >= inMs && a < outMs) {
                val half = max(0.5.dp.toPx(), peaks.peak(a, max(a, b)) * room)
                drawLine(AudioWave, Offset(x + column / 2, centre - half), Offset(x + column / 2, centre + half), column * 0.6f)
            }
            x += column
        }
    }
}
