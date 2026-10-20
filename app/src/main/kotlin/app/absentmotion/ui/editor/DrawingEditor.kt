package app.absentmotion.ui.editor

import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import app.absentmotion.ui.editor.panels.ThinSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.absentmotion.platform.drawLiveStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.DrawingMode
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FreehandMode
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.TrackMode
import app.absentmotion.editor.leaveDrawing
import app.absentmotion.editor.shapeKeyFrames
import app.absentmotion.editor.toggleShapeKey
import app.absentmotion.editor.worldMatrix
import app.absentmotion.model.DrawTool
import app.absentmotion.model.Stroke
import app.absentmotion.model.StrokePoint
import app.absentmotion.model.withStroke
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private val Bar get() = Am.Button

@Composable
internal fun DrawingEditor(state: EditorState) {
    val mode = state.drawing ?: return
    BackHandler { state.leaveDrawing() }
    LaunchedEffect(mode.layerId) { state.playing = false }
    val gone = state.scene.layer(mode.layerId) == null
    LaunchedEffect(gone) { if (gone) state.drawing = null }
    DisposableEffect(mode) { onDispose { state.endGesture() } }
    Column(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding()) {
        DrawingBar(state, mode, Modifier.fillMaxWidth().height(42.dp).enterFade())
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Preview(state, Modifier.fillMaxSize())
            when (mode) {
                is FreehandMode -> FreehandOverlay(state, mode, Modifier.fillMaxSize())
                is PointMode -> PointOverlay(state, mode, Modifier.fillMaxSize())
                is TrackMode -> TrackOverlay(state, mode, Modifier.fillMaxSize())
            }
        }
        if (mode !is FreehandMode) DrawingTimeBar(state, mode, Modifier.fillMaxWidth().height(40.dp))
        Box(Modifier.fillMaxWidth().height(200.dp).enterFade().background(Am.Panel).pointerInput(Unit) {}) {
            when (mode) {
                is FreehandMode -> FreehandPanel(mode)
                is PointMode -> PointPanel(state, mode)
                is TrackMode -> TrackPanel(state, mode)
            }
        }
    }
}

@Composable
private fun DrawingBar(state: EditorState, mode: DrawingMode, modifier: Modifier) {
    val cell = Modifier.width(40.dp).fillMaxHeight()
    Row(modifier.background(Bar), verticalAlignment = Alignment.CenterVertically) {
        BarButton(ChromeIcons.Back, "Back", cell) { state.leaveDrawing() }
        Text(
            when { mode is TrackMode -> "Track Motion"; mode is PointMode && mode.vector -> "Pen Tool"; mode is PointMode -> "Edit Points"; else -> "Freehand Brush" },
            Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp, maxLines = 1,
        )
        val busy = (mode as? TrackMode)?.running == true
        BarButton(ChromeIcons.Undo, "Undo", cell, enabled = state.canUndo && !busy) { state.undo() }
        BarButton(ChromeIcons.Redo, "Redo", cell, enabled = state.canRedo && !busy) { state.redo() }
    }
}

