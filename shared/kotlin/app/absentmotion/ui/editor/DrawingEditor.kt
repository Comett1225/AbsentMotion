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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.editor.DrawingMode
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FreehandMode
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.TrackMode
import app.absentmotion.editor.leaveDrawing
import app.absentmotion.editor.shapeKeyFrames
import app.absentmotion.editor.toggleShapeKey
import app.absentmotion.editor.screenMatrix
import app.absentmotion.model.DrawTool
import app.absentmotion.model.Stroke
import app.absentmotion.model.StrokePoint
import app.absentmotion.model.withStroke
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt
import app.absentmotion.ui.HoldName
import app.absentmotion.editor.BrushWidths
import app.absentmotion.editor.Pressure
import app.absentmotion.model.caughtUp
import app.absentmotion.model.smoothTo
import app.absentmotion.model.speedPressure
import app.absentmotion.model.touchedBy
import app.absentmotion.model.withoutStrokes
import app.absentmotion.platform.Hint
import app.absentmotion.platform.readPreviewPixel
import app.absentmotion.ui.adaptive.deskLayout
import app.absentmotion.ui.editor.panels.drawLoupe
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalHapticFeedback
import app.absentmotion.model.strokes
import kotlin.math.abs
import kotlin.math.sqrt

private fun DrawTool.labelRes() = when (this) {
    DrawTool.Pen -> R.string.draw_tool_pen
    DrawTool.Brush -> R.string.draw_tool_brush
    DrawTool.Fill -> R.string.draw_tool_fill
    DrawTool.Eraser -> R.string.draw_tool_eraser
}

private val Bar get() = Am.Button

@Composable
internal fun DrawingEditor(state: EditorState) {
    val mode = state.drawing ?: return
    BackHandler { state.leaveDrawing() }
    DrawingSession(state, mode)
    Column(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding()) {
        DrawingBar(state, mode, Modifier.fillMaxWidth().height(42.dp).enterFade())
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Preview(state, Modifier.fillMaxSize())
            when (mode) {
                is FreehandMode -> FreehandOverlay(state, mode, Modifier.fillMaxSize())
                is PointMode -> PointOverlay(state, mode, Modifier.fillMaxSize())
                is TrackMode -> TrackOverlay(state, mode, Modifier.fillMaxSize())
            }
            if (mode is FreehandMode && deskLayout) ZoomGroup(state, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
        if (mode !is FreehandMode) DrawingTimeBar(state, mode, Modifier.fillMaxWidth().height(40.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp).enterFade().background(Am.Panel).pointerInput(Unit) {}) {
            when (mode) {
                is FreehandMode -> FreehandPanel(mode, wide = maxWidth >= 700.dp)
                is PointMode -> PointPanel(state, mode)
                is TrackMode -> TrackPanel(state, mode)
            }
        }
    }
}

@Composable
internal fun DrawingSession(state: EditorState, mode: DrawingMode) {
    LaunchedEffect(mode.layerId) { state.playing = false }
    val gone = state.scene.layer(mode.layerId) == null
    LaunchedEffect(gone) { if (gone) state.drawing = null }
    DisposableEffect(mode) { onDispose { state.endGesture() } }
    LaunchedEffect(mode, state.scene, state.cameraMode) { snapshotFlow { state.timeMs }.collectLatest { mode.seen = state.engine.layerMatrix(mode.layerId, it) } }
}

@Composable
internal fun PointSession(state: EditorState) {
    val mode = state.drawing as? PointMode ?: return
    DrawingSession(state, mode)
    LaunchedEffect(mode, state.selectedId) { if (state.selectedId != mode.layerId) state.leaveDrawing() }
}

@Composable
internal fun PointTopBar(state: EditorState, mode: PointMode, modifier: Modifier) {
    val cell = Modifier.width(40.dp).fillMaxHeight()
    val layer = state.scene.layer(mode.layerId)
    val first = layer?.let { state.scene.firstShownFrame(it.startTime) } ?: 0
    val last = layer?.let { state.scene.lastShownFrame(it.endTime) } ?: 0
    val keys = state.shapeKeyFrames(mode)
    val on = state.frame in keys
    Row(modifier.background(Am.Button), verticalAlignment = Alignment.CenterVertically) {
        BarButton(ChromeIcons.Back, stringResource(R.string.draw_back), cell) { state.leaveDrawing() }
        Text(
            stringResource(if (mode.vector) R.string.add_quick_pen_tool else R.string.draw_title_edit_points),
            Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp, maxLines = 1,
        )
        BarButton(ChromeIcons.SkipBack, stringResource(R.string.draw_previous_shape_keyframe), cell, enabled = keys.any { it < state.frame }) {
            keys.lastOrNull { it < state.frame }?.let { if (first <= last) state.frame = it.coerceIn(first, last) }
        }
        BarButton(ChromeIcons.SkipForward, stringResource(R.string.draw_next_shape_keyframe), cell, enabled = keys.any { it > state.frame }) {
            keys.firstOrNull { it > state.frame }?.let { if (first <= last) state.frame = it.coerceIn(first, last) }
        }
        Box(cell.clickable { state.toggleShapeKey(mode) }, Alignment.Center) {
            Icon(if (on) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (on) R.string.draw_remove_shape_keyframe else R.string.draw_add_shape_keyframe),
                Modifier.size(26.dp), tint = if (keys.isNotEmpty()) Am.Accent else Am.OnDark)
            HoldName(stringResource(if (on) R.string.draw_remove_shape_keyframe else R.string.draw_add_shape_keyframe))
        }
    }
}

