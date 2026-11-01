package app.absentmotion.pc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import app.absentmotion.model.Scene
import app.absentmotion.model.formatColor
import app.absentmotion.model.parseColor
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.platform.DesktopPlatform

@Composable
fun ProjectSettings(state: ProjectState, dismiss: () -> Unit, inline: Boolean = false) {
    var width by remember { mutableStateOf(state.scene.width.toString()) }
    var height by remember { mutableStateOf(state.scene.height.toString()) }
    var fps by remember { mutableStateOf(state.scene.fps.toString()) }
    var duration by remember { mutableStateOf((state.scene.totalTime / 1000f).toString()) }
    var color by remember { mutableStateOf(formatColor(state.scene.bgColor)) }
    val content: @Composable () -> Unit = {
        Surface(color = panel) {
            Column(Modifier.width(380.dp).padding(20.dp)) {
                Text("Project Settings", style = MaterialTheme.typography.h6)
                listOf(listOf("1920×1080" to (1920 to 1080), "1080×1920" to (1080 to 1920)), listOf("1080×1080" to (1080 to 1080), "3840×2160" to (3840 to 2160))).forEach { row ->
                    Row { row.forEach { (label, size) -> TextButton(onClick = { width = "${size.first}"; height = "${size.second}" }) { Text(label) } } }
                }
                Field("Width", width) { width = it }; Field("Height", height) { height = it }; Field("FPS", fps) { fps = it }
                Field("Seconds", duration) { duration = it }; Field("Background", color, 95.dp) { color = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val w = width.toIntOrNull()?.takeIf { it in 1..8192 }; val h = height.toIntOrNull()?.takeIf { it in 1..8192 }; val f = fps.toIntOrNull()?.takeIf { it in 1..120 }
                        val d = duration.toFloatOrNull()?.takeIf { it.isFinite() && it in 0.1f..86400f }; val bg = parseColor(color)
                        if (w != null && h != null && f != null && d != null && bg != null) { state.edit(preserveDuration = false) { Scene(it.withSettings(w, h, f, bg).node.with("totalTime", "${maxOf((d * 1000).toInt(), it.layers.maxOfOrNull { layer -> layer.endTime } ?: 0)}")) }; dismiss() }
                    }) { Text("Apply") }
                }
            }
        }
    }
    if (inline) DialogOverlay(content) else DialogWindow(onCloseRequest = dismiss, title = "Project Settings") { content() }
}

@Composable
fun KeyboardShortcuts(inline: Boolean = false, dismiss: () -> Unit) {
    val content: @Composable () -> Unit = {
        Surface(color = panel) {
            Column(Modifier.width(400.dp).padding(20.dp)) {
                Text("Keyboard Shortcuts", style = MaterialTheme.typography.h6)
                listOf(
                    "Play / Pause" to "Space", "Step Frame" to "Left / Right", "Step 10 Frames" to "Shift + Left / Right",
                    "Start / End" to "Home / End", "Undo / Redo" to "Cmd + Z / Shift + Cmd + Z",
                    "Cut / Copy / Paste" to "Cmd + X / C / V", "Select All" to "Cmd + A", "Duplicate" to "Cmd + D",
                    "Delete" to "Delete", "Split" to "Cmd + B", "Timeline Zoom" to "+ / −", "Nudge Layer" to "Arrow keys"
                ).forEach { (label, keys) -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(if (DesktopPlatform.mac) keys else keys.replace("Cmd", "Ctrl"), color = muted) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = dismiss) { Text("Close") } }
            }
        }
    }
    if (inline) DialogOverlay(content) else DialogWindow(onCloseRequest = dismiss, title = "Keyboard Shortcuts") { content() }
}

@Composable
private fun DialogOverlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f)), contentAlignment = androidx.compose.ui.Alignment.Center) { content() }
}
