package app.absentmotion.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.setValueLink
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am

private data class LinkChoice(val number: NumberRef, val name: String, val fallback: String)

@Composable
private fun transformChoices(): List<LinkChoice> {
    val labels = listOf(R.string.panel_position, R.string.link_scale, R.string.link_rotation, R.string.link_opacity, R.string.link_anchor, R.string.link_tilt, R.string.link_skew).map { stringResource(it) }
    val refs = listOf(PropRef.Location, PropRef.Scale, PropRef.Rotation, PropRef.Opacity, PropRef.Pivot, PropRef.Tilt, PropRef.Skew)
    return refs.flatMapIndexed { i, ref ->
        val count = when (ref) { PropRef.Location, PropRef.Pivot -> 3; PropRef.Rotation, PropRef.Opacity -> 1; else -> 2 }
        val fallback = List(count) { if (ref == PropRef.Scale || ref == PropRef.Opacity) "1" else "0" }.joinToString(",")
        List(count) { c -> LinkChoice(NumberRef(ref, c), labels[i] + if (count > 1) " ${"XYZ"[c]}" else "", fallback) }
    }
}

@Composable
internal fun ValueLinkControl(state: EditorState, target: KeyTarget?, modifier: Modifier = Modifier) {
    val layer = state.selected ?: return
    val usable = target != null && target.ref.kind in listOf(PropRef.Kind.Transform, PropRef.Kind.Effect) && target.type in listOf("float", "vec2", "vec3")
    val links = target?.let { t -> (0..2).mapNotNull { layer.element(t.ref)?.valueLink(it) } }.orEmpty()
    var open by remember(layer.id, target?.ref) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable(enabled = usable) { state.playing = false; open = true }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(PanelIcons.Link, stringResource(R.string.value_link), Modifier.size(20.dp), tint = if (links.isNotEmpty()) Am.Accent else Am.OnDark.copy(alpha = if (usable) 1f else .3f))
        Text(links.firstOrNull()?.let { state.scene.layer(it.layer)?.label } ?: stringResource(R.string.value_link), color = Am.OnDark, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open && target != null) ValueLinkDialog(state, target) { open = false }
}

