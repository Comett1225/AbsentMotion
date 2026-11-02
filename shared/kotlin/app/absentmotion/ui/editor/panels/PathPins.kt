package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.LayerPoints
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.MAX_PINS
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.PuppetBend
import app.absentmotion.model.PuppetPin
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.formatFloats
import app.absentmotion.model.puppetFalloff
import app.absentmotion.model.allPuppetPins
import app.absentmotion.model.puppetPins
import app.absentmotion.model.valueAt
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.withPuppetPinAt
import app.absentmotion.model.withoutPuppetPin
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am

internal const val TEXT_PATH_ID = "absent.textpath"
internal const val PUPPET_ID = "absent.puppet"

private fun XmlNode.point(param: EffectParam, t: Float): Pt =
    (effectProperty(param.id)?.valueAt(t) ?: param.missingDefault).split(',').map { it.trim().finiteFloat() ?: 0f }.let { Pt(it.getOrElse(0) { 0f }, it.getOrElse(1) { 0f }) }

private fun XmlNode.number(param: EffectParam, t: Float): Float = (effectProperty(param.id)?.valueAt(t) ?: param.missingDefault).finiteFloat() ?: 0f

internal fun arcGuide(a: Pt, b: Pt, c: Pt, d: Pt, steps: Int = 48): List<Pt> = List(steps + 1) { i ->
    val t = i.toFloat() / steps
    val u = 1 - t
    Pt(u * u * u * a.x + 3 * u * u * t * b.x + 3 * u * t * t * c.x + t * t * t * d.x, u * u * u * a.y + 3 * u * u * t * b.y + 3 * u * t * t * c.y + t * t * t * d.y)
}

internal fun circleGuide(c: Pt, r: Float, steps: Int = 72): List<Pt> = List(steps + 1) { i ->
    val angle = 2 * Math.PI * i / steps
    Pt(c.x + r * kotlin.math.cos(angle).toFloat(), c.y + r * kotlin.math.sin(angle).toFloat())
}

internal fun textPathPoints(state: EditorState, layer: Layer, index: Int, instance: XmlNode, definition: EffectDefinition, active: EffectParam?, select: (EffectParam) -> Unit): LayerPoints? {
    fun param(id: String) = definition.params.firstOrNull { it.id == id }
    fun setPoint(p: EffectParam, to: Pt) = state.editSelected("effect-$index-${p.id}") { current ->
        current.withEffect(index) { it.withEffectProperty(p.id, p.type, formatFloats(floatArrayOf(to.x.coerceIn(p.min, p.max), to.y.coerceIn(p.min, p.max))), current.t, current.snap) }
    }
    val arc = listOf("start", "control1", "control2", "end").mapNotNull(::param).filter { definition.shown(it, instance, layer.t) }
    if (arc.size == 4) {
        val (a, b, c, d) = arc.map { instance.point(it, layer.t) }
        return LayerPoints(listOf(a, b, c, d), arc.indexOf(active), lines = listOf(0 to 1, 3 to 2), guide = arcGuide(a, b, c, d), pick = { select(arc[it]) }) { i, to -> setPoint(arc[i], to) }
    }
    val center = param("center")?.takeIf { definition.shown(it, instance, layer.t) } ?: return null
    val radius = param("radius") ?: return null
    val c = instance.point(center, layer.t)
    val r = kotlin.math.abs(instance.number(radius, layer.t))
    val chosen = when (active) { center -> 0; radius -> 1; else -> -1 }
    return LayerPoints(listOf(c, Pt(c.x, c.y - r)), chosen, lines = listOf(0 to 1), guide = circleGuide(c, r), pick = { select(if (it == 0) center else radius) }) { i, to ->
        if (i == 0) setPoint(center, to)
        else state.editSelected("effect-$index-radius") { current ->
            val now = current.effects.getOrNull(index)?.point(center, current.t) ?: c
            val value = to.distanceTo(now).coerceIn(radius.min, radius.max)
            current.withEffect(index) { it.withEffectProperty(radius.id, radius.type, formatFloats(floatArrayOf(value)), current.t, current.snap) }
        }
    }
}

internal fun Scene.missesPathBelow(layer: Layer, instance: XmlNode): Boolean {
    if ((instance.effectProperty("shape")?.valueAt(layer.t)?.finiteFloat() ?: 0f).roundToInt() != 2) return false
    val list = layers
    val index = list.indexOfFirst { it.id == layer.id }
    if (index < 0) return false
    val pinned = layer.node["pathLayer"]?.toLongOrNull()?.takeIf { it != layer.id }?.let { id -> list.firstOrNull { it.id == id } }
    val below = pinned ?: list.getOrNull(index - 1)
    return below == null || below.kind in setOf("nullobj", "camera", "audio")
}

