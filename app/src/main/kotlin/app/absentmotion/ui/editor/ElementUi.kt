package app.absentmotion.ui.editor

import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.absentmotion.platform.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.ElementNav
import app.absentmotion.editor.addElement
import app.absentmotion.editor.saveToMyElements
import app.absentmotion.editor.unlinkElement
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

internal val PrecomposeOptions = listOf("dynamicResolution" to R.string.element_adaptive_resolution, "fixedResolution" to R.string.element_locked_resolution)
internal val RetimingOptions = listOf(
    "freeze" to R.string.element_retime_freeze, "stretch" to R.string.element_retime_stretch, "loop" to R.string.element_retime_loop,
    "loop-stretch" to R.string.element_retime_repeat_stretch, "blank" to R.string.element_retime_blank,
)

@Composable
private fun rememberStore(): ProjectStore {
    val context = LocalContext.current.applicationContext
    return remember { ProjectStore(context) }
}

@Composable
internal fun rememberSaveToMyElements(state: EditorState): (done: () -> Unit) -> Unit {
    val context = LocalContext.current
    val store = rememberStore()
    val scope = rememberCoroutineScope()
    return { done ->
        scope.launch {
            try {
                if (state.saveToMyElements(store)) Toast.makeText(context, context.getString(R.string.element_saved_to_my_assets), Toast.LENGTH_SHORT).show()
            } catch (e: java.io.IOException) {
                Toast.makeText(context, context.getString(R.string.element_save_failed), Toast.LENGTH_LONG).show()
            }
            done()
        }
    }
}

@Composable
internal fun ElementPicker(state: EditorState, modifier: Modifier) {
    val store = rememberStore()
    val scope = LocalEditorScope.current
    var items by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            store.list().filter { it.id != state.projectId }.sortedWith(compareByDescending<ProjectInfo> { it.isElement }.thenByDescending { it.modified })
        }
    }
    var search by remember { mutableStateOf("") }
    val query = debounced(search)
    val list = items
    val shown = remember(list, query) { list?.filter { matchesSearch(query, it.title) } }
    Column(modifier) {
        SearchField(search, { search = it }, Modifier.padding(start = 8.5.dp, end = 8.dp, top = 6.dp).fillMaxWidth().height(32.dp), hint = stringResource(R.string.element_search_hint))
        val body = Modifier.fillMaxWidth().weight(1f)
        when {
            list == null || shown == null -> Box(body)
            list.isEmpty() -> Box(body, Alignment.Center) { Text(stringResource(R.string.your_asset_collection_is_empty), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center) }
            shown.isEmpty() -> NoMatches(query, R.string.search_no_assets_or_projects, body)
            else -> LazyRow(body.padding(start = 8.5.dp), verticalAlignment = Alignment.CenterVertically) {
                items(shown, key = { it.id }) { info -> ElementCard(info) {
                    if (!state.addingMedia) scope.launch { state.addingMedia = true; try { state.addElement(store, info.id, link = info.isElement) } finally { state.addingMedia = false } }
                } }
            }
        }
    }
}

@Composable
private fun ElementCard(info: ProjectInfo, onClick: () -> Unit) {
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, info.id, info.modified) {
        value = withContext(Dispatchers.IO) { info.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    Box(Modifier.padding(horizontal = 2.5.dp).size(75.dp).clip(RoundedCornerShape(2.5.dp)).background(Am.PanelInset).clickable(onClick = onClick)) {
        thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Text(
            info.title, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.27f)).padding(horizontal = 3.dp),
            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

@Composable
internal fun ElementProperties(state: EditorState, onBack: () -> Unit, modifier: Modifier) {
    val layer = state.selected ?: return
    val link = layer.link
    val context = LocalContext.current
    val store = rememberStore()
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(false) }
    fun unlink() {
        state.unlinkElement()
        onBack()
    }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(onClick = onBack), Alignment.Center) { Icon(ChromeIcons.Back, stringResource(R.string.topbar_back), Modifier.size(24.dp), tint = Am.OnDark) }
            Text(stringResource(R.string.asset_properties), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PropertyButton(stringResource(R.string.element_open_original), Modifier.weight(1f), enabled = ElementNav.open != null && link != null) {
                scope.launch {
                    val original = withContext(Dispatchers.IO) { link?.let(store::idOfLink) }
                    if (original == null) missing = true else ElementNav.open?.invoke(original)
                }
            }
            PropertyButton(stringResource(R.string.element_unlink), Modifier.weight(1f), enabled = link != null) { unlink() }
        }
    }
    if (missing && link != null) AlertDialog(
        onDismissRequest = { missing = false },
        text = { Text(stringResource(R.string.this_asset_pointed_to_a_project)) },
        confirmButton = {
            TextButton({
                missing = false
                val nested = layer.nested
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { runCatching { nested?.let { store.save(store.linkOf(link), it) } }.isSuccess }
                    Toast.makeText(context, context.getString(if (saved) R.string.element_saved_to_my_assets else R.string.element_save_failed), if (saved) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.rebuild_source_project), color = Am.Accent) }
        },
        dismissButton = {
            Row {
                TextButton({ missing = false }) { Text(stringResource(R.string.cancel), color = Am.Accent) }
                TextButton({ missing = false; unlink() }) { Text(stringResource(R.string.element_unlink), color = Am.Accent) }
            }
        },
        containerColor = Am.Field, textContentColor = Am.OnDark,
    )
}

@Composable
private fun PropertyButton(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).alpha(if (enabled) 1f else DimAlpha).clip(RoundedCornerShape(5.dp)).background(Am.Button)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(5.dp),
        Alignment.Center,
    ) { Text(label, color = Am.OnDark, fontSize = 12.sp, maxLines = 2, lineHeight = 14.sp, textAlign = TextAlign.Center) }
}
