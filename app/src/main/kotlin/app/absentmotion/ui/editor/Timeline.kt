package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialGuard
import app.absentmotion.model.Scene
import app.absentmotion.model.shownName
import app.absentmotion.model.defaultCameraId
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.absentmotion.R
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
import androidx.compose.ui.zIndex
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.toggleBookmark
import app.absentmotion.editor.toggleHidden
import app.absentmotion.editor.deselectLayer
import app.absentmotion.editor.clearSelection
import app.absentmotion.media.VideoProxies
import app.absentmotion.model.LabelColour
import app.absentmotion.model.Layer
import app.absentmotion.ai.trimmedClip
import app.absentmotion.ai.withClip
import app.absentmotion.model.KeyScope
import app.absentmotion.model.withKeyframesMoved
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.tutorial.TourFacts
import app.absentmotion.ui.tutorial.kindOf
import app.absentmotion.ui.tutorial.tutorialRegion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private val RulerHeight = 42.dp
private val RowHeight = 28.dp


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

internal val HeaderWidth = 62.dp
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

private val ProxyLineHeight = 2.dp
private val ProxyLineInset = 1.dp

private val GripZone = 30.dp
private val GripGap = 1.8.dp

private val HandleInset = 9.dp
private val HandleZone = 22.dp

private val MultiBarHeight = 48.dp

private val SnapZone = 8.dp
private val SnapLine = 1.dp

private const val LIFT_SCALE = 1.02f
private const val LIFT_DIM = 0.3f
private const val LIFT_SHADOW_ALPHA = 0.07f
private val LiftShadowStep = 1.5.dp
private const val LIFT_MS = 100
private const val SLIDE_MS = 120
private const val DROP_MS = 150
private val PushZone = 56.dp
private const val PUSH_SLOWEST_ROWS = 1f
private const val PUSH_FASTEST_ROWS = 12f
private const val PUSH_MAX_STEP_S = 0.1f

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
private val CameraBar = Color(0xFFC32F5B)
private val CameraStripe = Color(0xFFC83D66)
private val BarShadow get() = Am.PreviewBg
private val GripColor get() = Am.Inactive
private val HandleColor get() = Am.Inactive
private val LimitColor get() = Am.Warning
private val SnapColor get() = Am.Accent

private val TimecodeStyle get() = TextStyle(color = Am.OnDark, fontSize = 14.sp)
private val LabelStyle get() = TextStyle(color = Am.OnBar, fontSize = 11.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content)

