package app.absentmotion.pc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState

@Composable
fun Inspector(state: ProjectState, definitions: List<EffectDefinition>, addEffect: () -> Unit) {
    val layer = state.scene.layerAt(state.selected, state.time)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("INSPECTOR")
        if (layer == null) { Text("Select a layer", Modifier.padding(12.dp), color = muted, fontSize = 12.sp); return@Column }
        SectionTitle("Layer")
        Field("Name", layer.label) { state.update { l -> l.withLabel(it) } }
        Text(layer.kind.replaceFirstChar { it.uppercase() } + "   " + formatTime(layer.startTime, state.scene.fps) + " – " + formatTime(layer.endTime, state.scene.fps), Modifier.padding(10.dp), color = muted, fontSize = 11.sp)
        SectionTitle("Transform")
        vectorFields(state, layer, "Position", "location", layer.location)
        vectorFields(state, layer, "Scale", "scale", layer.scale)
        Text(if (layer.scaleLinked) "Scale linked" else "Scale unlinked", Modifier.clickable { state.update { it.withScaleLinked(!it.scaleLinked) } }.padding(start = 8.dp, bottom = 6.dp), color = muted, fontSize = 10.sp)
        vectorFields(state, layer, "Pivot", "pivot", layer.pivot)
        numberField(state, layer, "Rotation", "rotation", layer.rotation)
        numberField(state, layer, "Opacity", "opacity", layer.opacity)
        if (layer.kind == "text") {
            SectionTitle("Text")
            Field("Content", layer.content) { value -> state.update { it.withContent(value) } }
            Field("Size", layer.textSize.toString()) { value -> value.toFloatOrNull()?.let { n -> state.update { it.withTextSize(n.coerceAtLeast(1f)) } } }
        }
        SectionTitle("Fill & Color")
        Field("Colour", formatColor(layer.fillColor)) { value -> parseColor(value)?.let { c -> state.update { it.withFill("color", c) } } }
        SectionTitle("Effects")
        layer.effects.forEachIndexed { index, effect ->
            val def = definitions.firstOrNull { it.id == effect["id"] }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(def?.name ?: effect["id"].orEmpty(), Modifier.weight(1f), color = accent, fontSize = 12.sp)
                Text(if (effect["hidden"] == "true") "○" else "●", Modifier.clickable { state.update { it.withEffect(index) { e -> e.with("hidden", if (e["hidden"] == "true") "false" else "true") } } }.padding(horizontal = 4.dp))
                Text("↑", Modifier.clickable { state.update { it.moveEffect(index, index - 1) } }.padding(horizontal = 4.dp))
                Text("↓", Modifier.clickable { state.update { it.moveEffect(index, index + 1) } }.padding(horizontal = 4.dp))
                Text("×", Modifier.clickable { state.update { it.removeEffect(index) } }.padding(horizontal = 4.dp))
            }
            def?.presets?.takeIf { it.isNotEmpty() }?.let { presets ->
                var expanded by remember(index, def.id) { mutableStateOf(false) }
                Box { Text("Presets ▾", Modifier.clickable { expanded = true }.padding(8.dp), color = muted, fontSize = 12.sp)
                    DropdownMenu(expanded, { expanded = false }) { presets.forEach { preset -> DropdownMenuItem(onClick = { state.update { it.withEffect(index) { def.instance(preset, layer.endTime - layer.startTime) } }; expanded = false }) { Text(preset.name) } } }
                }
            }
            def?.params?.filter { def.shown(it, effect, layer.t) }?.forEach { param -> EffectField(state, layer, index, param) }
        }
        Text("+ Add Effect", Modifier.clickable(onClick = addEffect).padding(12.dp), color = accent, fontSize = 12.sp)
    }
}

