package app.absentmotion.ui.editor

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.at
import app.absentmotion.editor.canToggleClosed
import app.absentmotion.editor.closePath
import app.absentmotion.editor.pullHandles
import app.absentmotion.editor.deletePoint
import app.absentmotion.editor.dragged
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
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.IntOffset
import kotlin.math.min
import app.absentmotion.ui.HoldName

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

internal fun weightedDrag(dx: Float, dy: Float, weight: Float): Pt {
    val far = hypot(dx, dy)
    if (far <= weight) return Pt(0f, 0f)
    val keep = (far - weight) / far
    return Pt(dx * keep, dy * keep)
}

internal fun angleSnapped(from: Pt, to: Pt): Pt {
    val d = to - from
    if (d.length < 1e-4f) return to
    val a = Math.round(atan2(d.y, d.x) / (PI / 4)) * (PI / 4)
    return from + Pt(cos(a).toFloat(), sin(a).toFloat()) * d.length
}

private fun EditorState.ghostClosesPath(mode: PointMode, radius: Float): Boolean {
    val c = pointContours(mode).getOrNull(mode.contour) ?: return false
    val g = mode.ghost ?: return false
    if (mode.handle != Handle.Add || !c.endsAt(mode.point) || !c.canToggleClosed) return false
    val m = mode.screenMatrix(scene, timeMs) ?: return false
    return m.map(g).distanceTo(m.map(c.knots[0].p)) * mode.previewScale < radius
}

