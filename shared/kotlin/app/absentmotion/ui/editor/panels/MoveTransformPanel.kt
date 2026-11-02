package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.engine.SnapGuide
import app.absentmotion.editor.chain
import app.absentmotion.editor.place
import app.absentmotion.editor.LayerPoints
import app.absentmotion.editor.setPivot
import app.absentmotion.editor.PIVOT_LIMIT
import app.absentmotion.editor.map
import app.absentmotion.model.Pt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.rememberHaptic
import app.absentmotion.ui.theme.Am
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
internal fun MoveTransformPanel(state: EditorState, layer: Layer, tab: Int, turn: Turn?, modifier: Modifier = Modifier) {
    Box(modifier) {
        when (tab) {
            0 -> PositionTab(state, layer)
            1 -> if (turn != null) TurnTab(state, layer, turn) else RotationTab(state, layer)
            2 -> if (layer.kind == "camera") CameraSizeTab(state, layer) else SizeTab(state, layer)
            3 -> TiltTab(state, layer, turn)
            4 -> SkewTab(state, layer)
            else -> PivotTab(state, layer)
        }
    }
}

@Composable
private fun PositionTab(state: EditorState, layer: Layer) {
    val at = layer.location
    var depth by remember(layer.id) { mutableStateOf(false) }
    val deep by rememberUpdatedState(depth)
    fun openKeypad(active: Int) {
        val fields = listOf(KeypadField("X", at[0]), KeypadField("Y", at[1]), KeypadField("Z", at[2]))
        state.keypad = KeypadRequest(fields, active) { v -> state.editSelected { it.withTransform("location", v[0], v[1], v[2]) } }
    }
    val others by produceState(emptyList<SnapOther>(), layer.id, state.scene.layers, state.timeMs, state.playing) {
        if (state.playing) return@produceState
        value = state.scene.layers.filter { it.id != layer.id && it.kind != "audio" && it.activeAt(state.sampleMs) }.map { o ->
            val at = state.scene.place(o, state.sampleMs)
            SnapOther(at.x, at.y, state.engine.layerBounds(o.id, state.timeMs))
        }
    }
    val box by produceState<FloatArray?>(null, layer.node, state.timeMs, state.playing) { if (!state.playing) value = state.engine.layerBounds(layer.id, state.timeMs) }
    val start by rememberUpdatedState { state.scene.snapAxesFor(layer, state.sampleMs, box, others) }
    val now by rememberUpdatedState(layer)
    val tick = rememberHaptic(state)
    DisposableEffect(Unit) { onDispose { state.snapGuides = emptyList() } }
    val timing = remember { LongArray(2) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.fillMaxSize().holdAndDoubleTap(timing, null).pointerInput(state) {
            var axes = SnapAxis(emptyList()) to SnapAxis(emptyList())
            var origin = now.location
            var travel = Offset.Zero
            var shown = Offset.Zero
            var scale = 1f
            fun done() { state.snapGuides = emptyList(); state.endGesture(); rulerTip(context) }
            detectDragGestures(
                onDragStart = {
                    scale = if (timing.heldBeforeMoving(android.os.SystemClock.uptimeMillis(), viewConfiguration.longPressTimeoutMillis)) FINE_FACTOR else 1f
                    axes = start(); origin = now.location; travel = Offset.Zero; shown = Offset.Zero
                    state.snapGuides = if (deep) emptyList() else axes.first.guidesAt(0f) + axes.second.guidesAt(0f)
                },
                onDragEnd = ::done, onDragCancel = ::done,
            ) { change, drag ->
                change.consume()
                if (deep) {
                    travel += drag * scale
                    state.editSelected(key = "move-z") { l -> l.withTransform("location", origin[0], origin[1], origin[2] - travel.y) }
                    return@detectDragGestures
                }
                travel += drag / density * scale
                val snapped = Offset(axes.first.map(travel.x), axes.second.map(travel.y))
                val step = snapped - shown
                shown = snapped
                val guides = (if (step.x == 0f) axes.first.guidesAt(snapped.x) else emptyList()) +
                    (if (step.y == 0f) axes.second.guidesAt(snapped.y) else emptyList())
                if (guides.any { it !in state.snapGuides }) tick(HapticFeedbackType.SegmentTick)
                state.snapGuides = guides
                if (step != Offset.Zero) state.editSelected(key = "move") { l -> l.withTransform("location", origin[0] + snapped.x, origin[1] + snapped.y, l.location[2]) }
            }
        },
    ) {
        TrackpadCorners()
        TrackpadHint(stringResource(if (depth) R.string.drag_to_move_along_z else R.string.drag_here_to_move_the_layer), Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.TopCenter).padding(top = 17.px)) {
            Row {
                Row(Modifier.width(354.px).height(70.px).clip(PanelShape).background(Am.Field)) {
                    ValueCell(formatFixed(at[0], 2), if (depth) Am.OnDark else Am.Accent, Modifier.weight(1f), "X") { if (depth) depth = false else openKeypad(0) }
                    ValueCell(formatFixed(at[1], 2), if (depth) Am.OnDark else Am.Accent, Modifier.weight(1f), "Y") { if (depth) depth = false else openKeypad(1) }
                }
                Spacer(Modifier.width(20.px))
                NumberBox(formatFixed(at[2], 2), if (depth) Am.Accent else Am.OnDark, Modifier.width(177.px).height(70.px), fontSize = 10.5f) { if (depth) openKeypad(2) else depth = true }
            }
            Row(Modifier.height(23.px)) {
                Caption("X", Modifier.width(177.px).fillMaxHeight())
                Caption("Y", Modifier.width(177.px).fillMaxHeight())
                Spacer(Modifier.width(20.px))
                Caption("Z", Modifier.width(177.px).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ValueCell(text: String, color: Color, modifier: Modifier, label: String? = null, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(onClick = onClick).semantics(mergeDescendants = true) { if (label != null) contentDescription = "$label, $text" }, contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 10.5.sp, maxLines = 1, softWrap = false)
    }
}

internal class SnapOther(val x: Float, val y: Float, val bounds: FloatArray?)

internal class Snap(val distance: Float, val guide: SnapGuide)

internal fun Scene.snapAxesFor(layer: Layer, timeMs: Int, mine: FloatArray?, others: List<SnapOther>): Pair<SnapAxis, SnapAxis> {
    val world = place(layer, timeMs)
    val parent = chain(layer).getOrNull(1)?.let { place(it, timeMs) }
    val tilted = chain(layer).drop(1).any { up -> up.at(timeMs, fps).tilt.any { it % 360f != 0f } }
    if (parent != null && (parent.rot % 360f != 0f || tilted)) return SnapAxis(emptyList()) to SnapAxis(emptyList())
    return snapAxes(width.toFloat(), height.toFloat(), floatArrayOf(world.x, world.y), mine, others, parent?.sx ?: 1f, parent?.sy ?: 1f)
}

internal fun snapAxes(width: Float, height: Float, at: FloatArray, mine: FloatArray?, others: List<SnapOther>, sx: Float, sy: Float): Pair<SnapAxis, SnapAxis> {
    val xs = arrayListOf(Snap((width / 2 - at[0]) / sx, SnapGuide(true, width / 2, true)))
    val ys = arrayListOf(Snap((height / 2 - at[1]) / sy, SnapGuide(false, height / 2, true)))
    for (o in others) {
        xs += Snap((o.x - at[0]) / sx, SnapGuide(true, o.x, false))
        ys += Snap((o.y - at[1]) / sy, SnapGuide(false, o.y, false))
        val b = o.bounds ?: continue
        if (mine == null) continue
        for (axis in 0..1) for (theirs in intArrayOf(axis, axis + 2)) for (own in intArrayOf(axis, axis + 2)) {
            (if (axis == 0) xs else ys) += Snap((b[theirs] - mine[own]) / (if (axis == 0) sx else sy), SnapGuide(axis == 0, b[theirs], false))
        }
    }
    return SnapAxis(xs) to SnapAxis(ys)
}

internal class SnapAxis(snaps: List<Snap>, private val threshold: Float = 25f) {
    private val stops: List<Pair<Float, List<SnapGuide>>> = snaps.filter { it.distance.isFinite() }.sortedBy { it.distance }
        .fold(mutableListOf<Pair<Float, MutableList<SnapGuide>>>()) { out, snap ->
            val last = out.lastOrNull()
            if (last != null && snap.distance - last.first <= SAME) last.second += snap.guide else out += snap.distance to mutableListOf(snap.guide)
            out
        }

    fun map(travel: Float): Float {
        if (travel.isNaN()) return 0f
        val sign = if (travel < 0) -1f else 1f
        val far = abs(travel)
        var held = 0f
        stops.firstOrNull { abs(it.first) <= SAME }?.let { (at, _) ->
            if (far <= threshold / 2) return at
            held = threshold / 2
        }
        val ahead = stops.filter { it.first * sign > SAME }.map { it.first }.sortedBy { abs(it) }
        for (at in ahead) {
            val reached = abs(at) + held
            if (far < reached) break
            if (far <= reached + threshold) return at
            held += threshold
        }
        return sign * (far - held)
    }

    fun guidesAt(distance: Float): List<SnapGuide> = stops.firstOrNull { abs(it.first - distance) <= SAME }?.second ?: emptyList()

    private companion object {
        const val SAME = 0.01f
    }
}

@Composable
internal fun TrackpadCorners() {
    Canvas(Modifier.fillMaxSize()) {
        val far = 718.5f
        val near = 24.5f
        val bottom = 537.5f
        corner(near, near, 1f, 1f)
        corner(far, near, -1f, 1f)
        corner(near, bottom, 1f, -1f)
        corner(far, bottom, -1f, -1f)
    }
}

private fun DrawScope.corner(xPx: Float, yPx: Float, dx: Float, dy: Float) {
    val x = xPx.px.toPx()
    val y = yPx.px.toPx()
    val length = 25.px.toPx()
    val radius = 9.px.toPx()
    val mark = Path().apply {
        moveTo(x, y + dy * length)
        lineTo(x, y + dy * radius)
        quadraticTo(x, y, x + dx * radius, y)
        lineTo(x + dx * length, y)
    }
    drawPath(mark, PanelCaption, style = Stroke(3.px.toPx(), cap = StrokeCap.Round))
}

@Composable
private fun RotationTab(state: EditorState, layer: Layer) {
    val angle = layer.rotation
    val timing = remember { LongArray(2) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.fillMaxSize().holdAndDoubleTap(timing) { state.editSelected { it.withRotation(0f) } }.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val centre = Offset(size.width / 2f, size.height / 2f)
                var last = dialAngle(down.position - centre)
                var fine: Boolean? = null
                drag(down.id) { change ->
                    if (fine == null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) fine = timing.heldBeforeMoving(change.uptimeMillis, viewConfiguration.longPressTimeoutMillis)
                    val now = dialAngle(change.position - centre)
                    var delta = now - last
                    if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f
                    last = now
                    change.consume()
                    if (fine == true) delta *= FINE_FACTOR
                    if (delta != 0f) state.editSelected(key = "rotate") { l -> l.withRotation(l.rotation + delta) }
                }
                state.endGesture()
                if (fine != null) rulerTip(context)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = 227.px.toPx()
            drawCircle(Am.Well, radius, centre, style = Stroke(7.px.toPx()))
            drawArc(
                PanelGreen, startAngle = 0f, sweepAngle = -(angle % 360f), useCenter = false,
                topLeft = centre - Offset(radius, radius), size = Size(radius * 2, radius * 2), style = Stroke(6.px.toPx()),
            )
            val radians = Math.toRadians(angle.toDouble())
            val knob = centre + Offset((radius * cos(radians)).toFloat(), (-radius * sin(radians)).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.25f), 49.px.toPx(), knob + Offset(0f, 2.px.toPx()))
            drawCircle(Am.OnDark, 49.px.toPx(), knob)
        }
        Box(
            Modifier.align(Alignment.Center).height(140.px).widthIn(min = 267.px).clip(PanelShape).background(Am.Field).clickable {
                state.keypad = KeypadRequest(listOf(KeypadField(ROTATION_LABEL, angle, "\u00B0")), 0) { v ->
                    state.editSelected { it.withRotation(v[0]) }
                }
            }.padding(horizontal = 24.px),
            contentAlignment = Alignment.Center,
        ) {
            Text(formatFixed(angle, 1).removeSuffix(".0") + "º", color = Am.Accent, fontSize = 32.5.sp, maxLines = 1, softWrap = false)
        }
    }
}