@Composable
private fun DrawingBar(state: EditorState, mode: DrawingMode, modifier: Modifier) {
    val cell = Modifier.width(40.dp).fillMaxHeight()
    Row(modifier.background(Bar), verticalAlignment = Alignment.CenterVertically) {
        BarButton(ChromeIcons.Back, stringResource(R.string.draw_back), cell) { state.leaveDrawing() }
        Text(
            stringResource(
                when { mode is TrackMode -> R.string.draw_title_track_motion; mode is PointMode && mode.vector -> R.string.add_quick_pen_tool; mode is PointMode -> R.string.draw_title_edit_points; else -> R.string.add_quick_freehand_brush },
            ),
            Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp, maxLines = 1,
        )
        val busy = (mode as? TrackMode)?.running == true
        BarButton(ChromeIcons.Undo, stringResource(R.string.draw_undo), cell, enabled = state.canUndo && !busy) { state.undo() }
        BarButton(ChromeIcons.Redo, stringResource(R.string.draw_redo), cell, enabled = state.canRedo && !busy) { state.redo() }
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
        if (point != null) BarButton(ChromeIcons.SkipBack, stringResource(R.string.draw_previous_shape_keyframe), cell, enabled = keys.any { it < state.frame }) {
            keys.lastOrNull { it < state.frame }?.let { if (first <= last) state.frame = it.coerceIn(first, last) }
        }
        BarButton(DrawingIcons.Prev, stringResource(R.string.draw_previous_frame), cell, enabled = !locked && state.frame > first) { state.frame = (state.frame - 1).coerceAtLeast(first) }
        Text(TimelineMath.timecode(state.frame, state.scene.fps), Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
        BarButton(DrawingIcons.Next, stringResource(R.string.draw_next_frame), cell, enabled = !locked && state.frame < last) { state.frame = (state.frame + 1).coerceAtMost(last) }
        if (point != null) {
            BarButton(ChromeIcons.SkipForward, stringResource(R.string.draw_next_shape_keyframe), cell, enabled = keys.any { it > state.frame }) {
                keys.firstOrNull { it > state.frame }?.let { if (first <= last) state.frame = it.coerceIn(first, last) }
            }
            val on = state.frame in keys
            Box(cell.clickable { state.toggleShapeKey(point) }, Alignment.Center) {
                Icon(if (on) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (on) R.string.draw_remove_shape_keyframe else R.string.draw_add_shape_keyframe),
                    Modifier.size(26.dp), tint = if (keys.isNotEmpty()) Am.Accent else Am.OnDark)
                HoldName(stringResource(if (on) R.string.draw_remove_shape_keyframe else R.string.draw_add_shape_keyframe))
            }
        }
    }
}

