package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskSp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiKind
import app.absentmotion.ai.AiTools
import app.absentmotion.ai.aiMarks
import app.absentmotion.ai.withoutAiMark
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.applySpeedCurve
import app.absentmotion.model.CURVE_FASTEST
import app.absentmotion.model.CURVE_MOST_POINTS
import app.absentmotion.model.CURVE_SLOWEST
import app.absentmotion.model.CurvePoint
import app.absentmotion.model.FASTEST_SPEED
import app.absentmotion.model.Layer
import app.absentmotion.model.SLOWEST_SPEED
import app.absentmotion.model.SpeedRamp
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.curve
import app.absentmotion.model.curveHeight
import app.absentmotion.model.curveSpan
import app.absentmotion.model.curveSpeed
import app.absentmotion.model.flatCurve
import app.absentmotion.model.lengthWithCurve
import app.absentmotion.model.moved
import app.absentmotion.model.outline
import app.absentmotion.model.ramp
import app.absentmotion.model.snappedToOne
import app.absentmotion.model.speedAt
import app.absentmotion.model.speedCurve
import app.absentmotion.model.steppedSpeed
import app.absentmotion.model.withPointNear
import app.absentmotion.model.without
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.rememberHaptic
import app.absentmotion.ui.editor.videoStripPicture
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import kotlin.math.ln
import kotlin.math.roundToInt

private val Flat = flatCurve(1f)
private const val SNAP_DP = 3f

@Composable
internal fun CurvePresets(state: EditorState, layer: Layer, modifier: Modifier, edit: (List<CurvePoint>) -> Unit) {
    val curve = remember(layer.node) { layer.speedCurve() }
    val ramp = curve?.ramp()
    val custom = curve != null && ramp == null
    Column(modifier.padding(top = 8.dp), Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(100.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp), Arrangement.spacedBy(8.dp)) {
            CurveTile(stringResource(R.string.none), null, curve == null) { if (curve != null) state.applySpeedCurve(null) }
            CurveTile(stringResource(R.string.speed_curve_custom), if (custom) curve else Flat, custom) { edit(Flat) }
            for (preset in SpeedRamp.entries) {
                val chosen = ramp == preset
                CurveTile(stringResource(preset.label), preset.curve, chosen) { if (chosen) edit(preset.curve) else state.applySpeedCurve(preset.curve) }
            }
        }
        Text(stringResource(R.string.speed_curve_edit_hint), Modifier.padding(horizontal = 15.dp), color = PanelCaption, fontSize = deskSp(11f), maxLines = 2)
    }
}

@Composable
private fun CurveTile(label: String, points: List<CurvePoint>?, chosen: Boolean, onClick: () -> Unit) {
    val outline = remember(points) { points?.outline() }
    val tint = if (chosen) Am.Accent else Am.OnDark
    val guide = PanelCaption
    Column(
        Modifier.width(66.dp).fillMaxHeight().clip(PanelShape).semantics(mergeDescendants = true) { selected = chosen }
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(PanelShape).background(if (chosen) PanelDark else Am.Field), Alignment.Center) {
            if (outline == null) PanelIcon(PanelIcons.FillNone, tint, size = 22.dp)
            else Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 9.dp)) {
                val g = CurveGeo(size.width, size.height, 0f)
                drawLine(guide, Offset(0f, g.y(1f)), Offset(size.width, g.y(1f)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                drawOutline(outline, tint, 2.dp.toPx(), g)
            }
            if (chosen && points != null) Icon(ChromeIcons.Pencil, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(11.dp), tint = Am.Accent)
        }
        Text(label, Modifier.padding(top = 4.dp, bottom = 2.dp).padding(horizontal = 2.dp), color = animatedColor(tint), maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(8.sp, 11.sp, 0.5.sp))
    }
}

