package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.model.CropBox
import app.absentmotion.model.cropBox
import app.absentmotion.model.cropIndex

internal const val CROP_INSIDE = 8

internal fun cropCorners(q: List<Offset>, box: CropBox): List<Offset> {
    val x0 = box.left
    val x1 = 1f - box.right
    val y0 = box.top
    val y1 = 1f - box.bottom
    return listOf(boxPoint(q, x0, y0), boxPoint(q, x1, y0), boxPoint(q, x1, y1), boxPoint(q, x0, y1))
}

internal fun cropHandles(q: List<Offset>, box: CropBox): List<Offset> = cropCorners(q, box).let { c -> c + List(4) { (c[it] + c[(it + 1) % 4]) / 2f } }

internal fun cropGrabAt(q: List<Offset>, box: CropBox, at: Offset, density: Float): Int {
    val corners = cropCorners(q, box)
    val handle = HandleHit.at(cropHandles(q, box), at, HandleHit.reach(corners, density))
    if (handle >= 0) return handle
    val f = boxFraction(q, at)
    return if (f.x in box.left..1f - box.right && f.y in box.top..1f - box.bottom) CROP_INSIDE else -1
}

@Composable
internal fun CropOverlay(state: EditorState, quad: FloatArray?, scale: Float, origin: Offset) {
    val layer = state.selected ?: return
    if (state.panel != EditorPanel.Crop || quad == null || layer.cropIndex < 0) return
    val q = List(4) { Offset(quad[2 * it], quad[2 * it + 1]) * scale + origin }
    val box = layer.cropBox()
    val description = stringResource(R.string.a11y_crop_area)
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
        val c = cropCorners(q, box)
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close()
            moveTo(c[0].x, c[0].y); c.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        drawPath(dim, Color.Black.copy(alpha = 0.6f))
        val thin = 1.dp.toPx()
        for (i in 1..2) {
            val f = i / 3f
            drawLine(Color.White.copy(alpha = 0.35f), c[0] + (c[1] - c[0]) * f, c[3] + (c[2] - c[3]) * f, thin)
            drawLine(Color.White.copy(alpha = 0.35f), c[0] + (c[3] - c[0]) * f, c[1] + (c[2] - c[1]) * f, thin)
        }
        for (i in 0..3) {
            drawLine(Color.Black.copy(alpha = 0.5f), c[i], c[(i + 1) % 4], thin * 3f)
            drawLine(Color.White, c[i], c[(i + 1) % 4], thin * 1.5f)
        }
        val arm = 16.dp.toPx()
        for (i in 0..3) {
            val next = c[(i + 1) % 4] - c[i]
            val prev = c[(i + 3) % 4] - c[i]
            bracket(c[i], c[i] + next * (arm / next.getDistance().coerceAtLeast(arm)), c[i] + prev * (arm / prev.getDistance().coerceAtLeast(arm)))
            val mid = (c[i] + c[(i + 1) % 4]) / 2f
            val half = next * (arm * 0.6f / next.getDistance().coerceAtLeast(arm))
            bracket(mid, mid - half, mid + half)
        }
    }
}

private fun DrawScope.bracket(at: Offset, a: Offset, b: Offset) {
    val width = 4.dp.toPx()
    drawLine(Color.Black.copy(alpha = 0.55f), a, at, width + 2.dp.toPx(), StrokeCap.Round)
    drawLine(Color.Black.copy(alpha = 0.55f), at, b, width + 2.dp.toPx(), StrokeCap.Round)
    drawLine(Color.White, a, at, width, StrokeCap.Round)
    drawLine(Color.White, at, b, width, StrokeCap.Round)
}