private const val StraightenMs = 550L

private fun softness(hardness: Float, width: Float) = (1f - hardness) * width.coerceAtLeast(0f) / 4f

private class LiveStroke(
    val tool: DrawTool, val color: Int, val width: Float, val hardness: Float, val smoothing: Float,
    val toScreen: app.absentmotion.platform.Matrix, val opacity: Float, val existing: List<Stroke>,
) {
    val raw = ArrayList<StrokePoint>()
    private val smooth = ArrayList<StrokePoint>()
    var points: List<StrokePoint> = raw
        private set
    var straight = false
        private set
    var doomed: Set<Int> = emptySet()
        private set
    var version by mutableIntStateOf(0)

    fun add(x: Float, y: Float, pressure: Float) {
        val last = raw.lastOrNull()
        if (last != null && hypot(last.x - x, last.y - y) < 0.3f) return
        raw += StrokePoint(x, y, pressure)
        refresh()
    }

    fun straighten() { straight = true; refresh() }

    fun shape(finish: Boolean): List<StrokePoint> = when {
        straight && raw.size > 1 -> listOf(raw.first(), raw.last())
        smoothing <= 0f || raw.size < 2 -> raw
        finish -> smooth.caughtUp(raw.last())
        else -> smooth
    }

    private fun refresh() {
        if (smoothing > 0f) smooth.smoothTo(raw, smoothing)
        points = shape(false)
        if (existing.isNotEmpty()) doomed = if (straight) existing.touchedBy(points, width / 2) else doomed + existing.touchedBy(points.takeLast(2), width / 2)
        version++
    }
}

