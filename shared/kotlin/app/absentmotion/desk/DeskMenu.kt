package app.absentmotion.desk

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.StylePart
import app.absentmotion.editor.canPaste
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.pasteStyleOnChosen
import app.absentmotion.editor.split
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.Hint
import app.absentmotion.platform.linkMediaFolder
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PasteLabels
import app.absentmotion.ui.editor.ShortcutItems
import app.absentmotion.ui.editor.TextIcons
import app.absentmotion.ui.editor.rightClick
import app.absentmotion.ui.editor.styleToast
import app.absentmotion.ui.home.BelowAnchor
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeskMenuItem(val label: String, val enabled: Boolean = true, val keys: MenuKey? = null, val children: List<DeskMenuItem>? = null, val action: Shortcut? = null, val run: () -> Unit = {}) {
    companion object { val Separator = DeskMenuItem("") }
}

interface DeskMenuHost {
    val context: Context
    val editor: EditorState?
    val canStartProject: Boolean
    val canImportLink: Boolean
    val linkedFolders: List<Pair<String, () -> Unit>>
    val view: List<DeskMenuItem> get() = emptyList()
    val quit: (() -> Unit)? get() = null
    fun newProject()
    fun openSettings()
    fun importMedia()
    fun importProjects()
    fun importLink()
    fun closeProject()
    fun openHelp()
    fun openAbout()
    fun openCrashLogs()
}

val LocalDeskMenu = staticCompositionLocalOf<@Composable () -> Unit> { {} }

internal fun menuKey(action: Shortcut): MenuKey? = DeskShortcuts.keys(action).firstOrNull()

fun deskMenus(host: DeskMenuHost): List<DeskMenuItem> {
    fun t(id: Int) = AppText.get(id)
    val editor = host.editor
    val chosen = editor?.chosenLayers?.isNotEmpty() == true
    val picked = editor?.selectedId != null || MultiSelect.active
    val style = chosen && editor.canPaste
    val linked = host.linkedFolders
    val view = host.view
    val quit = host.quit
    val separator = DeskMenuItem.Separator
    return listOfNotNull(
        DeskMenuItem(t(R.string.desk_menu_file), children = listOfNotNull(
            DeskMenuItem(t(R.string.desk_menu_new_project), host.canStartProject, menuKey(Shortcut.NewProject), action = Shortcut.NewProject) { host.newProject() },
            DeskMenuItem(t(R.string.settings) + "…", keys = menuKey(Shortcut.Settings), action = Shortcut.Settings) { host.openSettings() },
            separator,
            DeskMenuItem(t(R.string.desk_menu_import_media), editor != null) { host.importMedia() },
            DeskMenuItem(t(R.string.desk_menu_import_project), editor == null) { host.importProjects() },
            DeskMenuItem(t(R.string.project_link_import), host.canImportLink) { host.importLink() },
            DeskMenuItem(t(R.string.desk_menu_link_folder)) { linkMediaFolder() },
            if (linked.isEmpty()) null else DeskMenuItem(t(R.string.desk_menu_unlink_folder), children = linked.map { (name, unlink) -> DeskMenuItem(name, run = unlink) }),
            separator,
            DeskMenuItem(t(R.string.export) + "…", editor != null, menuKey(Shortcut.Export), action = Shortcut.Export) { editor?.let { it.playing = false; it.exportOpen = true } },
            DeskMenuItem(t(R.string.desk_menu_close_project), editor != null, menuKey(Shortcut.CloseProject), action = Shortcut.CloseProject) { host.closeProject() },
        ) + if (quit == null) emptyList() else listOf(separator, DeskMenuItem(t(R.string.desk_export_quit), true, menuKey(Shortcut.Quit), action = Shortcut.Quit, run = quit))),
        DeskMenuItem(t(R.string.history_edit), children = listOf(
            DeskMenuItem(t(R.string.draw_undo), editor?.canUndo == true, action = Shortcut.Undo) { editor?.undo() },
            DeskMenuItem(t(R.string.draw_redo), editor?.canRedo == true, action = Shortcut.Redo) { editor?.redo() },
            separator,
            DeskMenuItem(t(R.string.desk_menu_cut_layer), chosen, action = Shortcut.Cut) { editor?.let { it.copySelected(); if (MultiSelect.active) it.deleteChosen() else it.deleteSelected() } },
            DeskMenuItem(t(R.string.desk_menu_copy_layer), chosen, action = Shortcut.Copy) { editor?.copySelected() },
            DeskMenuItem(t(R.string.desk_menu_paste_layer), editor != null, action = Shortcut.Paste) { editor?.pasteLayer() },
            DeskMenuItem(t(R.string.menu_paste_style), style, children = (StylePart.entries.map { t(PasteLabels.getValue(it)) to setOf(it) } +
                (t(R.string.structure_paste_style) to StylePart.entries.toSet())).map { (label, parts) ->
                DeskMenuItem(label, style) { editor?.let { styleToast(host.context, it.pasteStyleOnChosen(parts), onto = MultiSelect.active) } }
            }),
            DeskMenuItem(t(R.string.home_duplicate), picked, action = Shortcut.Duplicate) { editor?.let { if (MultiSelect.active) it.duplicateChosen() else it.duplicateSelected() } },
            DeskMenuItem(t(R.string.timeline_split), picked, action = Shortcut.Split) { editor?.split() },
            DeskMenuItem(t(R.string.delete), picked, action = Shortcut.Delete) { editor?.let { if (MultiSelect.active) it.deleteChosen() else it.deleteSelected() } },
        )),
        if (view.isEmpty()) null else DeskMenuItem(t(R.string.desk_menu_view), children = view),
        DeskMenuItem(t(R.string.desk_menu_playback), children = listOf(
            DeskMenuItem(if (editor?.playing == true) t(R.string.export_pause) else t(R.string.export_play), editor != null, action = Shortcut.PlayPause) { editor?.togglePlayback() },
            DeskMenuItem(t(R.string.desk_menu_go_start), editor != null, action = Shortcut.GoStart) { editor?.let { it.playing = false; it.frame = 0 } },
            DeskMenuItem(t(R.string.desk_menu_go_end), editor != null, action = Shortcut.GoEnd) { editor?.let { it.playing = false; it.parkAtEnd() } },
            separator,
            DeskMenuItem(t(R.string.desk_menu_previous_bookmark), editor != null, menuKey(Shortcut.PreviousBookmark), action = Shortcut.PreviousBookmark) { editor?.let { DeskJump.previous(it) } },
            DeskMenuItem(t(R.string.desk_menu_next_bookmark), editor != null, menuKey(Shortcut.NextBookmark), action = Shortcut.NextBookmark) { editor?.let { DeskJump.next(it) } },
        )),
        DeskMenuItem(t(R.string.settings_help), children = listOf(
            DeskMenuItem(t(R.string.desk_menu_help)) { host.openHelp() },
            DeskMenuItem(t(R.string.desk_menu_about)) { host.openAbout() },
            DeskMenuItem(t(R.string.desk_menu_crash_logs)) { host.openCrashLogs() },
        )),
    )
}

