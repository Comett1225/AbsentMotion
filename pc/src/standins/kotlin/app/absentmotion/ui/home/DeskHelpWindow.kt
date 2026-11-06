package app.absentmotion.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.prefs.Preferences

private object HelpWindowOwner {
    var token: Any? = null
    var close: (() -> Unit)? = null
}

private fun visibleOnAScreen(x: Int, y: Int, w: Int): Boolean =
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.contains(x + w / 2, y + 20) }

@Composable
fun HelpScreen(page: String = "index.html", onClose: () -> Unit) {
    val token = remember { Any() }
    DisposableEffect(token) {
        HelpWindowOwner.close?.invoke()
        HelpWindowOwner.token = token
        HelpWindowOwner.close = onClose
        onDispose { if (HelpWindowOwner.token === token) { HelpWindowOwner.token = null; HelpWindowOwner.close = null } }
    }
    val prefs = remember { Preferences.userNodeForPackage(HelpWindowOwner::class.java) }
    val width = remember { prefs.getInt("helpWidth", 720).coerceIn(420, 3000) }
    val height = remember { prefs.getInt("helpHeight", 820).coerceIn(320, 3000) }
    val x = remember { prefs.getInt("helpX", Int.MIN_VALUE) }
    val y = remember { prefs.getInt("helpY", Int.MIN_VALUE) }
    val placed = remember {
        val (screens, primary) = app.absentmotion.desk.DeskWindow.screens()
        val saved = if (x != Int.MIN_VALUE && y != Int.MIN_VALUE && visibleOnAScreen(x, y, width)) java.awt.Rectangle(x, y, width, height) else null
        saved?.let { app.absentmotion.desk.DeskWindow.place(it, screens, primary, 420, 320) }
            ?: java.awt.Rectangle(0, 0, width.coerceAtMost(primary.width), height.coerceAtMost(primary.height))
    }
    val state = rememberDialogState(
        position = if (x != Int.MIN_VALUE && y != Int.MIN_VALUE && visibleOnAScreen(x, y, width)) WindowPosition(placed.x.dp, placed.y.dp) else WindowPosition(androidx.compose.ui.Alignment.Center),
        width = placed.width.dp, height = placed.height.dp,
    )
    val clean = page.substringBefore('#').takeIf { !it.startsWith("/") && !it.contains("..") } ?: "index.html"
    var title by remember { mutableStateOf("") }
    DialogWindow(
        onCloseRequest = onClose, state = state, title = title.ifEmpty { "AbsentMotion" },
        onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.key == Key.Escape && run { onClose(); true } },
    ) {
        remember(window) { window.minimumSize = java.awt.Dimension(420, 320) }
        DisposableEffect(window) {
            val listener = object : ComponentAdapter() {
                private fun save() {
                    if (!window.isShowing) return
                    prefs.putInt("helpWidth", window.width); prefs.putInt("helpHeight", window.height)
                    prefs.putInt("helpX", window.x); prefs.putInt("helpY", window.y)
                }
                override fun componentMoved(e: ComponentEvent) = save()
                override fun componentResized(e: ComponentEvent) = save()
            }
            window.addComponentListener(listener)
            window.toFront()
            onDispose { window.removeComponentListener(listener) }
        }
        DeskHelpReader(clean, { title = it }, onClose)
    }
}