@Composable
private fun FreehandOverlay(state: EditorState, mode: FreehandMode, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val frame = { previewFrame(size.width.toFloat(), size.height.toFloat(), 0f, state.scene.width.toFloat(), state.scene.height.toFloat(), state.canvasZoom, state.canvasPan) }
    val strokes = remember { mutableStateListOf<LiveStroke>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var picking by remember { mutableStateOf<Offset?>(null) }
    val highlight = Am.Accent.copy(alpha = 0.75f).toArgb()
    DisposableEffect(Unit) { onDispose { DrawingKeys.space = false } }
    Canvas(
        modifier.onSizeChanged { size = it }
            .canvasNavigation(state, frame) { fingers -> if (fingers == 2 && state.canUndo) state.undo() else if (fingers == 3 && state.canRedo) state.redo() }
            .then(if (deskLayout) Modifier.pointerHoverIcon(if (DrawingKeys.space) PointerIcon.Hand else PointerIcon.Crosshair) else Modifier)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        cursor = if (event.type == PointerEventType.Exit || change.type != PointerType.Mouse) null else change.position
                    }
                }
            }
            .pointerInput(state, mode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (DrawingKeys.space || down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val keys = currentEvent.keyboardModifiers
                    down.consume()
                    if (deskLayout && keys.isAltPressed) {
                        fun sample(at: Offset) {
                            if (size.width <= 0 || size.height <= 0) return
                            readPreviewPixel(state.previewSurface, at.x / size.width, at.y / size.height) { c -> if (c != null) mode.recolour((c and 0xFFFFFF) or (mode.color and 0xFF000000.toInt())) }
                        }
                        try {
                            picking = down.position
                            sample(down.position)
                            drag(down.id) { picking = it.position; sample(it.position); it.consume() }
                        } finally {
                            picking = null
                        }
                        return@awaitEachGesture
                    }
                    val layer = state.scene.layerAt(mode.layerId, state.timeMs) ?: return@awaitEachGesture
                    val (s, ox, oy) = frame()
                    val toScene = mode.screenMatrix(state.scene, state.timeMs) ?: return@awaitEachGesture
                    val toLayer = app.absentmotion.platform.Matrix().also { toScene.invert(it) }
                    val toScreen = app.absentmotion.platform.Matrix().apply { setTranslate(ox, oy); preScale(s, s); preConcat(toScene) }
                    fun local(p: Offset): FloatArray = floatArrayOf((p.x - ox) / s, (p.y - oy) / s).also { toLayer.mapPoints(it) }
                    val whole = mode.tool == DrawTool.Eraser && mode.wholeStrokes
                    val existing = if (whole) layer.strokes else emptyList()
                    fun begin(tool: DrawTool) = LiveStroke(tool, mode.color, mode.width, mode.settings.hardness, mode.settings.smoothing, toScreen, layer.opacity, existing).also { strokes += it }
                    fun finish(live: LiveStroke, keep: Boolean) {
                        val final = live.shape(true)
                        if (keep && final.isNotEmpty()) {
                            if (whole) {
                                val drop = existing.touchedBy(final, live.width / 2)
                                if (drop.isNotEmpty()) state.edit { sc -> sc.update(mode.layerId) { it.withoutStrokes(drop) } }
                            } else {
                                state.edit { sc -> sc.update(mode.layerId) { it.withStroke(Stroke(final, live.color, live.tool, live.width, live.hardness)) } }
                            }
                        }
                        scope.launch { delay(90); strokes.remove(live) }
                    }
                    val anchor = layer.strokes.lastOrNull()?.points?.lastOrNull()
                    if (keys.isShiftPressed && anchor != null) {
                        val live = begin(if (mode.savedTool == DrawTool.Fill) DrawTool.Pen else mode.savedTool)
                        live.add(anchor.x, anchor.y, 1f)
                        local(down.position).let { live.add(it[0], it[1], 1f) }
                        live.straighten()
                        finish(live, true)
                        return@awaitEachGesture
                    }
                    val live = begin(mode.savedTool)
                    val pressureMode = mode.pressure
                    val dp = 1.dp.toPx()
                    var speed = 1f
                    var lastAt = down.position
                    var lastTime = down.uptimeMillis
                    fun pressure(raw: Float, at: Offset, time: Long): Float {
                        val p = when {
                            pressureMode == Pressure.Speed -> speedPressure(speed, (at - lastAt).getDistance() / dp, time - lastTime).also { speed = it }
                            pressureMode == Pressure.Off && mode.tool == DrawTool.Brush -> 1f
                            else -> raw
                        }
                        lastAt = at
                        lastTime = time
                        return p
                    }
                    var rawPressure = down.pressure.coerceIn(0f, 1f)
                    local(down.position).let { live.add(it[0], it[1], if (pressureMode == Pressure.Speed) 1f else pressure(rawPressure, down.position, down.uptimeMillis)) }
                    val holds = down.type != PointerType.Mouse
                    val still = 6.dp.toPx()
                    val reach = 24.dp.toPx()
                    var stillAt = down.position
                    var stillSince = down.uptimeMillis
                    var now = down.uptimeMillis
                    var travelled = 0f
                    var dropped = false
                    try {
                        while (true) {
                            val waiting = holds && !live.straight && travelled >= reach
                            val event = if (waiting) withTimeoutOrNull((StraightenMs - (now - stillSince)).coerceAtLeast(1L)) { awaitPointerEvent() } else awaitPointerEvent()
                            if (event == null) {
                                live.straighten()
                                if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) { dropped = true; break }
                            if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                            val next = change.pressure.coerceIn(0f, 1f)
                            val history = change.historical
                            history.forEachIndexed { i, h ->
                                local(h.position).let { live.add(it[0], it[1], pressure(rawPressure + (next - rawPressure) * (i + 1) / (history.size + 1), h.position, h.uptimeMillis)) }
                            }
                            local(change.position).let { live.add(it[0], it[1], pressure(next, change.position, change.uptimeMillis)) }
                            rawPressure = next
                            now = change.uptimeMillis
                            travelled = maxOf(travelled, (change.position - down.position).getDistance())
                            if ((change.position - stillAt).getDistance() > still) { stillAt = change.position; stillSince = now }
                            change.consume()
                        }
                    } finally {
                        finish(live, live.raw.isNotEmpty() && !dropped)
                    }
                }
            },
    ) {
        for (stroke in strokes) {
            stroke.version
            for (i in stroke.doomed) {
                val gone = stroke.existing.getOrNull(i) ?: continue
                val (path, filled) = strokeShape(gone.points, gone.tool, gone.width)
                drawLiveStroke(path, filled, highlight, gone.width, 1f, stroke.toScreen, false)
            }
            val (path, filled) = strokeShape(stroke.points, stroke.tool, stroke.width)
            drawLiveStroke(path, filled, stroke.color, stroke.width, stroke.opacity, stroke.toScreen, stroke.tool == DrawTool.Eraser, softness(stroke.hardness, stroke.width))
        }
        picking?.let { drawLoupe(it) }
        val at = cursor
        if (at != null && picking == null && !DrawingKeys.space && mode.tool != DrawTool.Fill) {
            val v = floatArrayOf(1f, 0f, 0f, 1f).also { mode.screenMatrix(state.scene, state.timeMs)?.mapVectors(it) }
            val r = mode.width / 2 * frame().first * sqrt(abs(v[0] * v[3] - v[1] * v[2]))
            drawCircle(Color.Black.copy(alpha = 0.55f), r, at, style = DrawStroke(2.5.dp.toPx()))
            drawCircle(Color.White, r, at, style = DrawStroke(1.dp.toPx()))
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

@Composable
private fun FreehandPanel(mode: FreehandMode, wide: Boolean) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(DrawingIcons.SizeDot, null, Modifier.padding(start = 8.dp).size(22.dp), tint = Am.OnDark)
                val index = BrushWidths.indexOfLast { it <= mode.width }.coerceAtLeast(0)
                ThinSlider(
                    index.toFloat(), { mode.resize(BrushWidths[it.roundToInt().coerceIn(0, BrushWidths.lastIndex)].toFloat()) },
                    Modifier.weight(1f).padding(horizontal = 6.dp), valueRange = 0f..BrushWidths.lastIndex.toFloat(),
                    active = Am.Accent, inactive = Am.FieldHi, thumb = Am.OnDark,
                )
                Box(Modifier.padding(end = 8.dp).size(35.dp, 22.dp).clip(RoundedCornerShape(4.dp)).background(Am.Field), Alignment.Center) {
                    Text("${mode.width.roundToInt()}", color = Am.Accent, fontSize = 12.sp, maxLines = 1)
                }
            }
            val picker = Modifier.padding(start = 8.dp, bottom = 8.dp, end = if (wide) 0.dp else 8.dp)
            when {
                wide -> Row(Modifier.fillMaxWidth().weight(1f)) {
                    DrawColorPicker(mode.color, mode::recolour, picker.weight(1f).fillMaxHeight())
                    Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp).width(1.dp).fillMaxHeight().background(Am.PreviewBg))
                    BrushSettings(mode, Modifier.width(320.dp).fillMaxHeight())
                }
                mode.settingsOpen -> BrushSettings(mode, Modifier.fillMaxWidth().weight(1f))
                else -> DrawColorPicker(mode.color, mode::recolour, picker.fillMaxWidth().weight(1f))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Am.PreviewBg))
        Column(Modifier.width(59.dp).fillMaxHeight()) {
            for ((tool, icon) in listOf(DrawTool.Pen to DrawingIcons.Pen, DrawTool.Brush to DrawingIcons.Brush, DrawTool.Fill to DrawingIcons.Fill, DrawTool.Eraser to DrawingIcons.Eraser)) {
                Box(Modifier.weight(1f).fillMaxWidth().clickable { mode.pick(tool); mode.settingsOpen = false }, Alignment.Center) {
                    Icon(icon, stringResource(tool.labelRes()), Modifier.size(26.dp), tint = app.absentmotion.ui.theme.animatedColor(if (mode.tool == tool) Am.Accent else Am.OnDark))
                    HoldName(if (deskLayout) deskShortcut(tool.labelRes(), tool.key) else stringResource(tool.labelRes()))
                }
            }
            if (!wide) Box(Modifier.weight(1f).fillMaxWidth().clickable { mode.settingsOpen = !mode.settingsOpen }, Alignment.Center) {
                Icon(DrawingIcons.Tune, stringResource(R.string.draw_brush_settings), Modifier.size(24.dp), tint = Am.OnDark)
                HoldName(stringResource(R.string.draw_brush_settings))
            }
        }
    }
}