@Composable
internal fun SpeedCurveEditor(state: EditorState, layer: Layer, ai: AiTools, base: List<CurvePoint>, close: () -> Unit, modifier: Modifier) {
    val timing = remember(layer.node) { VideoTiming(layer) }
    val span = remember(layer.node) { layer.curveSpan(timing) }
    val stored = remember(layer.node) { layer.speedCurve() ?: flatCurve(timing.speed(0.0)) }
    var dragged by remember(layer.id) { mutableStateOf<List<CurvePoint>?>(null) }
    var picked by remember(layer.id) { mutableIntStateOf(-1) }
    val points = dragged ?: stored
    val chosen = picked.takeIf { it in points.indices }
    val source = span.endInclusive - span.start
    val playhead = ((timing.mediaMs((state.sampleMs - layer.startTime).toDouble()) - span.start) / source).toFloat()
        .takeIf { state.sampleMs in layer.startTime..layer.endTime && it.isFinite() }?.coerceIn(0f, 1f)
    val length = layer.endTime - layer.startTime
    val after = remember(dragged) { dragged?.let(layer::lengthWithCurve) }
    val speedLabel = stringResource(R.string.a11y_speed)
    fun scrub(u: Float) {
        state.playing = false
        val ms = layer.startTime + timing.localMs(span.start + u * source).roundToInt()
        state.frame = state.scene.frameAt(ms).coerceIn(state.scene.frameAt(layer.startTime), state.scene.frameAt(layer.endTime))
    }
    Column(modifier.padding(horizontal = 10.dp, vertical = 6.dp), Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp, 30.dp).clip(PanelShape).clickable(role = Role.Button, onClick = close), Alignment.Center) {
                PanelIcon(PanelIcons.Back, Am.OnDark, size = 20.dp, description = stringResource(R.string.topbar_back))
            }
            Text(stringResource(points.ramp()?.label ?: R.string.speed_curve_custom), Modifier.padding(start = 2.dp).width(84.dp), color = Am.OnDark,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f).fillMaxHeight(), Alignment.Center) {
                Text(if (after == null) stringResource(R.string.speed_curve_seconds, seconds(length)) else stringResource(R.string.speed_curve_length, seconds(length), seconds(after)),
                    color = if (after == null) PanelCaption else Am.Accent, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            RampChip(stringResource(R.string.speed_curve_reset), points != base, modifier = Modifier.height(28.dp)) { picked = -1; state.applySpeedCurve(base) }
        }
        CurveGraph(state, layer, points, chosen, playhead, Modifier.fillMaxWidth().weight(1f),
            pick = { i, u -> picked = i ?: -1; scrub(u) }, scrub = ::scrub, move = { dragged = it },
            drop = { result -> if (result != stored) state.applySpeedCurve(result); dragged = null })
        Row(Modifier.fillMaxWidth().height(34.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            SpeedValue(chosen?.let { points[it].speed } ?: points.speedAt(playhead ?: 0f), Modifier.width(84.dp).fillMaxHeight(), chosen?.let { i ->
                { state.askNumber(speedLabel, points[i].speed, "x", SLOWEST_SPEED, FASTEST_SPEED) { v -> state.applySpeedCurve(points.moved(i, points[i].u, steppedSpeed(v))) } }
            })
            if (chosen != null) RampChip(stringResource(R.string.speed_curve_delete_point), chosen in 1 until points.lastIndex, modifier = Modifier.weight(1f)) {
                picked = -1
                state.applySpeedCurve(points.without(chosen))
            } else RampChip(stringResource(R.string.speed_curve_add_point), points.size < CURVE_MOST_POINTS, modifier = Modifier.weight(1f)) {
                points.withPointNear(playhead ?: -1f)?.let { (next, index) -> state.applySpeedCurve(next); picked = index }
            }
            val canSmooth = layer.mediaFill?.video == true && ai.running == null
            Text(stringResource(R.string.smooth_clip), Modifier.alpha(if (canSmooth) 1f else 0.35f), color = Am.OnDark, fontSize = 12.sp, maxLines = 1, softWrap = false)
            ThinSwitch(layer.aiMarks.any { it.kind == AiKind.Smooth }, enabled = canSmooth) { on ->
                state.playing = false
                if (on) ai.apply(AiAction.Smooth) else state.edit { it.withoutAiMark(layer.id, AiKind.Smooth) }
            }
        }
    }
}

