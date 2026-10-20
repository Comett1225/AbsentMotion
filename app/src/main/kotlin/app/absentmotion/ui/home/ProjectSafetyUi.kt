package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.ProjectVersions
import app.absentmotion.model.Scene
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun OpeningOverlay(onCancel: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); shown = true; delay(4750); slow = true }
    Box(
        Modifier.fillMaxSize().background(if (shown) Color.Black.copy(alpha = 0.35f) else Color.Transparent).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        if (shown) Column(Modifier.size(220.dp, 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(44.dp), color = Am.Accent)
            if (slow) {
                Text("Still working…", Modifier.padding(top = 16.dp), color = Color.White, fontSize = 15.sp)
                TextButton(onCancel) { Text("Cancel", color = Color.White, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
fun VersionHistory(store: ProjectStore, id: String, title: String, engine: EngineThread, onRestore: (ProjectVersions.Version) -> Unit, onDismiss: () -> Unit) {
    val versions by produceState<List<ProjectVersions.Version>?>(null, id) { value = withContext(Dispatchers.IO) { store.versions.list(id) } }
    var chosen by remember { mutableStateOf<ProjectVersions.Version?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Version history", color = Am.Navy)
                Text(title, color = Am.HomeMid, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                val list = versions
                if (list != null && list.isEmpty()) Text(
                    "No earlier versions yet. A version is kept each time the project is opened or closed, and every two minutes while you edit.",
                    color = Am.Navy, fontSize = 15.sp,
                )
                if (list != null) LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.time }) { version -> VersionRow(version, engine) { chosen = version } }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close", color = Am.Navy) } },
        containerColor = Am.Card,
    )
    chosen?.let { version ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text("Restore this version?", color = Am.Navy) },
            text = { Text("The project goes back to how it was ${versionLabel(version.time).replaceFirstChar { it.lowercase() }}. The current state is kept in Version history, so nothing is lost.", color = Am.Navy) },
            dismissButton = { TextButton({ chosen = null }) { Text("Cancel", color = Am.Navy) } },
            confirmButton = { TextButton({ chosen = null; onRestore(version) }) { Text("Restore", color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

@Composable
private fun VersionRow(version: ProjectVersions.Version, engine: EngineThread, onRestore: () -> Unit) {
    val picture by produceState<ImageBitmap?>(null, version.file) { value = versionThumbnail(version, engine) }
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).checkerboard(4.dp)) {
            picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(versionLabel(version.time), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(fileSize(version.file.length()), color = Am.HomeMid, fontSize = 13.sp, maxLines = 1)
        }
        TextButton(onRestore) { Text("Restore", color = Am.Navy) }
    }
}

private suspend fun versionThumbnail(version: ProjectVersions.Version, engine: EngineThread): ImageBitmap? = try {
    val png = version.thumbnail
    if (!png.exists()) {
        val xml = withContext(Dispatchers.IO) { version.file.readText() }
        val scene = Scene.parse(xml)
        val long = maxOf(scene.width, scene.height, 1)
        val temp = File(png.parentFile, png.name + ".tmp")
        if (engine.stillPng(xml, scene.totalTime / 2, (160 * scene.width / long).coerceAtLeast(1), (160 * scene.height / long).coerceAtLeast(1), temp)) temp.renameTo(png)
    }
    withContext(Dispatchers.IO) { BitmapFactory.decodeFile(png.path)?.asImageBitmap() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

internal fun versionLabel(time: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(time).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (at.toLocalDate()) {
        today -> "Today at $clock"
        today.minusDays(1) -> "Yesterday at $clock"
        else -> "On " + at.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)) + " at $clock"
    }
}

@Composable
fun RecoveryDialog(title: String, onRestore: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("AbsentMotion closed unexpectedly", color = Am.Navy) },
        text = { Text("Restore your last edits to “$title”? If you discard them, they are kept in the project's Version history.", color = Am.Navy) },
        dismissButton = { TextButton(onDiscard) { Text("Discard", color = Am.Navy) } },
        confirmButton = { TextButton(onRestore) { Text("Restore", color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
fun OpenFailedDialog(onHistory: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Could not open this project", color = Am.Navy) },
        text = {
            Text(
                "The project file is missing or damaged, so nothing was changed." +
                    if (onHistory != null) " You can bring back an earlier version from Version history." else "",
                color = Am.Navy,
            )
        },
        dismissButton = onHistory?.let { { TextButton(it) { Text("Version history", color = Am.Navy) } } },
        confirmButton = { TextButton(onDismiss) { Text("OK", color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
