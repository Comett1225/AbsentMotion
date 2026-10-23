package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.at
import app.absentmotion.editor.canToggleClosed
import app.absentmotion.editor.deletePoint
import app.absentmotion.editor.endsAt
import app.absentmotion.editor.gotoContour
import app.absentmotion.editor.leaveDrawing
import app.absentmotion.editor.map
import app.absentmotion.editor.screenMatrix
import app.absentmotion.editor.inverseMap
import app.absentmotion.editor.moveSelectedTo
import app.absentmotion.editor.onionShape
import app.absentmotion.editor.placePoint
import app.absentmotion.editor.pointContours
import app.absentmotion.editor.selectAdd
import app.absentmotion.editor.toggleClosed
import app.absentmotion.editor.toggleCurve
import app.absentmotion.model.Contour
import app.absentmotion.model.Handle
import app.absentmotion.model.Knot
import app.absentmotion.model.Pt
import app.absentmotion.model.hit
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import kotlin.math.min

private val Divider get() = Am.PreviewBg
private val Guide get() = Am.Caption

private fun ghostOf(c: Contour, mode: PointMode): Pt? {
    if (c.endsAt(mode.point)) return mode.ghost
    val a = c.knots.getOrNull(mode.point) ?: return null
    val b = c.knots[(mode.point + 1) % c.knots.size]
    val t = mode.curveAt
    val c1 = a.outHandle ?: a.p
    val c2 = b.inHandle ?: b.p
    val u = 1 - t
    return a.p * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + b.p * (t * t * t)
}

@Composable
internal fun PointOverlay(state: EditorState, mode: PointMode, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scene = state.scene
    val scale = if (size.width == 0) 1f else min(size.width.toFloat() / scene.width, size.height.toFloat() / scene.height)
    val dx = (size.width - scene.width * scale) / 2
    val dy = (size.height - scene.height * scale) / 2
    SideEffect { mode.previewScale = scale }
    val view by rememberUpdatedState(Triple(scale, dx, dy))
    var pulling by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    Canvas(
        modifier.onSizeChanged { size = it }.pointerInput(state, mode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val (s, ox, oy) = view
                val m = mode.screenMatrix(state.scene, state.timeMs) ?: return@awaitEachGesture
                fun scene(p: Offset) = Pt((p.x - ox) / s, (p.y - oy) / s)
                val contours = state.pointContours(mode)
                val inScene = contours.map { c -> c.copy(knots = c.knots.map { k -> Knot(m.map(k.p), k.inHandle?.let { h -> m.map(h) }, k.outHandle?.let { h -> m.map(h) }) }) }
                val picked = inScene.hit(scene(down.position), 24.dp.toPx() / s, mode.contour.takeIf { mode.point >= 0 }?.let { it to mode.point })
                var moved = false
                if (picked != null) {
                    mode.contour = picked.first
                    mode.point = picked.second
                    mode.handle = picked.third
                    val start = contours[picked.first].knots[picked.second].at(picked.third) ?: return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) state.moveSelectedTo(mode, start + (m.inverseMap(scene(change.position)) - m.inverseMap(scene(down.position))))
                        change.consume()
                    }
                    if (moved) state.endGesture()
                } else if (mode.handle == Handle.Add && contours.getOrNull(mode.contour)?.endsAt(mode.point) == true) {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val at = m.inverseMap(scene(down.position))
                            val pull = if (moved) m.inverseMap(scene(change.position)) - at else null
                            pulling = null
                            state.placePoint(mode, at, pull)
                            break
                        }
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) pulling = down.position to change.position
                        change.consume()
                    }
                    pulling = null
                }
            }
        },
    ) {
        val m = mode.screenMatrix(state.scene, state.timeMs) ?: return@Canvas
        fun screen(p: Pt) = m.map(p).let { Offset(it.x * scale + dx, it.y * scale + dy) }
        val mint = Am.Accent
        val ring = 11.dp.toPx()
        state.onionShape(mode)?.forEach { c ->
            if (c.knots.size < 2) return@forEach
            val outline = Path()
            outline.moveTo(screen(c.knots[0].p).x, screen(c.knots[0].p).y)
            for (i in 0 until if (c.closed) c.knots.size else c.knots.size - 1) {
                val a = c.knots[i]
                val b = c.knots[(i + 1) % c.knots.size]
                val end = screen(b.p)
                if (a.outHandle == null && b.inHandle == null) outline.lineTo(end.x, end.y)
                else {
                    val c1 = screen(a.outHandle ?: a.p)
                    val c2 = screen(b.inHandle ?: b.p)
                    outline.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
                }
            }
            if (c.closed) outline.close()
            drawPath(outline, Color.Black.copy(alpha = 0.5f), style = Stroke(3.dp.toPx()))
            drawPath(outline, Color.White.copy(alpha = 0.7f), style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }
        if (mode.handle == Handle.Add) {
            val c = state.pointContours(mode).getOrNull(mode.contour)
            val g = c?.let { ghostOf(it, mode) }
            if (c != null && g != null) {
                val at = screen(g)
                c.knots.getOrNull(mode.point)?.let { drawLine(mint, screen(it.p), at, 1.5.dp.toPx()) }
                drawCircle(Color.Black, ring + 1.5.dp.toPx(), at, style = Stroke(5.dp.toPx()))
                drawCircle(mint, ring, at, style = Stroke(2.5.dp.toPx()))
            }
        }
        pulling?.let { (from, to) ->
            drawLine(Color.White, from, to, 2.dp.toPx(), StrokeCap.Round)
            drawCircle(mint, 6.dp.toPx(), to)
            drawCircle(Color.White, 8.dp.toPx(), from)
        }
    }
}