private val DrawTool.key get() = when (this) { DrawTool.Pen -> "P"; DrawTool.Brush -> "B"; DrawTool.Fill -> "F"; DrawTool.Eraser -> "E" }

private fun Pressure.labelRes() = when (this) {
    Pressure.Off -> R.string.draw_pressure_off
    Pressure.Stylus -> R.string.draw_pressure_stylus
    Pressure.Speed -> R.string.draw_pressure_speed
}

@Composable
private fun BrushSettings(mode: FreehandMode, modifier: Modifier) {
    val settings = mode.settings
    Column(modifier.padding(horizontal = 8.dp).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (mode.tool == DrawTool.Eraser) {
            Choices(stringResource(R.string.draw_erase), listOf(stringResource(R.string.draw_erase_pixels) to false, stringResource(R.string.draw_erase_strokes) to true), mode.wholeStrokes, true) { mode.wholeStrokes = it }
        } else {
            PercentRow(stringResource(R.string.draw_opacity), app.absentmotion.platform.Color.alpha(mode.color) / 255f) { mode.opacity(it) }
        }
        PercentRow(stringResource(R.string.draw_hardness), settings.hardness) { settings.hardness = it }
        PercentRow(stringResource(R.string.draw_smoothing), settings.smoothing) { settings.smoothing = it }
        if (mode.tool == DrawTool.Pen || mode.tool == DrawTool.Brush) {
            Choices(stringResource(R.string.draw_pressure), Pressure.entries.map { stringResource(it.labelRes()) to it }, mode.pressure, true) { mode.pressure = it }
        }
    }
}

