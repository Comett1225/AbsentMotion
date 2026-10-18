package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.gradient
import app.absentmotion.ui.editor.panels.textOn

@Composable
internal fun GradientHandles(state: EditorState, quad: FloatArray?, scale: Float, origin: Offset) {
    val layer = state.selected ?: return
    if (state.panel != EditorPanel.ColorFill || layer.fillType != "gradient" || quad == null) return
    val g = layer.gradient
    val q = List(4) { Offset(quad[2 * it], quad[2 * it + 1]) * scale + origin }
    val start = boxPoint(q, g.x0, g.y0)
    val end = boxPoint(q, g.x1, g.y1)
    Canvas(Modifier.fillMaxSize()) {
        drawLine(Color.White, start, end, 3.dp.toPx(), StrokeCap.Round)
        drawLine(Color.Black, start, end, 1.dp.toPx(), StrokeCap.Round)
        handle(start, g.startColor)
        handle(end, g.endColor)
    }
}

private fun DrawScope.handle(at: Offset, argb: Int) {
    val ring = textOn(argb)
    val r = 9.dp.toPx()
    drawCircle(Color(argb), r, at)
    drawCircle(ring, r - 1.25.dp.toPx(), at, style = Stroke(2.5.dp.toPx()))
    drawCircle(Color(argb), r + 0.5.dp.toPx(), at, style = Stroke(1.dp.toPx()))
}

internal fun boxPoint(q: List<Offset>, u: Float, v: Float): Offset = q[0] + (q[1] - q[0]) * u + (q[3] - q[0]) * v

internal fun boxFraction(q: List<Offset>, p: Offset): Offset {
    val ex = q[1] - q[0]
    val ey = q[3] - q[0]
    val det = (ex.x * ey.y - ex.y * ey.x).takeIf { it != 0f } ?: return Offset.Zero
    val d = p - q[0]
    return Offset((d.x * ey.y - d.y * ey.x) / det, (ex.x * d.y - ex.y * d.x) / det)
}

internal fun gradientHandleAt(layer: app.absentmotion.model.Layer, q: List<Offset>, at: Offset, radius: Float): Int {
    val g = layer.gradient
    return listOf(boxPoint(q, g.x0, g.y0), boxPoint(q, g.x1, g.y1)).indexOfFirst { (it - at).getDistance() < radius }.let { if (it < 0) -1 else 10 + it }
}
