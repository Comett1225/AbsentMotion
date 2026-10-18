package app.absentmotion.ui.editor

import app.absentmotion.model.Scene
import androidx.compose.ui.unit.DpSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.absentmotion.platform.SystemClock
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.toggleBookmark
import app.absentmotion.editor.toggleHidden
import app.absentmotion.editor.deselectLayer
import app.absentmotion.editor.clearSelection
import app.absentmotion.model.LabelColour
import app.absentmotion.model.Layer
import app.absentmotion.model.trimmedTo
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val RulerHeight = 42.dp
private val RowHeight = 28.dp

private val PanelTop = 80.dp

private val PlayheadWidth = 1.5.dp
private val TickWidth = 1.dp
private val TickShort = 5.dp
private val TickHalfSecond = 12.dp
private val TickSecond = 18.dp
private const val MIN_TICK_GAP_DP = 3f

private val PillWidth = 70.dp
private val PillTop = 17.8.dp
private val PillHeight = 16.7.dp
private val PillNotch = 5.dp

private val ReadoutHalfWidth = PillWidth / 2 + 12.dp
private val ReadoutTop = PillTop + PillHeight / 2 - 24.dp

private val BookmarkColor get() = Am.Bookmark
private const val BOOKMARK_ALPHA = 0.58f
private val BookmarkLineWidth = 0.7.dp
private val BookmarkFlagTop = 2.1.dp
private val BookmarkFlagBottom = 5.dp
private val BookmarkFlagSide = 2.1.dp
private val BookmarkPeakHalf = 2.5.dp
private val PillOutline = 1.1.dp

private val HeaderWidth = 62.dp
private val HeaderInset = 1.dp
private val TagStripWidth = 2.1.dp
private val EyeZone = 36.dp
private val EyeCentre = 17.dp
private val ThumbLeft = 36.dp
private val ThumbSize = 16.dp
private const val HIDDEN_ALPHA = 0.55f

private val BarInset = 2.5.dp
private val BarRadius = 2.dp
private val BarShadowHeight = 2.dp
private val LabelInset = 14.dp
private const val MAX_LABEL_PX = 16_000

private val GripZone = 30.dp
private val GripGap = 1.8.dp

private val HandleInset = 9.dp
private val HandleZone = 22.dp

private const val DEFAULT_ZOOM = 100f
private const val MIN_ZOOM = 10f
private const val MAX_ZOOM = 1000f

private val TickColor get() = Am.Line
private val RetimeMarkColor get() = Am.Gold.copy(alpha = 0.67f)
private val PillColor get() = Am.Button
private val HeaderFill get() = Am.Button
private val HeaderEdge get() = Am.Line
private val BarStripe get() = Am.LayerBarStripe
private val TextBar get() = Am.TextBar
private val TextStripe get() = Am.TextBarStripe
private val BarShadow get() = Am.PreviewBg
private val GripColor get() = Am.Inactive
private val HandleColor get() = Am.Inactive

private val TimecodeStyle get() = TextStyle(color = Am.OnDark, fontSize = 14.sp)
private val LabelStyle get() = TextStyle(color = Am.OnBar, fontSize = 11.sp)

