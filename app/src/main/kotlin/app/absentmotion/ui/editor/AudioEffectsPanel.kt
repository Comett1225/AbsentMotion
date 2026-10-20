package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.audio.AudioFx
import app.absentmotion.audio.AudioPresets
import app.absentmotion.audio.FxKind
import app.absentmotion.audio.FxParam
import app.absentmotion.audio.MAX_AUDIO_FX
import app.absentmotion.audio.audioEffects
import app.absentmotion.audio.moveAudioEffect
import app.absentmotion.audio.updateAudioEffect
import app.absentmotion.audio.withAudioEffects
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.platform.BackHandler
import app.absentmotion.ui.editor.effects.EffectIcons
import app.absentmotion.ui.editor.panels.px
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val RowShape = RoundedCornerShape(5.dp)
private val RowHeight = 37.dp
private val SliderHeight = 40.dp

@Composable
internal fun AudioEffectsPanel(state: EditorState, modifier: Modifier, onBack: () -> Unit) {
    val layer = state.selected ?: return
    val effects = layer.audioEffects
    var editing by remember(layer.id) { mutableIntStateOf(-1) }
    val shown = effects.getOrNull(editing)
    LaunchedEffect(shown == null) { if (shown == null) editing = -1 }
    SideEffect { state.keyTarget = null }
    BackHandler { if (editing >= 0) editing = -1 else onBack() }
    Row(modifier.background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) {
            FrameButton(PanelIcons.Back, stringResource(R.string.topbar_back), Modifier.weight(1f)) { if (editing >= 0) editing = -1 else onBack() }
            val anyOn = effects.any { !it.bypass }
            FrameButton(if (anyOn) EffectIcons.Eye else EffectIcons.EyeOff, stringResource(if (anyOn) R.string.audiofx_bypass else R.string.audiofx_turn_on), Modifier.weight(1f), effects.isNotEmpty()) {
                state.editSelected { it.withAudioEffects(it.audioEffects.map { fx -> fx.withBypass(anyOn) }) }
            }
            FrameButton(ChromeIcons.Trash, stringResource(R.string.delete_all), Modifier.weight(1f), effects.isNotEmpty()) {
                editing = -1
                state.editSelected { it.withAudioEffects(emptyList()) }
            }
        }
        Swap(editing, Modifier.weight(1f).fillMaxHeight(), rise = 0.dp) {
            if (shown == null) EffectList(state, effects) { editing = it }
            else EffectDetail(state, editing, shown) { editing = -1 }
        }
    }
}