@Composable
private fun vectorFields(state: ProjectState, layer: Layer, label: String, name: String, values: FloatArray) {
    val ref = PropRef(PropRef.Kind.Transform, name)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 10.dp), fontSize = 12.sp)
        values.take(2).forEachIndexed { i, number ->
            SmallField(number.toString(), Modifier.width(65.dp).testTag("$name-$i")) { text -> text.toFloatOrNull()?.let { n ->
                state.update { l -> val v = l.transform(name, *values); val previous = v[i]; v[i] = n; if (name == "scale" && l.scaleLinked && v.size > 1) { val other = 1 - i; v[other] = if (previous == 0f) n else v[other] * n / previous }; l.withTransform(name, *v) }
            } }
        }
        Text(if (layer.onKeyframe(ref)) "◆" else "◇", Modifier.clickable { state.update { it.withKeyframeToggled(ref, formatFloats(values)) } }.padding(8.dp), color = accent)
    }
}

@Composable
private fun numberField(state: ProjectState, layer: Layer, label: String, name: String, value: Float) {
    val ref = PropRef(PropRef.Kind.Transform, name)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 10.dp), fontSize = 12.sp)
        SmallField(value.toString(), Modifier.width(100.dp).testTag(name)) { text -> text.toFloatOrNull()?.let { n -> state.update { if (name == "rotation") it.withRotation(n) else it.withTransform(name, n) } } }
        Text(if (layer.onKeyframe(ref)) "◆" else "◇", Modifier.clickable { state.update { it.withKeyframeToggled(ref, "${if (name == "rotation") -value else value}") } }.padding(8.dp), color = accent)
    }
}

@Composable
private fun EffectField(state: ProjectState, layer: Layer, index: Int, param: EffectParam) {
    val ref = PropRef(PropRef.Kind.Effect, param.id, index)
    val value = layer.element(ref)?.valueAt(layer.t) ?: param.missingDefault
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(param.label, Modifier.weight(1f).padding(top = 10.dp), fontSize = 11.sp, maxLines = 2)
        when (param.kind) {
            "choice" -> {
                var expanded by remember(index, param.id) { mutableStateOf(false) }
                Box(Modifier.width(105.dp)) {
                    Text((param.options.getOrNull(value.toFloatOrNull()?.toInt() ?: 0) ?: value) + " ▾", Modifier.clickable { expanded = true }.padding(6.dp), color = muted, fontSize = 11.sp)
                    DropdownMenu(expanded, { expanded = false }) { param.options.forEachIndexed { option, label -> DropdownMenuItem(onClick = { state.update { it.withValue(ref, option.toString()) }; expanded = false }) { Text(label) } } }
                }
            }
            "switch" -> Checkbox(checked = value == "true" || value == "1", onCheckedChange = { enabled -> state.update { it.withValue(ref, enabled.toString()) } }, modifier = Modifier.size(28.dp))
            else -> SmallField(value, Modifier.width(105.dp).testTag("effect-${param.id}")) { text ->
                val valid = when (param.kind) {
                    "float", "angle" -> text.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(param.min, param.max)?.toString()
                    "color" -> text.takeIf { parseColor(it) != null }
                    "point" -> text.takeIf { it.split(',').size == 2 && it.split(',').all { part -> part.trim().toFloatOrNull()?.isFinite() == true } }
                    else -> text
                }
                if (valid != null) state.update { it.withValue(ref, valid) }
            }
        }
        Text(if (layer.onKeyframe(ref)) "◆" else "◇", Modifier.testTag("key-${param.id}").clickable { state.update { it.withKeyframeToggled(ref, param.missingDefault) } }.padding(8.dp), color = accent)
    }
}

@Composable
fun Field(label: String, value: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.width(70.dp).padding(top = 10.dp), fontSize = 12.sp)
        SmallField(value, Modifier.weight(1f).padding(end = 8.dp), change)
    }
}

@Composable
fun SmallField(value: String, modifier: Modifier = Modifier, change: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    androidx.compose.foundation.text.BasicTextField(text, { text = it; change(it) }, modifier.padding(vertical = 4.dp).heightIn(min = 28.dp), singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth().background(Color(0xff373a43)).padding(6.dp)) { inner() } })
}