@Composable
internal fun PointPanel(state: EditorState, mode: PointMode) {
    val contours = state.pointContours(mode)
    val contour = contours.getOrNull(mode.contour)
    val knot = contour?.knots?.getOrNull(mode.point)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(60.dp).fillMaxHeight()) {
            PanelButton(ChromeIcons.Back, stringResource(R.string.topbar_back), Modifier.weight(1f)) { state.leaveDrawing() }
            val closable = contour?.canToggleClosed == true
            Box(Modifier.weight(1f).fillMaxWidth().alpha(if (closable) 1f else DimAlpha).clickable(enabled = closable) { state.toggleClosed(mode) }, Alignment.Center) {
                Icon(if (contour?.closed == true) DrawingIcons.PathOpen else DrawingIcons.PathClosed, stringResource(if (contour?.closed == true) R.string.a11y_open_path else R.string.a11y_close_path), Modifier.size(24.dp), tint = Am.OnDark)
            }
            val curvable = knot != null && mode.handle != Handle.Add
            Box(Modifier.weight(1f).fillMaxWidth().alpha(if (curvable) 1f else DimAlpha).clickable(enabled = curvable) { state.toggleCurve(mode) }, Alignment.Center) {
                Icon(if (knot?.sharp != false) DrawingIcons.VertexCurved else DrawingIcons.VertexSharp, stringResource(if (knot?.sharp != false) R.string.a11y_curved_point else R.string.a11y_sharp_point), Modifier.size(28.dp), tint = Am.OnDark)
            }
            val adding = mode.handle == Handle.Add
            val usable = if (adding) contour != null && (contour.endsAt(mode.point) || contour.knots.size > 1) else knot != null
            Box(Modifier.weight(1f).fillMaxWidth().alpha(if (usable) 1f else DimAlpha).clickable(enabled = usable) {
                if (adding) state.placePoint(mode) else state.deletePoint(mode)
            }, Alignment.Center) {
                Icon(if (adding) DrawingIcons.AddPoint else DrawingIcons.DeletePoint, stringResource(if (adding) R.string.a11y_add_point else R.string.a11y_delete_point), Modifier.size(24.dp), tint = Am.OnDark)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Divider))
        Trackpad(state, mode, contour, Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(Divider))
        PointList(state, mode, contour, Modifier.width(59.dp).fillMaxHeight())
    }
}

@Composable
private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().clickable(onClick = onClick), Alignment.Center) { Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark) }
}

