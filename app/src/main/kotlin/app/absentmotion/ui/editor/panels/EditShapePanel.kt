package app.absentmotion.ui.editor.panels

import app.absentmotion.model.finiteFloat
import app.absentmotion.R
import androidx.compose.ui.res.stringResource
import app.absentmotion.model.PropRef
import app.absentmotion.editor.KeyTarget
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.model.Layer
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.model.ShapeParam
import app.absentmotion.model.ShapeParam.Kind
import app.absentmotion.model.formatFloats
import app.absentmotion.ui.theme.Am
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun EditShapePanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val params = ShapeCatalog.byId(layer.shape)?.params ?: return
    var activeRow by remember(layer.id) { mutableIntStateOf(0) }
    val activeParam = params.getOrNull(activeRow)?.takeIf { it.kind != Kind.Toggle }
    SideEffect { state.keyTarget = activeParam?.let { KeyTarget(PropRef.shape(it.prop), layer.propText(it.prop) ?: it.default, it.kind.type) } }
    Column(modifier.verticalScroll(rememberScrollState())) {
        params.forEachIndexed { i, param ->
            val edit = ShapeEdit(state, layer, param, stringResource(param.label))
            val activate = { activeRow = i }
            val active = activeRow == i
            when (param.kind) {
                Kind.Toggle -> ToggleRow(edit)
                Kind.Count -> CountRow(edit, active, activate)
                Kind.Size, Kind.Point -> PairRow(edit, active, activate)
                else -> NumberRow(edit, active, activate)
            }
        }
    }
}

private class ShapeEdit(val state: EditorState, val layer: Layer, val param: ShapeParam, val label: String) {
    val values = numbers(layer, param)
    val isOn = (layer.propText(param.prop) ?: param.default).trim() == "true"

    fun change(box: Int, f: (Float) -> Float) = state.editSelected("shape-${param.prop}") { l ->
        val v = numbers(l, param)
        v[box] = f(v[box])
        l.withProp(param.prop, param.kind.type, formatFloats(v))
    }

    fun live(box: Int): Float = (state.selected?.let { numbers(it, param) } ?: values).getOrElse(box) { 0f }

    fun show(v: Float) = formatFixed(v, param.decimals) + if (param.kind == Kind.Angle) "º" else ""

    fun openKeypad(box: Int) {
        val pair = param.kind == Kind.Size || param.kind == Kind.Point
        val fields = if (pair) listOf(KeypadField("X", values[0]), KeypadField("Y", values[1]))
            else if (param.kind == Kind.Count) listOf(KeypadField(label, values[0], "", MIN_COUNT.toFloat(), MAX_COUNT.toFloat()))
            else listOf(KeypadField(label, values[0]))
        state.keypad = KeypadRequest(fields, if (pair) box else 0) { v ->
            val entered = FloatArray(v.size) { if (param.kind == Kind.Count) v[it].roundToInt().coerceAtLeast(MIN_COUNT).toFloat() else v[it] }
            state.editSelected { it.withProp(param.prop, param.kind.type, formatFloats(entered)) }
        }
    }

    fun flip() = state.editSelected { it.withProp(param.prop, param.kind.type, (!isOn).toString()) }
}

private val RowTile get() = Am.Button
private val ValueTileBg get() = Am.Well
private val AxisLabel get() = Am.Caption
private val TrackGrey get() = Am.Caption
private val TickGrey get() = Am.Faint

private val RowTop = 16.px

