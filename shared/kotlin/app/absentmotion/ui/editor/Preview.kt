package app.absentmotion.ui.editor

import android.graphics.Rect
import android.os.Build
import app.absentmotion.platform.SystemClock
import app.absentmotion.platform.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import app.absentmotion.editor.PreviewCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.clearSelection
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.place
import app.absentmotion.editor.withWorldLocation
import app.absentmotion.editor.movedTogether
import app.absentmotion.editor.setCrop
import app.absentmotion.model.cropBox
import app.absentmotion.model.cropIndex
import app.absentmotion.model.cropRatio
import app.absentmotion.editor.inverseMap
import app.absentmotion.editor.LayerPoints
import app.absentmotion.editor.projected
import app.absentmotion.editor.unmapVector
import app.absentmotion.model.Pt
import app.absentmotion.platform.Matrix
import app.absentmotion.engine.PreviewView
import app.absentmotion.model.withGradient
import app.absentmotion.model.cameraGesture
import app.absentmotion.model.gradient
import app.absentmotion.model.PropRef
import app.absentmotion.model.textAlign
import app.absentmotion.model.withResizedTextWrap
import app.absentmotion.model.wrapWidth
import app.absentmotion.model.onTextPath
import app.absentmotion.ui.editor.panels.EyedropperLoupe
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.Presence
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
fun Preview(state: EditorState, modifier: Modifier = Modifier, reserveEnd: Dp = 0.dp) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scene = state.scene
    val editingText = state.panel == EditorPanel.EditText
    val mediaRevision by state.engine.mediaRevision.collectAsState()
    val fixedView = state.drawing != null || editingText
    val viewZoom = if (fixedView) 1f else state.canvasZoom
    val viewPan = if (fixedView) Offset.Zero else state.canvasPan
    val free = size.width - with(LocalDensity.current) { reserveEnd.toPx() }.coerceAtMost(size.width / 2f)
    val fit = if (size.width == 0) 1f else min(free / scene.width, size.height.toFloat() / scene.height)
    val scale = fit * viewZoom
    val dx = min((size.width / 2).toFloat(), free - scene.width * fit / 2f) - (scene.width / 2 - viewPan.x) * scale
    val dy = size.height / 2 - (scene.height / 2 - viewPan.y) * scale
    val divisor = state.settings.previewQuality.divisor
    val quality = 1f / divisor
    val selection = if (MultiSelect.ids.isNotEmpty()) MultiSelect.ids.toList() else listOfNotNull(state.selectedId)
    val refPx = if (LocalWide.current) LocalDensity.current.density * PhoneWidth.value / 1080f else 0f

    var gesture by remember { mutableIntStateOf(IDLE) }
    var tapGeneration by remember { mutableIntStateOf(0) }
    val handles = when {
        state.drawing != null -> state.drawing!!.handlesCode
        state.panel == EditorPanel.Crop -> HIDDEN
        gesture != IDLE -> gesture
        state.panel == EditorPanel.MoveTransform && state.keyTarget?.ref == PropRef.Location -> -2
        state.panel == EditorPanel.Effects -> 8
        state.panel != EditorPanel.Tools -> -1
        else -> 8
    }
    val shownId = state.selectedId?.takeIf { handles != HIDDEN && !editingText } ?: 0
    val baseView = if (size.width > 0) PreviewView(
        0, scale * quality, dx * quality, dy * quality, shownId, handles,
        pixels = state.pixelMode, divisor = divisor, gridSpacing = if (state.gridMode) state.gridSpacing.toFloat() else 0f,
        solo = state.soloMode, soloIds = selection, camera = state.cameraMode, guides = state.snapGuides, refPx = refPx * quality,
        accurateBlur = state.settings.fullMotionBlur,
    ) else null
    val cacheMatch by remember(state, mediaRevision) { derivedStateOf { cacheMatches(state, mediaRevision) } }
    val cacheUsable = cacheMatch && app.absentmotion.editor.cacheFrameSize(scene.width, scene.height, scale, divisor).first <= (PreviewCache.player?.width ?: 0) * 1.25f
    val usable = rememberUpdatedState(cacheUsable)
    val cachedPlaying = remember(state) { derivedStateOf { usable.value && state.playing && PreviewCache.covers(state.frame) } }
    val live = remember { LiveHandoff() }
    SideEffect {
        PreviewCache.fit = fit
        PreviewCache.checkerCell = 25f * (if (refPx > 0f) refPx * quality else size.width * quality / 1080f)
    }
    LaunchedEffect(baseView) {
        if (baseView != null) {
            var wasCached = false
            snapshotFlow { state.timeMs to cachedPlaying.value }.collect { (timeMs, cached) ->
                if (!cached) {
                    val serial = state.engine.setView(baseView.copy(timeMs = timeMs))
                    live.requested = serial
                    if (wasCached) live.handoff = serial
                }
                wasCached = cached
            }
        }
    }

    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var plane by remember { mutableStateOf<FloatArray?>(null) }
    var world by remember { mutableStateOf<FloatArray?>(null) }
    var quadFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.selectedId, scene, mediaRevision, state.cameraMode) {
        if (quadFor != state.selectedId) {
            quadFor = state.selectedId
            quad = null
            plane = null
            world = null
        }
        snapshotFlow { state.timeMs.takeUnless { state.playing } }.collectLatest { timeMs ->
            if (timeMs == null) return@collectLatest
            quad = state.selectedId?.let { state.engine.layerQuad(it, timeMs) }
            val matrices = state.selectedId?.let { state.engine.layerMatrix(it, timeMs) }
            plane = matrices?.copyOfRange(9, 18)
            world = matrices?.copyOfRange(0, 9)
        }
    }

    var multiBox by remember { mutableStateOf<FloatArray?>(null) }
    var multiMoving by remember { mutableStateOf(false) }
    val multiIds = MultiSelect.ids
    LaunchedEffect(multiIds, scene, mediaRevision) {
        if (multiIds.isEmpty()) { multiBox = null; return@LaunchedEffect }
        snapshotFlow { state.timeMs.takeUnless { state.playing } }.collectLatest { timeMs ->
            if (timeMs == null) return@collectLatest
            val boxes = multiIds.mapNotNull { state.engine.layerBounds(it, timeMs) }
            multiBox = if (boxes.isEmpty()) null else floatArrayOf(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] })
        }
    }

    val adjust = state.pointAdjust.takeIf { state.panel == EditorPanel.Effects }
    var hint by remember { mutableStateOf(false) }
    var hintLabel by remember { mutableStateOf("") }
    adjust?.let { hintLabel = it.label }
    LaunchedEffect(adjust) {
        hint = adjust != null
        if (adjust != null) { delay(2500); hint = false }
    }

    val scope = rememberCoroutineScope()
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(Offset(dx, dy))

    val view = LocalView.current
    val screenDensity = LocalDensity.current.density
    var origin by remember { mutableStateOf(Offset.Zero) }
    val edgeQuad = quad.takeUnless { state.playing }
    val edgePoints = if (edgeQuad == null || state.selectedId == null) emptyList() else {
        val corners = List(4) { Offset(edgeQuad[2 * it], edgeQuad[2 * it + 1]) * scale + Offset(dx, dy) }
        if (state.panel == EditorPanel.Crop) state.selected?.takeIf { it.cropIndex >= 0 }?.let { cropHandles(corners, it.cropBox()) }.orEmpty() else handlesOf(corners)
    }
    DisposableEffect(view, edgePoints, origin) {
        if (Build.VERSION.SDK_INT >= 29) {
            view.systemGestureExclusionRects = GestureEdges.bands(edgePoints.map { it + origin }, view.width.toFloat(), screenDensity).map { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        }
        onDispose { if (Build.VERSION.SDK_INT >= 29) view.systemGestureExclusionRects = emptyList() }
    }

    var wheelEnd by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val wheelView = if (!app.absentmotion.platform.PlatformFeatures.desktop || fixedView) Modifier else app.absentmotion.platform.previewWheel { delta, at, zoom, alt, shift ->
        if (alt && !zoom && state.selectedId != null && !state.playing) {
            val amount = if (delta.y != 0f) delta.y else delta.x
            if (shift) state.editSelected(key = WHEEL_KEY) { it.withRotation(it.rotation + wheelTurn(amount)) }
            else wheelScaleLayer(state, at, wheelFactor(amount), plane, scale, Offset(dx, dy))
            wheelEnd?.cancel()
            wheelEnd = scope.launch { delay(WHEEL_IDLE_MS); state.endGesture() }
        } else if (zoom || alt) {
            val next = (state.canvasZoom * kotlin.math.exp(-delta.y * 0.1f)).coerceIn(0.01f, 64f)
            val about = (at - Offset(dx, dy)) / scale - Offset(state.scene.width / 2f, state.scene.height / 2f)
            state.canvasPan = pinchedPan(state.canvasPan, about, next / state.canvasZoom)
            state.canvasZoom = next
            state.zoomShown++
        } else {
            state.canvasPan -= delta * (40f / scale)
            state.zoomShown++
        }
    }
    val panScale by rememberUpdatedState(scale)
    val middlePan = if (!app.absentmotion.platform.PlatformFeatures.desktop || fixedView) Modifier else Modifier.pointerInput(state) {
        awaitPointerEventScope {
            var last: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                val from = last
                if (from == null) {
                    if (event.type != PointerEventType.Press || !event.buttons.isTertiaryPressed) continue
                    last = change.position
                    state.zoomShown++
                } else if (event.buttons.isTertiaryPressed) {
                    state.canvasPan += (change.position - from) / panScale
                    last = change.position
                } else last = null
                event.changes.forEach { it.consume() }
            }
        }
    }
    Box(modifier.background(Am.PreviewBg).onSizeChanged { size = it }.onGloballyPositioned { origin = it.positionInRoot() }.then(wheelView).then(middlePan)) {
        app.absentmotion.platform.SceneSurface(state.engine, divisor, size, Modifier.fillMaxSize()) { state.previewSurface = it }
        CachedFrame(state, cacheUsable, live, dx, dy, scene.width * scale, scene.height * scale)
        Box(
            Modifier.fillMaxSize().pointerInput(state) {
                var lastTwoFingerTap = -1_000_000L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val canvasGesture = (state.selectedId == null && MultiSelect.ids.isEmpty()) || state.panZoomMode || state.panel == EditorPanel.Crop
                    val multi = MultiSelect.ids.takeIf { it.isNotEmpty() && state.selectedId == null && !canvasGesture && !state.playing && state.drawing == null }
                    val multiFrom = multi?.let { state.scene }
                    val multiQuad = multi?.let { multiBox }?.let { b -> listOf(Offset(b[0], b[1]), Offset(b[2], b[1]), Offset(b[2], b[3]), Offset(b[0], b[3])).map { it * currentScale + currentOffset } }
                    val multiCorner = multiQuad?.let { HandleHit.at(it, down.position, HandleHit.reach(it, density)) } ?: -1
                    val multiGrab = if (multiCorner >= 0 && multiQuad != null) multiQuad[multiCorner] - down.position else Offset.Zero
                    var multiShift = Offset.Zero
                    var multiZoom = 1f
                    var fingers = 1
                    var drifted = false
                    val starts = HashMap<PointerId, Offset>()
                    val startLayer = state.selected
                    val startQuad = quad?.takeUnless { state.playing }?.let { q -> List(4) { Offset(q[2 * it], q[2 * it + 1]) * currentScale + currentOffset } }
                    val planeView = plane?.let { v -> Matrix().apply { setValues(v) } }
                    val adjusting = state.pointAdjust.takeIf { state.panel == EditorPanel.Effects }
                    val cropFrom = startLayer?.takeIf { state.panel == EditorPanel.Crop && startQuad != null && it.cropIndex >= 0 }?.cropBox()
                    val cropGrab = if (cropFrom != null && startQuad != null) cropGrabAt(startQuad, cropFrom, down.position, density) else -1
                    val cropLock = state.cropAspect?.let { r -> startQuad?.let { q -> cropRatio(r, (q[1] - q[0]).getDistance(), (q[3] - q[0]).getDistance()) } }
                    val turning = state.turner
                    val points = state.layerPoints.takeIf { startLayer != null && !state.playing && !state.panZoomMode }
                    val worldView = world?.takeUnless { state.playing }?.let { v -> Matrix().apply { setValues(v) } }?.takeIf { it.invert(Matrix()) }
                    fun onScreen(p: Pt): Offset? = world?.takeIf { worldView != null }?.projected(p)?.let { Offset(it.x, it.y) * currentScale + currentOffset }
                    fun inLayer(at: Offset): Pt? = worldView?.inverseMap(((at - currentOffset) / currentScale).let { Pt(it.x, it.y) })
                    val gradientMode = startLayer != null && startQuad != null && !state.panZoomMode && state.panel == EditorPanel.ColorFill && startLayer.fillType == "gradient"
                    val pointGrab = if (gradientMode) -1 else points?.points?.indices?.mapNotNull { i -> onScreen(points.points[i])?.let { i to (it - down.position).getDistance() } }
                        ?.filter { it.second < 24.dp.toPx() }?.minByOrNull { it.second }?.first ?: -1
                    val grabOffset = if (pointGrab >= 0) (onScreen(points!!.points[pointGrab]) ?: down.position) - down.position else Offset.Zero
                    if (pointGrab >= 0) points?.pick?.invoke(pointGrab)
                    val handle = if (pointGrab >= 0) -1 else if (startLayer != null && startQuad != null && state.panel == EditorPanel.EditText && !startLayer.onTextPath) {
                        val centre = if (startLayer.textAlign == "right") (startQuad[0] + startQuad[3]) / 2f else (startQuad[1] + startQuad[2]) / 2f
                        if ((centre - down.position).getDistance() < 24.dp.toPx()) 9 else -1
                    } else if (gradientMode) {
                        nearestGradientHandle(startLayer, startQuad, down.position)
                    } else if (startLayer != null && startQuad != null && startLayer.kind != "camera" && (state.panel == EditorPanel.Tools || (state.panel == EditorPanel.Effects && adjusting == null))) {
                        HandleHit.at(handlesOf(startQuad), down.position, HandleHit.reach(startQuad, density))
                    } else {
                        -1
                    }
                    if (handle in 0..9) gesture = handle
                    val handleGrab = if (handle in 0..7 && startQuad != null) handleGrab(handle, startQuad, down.position) else Offset.Zero
                    val grab = if (handle >= 10 && startLayer != null && startQuad != null) startLayer.gradient.let { g ->
                        boxPoint(startQuad, if (handle == 10) g.x0 else g.x1, if (handle == 10) g.y0 else g.y1) - down.position
                    } else Offset.Zero
                    var moved = false
                    var held = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val position = event.changes.first().position
                        fingers = maxOf(fingers, event.changes.size)
                        if (!drifted) drifted = event.changes.any { (it.position - starts.getOrPut(it.id) { it.position }).getDistance() > viewConfiguration.touchSlop }
                        if (!moved) {
                            moved = event.changes.size > 1 || (position - down.position).getDistance() > viewConfiguration.touchSlop
                            if (!moved) held += pan
                        }
                        if (moved && multi != null && multiFrom != null) {
                            multiMoving = true
                            val ms = state.sampleMs
                            if (multiCorner >= 0 && multiQuad != null) {
                                val centre = (multiQuad[0] + multiQuad[2]) / 2f
                                val factor = if (fingers == 1) handleScale(multiCorner, multiQuad, centre, position + multiGrab, floatArrayOf(1f, 1f), 8.dp.toPx())?.get(0) else null
                                if (factor != null) {
                                    val about = (centre - currentOffset) / currentScale
                                    state.edit(MULTI_KEY) { multiFrom.movedTogether(multi, ms, about.x, about.y, factor, 0f, 0f) }
                                }
                            } else {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (centroid.isSpecified && (zoom != 1f || pan + held != Offset.Zero)) {
                                    val about = (centroid - currentOffset) / currentScale
                                    multiShift = about + (multiShift - about) * zoom + (pan + held) / currentScale
                                    held = Offset.Zero
                                    multiZoom *= zoom
                                    state.edit(MULTI_KEY) { multiFrom.movedTogether(multi, ms, 0f, 0f, multiZoom, multiShift.x, multiShift.y) }
                                }
                            }
                        } else if (moved && pointGrab >= 0 && points != null && fingers == 1) {
                            gesture = HIDDEN
                            inLayer(position + grabOffset)?.let { points.move(pointGrab, it) }
                        } else if (moved && startLayer != null && handle >= 10 && startQuad != null) {
                            if (fingers == 1) {
                                val (u, v) = boxFraction(startQuad, position + grab)
                                state.editSelected("gradient-handle") { l ->
                                    val g = l.gradient
                                    l.withGradient(if (handle == 10) g.copy(x0 = u, y0 = v) else g.copy(x1 = u, y1 = v))
                                }
                            }
                        } else if (moved && startLayer != null && handle == 9 && startQuad != null) {
                            val axis = startQuad[1] - startQuad[0]
                            val drag = position - down.position
                            val along = (drag.x * axis.x + drag.y * axis.y) / axis.getDistance().coerceAtLeast(1e-3f)
                            val localX = along / currentScale * (planeView?.unmapVector(Pt(1f, 0f))?.length ?: 1f) / abs(state.scene.place(startLayer, state.sampleMs).sx).coerceAtLeast(1e-3f)
                            val factor = when (startLayer.textAlign) { "center" -> 2f; "right" -> -1f; else -> 1f }
                            val width = (startLayer.wrapWidth + localX * factor).roundToInt().coerceAtLeast(50)
                            state.editSelected("text-wrap") { startLayer.withResizedTextWrap(width) }
                        } else if (moved && startLayer != null && handle >= 0 && startQuad != null) {
                            val pivot = startLayer.pivot.let { onScreen(Pt(it[0], it[1])) } ?: ((startQuad[0] + startQuad[2]) / 2f)
                            if (app.absentmotion.platform.PlatformFeatures.desktop && event.keyboardModifiers.isAltPressed) {
                                val turn = handleTurn(down.position - pivot, position - pivot)
                                state.editSelected(key = "preview-handle") { it.withRotation(startLayer.rotation + turn) }
                                continue
                            }
                            if (startLayer.kind == "embedScene") {
                                val scaled = handleScale(handle, startQuad, (startQuad[0] + startQuad[2]) / 2f, position + handleGrab, startLayer.scale, 8.dp.toPx()) ?: continue
                                val shift = (centreKept(startQuad, pivot, startLayer.scale, scaled) / currentScale).let { Pt(it.x, it.y) }.let { planeView?.unmapVector(it) ?: it }
                                state.editSelected(key = "preview-handle") {
                                    state.scene.withWorldLocation(startLayer, state.sampleMs) { x, y -> x + shift.x to y + shift.y }.withTransform("scale", scaled[0], scaled[1])
                                }
                                continue
                            }
                            val scaled = handleScale(handle, startQuad, pivot, position + handleGrab, startLayer.scale, 8.dp.toPx()) ?: continue
                            state.editSelected(key = "preview-handle") { it.withTransform("scale", scaled[0], scaled[1]) }
                        } else if (moved && adjusting != null) {
                            hint = false
                            val move = (pan + held) / currentScale
                            held = Offset.Zero
                            if (move != Offset.Zero) adjusting.move(move.x, move.y)
                        } else if (moved && cropFrom != null && startQuad != null && cropGrab >= 0 && fingers == 1) {
                            val d = boxFraction(startQuad, position) - boxFraction(startQuad, down.position)
                            state.setCrop(if (cropGrab == CROP_INSIDE) cropFrom.moved(d.x, d.y) else cropFrom.dragged(cropGrab, d.x, d.y, cropLock), "crop-drag")
                        } else if (moved && canvasGesture && (event.changes.size > 1 || app.absentmotion.platform.PlatformFeatures.desktop)) {
                            val move = (pan + held) / currentScale
                            held = Offset.Zero
                            val next = (state.canvasZoom * zoom).coerceIn(0.01f, 64f)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val about = if (centroid.isSpecified) (centroid - currentOffset) / currentScale - Offset(state.scene.width / 2f, state.scene.height / 2f) else Offset.Zero
                            state.canvasPan = pinchedPan(state.canvasPan + move, about, next / state.canvasZoom)
                            state.canvasZoom = next
                            state.zoomShown++
                        } else if (moved && state.panel == EditorPanel.Crop) {
                            held = Offset.Zero
                        } else if (moved && startLayer?.kind == "camera" && !(canvasGesture && fingers > 1)) {
                            gesture = HIDDEN
                            val move = (pan + held) / currentScale
                            held = Offset.Zero
                            state.editSelected(key = "preview-gesture") { it.cameraGesture(move.x, move.y, zoom) }
                        } else if (moved && turning != null && !(canvasGesture && fingers > 1)) {
                            gesture = HIDDEN
                            turning(if (fingers > 1) Offset.Zero else (pan + held) / density, if (fingers > 1) event.calculateRotation() else 0f)
                            held = Offset.Zero
                        } else if (moved && state.selectedId != null && !(canvasGesture && fingers > 1)) {
                            gesture = HIDDEN
                            val s = currentScale
                            val move = ((pan + held) / s).let { Pt(it.x, it.y) }.let { planeView?.unmapVector(it) ?: it }
                            held = Offset.Zero
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val about = if (centroid.isSpecified) ((centroid - currentOffset) / s).let { Pt(it.x, it.y) }.let { planeView?.inverseMap(it) ?: it } else null
                            state.editSelected(key = "preview-gesture") { layer ->
                                if (zoom == 1f || about == null) {
                                    state.scene.withWorldLocation(layer, state.sampleMs) { x, y -> x + move.x to y + move.y }
                                } else {
                                    state.scene.withWorldLocation(layer, state.sampleMs) { x, y -> about.x + (x - about.x) * zoom + move.x to about.y + (y - about.y) * zoom + move.y }
                                        .withTransform("scale", layer.scale[0] * zoom, layer.scale[1] * zoom)
                                }
                            }
                        }
                        if (moved) event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })
                    gesture = IDLE
                    multiMoving = false
                    val farFromDots = gradientMode && gradientHandleDistance(startLayer, startQuad, down.position) > 24.dp.toPx()
                    if (canvasGesture && fingers == 2 && !drifted) {
                        if (down.uptimeMillis - lastTwoFingerTap < viewConfiguration.doubleTapTimeoutMillis) {
                            state.resetCanvasView()
                            lastTwoFingerTap = -1_000_000L
                        } else if (SystemClock.uptimeMillis() - down.uptimeMillis < ViewConfiguration.getTapTimeout()) {
                            lastTwoFingerTap = SystemClock.uptimeMillis()
                        }
                    }
                    val tapped = if (!moved && pointGrab < 0 && fingers == 1 && points?.tap != null && startQuad != null) {
                        inLayer(down.position)?.takeIf { v -> points.source(v).let(::onScreen)?.let { inside(startQuad, it) } == true }
                    } else null
                    if (moved) {
                        state.endGesture()
                    } else if (pointGrab >= 0) {
                        state.endGesture()
                    } else if (tapped != null) {
                        points?.tap?.invoke(tapped)
                    } else if ((handle < 0 || farFromDots) && state.panel != EditorPanel.EditText && state.panel != EditorPanel.Crop) {
                        val point = (down.position - currentOffset) / currentScale
                        val generation = ++tapGeneration
                        scope.launch {
                            val hit = state.engine.hitTest(point.x, point.y, state.timeMs)
                            if (generation != tapGeneration) return@launch
                            if (hit == 0L) {
                                state.clearSelection()
                            } else if (MultiSelect.active) {
                                MultiSelect.toggle(hit)
                            } else {
                                state.selectedId = hit.takeIf { it != 0L }
                                state.panel = EditorPanel.Tools
                            }
                        }
                    }
                }
            },
        )
        if (multiIds.isNotEmpty() && state.selectedId == null && !multiMoving && !state.playing && state.drawing == null) multiBox?.let { b ->
            MultiSelectionBox(b, scale, Offset(dx, dy), if (refPx > 0f) refPx else size.width / 1080f)
        }
        Presence(hint) { SwipeHint(hintLabel, Modifier.align(Alignment.Center).presence()) }
        if (!fixedView) CanvasZoomHud(state, Modifier.align(Alignment.TopStart))
        if (state.drawing == null && !editingText) {
            RenderPreviewNotes(state, cachedPlaying, Modifier.align(Alignment.TopCenter))
            PreRenderBadge(cachedPlaying, Modifier.align(Alignment.TopEnd))
        }
        Presence(state.cameraTip && !LocalWide.current) { CameraTip(state, Modifier.align(Alignment.BottomEnd).presence()) }
        EyedropperLoupe(state, Modifier.fillMaxSize())
        GradientHandles(state, quad.takeUnless { state.playing }, scale, Offset(dx, dy))
        CropOverlay(state, quad.takeUnless { state.playing }, scale, Offset(dx, dy))
        state.layerPoints?.let { points -> world?.takeUnless { state.playing }?.let { LayerPointsOverlay(points, it, scale, Offset(dx, dy)) } }
        if (editingText) Canvas(Modifier.fillMaxSize()) {
            val q = quad.takeUnless { state.playing } ?: return@Canvas
            val points = List(4) { Offset(q[2 * it] * scale + dx, q[2 * it + 1] * scale + dy) }
            for (i in 0..3) drawLine(Am.Accent, points[i], points[(i + 1) % 4], 1.dp.toPx())
            if (state.selected?.onTextPath == true) return@Canvas
            val handle = if (state.selected?.textAlign == "right") (points[0] + points[3]) / 2f else (points[1] + points[2]) / 2f
            drawCircle(Am.AccentDeep, 10.dp.toPx(), handle)
            drawCircle(Am.Accent, 9.dp.toPx(), handle)
        }
    }
}