@Composable
private fun DrawingTimeBar(state: EditorState, mode: DrawingMode, modifier: Modifier) {
    val layer = state.scene.layer(mode.layerId)
    val locked = (mode as? TrackMode)?.let { it.running || it.lostFrame != null } == true
    val first = layer?.let { state.scene.firstShownFrame(it.startTime) } ?: 0
    val last = layer?.let { state.scene.lastShownFrame(it.endTime) } ?: 0
    val cell = Modifier.width(48.dp).fillMaxHeight()
    val point = mode as? PointMode
    val keys = point?.let(state::shapeKeyFrames).orEmpty()
    Row(modifier.background(Am.PanelInset), verticalAlignment = Alignment.CenterVertically) {
        if (point != null) BarButton(ChromeIcons.SkipBack, "Previous shape keyframe", cell, enabled = keys.any { it < state.frame }) {
            keys.lastOrNull { it < state.frame }?.let { state.frame = it.coerceIn(first, last) }
        }
        BarButton(DrawingIcons.Prev, "Previous frame", cell, enabled = !locked && state.frame > first) { state.frame = (state.frame - 1).coerceAtLeast(first) }
        Text(TimelineMath.timecode(state.frame, state.scene.fps), Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
        BarButton(DrawingIcons.Next, "Next frame", cell, enabled = !locked && state.frame < last) { state.frame = (state.frame + 1).coerceAtMost(last) }
        if (point != null) {
            BarButton(ChromeIcons.SkipForward, "Next shape keyframe", cell, enabled = keys.any { it > state.frame }) {
                keys.firstOrNull { it > state.frame }?.let { state.frame = it.coerceIn(first, last) }
            }
            val on = state.frame in keys
            Box(cell.clickable { state.toggleShapeKey(point) }, Alignment.Center) {
                Icon(if (on) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, if (on) "Remove shape keyframe" else "Add shape keyframe",
                    Modifier.size(26.dp), tint = if (keys.isNotEmpty()) Am.Accent else Am.OnDark)
            }
        }
    }
}

private class LiveStroke(val tool: DrawTool, val color: Int, val width: Float, val toScreen: app.absentmotion.platform.Matrix, val opacity: Float) {
    val points = ArrayList<StrokePoint>()
    var version by mutableIntStateOf(0)
    fun add(x: Float, y: Float, pressure: Float) {
        val last = points.lastOrNull()
        if (last != null && hypot(last.x - x, last.y - y) < 0.3f) return
        points += StrokePoint(x, y, pressure)
        version++
    }
}

@Composable
private fun FreehandOverlay(state: EditorState, mode: FreehandMode, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scene = state.scene
    val scale = if (size.width == 0) 1f else min(size.width.toFloat() / scene.width, size.height.toFloat() / scene.height)
    val dx = (size.width - scene.width * scale) / 2
    val dy = (size.height - scene.height * scale) / 2
    val view by rememberUpdatedState(Triple(scale, dx, dy))
    val strokes = remember { mutableStateListOf<LiveStroke>() }
    val scope = rememberCoroutineScope()
    Canvas(
        modifier.onSizeChanged { size = it }.pointerInput(state, mode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val layer = state.scene.layerAt(mode.layerId, state.timeMs) ?: return@awaitEachGesture
                val (s, ox, oy) = view
                val toScene = state.scene.worldMatrix(mode.layerId, state.timeMs) ?: return@awaitEachGesture
                val toLayer = app.absentmotion.platform.Matrix().also { toScene.invert(it) }
                val toScreen = app.absentmotion.platform.Matrix().apply { setTranslate(ox, oy); preScale(s, s); preConcat(toScene) }
                val live = LiveStroke(mode.tool, mode.color, mode.width, toScreen, layer.opacity)
                fun local(p: Offset): FloatArray = floatArrayOf((p.x - ox) / s, (p.y - oy) / s).also { toLayer.mapPoints(it) }
                var pressure = down.pressure.coerceIn(0f, 1f)
                local(down.position).let { live.add(it[0], it[1], pressure) }
                strokes += live
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                        val now = change.pressure.coerceIn(0f, 1f)
                        val history = change.historical
                        history.forEachIndexed { i, h ->
                            local(h.position).let { live.add(it[0], it[1], pressure + (now - pressure) * (i + 1) / (history.size + 1)) }
                        }
                        local(change.position).let { live.add(it[0], it[1], now) }
                        pressure = now
                        change.consume()
                    }
                } finally {
                    if (live.points.isNotEmpty()) {
                        state.edit { sc -> sc.update(mode.layerId) { it.withStroke(Stroke(live.points.toList(), mode.color, mode.tool, mode.width)) } }
                    }
                    scope.launch { delay(90); strokes.remove(live) }
                }
            }
        },
    ) {
        for (stroke in strokes) {
            stroke.version
            val (path, filled) = strokeShape(stroke.points, stroke.tool, stroke.width)
            drawLiveStroke(path, filled, stroke.color, stroke.width, stroke.opacity, stroke.toScreen, stroke.tool == DrawTool.Eraser)
        }
    }
}

internal fun strokeShape(points: List<StrokePoint>, tool: DrawTool, width: Float): Pair<app.absentmotion.platform.DrawPath, Boolean> {
    val path = app.absentmotion.platform.DrawPath()
    if (points.isEmpty()) return path to false
    when {
        tool == DrawTool.Fill -> {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
            path.close()
            return path to true
        }
        tool == DrawTool.Brush -> {
            band(path, points, FloatArray(points.size) { maxOf(0.01f, points[it].pressure * width / 2) })
            return path to true
        }
        points.size == 1 -> {
            path.addCircle(points[0].x, points[0].y, maxOf(0.01f, width / 2), true)
            return path to true
        }
        else -> {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
            return path to false
        }
    }
}

