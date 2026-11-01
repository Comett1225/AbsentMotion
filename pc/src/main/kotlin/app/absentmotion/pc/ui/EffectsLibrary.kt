package app.absentmotion.pc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectPreset
import app.absentmotion.model.addEffect
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.library.LibrarySettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun LibraryImage(file: File, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull() }
    }
    Box(modifier.background(timeline), contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!, null, Modifier.fillMaxSize().testTag("library-image"), contentScale = scale)
    }
}

@Composable
fun EffectsLibrary(state: ProjectState, definitions: List<EffectDefinition>, assets: File, settings: LibrarySettings = LibrarySettings.shared) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var detail by remember { mutableStateOf<EffectDefinition?>(null) }
    var message by remember { mutableStateOf("") }
    val newest = remember(definitions) { val versions = definitions.mapNotNull { it.since }; val major = versions.mapNotNull { it.substringBefore('.').toIntOrNull() }.maxOrNull(); versions.filter { it.substringBefore('.').toIntOrNull() == major }.toSet() }
    fun canAdd(def: EffectDefinition) = def.available && state.layer?.kind != "audio" && (state.layer == null || def.category != "text" || state.layer?.kind == "text")
    fun add(def: EffectDefinition, preset: EffectPreset? = null) {
        val layer = state.layer
        if (layer == null) { message = "Select a layer first"; return }
        if (!canAdd(def)) { message = "Effect unavailable for this layer"; return }
        state.update { it.addEffect(def.instance(preset, layer.endTime - layer.startTime)) }
        message = "Added ${preset?.name ?: def.name}"
    }
    Column(Modifier.fillMaxSize()) {
        if (detail != null) {
            val def = detail!!
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", Modifier.clickable { detail = null }.padding(end = 12.dp), color = accent, fontSize = 12.sp)
                Text(def.name, Modifier.weight(1f), fontSize = 13.sp)
                Text(if (settings.contains(def.id)) "★" else "☆", Modifier.clickable { settings.toggle(def.id) }.padding(4.dp), color = accent)
            }
            LazyColumn(Modifier.weight(1f)) {
                item {
                    LibraryImage(assets.resolve("effects/${def.thumbnail}"), Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 8.dp))
                    Text(def.description, Modifier.padding(10.dp), color = muted, fontSize = 12.sp)
                    Text("+ Add Effect", Modifier.fillMaxWidth().clickable { add(def) }.padding(10.dp), color = accent, fontSize = 12.sp)
                    if (def.presets.isNotEmpty()) SectionTitle("Presets")
                }
                items(def.presets) { preset ->
                    EffectRow(def, preset, assets, canAdd(def), settings, { add(def, preset) }, { settings.toggle(def.id, preset.slug) }, {})
                }
            }
        } else {
            Field("Search", query) { query = it }
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Text("$category ▾", Modifier.clickable { expanded = true }, color = accent, fontSize = 12.sp)
                    DropdownMenu(expanded, { expanded = false }) {
                        (listOf("All", "New") + EffectCatalog.Categories.values).forEach { item -> DropdownMenuItem(onClick = { category = item; expanded = false }) { Text(item) } }
                    }
                }
            }
            val matches = definitions.filter { def ->
                (category == "All" || category == "New" && def.since in newest || EffectCatalog.Categories[def.category] == category) &&
                    (query.isBlank() || def.name.contains(query, true) || def.presets.any { it.name.contains(query, true) })
            }
            val favorites = matches.flatMap { def -> buildList {
                if (settings.contains(def.id)) add(def to null)
                def.presets.filter { settings.contains(def.id, it.slug) }.forEach { add(def to it) }
            } }
            LazyColumn(Modifier.weight(1f)) {
                if (favorites.isNotEmpty()) {
                    item { SectionTitle("Favorites") }
                    items(favorites) { (def, preset) -> EffectRow(def, preset, assets, canAdd(def), settings, { add(def, preset) }, { settings.toggle(def.id, preset?.slug) }, { detail = def }) }
                }
                item { SectionTitle(if (category == "All") "Effects" else category) }
                items(matches) { def ->
                    val presetHit = query.isNotBlank() && !def.name.contains(query, true) && def.presets.any { it.name.contains(query, true) }
                    EffectRow(def, null, assets, canAdd(def), settings, { if (presetHit) detail = def else add(def) }, { settings.toggle(def.id) }, { detail = def })
                }
            }
        }
        if (message.isNotBlank()) Text(message, Modifier.fillMaxWidth().padding(8.dp), color = accent, fontSize = 11.sp)
    }
}

@Composable
private fun EffectRow(def: EffectDefinition, preset: EffectPreset?, assets: File, available: Boolean, settings: LibrarySettings, add: () -> Unit, star: () -> Unit, info: () -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if (available) 1f else 0.45f).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = add), verticalAlignment = Alignment.CenterVertically) {
            LibraryImage(assets.resolve("effects/${if (preset == null) def.thumbnail else def.thumbnail(preset)}"), Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(preset?.name ?: def.name, fontSize = 12.sp, maxLines = 2)
                if (preset != null) Text(def.name, color = muted, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (preset == null) Text("ⓘ", Modifier.clickable(onClick = info).padding(5.dp), color = muted, fontSize = 14.sp)
        Text(if (settings.contains(def.id, preset?.slug)) "★" else "☆", Modifier.clickable(onClick = star).padding(5.dp), color = accent, fontSize = 16.sp)
    }
}