@Composable
fun Timeline(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val wide = LocalWide.current
    val view = remember(state, density, wide) { TimelineView(state, density, scope, wide) }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val eye = rememberVectorPainter(TimelineIcons.Eye)
    val eyeOff = rememberVectorPainter(TimelineIcons.EyeOff)
    val outline = remember { Path() }
    val mediaPictures = buildMap {
        for (row in view.visibleRows()) {
            val layer = view.layerAt(row) ?: continue
            val time = view.timeScale()
            val info = layer.mediaFill?.let { state.scene.media(it.uri) }
            val turned = info?.orientation == 90 || info?.orientation == 270
            val width = (if (turned) info.height else info?.width)?.coerceAtLeast(1) ?: 1
            val height = (if (turned) info.width else info?.height)?.coerceAtLeast(1) ?: 1
            val tileMs = 23f * width / height * 1000 / view.dpPerSecond
            key(layer.id) { mediaStripPicture(layer, state.scene, state.engine, time.time(0f), time.time(view.size.width.toFloat()), tileMs)?.let { put(layer.id, it) } }
        }
    }

    LoadWaveforms(state.scene)
    LaunchedEffect(view, state.selectedId) { view.revealSelected() }
    val appear = remember(state.selectedId) { Animatable(if (state.selectedId == null) 1f else 0f) }
    LaunchedEffect(appear) { appear.animateTo(1f, Motion.enter()) }
    LaunchedEffect(view, state.playing) { if (state.playing) view.followPlayback() }

    Box(
        modifier
            .then(app.absentmotion.platform.wheel { delta, at, zoom ->
                if (zoom) return@wheel view.zoomBy(1.1f.pow(-delta.y))
                view.stopMotion(); state.playing = false
                val horizontal = kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y) || at.y < view.rulerH
                view.dragBy(if (horizontal) Drag.Scrub else Drag.Scroll, if (horizontal) Offset(-(if (delta.x != 0f) delta.x else delta.y) * 18f, 0f) else Offset(0f, -delta.y * 18f), at)
            })
            .onSizeChanged { view.size = it }
            .pointerInput(view) { timelineGestures(view) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
            .drawBehind { drawTimeline(view, measurer, eye, eyeOff, outline, mediaPictures, appear.value) },
    ) {
        HeaderPictures(view)
    }
}

@Composable
private fun HeaderPictures(view: TimelineView) {
    val rows by remember(view) { derivedStateOf { view.visibleRows() } }
    Box(Modifier.fillMaxSize().padding(top = RulerHeight).clipToBounds()) {
        for (row in rows) {
            val layer = view.layerAt(row)
            if (layer != null) {
                key(layer.id) {
                    LayerThumbnail(
                        layer, view.state.engine,
                        Modifier
                            .offset {
                                val top = view.rowTop(row) - view.rulerH + (RowHeight - ThumbSize).toPx() / 2
                                IntOffset(ThumbLeft.roundToPx(), top.roundToInt())
                            }
                            .size(ThumbSize)
                            .alpha(if (layer.hidden) HIDDEN_ALPHA else 1f),
                    )
                }
            }
        }
    }
}

internal object TimelineMath {
    fun timeToX(timeMs: Float, nowMs: Float, originX: Float, pxPerMs: Float): Float = originX + (timeMs - nowMs) * pxPerMs

    fun xToTime(x: Float, nowMs: Float, originX: Float, pxPerMs: Float): Float = nowMs + (x - originX) / pxPerMs

    fun frameTimeMs(frame: Int, fps: Int): Float = frame * 1000f / fps

    fun nearestFrame(timeMs: Float, fps: Int): Int = (timeMs * fps / 1000f).roundToInt()

    fun timecode(frame: Int, fps: Int): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${two(frame / fps / 60)}:${two(frame / fps % 60)}:${two(frame % fps)}"
    }

    fun tickStep(fps: Int, dpPerSecond: Float): Int =
        (1..fps).firstOrNull { fps % it == 0 && it * dpPerSecond / fps >= MIN_TICK_GAP_DP } ?: fps
}

private class TimeScale(val nowMs: Float, val originX: Float, val pxPerMs: Float) {
    fun x(timeMs: Float) = TimelineMath.timeToX(timeMs, nowMs, originX, pxPerMs)
    fun time(x: Float) = TimelineMath.xToTime(x, nowMs, originX, pxPerMs)
}

private sealed interface Hit {
    data object Ruler : Hit
    data object Empty : Hit

    data object Readout : Hit
    data class Eye(val id: Long) : Hit

    data class Bar(val id: Long) : Hit
    data class Header(val id: Long) : Hit
    data class Grip(val id: Long, val start: Boolean) : Hit
    data class Handle(val id: Long) : Hit
}

private enum class Drag(val edit: Boolean = false) { Scrub, Scroll, Pinch, Spent, TrimStart(true), TrimEnd(true), Reorder(true) }

private class TimelineView(val state: EditorState, density: Density, private val scope: CoroutineScope, private val wide: Boolean) {
    private val scale = density.density
    private val Dp.px get() = value * scale
    private val decay = splineBasedDecay<Float>(density)

