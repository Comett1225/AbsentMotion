package app.absentmotion.pc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.pc.media.addMedia
import app.absentmotion.pc.media.mediaThumbnail
import app.absentmotion.pc.project.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaPool(state: ProjectState, import: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("+ Import Media", Modifier.clickable(onClick = import).padding(10.dp), color = accent, fontSize = 12.sp)
        if (state.scene.media.isEmpty()) Text("Import files to get started", Modifier.padding(10.dp), color = muted, fontSize = 12.sp)
        LazyColumn {
            items(state.scene.media, key = { it.uri }) { media ->
                val thumbnail by produceState<File?>(null, media.uri, state.file) {
                    value = withContext(Dispatchers.IO) { runCatching { mediaThumbnail(media, state.file?.parentFile) }.getOrNull() }
                }
                var dragged by remember(media.uri) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().pointerInput(media) { detectTapGestures(onDoubleTap = { addMedia(state, media) }) }
                    .pointerInput(media) { detectDragGestures(onDragStart = { dragged = false }, onDragEnd = { if (dragged) addMedia(state, media) }) { change, _ -> dragged = change.position.x > size.width; change.consume() } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (thumbnail != null) LibraryImage(thumbnail!!, Modifier.size(48.dp), androidx.compose.ui.layout.ContentScale.Fit) else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text(if (media.type.startsWith("audio/")) "♫" else "▣", color = muted) }
                    Column(Modifier.padding(start = 8.dp)) { Text(media.title.ifBlank { media.filename }, fontSize = 12.sp, maxLines = 2); Text("Double-click or drag to add", color = muted, fontSize = 10.sp) }
                }
            }
        }
    }
}
