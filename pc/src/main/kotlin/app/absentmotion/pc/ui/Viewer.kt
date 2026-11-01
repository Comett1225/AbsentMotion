package app.absentmotion.pc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import kotlin.math.atan2
import kotlin.math.hypot

internal fun viewerScale(mode: String, width: Float, height: Float, scene: Scene): Float =
    (if (mode == "Fit") minOf(width / scene.width.coerceAtLeast(1), height / scene.height.coerceAtLeast(1)) else mode.removeSuffix("%").toFloatOrNull()?.div(100f) ?: 1f).coerceAtLeast(0.001f)

internal fun boxPoint(q: List<Offset>, u: Float, v: Float) = q[0] + (q[1] - q[0]) * u + (q[3] - q[0]) * v
internal fun boxFraction(q: List<Offset>, p: Offset): Offset {
    val x = q[1] - q[0]
    val y = q[3] - q[0]
    val d = p - q[0]
    val det = x.x * y.y - x.y * y.x
    if (kotlin.math.abs(det) < 0.0001f) return Offset.Zero
    return Offset((d.x * y.y - d.y * y.x) / det, (x.x * d.y - x.y * d.x) / det)
}

private fun rotationPoint(q: List<Offset>): Offset {
    val middle = (q[0] + q[1]) / 2f
    val out = middle - (q[2] + q[3]) / 2f
    return middle + out / out.getDistance().coerceAtLeast(0.001f) * 32f
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Viewer(state: ProjectState, image: ImageBitmap?, outline: FloatArray?, mode: String, setMode: (String) -> Unit,
           space: Boolean, onPan: () -> Unit, onSize: (IntSize) -> Unit, hitTest: (Float, Float) -> Unit) {
    val scene = state.scene
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentOutline by rememberUpdatedState(outline)
    val currentMode by rememberUpdatedState(mode)
    val currentSpace by rememberUpdatedState(space)
    val currentScene by rememberUpdatedState(scene)
    val currentPan by rememberUpdatedState(pan)
    val currentOnPan by rememberUpdatedState(onPan)
    val currentSetMode by rememberUpdatedState(setMode)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(mode) { if (mode == "Fit") pan = Offset.Zero }
    fun zoom(factor: Float) {
        val scale = viewerScale(currentMode, viewport.width.toFloat(), viewport.height.toFloat(), currentScene)
        currentSetMode("${(scale * factor * 100f).coerceIn(5f, 800f)}%")
    }
    Box(Modifier.fillMaxSize().clipToBounds().testTag("viewer").onSizeChanged { viewport = it; onSize(it) }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            if (event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed) zoom(if (change.scrollDelta.y < 0) 1.1f else 1f / 1.1f)
            else pan -= change.scrollDelta * 28f
            change.consume()
        }
        .pointerInput(state.selected, state.time) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startScene = currentScene
                val fit = viewerScale(currentMode, size.width.toFloat(), size.height.toFloat(), startScene)
                val origin = Offset((size.width - startScene.width * fit) / 2f, (size.height - startScene.height * fit) / 2f) + currentPan
                val quad = currentOutline?.takeIf { it.size >= 8 }?.let { a -> List(4) { i -> origin + Offset(a[i * 2], a[i * 2 + 1]) * fit } }
                val layer = startScene.layerAt(state.selected, state.time)
                val gradient = layer?.takeIf { it.fillType == "gradient" }?.gradient
                val g0 = if (gradient != null && quad != null) boxPoint(quad, gradient.x0, gradient.y0) else Offset.Unspecified
                val g1 = if (gradient != null && quad != null) boxPoint(quad, gradient.x1, gradient.y1) else Offset.Unspecified
                val corner = quad?.indices?.minByOrNull { (quad[it] - down.position).getDistance() } ?: -1
                val fraction = quad?.let { boxFraction(it, down.position) }
                val grab = when {
                    currentSpace -> "pan"
                    quad == null || layer == null -> "none"
                    gradient != null && minOf((g0 - down.position).getDistance(), (g1 - down.position).getDistance()) < 20f -> if ((g0 - down.position).getDistance() <= (g1 - down.position).getDistance()) "g0" else "g1"
                    (rotationPoint(quad) - down.position).getDistance() < 18f -> "rotate"
                    corner >= 0 && (quad[corner] - down.position).getDistance() < 18f -> "scale"
                    fraction != null && fraction.x in 0f..1f && fraction.y in 0f..1f -> "move"
                    else -> "none"
                }
                var moved = false
                var editing = false
                var multi = false
                var previous = down.position
                val center = if (quad != null) (quad[0] + quad[2]) / 2f else Offset.Zero
                val startVector = down.position - center
                val startAngle = atan2(startVector.y, startVector.x)
                val initialScale = layer?.scale
                val initialLocation = layer?.location
                val initialRotation = layer?.rotation ?: 0f
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    if (pressed > 1) {
                        if (editing) { state.cancelGesture(); editing = false }
                        multi = true
                        val factor = event.calculateZoom()
                        if (factor.isFinite() && factor != 1f) zoom(factor)
                        pan += event.calculatePan()
                        event.changes.forEach { it.consume() }
                    } else if (!multi && change.pressed) {
                        val delta = change.position - down.position
                        if (!moved && delta.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved && grab != "none") {
                            if (grab == "pan") { pan += change.position - previous; currentOnPan() }
                            else if (quad != null && layer != null) {
                                if (!editing) { state.beginGesture(); editing = true }
                                val at = change.position
                                state.gesture { start -> start.update(state.selected, state.time) { l ->
                                    when (grab) {
                                        "move" -> l.withTransform("location", initialLocation!![0] + delta.x / fit, initialLocation[1] + delta.y / fit, initialLocation.getOrElse(2) { 0f })
                                        "rotate" -> l.withRotation(initialRotation + Math.toDegrees((atan2(at.y - center.y, at.x - center.x) - startAngle).toDouble()).toFloat())
                                        "scale" -> {
                                            val a = boxFraction(quad, down.position) - Offset(0.5f, 0.5f)
                                            val b = boxFraction(quad, at) - Offset(0.5f, 0.5f)
                                            var sx = if (kotlin.math.abs(a.x) > 0.0001f) b.x / a.x else 1f
                                            var sy = if (kotlin.math.abs(a.y) > 0.0001f) b.y / a.y else 1f
                                            if (event.keyboardModifiers.isShiftPressed) { val f = hypot(b.x, b.y) / hypot(a.x, a.y).coerceAtLeast(0.0001f); sx = f; sy = f }
                                            l.withTransform("scale", initialScale!![0] * sx.coerceIn(-100f, 100f), initialScale[1] * sy.coerceIn(-100f, 100f))
                                        }
                                        "g0", "g1" -> {
                                            val p = boxFraction(quad, at + (if (grab == "g0") g0 else g1) - down.position)
                                            l.withGradient(if (grab == "g0") gradient!!.copy(x0 = p.x, y0 = p.y) else gradient!!.copy(x1 = p.x, y1 = p.y))
                                        }
                                        else -> l
                                    }
                                } }
                            }
                            change.consume()
                        }
                    }
                    previous = change.position
                } while (event.changes.any { it.pressed })
                if (editing) state.endGesture()
                if (!moved && !multi && !currentSpace) {
                    val p = (down.position - origin) / fit
                    hitTest(p.x, p.y)
                }
            }
        }, contentAlignment = Alignment.Center) {
        val fit = viewerScale(mode, viewport.width.toFloat(), viewport.height.toFloat(), scene)
        val base = viewerScale("Fit", viewport.width.toFloat(), viewport.height.toFloat(), scene)
        image?.let { bitmap -> Image(bitmap, null, Modifier.fillMaxSize().graphicsLayer { scaleX = fit / base; scaleY = fit / base; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit) }
        Canvas(Modifier.fillMaxSize()) {
            val origin = Offset((size.width - scene.width * fit) / 2f, (size.height - scene.height * fit) / 2f) + pan
            drawRect(Color.LightGray, origin, Size(scene.width * fit, scene.height * fit), style = Stroke(1f))
            outline?.takeIf { it.size >= 8 }?.let { a ->
                val q = List(4) { i -> origin + Offset(a[i * 2], a[i * 2 + 1]) * fit }
                val path = Path().apply { moveTo(q[0].x, q[0].y); for (i in 1..3) lineTo(q[i].x, q[i].y); close() }
                drawPath(path, accent, style = Stroke(2f))
                q.forEach { drawCircle(Color.White, 6f, it); drawCircle(accent, 4f, it) }
                val rotate = rotationPoint(q)
                drawLine(accent, (q[0] + q[1]) / 2f, rotate, 2f)
                drawCircle(Color.White, 7f, rotate); drawCircle(accent, 5f, rotate)
                state.scene.layerAt(state.selected, state.time)?.takeIf { it.fillType == "gradient" }?.gradient?.let { g ->
                    val a0 = boxPoint(q, g.x0, g.y0); val a1 = boxPoint(q, g.x1, g.y1)
                    drawLine(Color.White, a0, a1, 2f)
                    drawCircle(Color.White, 8f, a0); drawCircle(Color(g.startColor), 6f, a0)
                    drawCircle(Color.White, 8f, a1); drawCircle(Color(g.endColor), 6f, a1)
                }
            }
        }
    }
}
