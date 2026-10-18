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

internal val PrecomposeOptions = listOf("dynamicResolution" to "Adaptive Resolution", "fixedResolution" to "Locked Resolution")
internal val RetimingOptions = listOf(
    "freeze" to "Freeze", "stretch" to "Stretch", "loop" to "Loop", "loop-stretch" to "Repeat & Stretch", "blank" to "Blank",
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
            if (state.saveToMyElements(store)) {
                Toast.makeText(context, "Saved to My Assets", Toast.LENGTH_SHORT).show()
            }
            done()
        }
    }
}

@Composable
internal fun ElementPicker(state: EditorState, modifier: Modifier) {
    val store = rememberStore()
    var items by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            store.list().filter { it.id != state.projectId }.sortedWith(compareByDescending<ProjectInfo> { it.isElement }.thenByDescending { it.modified })
        }
    }
    val list = items
    when {
        list == null -> Box(modifier)
        list.isEmpty() -> Box(modifier, Alignment.Center) { Text("Your asset collection is empty", color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center) }
        else -> LazyRow(modifier.padding(start = 8.5.dp), verticalAlignment = Alignment.CenterVertically) {
            items(list, key = { it.id }) { info -> ElementCard(info) { state.addElement(store, info.id, link = info.isElement) } }
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
    val context = LocalContext.current
    val store = rememberStore()
    val original = layer.link?.let(store::idOfLink)
    var missing by remember { mutableStateOf(false) }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(onClick = onBack), Alignment.Center) { Icon(ChromeIcons.Back, "Back", Modifier.size(24.dp), tint = Am.OnDark) }
            Text("Asset Properties", color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PropertyButton("Open original Asset", Modifier.weight(1f), enabled = ElementNav.open != null) {
                if (original == null) missing = true else ElementNav.open?.invoke(original)
            }
            PropertyButton("Convert to Group", Modifier.weight(1f)) { state.unlinkElement() }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PropertyButton("Unlink from Asset", Modifier.weight(1f)) { state.unlinkElement() }
            Box(Modifier.weight(1f))
        }
    }
    if (missing) AlertDialog(
        onDismissRequest = { missing = false },
        text = { Text("This asset pointed to a project that has been deleted.\n\nRebuild a project from this asset, or detach it and edit it directly?") },
        confirmButton = {
            TextButton({
                missing = false
                layer.nested?.let { store.save(store.linkOf(layer.link.orEmpty()), it) }
                Toast.makeText(context, "Saved to My Assets", Toast.LENGTH_SHORT).show()
            }) { Text("Rebuild Source Project", color = Am.Accent) }
        },
        dismissButton = {
            Row {
                TextButton({ missing = false }) { Text("Cancel", color = Am.Accent) }
                TextButton({ missing = false; state.unlinkElement() }) { Text("Convert to Group", color = Am.Accent) }
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
