package app.absentmotion.pc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.addEffect
import app.absentmotion.pc.project.ProjectState
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun EffectsLibrary(state: ProjectState, definitions: List<EffectDefinition>, assets: File) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    Column(Modifier.fillMaxSize()) {
        Field("Search", query) { query = it }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            var expanded by remember { mutableStateOf(false) }
            Box { Text("$category ▾", Modifier.clickable { expanded = true }, color = accent, fontSize = 12.sp)
                androidx.compose.material.DropdownMenu(expanded, { expanded = false }) {
                    (listOf("All") + EffectCatalog.Categories.values).forEach { item -> androidx.compose.material.DropdownMenuItem(onClick = { category = item; expanded = false }) { Text(item) } }
                }
            }
        }
        LazyColumn {
            items(definitions.filter { (category == "All" || EffectCatalog.Categories[it.category] == category) && (query.isBlank() || it.name.contains(query, true) || it.presets.any { p -> p.name.contains(query, true) }) }) { def ->
                Row(Modifier.fillMaxWidth().clickable(enabled = state.layer != null) { state.update { it.addEffect(def.instance()) } }.padding(6.dp)) {
                    val image by produceState<ImageBitmap?>(null, def.key) {
                        value = withContext(Dispatchers.IO) { runCatching { SkiaImage.makeFromEncoded(assets.resolve("effects/${def.thumbnail}").readBytes()).toComposeImageBitmap() }.getOrNull() }
                    }
                    if (image != null) Image(image!!, null, Modifier.size(46.dp)) else Spacer(Modifier.size(46.dp))
                    Column(Modifier.padding(start = 8.dp)) { Text(def.name, color = accent, fontSize = 12.sp); Text(def.description, color = muted, fontSize = 10.sp, maxLines = 2) }
                }
            }
        }
    }
}
