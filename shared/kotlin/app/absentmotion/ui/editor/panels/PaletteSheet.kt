package app.absentmotion.ui.editor.panels

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.effects.Palettes
import app.absentmotion.export.AppFolder
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ColourPalette
import app.absentmotion.library.PaletteLibrary
import app.absentmotion.library.PaletteSort
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.rememberDocumentPicker
import app.absentmotion.platform.rememberMediaPicker
import app.absentmotion.ui.editor.BarButton
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.DimAlpha
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.MotionSheet
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun rememberPaletteLibrary(): PaletteLibrary {
    val context = LocalContext.current
    return remember { PaletteLibrary.at(context.filesDir) }
}

internal fun PaletteLibrary.swatches(saved: List<Int>): List<Int> = chosen.let { if (it.builtIn) it.colors + saved else it.colors }

internal fun PaletteLibrary.canKeep(rgb: Int, saved: List<Int>): Boolean = chosen.let {
    if (it.builtIn) rgb !in it.colors + saved else it.open && rgb !in it.colors
}

internal fun PaletteLibrary.keep(rgb: Int, settings: AppSettings) {
    val saved = settings.savedColours
    if (!canKeep(rgb, saved)) return
    if (chosen.builtIn) settings.savedColours = saved + rgb else put(chosen.added(rgb))
}

@Composable
internal fun ColourPalette.label(): String = if (title != 0) stringResource(title) else name.ifBlank { stringResource(R.string.palette_untitled) }