    val rulerH = RulerHeight.px
    val rowH = RowHeight.px

    var size by mutableStateOf(IntSize.Zero)

    var dpPerSecond by mutableFloatStateOf(DEFAULT_ZOOM)
        private set
    private var scrollPx by mutableFloatStateOf(0f)

    private var scrubMs by mutableStateOf<Float?>(null)
    private var scrubFrame = 0
    private var motion: Job? = null

    private var dragId = 0L
    private var edgeMs = 0f

    private val layersState = derivedStateOf(referentialEqualityPolicy()) { state.scene.layers }

    val layers: List<Layer> get() = layersState.value
    val fps: Int get() = state.scene.fps.coerceAtLeast(1)

    private val nowMs: Float
        get() = scrubMs?.takeIf { state.frame == scrubFrame } ?: TimelineMath.frameTimeMs(state.frame, fps)

    fun timeScale() = TimeScale(nowMs, (size.width - PlayheadWidth.px) / 2f, dpPerSecond * scale / 1000f)

    private val covered: Boolean get() = !wide && state.selectedId != null

    private val roomBottom: Float get() = if (covered) PanelTop.px else size.height.toFloat()

    val scroll: Float
        get() = scrollPx.coerceIn(0f, max(0f, layers.size * rowH - (roomBottom - rulerH)))

    fun layerAt(row: Int): Layer? = layers.getOrNull(layers.lastIndex - row)

    fun rowTop(row: Int): Float = rulerH + row * rowH - scroll

    private fun rowAt(y: Float): Int = floor((y - rulerH + scroll) / rowH).toInt()

    fun visibleRows(): IntRange = max(0, rowAt(rulerH))..min(layers.lastIndex, rowAt(size.height.toFloat()))

    fun revealSelected() {
        val index = layers.indexOfFirst { it.id == state.selectedId }
        if (index < 0) return
        val top = (layers.lastIndex - index) * rowH
        scrollPx = scroll.coerceIn(min(top + rowH - (roomBottom - rulerH), top), top)
    }

    fun isCovered(at: Offset): Boolean = (covered && at.y >= PanelTop.px) || (MultiSelect.active && at.y >= size.height - 48.dp.px)

    fun hitAt(at: Offset): Hit {
        if (at.y < rulerH) {
            val centre = timeScale().originX + PlayheadWidth.px / 2
            return if (at.y >= ReadoutTop.px && abs(at.x - centre) <= ReadoutHalfWidth.px) Hit.Readout else Hit.Ruler
        }
        val layer = layerAt(rowAt(at.y)) ?: return Hit.Empty
        val time = timeScale()
        val left = time.x(layer.startTime.toFloat())
        val right = time.x(layer.endTime + 1f)
        val selected = layer.id == state.selectedId
        return when {
            at.x < EyeZone.px -> Hit.Eye(layer.id)
            at.x < HeaderWidth.px -> Hit.Header(layer.id)
            selected && at.x in left - GripZone.px..left -> Hit.Grip(layer.id, start = true)
            selected && at.x in right..right + GripZone.px -> Hit.Grip(layer.id, start = false)
            !selected && at.x >= size.width - HandleZone.px -> Hit.Handle(layer.id)
            at.x in left..right -> Hit.Bar(layer.id)
            else -> Hit.Empty
        }
    }

    fun tap(hit: Hit) {
        when (hit) {
            is Hit.Eye -> if (MultiSelect.active) MultiSelect.toggle(hit.id) else state.toggleHidden(hit.id)
            is Hit.Header -> select(hit.id)
            is Hit.Bar -> select(hit.id)
            Hit.Readout -> state.toggleBookmark()
            Hit.Empty, Hit.Ruler -> state.clearSelection()
            is Hit.Grip, is Hit.Handle -> Unit
        }
    }

    private fun select(id: Long) {
        if (MultiSelect.active) MultiSelect.toggle(id) else {
            state.selectedId = id
            state.panel = EditorPanel.Tools
        }
    }