@Composable
private fun LabelTile(edit: ShapeEdit, active: Boolean, topPad: Dp, onActivate: () -> Unit) {
    Box(
        Modifier.padding(top = topPad).size(211.px, 127.px).clip(PanelShape).background(if (active) PanelDark else RowTile).clickable(onClick = onActivate),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            edit.label, Modifier.padding(horizontal = 1.dp), color = if (active) Am.Accent else Am.OnDark, fontSize = 10.5.sp,
            lineHeight = 13.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ValueTile(text: String, topPad: Dp, onClick: () -> Unit) {
    Box(
        Modifier.padding(top = topPad).size(158.px, 127.px).clip(PanelShape).background(ValueTileBg).clickable(onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, Modifier.padding(end = 26.px), color = Am.OnDark, fontSize = 13.5.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun NumberRow(edit: ShapeEdit, active: Boolean, onActivate: () -> Unit) {
    val param = edit.param
    Row(Modifier.fillMaxWidth().height(160.px)) {
        Spacer(Modifier.width(8.px))
        LabelTile(edit, active, RowTop, onActivate)
        val drag = remember { floatArrayOf(0f, 0f) }
        RulerSpinner(
            PanelGreen,
            onDrag = { dx -> drag[1] += dx; edit.change(0) { step(param.kind, drag[0], drag[1], param.decimals) } },
            onEnd = { edit.state.endGesture() },
            modifier = Modifier.weight(1f).height(158.px),
            onStart = { onActivate(); drag[0] = edit.live(0); drag[1] = 0f },
            tickSpacing = 5.33.dp,
        )
        Spacer(Modifier.width(3.px))
        ValueTile(edit.show(edit.values[0]), RowTop) {
            onActivate()
            edit.openKeypad(0)
        }
        Spacer(Modifier.width(6.px))
    }
}

@Composable
private fun PairRow(edit: ShapeEdit, active: Boolean, onActivate: () -> Unit) {
    val param = edit.param
    var box by remember(edit.layer.id, param.prop) { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().height(160.px)) {
        Spacer(Modifier.width(8.px))
        LabelTile(edit, active, RowTop, onActivate)
        val drag = remember { floatArrayOf(0f, 0f) }
        RulerSpinner(
            PanelGreen,
            onDrag = { dx -> drag[1] += dx; edit.change(box) { step(param.kind, drag[0], drag[1], param.decimals) } },
            onEnd = { edit.state.endGesture() },
            modifier = Modifier.weight(1f).height(158.px),
            onStart = { onActivate(); drag[0] = edit.live(box); drag[1] = 0f },
            tickSpacing = 5.33.dp,
        )
        Column(Modifier.width(37.px).padding(top = 19.px), Arrangement.spacedBy(6.px)) {
            for (label in listOf("X:", "Y:")) {
                Box(Modifier.height(57.px), contentAlignment = Alignment.CenterStart) {
                    Text(label, color = AxisLabel, fontSize = 10.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Column(Modifier.width(155.px).padding(top = 19.px), Arrangement.spacedBy(6.px)) {
            for (i in 0..1) {
                val on = box == i
                Box(
                    Modifier.fillMaxWidth().height(57.px).clip(PanelShape).background(if (on) PanelDark else Am.PanelInset).clickable {
                        if (active && on) edit.openKeypad(i)
                        box = i
                        onActivate()
                    },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(edit.show(edit.values[i]), Modifier.padding(end = 19.px), color = if (on) PanelGreen else Am.OnDark, fontSize = 13.5.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.width(6.px))
    }
}

@Composable
private fun CountRow(edit: ShapeEdit, active: Boolean, onActivate: () -> Unit) {
    val top = 8.px
    val value = edit.values[0].roundToInt().coerceIn(MIN_COUNT, MAX_COUNT)
    Row(Modifier.fillMaxWidth().height(144.px)) {
        Spacer(Modifier.width(8.px))
        LabelTile(edit, active, top, onActivate)
        Spacer(Modifier.width(23.px))
        CountSlider(
            value,
            onValue = { n ->
                onActivate()
                edit.change(0) { n.toFloat() }
            },
            onEnd = { edit.state.endGesture() },
            Modifier.width(483.px).fillMaxHeight(),
        )
        Spacer(Modifier.width(23.px))
        ValueTile(edit.show(edit.values[0]), top) {
            onActivate()
            edit.openKeypad(0)
        }
        Spacer(Modifier.width(6.px))
    }
}

private const val MIN_COUNT = 3
private const val MAX_COUNT = 32
private const val FIRST_TICK = 24.5f
private const val TICK_GAP = 14.9f
private const val TRACK_LEFT = 21f
private const val TRACK_RIGHT = 461f

@Composable
private fun CountSlider(value: Int, onValue: (Int) -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val set by rememberUpdatedState(onValue)
    val ended by rememberUpdatedState(onEnd)
    Canvas(
        modifier
            .pointerInput(Unit) {
                val u = 1.px.toPx()
                fun valueAt(x: Float) = MIN_COUNT + ((x / u - FIRST_TICK) / TICK_GAP).roundToInt().coerceIn(0, MAX_COUNT - MIN_COUNT)
                detectTapGestures {
                    set(valueAt(it.x))
                    ended()
                }
            }
            .pointerInput(Unit) {
                val u = 1.px.toPx()
                fun valueAt(x: Float) = MIN_COUNT + ((x / u - FIRST_TICK) / TICK_GAP).roundToInt().coerceIn(0, MAX_COUNT - MIN_COUNT)
                detectHorizontalDragGestures(onDragStart = { set(valueAt(it.x)) }, onDragEnd = { ended() }, onDragCancel = { ended() }) { change, _ ->
                    change.consume()
                    set(valueAt(change.position.x))
                }
            },
    ) {
        val u = 1.px.toPx()
        val knobX = (FIRST_TICK + (value - MIN_COUNT) * TICK_GAP) * u
        for (i in 0..(MAX_COUNT - MIN_COUNT)) {
            val x = (FIRST_TICK + i * TICK_GAP) * u
            drawLine(TickGrey, Offset(x, 18 * u), Offset(x, 36 * u), 4 * u)
            drawLine(TickGrey, Offset(x, 108 * u), Offset(x, 126 * u), 4 * u)
        }
        val round = CornerRadius(3.5f * u)
        drawRoundRect(TrackGrey, Offset(TRACK_LEFT * u, 67.5f * u), Size((TRACK_RIGHT - TRACK_LEFT) * u, 7 * u), round)
        drawRoundRect(Am.Accent, Offset(TRACK_LEFT * u, 67.5f * u), Size(knobX - TRACK_LEFT * u, 7 * u), round)
        drawCircle(Am.OnDark, 25 * u, Offset(knobX, 71 * u))
    }
}

@Composable
private fun ToggleRow(edit: ShapeEdit) {
    Row(Modifier.fillMaxWidth().height(104.px), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(23.px))
        Text(edit.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.5.sp, maxLines = 1)
        Canvas(Modifier.size(132.px, 68.px).clickable { edit.flip() }) {
            val u = 1.px.toPx()
            val on = edit.isOn
            val centreY = size.height / 2f
            val track = Size(90 * u, 39 * u)
            drawRoundRect(if (on) Am.AccentDeep else Am.Inactive, Offset(31 * u, centreY - track.height / 2f), track, CornerRadius(track.height / 2f))
            drawCircle(if (on) Am.AccentLit else AxisLabel, 28 * u, Offset((if (on) 121 - 28 else 31 + 28) * u, centreY))
        }
        Spacer(Modifier.width(23.px))
    }
}

internal fun step(kind: Kind, base: Float, travel: Float, decimals: Int): Float = when (kind) {
    Kind.Point -> base + travel
    Kind.Angle -> base + travel * 0.5f
    else -> RulerMath.value(base, travel, 0.5f * 10f.pow(-decimals))
}

private fun numbers(layer: Layer, param: ShapeParam): FloatArray {
    if (param.kind == Kind.Toggle) return FloatArray(0)
    val need = if (param.kind == Kind.Size || param.kind == Kind.Point) 2 else 1
    fun parse(text: String) = text.split(',').mapNotNull { it.trim().finiteFloat() }.toFloatArray()
    val found = parse(layer.propText(param.prop) ?: param.default)
    return (if (found.size >= need) found else parse(param.default)).copyOf(need)
}
