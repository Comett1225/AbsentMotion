package app.absentmotion.pc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.absentmotion.pc.media.Exporter
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.platform.chooseExport
import app.absentmotion.pc.project.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File
import java.util.concurrent.CancellationException

@Composable
fun ExportDialog(state: ProjectState, window: Window?, dismiss: () -> Unit) {
    val scene = state.scene
    var format by remember { mutableStateOf("MP4 H.264") }
    var size by remember { mutableStateOf("Project size") }
    var width by remember { mutableStateOf("${scene.width}") }
    var height by remember { mutableStateOf("${scene.height}") }
    var fps by remember { mutableStateOf("${scene.fps}") }
    var quality by remember { mutableStateOf("High") }
    var range by remember { mutableStateOf("Whole project") }
    val folder = state.file?.parentFile ?: File(System.getProperty("user.home"))
    val name = state.file?.nameWithoutExtension ?: "Untitled"
    var output by remember { mutableStateOf(folder.resolve("$name.mp4").absolutePath) }
    var progress by remember { mutableStateOf<Exporter.Progress?>(null) }
    var message by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var exporter by remember { mutableStateOf<Exporter?>(null) }
    val content: @Composable () -> Unit = {
        Surface(color = panel, modifier = Modifier.testTag("export-dialog")) {
            Column(Modifier.width(520.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export", style = MaterialTheme.typography.h6)
                Choice("Format", format, listOf("MP4 H.264", "MP4 HEVC", "PNG sequence", "GIF"), !running) {
                    format = it
                    val ext = when (it) { "PNG sequence" -> ""; "GIF" -> ".gif"; else -> ".mp4" }
                    output = File(output).parentFile.resolve(File(output).nameWithoutExtension + ext).absolutePath
                }
                Choice("Resolution", size, listOf("Project size", "4K", "1080p", "720p", "Custom"), !running) {
                    size = it
                    val dimensions = when (it) {
                        "4K" -> 3840 to 2160
                        "1080p" -> 1920 to 1080
                        "720p" -> 1280 to 720
                        else -> scene.width to scene.height
                    }
                    if (it != "Custom") {
                        width = "${if (scene.height > scene.width && it != "Project size") dimensions.second else dimensions.first}"
                        height = "${if (scene.height > scene.width && it != "Project size") dimensions.first else dimensions.second}"
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(width, { width = it; size = "Custom" }, Modifier.weight(1f).testTag("export-width"), enabled = !running, label = { Text("Width") }, singleLine = true)
                    OutlinedTextField(height, { height = it; size = "Custom" }, Modifier.weight(1f).testTag("export-height"), enabled = !running, label = { Text("Height") }, singleLine = true)
                }
                Choice("FPS", fps, (listOf(scene.fps, 24, 25, 30, 60).distinct()).map(Int::toString), !running) { fps = it }
                Choice("Quality", quality, listOf("Low", "Medium", "High"), !running) { quality = it }
                Choice("Range", range, listOf("Whole project", "Work area"), !running) { range = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(output, { output = it }, Modifier.weight(1f).testTag("export-output"), enabled = !running, label = { Text(if (format == "PNG sequence") "Output folder" else "Output file") }, singleLine = true)
                    TextButton(enabled = !running && window != null, onClick = { window?.let { chooseExport(it, File(output)) }?.let { output = it.absolutePath } }) { Text("Browse") }
                }
                if (running) {
                    LinearProgressIndicator(progress = progress?.let { it.frame.toFloat() / it.total } ?: 0f, modifier = Modifier.fillMaxWidth())
                    Text(progress?.let { "${it.frame} / ${it.total} frames · ${it.secondsLeft ?: 0} s left" } ?: "Preparing audio…", color = muted)
                }
                if (message.isNotEmpty()) Text(message, color = if (message.startsWith("Export verified")) Color(0xff8fdbad) else Color(0xffffae9e), modifier = Modifier.testTag("export-message"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (running) exporter?.cancel() else dismiss() }) { Text(if (running) "Cancel export" else "Close") }
                    Button(enabled = !running, onClick = {
                        val w = width.toIntOrNull(); val h = height.toIntOrNull(); val rate = fps.toIntOrNull()
                        if (w == null || h == null || w !in 1..8192 || h !in 1..8192 || rate == null || rate !in 1..120 || output.isBlank()) message = "Enter a valid size, FPS, and output."
                        else if (format.startsWith("MP4") && (w % 2 != 0 || h % 2 != 0)) message = "Video width and height must be even."
                        else {
                            val fmt = when (format) { "GIF" -> Exporter.Format.GIF; "PNG sequence" -> Exporter.Format.PNG; else -> Exporter.Format.MP4 }
                            val bitrate = (w.toLong() * h * rate * when (quality) { "Low" -> .06; "Medium" -> .12; else -> .2 }).toInt().coerceIn(300_000, 100_000_000)
                            val start = if (range == "Work area") scene.node["workStart"]?.toIntOrNull() ?: 0 else 0
                            val end = if (range == "Work area") scene.node["workEnd"]?.toIntOrNull() ?: scene.totalTime else scene.totalTime
                            val options = Exporter.Options(fmt, w, h, rate, bitrate, File(output), format == "MP4 HEVC", start, end)
                            val task = Exporter(DesktopPlatform.assets(), System.getProperty("am.repo")?.let(::File))
                            exporter = task
                            running = true; progress = null; message = ""
                            scope.launch {
                                val result = runCatching { withContext(Dispatchers.IO) { task.export(scene, state.file?.parentFile, options) { value -> scope.launch { progress = value } } } }
                                running = false
                                message = result.fold({ "Export verified: ${it.width} × ${it.height}, ${it.frames} frames, ${"%.2f".format(it.duration)} s." }, { if (it is CancellationException) "Export cancelled." else it.message ?: "Export failed." })
                            }
                        }
                    }, modifier = Modifier.testTag("start-export")) { Text("Export") }
                }
            }
        }
    }
    if (window == null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) { content() }
    else DialogWindow(onCloseRequest = { if (!running) dismiss() }, title = "Export", state = rememberDialogState(width = 560.dp, height = 700.dp)) { content() }
}

@Composable
private fun Choice(label: String, value: String, values: List<String>, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp), color = muted)
        Box {
            TextButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.testTag("export-${label.lowercase()}")) { Text("$value ▾") }
            DropdownMenu(expanded, { expanded = false }) { values.forEach { item -> DropdownMenuItem(onClick = { expanded = false; change(item) }) { Text(item) } } }
        }
    }
}
