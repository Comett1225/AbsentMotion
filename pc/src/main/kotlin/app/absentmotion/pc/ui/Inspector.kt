package app.absentmotion.pc.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.effects.*
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.inspector.*

@Composable
fun Inspector(state: ProjectState, definitions: List<EffectDefinition>, addEffect: () -> Unit) {
    val layer = state.scene.layerAt(state.selected, state.time)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("INSPECTOR")
        if (layer == null) { Text("Select a layer", Modifier.padding(12.dp), color = muted, fontSize = 12.sp); return@Column }
        if (state.selectedIds.size > 1) Text("${state.selectedIds.size} layers selected", Modifier.padding(10.dp), color = muted, fontSize = 12.sp)
        InspectorSection("Layer") {
            Field("Name", layer.label) { v -> state.update { it.withLabel(v) } }
            Text(layer.kind.replaceFirstChar { it.uppercase() }, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = muted, fontSize = 11.sp)
            Field("Start", formatTime(layer.startTime, state.scene.fps)) { v -> parseTime(v, state.scene.fps)?.let { n -> state.update { it.trimmedTo(n.coerceIn(0, (it.endTime - 1).coerceAtLeast(0)), it.endTime, mediaDuration(state, it)) } } }
            Field("End", formatTime(layer.endTime, state.scene.fps)) { v -> parseTime(v, state.scene.fps)?.let { n -> state.update { it.trimmedTo(it.startTime, n.coerceAtLeast(it.startTime + 1), mediaDuration(state, it)) } } }
            ChoiceField("Blend", layer.blend, blends) { v -> state.update { it.withBlend(v) } }
            numberField(state, layer, "Opacity", "opacity", layer.opacity)
        }
        InspectorSection("Transform") {
            vectorFields(state, layer, "Position", "location", layer.location)
            vectorFields(state, layer, "Scale", "scale", layer.scale)
            Text(if (layer.scaleLinked) "Linked ●" else "Unlinked", Modifier.testTag("scale-link").clickable { state.updateSelection { it.withScaleLinked(!it.scaleLinked) } }.padding(start = 10.dp, bottom = 5.dp), color = accent, fontSize = 11.sp)
            numberField(state, layer, "Rotation", "rotation", layer.rotation)
            vectorFields(state, layer, "Pivot", "pivot", layer.pivot)
        }
        InspectorSection("Fill & Color", state.fillOpen, { state.fillOpen = it }) { FillFields(state, layer) }
        if (layer.kind == "text") InspectorSection("Text") { TextFields(state, layer) }
        InspectorSection("Border & Shadow", false) { StyleFields(state, layer) }
        InspectorSection("Effects") {
            layer.effects.forEachIndexed { index, effect ->
                val def = definitions.firstOrNull { it.id == effect["id"] }
                EffectCard(state, layer, index, effect, def)
            }
            Text("+ Add Effect", Modifier.testTag("add-effect").clickable(onClick = addEffect).padding(12.dp), color = accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun vectorFields(state: ProjectState, layer: Layer, label: String, name: String, values: FloatArray) {
    val ref = PropRef(PropRef.Kind.Transform, name)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp)
        values.take(2).forEachIndexed { i, number ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (i == 0) "X" else "Y", Modifier.padding(horizontal = 2.dp), color = muted, fontSize = 9.sp)
                ScrubField(number, Modifier.width(54.dp).testTag("$name-$i")) { n -> state.updateSelection { l ->
                    val v = l.transform(name, *values); val previous = v[i]; v[i] = n
                    if (name == "scale" && l.scaleLinked && v.size > 1) { val other = 1 - i; v[other] = if (previous == 0f) n else v[other] * n / previous }
                    l.withTransform(name, *v)
                } }
            }
        }
        Diamond(layer, ref, formatFloats(values)) { state.updateSelection(it) }
    }
}

@Composable
private fun numberField(state: ProjectState, layer: Layer, label: String, name: String, value: Float) {
    val ref = PropRef(PropRef.Kind.Transform, name)
    val update: ((Layer) -> Layer) -> Unit = if (name == "rotation") state::updateSelection else state::update
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp)
        ScrubField(value, Modifier.width(100.dp).testTag(name)) { n -> update { if (name == "rotation") it.withRotation(n) else it.withTransform(name, n.coerceIn(0f, 1f)) } }
        Diamond(layer, ref, "${if (name == "rotation") -value else value}") { update(it) }
    }
}

@Composable
fun Field(label: String, value: String, labelWidth: androidx.compose.ui.unit.Dp = 70.dp, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(labelWidth), fontSize = 12.sp)
        SmallField(value, Modifier.weight(1f).padding(end = 8.dp), change)
    }
}

@Composable
fun SmallField(value: String, modifier: Modifier = Modifier, change: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    BasicTextField(text, { text = it; change(it) }, modifier.onFocusChanged { focused = it.isFocused }.padding(vertical = 4.dp).heightIn(min = 28.dp), singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth().background(Color(0xff373a43)).padding(6.dp)) { inner() } })
}

@Composable
fun ScrubField(value: Float, modifier: Modifier = Modifier, step: Float = 0.01f, change: (Float) -> Unit) {
    var drag by remember { mutableFloatStateOf(value) }
    SmallField(value.toString(), modifier.pointerInput(value, step) {
        detectHorizontalDragGestures(onDragStart = { drag = value }, onHorizontalDrag = { event, amount -> event.consume(); drag += amount * step; if (drag.isFinite()) change(drag) })
    }) { it.toFloatOrNull()?.takeIf(Float::isFinite)?.let(change) }
}

private fun mediaDuration(state: ProjectState, layer: Layer): Int {
    val uri = layer.mediaFill?.uri ?: layer.node["src"] ?: return Int.MAX_VALUE
    return state.scene.media(uri)?.duration?.takeIf { it > 0 }?.div(1000)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
}