@Composable
internal fun PointOverlay(state: EditorState, mode: PointMode, modifier: Modifier, reserveEnd: Dp = 0.dp) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scene = state.scene
    val density = LocalDensity.current
    val reservePx = with(density) { reserveEnd.toPx() }
    val (scale, dx, dy) = previewFrame(size.width + reservePx, size.height.toFloat(), reservePx, scene.width.toFloat(), scene.height.toFloat(), state.canvasZoom, state.canvasPan)
    SideEffect { mode.previewScale = scale }
    val view by rememberUpdatedState(Triple(scale, dx, dy))
    var pulling by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var shift by remember { mutableStateOf(false) }
    val matrix = mode.screenMatrix(scene, state.timeMs)
    fun onScreen(p: Pt) = matrix?.map(p)?.let { Offset(it.x * scale + dx, it.y * scale + dy) }
    val current = state.pointContours(mode).getOrNull(mode.contour)
    val appending = mode.handle == Handle.Add && current?.endsAt(mode.point) == true
    val first = current?.knots?.firstOrNull()?.takeIf { appending && current.canToggleClosed }?.let { onScreen(it.p) }
    val hoverCloses = first != null && hover?.let { (it - first).getDistance() < with(density) { 12.dp.toPx() } } == true
    val closeCue = hoverCloses || state.ghostClosesPath(mode, with(density) { 22.dp.toPx() })
    Box(modifier) {
        Canvas(
            Modifier.matchParentSize().onSizeChanged { size = it }.canvasNavigation(state, frame = {
                previewFrame(size.width + reservePx, size.height.toFloat(), reservePx, state.scene.width.toFloat(), state.scene.height.toFloat(), state.canvasZoom, state.canvasPan)
            }).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        hover = if (event.type == PointerEventType.Exit || change.type != PointerType.Mouse) null else change.position
                        shift = event.keyboardModifiers.isShiftPressed
                    }
                }
            }.pointerInput(state, mode) {
                var lastTap: Triple<Int, Int, Long>? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val keys = currentEvent.keyboardModifiers
                    down.consume()
                    state.playing = false
                    val (s, ox, oy) = view
                    val m = mode.screenMatrix(state.scene, state.timeMs) ?: return@awaitEachGesture
                    fun scene(p: Offset) = Pt((p.x - ox) / s, (p.y - oy) / s)
                    val contours = state.pointContours(mode)
                    val inScene = contours.map { c -> c.copy(knots = c.knots.map { k -> Knot(m.map(k.p), k.inHandle?.let { h -> m.map(h) }, k.outHandle?.let { h -> m.map(h) }) }) }
                    val picked = inScene.hit(scene(down.position), 24.dp.toPx() / s, mode.contour.takeIf { mode.point >= 0 }?.let { it to mode.point })
                    val contour = contours.getOrNull(mode.contour)
                    val adding = mode.handle == Handle.Add && contour?.endsAt(mode.point) == true
                    var moved = false
                    if (picked != null && adding && picked.first == mode.contour && picked.second == 0 && picked.third == Handle.Main && contour.canToggleClosed) {
                        state.closePath(mode)
                        lastTap = null
                    } else if (picked != null) {
                        val tapped = lastTap
                        if (picked.third == Handle.Main && tapped != null && tapped.first == picked.first && tapped.second == picked.second && down.uptimeMillis - tapped.third < 350) {
                            lastTap = null
                            mode.contour = picked.first
                            mode.point = picked.second
                            mode.handle = Handle.Main
                            state.toggleCurve(mode)
                            return@awaitEachGesture
                        }
                        lastTap = if (picked.third == Handle.Main) Triple(picked.first, picked.second, down.uptimeMillis) else null
                        mode.contour = picked.first
                        mode.point = picked.second
                        mode.handle = picked.third
                        val alt = keys.isAltPressed
                        val start = contours[picked.first].knots[picked.second].at(picked.third) ?: return@awaitEachGesture
                        val weight = maxOf(viewConfiguration.touchSlop, 10.dp.toPx())
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed || change.isConsumed) break
                                val raw = change.position - down.position
                                val far = raw.getDistance()
                                if (!moved && far > weight) { moved = true; mode.holding = true }
                                if (moved) {
                                    if (alt && picked.third == Handle.Main) state.pullHandles(mode, m.inverseMap(scene(change.position)))
                                    else state.moveSelectedTo(mode, start.dragged(weightedDrag(raw.x, raw.y, weight), s, m::map, m::inverseMap), free = alt)
                                }
                                change.consume()
                            }
                        } finally {
                            mode.holding = false
                        }
                        if (moved) { state.endGesture(); lastTap = null }
                    } else if (adding) {
                        val last = contour.knots.lastOrNull()?.p?.let(m::map)
                        val tapAt = scene(down.position).let { p -> if (keys.isShiftPressed && last != null) angleSnapped(last, p) else p }
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                val at = m.inverseMap(tapAt)
                                val pull = if (moved) m.inverseMap(scene(change.position)) - at else null
                                pulling = null
                                state.placePoint(mode, at, pull)
                                break
                            }
                            if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) pulling = Offset(tapAt.x * s + ox, tapAt.y * s + oy) to change.position
                            change.consume()
                        }
                        pulling = null
                    }
                }
            },
        ) {
            val m = matrix ?: return@Canvas
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
            val pointer = hover
            if (mode.handle == Handle.Add && pointer == null) {
                val c = state.pointContours(mode).getOrNull(mode.contour)
                val g = c?.let { ghostOf(it, mode) }
                if (c != null && g != null && !closeCue) {
                    val at = screen(g)
                    c.knots.getOrNull(mode.point)?.let { drawLine(mint, screen(it.p), at, 1.5.dp.toPx()) }
                    drawCircle(Color.Black, ring + 1.5.dp.toPx(), at, style = Stroke(5.dp.toPx()))
                    drawCircle(mint, ring, at, style = Stroke(2.5.dp.toPx()))
                }
            }
            val lastKnot = current?.knots?.lastOrNull()
            if (appending && pointer != null && !hoverCloses && pulling == null && lastKnot != null) {
                val from = screen(lastKnot.p)
                val to = if (shift) angleSnapped(m.map(lastKnot.p), Pt((pointer.x - dx) / scale, (pointer.y - dy) / scale)).let { Offset(it.x * scale + dx, it.y * scale + dy) } else pointer
                drawLine(mint, from, to, 1.5.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            }
            if (closeCue && first != null) {
                drawCircle(Color.Black, ring + 1.5.dp.toPx(), first, style = Stroke(5.dp.toPx()))
                drawCircle(mint, ring, first, style = Stroke(2.5.dp.toPx()))
            }
            pulling?.let { (from, to) ->
                drawLine(Color.White, from, to, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(mint, 6.dp.toPx(), to)
                drawCircle(Color.White, 8.dp.toPx(), from)
            }
        }
        val tipAt = hover
        if (hoverCloses && tipAt != null) {
            Text(
                stringResource(R.string.a11y_close_path),
                Modifier.offset { IntOffset((tipAt.x + 14.dp.toPx()).roundToInt(), (tipAt.y + 10.dp.toPx()).roundToInt()) }
                    .background(Am.Field, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = Am.OnDark, fontSize = 12.sp, maxLines = 1,
            )
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
                HoldName(stringResource(if (contour?.closed == true) R.string.a11y_open_path else R.string.a11y_close_path))
            }
            val curvable = knot != null && mode.handle != Handle.Add
            Box(Modifier.weight(1f).fillMaxWidth().alpha(if (curvable) 1f else DimAlpha).clickable(enabled = curvable) { state.toggleCurve(mode) }, Alignment.Center) {
                Icon(if (knot?.sharp != false) DrawingIcons.VertexCurved else DrawingIcons.VertexSharp, stringResource(if (knot?.sharp != false) R.string.a11y_curved_point else R.string.a11y_sharp_point), Modifier.size(28.dp), tint = Am.OnDark)
                HoldName(stringResource(if (knot?.sharp != false) R.string.a11y_curved_point else R.string.a11y_sharp_point))
            }
            val adding = mode.handle == Handle.Add
            val usable = if (adding) contour != null && (contour.endsAt(mode.point) || contour.knots.size > 1) else knot != null
            Box(Modifier.weight(1f).fillMaxWidth().alpha(if (usable) 1f else DimAlpha).clickable(enabled = usable) {
                if (adding) state.placePoint(mode) else state.deletePoint(mode)
            }, Alignment.Center) {
                Icon(if (adding) DrawingIcons.AddPoint else DrawingIcons.DeletePoint, stringResource(if (adding) R.string.a11y_add_point else R.string.a11y_delete_point), Modifier.size(24.dp), tint = Am.OnDark)
                HoldName(stringResource(if (adding) R.string.a11y_add_point else R.string.a11y_delete_point))
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
    Box(modifier.fillMaxWidth().clickable(onClick = onClick), Alignment.Center) { Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark); HoldName(description) }
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
                        if (!dragged && change.uptimeMillis - down.uptimeMillis < 300 && mode.handle == Handle.Add) {
                            if (state.ghostClosesPath(mode, 22.dp.toPx())) state.closePath(mode) else state.placePoint(mode)
                        }
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
            when {
                !adding -> stringResource(R.string.point_drag_to_move)
                state.ghostClosesPath(mode, with(LocalDensity.current) { 22.dp.toPx() }) -> stringResource(R.string.point_tap_to_close)
                atEnd -> stringResource(R.string.point_aim_next)
                else -> stringResource(R.string.point_drag_to_add)
            },
            Modifier.align(Alignment.Center).padding(horizontal = 20.dp), color = Guide, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LockButton(stringResource(R.string.point_angle), mode.angleLock, bothHandles, true) { mode.angleLock = !mode.angleLock }
            LockButton(stringResource(R.string.point_distance), mode.distanceLock && mode.angleLock, bothHandles && mode.angleLock, true) { mode.distanceLock = !mode.distanceLock }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).fillMaxWidth().padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            ContourArrow(DrawingIcons.Prev, stringResource(R.string.point_contour, mode.contour.toString()), mode.contour > 0) { state.gotoContour(mode, -1) }
            Text(stringResource(R.string.point_contour, (mode.contour + 1).toString()), Modifier.weight(1f), color = Guide, fontSize = 13.sp, textAlign = TextAlign.Center)
            ContourArrow(DrawingIcons.Next, stringResource(R.string.point_contour, (mode.contour + 2).toString()), true) { state.gotoContour(mode, 1) }
        }
    }
}

