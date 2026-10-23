package app.absentmotion.ui.editor

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
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
import app.absentmotion.engine.PreviewView
import app.absentmotion.model.withGradient
import app.absentmotion.model.cameraGesture
import app.absentmotion.model.gradient
import app.absentmotion.model.PropRef
import app.absentmotion.model.textAlign
import app.absentmotion.model.withResizedTextWrap
import app.absentmotion.model.wrapWidth
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
    val handles = when {
        state.drawing != null -> state.drawing!!.handlesCode
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
    LaunchedEffect(baseView) {
        if (baseView != null) snapshotFlow { state.timeMs }.collect { state.engine.setView(baseView.copy(timeMs = it)) }
    }

    var quad by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(state.selectedId, scene, mediaRevision, state.cameraMode) {
        snapshotFlow { state.timeMs }.collectLatest { timeMs -> quad = state.selectedId?.let { state.engine.layerQuad(it, timeMs) } }
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

    Box(modifier.background(Am.PreviewBg).onSizeChanged { size = it }) {
        app.absentmotion.platform.SceneSurface(state.engine, divisor, size, Modifier.fillMaxSize()) { state.previewSurface = it }
        Box(
            Modifier.fillMaxSize().pointerInput(state) {
                var lastTwoFingerTap = -1_000_000L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val canvasGesture = (state.selectedId == null && MultiSelect.ids.isEmpty()) || state.panZoomMode
                    var fingers = 1
                    var drifted = false
                    val starts = HashMap<PointerId, Offset>()
                    val startLayer = state.selected
                    val startQuad = quad?.let { q -> List(4) { Offset(q[2 * it], q[2 * it + 1]) * currentScale + currentOffset } }
                    val adjusting = state.pointAdjust.takeIf { state.panel == EditorPanel.Effects }
                    val turning = state.turner
                    val handle = if (startLayer != null && startQuad != null && state.panel == EditorPanel.EditText) {
                        val centre = if (startLayer.textAlign == "right") (startQuad[0] + startQuad[3]) / 2f else (startQuad[1] + startQuad[2]) / 2f
                        if ((centre - down.position).getDistance() < 24.dp.toPx()) 9 else -1
                    } else if (startLayer != null && startQuad != null && state.panel == EditorPanel.ColorFill && startLayer.fillType == "gradient") {
                        gradientHandleAt(startLayer, startQuad, down.position, 24.dp.toPx())
                    } else if (startLayer != null && startQuad != null && startLayer.kind != "camera" && (state.panel == EditorPanel.Tools || (state.panel == EditorPanel.Effects && adjusting == null))) {
                        handlesOf(startQuad).indexOfFirst { (it - down.position).getDistance() < 20.dp.toPx() }
                    } else {
                        -1
                    }
                    if (handle in 0..9) gesture = handle
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
                        if (moved && startLayer != null && handle >= 10 && startQuad != null) {
                            val (u, v) = boxFraction(startQuad, position + grab)
                            state.editSelected("gradient-handle") { l ->
                                val g = l.gradient
                                l.withGradient(if (handle == 10) g.copy(x0 = u, y0 = v) else g.copy(x1 = u, y1 = v))
                            }
                        } else if (moved && startLayer != null && handle == 9 && startQuad != null) {
                            val axis = startQuad[1] - startQuad[0]
                            val drag = position - down.position
                            val along = (drag.x * axis.x + drag.y * axis.y) / axis.getDistance().coerceAtLeast(1e-3f)
                            val localX = along / currentScale / abs(state.scene.place(startLayer, state.sampleMs).sx).coerceAtLeast(1e-3f)
                            val factor = when (startLayer.textAlign) { "center" -> 2f; "right" -> -1f; else -> 1f }
                            val width = (startLayer.wrapWidth + localX * factor).roundToInt().coerceAtLeast(50)
                            state.editSelected("text-wrap") { startLayer.withResizedTextWrap(width) }
                        } else if (moved && startLayer != null && handle >= 0 && startQuad != null) {
                            val centre = (startQuad[0] + startQuad[2]) / 2f
                            val grabbed = handlesOf(startQuad)[handle] - centre
                            val now = position - centre
                            val start = startLayer.scale
                            if (grabbed.getDistanceSquared() < 1e-4f) continue
                            state.editSelected(key = "preview-handle") { layer ->
                                if (handle < 4) {
                                    val factor = (now.getDistance() / grabbed.getDistance()).coerceAtLeast(0.01f)
                                    layer.withTransform("scale", start[0] * factor, start[1] * factor)
                                } else {
                                    val factor = ((now.x * grabbed.x + now.y * grabbed.y) / grabbed.getDistanceSquared()).coerceAtLeast(0.01f)
                                    if (handle % 2 == 0) layer.withTransform("scale", start[0], start[1] * factor)
                                    else layer.withTransform("scale", start[0] * factor, start[1])
                                }
                            }
                        } else if (moved && adjusting != null) {
                            hint = false
                            val move = (pan + held) / currentScale
                            held = Offset.Zero
                            if (move != Offset.Zero) adjusting.move(move.x, move.y)
                        } else if (moved && canvasGesture && event.changes.size > 1) {
                            val move = (pan + held) / currentScale
                            held = Offset.Zero
                            val next = (state.canvasZoom * zoom).coerceIn(0.01f, 64f)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val about = if (centroid.isSpecified) (centroid - currentOffset) / currentScale - Offset(state.scene.width / 2f, state.scene.height / 2f) else Offset.Zero
                            state.canvasPan = pinchedPan(state.canvasPan + move, about, next / state.canvasZoom)
                            state.canvasZoom = next
                            state.zoomShown++
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
                            val move = (pan + held) / s
                            held = Offset.Zero
                            val about = (event.calculateCentroid(useCurrent = false) - currentOffset) / s
                            state.editSelected(key = "preview-gesture") { layer ->
                                if (zoom == 1f || !about.isSpecified) {
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
                    if (canvasGesture && fingers == 2 && !drifted) {
                        if (down.uptimeMillis - lastTwoFingerTap < viewConfiguration.doubleTapTimeoutMillis) {
                            state.resetCanvasView()
                            lastTwoFingerTap = -1_000_000L
                        } else if (SystemClock.uptimeMillis() - down.uptimeMillis < ViewConfiguration.getTapTimeout()) {
                            lastTwoFingerTap = SystemClock.uptimeMillis()
                        }
                    }
                    if (moved) {
                        state.endGesture()
                    } else if (handle < 0 && state.panel != EditorPanel.EditText) {
                        val point = (down.position - currentOffset) / currentScale
                        scope.launch {
                            val hit = state.engine.hitTest(point.x, point.y, state.timeMs)
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
        Presence(hint) { SwipeHint(hintLabel, Modifier.align(Alignment.Center).presence()) }
        CanvasZoomHud(state, Modifier.align(Alignment.TopStart))
        Presence(state.cameraTip && !LocalWide.current) { CameraTip(state, Modifier.align(Alignment.BottomEnd).presence()) }
        EyedropperLoupe(state, Modifier.fillMaxSize())
        GradientHandles(state, quad, scale, Offset(dx, dy))
        if (editingText) Canvas(Modifier.fillMaxSize()) {
            val q = quad ?: return@Canvas
            val points = List(4) { Offset(q[2 * it] * scale + dx, q[2 * it + 1] * scale + dy) }
            for (i in 0..3) drawLine(Am.Accent, points[i], points[(i + 1) % 4], 1.dp.toPx())
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
            Text(stringResource(R.string.drag_to_adjust), color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
            Text(label, color = Am.Accent, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private const val IDLE = -10
private const val HIDDEN = -11

private fun handlesOf(corners: List<Offset>): List<Offset> = corners + List(4) { (corners[it] + corners[(it + 1) % 4]) / 2f }

internal fun pinchedPan(pan: Offset, about: Offset, factor: Float): Offset = pan / factor + about * (1 / factor - 1)
