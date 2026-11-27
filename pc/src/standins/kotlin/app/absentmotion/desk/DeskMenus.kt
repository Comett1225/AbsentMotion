package app.absentmotion.desk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope

private val DeskMenuItem.shortcut: KeyShortcut? get() = keys?.let { KeyShortcut(it.key, meta = it.command && DeskOs.mac, ctrl = it.command && !DeskOs.mac, alt = it.alt, shift = it.shift) }

@Composable
fun FrameWindowScope.DeskMenus(app: DeskAppState) {
    val menus = deskMenus(app, rememberCoroutineScope())
    MenuBar { menus.forEach { menu -> Menu(menu.label) { entries(menu.children.orEmpty()) } } }
}

@Composable
private fun MenuScope.entries(items: List<DeskMenuItem>) {
    items.forEach { item ->
        when {
            item === DeskMenuItem.Separator -> Separator()
            item.children != null -> Menu(item.label) { entries(item.children) }
            else -> Item(item.label, enabled = item.enabled, shortcut = item.shortcut, onClick = item.run)
        }
    }
}
