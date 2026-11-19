package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.model.CropBox
import app.absentmotion.model.Layer
import app.absentmotion.model.cropBox
import app.absentmotion.model.cropIndex
import app.absentmotion.model.effects

internal const val CROP_INSIDE = 8

internal fun cropCorners(q: List<Offset>, box: CropBox): List<Offset> {
    val x0 = box.left
    val x1 = 1f - box.right
    val y0 = box.top
    val y1 = 1f - box.bottom
    return listOf(boxPoint(q, x0, y0), boxPoint(q, x1, y0), boxPoint(q, x1, y1), boxPoint(q, x0, y1))
}

internal fun uncroppedQuad(q: FloatArray, layer: Layer): FloatArray {
    val index = layer.cropIndex
    if (index < 0 || layer.kind == "embedScene" || layer.effects[index]["hidden"] == "true") return q
    val b = layer.cropBox(index)
    if (b.left <= 0f && b.top <= 0f && b.right <= 0f && b.bottom <= 0f) return q
    val c = cropCorners(List(4) { Offset(q[2 * it], q[2 * it + 1]) }, CropBox(-b.left / b.width, -b.top / b.height, -b.right / b.width, -b.bottom / b.height))
    return FloatArray(8) { if (it % 2 == 0) c[it / 2].x else c[it / 2].y }
}

internal fun cropHandles(q: List<Offset>, box: CropBox): List<Offset> = cropCorners(q, box).let { c -> c + List(4) { (c[it] + c[(it + 1) % 4]) / 2f } }

internal fun cropGrabAt(q: List<Offset>, box: CropBox, at: Offset, density: Float): Int {
    val corners = cropCorners(q, box)
    val handle = HandleHit.at(cropHandles(q, box), at, HandleHit.reach(corners, density))
    if (handle >= 0) return handle
    val f = boxFraction(q, at)
    return if (f.x in box.left..1f - box.right && f.y in box.top..1f - box.bottom) CROP_INSIDE else -1
}

internal const val CROP_FILL = 0.72f

internal data class CanvasView(val zoom: Float, val pan: Offset)

internal fun cropView(width: Float, height: Float, reserve: Float, sceneW: Float, sceneH: Float, corners: List<Offset>): CanvasView? {
    if (width <= 0f || height <= 0f || corners.isEmpty()) return null
    val free = width - reserve.coerceAtMost(width / 2f)
    val fit = minOf(free / sceneW, height / sceneH)
    val left = corners.minOf { it.x }
    val top = corners.minOf { it.y }
    val w = (corners.maxOf { it.x } - left).coerceAtLeast(1e-3f)
    val h = (corners.maxOf { it.y } - top).coerceAtLeast(1e-3f)
    val zoom = (minOf(free * CROP_FILL / w, height * CROP_FILL / h) / fit).coerceIn(0.01f, 64f)
    val centre = minOf(width / 2f, free - sceneW * fit / 2f)
    return CanvasView(zoom, Offset(sceneW / 2f - (left + w / 2f) + (free / 2f - centre) / (fit * zoom), sceneH / 2f - (top + h / 2f)))
}

internal fun viewBetween(from: CanvasView, to: CanvasView, t: Float): CanvasView {
    if (t >= 1f) return to
    val ratio = to.zoom / from.zoom
    if (kotlin.math.abs(ratio - 1f) < 1e-3f) return CanvasView(from.zoom + (to.zoom - from.zoom) * t, from.pan + (to.pan - from.pan) * t)
    val zoom = from.zoom * Math.pow(ratio.toDouble(), t.toDouble()).toFloat()
    val still = (to.pan * to.zoom - from.pan * from.zoom) / (to.zoom - from.zoom)
    return CanvasView(zoom, still - (still - from.pan) * (from.zoom / zoom))
}

private class CropViewMemory(var before: CanvasView? = null, var fitted: Any? = null, var written: CanvasView? = null)

@Composable
internal fun CropAutoView(state: EditorState, quad: FloatArray?, size: IntSize, reserve: Float, touching: Boolean) {
    val active = state.livePanel == EditorPanel.Crop && state.drawing == null
    val box = if (active) state.selected?.takeIf { it.cropIndex >= 0 }?.cropBox() else null
    val memory = remember { CropViewMemory() }
    val corners = if (box != null && quad != null) cropCorners(List(4) { Offset(quad[2 * it], quad[2 * it + 1]) }, box) else null
    LaunchedEffect(active, corners, size, reserve, touching) {
        suspend fun glide(to: CanvasView) {
            val from = CanvasView(state.canvasZoom, state.canvasPan)
            var mine = true
            memory.written = from
            Animatable(0f).animateTo(1f, tween(280, easing = FastOutSlowInEasing)) {
                if (mine && CanvasView(state.canvasZoom, state.canvasPan) != memory.written) mine = false
                if (mine) viewBetween(from, to, value).let { memory.written = it; state.canvasZoom = it.zoom; state.canvasPan = it.pan }
            }
        }
        if (!active) {
            memory.before?.let { glide(it) }
            memory.before = null
            memory.fitted = null
            memory.written = null
            return@LaunchedEffect
        }
        if (memory.before == null) memory.before = CanvasView(state.canvasZoom, state.canvasPan)
        val key = Triple(corners ?: return@LaunchedEffect, size, reserve)
        if (touching || key == memory.fitted) return@LaunchedEffect
        if (memory.written.let { it != null && it != CanvasView(state.canvasZoom, state.canvasPan) }) {
            memory.fitted = key
            memory.written = null
            return@LaunchedEffect
        }
        glide(cropView(size.width.toFloat(), size.height.toFloat(), reserve, state.scene.width.toFloat(), state.scene.height.toFloat(), corners) ?: return@LaunchedEffect)
        memory.fitted = key
        memory.written = null
    }
}

@Composable
internal fun CropOverlay(state: EditorState, quad: FloatArray?, scale: Float, origin: Offset) {
    if (state.panel != EditorPanel.Crop || quad == null) return
    val layer = state.selected ?: return
    if (layer.cropIndex < 0) return
    val q = List(4) { Offset(quad[2 * it], quad[2 * it + 1]) * scale + origin }
    val box = layer.cropBox()
    val description = stringResource(R.string.a11y_crop_area)
    Canvas(Modifier.fillMaxSize().clipToBounds().semantics { contentDescription = description }) {
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
