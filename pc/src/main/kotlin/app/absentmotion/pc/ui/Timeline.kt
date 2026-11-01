package app.absentmotion.pc.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.ui.timeline.*
import kotlinx.coroutines.launch
import kotlin.math.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Timeline(state: ProjectState, zoom: Float, setZoom: (Float) -> Unit, status: String) {
    val scene = state.scene
    val layers = scene.layers.asReversed()
    val selected = state.selectedIds
    val scroll = rememberScrollState()
    val vertical = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val textMeasurer = rememberTextMeasurer()
    var viewportWidth by remember { mutableIntStateOf(1000) }
    var snap by remember { mutableStateOf<Int?>(null) }
    var marquee by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var renaming by remember { mutableLongStateOf(0L) }
    val total = scene.totalTime.coerceAtLeast(1000)
    val extent = maxOf(total.toLong() * 2, (layers.maxOfOrNull { it.endTime } ?: 0).toLong() + total / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val width = (800f * zoom * extent / total).coerceIn(800f, 100_000f)
    fun choose(id: Long, add: Boolean) = state.select(id, add)
    fun changeZoom(value: Float, cursor: Float? = null) {
        val next = value.coerceIn(.5f, 15f)
        if (cursor != null && next != zoom) scope.launch {
            val target = ((scroll.value + cursor) * next / zoom - cursor).roundToInt()
            setZoom(next)
            androidx.compose.runtime.withFrameNanos { }
            scroll.scrollTo(target.coerceAtLeast(0))
        } else setZoom(next)
    }
    Column(Modifier.fillMaxSize().background(timeline).focusRequester(focus).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || renaming != 0L) false else when {
            (if (DesktopPlatform.mac) event.isMetaPressed else event.isCtrlPressed) && event.key == Key.B -> { if (state.layer?.node?.get("locked") != "true") state.split(); true }
            !(if (DesktopPlatform.mac) event.isMetaPressed else event.isCtrlPressed) && (event.key == Key.Delete || event.key == Key.Backspace) -> {
                state.delete(); true
            }
            !(if (DesktopPlatform.mac) event.isMetaPressed else event.isCtrlPressed) && (event.key == Key.Plus || event.key == Key.Equals) -> { changeZoom(zoom * 1.5f); true }
            !(if (DesktopPlatform.mac) event.isMetaPressed else event.isCtrlPressed) && event.key == Key.Minus -> { changeZoom(zoom / 1.5f); true }
            else -> false
        }
    }.focusable()) {
        Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TIMELINE   ${formatTime(state.time, scene.fps)}    $status", Modifier.padding(start = 10.dp), color = muted, fontSize = 11.sp)
            Row { Text("−", Modifier.clickable { changeZoom(zoom / 1.5f) }.padding(horizontal = 10.dp), color = Color.White); Text("+", Modifier.clickable { changeZoom(zoom * 1.5f) }.padding(horizontal = 10.dp), color = Color.White) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(185.dp)) {
                Text("LAYERS", Modifier.height(28.dp).padding(start = 10.dp, top = 6.dp), color = muted, fontSize = 10.sp)
                Column(Modifier.weight(1f).verticalScroll(vertical)) {
                    layers.forEachIndexed { index, layer ->
                        val locked = layer.node["locked"] == "true"
                        var drag by remember(layer.id) { mutableFloatStateOf(0f) }
                        Row(Modifier.height(32.dp).fillMaxWidth().background(if (layer.id in selected) Color(0xff373a44) else timeline), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (layer.hidden) "○" else "●", Modifier.testTag("eye-${layer.id}").clickable { state.edit { it.update(layer.id) { l -> l.withHidden(!l.hidden) } } }.padding(horizontal = 7.dp), color = muted, fontSize = 12.sp)
                            Text(if (locked) "▣" else "□", Modifier.testTag("lock-${layer.id}").clickable {
                                state.edit { it.update(layer.id) { l -> l.copy(node = l.node.with("locked", if (locked) null else "true")) } }
                                if (!locked) state.selectMany(selected - layer.id)
                            }.padding(horizontal = 5.dp), color = if (locked) accent else muted, fontSize = 13.sp)
                            if (renaming == layer.id) {
                                var name by remember(layer.id) { mutableStateOf(layer.label.ifBlank { layer.kind }) }
                                val renameFocus = remember { FocusRequester() }
                                LaunchedEffect(layer.id) { renameFocus.requestFocus() }
                                BasicTextField(name, { name = it }, Modifier.focusRequester(renameFocus).weight(1f).testTag("rename-${layer.id}").onKeyEvent {
                                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.Escape)) {
                                        if (it.key == Key.Enter) state.edit { s -> s.update(layer.id) { l -> l.withLabel(name) } }
                                        renaming = 0; true
                                    } else false
                                }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 11.sp))
                            } else Text(layer.label.ifBlank { layer.kind }, Modifier.weight(1f).testTag("header-${layer.id}")
                                .combinedClickable(onClick = { focus.requestFocus(); choose(layer.id, false) }, onDoubleClick = { if (!locked) renaming = layer.id })
                                .pointerInput(layer.id, layers.size, locked) { detectDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                                    if (!locked) { val target = (index + (if (abs(drag) < 6.dp.toPx()) 0 else sign(drag).toInt() * maxOf(1, (abs(drag) / 32.dp.toPx()).roundToInt()))).coerceIn(0, layers.lastIndex); state.edit { it.reorder(layer.id, layers.lastIndex - target) } }
                                }) { change, amount -> change.consume(); drag += amount.y } }, color = if (layer.id in selected) accent else Color.White, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportWidth = it.width }.pointerInput(zoom) {
                    awaitPointerEventScope { while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val change = event.changes.first()
                            val delta = change.scrollDelta
                            if ((if (DesktopPlatform.mac) event.keyboardModifiers.isMetaPressed else event.keyboardModifiers.isCtrlPressed)) changeZoom(zoom * if (delta.y < 0) 1.2f else 1f / 1.2f, change.position.x)
                            else scope.launch { scroll.scrollBy((if (abs(delta.x) > .01f) delta.x else delta.y) * 48f) }
                            change.consume()
                        }
                    } }
                }) {
                    Column(Modifier.horizontalScroll(scroll)) {
                        Canvas(Modifier.width(width.dp).height(28.dp).testTag("ruler").pointerInput(extent) {
                            detectDragGestures(onDragStart = { state.playing = false; state.time = (it.x / size.width * extent).roundToInt().coerceIn(0, extent); focus.requestFocus() }) { change, _ -> state.time = (change.position.x / size.width * extent).roundToInt().coerceIn(0, extent); change.consume() }
                        }.pointerInput(extent) { detectTapGestures { state.playing = false; state.time = (it.x / size.width * extent).roundToInt().coerceIn(0, extent); focus.requestFocus() } }) {
                            val step = rulerStep(extent, size.width, scene.fps)
                            val first = (scroll.value / size.width * extent / step).toInt().coerceAtLeast(0)
                            val last = minOf((extent / step).toInt(), first + (viewportWidth / size.width * extent / step).toInt() + 2)
                            for (i in first..last) {
                                val t = i * step
                                val x = t / extent * size.width
                                drawLine(muted, Offset(x, size.height - 8), Offset(x, size.height), 1f)
                                drawText(textMeasurer, rulerLabel(t, step, scene.fps), topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx()), style = TextStyle(color = muted, fontSize = 10.sp))
                            }
                            val end = scene.totalTime.toFloat() / extent * size.width
                            drawLine(Color(0xffe5b66d), Offset(end, 0f), Offset(end, size.height), 2f)
                            val x = state.time.toFloat() / extent * size.width
                            drawLine(accent, Offset(x, 0f), Offset(x, size.height), 2f)
                        }
                        Box(Modifier.weight(1f).verticalScroll(vertical)) {
                            Canvas(Modifier.width(width.dp).height((layers.size.coerceAtLeast(1) * 32).dp).testTag("tracks").pointerInput(scene, extent, selected.toList()) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    focus.requestFocus()
                                    val rowHeight = 32.dp.toPx()
                                    val row = (down.position.y / rowHeight).toInt()
                                    val layer = layers.getOrNull(row)
                                    val scale = size.width / extent.toFloat()
                                    val start = layer?.startTime?.times(scale) ?: 0f
                                    val end = layer?.endTime?.times(scale) ?: 0f
                                    val hit = layer != null && down.position.x in (start - 6f)..(end + 6f) && layer.node["locked"] != "true"
                                    val key = if (layer != null && layer.id == state.selected) layer.keyframeTimes().minByOrNull { abs(it * scale - down.position.x) }?.takeIf { abs(it * scale - down.position.x) < 7f && abs(down.position.y % rowHeight - rowHeight / 2) < 8f } else null
                                    val mode = if (key == null && abs(down.position.x - state.time * scale) < 5f) 4 else if (!hit) 0 else if (abs(down.position.x - start) < 8f) 1 else if (abs(down.position.x - end) < 8f) 2 else 3
                                    var current = down.position
                                    var moved = false
                                    var meta = false
                                    var shift = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        meta = (if (DesktopPlatform.mac) event.keyboardModifiers.isMetaPressed else event.keyboardModifiers.isCtrlPressed)
                                        shift = event.keyboardModifiers.isShiftPressed
                                        current = change.position
                                        if ((current - down.position).getDistance() > 4f) moved = true
                                        if (moved && mode == 0) marquee = down.position to current
                                        if (moved && mode == 4) { state.playing = false; state.time = (current.x / scale).roundToInt().coerceIn(0, extent) }
                                        if (moved && mode in 1..3 && layer != null) {
                                            val raw = ((current.x - down.position.x) / scale).roundToInt()
                                            val reference = if (mode == 2) layer.endTime else layer.startTime
                                            val targets = layers.filter { it.id != layer.id }.flatMap { listOf(it.startTime, it.endTime) } + state.time
                                            val result = clipSnap(reference, raw, if (mode == 3) layer.endTime - layer.startTime else 0, targets, scale, meta, scene.fps)
                                            snap = result.second
                                        }
                                        if (!change.pressed) break
                                        if (moved) change.consume()
                                    }
                                    if (moved && mode == 0) {
                                        val lo = minOf(down.position.x, current.x) / scale
                                        val hi = maxOf(down.position.x, current.x) / scale
                                        val top = (minOf(down.position.y, current.y) / rowHeight).toInt().coerceAtLeast(0)
                                        val bottom = (maxOf(down.position.y, current.y) / rowHeight).toInt().coerceAtMost(layers.lastIndex)
                                        val hits = if (top <= bottom) (top..bottom).map { layers[it] }.filter { it.node["locked"] != "true" && it.endTime >= lo && it.startTime <= hi }.map { it.id } else emptyList()
                                        state.selectMany((if (shift) selected else emptyList()) + hits)
                                    } else if (moved && mode == 4) {
                                        state.playing = false
                                    } else if (moved && layer != null && mode in 1..3) {
                                        if (layer.id !in selected) choose(layer.id, false)
                                        val raw = ((current.x - down.position.x) / scale).roundToInt()
                                        val reference = if (mode == 2) layer.endTime else layer.startTime
                                        val targets = layers.filter { it.id !in selected }.flatMap { listOf(it.startTime, it.endTime) } + state.time
                                        val delta = clipSnap(reference, raw, if (mode == 3) layer.endTime - layer.startTime else 0, targets, scale, meta, scene.fps).first - reference
                                        val ids = if (mode == 3) selected.toList() else listOf(layer.id)
                                        val bounded = maxOf(delta, -(ids.mapNotNull { scene.layer(it)?.startTime }.minOrNull() ?: 0))
                                        state.edit { original -> ids.fold(original) { s, id -> s.update(id) { l ->
                                            val duration = (l.mediaFill?.uri ?: l.node["src"])?.let { s.media(it)?.duration }?.takeIf { it > 0 }?.div(1000)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
                                            when (mode) {
                                                1 -> l.trimmedTo((l.startTime + delta).coerceIn(0, (l.endTime - 1).coerceAtLeast(0)), l.endTime, duration)
                                                2 -> l.trimmedTo(l.startTime, (l.endTime.toLong() + delta).coerceIn((l.startTime + 1).toLong(), Int.MAX_VALUE.toLong()).toInt(), duration)
                                                else -> s.movedClip(l, (l.startTime + bounded).coerceAtLeast(0))
                                            }
                                        } } }
                                    } else if (key != null) { state.playing = false; state.time = key }
                                    else if (hit && layer != null) choose(layer.id, shift)
                                    else { state.playing = false; state.time = (down.position.x / scale).roundToInt().coerceIn(0, extent); if (!shift) state.selected = 0 }
                                    marquee = null; snap = null
                                }
                            }) {
                                val scale = size.width / extent
                                layers.forEachIndexed { index, layer ->
                                    val y = index * 32.dp.toPx()
                                    val h = 32.dp.toPx()
                                    drawRect(if (index % 2 == 0) Color(0xff202229) else timeline, Offset(0f, y), Size(size.width, h))
                                    val left = layer.startTime * scale
                                    val right = layer.endTime * scale
                                    drawRect(if (layer.id in selected) accent else Color(0xff666c80), Offset(left, y + 4.dp.toPx()), Size((right - left).coerceAtLeast(2f), h - 8.dp.toPx()), alpha = if (layer.hidden) .4f else 1f)
                                    if (right - left > 45.dp.toPx()) {
                                        clipRect(left, y, right, y + h) {
                                            drawText(textMeasurer, layer.label.ifBlank { layer.kind }, topLeft = Offset(left + 6.dp.toPx(), y + 7.dp.toPx()), style = TextStyle(color = Color(0xff171920), fontSize = 11.sp))
                                        }
                                    }
                                    if (layer.id == state.selected) layer.keyframeTimes().forEach { t ->
                                        val x = t * scale; val cy = y + h / 2; val d = 4.dp.toPx()
                                        val path = Path().apply { moveTo(x, cy - d); lineTo(x + d, cy); lineTo(x, cy + d); lineTo(x - d, cy); close() }
                                        drawPath(path, Color.White)
                                    }
                                }
                                val end = scene.totalTime * scale
                                drawLine(Color(0xffe5b66d), Offset(end, 0f), Offset(end, size.height), 1f)
                                val x = state.time * scale
                                drawLine(accent, Offset(x, 0f), Offset(x, size.height), 1f)
                                snap?.let { drawLine(Color(0xff87dbff), Offset(it * scale, 0f), Offset(it * scale, size.height), 2f) }
                                marquee?.let { (a, b) -> drawRect(Color(0x4487dbff), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y))) }
                            }
                        }
                    }
                }
                HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth().height(12.dp))
            }
        }
    }
}