    fun longPress(hit: Hit) {
        val id = when (hit) { is Hit.Eye -> hit.id; is Hit.Header -> hit.id; else -> return }
        state.deselectLayer()
        if (MultiSelect.active) MultiSelect.toggle(id) else {
            MultiSelect.ids = setOf(id)
            MultiSelect.active = true
        }
    }

    fun beginDrag(hit: Hit, moved: Offset): Drag = when {
        hit is Hit.Grip -> {
            val layer = layers.firstOrNull { it.id == hit.id }
            dragId = hit.id
            edgeMs = (if (hit.start) layer?.startTime else layer?.endTime?.plus(1))?.toFloat() ?: 0f
            if (hit.start) Drag.TrimStart else Drag.TrimEnd
        }
        hit is Hit.Handle -> {
            dragId = hit.id
            Drag.Reorder
        }
        abs(moved.x) > abs(moved.y) -> {
            state.playing = false
            Drag.Scrub
        }
        else -> Drag.Scroll
    }

    fun dragBy(drag: Drag, delta: Offset, at: Offset) {
        when (drag) {
            Drag.Scrub -> scrubBy(delta.x)
            Drag.Scroll -> scrollBy(delta.y)
            Drag.TrimStart, Drag.TrimEnd -> trimBy(delta.x, start = drag == Drag.TrimStart)
            Drag.Reorder -> reorderTo(at.y)
            Drag.Pinch, Drag.Spent -> Unit
        }
    }

    fun endDrag(drag: Drag?, velocity: Velocity) {
        if (drag?.edit == true) return state.endGesture()
        motion = scope.launch {
            if (drag == Drag.Scroll) launch { fling(velocity.y, ::scrollBy) }
            if (drag == Drag.Scrub) fling(velocity.x) { state.frame == scrubFrame && scrubBy(it) }
            settle()
        }
    }

    fun stopMotion() {
        motion?.cancel()
    }

    fun followPlayback() {
        stopMotion()
        scrubMs = null
    }

