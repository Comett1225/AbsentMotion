package app.absentmotion.desk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope

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