@Composable
internal fun PaletteChip(palette: ColourPalette, tint: Color, modifier: Modifier, stacked: Boolean = false, onClick: () -> Unit) {
    val chip = modifier.clip(PanelShape).background(if (stacked) Am.Panel else Color(0x33000000)).clickable(onClick = onClick)
    if (stacked) Column(chip.padding(4.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(palette.label(), color = tint, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Icon(ChromeIcons.ChevronDown, stringResource(R.string.palettes), Modifier.size(14.dp), tint = tint)
    } else Row(chip.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(palette.label(), Modifier.weight(1f, fill = false), color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(ChromeIcons.ChevronDown, stringResource(R.string.palettes), Modifier.padding(start = 2.dp).size(14.dp), tint = tint)
    }
}

internal enum class PaletteExport(val label: Int, val extension: String, val mime: String) {
    Png(R.string.palette_export_png, "png", "image/png"),
    Gpl(R.string.palette_export_gpl, "gpl", "application/octet-stream"),
    Ase(R.string.palette_export_ase, "ase", "application/octet-stream"),
}

private val SortLabels = mapOf(PaletteSort.Hue to R.string.palette_sort_hue, PaletteSort.Lightness to R.string.palette_sort_lightness, PaletteSort.Saturation to R.string.palette_sort_saturation)

internal fun readPaletteFile(context: Context, uri: Uri, picture: Boolean): Pair<String, List<Int>>? = runCatching {
    val (name, bytes) = paletteSource(context, uri) ?: return null
    val colors = (if (picture) null else Palettes.fromFile(name, bytes)) ?: pictureColors(bytes, Palettes.MAIN_COLORS)
    colors?.let { name.substringBeforeLast('.').ifBlank { name } to it }
}.getOrNull()

internal fun exportPalette(context: Context, name: String, colors: List<Int>, kind: PaletteExport): String {
    val file = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "palette" } + "." + kind.extension
    val store = MediaStoreExport(context)
    store.save(null, file, kind.mime) { out ->
        when (kind) {
            PaletteExport.Png -> {
                val (pixels, width, height) = Palettes.strip(colors)
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { bitmap.recycle() }
            }
            PaletteExport.Gpl -> out.write(Palettes.gpl(name, colors).toByteArray())
            PaletteExport.Ase -> out.write(Palettes.ase(colors))
        }
    }
    return store.location((if (kind == PaletteExport.Png) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOCUMENTS) + "/" + AppFolder)
}

@Composable
internal fun PaletteSheet(current: Int?, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = rememberPaletteLibrary()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }
    val unreadable = stringResource(R.string.effect_palette_unreadable)
    val fresh = stringResource(R.string.palette_new_name)
    val fromPicture = stringResource(R.string.palette_picture_name)
    fun opened(palette: ColourPalette) {
        library.choose(palette.id)
        editing = palette.id
    }
    fun imported(found: Pair<String, List<Int>>?) {
        if (found == null) Toast.makeText(context, unreadable, Toast.LENGTH_LONG).show() else opened(library.create(found.first, found.second))
    }
    val picture = rememberMediaPicker { uri -> if (uri != null) scope.launch { imported(withContext(Dispatchers.IO) { readPaletteFile(context, uri, picture = true) }?.let { fromPicture to it.second }) } }
    val file = rememberDocumentPicker { uri -> if (uri != null) scope.launch { imported(withContext(Dispatchers.IO) { readPaletteFile(context, uri, picture = false) }) } }
    val open = editing?.let { library[it] }
    MotionSheet(stringResource(R.string.palettes), onClose) {
        if (open == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), Arrangement.spacedBy(6.dp)) {
                SheetChip(stringResource(R.string.palette_new)) { opened(library.create(fresh, listOfNotNull(current))) }
                SheetChip(stringResource(R.string.palette_from_picture)) { picture.launch() }
                SheetChip(stringResource(R.string.palette_from_file)) { file.launch() }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).deskVerticalScroll()) {
                library.all.forEach { p ->
                    PaletteRow(p, p.id == library.chosenId, { library.choose(p.id); onClose() }) { editing = p.id }
                }
            }
        } else PaletteEditor(library, open, current, ::opened) { editing = null }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PaletteRow(palette: ColourPalette, chosen: Boolean, onPick: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(PanelShape).background(if (chosen) Am.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onPick).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(palette.label(), Modifier.weight(1f), color = if (chosen) Am.Accent else Am.OnDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (palette.locked && !palette.builtIn) Text(stringResource(R.string.palette_locked), Modifier.padding(horizontal = 6.dp), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
        PaletteStrip(palette.colors, Modifier.width(88.dp).height(14.dp).clip(PanelShape).background(Am.Field))
        BarButton(ChromeIcons.Pencil, stringResource(R.string.palette_edit), Modifier.size(44.dp), iconSize = androidx.compose.ui.unit.DpSize(20.dp, 20.dp), onClick = onEdit)
    }
}

@Composable
private fun PaletteEditor(library: PaletteLibrary, palette: ColourPalette, current: Int?, onOpen: (ColourPalette) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember(palette.id) { mutableStateOf<Int?>(null) }
    var name by remember(palette.id) { mutableStateOf(palette.name) }
    val latest by rememberUpdatedState(palette)
    LaunchedEffect(palette.id, name) { if (name != latest.name) { delay(500); library.put(latest.renamed(name)) } }
    DisposableEffect(palette.id) { onDispose { if (name != latest.name) library.put(latest.renamed(name)) } }
    var sortMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.palette_export_failed)
    val copyName = stringResource(R.string.palette_copy_name, palette.label())
    val pick = selected?.takeIf { it in palette.colors.indices }
    BackHandler(onBack = onBack)
    fun change(next: ColourPalette) { library.put(next) }
    fun export(kind: PaletteExport) {
        val label = (if (palette.title != 0) context.getString(palette.title) else palette.name).ifBlank { context.getString(R.string.palette_untitled) }
        scope.launch {
            val where = withContext(Dispatchers.IO) { runCatching { exportPalette(context, label, palette.colors, kind) }.getOrNull() }
            Toast.makeText(context, where?.let { context.getString(R.string.compress_saved_to, it) } ?: failed, Toast.LENGTH_LONG).show()
        }
    }

    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(ChromeIcons.Back, stringResource(R.string.topbar_back), Modifier.size(40.dp), onClick = onBack)
        if (palette.editable) BasicTextField(
            name, { name = it.take(ColourPalette.MAX_NAME) },
            Modifier.weight(1f).clip(PanelShape).background(Am.Field).padding(horizontal = 10.dp, vertical = 8.dp), singleLine = true,
            textStyle = TextStyle(color = Am.OnDark, fontSize = 15.sp), cursorBrush = SolidColor(Am.Accent),
        ) else Text(palette.label(), Modifier.weight(1f).padding(start = 6.dp), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        if (library.chosenId == palette.id) Text(stringResource(R.string.palette_in_use), Modifier.padding(horizontal = 6.dp), color = Am.Accent, fontSize = 13.sp, maxLines = 1)
        else SheetChip(stringResource(R.string.palette_use)) { library.choose(palette.id) }
    }
    Spacer(Modifier.height(6.dp))
    if (palette.colors.isEmpty()) Text(stringResource(R.string.palette_empty), Modifier.fillMaxWidth().padding(vertical = 18.dp), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center)
    else Column(Modifier.fillMaxWidth().heightIn(max = 172.dp).deskVerticalScroll(), Arrangement.spacedBy(4.dp)) {
        palette.colors.chunked(SwatchColumns).forEachIndexed { row, colours ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
                colours.forEachIndexed { column, rgb ->
                    val index = row * SwatchColumns + column
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(Color(rgb or (0xFF shl 24)))
                            .then(if (index == pick) Modifier.border(3.dp, Am.Accent, RoundedCornerShape(5.dp)).padding(3.dp).border(1.5.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier)
                            .buttonLabel(colourText(rgb or (0xFF shl 24))).clickable { selected = if (pick == index) null else index },
                    )
                }
                repeat(SwatchColumns - colours.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val note = when {
        palette.builtIn -> R.string.palette_built_in
        palette.locked -> R.string.palette_locked_note
        else -> null
    }
    if (note != null) Text(stringResource(note), Modifier.fillMaxWidth().padding(top = 8.dp), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 16.sp)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp)) {
        if (current != null) SheetChip(stringResource(R.string.palette_add), palette.open && palette.colors.size < Palettes.MAX_COLORS) {
            change(palette.added(current))
            selected = palette.colors.size
        }
        SheetChip(stringResource(R.string.palette_duplicate), palette.open && pick != null) { pick?.let { change(palette.duplicated(it)); selected = it + 1 } }
        SheetChip(stringResource(R.string.delete), palette.open && pick != null) { pick?.let { change(palette.deleted(it)); selected = null } }
        Box {
            SheetChip(stringResource(R.string.palette_sort), palette.editable && palette.colors.size > 1, ChromeIcons.ChevronDown) { sortMenu = true }
            Presence(sortMenu) {
                MenuPopup(DropOrRise, { sortMenu = false }) {
                    PaletteSort.entries.forEach { by -> MenuRow(stringResource(SortLabels.getValue(by))) { change(palette.sorted(by)); selected = null; sortMenu = false } }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
        Box {
            SheetChip(stringResource(R.string.export), palette.colors.isNotEmpty(), ChromeIcons.ChevronDown) { exportMenu = true }
            Presence(exportMenu) {
                MenuPopup(DropOrRise, { exportMenu = false }) {
                    PaletteExport.entries.forEach { kind -> MenuRow(stringResource(kind.label)) { exportMenu = false; export(kind) } }
                }
            }
        }
        SheetChip(stringResource(R.string.palette_copy)) { onOpen(library.create(copyName, palette.colors)) }
        if (palette.editable) SheetChip(stringResource(R.string.palette_delete), palette.removable) { askDelete = true }
    }
    if (palette.editable) Row(Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.palette_lock), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        PanelSwitch(palette.locked) { change(palette.locking(it)) }
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false }, containerColor = Am.Panel,
        title = { Text(stringResource(R.string.palette_delete_ask), color = Am.OnDark) },
        confirmButton = { TextButton({ askDelete = false; if (library.remove(palette.id)) onBack() }) { Text(stringResource(R.string.delete), color = Am.Accent) } },
        dismissButton = { TextButton({ askDelete = false }) { Text(stringResource(R.string.cancel), color = Am.OnDark) } },
    )
}

private const val SwatchColumns = 8

@Composable
private fun SheetChip(text: String, enabled: Boolean = true, trailing: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(PanelShape).background(Am.Field).alpha(if (enabled) 1f else DimAlpha).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
        if (trailing != null) Icon(trailing, null, Modifier.padding(start = 4.dp).size(16.dp), tint = Am.OnDark)
    }
}
