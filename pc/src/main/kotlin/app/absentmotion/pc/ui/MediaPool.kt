package app.absentmotion.pc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.pc.media.addMedia
import app.absentmotion.pc.project.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun MediaPool(state: ProjectState, import: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("+ Import Media", Modifier.clickable(onClick = import).padding(10.dp), color = accent, fontSize = 12.sp)
        state.scene.media.forEach { media ->
            val image by produceState<ImageBitmap?>(null, media.uri) {
                if (media.type.startsWith("image/")) value = withContext(Dispatchers.IO) { runCatching { SkiaImage.makeFromEncoded(File(URI(media.uri)).readBytes()).toComposeImageBitmap() }.getOrNull() }
            }
            Row(Modifier.fillMaxWidth().pointerInput(media) { detectTapGestures(onDoubleTap = { addMedia(state, media) }) }.padding(8.dp)) {
                if (image != null) Image(image!!, null, Modifier.size(42.dp)) else Spacer(Modifier.size(42.dp))
                Column(Modifier.padding(start = 8.dp)) { Text(media.title.ifBlank { media.filename }, fontSize = 12.sp, maxLines = 2); Text("Double-click to add", color = muted, fontSize = 10.sp) }
            }
        }
    }
}