private const val ROTATION_LABEL = ""

private fun dialAngle(fromCentre: Offset): Float = Math.toDegrees(atan2(-fromCentre.y, fromCentre.x).toDouble()).toFloat()

internal class Sides(val x: Float, val y: Float, val upright: Boolean)

internal fun sidesOf(q: FloatArray): Sides {
    val dx = q[2] - q[0]
    val dy = q[3] - q[1]
    return Sides(hypot(dx, dy), hypot(q[6] - q[0], q[7] - q[1]), abs(dy) > abs(dx) * 1.001f)
}

private class BoxAt(val sides: Sides, val sx: Float, val sy: Float)

@Composable
private fun SizeTab(state: EditorState, layer: Layer) {
    val linked = layer.scaleLinked
    var active by remember { mutableIntStateOf(0) }
    val measured by produceState<BoxAt?>(null, layer.node, state.sampleMs) {
        value = state.engine.layerQuad(layer.id, state.sampleMs)?.let { q -> BoxAt(sidesOf(q), layer.scale[0], layer.scale[1]) }
    }
    val upright = measured?.sides?.upright == true
    val box = measured?.let { m ->
        val x = m.sides.x * if (m.sx != 0f) abs(layer.scale[0] / m.sx) else 1f
        val y = m.sides.y * if (m.sy != 0f) abs(layer.scale[1] / m.sy) else 1f
        if (upright) y to x else x to y
    }
    val (boxW, boxH) = box ?: (0f to 0f)

    fun stretch(factor: Float, isWidth: Boolean, key: Any? = null) = state.editSelected(key) { l ->
        val s = l.scale
        val first = isWidth != upright
        l.withTransform("scale", if (first || linked) s[0] * factor else s[0], if (!first || linked) s[1] * factor else s[1])
    }
    val drag = remember { floatArrayOf(1f, 1f, 0f) }
    fun resize(isWidth: Boolean, key: Any) = state.editSelected(key) { l ->
        val first = isWidth != upright
        val ref = if (linked) maxOf(abs(drag[0]), abs(drag[1])) else abs(drag[if (first) 0 else 1])
        val factor = RulerMath.scale(ref, drag[2]) / ref.coerceAtLeast(1e-4f)
        val s = l.scale
        l.withTransform("scale", if (first || linked) drag[0] * factor else s[0], if (!first || linked) drag[1] * factor else s[1])
    }
    val widthLabel = stringResource(R.string.transform_width)
    val heightLabel = stringResource(R.string.transform_height)
    fun openKeypad(isWidth: Boolean) {
        active = if (isWidth) 0 else 1
        if (box == null) return
        val old = if (isWidth) boxW else boxH
        val field = KeypadField(if (isWidth) widthLabel else heightLabel, old)
        state.keypad = KeypadRequest(listOf(field), 0) { v -> if (old > 0f && v[0] > 0f) stretch(v[0] / old, isWidth) }
    }
    fun colour(which: Int) = if (linked || active == which) Am.Accent else Am.OnDark

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 42.px)) {
            Row {
                NumberBox(formatFixed(boxW, 1), colour(0), Modifier.width(177.px).height(70.px)) { openKeypad(true) }
                Spacer(Modifier.width(14.px))
                Box(
                    Modifier.size(70.px).clip(PanelShape).background(Am.Inactive).clickable { state.editSelected { it.withScaleLinked(!it.scaleLinked) } },
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon(if (linked) PanelIcons.Link else PanelIcons.Unlink, Am.OnDark, size = 58.px, description = stringResource(R.string.a11y_keep_proportions))
                }
                Spacer(Modifier.width(14.px))
                NumberBox(formatFixed(boxH, 1), colour(1), Modifier.width(177.px).height(70.px)) { openKeypad(false) }
            }
            Row(Modifier.height(23.px)) {
                Caption(widthLabel, Modifier.width(177.px).fillMaxHeight())
                Spacer(Modifier.width(98.px))
                Caption(heightLabel, Modifier.width(177.px).fillMaxHeight())
            }
        }
        for (which in if (linked) 0..0 else 0..1) {
            key(linked, which) {
                RulerSpinner(
                    colour(which),
                    onDrag = { dx -> drag[2] += dx; resize(which == 0, "size-$which") },
                    onEnd = { state.endGesture() },
                    modifier = when {
                        linked -> Modifier.padding(top = 160.px).fillMaxWidth().height(378.px)
                        which == 0 -> Modifier.padding(top = 137.px).fillMaxWidth().height(194.px)
                        else -> Modifier.padding(top = 345.px).fillMaxWidth().height(193.px)
                    },
                    onStart = {
                        active = which
                        state.selected?.scale?.let { drag[0] = it[0]; drag[1] = it[1] }
                        drag[2] = 0f
                    },
                    onReset = {
                        state.editSelected { l ->
                            val s = l.scale
                            l.withTransform("scale", if (linked || which == 0) 1f else s[0], if (linked || which == 1) 1f else s[1])
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SkewTab(state: EditorState, layer: Layer) {
    var active by remember { mutableIntStateOf(0) }
    val skew = layer.skew
    val xSkew = stringResource(R.string.transform_x_skew)
    val ySkew = stringResource(R.string.transform_y_skew)
    fun openKeypad(which: Int) {
        active = which
        val fields = listOf(KeypadField(xSkew, skew[0], "\u00B0"), KeypadField(ySkew, skew[1], "\u00B0"))
        state.keypad = KeypadRequest(fields, which) { v -> state.editSelected { it.withSkew(v[0], v[1]) } }
    }
    fun colour(which: Int) = if (active == which) Am.Accent else Am.OnDark

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 42.px)) {
            Row {
                NumberBox(formatFixed(skew[0], 1) + "º", colour(0), Modifier.width(177.px).height(70.px)) { openKeypad(0) }
                Spacer(Modifier.width(14.px))
                NumberBox(formatFixed(skew[1], 1) + "º", colour(1), Modifier.width(177.px).height(70.px)) { openKeypad(1) }
            }
            Row(Modifier.height(23.px)) {
                Caption(xSkew, Modifier.width(177.px).fillMaxHeight())
                Spacer(Modifier.width(14.px))
                Caption(ySkew, Modifier.width(177.px).fillMaxHeight())
            }
        }
        for (which in 0..1) {
            RulerSpinner(
                colour(which),
                onDrag = { dx ->
                    val d = dx * DEGREES_PER_DP
                    state.editSelected(key = "skew-$which") { l ->
                        val s = l.skew
                        l.withSkew(if (which == 0) s[0] + d else s[0], if (which == 1) s[1] + d else s[1])
                    }
                },
                onEnd = { state.endGesture() },
                modifier = Modifier.padding(top = (if (which == 0) 137 else 345).px).fillMaxWidth().height((if (which == 0) 194 else 193).px),
                onStart = { active = which },
                onReset = { state.editSelected { l -> l.skew.let { s -> l.withSkew(if (which == 0) 0f else s[0], if (which == 1) 0f else s[1]) } } },
            )
        }
    }
}

private const val DEGREES_PER_DP = 0.25f

@Composable
private fun TiltTab(state: EditorState, layer: Layer, turn: Turn?) {
    if (turn != null) TurnHook(state, turn)
    var active by remember { mutableIntStateOf(0) }
    val tilt = layer.tilt
    val labels = listOf(stringResource(R.string.tilt_x_rotation), stringResource(R.string.tilt_y_rotation))
    fun openKeypad(which: Int) {
        active = which
        state.keypad = KeypadRequest(labels.mapIndexed { i, label -> KeypadField(label, tilt[i], "\u00B0") }, which) { v -> state.editSelected { it.withTilt(v[0], v[1]) } }
    }
    fun colour(which: Int) = if (active == which) Am.Accent else Am.OnDark

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 42.px)) {
            Row {
                NumberBox(formatFixed(tilt[0], 1) + "º", colour(0), Modifier.width(177.px).height(70.px)) { openKeypad(0) }
                Spacer(Modifier.width(14.px))
                NumberBox(formatFixed(tilt[1], 1) + "º", colour(1), Modifier.width(177.px).height(70.px)) { openKeypad(1) }
            }
            Row(Modifier.height(23.px)) {
                Caption(labels[0], Modifier.width(177.px).fillMaxHeight())
                Spacer(Modifier.width(14.px))
                Caption(labels[1], Modifier.width(177.px).fillMaxHeight())
            }
        }
        for (which in 0..1) {
            RulerSpinner(
                colour(which),
                onDrag = { dx ->
                    val d = dx * TILT_PER_DP
                    state.editSelected(key = "tilt-$which") { l ->
                        val t = l.tilt
                        l.withTilt(if (which == 0) t[0] + d else t[0], if (which == 1) t[1] + d else t[1])
                    }
                },
                onEnd = { state.endGesture() },
                modifier = Modifier.padding(top = (if (which == 0) 137 else 345).px).fillMaxWidth().height((if (which == 0) 194 else 193).px),
                onStart = { active = which },
                onReset = { state.editSelected { l -> l.tilt.let { t -> l.withTilt(if (which == 0) 0f else t[0], if (which == 1) 0f else t[1]) } } },
            )
        }
    }
}