@Composable
private fun PercentRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp).padding(end = 4.dp), color = Am.Caption, maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(8.sp, 12.sp, 0.5.sp))
        ThinSlider(value, { onChange((it * 100).roundToInt() / 100f) }, Modifier.weight(1f).padding(horizontal = 6.dp), active = Am.Accent, inactive = Am.FieldHi, thumb = Am.OnDark)
        Box(Modifier.size(40.dp, 22.dp).clip(RoundedCornerShape(4.dp)).background(Am.Field), Alignment.Center) {
            Text("${(value * 100).roundToInt()}%", color = Am.Accent, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ZoomGroup(state: EditorState, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Am.Panel.copy(alpha = 0.92f)), verticalAlignment = Alignment.CenterVertically) {
        DeskIcon(ChromeIcons.Minus, deskShortcut(R.string.a11y_zoom_out, DeskAlt + "-"), size = 32.dp) { state.zoomStep(false) }
        Hint(deskShortcut(R.string.desk_fit, DeskAlt + "0")) {
            Box(Modifier.height(32.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable { state.resetCanvasView() }.padding(horizontal = 6.dp), Alignment.Center) {
                Text("${(state.canvasZoom * 100).toInt()}%", color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
            }
        }
        DeskIcon(ChromeIcons.Plus, deskShortcut(R.string.a11y_zoom_in, DeskAlt + "="), size = 32.dp) { state.zoomStep(true) }
    }
}

@Composable
private fun DrawColorPicker(argb: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    val start = remember { FloatArray(3).also { app.absentmotion.platform.Color.colorToHSV(argb, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var bright by remember { mutableFloatStateOf(start[2]) }
    var opacity by remember { mutableFloatStateOf(app.absentmotion.platform.Color.alpha(argb) / 255f) }
    LaunchedEffect(argb) {
        opacity = app.absentmotion.platform.Color.alpha(argb) / 255f
        if (app.absentmotion.platform.Color.HSVToColor(floatArrayOf(hue, sat, bright)) != (argb or 0xFF000000.toInt())) {
            app.absentmotion.platform.Color.colorToHSV(argb, start)
            hue = start[0]; sat = start[1]; bright = start[2]
        }
    }
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
