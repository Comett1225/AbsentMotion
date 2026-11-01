package app.absentmotion.pc.ui.inspector

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.*
import java.io.File

val blends = listOf("normal", "multiply", "darken", "darker-color", "color-burn", "linear-burn", "screen", "lighten", "lighter-color", "color-dodge", "linear-dodge", "overlay", "soft-light", "hard-light", "soft-overlay", "vivid-light", "linear-light", "pin-light", "diff", "exclusion", "subtract", "divide", "hue", "saturation", "color", "luminance", "mask", "exclude")

fun parseTime(text: String, fps: Int): Int? {
    val p = text.split(':').map { it.toIntOrNull() ?: return null }
    if (p.size != 4 || p.any { it < 0 } || p[1] >= 60 || p[2] >= 60 || p[3] >= fps) return null
    val ms = ((p[0].toLong() * 3600 + p[1] * 60 + p[2]) * 1000 + p[3] * 1000L / fps)
    return ms.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

@Composable
fun InspectorSection(title: String, initiallyOpen: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    Text("${if (open) "▾" else "▸"}  $title", Modifier.fillMaxWidth().background(Color(0xff30333b)).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp, color = Color.White)
    if (open) Column(content = content)
}

@Composable
fun ChoiceField(label: String, value: String, options: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(70.dp), fontSize = 12.sp)
        Box(Modifier.weight(1f)) {
            Text(value.replace('-', ' ').replaceFirstChar { it.uppercase() } + " ▾", Modifier.fillMaxWidth().clickable { open = true }.padding(7.dp), color = muted, fontSize = 12.sp)
            DropdownMenu(open, { open = false }) { options.forEach { option -> DropdownMenuItem(onClick = { change(option); open = false }) { Text(option.replace('-', ' ').replaceFirstChar { it.uppercase() }) } } }
        }
    }
}

@Composable
fun Diamond(layer: Layer, ref: PropRef, fallback: String, change: ((Layer) -> Layer) -> Unit) {
    Text(if (layer.onKeyframe(ref)) "◆" else "◇", Modifier.testTag("key-${ref.name}").clickable { change { it.withKeyframeToggled(ref, fallback) } }.padding(8.dp), color = accent)
}

@Composable
fun ColorField(label: String, color: Int, change: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(70.dp), fontSize = 12.sp)
        Box {
            Box(Modifier.size(24.dp).background(Color(color)).border(1.dp, muted).clickable { open = true })
            DropdownMenu(open, { open = false }) {
                listOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xffff0000.toInt(), 0xffffcc00.toInt(), 0xff00bb77.toInt(), 0xff2288ff.toInt(), 0xff9933ff.toInt()).forEach { c ->
                    DropdownMenuItem(onClick = { change(c); open = false }) { Box(Modifier.size(24.dp).background(Color(c))); Text("  ${formatColor(c)}") }
                }
            }
        }
        SmallField(formatColor(color), Modifier.weight(1f).padding(horizontal = 8.dp)) { parseColor(it)?.let(change) }
    }
}

@Composable
fun FillFields(state: ProjectState, layer: Layer) {
    val types = listOf("none", "color", "gradient", "media")
    ChoiceField("Fill", layer.fillType, types) { type -> state.update { l -> l.withFill(type).let { if (type == "gradient" && it.node.child("gradient") == null) it.withGradient(it.gradient) else it } } }
    when (layer.fillType) {
        "color" -> ColorField("Colour", layer.fillColor) { c -> state.update { it.withFill("color", c) } }
        "gradient" -> {
            val g = layer.gradient
            ChoiceField("Type", g.type, listOf("linear", "radial")) { v -> state.update { it.withGradient(it.gradient.copy(type = v)) } }
            ColorField("Start", g.startColor) { c -> state.update { it.withGradient(it.gradient.copy(startColor = c)) } }
            ColorField("End", g.endColor) { c -> state.update { it.withGradient(it.gradient.copy(endColor = c)) } }
        }
        "media" -> {
            val media = state.scene.media.filter { it.type.startsWith("image") || it.type.startsWith("video") }
            val names = media.map { it.title }
            ChoiceField("Media", layer.mediaFill?.uri?.let { state.scene.mediaName(it) } ?: "Select media", names) { name ->
                media.firstOrNull { it.title == name }?.let { selected -> state.update { l ->
                    val video = selected.type.startsWith("video")
                    l.copy(node = l.node.with("fillType", "media").with("fillImage", selected.uri.takeUnless { video }).with("fillVideo", selected.uri.takeIf { video }).with("mediaFillMode", "fill").with("inTime", if (video) "0" else null).with("outTime", if (video && selected.duration > 0) "${selected.duration / 1000}" else null))
                } }
            }
        }
    }
}

