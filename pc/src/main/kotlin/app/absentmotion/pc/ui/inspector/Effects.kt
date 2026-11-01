package app.absentmotion.pc.ui.inspector

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.effects.*
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.*
import java.io.File
import app.absentmotion.pc.ui.library.LibrarySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.round

@Composable
fun EffectCard(state: ProjectState, layer: Layer, index: Int, effect: XmlNode, def: EffectDefinition?) {
    var open by remember(layer.id, index, effect["id"]) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp).background(androidx.compose.ui.graphics.Color(0xff2c2f36))) {
        Row(Modifier.fillMaxWidth().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (open) "▾" else "▸"} ${def?.name ?: effect["id"].orEmpty()}", Modifier.weight(1f).clickable { open = !open }, color = accent, fontSize = 12.sp, maxLines = 2)
            Text(if (effect["hidden"] == "true") "○" else "●", Modifier.clickable { state.update { it.withEffect(index) { e -> e.with("hidden", if (e["hidden"] == "true") "false" else "true") } } }.padding(4.dp))
            Text("↑", Modifier.clickable { state.update { it.moveEffect(index, index - 1) } }.padding(4.dp))
            Text("↓", Modifier.clickable { state.update { it.moveEffect(index, index + 1) } }.padding(4.dp))
            Text("×", Modifier.clickable { state.update { it.removeEffect(index) } }.padding(4.dp))
        }
        if (open && def != null) {
            if (def.presets.isNotEmpty()) Presets(state, layer, index, def)
            def.params.filter { def.shown(it, effect, layer.t) }.forEach { p ->
                val param = def.bounded(p, (layer.endTime - layer.startTime) / 1000f) { other -> layer.element(PropRef.effect(index, other.id))?.valueAt(layer.t)?.toFloatOrNull() ?: other.missingDefault.toFloatOrNull() ?: 0f }
                EffectField(state, layer, index, param)
            }
        }
    }
}

@Composable
private fun Presets(state: ProjectState, layer: Layer, index: Int, def: EffectDefinition) {
    var expanded by remember { mutableStateOf(false) }
    val settings = LibrarySettings.shared
    Box {
        Text("Presets ▾", Modifier.clickable { expanded = true }.padding(8.dp), color = muted, fontSize = 12.sp)
        DropdownMenu(expanded, { expanded = false }) {
            def.presets.forEach { preset ->
                val favoriteKey = "${def.key}.${preset.slug}"
                val favorite = settings.contains(def.id, preset.slug)
                Row(Modifier.width(220.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val assets = System.getProperty("am.assets")?.let(::File) ?: File("../app/src/main/assets")
                    val image by produceState<ImageBitmap?>(null, favoriteKey) { value = withContext(Dispatchers.IO) { runCatching { SkiaImage.makeFromEncoded(assets.resolve("effects/${def.thumbnail(preset)}").readBytes()).toComposeImageBitmap() }.getOrNull() } }
                    if (image != null) Image(image!!, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                    Text(preset.name, Modifier.weight(1f).clickable { state.update { it.withEffect(index) { def.instance(preset, layer.endTime - layer.startTime) } }; expanded = false }.padding(6.dp), fontSize = 12.sp)
                    Text(if (favorite) "★" else "☆", Modifier.clickable { settings.toggle(def.id, preset.slug) }.padding(6.dp), color = accent)
                }
            }
        }
    }
}

@Composable
private fun EffectField(state: ProjectState, layer: Layer, index: Int, param: EffectParam) {
    val ref = PropRef.effect(index, param.id)
    val value = layer.element(ref)?.valueAt(layer.t) ?: param.missingDefault
    fun set(v: String) { state.update { it.withEffect(index) { e -> e.withEffectProperty(param.id, param.type, v, it.t, it.snap) } } }
    when (param.kind) {
        "choice" -> {
            val current = value.toFloatOrNull()?.toInt() ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ChoiceField(param.label, param.options.getOrNull(current) ?: value, param.options) { set(param.options.indexOf(it).toString()) } }
                EffectDiamond(state, layer, index, param, value)
            }
        }
        "switch" -> Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(param.label, Modifier.weight(1f), fontSize = 11.sp)
            Checkbox(value == "true" || value == "1", { set(it.toString()) }, Modifier.size(28.dp))
            EffectDiamond(state, layer, index, param, value)
        }
        "color" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ColorField(param.label, parseColor(value) ?: 0xffffffff.toInt()) { set(formatColor(it)) } }
            EffectDiamond(state, layer, index, param, value)
        }
        "float", "angle", "point" -> {
            val factor = if (param.percent) 100f else 1f
            val values = value.split(',').map { it.trim().toFloatOrNull() ?: 0f }
            val count = if (param.kind == "point") 2 else 1
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(param.label, Modifier.weight(1f), fontSize = 11.sp, maxLines = 2)
                    repeat(count) { i ->
                        val n = values.getOrElse(i) { 0f }
                        ScrubField(n * factor, Modifier.width(if (count == 2) 55.dp else 82.dp).testTag(if (i == 0) "effect-${param.id}" else "effect-${param.id}-$i"), param.step * factor) { shown ->
                            val next = MutableList(count) { j -> values.getOrElse(j) { 0f } }
                            next[i] = (shown / factor).coerceIn(param.min, param.max)
                            set(formatFloats(next.toFloatArray()))
                        }
                    }
                    val unit = if (param.percent) "%" else if (param.kind == "angle") "°" else if (param.pixels) "px" else param.unit
                    if (unit.isNotEmpty()) Text(unit, fontSize = 10.sp, color = muted)
                    EffectDiamond(state, layer, index, param, value)
                }
                if (count == 1 && param.min.isFinite() && param.max.isFinite() && param.min > -1e8f && param.max < 1e8f && param.min < param.max) {
                    Slider(values.firstOrNull()?.coerceIn(param.min, param.max) ?: param.min, { n ->
                        val v = if (param.step > 0f) round(n / param.step) * param.step else n
                        set(v.coerceIn(param.min, param.max).toString())
                    }, Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 12.dp), valueRange = param.min..param.max)
                }
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(param.label, value, change = ::set) }
            EffectDiamond(state, layer, index, param, value)
        }
    }
}

@Composable
private fun EffectDiamond(state: ProjectState, layer: Layer, index: Int, param: EffectParam, value: String) {
    val ref = PropRef.effect(index, param.id)
    Text(if (layer.onKeyframe(ref)) "◆" else "◇", Modifier.testTag("key-${param.id}").clickable {
        state.update { l ->
            val ready = if (l.element(ref) == null) l.withEffect(index) { it.withEffectProperty(param.id, param.type, value, l.t, l.snap) } else l
            ready.withKeyframeToggled(ref, value)
        }
    }.padding(8.dp), color = accent)
}