private const val TILT_PER_DP = 0.5f

internal class PivotFrame(val world: android.graphics.Matrix, val box: FloatArray?) {
    fun local(at: Pt, v: Pt): Pt? {
        val o = world.map(at)
        val a = world.map(Pt(at.x + 1f, at.y)) - o
        val b = world.map(Pt(at.x, at.y + 1f)) - o
        val det = a.x * b.y - a.y * b.x
        if (!(abs(det) > 1e-6f) || !det.isFinite()) return null
        return Pt((v.x * b.y - v.y * b.x) / det, (a.x * v.y - a.y * v.x) / det)
    }
}

internal fun pivotFrame(matrix: FloatArray, quad: FloatArray?): PivotFrame {
    val world = android.graphics.Matrix().apply { setValues(matrix.copyOfRange(0, 9)) }
    val inverse = android.graphics.Matrix()
    if (quad == null || !world.invert(inverse)) return PivotFrame(world, null)
    val local = quad.copyOf().also { inverse.mapPoints(it) }
    val xs = floatArrayOf(local[0], local[2], local[4], local[6])
    val ys = floatArrayOf(local[1], local[3], local[5], local[7])
    return PivotFrame(world, floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()))
}

@Composable
private fun PivotTab(state: EditorState, layer: Layer) {
    val at = layer.pivot
    val frame by produceState<PivotFrame?>(null, layer.id, layer.node, state.timeMs) {
        val m = state.engine.layerMatrix(layer.id, state.timeMs)
        value = m?.let { pivotFrame(it, state.engine.layerQuad(layer.id, state.timeMs)) }
    }
    val now by rememberUpdatedState(layer)
    val seen by rememberUpdatedState(frame)
    SideEffect {
        state.layerPoints = if (!layer.activeAt(state.sampleMs)) null else LayerPoints(listOf(Pt(at[0], at[1])), 0, pivot = true) { _, p -> state.setPivot(p.x, p.y, "pivot-drag") }
    }
    DisposableEffect(Unit) { onDispose { state.layerPoints = null } }
    fun openKeypad(active: Int) {
        val fields = listOf(KeypadField("X", at[0], min = -PIVOT_LIMIT, max = PIVOT_LIMIT), KeypadField("Y", at[1], min = -PIVOT_LIMIT, max = PIVOT_LIMIT))
        state.keypad = KeypadRequest(fields, active) { v -> state.setPivot(v[0], v[1]) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val timing = remember { LongArray(2) }
    Box(
        Modifier.fillMaxSize().holdAndDoubleTap(timing, null).pointerInput(state) {
            var origin = Pt(0f, 0f)
            var travel = Offset.Zero
            var scale = 1f
            fun done() { state.endGesture(); rulerTip(context) }
            detectDragGestures(
                onDragStart = {
                    scale = if (timing.heldBeforeMoving(android.os.SystemClock.uptimeMillis(), viewConfiguration.longPressTimeoutMillis)) FINE_FACTOR else 1f
                    origin = now.pivot.let { Pt(it[0], it[1]) }
                    travel = Offset.Zero
                },
                onDragEnd = ::done, onDragCancel = ::done,
            ) { change, drag ->
                change.consume()
                travel += drag / density * scale
                val local = seen?.local(origin, Pt(travel.x, travel.y)) ?: return@detectDragGestures
                state.setPivot(origin.x + local.x, origin.y + local.y, "pivot-pad")
            }
        },
    ) {
        TrackpadCorners()
        TrackpadHint(stringResource(R.string.drag_here_to_move_the_pivot), Modifier.align(Alignment.Center).padding(bottom = 150.px))
        Column(Modifier.align(Alignment.TopCenter).padding(top = 17.px)) {
            Row(Modifier.width(354.px).height(70.px).clip(PanelShape).background(Am.Field)) {
                ValueCell(formatFixed(at[0], 2), Am.Accent, Modifier.weight(1f), "X") { openKeypad(0) }
                ValueCell(formatFixed(at[1], 2), Am.Accent, Modifier.weight(1f), "Y") { openKeypad(1) }
            }
            Row(Modifier.height(23.px)) {
                Caption("X", Modifier.width(177.px).fillMaxHeight())
                Caption("Y", Modifier.width(177.px).fillMaxHeight())
            }
        }
        val box = frame?.box
        val names = PivotCells.map { stringResource(it) }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.px)) {
            for (row in 0..2) {
                Row {
                    for (col in 0..2) {
                        val x = box?.let { it[0] + (it[2] - it[0]) * col / 2f }
                        val y = box?.let { it[1] + (it[3] - it[1]) * row / 2f }
                        val here = x != null && y != null && abs(x - at[0]) < 0.01f && abs(y - at[1]) < 0.01f
                        Box(
                            Modifier.size(72.px).then(if (x != null && y != null) Modifier.clickable(role = Role.Button) { state.setPivot(x, y) } else Modifier.dimmed())
                                .semantics { contentDescription = names[row * 3 + col]; selected = here }.padding(5.px).clip(PanelShape).background(if (here) PanelDark else Am.Field),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(14.px).clip(androidx.compose.foundation.shape.CircleShape).background(if (here) Am.Accent else Am.OnDark))
                        }
                    }
                }
            }
        }
    }
}

private val PivotCells = listOf(
    R.string.a11y_pivot_top_left, R.string.a11y_pivot_top, R.string.a11y_pivot_top_right,
    R.string.a11y_pivot_left, R.string.a11y_pivot_center, R.string.a11y_pivot_right,
    R.string.a11y_pivot_bottom_left, R.string.a11y_pivot_bottom, R.string.a11y_pivot_bottom_right,
)

@Composable
private fun TrackpadHint(text: String, modifier: Modifier) {
    Text(
        text, modifier.padding(horizontal = 8.dp), color = PanelCaption, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 17.sp,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, 15.sp, 0.5.sp),
    )
}
