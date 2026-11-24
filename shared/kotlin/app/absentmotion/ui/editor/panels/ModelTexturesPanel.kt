package app.absentmotion.ui.editor.panels

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FullColor
import app.absentmotion.library.modelFolder
import app.absentmotion.media.ModelFacts
import app.absentmotion.media.ModelFactsCache
import app.absentmotion.media.ModelMaterial
import app.absentmotion.media.companionName
import app.absentmotion.media.copyModelPicture
import app.absentmotion.media.modelPictureShown
import app.absentmotion.media.modelSkinned
import app.absentmotion.media.modelSource
import app.absentmotion.model.Layer
import app.absentmotion.model.material
import app.absentmotion.model.materialTint
import app.absentmotion.model.modelKey
import app.absentmotion.model.withMaterialPicture
import app.absentmotion.model.withMaterialTint
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.HoldName
import app.absentmotion.ui.adaptive.deskLayout
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.LocalEditorScope
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.home.AccentPill
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberModelFacts(layer: Layer?): ModelFacts? {
    val known by ModelFactsCache.known.collectAsState()
    val key = layer?.takeIf { it.kind == "model" }?.node?.modelKey() ?: return null
    return known[key] ?: known.entries.lastOrNull { modelSource(it.key) == modelSource(key) && modelSkinned(it.key) == modelSkinned(key) }?.value
}

internal fun ModelMaterial.missing(layer: Layer): Boolean = picture != null && !embedded && !found && layer.material(index)?.get("picture") == null

internal fun ModelMaterial.pictured(layer: Layer): Boolean = layer.material(index)?.get("picture")?.isNotEmpty() ?: found

internal fun missingPictureMatches(wanted: List<Pair<Int, String>>, picked: List<String>): List<Pair<Int, Int>> {
    val matched = wanted.mapNotNull { (index, name) -> companionName(name, picked)?.let { index to picked.indexOf(it) } }
    val left = picked.indices.filter { at -> matched.none { it.second == at } }
    return matched + wanted.filter { (index, _) -> matched.none { it.first == index } }.zip(left) { (index, _), at -> index to at }
}

