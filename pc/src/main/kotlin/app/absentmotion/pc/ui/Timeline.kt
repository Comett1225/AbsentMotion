package app.absentmotion.pc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import kotlin.math.roundToInt

@Composable
fun Timeline(state: ProjectState, zoom: Float, setZoom: (Float) -> Unit, status: String) {
    val scene = state.scene
    val width = (800f * zoom).coerceIn(400f, 12000f)
    val verticalScroll = rememberScrollState()
    val rulerFont = remember { Font(null, 11f) }
    val rulerPaint = remember { Paint().apply { color = 0xffa9abb2.toInt() } }
    Column(Modifier.fillMaxSize().background(timeline)) {
        Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TIMELINE   ${formatTime(state.time, scene.fps)}    $status", Modifier.padding(10.dp), color = muted, fontSize = 11.sp)
            Row { Text("−", Modifier.clickable { setZoom((zoom / 1.5f).coerceAtLeast(0.5f)) }.padding(horizontal = 10.dp), color = Color.White); Text("+", Modifier.clickable { setZoom((zoom * 1.5f).coerceAtMost(15f)) }.padding(horizontal = 10.dp), color = Color.White) }
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(185.dp).verticalScroll(verticalScroll)) {
                Text("LAYERS", Modifier.height(28.dp).padding(start = 10.dp, top = 6.dp), color = muted, fontSize = 10.sp)
                scene.layers.asReversed().forEach { layer ->
                    Row(Modifier.height(32.dp).fillMaxWidth().background(if (state.selected == layer.id) Color(0xff373a44) else timeline)) {
                        Text(if (layer.hidden) "○" else "●", Modifier.clickable { state.edit { it.update(layer.id) { l -> l.withHidden(!l.hidden) } } }.padding(8.dp), color = muted, fontSize = 11.sp)
                        Text(layer.label.ifBlank { layer.kind }, Modifier.weight(1f).clickable { state.selected = layer.id }.padding(top = 8.dp), color = if (state.selected == layer.id) accent else Color.White, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(verticalScroll)) {
                Canvas(Modifier.width(width.dp).height(28.dp).pointerInput(scene.totalTime) { detectTapGestures { state.time = (it.x / size.width * scene.totalTime).toInt().coerceIn(0, scene.totalTime) } }.pointerInput(scene.totalTime) { detectDragGestures { change, _ -> state.time = (change.position.x / size.width * scene.totalTime).toInt().coerceIn(0, scene.totalTime) } }) {
                    val n = (width / 120).toInt().coerceAtLeast(1)
                    for (i in 0..n) {
                        val x = size.width * i / n
                        drawLine(muted, Offset(x, size.height - 10), Offset(x, size.height), 1f)
                        if (i < n) drawContext.canvas.nativeCanvas.drawString(formatTime((scene.totalTime.toLong() * i / n).toInt(), scene.fps), x + 4f, 12f, rulerFont, rulerPaint)
                    }
                    drawLine(accent, Offset(size.width * state.time / scene.totalTime.coerceAtLeast(1), 0f), Offset(size.width * state.time / scene.totalTime.coerceAtLeast(1), size.height), 2f)
                }
                scene.layers.asReversed().forEach { layer ->
                    var dragX by remember(layer.id) { mutableFloatStateOf(0f) }
                    var startX by remember(layer.id) { mutableFloatStateOf(0f) }
                    Canvas(Modifier.width(width.dp).height(32.dp).pointerInput(scene, layer) {
                        detectTapGestures { state.selected = layer.id }
                    }.pointerInput(scene, layer) {
                        detectDragGestures(onDragStart = { startX = it.x; dragX = 0f; state.selected = layer.id }, onDragEnd = {
                            val total = scene.totalTime.coerceAtLeast(1)
                            val delta = (dragX / size.width * total).roundToInt()
                            val begin = size.width * layer.startTime / total.toFloat()
                            val end = size.width * layer.endTime / total.toFloat()
                            val frameDelta = (delta.toFloat() * scene.fps / 1000f).roundToInt()
                            val snapped = scene.frameStart(kotlin.math.abs(frameDelta)) * if (frameDelta < 0) -1 else 1
                            state.edit { current -> current.update(layer.id) { l ->
                                when {
                                    kotlin.math.abs(startX - begin) < 10f -> l.trimmedTo((layer.startTime + snapped).coerceIn(0, layer.endTime - 1), layer.endTime)
                                    kotlin.math.abs(startX - end) < 10f -> l.trimmedTo(layer.startTime, (layer.endTime + snapped).coerceIn(layer.startTime + 1, scene.totalTime), scene.media(layer.node["fillVideo"] ?: layer.node["src"] ?: "")?.duration?.div(1000)?.toInt() ?: Int.MAX_VALUE)
                                    startX in begin..end -> current.movedClip(l, (layer.startTime + snapped).coerceIn(0, (scene.totalTime - (layer.endTime - layer.startTime)).coerceAtLeast(0)))
                                    else -> l
                                }
                            } }
                            dragX = 0f
                        }) { change, amount -> change.consume(); dragX += amount.x }
                    }) {
                        val total = scene.totalTime.coerceAtLeast(1).toFloat()
                        val left = size.width * layer.startTime / total
                        val right = size.width * layer.endTime / total
                        drawRect(if (state.selected == layer.id) accent else Color(0xff666c80), Offset(left, 4f), Size((right - left).coerceAtLeast(2f), size.height - 8f))
                        if (state.selected == layer.id) layer.keyframeTimes().forEach { t -> drawCircle(Color.White, 3f, Offset(size.width * t / total, size.height / 2)) }
                        val x = size.width * state.time / total
                        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1f)
                    }
                }
            }
        }
    }
}