@Composable
fun TextFields(state: ProjectState, layer: Layer) {
    var content by remember(layer.content) { mutableStateOf(layer.content) }
    Text("Content", Modifier.padding(start = 8.dp, top = 4.dp), fontSize = 12.sp)
    BasicTextField(content, { content = it; state.update { l -> l.withContent(it) } }, Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 56.dp).background(Color(0xff373a43)).padding(6.dp).testTag("text-content"), textStyle = TextStyle(color = Color.White, fontSize = 12.sp))
    val fonts = remember { (System.getProperty("am.assets")?.let(::File) ?: File("../app/src/main/assets")).resolve("fonts").listFiles().orEmpty().filter(File::isDirectory).map { it.name }.sorted() }
    val family = Regex("(?:^|[?&])name=([^&]*)").find(layer.font)?.groupValues?.get(1) ?: "Roboto"
    val weight = Regex("(?:^|[?&])weight=(\\d+)").find(layer.font)?.groupValues?.get(1) ?: "400"
    ChoiceField("Family", family, fonts) { v -> state.update { it.withFont("googlefonts?name=$v&weight=$weight") } }
    ChoiceField("Weight", weight, listOf("100", "200", "300", "400", "500", "600", "700", "800", "900")) { v -> state.update { it.withFont("googlefonts?name=$family&weight=$v") } }
    Field("Size", layer.textSize.toString()) { v -> v.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { n -> state.update { it.withTextSize(n) } } }
    ChoiceField("Align", layer.textAlign, listOf("left", "center", "right")) { v -> state.update { it.withTextAlign(v) } }
    listOf("letterspacing" to "Letter", "linespacing" to "Line").forEach { (key, label) ->
        val fallback = if (key == "linespacing") 1f else 0f
        Field(label, layer.textSpacing(key, fallback).toString()) { v -> v.toFloatOrNull()?.takeIf(Float::isFinite)?.let { n -> state.update { l ->
            val id = app.absentmotion.compat.ForeignEffectIds.TEXT_SPACING
            val index = l.effects.indexOfFirst { it["id"] == id && it["hidden"] != "true" }
            val target = if (index < 0) l.addEffect(XmlNode("effect", mapOf("id" to id))) else l
            target.withEffect(if (index < 0) target.effects.lastIndex else index) { e -> e.withEffectProperty(key, "float", n.toString(), target.t, target.snap) }
        } } }
    }
    ColorField("Colour", layer.fillColor) { c -> state.update { it.withFill("color", c) } }
}

@Composable
fun StyleFields(state: ProjectState, layer: Layer) {
    layer.borders.forEach { b ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Border ${b.index + 1}", Modifier.weight(1f), fontSize = 12.sp)
            Text("↑", Modifier.clickable { state.update { it.withBorderMoved(b.index, b.index - 1) } }.padding(5.dp))
            Text("↓", Modifier.clickable { state.update { it.withBorderMoved(b.index, b.index + 1) } }.padding(5.dp))
            Text("×", Modifier.clickable { state.update { it.withBorderRemoved(b.index) } }.padding(5.dp))
        }
        ChoiceField("Direction", b.direction, listOf("inside", "centered", "outside")) { v -> state.update { it.withBorderDirection(b.index, v) } }
        ColorField("Colour", b.color) { c -> state.update { it.withValue(b.colorRef, formatColor(c)) } }
        Field("Size", b.size.toString()) { v -> v.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { n -> state.update { it.withValue(b.sizeRef, n.toString()) } } }
    }
    Text("+ Add Border", Modifier.clickable { state.update { it.withBorderAdded() } }.padding(8.dp), color = accent, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(layer.shadow.enabled, { enabled -> state.update { it.withShadowEnabled(enabled) } }); Text("Shadow", fontSize = 12.sp) }
    if (layer.shadow.enabled) {
        val s = layer.shadow
        ColorField("Colour", s.color) { c -> state.update { it.withValue(PropRef(PropRef.Kind.Child, "shadow/color"), formatColor(c)) } }
        listOf("Opacity" to s.opacity, "Size" to s.size).forEach { (label, value) -> Field(label, value.toString()) { v -> v.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { n -> state.update { it.withValue(PropRef(PropRef.Kind.Child, "shadow/${label.lowercase()}"), (if (label == "Opacity") n.coerceAtMost(1f) else n).toString()) } } } }
        listOf("X" to s.x, "Y" to s.y).forEachIndexed { i, (label, value) -> Field(label, value.toString()) { v -> v.toFloatOrNull()?.takeIf(Float::isFinite)?.let { n -> state.update { l -> val now = l.shadow; val xy = floatArrayOf(now.x, now.y); xy[i] = n; l.withValue(PropRef(PropRef.Kind.Child, "shadow/offset"), formatFloats(xy)) } } } }
    }
}