@Composable
fun DeskBurger(light: Boolean, menus: (CoroutineScope) -> List<DeskMenuItem>) {
    var open by remember { mutableStateOf(false) }
    var closedAt by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.desk_menu)
    val button: @Composable () -> Unit = {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(if (open) (if (light) Am.PressHome else Am.Field) else Color.Transparent)
                .hoverFill(6.dp, if (light) Am.PressHome else Am.Field).handCursor().clickable { if (System.nanoTime() - closedAt > 300_000_000L) open = !open },
            Alignment.Center,
        ) { Icon(TextIcons.Menu, label, Modifier.size(20.dp), tint = if (light) Am.Navy else Am.OnDark) }
    }
    Box {
        if (open) button() else Hint(label, button)
        if (open) DeskMenuPopup(menus(scope), light, { action -> scope.launch { delay(50); action() } }) { open = false; closedAt = System.nanoTime() }
    }
}

private const val RowHeight = 32
private const val SeparatorHeight = 9
private val ColumnWidths = intArrayOf(168, 280, 240)

private fun offsetOf(items: List<DeskMenuItem>, index: Int): Int = 4 + items.take(index).sumOf { if (it === DeskMenuItem.Separator) SeparatorHeight else RowHeight }

private fun step(items: List<DeskMenuItem>, from: Int, by: Int): Int {
    if (items.none { it !== DeskMenuItem.Separator }) return from
    var at = if (from < 0) (if (by > 0) -1 else items.size) else from
    do { at = (at + by).mod(items.size) } while (items[at] === DeskMenuItem.Separator)
    return at
}

