package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiKind
import app.absentmotion.ai.AiTools
import app.absentmotion.ai.aiMarks
import app.absentmotion.ai.reverseMark
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.freezeFrame
import app.absentmotion.editor.retimeVideo
import app.absentmotion.editor.setVideoSpeed
import app.absentmotion.model.CurvePoint
import app.absentmotion.model.FASTEST_SPEED
import app.absentmotion.model.Layer
import app.absentmotion.model.SLOWEST_SPEED
import app.absentmotion.model.SpeedTicks
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.normalSpeed
import app.absentmotion.model.rulerTick
import app.absentmotion.model.steppedSpeed
import app.absentmotion.model.tickSpeed
import app.absentmotion.platform.BackHandler
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.effects.AiProgressDialog
import app.absentmotion.ui.editor.effects.AiSmoothDialog
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.fadeBackground
import kotlin.math.roundToInt

@Composable
fun SpeedPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember(state, context, scope) { AiTools(context.applicationContext, state, scope) }
    var curveTab by remember(layer.id) { mutableStateOf(layer.node.child("speedMap")?.isKeyframed == true) }
    var editing by remember(layer.id) { mutableStateOf<List<CurvePoint>?>(null) }
    BackHandler(editing != null) { editing = null }
    Swap(editing != null, modifier, rise = 0.dp) {
        val base = editing
        if (base != null) SpeedCurveEditor(state, layer, ai, base, { editing = null }, Modifier.fillMaxSize())
        else Column(Modifier.fillMaxSize()) {
            SpeedTabs(curveTab, Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)) { curveTab = it }
            Swap(curveTab, Modifier.fillMaxWidth().weight(1f), rise = 0.dp) {
                if (curveTab) CurvePresets(state, layer, Modifier.fillMaxSize()) { editing = it }
                else NormalSpeed(state, layer, ai, Modifier.fillMaxSize())
            }
        }
    }
    AiSmoothDialog(ai, state.scene)
    AiProgressDialog(ai)
}