@Composable
private fun CanvasZoomHud(state: EditorState, modifier: Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(state.zoomShown) {
        if (state.zoomShown == 0) return@LaunchedEffect
        shown.snapTo(1f)
        delay(100)
        shown.animateTo(0f, tween(2000))
    }
    val selected = state.selectedId != null || MultiSelect.ids.isNotEmpty()
    Column(modifier.padding(start = 10.dp, top = 10.dp).width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp).alpha(if (selected) 1f else shown.value)
                .then(if (selected) Modifier.clickable { state.panZoomMode = !state.panZoomMode } else Modifier),
            Alignment.Center,
        ) { CanvasZoomIcon(on = state.panZoomMode, guide = !selected) }
        Text(
            "${(state.canvasZoom * 100).toInt()}%", Modifier.alpha(shown.value), color = Am.Caption.copy(alpha = 0.6f), fontSize = 12.sp,
            fontWeight = FontWeight.Bold, maxLines = 1,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 3f)),
        )
    }
}

@Composable
private fun SwipeHint(label: String, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(175.dp, 110.dp).clip(shape).background(Color(0xB3222222)).border(0.7.dp, Am.PreviewBg.copy(alpha = 0.8f), shape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val length = 21.dp.toPx()
            val head = 6.dp.toPx()
            val width = 1.2.dp.toPx()
            val tips = listOf(
                Offset(0f, -1f) to Offset(size.width / 2, 10.dp.toPx()), Offset(0f, 1f) to Offset(size.width / 2, size.height - 10.dp.toPx()),
                Offset(-1f, 0f) to Offset(15.dp.toPx(), size.height / 2), Offset(1f, 0f) to Offset(size.width - 15.dp.toPx(), size.height / 2),
            )
            for ((dir, tip) in tips) {
                val side = Offset(dir.y, dir.x)
                drawLine(Color.White, tip, tip - dir * length, width, StrokeCap.Round)
                drawLine(Color.White, tip, tip - dir * head + side * head, width, StrokeCap.Round)
                drawLine(Color.White, tip, tip - dir * head - side * head, width, StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.drag_to_adjust), color = Color.White, fontSize = deskSp(11f), lineHeight = 14.sp, maxLines = 1)
            Text(label, color = Am.Accent, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private const val IDLE = -10
private const val HIDDEN = -11

private fun handlesOf(corners: List<Offset>): List<Offset> = corners + List(4) { (corners[it] + corners[(it + 1) % 4]) / 2f }

internal fun handleGrab(handle: Int, corners: List<Offset>, down: Offset): Offset = handlesOf(corners)[handle] - down

internal fun handleScale(handle: Int, corners: List<Offset>, pivot: Offset, now: Offset, start: FloatArray, reach: Float): FloatArray? {
    val grabbed = handlesOf(corners)[handle] - pivot
    if (handle < 4) {
        if (grabbed.getDistance() < reach) return null
        val factor = ((now - pivot).getDistance() / grabbed.getDistance()).coerceAtLeast(0.01f)
        return floatArrayOf(start[0] * factor, start[1] * factor)
    }
    val axis = handlesOf(corners)[handle] - (corners[0] + corners[2]) / 2f
    val length = axis.getDistance()
    if (length < 1e-3f) return null
    val along = (grabbed.x * axis.x + grabbed.y * axis.y) / length
    if (abs(along) < reach) return null
    val factor = (((now - pivot).x * axis.x + (now - pivot).y * axis.y) / length / along).coerceAtLeast(0.01f)
    return if (handle % 2 == 0) floatArrayOf(start[0], start[1] * factor) else floatArrayOf(start[0] * factor, start[1])
}

internal fun centreKept(corners: List<Offset>, pivot: Offset, start: FloatArray, scaled: FloatArray): Offset {
    val centre = (corners[0] + corners[2]) / 2f
    val handles = handlesOf(corners)
    val ex = handles[5] - centre
    val ey = handles[6] - centre
    val det = ex.x * ey.y - ex.y * ey.x
    if (abs(det) < 1e-6f) return Offset.Zero
    val d = centre - pivot
    val u = (d.x * ey.y - d.y * ey.x) / det
    val v = (ex.x * d.y - ex.y * d.x) / det
    val kx = if (start[0] != 0f) scaled[0] / start[0] else 1f
    val ky = if (start[1] != 0f) scaled[1] / start[1] else 1f
    return d - ex * (u * kx) - ey * (v * ky)
}

internal const val MULTI_KEY = "preview-multi"

@Composable
private fun MultiSelectionBox(box: FloatArray, scale: Float, offset: Offset, px: Float) {
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val corners = listOf(Offset(box[0], box[1]), Offset(box[2], box[1]), Offset(box[2], box[3]), Offset(box[0], box[3])).map { it * scale + offset }
        val outline = Path().apply { moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(outline, Color.Black, style = Stroke(7.3f * px))
        drawPath(outline, Color.White, style = Stroke(5.3f * px))
        for (corner in corners) {
            drawCircle(Color.Black, 15.5f * px, corner)
            drawCircle(Color.White, 12.5f * px, corner)
        }
    }
}

internal const val WHEEL_KEY = "preview-wheel"
internal const val WHEEL_IDLE_MS = 500L

internal fun wheelFactor(amount: Float): Float = kotlin.math.exp(-amount * 0.06f).coerceIn(0.5f, 2f)

internal fun wheelTurn(amount: Float): Float = (-amount * 5f).coerceIn(-45f, 45f)

internal fun handleTurn(from: Offset, to: Offset): Float {
    if (from.getDistance() < 1f || to.getDistance() < 1f) return 0f
    var turn = Math.toDegrees((kotlin.math.atan2(-to.y, to.x) - kotlin.math.atan2(-from.y, from.x)).toDouble()).toFloat()
    if (turn > 180f) turn -= 360f else if (turn < -180f) turn += 360f
    return turn
}

private fun wheelScaleLayer(state: EditorState, at: Offset, factor: Float, plane: FloatArray?, scale: Float, offset: Offset) {
    val planeView = plane?.let { v -> Matrix().apply { setValues(v) } }
    val about = ((at - offset) / scale).let { Pt(it.x, it.y) }.let { planeView?.inverseMap(it) ?: it }
    state.editSelected(key = WHEEL_KEY) { layer ->
        state.scene.withWorldLocation(layer, state.sampleMs) { x, y -> about.x + (x - about.x) * factor to about.y + (y - about.y) * factor }
            .withTransform("scale", layer.scale[0] * factor, layer.scale[1] * factor)
    }
}

internal fun pinchedPan(pan: Offset, about: Offset, factor: Float): Offset = pan / factor + about * (1 / factor - 1)

private fun inside(quad: List<Offset>, at: Offset): Boolean {
    var sign = 0
    for (i in 0..3) {
        val a = quad[i]
        val b = quad[(i + 1) % 4]
        val cross = (b.x - a.x) * (at.y - a.y) - (b.y - a.y) * (at.x - a.x)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
        if (s != 0) { if (sign != 0 && s != sign) return false; sign = s }
    }
    return true
}

@Composable
private fun LayerPointsOverlay(points: LayerPoints, world: FloatArray, scale: Float, offset: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        val shown = points.points.map { p -> world.projected(p)?.let { Offset(it.x, it.y) * scale + offset } }
        val line = 1.2.dp.toPx()
        val guide = points.guide.map { p -> world.projected(p)?.let { Offset(it.x, it.y) * scale + offset } }
        for (i in 1 until guide.size) {
            val from = guide[i - 1] ?: continue
            val to = guide[i] ?: continue
            drawLine(Color.Black.copy(alpha = 0.35f), from, to, line * 2.5f)
            drawLine(Color.White.copy(alpha = 0.8f), from, to, line)
        }
        for ((a, b) in points.lines) {
            val from = shown.getOrNull(a) ?: continue
            val to = shown.getOrNull(b) ?: continue
            drawLine(Color.Black.copy(alpha = 0.5f), from, to, line * 2.5f)
            drawLine(Am.Accent, from, to, line)
        }
        shown.forEachIndexed { i, at ->
            if (at == null) return@forEachIndexed
            val chosen = i == points.selected
            if (points.pivot) {
                val r = 9.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.5f), r + line, at, style = androidx.compose.ui.graphics.drawscope.Stroke(line * 3))
                drawCircle(Color.White, r, at, style = androidx.compose.ui.graphics.drawscope.Stroke(line * 1.5f))
                for (d in listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f))) drawLine(Color.White, at + d * (r * 0.45f), at + d * (r * 1.6f), line * 1.5f)
                drawCircle(Am.Accent, 2.5.dp.toPx(), at)
            } else {
                val r = (if (chosen) 8 else 6).dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.55f), r + 1.5.dp.toPx(), at)
                drawCircle(if (chosen) Am.Accent else Am.Gold, r, at)
                drawCircle(Color.White, r * 0.35f, at)
            }
        }
    }
}