@Composable
internal fun TexturesPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val facts = rememberModelFacts(layer)
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    val folder = remember(layer.node["src"]) { layer.node["src"]?.let(::modelFolder) }
    var replacing by remember { mutableIntStateOf(-1) }
    val materials = facts?.materials.orEmpty()
    val missing = materials.filter { it.missing(layer) }
    val failed = stringResource(R.string.model_picture_failed)
    fun add(uris: List<Uri>, targets: (List<String>) -> List<Pair<Int, Int>>) {
        val dir = folder ?: return
        val id = layer.id
        scope.launch {
            val resolver = context.contentResolver
            val names = withContext(Dispatchers.IO) {
                uris.map { uri -> resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "picture.png" }
            }
            val pairs = targets(names)
            val stored = withContext(Dispatchers.IO) { pairs.map { it.second }.distinct().associateWith { copyModelPicture(resolver, dir, uris[it], names[it]) } }
            val placed = pairs.mapNotNull { (index, at) -> stored[at]?.let { index to it } }
            if (placed.isEmpty()) { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show(); return@launch }
            state.edit { scene -> scene.update(id, state.sampleMs) { l -> placed.fold(l) { next, (index, name) -> next.withMaterialPicture(index, name) } } }
            val count = placed.map { it.second }.distinct().size
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.model_pictures_added, count, "$count", layer.label), Toast.LENGTH_SHORT).show()
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = replacing
        if (uri != null && index >= 0) add(listOf(uri)) { listOf(index to 0) }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) add(uris) { names -> missingPictureMatches(materials.filter { it.missing(layer) }.map { it.index to it.picture.orEmpty() }, names) }
    }
    Column(modifier.deskVerticalScroll().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(pluralStringResource(R.plurals.model_materials, materials.size, "${materials.size}"), Modifier.weight(1f), color = Am.Caption, fontSize = 12.sp, maxLines = 1)
            if (materials.isNotEmpty()) Text(
                if (missing.isEmpty()) stringResource(R.string.model_pictures_found) else pluralStringResource(R.plurals.model_pictures_missing, missing.size, "${missing.size}"),
                color = if (missing.isEmpty()) Am.Caption else Am.Warning, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (facts != null && materials.isEmpty()) Text(stringResource(R.string.model_no_materials), color = Am.Caption, fontSize = 13.sp)
        if (missing.isNotEmpty()) Text(stringResource(R.string.model_pictures_hint), color = Am.Caption, fontSize = 12.sp, lineHeight = 15.sp)
        for (m in materials) MaterialRow(
            layer, m, facts?.pictures?.getOrNull(m.texture), folder != null,
            onPicture = { replacing = m.index; pickOne.launch(arrayOf("image/*")) },
            onTint = {
                state.fullColor = FullColor(layer.materialTint(m.index)) { value, drag -> state.editSelected(if (drag) "model-tint-${m.index}" else null) { it.withMaterialTint(m.index, value) } }
            },
            onRemove = { state.editSelected { it.withMaterialPicture(m.index, "") } },
        )
        AccentPill(
            if (missing.isEmpty()) stringResource(R.string.model_add_missing) else stringResource(R.string.model_add_missing_count, "${missing.size}"),
            Modifier.fillMaxWidth().height(40.dp), enabled = missing.isNotEmpty() && folder != null,
        ) { pickMany.launch(arrayOf("image/*")) }
        if (deskLayout && materials.isNotEmpty()) Text(stringResource(R.string.model_textures_note), color = Am.Caption, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun MaterialRow(layer: Layer, m: ModelMaterial, picture: android.graphics.Bitmap?, editable: Boolean, onPicture: () -> Unit, onTint: () -> Unit, onRemove: () -> Unit) {
    val look = layer.material(m.index)?.get("picture")
    val missing = m.missing(layer)
    val pictured = m.pictured(layer)
    val caption = when {
        look != null -> if (look.isEmpty()) stringResource(R.string.model_picture_none) else modelPictureShown(look)
        missing -> stringResource(R.string.model_picture_missing, m.picture.orEmpty())
        m.picture == null -> stringResource(R.string.model_picture_none)
        m.embedded && m.picture.isEmpty() -> stringResource(R.string.model_picture_inside)
        else -> m.picture
    }
    Row(Modifier.fillMaxWidth().height(52.dp).clip(PanelShape).background(PanelDark).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val thumb = Modifier.size(40.dp).clip(RoundedCornerShape(5.dp))
        val image = remember(picture) { picture?.asImageBitmap() }
        if (image != null && pictured) Image(image, null, thumb, contentScale = ContentScale.Crop)
        else Box(thumb.background(Am.Field).then(if (missing) Modifier.border(1.dp, Am.Warning, RoundedCornerShape(5.dp)) else Modifier))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(m.name.ifEmpty { stringResource(R.string.model_material_number, "${m.index + 1}") }, color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, color = if (missing) Am.Warning else Am.Caption, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val label = stringResource(if (pictured) R.string.model_picture_replace else R.string.model_picture_add)
        RowPill(if (pictured) HomeIcons.Replace else ChromeIcons.Plus, label, if (pictured) Am.OnDark else Am.Accent, editable, onPicture)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(40.dp, 32.dp).clip(CircleShape).background(Am.Button).clickable(onClick = onTint), Alignment.Center) {
            ColourSwatch(layer.materialTint(m.index), 20.dp, round = true)
            HoldName(stringResource(R.string.color))
        }
        Spacer(Modifier.width(4.dp))
        RowPill(ChromeIcons.Trash, stringResource(R.string.model_picture_remove), Am.OnDark, editable && pictured, onRemove)
    }
}

@Composable
private fun RowPill(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp, 32.dp).clip(CircleShape).background(Am.Button).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.35f)), Alignment.Center) {
        PanelIcon(icon, tint, size = 18.dp, description = label)
        HoldName(label)
    }
}
