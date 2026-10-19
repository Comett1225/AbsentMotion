package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.*
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlin.math.*

@Composable
internal fun EffectSettings(state: EditorState, layer: Layer, instance: XmlNode, definition: EffectDefinition,
                            active: EffectParam?, select: (EffectParam) -> Unit) {
    val enabled = definition.available
    definition.params.forEach { param ->
        val raw = instance.effectProperty(param.id)?.valueAt(layer.t) ?: param.default
        fun set(text: String, gesture: Boolean = false) {
            select(param)
            state.editSelected(if (gesture) "effect-${state.effectIndex}-${param.id}" else null) { current ->
                current.withEffect(state.effectIndex) { it.withEffectProperty(param.id, param.type, text, current.t, current.snap) }
            }
        }
        when (param.kind) {
            "switch" -> Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
                Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
                PanelSwitch(raw == "true") { if (enabled) set(it.toString()) }
            }
            "color" -> EffectColor(state, param, raw, enabled, active == param, { select(param) }, { set(it) })
            "choice" -> EffectChoice(param, raw, enabled) { set(it) }
            else -> EffectNumbers(state, param, raw, enabled, active == param, { select(param) }, { text, gesture -> set(text, gesture) })
        }
    }
}

@Composable
internal fun PropertyLabel(label: String, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(PanelShape).background(if (active) PanelDark else Am.FieldHi.copy(alpha = 0.3f)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, Modifier.padding(3.dp), color = if (active) Am.Accent else Am.OnDark, fontSize = if (label.length <= 11) 12.sp else 10.sp,
            lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun EffectNumbers(state: EditorState, param: EffectParam, raw: String, enabled: Boolean, active: Boolean,
                          select: () -> Unit, set: (String, Boolean) -> Unit) {
    val count = if (param.kind == "point") 2 else 1
    val parsed = raw.split(',').map { it.trim().toFloatOrNull() ?: 0f }
    fun stored(v: List<Float>) = v.joinToString(",")
    val values = List(count) { parsed.getOrElse(it) { 0f } }
    var component by remember(param.id) { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(values)
    val percent = param.percent
    val factor = if (percent) 100f else 1f
    fun adjust(value: Float, gesture: Boolean) {
        val rounded = if (param.step > 0) round(value/param.step)*param.step else value
        val changed = latest.toMutableList().also { it[component] = rounded.coerceIn(param.min, param.max) }
        set(stored(changed), gesture)
    }
    val decimals = param.step.toBigDecimal().stripTrailingZeros().scale().coerceAtLeast(0).let { if (percent) (it - 2).coerceAtLeast(0) else it }
    val unit = if (percent) "%" else if (param.kind == "angle") "°" else ""
    fun keypad(index: Int) {
        state.keypad = KeypadRequest(values.mapIndexed { i, v -> KeypadField(if (count == 1) param.label else listOf("X", "Y")[i], v * factor) }, index) { next ->
            set(stored(next.map { v -> (v/factor).coerceIn(param.min, param.max) }), false)
        }
    }
    fun tap(index: Int) { if (active && component == index) keypad(index) else { component = index; select() } }
    val scalarRuler = count == 1
    Row(Modifier.fillMaxWidth().height(57.dp)
        .then(if (scalarRuler) Modifier.padding(start = 3.dp) else Modifier.padding(3.dp))
        .alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        PropertyLabel(param.label, active, enabled, Modifier.width(75.dp).height(45.dp), select)
        if (scalarRuler) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                key(param.id) {
                    EffectRuler(values[0], param.step, param.min, param.max, enabled,
                        select, { adjust(it, true) }, state::endGesture,
                        Modifier.matchParentSize().padding(end = 45.dp, top = .5.dp, bottom = .5.dp))
                }
                ValueBox(formatFixed(values[0] * factor, decimals) + unit, false, false,
                    Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(56.dp).height(45.dp), 8f, enabled) { tap(0) }
            }
            return@Row
        }
        key(param.id, component) {
            EffectRuler(values[component], param.step, param.min, param.max, enabled,
                select, { adjust(it, true) }, state::endGesture, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp).fillMaxHeight(), Arrangement.spacedBy(2.dp)) {
            values.forEachIndexed { i, value ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == 0) "X:" else "Y:", Modifier.padding(end = 5.dp), color = Am.Caption, fontSize = 10.sp)
                    ValueBox(formatFixed(value * factor, decimals), i == component, active && i == component, Modifier.width(55.dp).fillMaxHeight(), 5.5f, enabled) { tap(i) }
                }
            }
        }
    }
}

@Composable
internal fun ValueBox(text: String, chosen: Boolean, lit: Boolean, modifier: Modifier, endPadding: Float, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier.clip(PanelShape).background(if (lit) PanelDark else Color(0x33000000)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.CenterEnd) {
        Text(text, Modifier.padding(start = 2.dp, end = endPadding.dp), color = if (chosen) PanelGreen else Am.OnDark, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun EffectChoice(param: EffectParam, raw: String, enabled: Boolean, set: (String) -> Unit) {
    val chosen = (raw.toFloatOrNull() ?: 0f).roundToInt().coerceIn(param.options.indices)
    var open by remember(param.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
        Box {
            Text(param.options[chosen], Modifier.clip(PanelShape).background(Color(0x33000000)).clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            Presence(open) {
                MenuPopup(DropOrRise, { open = false }) {
                    param.options.forEachIndexed { i, option -> MenuRow(option) { set(formatFloats(floatArrayOf(i.toFloat()))); open = false } }
                }
            }
        }
    }
}

@Composable
private fun EffectColor(state: EditorState, param: EffectParam, raw: String, enabled: Boolean, active: Boolean, select: () -> Unit, set: (String) -> Unit) {
    val color = parseColor(raw) ?: 0xFFFFFFFF.toInt()
    var expanded by remember(param.id) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(3.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
            PropertyLabel(param.label, active, enabled, Modifier.width(75.dp).fillMaxHeight(), select)
            Box(Modifier.weight(1f).fillMaxHeight().padding(5.dp).clip(PanelShape).background(Color(color)).clickable(enabled = enabled) { select(); expanded = !expanded })
            Text(raw.uppercase(), Modifier.width(82.dp).clickable(enabled = enabled) {
                select()
                state.keypad = KeypadRequest(listOf("R", "G", "B", "A").mapIndexed { i, label -> KeypadField(label, ((color ushr intArrayOf(16,8,0,24)[i]) and 255).toFloat()) }, 0) { next ->
                    var out = 0
                    next.forEachIndexed { i, value -> out = out or (value.roundToInt().coerceIn(0,255) shl intArrayOf(16,8,0,24)[i]) }
                    set(formatColor(out))
                }
            }, color = Am.OnDark, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        Presence(expanded) { MenuPopup(DropOrRise, { expanded = false }) {
            val swatches = listOf(0xFF4444,0xF4B446,0xFAE965,0x1ED91E,0x17E1E1,0x3D4CF5,0xDD44DD,0xFFFFFF,0xBEBEBE,0x888888,0x444444,0x000000,0x5EB7D9,0x98C99C)
            swatches.chunked(7).forEach { row -> Row(Modifier.fillMaxWidth().height(32.dp).padding(3.dp), Arrangement.spacedBy(2.dp)) {
                row.forEach { rgb -> Box(Modifier.width(30.dp).fillMaxHeight().background(Color(rgb or 0xFF000000.toInt())).clickable(enabled = enabled) { set(formatColor(rgb or 0xFF000000.toInt())); expanded = false }) }
            } }
        } }
    }
}