@Composable
private fun SpeedTabs(curve: Boolean, modifier: Modifier, choose: (Boolean) -> Unit) {
    Row(modifier.height(28.dp).clip(PanelShape).background(Am.Field)) {
        for (option in listOf(false, true)) {
            val chosen = option == curve
            Box(
                Modifier.width(92.dp).fillMaxHeight().clip(PanelShape).fadeBackground(chosen, PanelDark)
                    .semantics { selected = chosen }.clickable(role = Role.Tab) { choose(option) },
                Alignment.Center,
            ) {
                Text(stringResource(if (option) R.string.speed_curve else R.string.speed_normal), color = animatedColor(if (chosen) Am.Accent else Am.OnDark),
                    fontSize = 12.5.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun NormalSpeed(state: EditorState, layer: Layer, ai: AiTools, modifier: Modifier) {
    val speed = remember(layer.node) { layer.normalSpeed().coerceIn(SLOWEST_SPEED, FASTEST_SPEED) }
    val inside = state.sampleMs in (layer.startTime + 1) until layer.endTime
    val before = state.sampleMs < layer.startTime
    val past = state.sampleMs > layer.endTime
    val usable = listOf(before, inside, inside, past)
    val held = remember { arrayOfNulls<Layer>(1) }
    val speedLabel = stringResource(R.string.a11y_speed)
    val secondsLabel = stringResource(R.string.speed_seconds)
    val video = layer.mediaFill?.video == true && ai.running == null
    Column(modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
        RetimeButtons(state, usable, Modifier.padding(horizontal = 15.dp).fillMaxWidth().height(36.dp))
        Row(Modifier.padding(horizontal = 15.dp).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            EffectRuler(rulerTick(speed).toFloat(), 1f, 1f, SpeedTicks.size.toFloat(), true,
                { held[0] = state.selected }, { tick -> held[0]?.let { state.setVideoSpeed(tickSpeed(tick.roundToInt()), it) } }, state::endGesture,
                Modifier.weight(1f).fillMaxHeight(), rulerTick(1f).toFloat(), state.settings.vibration)
            SpeedValue(speed, Modifier.padding(start = 8.dp).width(88.dp).height(40.dp)) {
                state.askNumber(speedLabel, speed, "x", SLOWEST_SPEED, FASTEST_SPEED) { v -> state.selected?.let { state.setVideoSpeed(steppedSpeed(v), it); state.endGesture() } }
            }
        }
        Row(Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp), Arrangement.spacedBy(6.dp)) {
            RampChip(stringResource(R.string.smooth_clip), video, layer.aiMarks.any { it.kind == AiKind.Smooth }) { state.playing = false; ai.apply(AiAction.Smooth) }
            RampChip(stringResource(R.string.speed_reverse), video, layer.reverseMark != null) { state.playing = false; ai.apply(AiAction.Reverse) }
            RampChip(stringResource(R.string.speed_freeze_frame), inside) { state.askNumber(secondsLabel, 2f, "", 0.1f, 30f) { state.freezeFrame(it) } }
        }
    }
}

@Composable
internal fun SpeedValue(speed: Float, modifier: Modifier, onClick: (() -> Unit)?) {
    Row(
        modifier.clip(PanelShape).background(Am.Field).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelIcon(paceIcon(speed), Am.OnDark, size = 18.dp)
        Text(formatFixed(speed, 2) + "x", Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.End)
    }
}

@Composable
internal fun RampChip(label: String, enabled: Boolean, active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().alpha(if (enabled) 1f else 0.35f).background(if (active) Am.Accent else Am.Button, RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp), Alignment.Center) {
        Text(label, color = Am.OnDark, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(8.sp, 12.sp, 0.5.sp))
    }
}

@Composable
private fun RetimeButtons(state: EditorState, usable: List<Boolean>, modifier: Modifier) {
    Row(modifier, Arrangement.spacedBy(1.5.dp)) {
        val shown = (0..3).filter { usable[it] }.ifEmpty { (0..3).toList() }
        for (i in shown) {
            val first = i == shown.first()
            val last = i == shown.last()
            val shape = RoundedCornerShape(topStart = if (first) 4.dp else 0.dp, bottomStart = if (first) 4.dp else 0.dp, topEnd = if (last) 4.dp else 0.dp, bottomEnd = if (last) 4.dp else 0.dp)
            val name = stringResource(RetimeNames[i])
            Box(Modifier.weight(1f).fillMaxHeight().alpha(if (usable[i]) 1f else 0.35f)
                .background(Am.Button, shape).clickable(enabled = usable[i], role = Role.Button) { state.retimeVideo(i < 2) }.semantics { contentDescription = name }, Alignment.Center) {
                RetimeGlyph(i, Modifier.size(23.dp))
            }
        }
    }
}

private val RetimeNames = listOf(R.string.a11y_extend_start, R.string.a11y_trim_start, R.string.a11y_trim_end, R.string.a11y_extend_end)

internal fun paceIcon(speed: Float) = when {
    speed < 0.3f -> PanelIcons.Snail
    speed < 0.75f -> PanelIcons.Turtle
    speed < 1.5f -> PanelIcons.Walk
    speed < 4f -> PanelIcons.Rabbit
    speed < 20f -> PanelIcons.Horse
    else -> PanelIcons.Cheetah
}

@Composable
private fun RetimeGlyph(index: Int, modifier: Modifier) {
    Canvas(modifier) {
        val k = size.width / 24
        val start = index < 2
        val extend = index == 0 || index == 3
        fun p(x: Float, y: Float) = Offset((if (start) x else 24 - x) * k, y * k)
        val edge = if (extend) 1f else 12f
        drawLine(Am.OnDark, p(edge, 1f), p(edge, 23f), k, StrokeCap.Round)
        val left = if (extend) 4f else 1f
        val right = if (extend) 23f else 10f
        val box = Path().apply {
            moveTo(p(left, 6f).x, p(left, 6f).y); lineTo(p(right, 6f).x, p(right, 6f).y)
            lineTo(p(right, 18f).x, p(right, 18f).y); lineTo(p(left, 18f).x, p(left, 18f).y); close()
        }
        drawPath(box, Am.OnDark, style = Stroke(k))
        val dx = if (extend) 19f else 18f
        val diamond = Path().apply {
            val points = listOf(p(dx, 9f), p(dx + 1.7f, 12f), p(dx, 15f), p(dx - 1.7f, 12f))
            moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        drawPath(diamond, Am.OnDark)
        val ax = if (extend) 8f else 6f
        drawLine(Am.OnDark, p(ax - 3f, 12f), p(ax + 2f, 12f), k)
        val tip = if (extend) ax - 3 else ax + 2
        drawLine(Am.OnDark, p(tip, 12f), p(tip + if (extend) 2 else -2, 10f), k)
        drawLine(Am.OnDark, p(tip, 12f), p(tip + if (extend) 2 else -2, 14f), k)
    }
}
