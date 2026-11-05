package app.absentmotion.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.media.cachedMediaThumbnail
import app.absentmotion.media.mediaThumbnail
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun DeskMedia(state: EditorState, library: AddLibrary) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    var query by remember { mutableStateOf("") }
    val settled = debounced(query)
    val shared = remember(library.media) { library.media.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskFileAction(stringResource(R.string.sounds_from_files), Modifier.weight(1f)) { MediaPicks.openFiles() }
                DeskFileAction(app.absentmotion.platform.linkFolderLabel(), Modifier.weight(1f)) { app.absentmotion.platform.linkMediaFolder() }
            }
            if (app.absentmotion.platform.PlatformFeatures.camera) DeskFileAction(stringResource(R.string.camera), Modifier.fillMaxWidth()) { state.playing = false; state.cameraOpen = true }
            SearchField(query, { query = it }, Modifier.fillMaxWidth().height(36.dp), hint = stringResource(R.string.mediatab_search_hint))
        }
        if (library.media.isEmpty() && query.isEmpty() && app.absentmotion.platform.libraryFolders().isEmpty()) Text(stringResource(R.string.desk_media_empty), Modifier.padding(12.dp), color = Am.OnDarkDim, fontSize = 13.sp)
        DeskFolderContent(library.media, settled, { query = "" }, library, { File(app.absentmotion.platform.libraryFilePath(it.uri)) }, { arrayOf(it.name, it.album) }, { it.uri.toString() }) { file ->
            val bitmap by produceState<android.graphics.Bitmap?>(cachedMediaThumbnail(file.uri), file.uri, file.modified, library.mediaRevision) { value = mediaThumbnail(context, file.uri) }
            val add = { if (!state.addingMedia) scope.launch { state.addingMedia = true; try { state.addPicked(context, file.uri, video = file.video, svg = file.svg) } finally { state.addingMedia = false } }; Unit }
            DeskTile(state, file.name, {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(ChromeIcons.MediaTab, null, Modifier.size(40.dp), tint = Am.OnDarkDim)
            }, { app.absentmotion.platform.revealDeskFile(file.uri) }, detail = file.album.takeIf { file.name in shared && it.isNotBlank() },
                remove = deskRemove(state, file.uri.toString(), library, audio = false) { unlink -> app.absentmotion.platform.removeFromLibrary(file.uri, unlink) }, add = add)
        }
    }
}

@Composable
internal fun DeskAudio(state: EditorState, library: AddLibrary) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    var query by remember { mutableStateOf("") }
    var speaking by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val preview = remember { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    val settled = debounced(query)
    val shared = remember(library.audio) {
        library.audio.groupBy { it.title.ifBlank { it.name } }.filterValues { it.size > 1 }.mapValues { (_, group) -> group.map { it.name.substringAfterLast('.', "").uppercase() }.distinct().size > 1 }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskFileAction(stringResource(R.string.sounds_from_files), Modifier.weight(1f)) { MediaPicks.openSounds() }
                DeskFileAction(app.absentmotion.platform.linkFolderLabel(), Modifier.weight(1f)) { app.absentmotion.platform.linkMediaFolder() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskFileAction(stringResource(R.string.view_all), Modifier.weight(1f)) { browsing = true }
                DeskFileAction(stringResource(R.string.text_to_speech), Modifier.weight(1f)) { speaking = true }
            }
            SearchField(query, { query = it }, Modifier.fillMaxWidth().height(36.dp), hint = stringResource(R.string.audiotab_search_hint))
        }
        if (library.audio.isEmpty() && query.isEmpty() && app.absentmotion.platform.libraryFolders().isEmpty()) Text(stringResource(R.string.sounds_empty), Modifier.padding(12.dp), color = Am.OnDarkDim, fontSize = 13.sp)
        DeskFolderContent(library.audio, settled, { query = "" }, library, { File(app.absentmotion.platform.libraryFilePath(android.net.Uri.parse(it.uri))) }, { arrayOf(it.title, it.name) }, { it.uri }) { file ->
            val label = file.title.ifBlank { file.name }
            val add = { if (!state.addingMedia) scope.launch { state.addingMedia = true; try { state.addPicked(context, android.net.Uri.parse(file.uri)) } finally { state.addingMedia = false } }; Unit }
            DeskTile(state, label, {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(ChromeIcons.AudioTab, null, Modifier.size(40.dp), tint = Am.OnDark)
                    val description = stringResource(if (preview.uri == file.uri) R.string.play_pause else R.string.play_play)
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        app.absentmotion.platform.Hint(description) {
                            Box(Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand).clickable { preview.toggle(file.uri) }, Alignment.Center) {
                                Icon(if (preview.uri == file.uri) ChromeIcons.Pause else ChromeIcons.Play, description, Modifier.size(18.dp), tint = Am.Accent)
                            }
                        }
                    }
                }
            }, { app.absentmotion.platform.revealDeskFile(android.net.Uri.parse(file.uri)) }, detail = shared[label]?.let { byExtension -> (if (byExtension) file.name.substringAfterLast('.', "").uppercase() else file.folder.substringAfterLast('/')).ifBlank { null } },
                remove = deskRemove(state, file.uri, library, audio = true) { unlink -> app.absentmotion.platform.removeFromLibrary(android.net.Uri.parse(file.uri), unlink) }, add = add)
        }
    }
    if (speaking) VoiceSheet(state) { speaking = false }
    if (browsing) DeskSoundBrowser(
        state, library.audio, preview,
        onAdd = { file -> if (!state.addingMedia) scope.launch { state.addingMedia = true; try { state.addPicked(context, android.net.Uri.parse(file.uri)) } finally { state.addingMedia = false } } },
        onReveal = { file -> app.absentmotion.platform.revealDeskFile(android.net.Uri.parse(file.uri)) },
    ) { browsing = false }
}

@Composable
private fun DeskFileAction(label: String, modifier: Modifier, action: () -> Unit) {
    Box(modifier.height(36.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).hoverFill(6.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = .08f)).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = action), Alignment.Center) {
        Text(label, color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun deskRemove(state: EditorState, uri: String, library: AddLibrary, audio: Boolean, run: (Boolean) -> Unit): DeskRemove? {
    if (!app.absentmotion.platform.PlatformFeatures.desktop) return null
    val folder = remember(uri, library.mediaRevision, library.audioRevision) { app.absentmotion.platform.libraryFolderName(android.net.Uri.parse(uri)) }
    val inUse = state.scene.media(uri) != null
    return DeskRemove(inUse, folder) { unlink ->
        run(unlink)
        if (audio) library.audioRevision++ else library.mediaRevision++
    }
}
