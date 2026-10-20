package app.absentmotion.ui.home

import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import app.absentmotion.platform.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.library.PresetStore
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.SavedPreset
import app.absentmotion.library.SavedTemplate
import app.absentmotion.library.TemplateStore
import app.absentmotion.ui.editor.NoMatches
import app.absentmotion.ui.editor.matchesSearch
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.enterFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val Grey get() = Am.HomeDim
private val FactText get() = Am.HomeFaint

@Composable
fun TemplatesTab(projects: ProjectStore, version: Int, onTemplate: (String) -> Unit, onProject: (String) -> Unit, modifier: Modifier = Modifier, query: String = "") {
    val context = LocalContext.current
    val templates = remember { TemplateStore(context) }
    val presets = remember { PresetStore(context) }
    val scope = rememberCoroutineScope()
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<Int, () -> Unit>?>(null) }
    val savedTemplates by produceState(emptyList<SavedTemplate>(), version, rev) { value = withContext(Dispatchers.IO) { templates.list() } }
    val savedPresets by produceState(emptyList<SavedPreset>(), version, rev) { value = withContext(Dispatchers.IO) { presets.list() } }
    val shownTemplates = remember(savedTemplates, query) { savedTemplates.filter { matchesSearch(query, it.title) } }
    val shownPresets = remember(savedPresets, query) { savedPresets.filter { matchesSearch(query, it.title) } }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(24.dp))
        Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SubChip(stringResource(R.string.template_my_templates), sub == 0) { sub = 0; menu = null }
            SubChip(stringResource(R.string.template_my_presets), sub == 1) { sub = 1; menu = null }
        }
        Spacer(Modifier.height(13.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sub == 0) {
                if (savedTemplates.isEmpty()) EmptyText(stringResource(R.string.template_none))
                else if (shownTemplates.isEmpty()) NoMatches(query, R.string.search_no_templates, Modifier.fillMaxWidth().padding(top = 120.dp), Am.Navy, 17.sp)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 28.dp)) {
                    items(shownTemplates, key = { it.id }) { t ->
                        TemplateCard(
                            t, menu == t.id, { menu = if (it) t.id else null }, { onTemplate(t.id) },
                            onProject = {
                                menu = null
                                scope.launch {
                                    try { onProject(withContext(Dispatchers.IO) { templates.createProject(t.scene, projects) }) }
                                    catch (e: IOException) { Toast.makeText(context, context.getString(R.string.home_create_project_failed), Toast.LENGTH_LONG).show() }
                                }
                            },
                            onRename = { menu = null; renaming = t.id to t.title },
                            onDelete = { menu = null; confirm = R.string.template_delete_template to { scope.launch { withContext(Dispatchers.IO) { templates.delete(t.id) }; rev++ } } },
                        )
                    }
                }
            } else {
                if (savedPresets.isEmpty()) EmptyText(stringResource(R.string.template_no_presets))
                else if (shownPresets.isEmpty()) NoMatches(query, R.string.search_no_presets, Modifier.fillMaxWidth().padding(top = 120.dp), Am.Navy, 17.sp)
                Column(Modifier.fillMaxSize()) {
                    if (savedPresets.isNotEmpty()) Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.delete_all), Modifier.clickable { confirm = R.string.template_delete_all_presets to { scope.launch { withContext(Dispatchers.IO) { presets.deleteAll() }; rev++ } } }.padding(vertical = 12.dp),
                            color = Am.Navy, fontSize = 15.sp,
                        )
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp)) {
                        items(shownPresets, key = { it.id }) { p ->
                            Card(Modifier.height(64.dp), onClick = null) {
                                Text(p.title, Modifier.weight(1f).padding(start = 16.dp), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Menu(
                                    menu == p.id, { menu = if (it) p.id else null },
                                    stringResource(R.string.rename) to HomeIcons.Pencil to { menu = null; renaming = p.id to p.title },
                                    stringResource(R.string.delete) to HomeIcons.Delete to { menu = null; confirm = R.string.template_delete_preset to { scope.launch { withContext(Dispatchers.IO) { presets.delete(p.id) }; rev++ } } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { (id, title) ->
        RenameDialog(
            title, onDone = { name ->
                val template = sub == 0
                scope.launch {
                    val renamed = withContext(Dispatchers.IO) { runCatching { if (template) templates.rename(id, name) else presets.rename(id, name) }.isSuccess }
                    if (!renamed) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.template_rename_failed), app.absentmotion.platform.Toast.LENGTH_SHORT).show()
                    rev++
                }
                renaming = null
            }, onDismiss = { renaming = null },
        )
    }
    confirm?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(title), color = Am.Navy) },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
            confirmButton = { TextButton({ confirm = null; action() }) { Text(stringResource(R.string.delete), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxSize().enterFade()) {
        Text(text, Modifier.align(Alignment.TopCenter).padding(top = 180.dp), color = Am.Navy, fontSize = 24.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SubChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.height(40.dp).shadow(3.dp, shape).clip(shape).background(animatedColor(if (selected) Am.Navy else Am.Card)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = animatedColor(if (selected) Am.OnNavy else Grey), fontSize = 18.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Card(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun TemplateCard(
    t: SavedTemplate, menuOpen: Boolean, onMenu: (Boolean) -> Unit, onOpen: () -> Unit,
    onProject: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    val scene = t.scene
    val ratio = aspectName(scene.width, scene.height)
    Card(Modifier.height(96.dp), onOpen) {
        Box(Modifier.padding(8.dp).size(80.dp).clip(RoundedCornerShape(8.dp)).checkerboard(5.dp)) {
            rememberFileBitmap(t.thumbnail, t.modified)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            val s = (scene.totalTime + 500) / 1000
            Text(
                String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60),
                Modifier.align(Alignment.BottomStart).padding(4.dp).background(Am.Navy, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                color = Am.OnNavy, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
            )
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(t.title, color = Am.Navy, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row {
                Fact(if (ratio != null) HomeIcons.Aspect else HomeIcons.Resolution, ratio ?: "${scene.width}x${scene.height}")
                if (ratio != null) Fact(HomeIcons.Resolution, "${minOf(scene.width, scene.height)}p")
            }
            Spacer(Modifier.height(4.dp))
            Row { Fact(HomeIcons.FrameRate, "${scene.fps}fps") }
        }
        Menu(
            menuOpen, onMenu,
            stringResource(R.string.template_open_as_project) to HomeIcons.Select to onProject,
            stringResource(R.string.rename) to HomeIcons.Pencil to onRename,
            stringResource(R.string.delete) to HomeIcons.Delete to onDelete,
        )
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(Modifier.widthIn(min = 85.dp).height(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Grey)
        Spacer(Modifier.width(6.dp))
        Text(text, color = FactText, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun Menu(open: Boolean, onOpen: (Boolean) -> Unit, vararg rows: Pair<Pair<String, ImageVector>, () -> Unit>) {
    Box {
        Box(Modifier.size(48.dp).clickable { onOpen(true) }, contentAlignment = Alignment.Center) { Icon(HomeIcons.More, stringResource(R.string.home_more), Modifier.size(24.dp), tint = Grey) }
        Box(Modifier.align(Alignment.BottomEnd).offset(y = 8.dp)) {
            RefMenu(open, { onOpen(false) }, Am.Card, RoundedCornerShape(14.dp), Modifier.width(246.dp)) {
                for ((label, action) in rows) {
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = action).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label.first, Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp)
                        Icon(label.second, null, Modifier.size(24.dp), tint = Am.HomeInk)
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberFileBitmap(file: File?, key: Any?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file, key) {
        value = file?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    return bitmap
}

@Composable
private fun RenameDialog(title: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rename), color = Am.Navy) },
        text = {
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Am.HomeBg).padding(12.dp),
                textStyle = TextStyle(color = Am.Navy, fontSize = 16.sp), singleLine = true, cursorBrush = SolidColor(Am.Navy),
            )
        },
        confirmButton = { TextButton({ onDone(text.ifBlank { title }) }) { Text(stringResource(R.string.done), color = Am.Navy) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