private fun swipe(state: EditorState, mode: PointMode, delta: Offset, width: Float) {
    val m = mode.screenMatrix(state.scene, state.timeMs) ?: return
    fun moved(from: Pt) = from.dragged(Pt(delta.x, delta.y), mode.previewScale, m::map, m::inverseMap)
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
        Text(label, color = animatedColor(if (on) Am.Accent else Guide), fontSize = deskSp(11f), maxLines = 1)
    }
}

@Composable
private fun ContourArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(35.dp, 30.dp).clip(RoundedCornerShape(5.dp)).background(Am.PanelInset).background(Am.OnDark.copy(alpha = 0.13f)).alpha(if (enabled) 1f else 0.4f)
            .clickable(role = Role.Button) { if (enabled) onClick() }.semantics { contentDescription = label; if (!enabled) disabled() },
        Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Am.Faint)
        HoldName(label)
    }
}

@Composable
private fun PointList(state: EditorState, mode: PointMode, contour: Contour?, modifier: Modifier) {
    val knots = contour?.knots ?: emptyList()
    LazyColumn(modifier.padding(horizontal = 4.dp).selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (knots.isEmpty()) item { PlusCell(mode.handle == Handle.Add) { state.selectAdd(mode, mode.contour, -1) } }
        itemsIndexed(knots) { i, k ->
            val here = i == mode.point
            val number = (i + 1).toString()
            val pointName = stringResource(R.string.a11y_point_number, number)
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                HandleChip(k.inHandle != null, here && mode.handle == Handle.In, stringResource(R.string.a11y_point_handle_in, number)) { mode.point = i; mode.handle = Handle.In }
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (here && mode.handle == Handle.Main) Am.Accent else Am.Field)
                        .selectable(here && mode.handle == Handle.Main, role = Role.RadioButton) { mode.point = i; mode.handle = Handle.Main }
                        .semantics { contentDescription = pointName },
                    Alignment.Center,
                ) { Text(number, color = if (here && mode.handle == Handle.Main) Am.OnAccent else Am.OnDark, fontSize = deskSp(11f), maxLines = 1) }
                HandleChip(k.outHandle != null, here && mode.handle == Handle.Out, stringResource(R.string.a11y_point_handle_out, number)) { mode.point = i; mode.handle = Handle.Out }
            }
            if (i < knots.lastIndex || contour?.closed == true || contour?.endsAt(i) == true) {
                PlusCell(here && mode.handle == Handle.Add) { state.selectAdd(mode, mode.contour, i) }
            }
        }
    }
}

@Composable
private fun HandleChip(present: Boolean, selected: Boolean, label: String, onClick: () -> Unit) {
    val strip = Modifier.size(12.dp, 34.dp).alpha(if (present) 1f else 0f)
    Box(
        if (present) strip.selectable(selected, role = Role.RadioButton, onClick = onClick).semantics { contentDescription = label } else strip.clearAndSetSemantics {},
        Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).background(if (selected) Am.Accent else Am.Faint))
        if (present) HoldName(label)
    }
}

@Composable
private fun PlusCell(selected: Boolean, onClick: () -> Unit) {
    val add = stringResource(R.string.a11y_add_point)
    Box(Modifier.fillMaxWidth().height(26.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = add }, Alignment.Center) {
        Icon(DrawingIcons.AddPoint, null, Modifier.size(18.dp), tint = animatedColor(if (selected) Am.Accent else Am.Faint))
        HoldName(add)
    }
}
