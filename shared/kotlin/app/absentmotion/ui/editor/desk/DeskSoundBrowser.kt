package app.absentmotion.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.audio.DeviceAudio
import app.absentmotion.audio.SoundGroup
import app.absentmotion.audio.SoundShelf
import app.absentmotion.audio.SoundSort
import app.absentmotion.audio.SoundView
import app.absentmotion.audio.shelves
import app.absentmotion.audio.sortedSounds
import app.absentmotion.editor.EditorState
import app.absentmotion.platform.Hint
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.home.deskPress
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DeskSoundBrowser(state: EditorState, all: List<DeviceAudio>, preview: SoundPreview, onAdd: (DeviceAudio) -> Unit, onReveal: (DeviceAudio) -> Unit, onClose: () -> Unit) {
    var view by remember { mutableStateOf(state.settings.soundView) }
    var search by remember { mutableStateOf("") }
    var shelfKey by remember { mutableStateOf<String?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    val query = debounced(search)
    val found by produceState<Pair<List<DeviceAudio>, List<SoundShelf>>?>(null, all, view, query) {
        value = withContext(Dispatchers.Default) {
            val matching = all.filter { matchesSearch(query, it.title, it.artist, it.name, it.album) }
            if (view.group == SoundGroup.Songs) matching.sortedSounds(view) to emptyList() else emptyList<DeviceAudio>() to matching.shelves(view)
        }
    }
    val shelves = found?.second.orEmpty()
    val shelf = shelves.firstOrNull { it.key == shelfKey } ?: shelves.firstOrNull()
    fun change(next: SoundView) { view = next; state.settings.soundView = next }
    fun close() { preview.stop(); onClose() }
    androidx.compose.ui.window.Dialog(::close, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
    DeskDialog(stringResource(R.string.sounds_title), 960.dp, ::close, height = 640.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(search, { search = it }, Modifier.weight(1f).height(36.dp), hint = stringResource(R.string.audiotab_search_hint))
                Box {
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).deskPress(6.dp).clickable { sortOpen = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(SortLabels.getValue(view.sort)) + " · " + stringResource(orderLabel(view.sort, view.descending)), color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
                        Icon(ExportIcons.ChevronDown, stringResource(R.string.sounds_sort), Modifier.padding(start = 4.dp).size(16.dp), tint = Am.OnDark)
                    }
                    Presence(sortOpen) {
                        MenuPopup(DropOrRise, { sortOpen = false }) {
                            SoundSort.entries.forEach { sort ->
                                MenuRow(stringResource(SortLabels.getValue(sort)), trailing = if (sort == view.sort) PanelIcons.Check else null) {
                                    change(view.copy(sort = sort, descending = if (sort == view.sort) view.descending else sort != SoundSort.Name))
                                    sortOpen = false
                                }
                            }
                            MenuRow(stringResource(orderLabel(view.sort, !view.descending)), divider = true) {
                                change(view.copy(descending = !view.descending))
                                sortOpen = false
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp)) {
                SoundGroup.entries.forEach { group ->
                    val active = group == view.group
                    Box(Modifier.width(120.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).deskPress(6.dp).clickable { shelfKey = null; change(view.copy(group = group)) }, Alignment.Center) {
                        Text(stringResource(GroupLabels.getValue(group)), color = if (active) Am.Accent else Am.OnDarkDim, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(2.dp).background(if (active) Am.Accent else Color.Transparent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Am.Line))
            val result = found
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (view.group != SoundGroup.Songs) {
                    val shelfList = rememberLazyListState()
                    LazyColumn(Modifier.width(300.dp).fillMaxHeight().deskScrollbar(shelfList), shelfList) {
                        items(shelves, key = { "shelf:" + it.key }) { item -> DeskShelfRow(item, view.group, item.key == shelf?.key) { shelfKey = item.key } }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Am.Line))
                }
                val songs = if (view.group == SoundGroup.Songs) result?.first.orEmpty() else shelf?.sounds.orEmpty()
                val songList = rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(Modifier.fillMaxSize().deskScrollbar(songList), songList) {
                        items(songs, key = { it.uri }) { DeskSoundRow(it, preview, state.addingMedia, onAdd, onReveal) }
                    }
                    if (result != null && songs.isEmpty()) {
                        if (query.isNotBlank()) NoMatches(query, R.string.search_no_sounds, Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.sounds_empty), Modifier.padding(horizontal = 24.dp), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DeskShelfRow(shelf: SoundShelf, group: SoundGroup, selected: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(if (selected) Am.Field else Color.Transparent).deskPress().clickable(onClick = onOpen).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shelfLabel(shelf, group), color = if (selected) Am.Accent else Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.sounds_count, shelf.sounds.size, shelf.sounds.size), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
        }
        Icon(ExportIcons.ChevronRight, null, Modifier.size(16.dp), tint = Am.OnDarkDim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeskSoundRow(file: DeviceAudio, preview: SoundPreview, busy: Boolean, onAdd: (DeviceAudio) -> Unit, onReveal: (DeviceAudio) -> Unit) {
    val playing = preview.uri == file.uri
    val label = stringResource(if (playing) R.string.play_pause else R.string.play_play)
    DeskContext({ onAdd(file) }, { onReveal(file) }) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).deskPress().combinedClickable(onDoubleClick = { if (!busy) onAdd(file) }, onClick = { preview.toggle(file.uri) }).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Hint(label) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(if (playing) Am.Accent else Am.Well), Alignment.Center) {
                    Icon(if (playing) ChromeIcons.Pause else ChromeIcons.Play, label, Modifier.size(16.dp), tint = if (playing) Am.OnAccent else Am.OnDark)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(file.title.ifBlank { file.name }, color = if (playing) Am.Accent else Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(file.artist, file.album).filter { it.isNotBlank() }.joinToString(" · "), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(duration(file.durationMs), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
            Hint(stringResource(R.string.audiotab_add)) {
                Box(Modifier.padding(start = 6.dp).size(32.dp).clip(RoundedCornerShape(6.dp)).deskPress(6.dp).clickable { if (!busy) onAdd(file) }, Alignment.Center) {
                    Icon(ChromeIcons.Plus, stringResource(R.string.audiotab_add), Modifier.size(18.dp), tint = Am.OnDark)
                }
            }
        }
    }
}