@Composable
private fun DeskMenuPopup(menus: List<DeskMenuItem>, light: Boolean, run: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(listOf(-1)) }
    var shortcutAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val focus = remember { FocusRequester() }
    BackHandler(true, onDismiss)
    fun levels(): List<List<DeskMenuItem>> {
        val out = mutableListOf(menus)
        for (i in 0 until path.size - 1) out += out.last().getOrNull(path[i])?.children ?: break
        return out
    }
    fun activate(level: Int, index: Int) {
        val item = levels()[level][index]
        when {
            item === DeskMenuItem.Separator || !item.enabled -> Unit
            item.children != null -> path = path.take(level) + index + -1
            else -> { onDismiss(); run(item.run) }
        }
    }
    fun hoverAt(level: Int, index: Int) {
        val item = levels().getOrNull(level)?.getOrNull(index) ?: return
        path = if (item.children != null && item.enabled) path.take(level) + index + -1 else path.take(level) + index
    }
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val shown = levels()
        val level = path.lastIndex
        val items = shown.getOrNull(level) ?: return false
        val at = path[level]
        when (event.key) {
            Key.Escape -> onDismiss()
            Key.DirectionDown -> path = path.dropLast(1) + step(items, at, 1)
            Key.DirectionUp -> path = path.dropLast(1) + step(items, at, -1)
            Key.DirectionLeft -> if (path.size > 1) path = path.dropLast(1)
            Key.DirectionRight -> if (at >= 0 && items[at].children != null) activate(level, at)
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> if (at >= 0) activate(level, at)
            else -> return false
        }
        return true
    }
    val keys = rememberUpdatedState<(KeyEvent) -> Boolean> { key(it) }
    val entered = rememberUpdatedState<(Int, Int) -> Unit> { level, index -> hoverAt(level, index) }
    DisposableEffect(Unit) {
        val before = DeskShortcuts.viewer
        DeskShortcuts.viewer = { keys.value(it) }
        onDispose { DeskShortcuts.viewer = before }
    }
    val surface = if (light) Am.Card else Am.Field
    val edge = if (light) Am.HomeLine else Am.Line
    val ink = if (light) Am.HomeInk else Am.OnDark
    val dim = if (light) Am.HomeDim else Am.OnDarkDim
    val hover = if (light) Am.HomePanel else Am.FieldHi
    Popup(BelowAnchor(false), onDismiss, PopupProperties(focusable = true)) {
        val shown = levels()
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(Modifier.onPreviewKeyEvent { keys.value(it) }.focusRequester(focus).focusable()) {
            var top = 0
            shown.forEachIndexed { level, items ->
                if (level > 0) top += offsetOf(shown[level - 1], path[level - 1]) - 4
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.padding(top = top.dp, start = if (level > 0) 2.dp else 0.dp).width(ColumnWidths[level.coerceAtMost(ColumnWidths.lastIndex)].dp)
                        .shadow(12.dp, shape).clip(shape).background(surface).border(1.dp, edge, shape).padding(vertical = 4.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        if (item === DeskMenuItem.Separator) Box(Modifier.fillMaxWidth().height(SeparatorHeight.dp).padding(vertical = 4.dp, horizontal = 8.dp).background(edge))
                        else Box { Row(
                            Modifier.fillMaxWidth().height(RowHeight.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
                                .then(item.action?.let { Modifier.rightClick { shortcutAt = level to index } } ?: Modifier)
                                .background(if (path.getOrNull(level) == index) hover else Color.Transparent)
                                .pointerInput(level, index) { awaitPointerEventScope { while (true) if (awaitPointerEvent().type == PointerEventType.Enter) entered.value(level, index) } }
                                .then(if (item.enabled) Modifier.handCursor().clickable { activate(level, index) } else Modifier)
                                .padding(horizontal = 10.dp),
                            Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                        ) {
                            Text(item.label, Modifier.weight(1f), color = if (item.enabled) ink else dim.copy(alpha = .6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (level == 0) FontWeight.Medium else FontWeight.Normal)
                            item.keys?.let { Text(shortcutText(it, ShortcutPlatform.mac), color = dim.copy(alpha = if (item.enabled) 1f else .6f), fontSize = 12.sp, maxLines = 1) }
                            if (item.children != null) Icon(ChromeIcons.ChevronRight, null, Modifier.size(16.dp), tint = if (item.enabled) dim else dim.copy(alpha = .6f))
                        }
                            val action = item.action
                            if (action != null && shortcutAt == level to index) DropdownMenu(expanded = true, onDismissRequest = { shortcutAt = null }, containerColor = Am.Field) {
                                ShortcutItems(action) { shortcutAt = null }
                            }
                        }
                    }
                }
            }
        }
    }
}