@Composable
internal fun TextPathHint(state: EditorState, layer: Layer, instance: XmlNode) {
    if (!state.scene.missesPathBelow(layer, instance)) return
    Text(stringResource(R.string.textpath_nothing_below), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Am.Caption, fontSize = 12.sp, lineHeight = 15.sp)
}

private fun EditorState.movePin(index: Int, pin: PuppetPin, to: Pt, key: Any?) = editSelected(key) { current ->
    current.withEffect(index) { it.withEffectProperty(pin.name, "vec2", formatFloats(floatArrayOf(to.x, to.y)), current.t, current.snap) }
}

internal fun puppetPoints(state: EditorState, layer: Layer, index: Int, instance: XmlNode, pin: String?, adding: Boolean, onPin: (String?) -> Unit,
                          onFull: () -> Unit): LayerPoints {
    val pins = instance.puppetPins(layer.t)
    val bend = PuppetBend(pins, instance.puppetFalloff(layer.t))
    val add: ((Pt) -> Unit)? = if (!adding) null else { at ->
        if (instance.allPuppetPins(layer.t).size >= MAX_PINS) onFull()
        else {
            var made: String? = null
            state.editSelected { current ->
                current.withEffect(index) { effect ->
                    effect.withPuppetPinAt(at, current.t)?.also { made = it.puppetPins(current.t).lastOrNull()?.name } ?: effect
                }
            }
            onPin(made)
        }
    }
    return LayerPoints(pins.map { it.at }, pins.indexOfFirst { it.name == pin }, pick = { onPin(pins[it].name) }, tap = add, source = bend::source) { i, to ->
        pins.getOrNull(i)?.let { state.movePin(index, it, to, "puppet-$index-${it.name}") }
    }
}

@Composable
internal fun PuppetSection(state: EditorState, layer: Layer, index: Int, instance: XmlNode, pin: String?, adding: Boolean, onPin: (String?) -> Unit, onAdding: (Boolean) -> Unit) {
    val all = instance.allPuppetPins(layer.t)
    val pins = all.take(MAX_PINS)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(PanelShape).background(if (adding) PanelDark else Am.Button).toggleable(adding, role = Role.Switch, onValueChange = onAdding), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelIcon(PanelIcons.Pin, if (adding) Am.Accent else Am.OnDark, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.puppet_add_pins), color = if (adding) Am.Accent else Am.OnDark, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
    if (pins.isEmpty() || adding) Text(stringResource(R.string.puppet_pins_hint), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Am.Caption, fontSize = 12.sp, lineHeight = 15.sp)
    if (all.size > MAX_PINS) Text(stringResource(R.string.puppet_pins_full, "$MAX_PINS"), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Am.Caption, fontSize = 12.sp, lineHeight = 15.sp)
    for (p in pins) {
        val chosen = p.name == pin
        fun keypad(which: Int) {
            onPin(p.name)
            state.keypad = KeypadRequest(listOf(KeypadField("X", p.at.x), KeypadField("Y", p.at.y)), which) { v -> state.movePin(index, p, Pt(v[0], v[1]), null) }
        }
        val label = stringResource(R.string.puppet_pin_name, "${p.number}")
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            PropertyLabel(label, chosen, true, Modifier.width(75.dp).fillMaxHeight()) { onPin(if (chosen) null else p.name) }
            Spacer(Modifier.width(4.dp))
            ValueBox(formatFixed(p.at.x, 1), false, chosen, Modifier.weight(1f).fillMaxHeight(), 5.5f, true) { keypad(0) }
            Spacer(Modifier.width(3.dp))
            ValueBox(formatFixed(p.at.y, 1), false, chosen, Modifier.weight(1f).fillMaxHeight(), 5.5f, true) { keypad(1) }
            Box(Modifier.size(48.dp).clickable { state.movePin(index, p, p.rest, null) }, contentAlignment = Alignment.Center) {
                Icon(ChromeIcons.Undo, "$label, ${stringResource(R.string.puppet_reset_pin)}", Modifier.size(20.dp), tint = Am.OnDark)
            }
            Box(Modifier.size(48.dp).clickable {
                if (chosen) onPin(null)
                state.editSelected { current -> current.withEffect(index) { it.withoutPuppetPin(p.number) } }
            }, contentAlignment = Alignment.Center) {
                Icon(ChromeIcons.Trash, "$label, ${stringResource(R.string.puppet_delete_pin)}", Modifier.size(20.dp), tint = Am.OnDark)
            }
        }
    }
}

@Composable
internal fun rememberPinsFull(): () -> Unit {
    val context = LocalContext.current
    val message = stringResource(R.string.puppet_pins_full, "$MAX_PINS")
    return { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }
}
