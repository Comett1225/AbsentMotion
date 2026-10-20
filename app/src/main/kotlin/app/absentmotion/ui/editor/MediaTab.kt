package app.absentmotion.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addImage
import app.absentmotion.editor.addVideo
import app.absentmotion.editor.keepCopy
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.media.mediaThumbnail
import app.absentmotion.media.cachedMediaThumbnail
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

@Composable
internal fun MediaTab(state: EditorState, modifier: Modifier, library: AddLibrary) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var album by remember { mutableStateOf<String?>(null) }
    var albumsOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val files = library.media
    LaunchedEffect(files) { if (album != null && files.none { it.albumId == album }) album = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        library.mediaRevision++
        state.engine.refreshMedia()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                adding = true
                try { mediaDeclaration(context, uri)?.let { state.keepCopy(context, it) }?.let { if (it.type.startsWith("video/")) state.addVideo(it) else state.addImage(it) } } finally { adding = false }
            }
        }
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("media", android.content.Context.MODE_PRIVATE)
        fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        val visible = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val full = granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        if (!prefs.getBoolean("asked", false) && !full && !visible) {
            prefs.edit().putBoolean("asked", true).apply()
            permission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
    }
    LazyHorizontalGrid(GridCells.Fixed(2), modifier.clipToBounds(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        item(key = "albums") {
            Column(Modifier.width(97.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).background(Am.Well).semantics { contentDescription = "View All" }.clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }, Alignment.Center) { MediaGridIcon(Modifier.size(24.dp)) }
                Box(Modifier.fillMaxWidth().weight(1f).background(Am.Well).clickable { albumsOpen = true }, Alignment.Center) {
                    Text(files.firstOrNull { it.albumId == album }?.album ?: "All", color = Am.OnDark, fontSize = 16.sp, maxLines = 1)
                    MediaAlbumArrow(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp))
                    if (albumsOpen) Popup(onDismissRequest = { albumsOpen = false }, properties = PopupProperties(focusable = true)) {
                        Column(Modifier.width(220.dp).heightIn(max = 300.dp).background(Am.Field).verticalScroll(rememberScrollState())) {
                            val albums = listOf(null to "All") + files.distinctBy { it.albumId }.map { it.albumId to it.album }
                            albums.forEach { (id, name) ->
                                Box(Modifier.fillMaxWidth().height(44.dp).clickable { album = id; albumsOpen = false }.padding(horizontal = 16.dp), Alignment.CenterStart) {
                                    Text(name, color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        items(files.filter { album == null || it.albumId == album }, key = { it.uri.toString() }) { file ->
            val bitmap by produceState<android.graphics.Bitmap?>(cachedMediaThumbnail(file.uri), file.uri, file.modified, library.mediaRevision) { value = mediaThumbnail(context, file.uri) }
            Box(Modifier.size(97.dp).background(Color.Black).clickable(enabled = !adding) {
                scope.launch {
                    adding = true
                    try { mediaDeclaration(context, file.uri)?.let { state.keepCopy(context, it) }?.let { if (file.video) state.addVideo(it) else state.addImage(it) } } finally { adding = false }
                }
            }) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (file.video) {
                    MediaPlayIcon(Modifier.align(Alignment.BottomStart).padding(5.dp).size(12.dp))
                    val seconds = file.durationMs / 1000
                    Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x66000000)), color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}