private fun band(path: app.absentmotion.platform.DrawPath, pts: List<StrokePoint>, radius: FloatArray) {
    for (i in pts.indices) path.addCircle(pts[i].x, pts[i].y, radius[i], true)
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val c = pts[i + 1]
        val r0 = radius[i]
        val r1 = radius[i + 1]
        val d = hypot(c.x - a.x, c.y - a.y)
        if (d <= Math.abs(r0 - r1) + 1e-4f) continue
        val ux = (c.x - a.x) / d
        val uy = (c.y - a.y) / d
        val nx = -uy
        val ny = ux
        val k = (r0 - r1) / d
        val h = Math.sqrt(maxOf(0f, 1 - k * k).toDouble()).toFloat()
        val m1x = k * ux + h * nx
        val m1y = k * uy + h * ny
        val m2x = k * ux - h * nx
        val m2y = k * uy - h * ny
        val q = floatArrayOf(
            a.x + r0 * m1x, a.y + r0 * m1y, c.x + r1 * m1x, c.y + r1 * m1y,
            c.x + r1 * m2x, c.y + r1 * m2y, a.x + r0 * m2x, a.y + r0 * m2y,
        )
        var area = 0f
        for (j in 0 until 4) area += q[2 * j] * q[2 * ((j + 1) % 4) + 1] - q[2 * ((j + 1) % 4)] * q[2 * j + 1]
        val order = if (area < 0) intArrayOf(3, 2, 1, 0) else intArrayOf(0, 1, 2, 3)
        path.moveTo(q[2 * order[0]], q[2 * order[0] + 1])
        for (j in 1 until 4) path.lineTo(q[2 * order[j]], q[2 * order[j] + 1])
        path.close()
    }
}

private val Widths = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 96, 112, 128, 160, 192, 224, 256, 300)

@Composable
private fun FreehandPanel(mode: FreehandMode) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(DrawingIcons.SizeDot, null, Modifier.padding(start = 8.dp).size(22.dp), tint = Am.OnDark)
                val index = Widths.indexOfLast { it <= mode.width }.coerceAtLeast(0)
                ThinSlider(
                    index.toFloat(), { mode.resize(Widths[it.roundToInt().coerceIn(0, Widths.lastIndex)].toFloat()) },
                    Modifier.weight(1f).padding(horizontal = 6.dp), valueRange = 0f..Widths.lastIndex.toFloat(),
                    active = Am.Accent, inactive = Am.FieldHi, thumb = Am.OnDark,
                )
                Box(Modifier.padding(end = 8.dp).size(35.dp, 22.dp).clip(RoundedCornerShape(4.dp)).background(Am.Field), Alignment.Center) {
                    Text("${mode.width.roundToInt()}", color = Am.Accent, fontSize = 12.sp, maxLines = 1)
                }
            }
            DrawColorPicker(mode.color, mode::recolour, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp).padding(bottom = 8.dp))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Am.PreviewBg))
        Column(Modifier.width(59.dp).fillMaxHeight()) {
            for ((tool, icon) in listOf(DrawTool.Pen to DrawingIcons.Pen, DrawTool.Brush to DrawingIcons.Brush, DrawTool.Fill to DrawingIcons.Fill, DrawTool.Eraser to DrawingIcons.Eraser)) {
                Box(Modifier.weight(1f).fillMaxWidth().clickable { mode.pick(tool) }, Alignment.Center) {
                    Icon(icon, tool.xml, Modifier.size(26.dp), tint = app.absentmotion.ui.theme.animatedColor(if (mode.tool == tool) Am.Accent else Am.OnDark))
                }
            }
        }
    }
}

@Composable
private fun DrawColorPicker(argb: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    val start = remember { FloatArray(3).also { app.absentmotion.platform.Color.colorToHSV(argb, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var bright by remember { mutableFloatStateOf(start[2]) }
    var opacity by remember { mutableFloatStateOf(app.absentmotion.platform.Color.alpha(argb) / 255f) }
    fun emit() = onChange(app.absentmotion.platform.Color.HSVToColor((opacity * 255).roundToInt(), floatArrayOf(hue, sat, bright)))
    val hueColor = Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val opaque = Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(hue, sat, bright)))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).dragPoint { p, s ->
            sat = (p.x / s.width).coerceIn(0f, 1f); bright = 1f - (p.y / s.height).coerceIn(0f, 1f); emit()
        }) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            knob(Offset(sat * size.width, (1f - bright) * size.height), opaque)
        }
        Canvas(Modifier.width(28.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).dragPoint { p, s -> hue = (p.y / s.height).coerceIn(0f, 1f) * 359.9f; emit() }) {
            drawRect(Brush.verticalGradient(List(7) { Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) }))
            knob(Offset(size.width / 2, hue / 359.9f * size.height), hueColor)
        }
        Canvas(Modifier.width(28.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color(0xFFB0B0B0)).dragPoint { p, s -> opacity = 1f - (p.y / s.height).coerceIn(0f, 1f); emit() }) {
            drawRect(Brush.verticalGradient(listOf(opaque, opaque.copy(alpha = 0f))))
            knob(Offset(size.width / 2, (1f - opacity) * size.height), opaque.copy(alpha = opacity))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.knob(at: Offset, fill: Color) {
    drawCircle(Color.Black, 9.dp.toPx(), at)
    drawCircle(Color.White, 8.dp.toPx(), at)
    drawCircle(fill, 6.dp.toPx(), at)
}

private fun Modifier.dragPoint(onPoint: (Offset, IntSize) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPoint(down.position, size)
        down.consume()
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.positionChange() != Offset.Zero) onPoint(change.position, size)
            change.consume()
        }
    }
}