@Composable
private fun ValueLinkDialog(state: EditorState, target: KeyTarget, close: () -> Unit) {
    val layer = state.selected ?: return
    val catalog by state.engine.effects.collectAsState()
    val transforms = transformChoices()
    val count = if (target.ref.kind == PropRef.Kind.Transform) transforms.count { it.number.ref == target.ref } else target.default.split(',').size
    var component by remember { mutableIntStateOf(0) }
    val number = NumberRef(target.ref, component)
    val old = layer.element(target.ref)?.valueLink(component)
    var sourceId by remember(component) { mutableStateOf(old?.layer) }
    var source by remember(component) { mutableStateOf(old?.source) }
    var stage by remember(component) { mutableIntStateOf(if (old == null) 0 else 2) }
    var multiply by remember(component) { mutableStateOf((old?.multiply ?: 1f).toString()) }
    var add by remember(component) { mutableStateOf((old?.add ?: 0f).toString()) }
    var delay by remember(component) { mutableStateOf((old?.delay ?: 0f).toString()) }
    val chosen = state.scene.layer(sourceId ?: 0)
    val choices = remember(chosen?.node, catalog, transforms) {
        transforms + chosen?.effects.orEmpty().flatMapIndexed { index, effect ->
            val def = catalog.find(effect["id"] ?: "")
            def?.params.orEmpty().filter { it.kind in listOf("float", "angle", "point") }.flatMap { p ->
                List(if (p.kind == "point") 2 else 1) { c -> LinkChoice(NumberRef(PropRef.effect(index, p.id), c), "${index + 1}. ${def?.name.orEmpty()}: ${p.label}" + if (p.kind == "point") " ${"XY"[c]}" else "", p.missingDefault) }
            }
        }
    }
    val m = multiply.finiteFloat()?.takeIf { it in -100000f..100000f }
    val a = add.finiteFloat()?.takeIf { it in -1000000f..1000000f }
    val d = delay.finiteFloat()?.takeIf { it in -3600f..3600f }
    val link = if (sourceId != null && source != null && m != null && a != null && d != null) ValueLink(sourceId!!, source!!, m, a, d, choices.firstOrNull { it.number == source }?.fallback?.split(',')?.getOrNull(source!!.component)?.finiteFloat() ?: 0f) else null
    val valid = link != null && state.scene.canLink(layer.id, number, link)
    AlertDialog(onDismissRequest = close, containerColor = Am.Panel,
        title = { Text(stringResource(R.string.value_link), color = Am.OnDark) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (count > 1) Row {
                    repeat(count.coerceAtMost(3)) { c -> TextButton(onClick = { component = c }) { Text("${"XYZ"[c]}", color = if (c == component) Am.Accent else Am.OnDark) } }
                }
                if (stage > 0) TextButton(onClick = { stage--; if (stage == 0) source = null }) { Text(stringResource(R.string.topbar_back), color = Am.Accent) }
                when (stage) {
                    0 -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(state.scene.layers, key = { it.id }) { l ->
                            Text(l.label.ifBlank { l.kind }, Modifier.fillMaxWidth().clickable { sourceId = l.id; source = null; stage = 1 }.padding(vertical = 14.dp), color = Am.OnDark)
                        }
                    }
                    1 -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(choices) { c ->
                            val allowed = state.scene.canLink(layer.id, number, ValueLink(sourceId ?: 0, c.number))
                            Text(c.name, Modifier.fillMaxWidth().clickable(enabled = allowed) { source = c.number; stage = 2 }.padding(vertical = 14.dp), color = Am.OnDark.copy(alpha = if (allowed) 1f else .35f))
                        }
                    }
                    else -> {
                        Text("${chosen?.label.orEmpty()} · ${choices.firstOrNull { it.number == source }?.name.orEmpty()}", color = Am.Accent)
                        LinkField(stringResource(R.string.link_multiply), multiply) { multiply = it }
                        LinkField(stringResource(R.string.link_add), add) { add = it }
                        LinkField(stringResource(R.string.link_delay), delay) { delay = it }
                        if (link != null && !valid) Text(stringResource(R.string.link_cycle), color = Am.OnDark)
                    }
                }
            }
        },
        confirmButton = { if (stage == 2) TextButton(enabled = valid, onClick = { if (state.setValueLink(number, link, target.default)) close() }) { Text(stringResource(R.string.apply), color = if (valid) Am.Accent else Am.OnDark.copy(alpha = .35f)) } },
        dismissButton = {
            Row {
                if (old != null) TextButton(onClick = { state.setValueLink(number, null, target.default); close() }) { Text(stringResource(R.string.link_unlink), color = Am.Accent) }
                TextButton(onClick = close) { Text(stringResource(R.string.cancel), color = Am.Accent) }
            }
        })
}

@Composable
private fun LinkField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Am.OnDark, unfocusedTextColor = Am.OnDark, cursorColor = Am.Accent,
        focusedLabelColor = Am.Accent, unfocusedLabelColor = Am.OnDark.copy(alpha = .7f),
        focusedBorderColor = Am.Accent, unfocusedBorderColor = Am.OnDark.copy(alpha = .5f),
    ))
}

@Composable
internal fun linkedNumbers(state: EditorState, layer: Layer, refs: List<PropRef>): Map<PropRef, FloatArray> {
    val linked = refs.filter { ref -> layer.element(ref)?.children?.any { it.name == "valueLink" } == true }
    val values by produceState(emptyMap<PropRef, FloatArray>(), state.scene.node, layer.id, state.sampleMs, linked) {
        value = linked.mapNotNull { ref ->
            state.engine.propertyNumbers(layer.id, ref.effectIndex, ref.name, state.sampleMs)?.let { ref to it }
        }.toMap()
    }
    return values.filterKeys { it in linked }
}
