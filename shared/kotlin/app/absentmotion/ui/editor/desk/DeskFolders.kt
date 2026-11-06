package app.absentmotion.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.platform.BackHandler
import app.absentmotion.ui.theme.Am
import java.io.File

internal class FolderGroup<T>(val folder: File, val items: List<T>)

internal fun <T> groupByFolder(items: List<T>, query: String, roots: List<File>, file: (T) -> File, texts: (T) -> Array<String>): List<FolderGroup<T>> {
    val parents = items.groupBy { file(it).parentFile ?: file(it) }
    val named = (parents.keys + roots).filter { matchesSearch(query, it.name.ifEmpty { it.path }) }.toSet()
    val matched = items.filter { matchesSearch(query, *texts(it)) }
    val groups = matched.groupBy { file(it).parentFile ?: file(it) }
    return (groups.keys + (named - groups.keys)).distinct()
        .sortedWith(compareBy({ it.name.lowercase() }, { it.path }))
        .map { FolderGroup(it, groups[it].orEmpty()) }
}

internal fun shortFolderPath(folder: File): String = folder.parentFile?.path?.let(::homeShortPath) ?: ""

internal fun homeShortPath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    return if (home.isNotEmpty() && (path == home || path.startsWith(home + File.separator))) "~" + path.removePrefix(home) else path
}

@Composable
internal fun <T> ColumnScope.DeskFolderContent(
    items: List<T>, query: String, clearQuery: () -> Unit, library: AddLibrary, file: (T) -> File, texts: (T) -> Array<String>, key: (T) -> Any,
    tile: @Composable (T) -> Unit,
) {
    var opened by remember { mutableStateOf<String?>(null) }
    BackHandler(opened != null) { opened = null }
    val paths = remember(library.mediaRevision, library.audioRevision) { app.absentmotion.platform.libraryFolders() }
    val pinned = remember(library.mediaRevision, library.audioRevision) { app.absentmotion.platform.pinnedLibraryFolders() }
    val roots = remember(paths) { paths.map(::File) }
    val searching = query.isNotBlank()
    val current = opened?.takeIf { !searching }
    if (current != null) Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DeskIcon(ChromeIcons.Back, stringResource(R.string.desk_folder_back)) { opened = null }
        Image(rememberDeskFolderIcon(current in pinned), null, Modifier.size(24.dp))
        Text(File(current).name.ifEmpty { current }, Modifier.padding(start = 8.dp), color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DeskGrid(Modifier.fillMaxWidth().weight(1f), top = query to current) {
        when {
            searching -> {
                val groups = groupByFolder(items, query, roots, file, texts)
                for (group in groups) {
                    item(key = "dir:${group.folder.path}", span = { GridItemSpan(maxLineSpan) }) {
                        DeskFolderRow(group.folder, group.folder.path in pinned) { opened = group.folder.path; clearQuery() }
                    }
                    items(group.items, key = key) { tile(it) }
                }
            }
            current != null -> {
                val base = File(current)
                items(items.filter { file(it).startsWith(base) }, key = key) { tile(it) }
            }
            else -> {
                items(paths, key = { "dir:$it" }) { path ->
                    DeskFolderTile(path, path in pinned, open = { opened = path }) {
                        app.absentmotion.platform.unlinkLibraryFolder(path)
                    }
                }
                items(items, key = key) { tile(it) }
            }
        }
    }
}

@Composable
private fun DeskFolderRow(folder: File, pinned: Boolean, open: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(if (hovered) Am.Button else Am.Field).hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = open).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(rememberDeskFolderIcon(pinned), null, Modifier.size(28.dp))
        Text(folder.name.ifEmpty { folder.path }, Modifier.weight(1f, fill = false), color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(shortFolderPath(folder), Modifier.weight(1f), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeskFolderTile(path: String, pinned: Boolean, open: () -> Unit, unlink: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val name = remember(path) { File(path).name.ifEmpty { path } }
    DeskTip(homeShortPath(path)) {
        DeskContext({}, menu = { close ->
            MenuRow(stringResource(if (pinned) R.string.desk_unpin_folder else R.string.desk_pin_folder)) { close(); app.absentmotion.platform.pinLibraryFolder(path, !pinned) }
            MenuRow(stringResource(R.string.desk_unlink_folder_plain)) { close(); unlink() }
            MenuRow(app.absentmotion.platform.revealFolderLabel(), divider = true) { close(); app.absentmotion.platform.revealLibraryFolder(path) }
        }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Am.Button else Am.Field).hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = open).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center) { Image(rememberDeskFolderIcon(pinned), null, Modifier.fillMaxSize().padding(4.dp)) }
                Column(Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp), Arrangement.Center) {
                    Text(name, Modifier.fillMaxWidth(), color = Am.OnDark, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