@Composable
fun Timeline(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val wide = LocalWide.current
    val view = remember(state, density, wide) {
        TimelineView(state, density, scope, wide).also { v ->
            v.onTick = { if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val eye = rememberVectorPainter(TimelineIcons.Eye)
    val eyeOff = rememberVectorPainter(TimelineIcons.EyeOff)
    val outline = remember { Path() }
    val strips by remember(view) { derivedStateOf { view.stripWindow() } }
    val proxies by VideoProxies.progress.collectAsState()
    val mediaPictures = buildMap {
        for (row in strips.rows) {
            val layer = view.layerAt(row) ?: continue
            val info = layer.mediaFill?.let { state.scene.media(it.uri) }
            val turned = info?.orientation == 90 || info?.orientation == 270
            val width = (if (turned) info.height else info?.width)?.coerceAtLeast(1) ?: 1
            val height = (if (turned) info.width else info?.height)?.coerceAtLeast(1) ?: 1
            val tileMs = 23f * width / height * 1000 / view.dpPerSecond
            key(layer.id) { mediaStripPicture(layer, state.scene, state.engine, strips.fromMs, strips.toMs, tileMs)?.let { put(layer.id, it) } }
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
                view.finishWheelScrub()
            })
            .onSizeChanged { view.size = it }
            .tutorialRegion("ruler") { listOf(Rect(0f, 0f, view.size.width.toFloat(), view.rulerH)) }
            .tutorialRegion("clip") { view.clipRows(media = false) }
            .tutorialRegion("clip_media") { view.clipRows(media = true) }
            .pointerInput(view) { timelineGestures(view) { if (state.settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
            .drawBehind { drawTimeline(view, measurer, eye, eyeOff, outline, mediaPictures, proxies, appear.value) },
    ) {
        HeaderPictures(view) { drawLifted(view, measurer, eye, eyeOff, outline, mediaPictures, appear.value) }
        RowSemantics(view)
    }
}

@Composable
private fun RowSemantics(view: TimelineView) {
    val rows by remember(view) { derivedStateOf { view.visibleRows() } }
    val select = stringResource(R.string.a11y_select_layer)
    val hide = stringResource(R.string.a11y_hide_layer)
    val show = stringResource(R.string.a11y_show_layer)
    val hidden = stringResource(R.string.a11y_layer_hidden)
    Box(Modifier.fillMaxSize().padding(top = RulerHeight).clipToBounds()) {
        for (row in rows) {
            val layer = view.layerAt(row) ?: continue
            key(layer.id) {
                val chosen = layer.id == view.state.selectedId || layer.id in MultiSelect.ids
                Box(Modifier.offset { IntOffset(0, (view.rowTop(row) - view.rulerH).roundToInt()) }.fillMaxWidth().height(RowHeight).tutorialGuard(any = true).semantics {
                    contentDescription = layer.shownName()
                    selected = chosen
                    if (layer.hidden) stateDescription = hidden
                    onClick(select) { view.tap(Hit.Header(layer.id)); true }
                    customActions = if (app.absentmotion.ui.tutorial.Tutorial.running) emptyList() else listOf(CustomAccessibilityAction(if (layer.hidden) show else hide) { view.state.toggleHidden(layer.id); true })
                })
            }
        }
    }
}

@Composable
private fun HeaderPictures(view: TimelineView, drawLift: DrawScope.() -> Unit) {
    val rows by remember(view) { derivedStateOf { view.visibleRows() } }
    Box(Modifier.fillMaxSize().padding(top = RulerHeight).clipToBounds()) {
        for (row in rows) {
            val layer = view.layerAt(row)
            if (layer != null) {
                key(layer.id) {
                    val lifted = layer.id == view.liftId
                    LayerThumbnail(
                        layer, view.state.engine,
                        Modifier
                            .zIndex(if (lifted) 2f else 0f)
                            .offset {
                                val top = view.shownTop(row, layer.id) - view.rulerH + (RowHeight - ThumbSize).toPx() / 2
                                IntOffset(ThumbLeft.roundToPx(), top.roundToInt())
                            }
                            .then(if (lifted) Modifier.graphicsLayer {
                                val grow = 1 + (LIFT_SCALE - 1) * view.liftAmount
                                scaleX = grow
                                scaleY = grow
                                transformOrigin = TransformOrigin((view.size.width / 2f - ThumbLeft.toPx()) / ThumbSize.toPx(), 0.5f)
                            } else Modifier)
                            .size(ThumbSize)
                            .alpha(if (layer.hidden) HIDDEN_ALPHA else 1f),
                    )
                }
            }
        }
        if (view.liftId != null) Box(Modifier.fillMaxSize().zIndex(1f).drawBehind(drawLift))
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

    fun rowsBottom(height: Float, panelTop: Float?, barHeight: Float): Float = min(panelTop ?: height, height - barHeight)

    fun maxScroll(rows: Int, rowH: Float, top: Float, bottom: Float): Float = max(0f, rows * rowH - (bottom - top))

    fun centredScroll(row: Int, rowH: Float, top: Float, bottom: Float): Float = (row + 0.5f) * rowH - (bottom - top) / 2

    fun visibleRows(rows: Int, rowH: Float, top: Float, bottom: Float, scroll: Float): IntRange =
        max(0, floor(scroll / rowH).toInt())..min(rows - 1, ceil((bottom - top + scroll) / rowH).toInt() - 1)

    fun released(end: PointerInputChange?): Boolean = end == null || !end.isConsumed

    fun aboveAddMenu(y: Float, height: Float, menu: Float, ruler: Float): Boolean = y >= 0f && y < minOf(height - menu, ruler)

    fun snapFrame(wantedMs: Float, targets: Collection<Int>, fps: Int, zoneMs: Float): Int? {
        val best = targets.minByOrNull { abs(frameTimeMs(it, fps) - wantedMs) } ?: return null
        return best.takeIf { abs(frameTimeMs(it, fps) - wantedMs) <= zoneMs }
    }

    fun moveTarget(wantedMs: Float, spanMs: Float, spanFrames: Int, targets: Collection<Int>, fps: Int, zoneMs: Float): MoveTarget {
        val snapped = listOf(0f to 0, spanMs to spanFrames).flatMap { (offsetMs, offsetFrames) ->
            targets.map { hit -> Triple(hit - offsetFrames, hit, abs(frameTimeMs(hit, fps) - wantedMs - offsetMs)) }
        }.filter { it.first >= 0 && it.third <= zoneMs }.minByOrNull { it.third }
        return snapped?.let { MoveTarget(it.first, it.second) } ?: MoveTarget(nearestFrame(wantedMs, fps).coerceAtLeast(0), null)
    }

    fun movedStart(scene: Scene, layer: Layer, frame: Int): Int =
        if (frame == scene.firstShownFrame(layer.startTime)) layer.startTime else scene.frameStart(frame)

    fun snapTargets(scene: Scene, skip: Long, playhead: Int): Set<Int> = snapTargets(scene, setOf(skip), playhead)

    fun snapTargets(scene: Scene, skip: Set<Long>, playhead: Int): Set<Int> = buildSet {
        add(playhead)
        scene.bookmarks.forEach { add(scene.firstShownFrame(it)) }
        for (layer in scene.layers) if (layer.id !in skip) {
            add(scene.firstShownFrame(layer.startTime))
            add(scene.lastShownFrame(layer.endTime) + 1)
            layer.keyframeTimes().forEach { add(scene.frameAt(it)) }
        }
    }

    fun groupTarget(scene: Scene, grabbed: Layer, others: List<Layer>, found: MoveTarget): MoveTarget {
        if (others.isEmpty()) return found
        val first = scene.firstShownFrame(grabbed.startTime)
        val lowest = minOf(first, others.minOf { scene.firstShownFrame(it.startTime) })
        return if (found.frame - first >= -lowest) found else MoveTarget(first - lowest, null)
    }

    fun movedClips(scene: Scene, grabbed: Layer, others: List<Layer>, frame: Int): Scene {
        val shift = frame - scene.firstShownFrame(grabbed.startTime)
        return (listOf(grabbed) + others).fold(scene) { acc, layer ->
            acc.update(layer.id) { acc.movedClip(layer, movedStart(acc, layer, acc.firstShownFrame(layer.startTime) + shift)) }
        }
    }
}

internal data class MoveTarget(val frame: Int, val snap: Int?)

private data class StripWindow(val rows: IntRange, val fromMs: Float, val toMs: Float)

private const val STRIP_WINDOW_MS = 2000f

private class TimeScale(val nowMs: Float, val originX: Float, val pxPerMs: Float) {
    fun x(timeMs: Float) = TimelineMath.timeToX(timeMs, nowMs, originX, pxPerMs)
    fun time(x: Float) = TimelineMath.xToTime(x, nowMs, originX, pxPerMs)
    fun slid(dx: Float) = TimeScale(nowMs, originX + dx, pxPerMs)
}

private sealed interface Hit {
    data object Ruler : Hit
    data object Empty : Hit

    data object Readout : Hit
    data class Eye(val id: Long) : Hit

    data class Bar(val id: Long) : Hit
    data class Key(val id: Long, val frame: Int) : Hit
    data class Header(val id: Long) : Hit
    data class Grip(val id: Long, val start: Boolean) : Hit
    data class Handle(val id: Long) : Hit
    data class Row(val id: Long) : Hit
}

private enum class Drag(val edit: Boolean = false) { Scrub, Scroll, Pinch, Spent, Select, TrimStart(true), TrimEnd(true), Reorder(true), Lift(true), Key(true) }

private const val KEY_MOVE = "keyframe-move"
private const val MOVE_EDIT = "clip-move"


private class TimelineView(val state: EditorState, density: Density, private val scope: CoroutineScope, private val wide: Boolean) {
    private val scale = density.density
    private val Dp.px get() = value * scale
    private val decay = splineBasedDecay<Float>(density)

    val rulerH = RulerHeight.px
    val rowH = RowHeight.px

    var size by mutableStateOf(IntSize.Zero)

    val dpPerSecond: Float get() = state.timelineZoom
    private var scrollPx by mutableFloatStateOf(0f)

    private var scrubMs by mutableStateOf<Float?>(null)
    private var scrubFrame = 0
    private var motion: Job? = null

    private var dragId = 0L
    private var edgeMs = 0f
    private var trimBase: Layer? = null
    private var trimScene: Scene? = null
    var limitEdge by mutableStateOf<Boolean?>(null)
        private set
    var onTick: () -> Unit = {}
    var snapAt by mutableStateOf<Int?>(null)
        private set
    private var snapTargets = emptySet<Int>()

    private class Slide(val from: Float, val at: Long)

    private var selectAnchor: Long? = null
    private var selectBase = emptySet<Long>()
    private var selectAdd = true
    private var selectStart = 0f
    private var selectFinger = 0f
    private var selectSlop = 0f
    private var selectPush = true
    private var selectArmed = false
    private var selectRows = 1

    var liftId by mutableStateOf<Long?>(null)
        private set
    private var liftTop by mutableFloatStateOf(0f)
    private var frameMs by mutableLongStateOf(0L)
    private var liftJob: Job? = null
    private var liftAt = 0L
    private var liftGrab = 0f
    private var liftFinger = 0f
    private var liftStart = 0f
    private var liftSlop = 0f
    private var liftArmed = false
    private var liftPush = true
    private var dropping = false
    private var dropAt = 0L
    private var dropFrom = 0f
    private val slides = HashMap<Long, Slide>()

    var keyId by mutableStateOf<Long?>(null)
        private set
    var keyFrame by mutableIntStateOf(0)
        private set
    private var keyFrom = 0
    private var keyMs = 0f
    private var keyLayer: Layer? = null
    private var keyScope: KeyScope? = null

    var moveId by mutableStateOf<Long?>(null)
        private set
    var moveSlide by mutableFloatStateOf(0f)
        private set
    private var moveLayer: Layer? = null
    private var moveGroup: List<Layer> = emptyList()
    private var moveGroupIds: Set<Long> = emptySet()
    private var moveBase: Scene? = null
    private var moveDx = 0f
    private var moveArmed = false
    private var liftMoves = false
    private var liftStartX = 0f

    val liftMoved: Boolean get() = liftArmed || moveArmed

    private val layersState = derivedStateOf(referentialEqualityPolicy()) { state.scene.layers }

    val layers: List<Layer> get() = layersState.value
    val fps: Int get() = state.scene.fps.coerceAtLeast(1)

    private val nowMs: Float
        get() = scrubMs?.takeIf { state.frame == scrubFrame } ?: TimelineMath.frameTimeMs(state.frame, fps)

    fun timeScale() = TimeScale(nowMs, (size.width - PlayheadWidth.px) / 2f, dpPerSecond * scale / 1000f)

    private val covered: Boolean get() = !wide && state.selectedId != null

    private val panelTop: Float get() = size.height - PanelHeight.px

    private val rowsBottom: Float
        get() = TimelineMath.rowsBottom(size.height.toFloat(), if (covered) panelTop else null, if (MultiSelect.active) MultiBarHeight.px else 0f)

    val scroll: Float
        get() = scrollPx.coerceIn(0f, TimelineMath.maxScroll(layers.size, rowH, rulerH, rowsBottom))

    fun layerAt(row: Int): Layer? = layers.getOrNull(layers.lastIndex - row)

    fun rowTop(row: Int): Float = rulerH + row * rowH - scroll

    private fun rowAt(y: Float): Int = floor((y - rulerH + scroll) / rowH).toInt()

    fun visibleRows(): IntRange = TimelineMath.visibleRows(layers.size, rowH, rulerH, rowsBottom, scroll)

    fun clipRows(media: Boolean): List<Rect> {
        val time = timeScale()
        val from = HeaderWidth.px
        val to = size.width.toFloat()
        return visibleRows().mapNotNull { row ->
            val layer = layerAt(row) ?: return@mapNotNull null
            val kind = kindOf(layer)
            val top = maxOf(rowTop(row), rulerH)
            val bottom = minOf(rowTop(row) + rowH, rowsBottom)
            val left = maxOf(time.x(layer.startTime.toFloat()), from)
            val right = minOf(time.x(layer.endTime + 1f), to)
            if (bottom > top && right > left && (kind == TourFacts.MEDIA || !media && kind == TourFacts.TEXT)) Rect(left, top, right, bottom) else null
        }
    }

    fun stripWindow(): StripWindow {
        val time = timeScale()
        return StripWindow(visibleRows(), floor(time.time(0f) / STRIP_WINDOW_MS) * STRIP_WINDOW_MS,
            ceil(time.time(size.width.toFloat()) / STRIP_WINDOW_MS) * STRIP_WINDOW_MS)
    }

    private val keyFrames = HashMap<Pair<Long, KeyScope?>, List<Int>>()
    private val keyFrameNodes = HashMap<Pair<Long, KeyScope?>, app.absentmotion.model.XmlNode>()

    private fun scopeOf(layer: Layer): KeyScope? = if (layer.id == state.selectedId) state.keyScope else null

    fun keyFrames(layer: Layer): List<Int> {
        val scope = scopeOf(layer)
        val key = layer.id to scope
        val cached = keyFrames[key]
        if (cached != null && keyFrameNodes[key] === layer.node) return cached
        return layer.keyframeTimes(scope).map { state.scene.frameAt(it) }.distinct().also {
            keyFrames[key] = it
            keyFrameNodes[key] = layer.node
        }
    }

    private var scrollBeforeSelect: Float? = null

    fun revealSelected() {
        val id = state.selectedId
        if (id == null) {
            scrollBeforeSelect?.takeUnless { MultiSelect.active }?.let { scrollPx = it }
            scrollBeforeSelect = null
            return
        }
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return
        if (scrollBeforeSelect == null) scrollBeforeSelect = scrollPx
        scrollPx = TimelineMath.centredScroll(layers.lastIndex - index, rowH, rulerH, rowsBottom)
    }

    fun isCovered(at: Offset): Boolean = (covered && at.y >= panelTop) || (MultiSelect.active && at.y >= size.height - MultiBarHeight.px)

    fun dismissZone(at: Offset): Boolean = state.addOpen && TimelineMath.aboveAddMenu(at.y, size.height.toFloat(), AddPopupHeight.px, rulerH)

    fun dismissAddMenu(): Boolean {
        if (!state.addOpen) return false
        state.addOpen = false
        return true
    }

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
        val key = if (selected && at.x in left..right) keyNear(layer, at.x, time) else null
        return when {
            at.x < EyeZone.px -> Hit.Eye(layer.id)
            at.x < HeaderWidth.px -> Hit.Header(layer.id)
            key != null -> Hit.Key(layer.id, key)
            selected && at.x in left - GripZone.px..left -> Hit.Grip(layer.id, start = true)
            selected && at.x in right..right + GripZone.px -> Hit.Grip(layer.id, start = false)
            !selected && at.x >= size.width - HandleZone.px -> Hit.Handle(layer.id)
            at.x in left..right -> Hit.Bar(layer.id)
            else -> Hit.Row(layer.id)
        }
    }

    fun tap(hit: Hit) {
        when (hit) {
            is Hit.Eye -> if (MultiSelect.active) MultiSelect.toggle(hit.id) else state.toggleHidden(hit.id)
            is Hit.Header -> select(hit.id)
            is Hit.Bar -> select(hit.id)
            is Hit.Key -> select(hit.id)
            Hit.Readout -> state.toggleBookmark()
            Hit.Empty, Hit.Ruler, is Hit.Row -> state.clearSelection()
            is Hit.Grip, is Hit.Handle -> Unit
        }
    }

    private fun select(id: Long) {
        if (MultiSelect.active) MultiSelect.toggle(id) else {
            state.selectedId = id
            state.panel = EditorPanel.Tools
        }
    }

    private fun keyNear(layer: Layer, x: Float, time: TimeScale): Int? {
        val scene = state.scene
        fun distance(frame: Int) = abs(time.x(scene.frameStart(frame).toFloat()) - x)
        return layer.keyframeTimes(scopeOf(layer)).map { scene.frameAt(it) }.minByOrNull(::distance)?.takeIf { distance(it) <= KeyGrab.px }
    }

    fun grabKey(hit: Hit.Key) {
        state.playing = false
        keyLayer = state.scene.layer(hit.id)
        keyScope = state.keyScope
        keyFrom = hit.frame
        keyFrame = hit.frame
        keyMs = TimelineMath.frameTimeMs(hit.frame, fps)
        keyId = hit.id
        snapTargets = TimelineMath.snapTargets(state.scene, hit.id, state.frame)
    }

    private fun snapNear(ms: Float): Int? = TimelineMath.snapFrame(ms, snapTargets, fps, SnapZone.px / timeScale().pxPerMs)

    private fun showSnap(snap: Int?, landed: Int) {
        val shown = snap?.takeIf { it == landed }
        if (shown != null && shown != snapAt) onTick()
        snapAt = shown
    }

    private fun keyBy(dx: Float) {
        val original = keyLayer ?: return
        val scene = state.scene
        keyMs += dx / timeScale().pxPerMs
        val snap = snapNear(keyMs)
        val frame = (snap ?: TimelineMath.nearestFrame(keyMs, fps)).coerceIn(scene.frameAt(original.startTime), scene.frameAt(original.endTime))
        showSnap(snap, frame)
        if (frame == keyFrame) return
        keyFrame = frame
        state.edit(KEY_MOVE) { s -> s.update(original.id) { original.withKeyframesMoved(keyFrom, frame, s, keyScope) } }
    }

    private fun dropTrim(key: String) {
        val base = trimBase ?: return
        if (state.scene.layer(base.id)?.node == base.node) state.cancelGesture(key)
    }

    private fun dropKey(commit: Boolean) {
        val original = keyLayer
        if (!commit || original == null || state.scene.layer(original.id)?.node == original.node) state.cancelGesture(KEY_MOVE)
        keyLayer = null
        keyId = null
    }

    fun longPress(hit: Hit, y: Float, slop: Float): Boolean {
        val id = when (hit) { is Hit.Eye -> hit.id; is Hit.Header -> hit.id; else -> return false }
        state.deselectLayer()
        if (MultiSelect.active) MultiSelect.toggle(id) else {
            MultiSelect.ids = setOf(id)
            MultiSelect.active = true
        }
        selectAnchor = id
        selectBase = MultiSelect.ids
        selectAdd = id in selectBase
        selectStart = y
        selectFinger = y
        selectSlop = slop
        selectPush = true
        selectArmed = false
        selectRows = 1
        liftJob?.cancel()
        liftJob = scope.launch { liftLoop() }
        return true
    }

    fun selectDrag(y: Float, pushing: Boolean) {
        selectFinger = y
        selectPush = pushing
        if (abs(y - selectStart) > selectSlop) selectArmed = true
        selectUpdate()
    }

    fun endSelect() {
        selectAnchor = null
        liftJob?.cancel()
    }

    private fun selectUpdate() {
        val anchor = rowOf(selectAnchor ?: return) ?: return
        val finger = if (selectArmed) RowDrag.fingerRow(selectFinger, rowH, rulerH, rowsBottom, scroll, layers.size) else anchor
        val rows = abs(finger - anchor) + 1
        if (rows > selectRows) onTick()
        selectRows = rows
        MultiSelect.ids = RowDrag.slideSelection(selectBase, layers.asReversed().map { it.id }, anchor, finger, selectAdd)
    }

    fun beginDrag(hit: Hit, moved: Offset): Drag {
        return when {
            hit is Hit.Grip -> {
                state.playing = false
                val layer = layers.firstOrNull { it.id == hit.id }
                dragId = hit.id
                trimBase = layer
                trimScene = state.scene
                edgeMs = (if (hit.start) layer?.startTime else layer?.endTime?.plus(1))?.toFloat() ?: 0f
                snapTargets = TimelineMath.snapTargets(state.scene, hit.id, state.frame)
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
    }

    fun dragBy(drag: Drag, delta: Offset, at: Offset) {
        when (drag) {
            Drag.Scrub -> { state.transitionButtonDismissed = false; scrubBy(delta.x) }
            Drag.Scroll -> scrollBy(delta.y)
            Drag.TrimStart, Drag.TrimEnd -> trimBy(delta.x, start = drag == Drag.TrimStart)
            Drag.Reorder -> reorderTo(at.y)
            Drag.Key -> keyBy(delta.x)
            Drag.Pinch, Drag.Spent, Drag.Lift, Drag.Select -> Unit
        }
    }

    fun endDrag(drag: Drag?, velocity: Velocity, lifted: Boolean = true) {
        limitEdge = null
        snapAt = null
        if (drag == Drag.Lift) drop()
        if (drag == Drag.Lift) dropMove(lifted)
        if (drag == Drag.Select) endSelect()
        if (drag == Drag.Key) dropKey(lifted)
        if (drag == Drag.TrimStart || drag == Drag.TrimEnd) dropTrim(if (drag == Drag.TrimStart) "trim-start" else "trim-end")
        if (drag?.edit == true) state.endGesture()
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
        state.timelineZoom = (dpPerSecond * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
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

    fun finishWheelScrub() { motion = scope.launch { kotlinx.coroutines.delay(120); settle() } }

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
        val base = trimBase ?: return
        val from = trimScene ?: return
        val snap = snapNear(edgeMs)
        val frame = snap ?: TimelineMath.nearestFrame(edgeMs, fps)
        val id = dragId
        val edge = if (start) frame.coerceIn(0, scene.lastShownFrame(layer.endTime).coerceAtLeast(0)) else (frame - 1).coerceAtLeast(scene.firstShownFrame(layer.startTime)) + 1
        val time = if (start) scene.frameStart(edge) else scene.frameEnd(edge - 1)
        val trimmed = if (start) from.trimmedClip(base, time, base.endTime) else from.trimmedClip(base, base.startTime, time)
        if (time != (if (start) layer.startTime else layer.endTime)) state.edit(if (start) "trim-start" else "trim-end") { it.withClip(id, trimmed, from) }
        val hit = if (start) trimmed.startTime > time else trimmed.endTime < time
        if (hit && limitEdge == null) onTick()
        limitEdge = start.takeIf { hit }
        showSnap(snap.takeUnless { hit }, edge)
    }

    private fun moveBegin(id: Long): Boolean {
        val layer = state.scene.layer(id) ?: return false
        moveLayer = layer
        moveGroup = if (MultiSelect.active && id in MultiSelect.ids) state.scene.layers.filter { it.id in MultiSelect.ids && it.id != id } else emptyList()
        moveGroupIds = moveGroup.map { it.id }.toSet()
        moveBase = state.scene
        moveId = id
        moveDx = 0f
        moveSlide = 0f
        moveArmed = false
        snapTargets = TimelineMath.snapTargets(state.scene, moveGroupIds + id, state.frame)
        return true
    }

    private fun moveApply() {
        val original = moveLayer ?: return
        state.playing = false
        val scene = state.scene
        val pxPerMs = timeScale().pxPerMs
        val wanted = original.startTime + moveDx / pxPerMs
        val spanFrames = scene.lastShownFrame(original.endTime) + 1 - scene.firstShownFrame(original.startTime)
        val found = TimelineMath.moveTarget(wanted, (original.endTime + 1 - original.startTime).toFloat(), spanFrames, snapTargets, fps, SnapZone.px / pxPerMs)
        val target = TimelineMath.groupTarget(scene, original, moveGroup, found)
        val start = TimelineMath.movedStart(scene, original, target.frame)
        showSnap(target.snap, target.snap ?: target.frame)
        moveSlide = if (target.snap != null || target != found || wanted <= 0f) 0f else (wanted - start) * pxPerMs
        val group = moveGroup
        if (scene.layer(original.id)?.startTime != start) state.edit(MOVE_EDIT) { TimelineMath.movedClips(it, original, group, target.frame) }
    }

    private fun dropMove(commit: Boolean) {
        if (!commit || state.scene == moveBase) state.cancelGesture(MOVE_EDIT)
        moveId = null
        moveLayer = null
        moveGroup = emptyList()
        moveGroupIds = emptySet()
        moveBase = null
        moveSlide = 0f
    }

    fun timeFor(id: Long, time: TimeScale) = if (id == moveId || id in moveGroupIds) time.slid(moveSlide) else time

    private fun reorderTo(y: Float) {
        val layers = layers
        if (layers.isEmpty()) return
        val index = layers.lastIndex - rowAt(y).coerceIn(0, layers.lastIndex)
        val id = dragId
        if (layers[index].id != id) state.edit("reorder") { it.reorder(id, index) }
    }

    fun rowOf(id: Long): Int? = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { layers.lastIndex - it }

    private fun progress(ms: Long, total: Int) = Motion.EaseOut.transform((ms / total.toFloat()).coerceIn(0f, 1f))

    val liftAmount: Float
        get() {
            val now = frameMs
            return if (dropping) dropFrom * (1 - progress(now - dropAt, DROP_MS)) else progress(now - liftAt, LIFT_MS)
        }

    fun shownTop(row: Int, id: Long): Float {
        val now = frameMs
        val top = rowTop(row)
        if (id == liftId) return if (dropping) liftTop + (top - liftTop) * progress(now - dropAt, DROP_MS) else liftTop
        val slide = slides[id] ?: return top
        return top + slide.from * (1 - progress(now - slide.at, SLIDE_MS)) * rowH
    }

    fun lift(id: Long, at: Offset, slop: Float, moves: Boolean): Boolean {
        val row = rowOf(id) ?: return false
        if (!moveBegin(id)) return false
        liftMoves = moves
        liftStartX = at.x
        liftJob?.cancel()
        liftAt = SystemClock.uptimeMillis()
        liftGrab = at.y - rowTop(row)
        liftFinger = at.y
        liftStart = at.y
        liftSlop = slop
        liftArmed = false
        liftPush = true
        dropping = false
        liftTop = rowTop(row)
        liftId = id
        liftJob = scope.launch { liftLoop() }
        liftUpdate()
        return true
    }

    fun liftDrag(at: Offset, pushing: Boolean) {
        liftFinger = at.y
        liftPush = pushing
        if (abs(at.y - liftStart) > liftSlop) liftArmed = true
        if (liftMoves) {
            if (abs(at.x - liftStartX) > liftSlop) moveArmed = true
            if (moveArmed) {
                moveDx = at.x - liftStartX
                moveApply()
            }
        }
        liftUpdate()
    }

    fun liftScroll(dy: Float) {
        scrollBy(dy)
        liftUpdate()
        selectUpdate()
    }

    private fun drop() {
        if (liftId == null || dropping) return
        dropFrom = liftAmount
        dropAt = SystemClock.uptimeMillis()
        dropping = true
    }

    private fun liftUpdate() {
        val id = liftId ?: return
        if (dropping) return
        val layers = layers
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return
        liftTop = RowDrag.clampTop(liftFinger - liftGrab, rowH, rulerH, rowsBottom, scroll, layers.size)
        val from = layers.lastIndex - index
        val to = RowDrag.slot(liftTop, rowH, rulerH, scroll, layers.size)
        if (to == from) return
        val now = SystemClock.uptimeMillis()
        for ((i, layer) in layers.withIndex()) {
            val shift = RowDrag.shift(layers.lastIndex - i, from, to)
            if (shift == 0) continue
            val left = slides[layer.id]?.let { it.from * (1 - progress(now - it.at, SLIDE_MS)) } ?: 0f
            slides[layer.id] = Slide(left - shift, now)
        }
        state.edit(MOVE_EDIT) { it.reorder(id, layers.lastIndex - to) }
        onTick()
    }

    private fun edgeSpeed(y: Float) = RowDrag.edgeSpeed(y, rulerH, rowsBottom, PushZone.px, PUSH_SLOWEST_ROWS * rowH, PUSH_FASTEST_ROWS * rowH)

    private suspend fun liftLoop() {
        val me = coroutineContext.job
        var last = SystemClock.uptimeMillis()
        try {
            while (true) {
                val now = withFrameNanos { SystemClock.uptimeMillis() }
                val step = min((now - last) / 1000f, PUSH_MAX_STEP_S)
                last = now
                frameMs = now
                slides.values.removeAll { now - it.at >= SLIDE_MS }
                if (dropping) {
                    if (now - dropAt >= DROP_MS && slides.isEmpty()) break
                } else if (selectAnchor != null) {
                    val speed = RowDrag.toward(edgeSpeed(selectFinger), selectFinger, selectStart, selectSlop)
                    if (selectPush && speed != 0f) {
                        scrollBy(-speed * step)
                        selectUpdate()
                    }
                } else if (liftArmed && liftPush) {
                    val speed = edgeSpeed(liftFinger)
                    if (speed != 0f) {
                        scrollBy(-speed * step)
                        liftUpdate()
                    }
                }
            }
        } finally {
            if (liftJob === me) {
                liftId = null
                dropping = false
                slides.clear()
            }
        }
    }
}

private val KeyFaint = DpSize(6.4.dp, 9.6.dp)
private val KeyOther = DpSize(9.25.dp, 14.6.dp)
private val KeyHere = DpSize(12.1.dp, 18.5.dp)
private val KeyGrab = 14.dp

private suspend fun PointerInputScope.timelineGestures(view: TimelineView, onLongPress: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown()
    view.stopMotion()
    if (view.isCovered(down.position)) return@awaitEachGesture
    val hit = view.hitAt(down.position)
    val tracker = VelocityTracker().apply { addPointerInputChange(down) }
    var drag: Drag? = null
    var lifted = false
    var held = false
    var last: PointerInputChange? = null
    var finger = down.position
    var second: PointerId? = null
    val dismissing = view.dismissZone(down.position)
    val holdable = !dismissing && (hit is Hit.Eye || hit is Hit.Header || hit is Hit.Row || hit is Hit.Key || hit is Hit.Bar)
    val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
    try {
        while (true) {
            val event = if (holdable && drag == null && !held) {
                withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (event == null) {
                when {
                    hit is Hit.Key -> {
                        view.grabKey(hit)
                        drag = Drag.Key
                    }
                    hit is Hit.Row && view.lift(hit.id, finger, viewConfiguration.touchSlop, moves = false) -> drag = Drag.Lift
                    hit is Hit.Bar && view.lift(hit.id, finger, viewConfiguration.touchSlop, moves = true) -> drag = Drag.Lift
                    view.longPress(hit, finger.y, viewConfiguration.touchSlop) -> drag = Drag.Select
                    else -> held = true
                }
                onLongPress()
                continue
            }
            val fingers = event.changes.count { it.pressed }
            val first = event.changes.firstOrNull { it.id == down.id }
            last = first
            if (first != null) {
                tracker.addPointerInputChange(first)
                finger = first.position
            }
            when {
                fingers == 0 -> break
                drag == Drag.Lift || drag == Drag.Select -> {
                    if (first == null || !first.pressed) break
                    for (change in event.changes) {
                        if (change.id == down.id) continue
                        if (second == null && change.changedToDown() && change.position.y >= view.rulerH) second = change.id
                        if (change.id != second) continue
                        if (change.pressed) view.liftScroll(change.position.y - change.previousPosition.y) else second = null
                    }
                    if (drag == Drag.Lift) view.liftDrag(first.position, pushing = second == null)
                    else view.selectDrag(first.position.y, pushing = second == null)
                    event.changes.forEach { it.consume() }
                }
                fingers > 1 && drag == Drag.Key -> {
                    view.endDrag(Drag.Key, Velocity.Zero, lifted = false)
                    drag = Drag.Spent
                }
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
        lifted = TimelineMath.released(last)
    } finally {
        if (lifted && (drag == null && !held || drag == Drag.Lift && hit is Hit.Bar && !view.liftMoved)) {
            if (dismissing) view.dismissAddMenu() else view.tap(hit)
        }
        view.endDrag(drag, if (lifted) tracker.calculateVelocity() else Velocity.Zero, lifted)
    }
}

private fun DrawScope.drawTimeline(view: TimelineView, measurer: TextMeasurer, eye: Painter, eyeOff: Painter, outline: Path, pictures: Map<Long, StripPicture>, proxies: Map<String, Float>, appear: Float = 1f) {
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
            if (layer.id == view.liftId) continue
            val rowTime = view.timeFor(layer.id, time)
            drawRow(layer, layer.id == state.selectedId, view.shownTop(row, layer.id), rowTime, stripe, measurer, if (layer.hidden) eyeOff else eye, outline, state.scene, state.frame, pictures[layer.id], view.keyFrames(layer), appear, view.keyFrame.takeIf { layer.id == view.keyId }, view.limitEdge.takeIf { layer.id == state.selectedId })
            layer.mediaFill?.takeIf { it.video }?.let { proxies[it.uri] }?.let { done ->
                drawProxyProgress(rowTime.x(layer.startTime.toFloat()), rowTime.x(layer.endTime + 1f), view.shownTop(row, layer.id), done)
            }
        }
    }
    view.snapAt?.let { frame ->
        val half = SnapLine.toPx() / 2
        drawRect(SnapColor, Offset(time.x(state.scene.frameStart(frame).toFloat()) - half, view.rulerH), Size(2 * half, size.height - view.rulerH))
    }
    val marks = state.scene.bookmarks
    val onBookmark = marks.any { state.scene.frameAt(it) == state.frame }
    for (ms in marks) drawBookmark(time.x(ms.toFloat()), outline)
    drawPlayhead(time.originX, TimelineMath.timecode(state.frame, fps), measurer, outline, onBookmark)
}

private fun DrawScope.drawLifted(view: TimelineView, measurer: TextMeasurer, eye: Painter, eyeOff: Painter, outline: Path, pictures: Map<Long, StripPicture>, appear: Float) {
    val id = view.liftId ?: return
    val row = view.rowOf(id) ?: return
    val layer = view.layerAt(row) ?: return
    val state = view.state
    val time = view.timeScale()
    val stripe = TimelineMath.frameTimeMs(2 * TimelineMath.tickStep(view.fps, view.dpPerSecond), view.fps) * time.pxPerMs
    val amount = view.liftAmount
    val rowH = view.rowH
    drawRect(Am.TimelineBg, alpha = LIFT_DIM * amount)
    translate(top = -view.rulerH) {
        val top = view.shownTop(row, id)
        val grow = 1 + (LIFT_SCALE - 1) * amount
        scale(grow, grow, Offset(size.width / 2, top + rowH / 2)) {
            val step = LiftShadowStep.toPx()
            for (i in 1..4) drawRect(Color.Black, Offset(-i * step, top - i * step + step), Size(size.width + 2 * i * step, rowH + 2 * i * step), alpha = LIFT_SHADOW_ALPHA * amount)
            drawRect(Am.TimelineBg, Offset(0f, top), Size(size.width, rowH), alpha = amount)
            drawRow(layer, id == state.selectedId, top, view.timeFor(id, time), stripe, measurer, if (layer.hidden) eyeOff else eye, outline, state.scene, state.frame, pictures[id], view.keyFrames(layer), appear)
        }
    }
}

private fun DrawScope.drawProxyProgress(left: Float, right: Float, top: Float, done: Float) {
    val from = max(left, 0f)
    val to = min(right, size.width)
    if (from >= to) return
    val height = ProxyLineHeight.toPx()
    val y = top + (RowHeight - BarInset).toPx() - height - ProxyLineInset.toPx()
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(from, y), Size(to - from, height))
    drawRect(Am.Accent, Offset(from, y), Size((to - from) * done.coerceIn(0f, 1f), height))
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
    measurer: TextMeasurer, eye: Painter, outline: Path, scene: Scene, frame: Int, picture: StripPicture?, keyFrames: List<Int>, appear: Float = 1f, held: Int? = null, limit: Boolean? = null,
) {
    val barTop = top + BarInset.toPx()
    val barBottom = top + (RowHeight - BarInset).toPx()
    val centre = top + RowHeight.toPx() / 2
    val left = time.x(layer.startTime.toFloat())
    val right = time.x(layer.endTime + 1f)

    if (layer.kind == "camera" && layer.id == scene.defaultCameraId) drawRect(CameraBar.copy(alpha = 0.1f), Offset(0f, top), Size(size.width, RowHeight.toPx()))
    if (layer.kind == "audio") drawAudioBar(outline, layer, left, right, barTop, barBottom, time.pxPerMs)
    else drawBar(outline, left, right, barTop, barBottom, stripe, picture, layer.startTime, time.pxPerMs, layer.kind == "text", layer.kind == "camera")
    layer.labelColour?.let { drawTagCorner(outline, left, barTop, (barBottom - barTop) / 2, Color(it.start)) }
    drawLabel(layer.shownName(), measurer, left, right, barTop, barBottom, if (layer.kind == "audio") LabelStyle.copy(color = Am.OnAudioBar) else LabelStyle)
    drawKeyframes(keyFrames, scene, selected, frame, time, left, right, centre, outline, appear, held)
    if (selected) {
        drawGrip(outline, left, barTop, barBottom, start = true, appear, limit == true)
        drawGrip(outline, right, barTop, barBottom, start = false, appear, limit == false)
    } else {
        drawGrabLines(Offset(size.width - HandleInset.toPx(), centre))
    }
    drawHeader(top, eye, layer.labelColour, layer.id in MultiSelect.ids)
}

private fun DrawScope.drawBar(outline: Path, left: Float, right: Float, top: Float, bottom: Float, stripe: Float, picture: StripPicture?, startTime: Int, pxPerMs: Float, text: Boolean = false, camera: Boolean = false) {
    val radius = BarRadius.toPx()
    val from = max(left, -2 * radius)
    val to = min(right, size.width + 2 * radius)
    if (from >= to) return
    drawRect(BarShadow, Offset(from, bottom - radius), Size(to - from, radius + BarShadowHeight.toPx()))
    outline.rewind()
    outline.addRoundRect(RoundRect(from, top, to, bottom, CornerRadius(radius)))
    clipPath(outline) {
        drawRect(if (camera) CameraBar else if (text) TextBar else Am.LayerBar, Offset(from, top), Size(to - from, bottom - top))
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
                drawRect(if (camera) CameraStripe else if (text) TextStripe else BarStripe, Offset(x, top), Size(stripe, bottom - top))
                x += 2 * stripe
            }
        }
    }
}

private fun DrawScope.drawKeyframes(frames: List<Int>, scene: Scene, selected: Boolean, frame: Int, time: TimeScale, left: Float, right: Float, centre: Float, outline: Path, appear: Float = 1f, held: Int? = null) {
    if (frames.isEmpty()) return
    val grabbed = held?.let { h -> frames.minByOrNull { abs(it - h) } }
    clipRect(left = max(left, 0f), right = min(right, size.width)) {
        for (keyFrame in frames) {
            val x = time.x(scene.frameStart(keyFrame).toFloat())
            if (x < -KeyHere.width.toPx() || x > size.width + KeyHere.width.toPx()) continue
            val here = selected && (keyFrame == frame || keyFrame == grabbed)
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
            drawPath(outline, if (keyFrame == grabbed) Am.Accent else Color.White)
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

private fun DrawScope.drawGrip(outline: Path, edge: Float, top: Float, bottom: Float, start: Boolean, appear: Float = 1f, limit: Boolean = false) {
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
    drawPath(outline, if (limit) LimitColor else GripColor, alpha = appear)

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