@Composable
private fun CurveGraph(
    state: EditorState, layer: Layer, points: List<CurvePoint>, chosen: Int?, playhead: Float?, modifier: Modifier,
    pick: (Int?, Float) -> Unit, scrub: (Float) -> Unit, move: (List<CurvePoint>?) -> Unit, drop: (List<CurvePoint>) -> Unit,
) {
    val live by rememberUpdatedState(points)
    val onPick by rememberUpdatedState(pick)
    val onScrub by rememberUpdatedState(scrub)
    val onMove by rememberUpdatedState(move)
    val onDrop by rememberUpdatedState(drop)
    val haptic = rememberHaptic(state)
    val outline = remember(points) { points.outline() }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val labelStyle = TextStyle(color = PanelCaption, fontSize = deskSp(9f))
    val ink = Am.OnDark
    val accent = Am.Accent
    val guide = PanelCaption
    val graphLabel = stringResource(R.string.speed_curve_graph)
    val previous = stringResource(R.string.speed_curve_previous_point)
    val next = stringResource(R.string.speed_curve_next_point)
    BoxWithConstraints(modifier.clip(PanelShape).background(PanelDark)) {
        CurveStrip(state, layer, (maxWidth.value / (maxHeight.value * 0.6f).coerceAtLeast(1f)).roundToInt().coerceIn(3, 12), Modifier.matchParentSize())
        Canvas(
            Modifier.matchParentSize().semantics {
                contentDescription = graphLabel
                stateDescription = chosen?.let { formatFixed(points[it].speed, 2) + "x" }.orEmpty()
                customActions = listOf(
                    CustomAccessibilityAction(previous) { val i = ((chosen ?: points.size) - 1).coerceAtLeast(0); onPick(i, points[i].u); true },
                    CustomAccessibilityAction(next) { val i = ((chosen ?: -1) + 1).coerceAtMost(points.lastIndex); onPick(i, points[i].u); true },
                )
            }.pointerInput(layer.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val g = CurveGeo(size.width.toFloat(), size.height.toFloat(), 10.dp.toPx())
                    val start = live
                    val reach = 22.dp.toPx()
                    val near = start.indices.minByOrNull { (g.at(start[it]) - down.position).getDistance() }
                        ?.takeIf { (g.at(start[it]) - down.position).getDistance() <= reach }
                    onPick(near, near?.let { start[it].u } ?: g.u(down.position.x))
                    val snapReach = SNAP_DP.dp.toPx() / g.span * ln(CURVE_FASTEST / CURVE_SLOWEST)
                    var snapped = near != null && start[near].speed == 1f
                    var moved = false
                    var result: List<CurvePoint>? = null
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        if (!moved && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                        moved = true
                        if (near == null) {
                            onScrub(g.u(change.position.x))
                            continue
                        }
                        val target = g.at(start[near]) + (change.position - down.position)
                        val speed = steppedSpeed(snappedToOne(g.speed(target.y), snapReach)).coerceIn(CURVE_SLOWEST, CURVE_FASTEST)
                        if (speed == 1f && !snapped) haptic(HapticFeedbackType.SegmentTick)
                        snapped = speed == 1f
                        result = start.moved(near, g.u(target.x), speed)
                        onMove(result)
                    }
                    if (result != null) onDrop(result) else onMove(null)
                }
            },
        ) {
            val g = CurveGeo(size.width, size.height, 10.dp.toPx())
            for (speed in listOf(CURVE_FASTEST, 1f, CURVE_SLOWEST)) {
                val y = g.y(speed)
                drawLine(if (speed == 1f) ink.copy(alpha = 0.6f) else guide.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = if (speed == 1f) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
                val text = measurer.measure(formatPlain(speed) + "x", labelStyle)
                drawText(text, topLeft = Offset(4.dp.toPx(), if (speed == CURVE_SLOWEST) y - text.size.height - 1.dp.toPx() else y + 1.dp.toPx()))
            }
            if (playhead != null) drawLine(ink, Offset(g.x(playhead), 0f), Offset(g.x(playhead), size.height), 1.5.dp.toPx())
            drawOutline(outline, accent, 2.5.dp.toPx(), g)
            points.forEachIndexed { i, p ->
                val at = g.at(p)
                if (i == chosen) {
                    drawCircle(ink, 8.dp.toPx(), at)
                    drawCircle(accent, 6.dp.toPx(), at)
                } else drawCircle(ink, 5.dp.toPx(), at)
            }
        }
    }
}

@Composable
private fun CurveStrip(state: EditorState, layer: Layer, tiles: Int, modifier: Modifier) {
    val ms = remember(layer.node) { layer.curveSpan().let { (it.endInclusive - it.start).roundToInt().coerceAtLeast(1) } }
    val flat = remember(layer.node) { layer.copy(node = layer.node.withChild("speedMap", null).with("speed", null).with("endTime", "${layer.startTime + ms}")) }
    val strip = videoStripPicture(flat, state.scene, flat.startTime.toFloat(), flat.endTime.toFloat(), ms.toFloat() / tiles)
    val shade = PanelDark
    Canvas(modifier) {
        val w = size.width / tiles
        strip?.frames?.forEach { (time, image) ->
            val i = ((time - flat.startTime) * tiles / ms.toFloat()).roundToInt()
            if (i in 0 until tiles) drawCover(image, i * w, (i + 1) * w, size.height)
        }
        drawRect(shade.copy(alpha = 0.6f))
    }
}

private fun DrawScope.drawCover(image: ImageBitmap, left: Float, right: Float, height: Float) {
    val width = right - left
    val scale = maxOf(width / image.width, height / image.height)
    val w = (width / scale).roundToInt().coerceIn(1, image.width)
    val h = (height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(image, IntOffset((image.width - w) / 2, (image.height - h) / 2), IntSize(w, h),
        IntOffset(left.roundToInt(), 0), IntSize(right.roundToInt() - left.roundToInt(), height.roundToInt()))
}

private fun DrawScope.drawOutline(outline: List<Pair<Float, Float>>, color: Color, width: Float, g: CurveGeo) {
    val path = Path()
    outline.forEachIndexed { i, (u, speed) -> if (i == 0) path.moveTo(g.x(u), g.y(speed)) else path.lineTo(g.x(u), g.y(speed)) }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private class CurveGeo(width: Float, height: Float, pad: Float) {
    private val left = pad
    private val right = width - pad
    private val top = pad
    private val bottom = height - pad
    val span = (bottom - top).coerceAtLeast(1f)
    fun x(u: Float) = left + (right - left) * u
    fun y(speed: Float) = bottom - span * curveHeight(speed)
    fun u(x: Float) = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
    fun speed(y: Float) = curveSpeed((bottom - y) / span)
    fun at(p: CurvePoint) = Offset(x(p.u), y(p.speed))
}

private fun seconds(ms: Int) = formatFixed(ms / 1000f, 1)