    fun zoomBy(factor: Float) {
        dpPerSecond = (dpPerSecond * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun scrubBy(dx: Float): Boolean {
        val time = timeScale()
        val wanted = time.nowMs - dx / time.pxPerMs
        val to = wanted.coerceIn(0f, TimelineMath.frameTimeMs(state.scene.lastFrame, fps))
        scrubFrame = TimelineMath.nearestFrame(to, fps)
        scrubMs = to
        state.frame = scrubFrame
        return to == wanted
    }

    private fun scrollBy(dy: Float): Boolean {
        val wanted = scroll - dy
        scrollPx = wanted
        return scroll == wanted
    }

    private suspend fun settle() {
        val from = scrubMs ?: return
        if (state.frame == scrubFrame) {
            animate(from, TimelineMath.frameTimeMs(scrubFrame, fps), animationSpec = tween(120)) { value, _ -> scrubMs = value }
        }
        scrubMs = null
    }

    private suspend fun fling(velocity: Float, move: (Float) -> Boolean) {
        var last = 0f
        AnimationState(0f, velocity).animateDecay(decay) {
            if (!move(value - last)) cancelAnimation()
            last = value
        }
    }

    private fun trimBy(dx: Float, start: Boolean) {
        edgeMs += dx / timeScale().pxPerMs
        val scene = state.scene
        val layer = layers.firstOrNull { it.id == dragId } ?: return
        val frame = TimelineMath.nearestFrame(edgeMs, fps)
        val id = dragId
        val mediaMs = (layer.mediaFill?.uri ?: layer.node["src"])?.let { scene.media(it)?.duration }?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: Int.MAX_VALUE
        if (start) {
            val time = scene.frameStart(frame.coerceIn(0, scene.frameAt(layer.endTime)))
            if (time != layer.startTime) state.edit("trim-start") { it.update(id) { l -> l.trimmedTo(time, l.endTime, mediaMs) } }
        } else {
            val time = scene.frameEnd((frame - 1).coerceAtLeast(scene.frameAt(layer.startTime)))
            if (time != layer.endTime) state.edit("trim-end") { it.update(id) { l -> l.trimmedTo(l.startTime, time, mediaMs) } }
        }
    }

    private fun reorderTo(y: Float) {
        val layers = layers
        if (layers.isEmpty()) return
        val index = layers.lastIndex - rowAt(y).coerceIn(0, layers.lastIndex)
        val id = dragId
        if (layers[index].id != id) state.edit("reorder") { it.move(id, index) }
    }
}

private val KeyFaint = DpSize(6.4.dp, 9.6.dp)
private val KeyOther = DpSize(9.25.dp, 14.6.dp)
private val KeyHere = DpSize(12.1.dp, 18.5.dp)

private suspend fun PointerInputScope.timelineGestures(view: TimelineView, onLongPress: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown()
    view.stopMotion()
    if (view.isCovered(down.position)) return@awaitEachGesture
    val hit = view.hitAt(down.position)
    val tracker = VelocityTracker().apply { addPointerInputChange(down) }
    var drag: Drag? = null
    var lifted = false
    var held = false
    val holdable = hit is Hit.Eye || hit is Hit.Header
    val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
    try {
        while (true) {
            val event = if (holdable && drag == null && !held) {
                withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
                    ?: run {
                        view.longPress(hit)
                        onLongPress()
                        held = true
                        continue
                    }
            } else awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            val first = event.changes.firstOrNull { it.id == down.id }
            if (first != null) tracker.addPointerInputChange(first)
            when {
                fingers == 0 -> break
                fingers > 1 && drag?.edit != true -> {
                    drag = Drag.Pinch
                    view.zoomBy(event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
                drag == Drag.Pinch || drag == Drag.Spent -> drag = Drag.Spent
                first == null || !first.pressed -> break
                drag == null -> {
                    val moved = first.position - down.position
                    if (moved.getDistance() > viewConfiguration.touchSlop) drag = view.beginDrag(hit, moved)
                }
                else -> {
                    view.dragBy(drag, first.position - first.previousPosition, first.position)
                    first.consume()
                }
            }
        }
        lifted = true
    } finally {
        if (lifted && drag == null && !held) view.tap(hit)
        view.endDrag(drag, if (lifted) tracker.calculateVelocity() else Velocity.Zero)
    }
}

private fun DrawScope.drawTimeline(view: TimelineView, measurer: TextMeasurer, eye: Painter, eyeOff: Painter, outline: Path, pictures: Map<Long, StripPicture>, appear: Float = 1f) {
    val state = view.state
    val fps = view.fps
    val time = view.timeScale()
    val step = TimelineMath.tickStep(fps, view.dpPerSecond)
    if (view.layers.isNotEmpty()) drawRuler(time, fps, state.scene.lastFrame, step)
    if (view.layers.isNotEmpty() && state.scene.isElement) drawRetimeMarks(state.scene, time)
    val stripe = TimelineMath.frameTimeMs(2 * step, fps) * time.pxPerMs
    clipRect(top = view.rulerH) {
        for (row in view.visibleRows()) {
            val layer = view.layerAt(row) ?: continue
            drawRow(layer, layer.id == state.selectedId, view.rowTop(row), time, stripe, measurer, if (layer.hidden) eyeOff else eye, outline, state.scene, state.frame, pictures[layer.id], appear)
        }
    }
    val marks = state.scene.bookmarks
    val onBookmark = marks.any { state.scene.frameAt(it) == state.frame }
    for (ms in marks) drawBookmark(time.x(ms.toFloat()), outline)
    drawPlayhead(time.originX, TimelineMath.timecode(state.frame, fps), measurer, outline, onBookmark)
}

private fun DrawScope.drawBookmark(x: Float, outline: Path) {
    if (x < -10.dp.toPx() || x > size.width + 10.dp.toPx()) return
    val colour = BookmarkColor.copy(alpha = BOOKMARK_ALPHA)
    val half = BookmarkLineWidth.toPx() / 2
    drawRect(colour, Offset(x - half, 0f), Size(2 * half, RulerHeight.toPx()))
    outline.rewind()
    outline.moveTo(x + half, BookmarkFlagTop.toPx())
    outline.lineTo(x + half, BookmarkFlagBottom.toPx())
    outline.lineTo(x + half - BookmarkFlagSide.toPx(), BookmarkFlagBottom.toPx())
    outline.close()
    drawPath(outline, colour)
}

private fun DrawScope.drawRuler(time: TimeScale, fps: Int, lastFrame: Int, tickFrames: Int) {
    val width = TickWidth.toPx()
    val firstSecond = floor(time.time(0f) / 1000f).toInt().coerceAtLeast(0)
    val lastSecond = min(floor(time.time(size.width) / 1000f).toInt(), lastFrame / fps)
    for (second in firstSecond..lastSecond) {
        for (i in 0 until fps step tickFrames) {
            val frame = second * fps + i
            if (frame > lastFrame) return
            val height = when {
                i == 0 -> TickSecond
                i * 2 == fps -> TickHalfSecond
                else -> TickShort
            }
            drawRect(TickColor, Offset(time.x(TimelineMath.frameTimeMs(frame, fps)), 0f), Size(width, height.toPx()))
        }
    }
}

private fun DrawScope.drawRetimeMarks(scene: Scene, time: TimeScale) {
    val width = TickWidth.toPx()
    val flag = TickShort.toPx()
    fun mark(ms: Int, direction: Float) {
        val x = time.x(scene.frameTime(scene.frameAt(ms)).toFloat())
        drawLine(RetimeMarkColor, Offset(x, 0f), Offset(x, flag * 2), width)
        drawLine(RetimeMarkColor, Offset(x, 0f), Offset(x + direction * flag / 2, 0f), width)
    }
    if (scene.retimeIn > 0) mark(scene.retimeIn, 1f)
    if (scene.retimeOut > 0) mark(scene.retimeOut, -1f)
}

private fun DrawScope.drawRow(
    layer: Layer, selected: Boolean, top: Float, time: TimeScale, stripe: Float,
    measurer: TextMeasurer, eye: Painter, outline: Path, scene: Scene, frame: Int, picture: StripPicture?, appear: Float = 1f,
) {
    val barTop = top + BarInset.toPx()
    val barBottom = top + (RowHeight - BarInset).toPx()
    val centre = top + RowHeight.toPx() / 2
    val left = time.x(layer.startTime.toFloat())
    val right = time.x(layer.endTime + 1f)

    if (layer.kind == "audio") drawAudioBar(outline, layer, left, right, barTop, barBottom, time.pxPerMs)
    else drawBar(outline, left, right, barTop, barBottom, stripe, picture, layer.startTime, time.pxPerMs, layer.kind == "text")
    layer.labelColour?.let { drawTagCorner(outline, left, barTop, (barBottom - barTop) / 2, Color(it.start)) }
    drawLabel(layer.label, measurer, left, right, barTop, barBottom, if (layer.kind == "audio") LabelStyle.copy(color = Am.OnAudioBar) else LabelStyle)
    drawKeyframes(layer, scene, selected, frame, time, left, right, centre, outline, appear)
    if (selected) {
        drawGrip(outline, left, barTop, barBottom, start = true, appear)
        drawGrip(outline, right, barTop, barBottom, start = false, appear)
    } else {
        drawGrabLines(Offset(size.width - HandleInset.toPx(), centre))
    }
    drawHeader(top, eye, layer.labelColour, layer.id in MultiSelect.ids)
}

private fun DrawScope.drawBar(outline: Path, left: Float, right: Float, top: Float, bottom: Float, stripe: Float, picture: StripPicture?, startTime: Int, pxPerMs: Float, text: Boolean = false) {
    val radius = BarRadius.toPx()
    val from = max(left, -2 * radius)
    val to = min(right, size.width + 2 * radius)
    if (from >= to) return
    drawRect(BarShadow, Offset(from, bottom - radius), Size(to - from, radius + BarShadowHeight.toPx()))
    outline.rewind()
    outline.addRoundRect(RoundRect(from, top, to, bottom, CornerRadius(radius)))
    clipPath(outline) {
        drawRect(if (text) TextBar else Am.LayerBar, Offset(from, top), Size(to - from, bottom - top))
        if (picture != null) {
            val tileHeight = (bottom - top).roundToInt().coerceAtLeast(1)
            val tileWidth = (tileHeight.toFloat() * picture.size.width / picture.size.height).roundToInt().coerceAtLeast(1)
            var x = left + tileWidth * floor((from - left) / tileWidth)
            while (x < to) {
                val projectTime = startTime + (x - left) / pxPerMs
                val frame = picture.frames.lastOrNull { it.first <= projectTime + 1 }?.second
                val bitmap = frame ?: picture.bitmap
                drawImage(bitmap, srcOffset = if (frame == null) picture.offset else IntOffset.Zero,
                    srcSize = if (frame == null) picture.size else IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(x.roundToInt(), top.roundToInt()), dstSize = IntSize(tileWidth, tileHeight))
                x += tileWidth
            }
        } else {
            var x = left + stripe * (2 * floor((from - left) / (2 * stripe)) + 1)
            while (x < to) {
                drawRect(if (text) TextStripe else BarStripe, Offset(x, top), Size(stripe, bottom - top))
                x += 2 * stripe
            }
        }
    }
}

private fun DrawScope.drawKeyframes(layer: Layer, scene: Scene, selected: Boolean, frame: Int, time: TimeScale, left: Float, right: Float, centre: Float, outline: Path, appear: Float = 1f) {
    val times = layer.keyframeTimes()
    if (times.isEmpty()) return
    clipRect(left = max(left, 0f), right = min(right, size.width)) {
        for (keyFrame in times.map { scene.frameAt(it) }.distinct()) {
            val x = time.x(scene.frameStart(keyFrame).toFloat())
            if (x < -KeyHere.width.toPx() || x > size.width + KeyHere.width.toPx()) continue
            val here = selected && keyFrame == frame
            val grow = if (selected) 0.7f + 0.3f * appear else 1f
            val half = (if (here) KeyHere else if (selected) KeyOther else KeyFaint).let { Offset(it.width.toPx() / 2 * grow, it.height.toPx() / 2 * grow) }
            outline.rewind()
            outline.moveTo(x, centre - half.y)
            outline.lineTo(x + half.x, centre)
            outline.lineTo(x, centre + half.y)
            outline.lineTo(x - half.x, centre)
            outline.close()
            if (!selected) {
                drawPath(outline, Color.White.copy(alpha = 0.7f))
                continue
            }
            if (here) drawPath(outline, Am.Accent, style = Stroke(3.55.dp.toPx()))
            drawPath(outline, Color.Black, style = Stroke(1.4.dp.toPx()))
            drawPath(outline, Color.White)
        }
    }
}

private fun DrawScope.drawTagCorner(outline: Path, left: Float, top: Float, side: Float, colour: Color) {
    outline.rewind()
    outline.moveTo(left, top + side)
    outline.lineTo(left, top)
    outline.lineTo(left + side, top)
    outline.close()
    drawPath(outline, colour)
}

private fun DrawScope.drawLabel(label: String, measurer: TextMeasurer, left: Float, right: Float, top: Float, bottom: Float, style: TextStyle = LabelStyle) {
    val inset = LabelInset.toPx()
    val room = min((right - left - 2 * inset).toInt(), MAX_LABEL_PX)
    if (room < 1 || left + inset > size.width || left + inset + room < 0) return
    val text = measurer.measure(label, style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = room))
    drawText(text, topLeft = Offset(left + inset, (top + bottom - text.size.height) / 2))
}

private fun DrawScope.drawGrip(outline: Path, edge: Float, top: Float, bottom: Float, start: Boolean, appear: Float = 1f) {
    val width = (GripZone - GripGap * 2).toPx()
    val left = if (start) edge - GripGap.toPx() - width else edge + GripGap.toPx()
    if (left > size.width || left + width < 0) return
    val far = CornerRadius((bottom - top) / 2)
    val near = CornerRadius(1.dp.toPx())
    val rect = Rect(left, top, left + width, bottom)
    outline.rewind()
    outline.addRoundRect(
        if (start) RoundRect(rect, topLeft = far, bottomLeft = far, topRight = near, bottomRight = near)
        else RoundRect(rect, topLeft = near, bottomLeft = near, topRight = far, bottomRight = far),
    )
    drawPath(outline, GripColor, alpha = appear)

    val centre = rect.center
    val reach = 1.95.dp.toPx() * if (start) 1 else -1
    val rise = 4.1.dp.toPx()
    val tip = Offset(centre.x - reach, centre.y)
    for (side in intArrayOf(-1, 1)) {
        drawLine(Am.TimelineBg, Offset(centre.x + reach, centre.y + side * rise), tip, 1.5.dp.toPx(), StrokeCap.Round, alpha = appear)
    }
}

private fun DrawScope.drawGrabLines(centre: Offset) {
    val half = 4.dp.toPx()
    for (line in -1..1) {
        val y = centre.y + line * 2.85.dp.toPx()
        drawLine(HandleColor, Offset(centre.x - half, y), Offset(centre.x + half, y), 0.7.dp.toPx())
    }
}

private fun DrawScope.drawHeader(top: Float, eye: Painter, tag: LabelColour?, checked: Boolean) {
    val height = (RowHeight - HeaderInset * 2).toPx()
    val topLeft = Offset(-height, top + HeaderInset.toPx())
    val pill = Size(HeaderWidth.toPx() + height, height)
    val radius = CornerRadius(height / 2)
    drawRoundRect(if (checked) Am.Accent else HeaderFill, topLeft, pill, radius)
    drawRoundRect(HeaderEdge, topLeft, pill, radius, style = Stroke(0.5.dp.toPx()))
    if (tag != null) drawRect(Color(tag.start), Offset(0f, topLeft.y + 0.7.dp.toPx()), Size(TagStripWidth.toPx(), height - 1.8.dp.toPx()))
    if (MultiSelect.active) {
        if (checked) {
            val centre = Offset(EyeCentre.toPx(), top + RowHeight.toPx() / 2)
            drawCircle(HeaderFill, 7.5.dp.toPx(), centre)
            val path = Path().apply {
                moveTo(centre.x - 3.5.dp.toPx(), centre.y)
                lineTo(centre.x - 1.dp.toPx(), centre.y + 2.5.dp.toPx())
                lineTo(centre.x + 4.dp.toPx(), centre.y - 3.dp.toPx())
            }
            drawPath(path, Am.Accent, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
        }
        return
    }
    val icon = eye.intrinsicSize
    translate(EyeCentre.toPx() - icon.width / 2, top + (RowHeight.toPx() - icon.height) / 2) {
        with(eye) { draw(icon) }
    }
}

private fun DrawScope.drawPlayhead(originX: Float, timecode: String, measurer: TextMeasurer, outline: Path, onBookmark: Boolean) {
    val width = PlayheadWidth.toPx()
    drawRect(if (onBookmark) BookmarkColor else Am.OnDark, Offset(originX, 0f), Size(width, size.height))
    if (onBookmark) {
        val centre = originX + width / 2
        outline.rewind()
        outline.moveTo(centre, 0f)
        outline.lineTo(centre + BookmarkPeakHalf.toPx(), BookmarkFlagBottom.toPx())
        outline.lineTo(centre - BookmarkPeakHalf.toPx(), BookmarkFlagBottom.toPx())
        outline.close()
        drawPath(outline, BookmarkColor)
    }

    val left = originX + (width - PillWidth.toPx()) / 2
    val right = left + PillWidth.toPx()
    val top = PillTop.toPx()
    val bottom = top + PillHeight.toPx()
    val notch = PillNotch.toPx()
    val round = 1.dp.toPx()
    outline.rewind()
    outline.moveTo(left + notch, top)
    outline.lineTo(right - round, top)
    outline.quadraticTo(right, top, right, top + round)
    outline.lineTo(right, bottom - round)
    outline.quadraticTo(right, bottom, right - round, bottom)
    outline.lineTo(left + round, bottom)
    outline.quadraticTo(left, bottom, left, bottom - round)
    outline.lineTo(left, top + notch)
    outline.close()
    drawPath(outline, PillColor)
    if (onBookmark) clipPath(outline) { drawPath(outline, BookmarkColor, style = Stroke(2 * PillOutline.toPx())) }

    val text = measurer.measure(timecode, TimecodeStyle)
    drawText(text, topLeft = Offset((left + right - text.size.width) / 2, (top + bottom - text.size.height) / 2))
}