@Composable
private fun EffectList(state: EditorState, effects: List<AudioFx>, open: (Int) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 6.5.dp, end = 6.5.dp, top = 6.5.dp).fillMaxWidth().height(RowHeight).horizontalScroll(rememberScrollState()),
            Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            for (preset in AudioPresets) {
                val active = preset.effects == effects
                Box(Modifier.fillMaxHeight().clip(RowShape).background(if (active) Am.Accent else Am.Button)
                    .clickable { state.editSelected { it.withAudioEffects(preset.effects) } }.padding(horizontal = 12.dp), Alignment.Center) {
                    Text(stringResource(preset.name), color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        effects.forEachIndexed { i, fx -> EffectRow(state, i, fx, effects.size) { open(i) } }
        Box(Modifier.padding(6.5.dp).fillMaxWidth().height(RowHeight)) {
            val room = effects.size < MAX_AUDIO_FX
            Box(Modifier.fillMaxSize().clip(RowShape).background(Am.Button).alpha(if (room) 1f else 0.35f)
                .clickable(enabled = room) { adding = true }, Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(EffectIcons.Add, null, Modifier.size(18.dp), tint = Am.OnDark)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_audio_effect), color = Am.OnDark, fontSize = 14.sp)
                }
            }
            Presence(adding) {
                MenuPopup(AboveLeft, { adding = false }, TransformOrigin(0f, 1f)) {
                    for (kind in FxKind.entries) MenuRow(stringResource(kind.label)) {
                        adding = false
                        val at = effects.size
                        state.editSelected { it.withAudioEffects(it.audioEffects + AudioFx.of(kind)) }
                        open(at)
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectRow(state: EditorState, index: Int, fx: AudioFx, count: Int, open: () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val latest by rememberUpdatedState(index)
    val rows by rememberUpdatedState(count)
    var held by remember { mutableIntStateOf(index) }
    val known = fx.kind != null
    Row(Modifier.padding(start = 6.5.dp, end = 6.5.dp, top = 6.5.dp).fillMaxWidth().height(RowHeight).clip(RowShape).background(Am.Field),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().alpha(if (known && !fx.bypass) 1f else 0.35f).clickable(enabled = known, onClick = open)
            .padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(EffectIcons.Expand, null, Modifier.size(18.dp).alpha(0.3f), tint = Am.OnDark)
            Text(fx.kind?.let { stringResource(it.label) } ?: stringResource(R.string.audiofx_unknown_effect), Modifier.padding(start = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        }
        Icon(if (fx.bypass) EffectIcons.EyeOff else EffectIcons.Eye, stringResource(if (fx.bypass) R.string.audiofx_turn_on else R.string.audiofx_bypass), Modifier.width(36.dp).fillMaxHeight().clickable {
            state.editSelected { it.updateAudioEffect(index) { e -> e.withBypass(!e.bypass) } }
        }, tint = Am.OnDark)
        Icon(EffectIcons.Grip, stringResource(R.string.audiofx_reorder), Modifier.width(32.dp).fillMaxHeight().pointerInput(state) {
            detectDragGesturesAfterLongPress(onDragStart = { drag = 0f; held = latest }, onDragEnd = { state.endGesture() }, onDragCancel = { state.endGesture() }) { change, amount ->
                change.consume()
                drag += amount.y
                if (abs(drag) >= 43.5.dp.toPx()) {
                    val to = (held + if (drag > 0) 1 else -1).coerceIn(0, rows - 1)
                    if (to != held) state.editSelected("audiofx-reorder") { it.moveAudioEffect(held, to) }
                    held = to
                    drag = 0f
                }
            }
        }, tint = Am.OnDark)
    }
}

@Composable
private fun EffectDetail(state: EditorState, index: Int, fx: AudioFx, close: () -> Unit) {
    val kind = fx.kind ?: return
    Column(Modifier.fillMaxSize().padding(6.5.dp).clip(RowShape).background(Am.Field)) {
        Row(Modifier.fillMaxWidth().height(RowHeight).padding(start = 3.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = close), verticalAlignment = Alignment.CenterVertically) {
                Icon(EffectIcons.Expanded, null, Modifier.padding(start = 8.dp).size(18.dp).alpha(0.9f), tint = Am.OnDark)
                Text(stringResource(kind.label), Modifier.padding(start = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            }
            Box(Modifier.size(36.dp).clickable { state.editSelected { it.updateAudioEffect(index) { e -> e.reset() } } }, Alignment.Center) {
                Icon(ChromeIcons.Undo, stringResource(R.string.audiofx_reset), Modifier.size(22.dp), tint = Am.OnDark)
            }
            Box(Modifier.size(36.dp).clickable { close(); state.editSelected { it.withAudioEffects(it.audioEffects.filterIndexed { i, _ -> i != index }) } }, Alignment.Center) {
                Icon(ChromeIcons.Trash, stringResource(R.string.delete), Modifier.size(22.dp), tint = Am.OnDark)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            ParamSlider(state, stringResource(R.string.audiofx_mix), fx.mix, 0f, 1f, false, percent(fx.mix), { it * 100 }, { it / 100 }) { v ->
                state.editSelected("audiofx-$index-mix") { it.updateAudioEffect(index) { e -> e.withMix(v) } }
            }
            for (param in kind.params) {
                val set = { v: Float, key: Any? -> state.editSelected(key) { it.updateAudioEffect(index) { e -> e.with(param, v) } } }
                if (param.choices.isNotEmpty()) ChoiceRow(param, fx[param]) { set(it, null) }
                else {
                    val scale = if (param.unit == "%") 100f else 1f
                    ParamSlider(state, stringResource(param.label), fx[param], param.min, param.max, param.log, display(param, fx[param]),
                        { it * scale }, { it / scale }) { set(it, "audiofx-$index-${param.key}") }
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    state: EditorState, label: String, value: Float, min: Float, max: Float, log: Boolean, text: String,
    toTyped: (Float) -> Float, fromTyped: (Float) -> Float, set: (Float) -> Unit,
) {
    val update by rememberUpdatedState(set)
    fun at(fraction: Float) = if (log) min * (max / min).pow(fraction) else min + (max - min) * fraction
    val fraction = (if (log) ln(value / min) / ln(max / min) else (value - min) / (max - min)).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(SliderHeight).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
        Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(min, max, log) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val pad = 8.dp.toPx()
                fun move(x: Float) = update(at(((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)))
                down.consume()
                move(down.position.x)
                try {
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) { change.consume(); move(change.position.x) }
                    } while (event.changes.any { it.pressed })
                } finally { state.endGesture() }
            }
        }) {
            val pad = 8.dp.toPx()
            val y = size.height / 2
            val x = pad + (size.width - 2 * pad) * fraction
            drawLine(Am.Well, Offset(pad, y), Offset(size.width - pad, y), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Am.AccentLit, Offset(pad, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Am.AccentLit, 6.dp.toPx(), Offset(x, y))
        }
        Box(Modifier.width(76.dp).height(30.dp).clip(RoundedCornerShape(4.dp)).background(Am.PanelInset).clickable {
            state.keypad = KeypadRequest(listOf(KeypadField(label, toTyped(value))), 0) { values -> set(fromTyped(values[0]).coerceIn(min, max)); state.endGesture() }
        }, Alignment.Center) {
            Text(text, color = Am.OnDark, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChoiceRow(param: FxParam, value: Float, set: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(SliderHeight).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(param.label), Modifier.width(78.dp), color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
        Row(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(4.dp)).background(Am.PanelInset)) {
            param.choices.forEachIndexed { i, label ->
                val v = if (i == 0) param.min else param.max
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (v == value) Am.Accent else Am.PanelInset)
                    .clickable { set(v) }, Alignment.Center) {
                    Text(stringResource(label), color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FrameButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick), Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark)
    }
}

private fun percent(v: Float) = String.format(Locale.US, "%.0f%%", v * 100)

private fun display(param: FxParam, v: Float): String = when (param.unit) {
    "Hz" -> if (v >= 1000) String.format(Locale.US, if (v >= 10_000) "%.1f kHz" else "%.2f kHz", v / 1000) else String.format(Locale.US, "%.0f Hz", v)
    "dB" -> String.format(Locale.US, "%.1f dB", v)
    "ms" -> String.format(Locale.US, if (v < 10) "%.1f ms" else "%.0f ms", v)
    "%" -> percent(v)
    ":1" -> String.format(Locale.US, "%.1f:1", v)
    "Q" -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.2f", v)
}