@Composable
private fun Trackpad(state: EditorState, mode: PointMode, contour: Contour?, modifier: Modifier) {
    val adding = mode.handle == Handle.Add
    val atEnd = contour == null || contour.endsAt(mode.point)
    val bothHandles = contour?.knots?.getOrNull(mode.point)?.let { it.inHandle != null && it.outHandle != null } == true && (mode.handle == Handle.In || mode.handle == Handle.Out)
    Box(
        modifier.background(Am.PanelInset).pointerInput(mode) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var total = Offset.Zero
                var dragged = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (!dragged && change.uptimeMillis - down.uptimeMillis < 300 && mode.handle == Handle.Add) state.placePoint(mode)
                        break
                    }
                    val d = change.positionChange()
                    total += d
                    if (!dragged && total.getDistance() > viewConfiguration.touchSlop) dragged = true
                    if (dragged) swipe(state, mode, d, size.width.toFloat())
                    change.consume()
                }
                state.endGesture()
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 8.dp.toPx()
            val arm = 12.dp.toPx()
            val line = 1.5.dp.toPx()
            val colour = Guide.copy(alpha = 0.6f)
            for ((cx, cy, sx, sy) in listOf(listOf(inset, inset, 1f, 1f), listOf(size.width - inset, inset, -1f, 1f), listOf(inset, size.height - inset, 1f, -1f), listOf(size.width - inset, size.height - inset, -1f, -1f))) {
                drawLine(colour, Offset(cx, cy), Offset(cx + sx * arm, cy), line, StrokeCap.Round)
                drawLine(colour, Offset(cx, cy), Offset(cx, cy + sy * arm), line, StrokeCap.Round)
            }
        }
        Text(
            when { !adding -> stringResource(R.string.point_drag_to_move); atEnd -> stringResource(R.string.point_aim_next); else -> stringResource(R.string.point_drag_to_add) },
            Modifier.align(Alignment.Center).padding(horizontal = 20.dp), color = Guide, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LockButton(stringResource(R.string.point_angle), mode.angleLock, bothHandles, true) { mode.angleLock = !mode.angleLock }
            LockButton(stringResource(R.string.point_distance), mode.distanceLock && mode.angleLock, bothHandles && mode.angleLock, true) { mode.distanceLock = !mode.distanceLock }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).fillMaxWidth().padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            ContourArrow(DrawingIcons.Prev) { state.gotoContour(mode, -1) }
            Text(stringResource(R.string.point_contour, (mode.contour + 1).toString()), Modifier.weight(1f), color = Guide, fontSize = 13.sp, textAlign = TextAlign.Center)
            ContourArrow(DrawingIcons.Next) { state.gotoContour(mode, 1) }
        }
    }
}

private fun swipe(state: EditorState, mode: PointMode, delta: Offset, width: Float) {
    val m = mode.screenMatrix(state.scene, state.timeMs) ?: return
    fun moved(from: Pt) = m.inverseMap(m.map(from) + Pt(delta.x / mode.previewScale, delta.y / mode.previewScale))
    val contour = state.pointContours(mode).getOrNull(mode.contour) ?: return
    if (mode.handle == Handle.Add) {
        if (contour.endsAt(mode.point)) mode.ghost = moved(mode.ghost ?: contour.knots.lastOrNull()?.p ?: Pt(0f, 0f))
        else mode.curveAt = (mode.curveAt + delta.x / width).coerceIn(0.03f, 0.97f)
        return
    }
    val at = contour.knots.getOrNull(mode.point)?.at(mode.handle) ?: return
    state.moveSelectedTo(mode, moved(at))
}

@Composable
private fun LockButton(label: String, on: Boolean, visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 66.dp, height = 28.dp).alpha(if (visible) 1f else 0f).clip(RoundedCornerShape(4.dp))
            .background(if (on) Am.FieldHi else Color.Transparent).clickable(enabled = visible && enabled, onClick = onClick),
        Alignment.Center,
    ) {
        Text(label, color = animatedColor(if (on) Am.Accent else Guide), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ContourArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(35.dp, 30.dp).clip(RoundedCornerShape(5.dp)).background(Am.PanelInset).background(Am.OnDark.copy(alpha = 0.13f)).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = Am.Faint)
    }
}

@Composable
private fun PointList(state: EditorState, mode: PointMode, contour: Contour?, modifier: Modifier) {
    val knots = contour?.knots ?: emptyList()
    LazyColumn(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (knots.isEmpty()) item { PlusCell(mode.handle == Handle.Add) { state.selectAdd(mode, mode.contour, -1) } }
        itemsIndexed(knots) { i, k ->
            val here = i == mode.point
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                HandleChip(k.inHandle != null, here && mode.handle == Handle.In) { mode.point = i; mode.handle = Handle.In }
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (here && mode.handle == Handle.Main) Am.Accent else Am.Field)
                        .clickable { mode.point = i; mode.handle = Handle.Main },
                    Alignment.Center,
                ) { Text("${i + 1}", color = if (here && mode.handle == Handle.Main) Am.OnAccent else Am.OnDark, fontSize = 11.sp, maxLines = 1) }
                HandleChip(k.outHandle != null, here && mode.handle == Handle.Out) { mode.point = i; mode.handle = Handle.Out }
            }
            if (i < knots.lastIndex || contour?.closed == true || contour?.endsAt(i) == true) {
                PlusCell(here && mode.handle == Handle.Add) { state.selectAdd(mode, mode.contour, i) }
            }
        }
    }
}

@Composable
private fun HandleChip(present: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(12.dp).alpha(if (present) 1f else 0f).background(if (selected) Am.Accent else Am.Faint).clickable(enabled = present, onClick = onClick))
}

@Composable
private fun PlusCell(selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(26.dp).clickable(onClick = onClick), Alignment.Center) {
        Icon(DrawingIcons.AddPoint, null, Modifier.size(18.dp), tint = animatedColor(if (selected) Am.Accent else Am.Faint))
    }
}
