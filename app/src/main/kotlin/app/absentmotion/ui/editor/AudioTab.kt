package app.absentmotion.ui.editor

import app.absentmotion.editor.keepCopy
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.absentmotion.audio.DeviceAudio
import app.absentmotion.audio.SoundGroup
import app.absentmotion.audio.SoundShelf
import app.absentmotion.audio.SoundSort
import app.absentmotion.audio.SoundView
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.audio.shelves
import app.absentmotion.audio.sortedSounds
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.platform.BackHandler
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val TilePalettes = listOf(
    0xFF85A776 to 0xFF8C7968, 0xFFE9B96A to 0xFF895060, 0xFFD26E72 to 0xFF772F54, 0xFFB1BD93 to 0xFF4F8F7A,
)

private val AudioPermission get() = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
internal fun AudioTab(state: EditorState, modifier: Modifier, library: AddLibrary) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = LocalEditorScope.current
    var speaking by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var view by remember { mutableStateOf(state.settings.soundView) }
    val query = debounced(search)
    val sorted by produceState(emptyList<DeviceAudio>(), library.audio, view.sort, view.descending) {
        value = withContext(Dispatchers.Default) { library.audio.sortedSounds(view) }
    }
    val shown = remember(sorted, query) { sorted.filter { matchesSearch(query, it.title, it.artist, it.name, it.album) } }
    val prefs = remember { context.getSharedPreferences("media", Context.MODE_PRIVATE) }
    fun granted() = context.checkSelfPermission(AudioPermission) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = granted() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        if (!ok) prefs.edit().putBoolean("audioRefused", true).apply()
        library.audioRevision++
    }
    fun blocked() = !allowed && prefs.getBoolean("audioRefused", false) && activity?.shouldShowRequestPermissionRationale(AudioPermission) != true
    fun ask() {
        if (blocked()) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else permission.launch(AudioPermission)
    }
    fun add(uris: List<Uri>) {
        if (uris.isEmpty() || state.addingMedia) return
        scope.launch {
            state.addingMedia = true
            try {
                for (uri in uris) {
                    val media = audioDeclaration(context, uri)?.let { state.keepCopy(context, it) }
                    if (media == null || media.duration <= 0) android.widget.Toast.makeText(context, context.getString(R.string.library_err_unsupported_type), android.widget.Toast.LENGTH_LONG).show()
                    else state.addAudio(media)
                }
            } finally { state.addingMedia = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        add(uris)
    }
    fun fromFiles() = picker.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!allowed && !prefs.getBoolean("askedAudio", false)) {
            prefs.edit().putBoolean("askedAudio", true).apply()
            permission.launch(AudioPermission)
        }
    }
    val preview = remember { SoundPreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview.stop() }

    Column(modifier) {
        SearchField(search, { search = it }, Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp).fillMaxWidth().height(32.dp), hint = stringResource(R.string.audiotab_search_hint))
        LazyHorizontalGrid(AtLeastTwo(84.dp), Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            item(key = "viewAll") { ActionTile(stringResource(R.string.view_all), { MediaGridIcon(Modifier.size(20.dp)) }) { browsing = true } }
            item(key = "files") { ActionTile(stringResource(R.string.sounds_from_files), { Icon(TextIcons.Import, null, Modifier.size(20.dp), tint = Am.OnDark) }, ::fromFiles) }
            item(key = "speech") { ActionTile(stringResource(R.string.text_to_speech), { Icon(ChromeIcons.Captions, null, Modifier.size(20.dp), tint = Am.OnDark) }) { speaking = true } }
            if (!allowed) item(key = "access", span = { GridItemSpan(maxLineSpan) }) {
                AccessNotice(Modifier.width(168.dp).fillMaxHeight().padding(6.dp), blocked(), ::ask)
            }
            if (shown.isEmpty() && query.isNotBlank()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                NoMatches(query, R.string.search_no_sounds, Modifier.width(200.dp).fillMaxHeight())
            }
            items(shown, key = { it.uri }) { file ->
                val (top, bottom) = TilePalettes[file.title.hashCode().mod(TilePalettes.size)]
                val playing = preview.uri == file.uri
                Box(Modifier.width(80.dp).fillMaxHeight(), Alignment.Center) {
                    Box(
                        Modifier.size(75.dp).clip(RoundedCornerShape(2.dp))
                            .background(Brush.verticalGradient(listOf(Color(top), Color(bottom))))
                            .then(if (playing) Modifier.border(2.dp, Am.Accent, RoundedCornerShape(2.dp)) else Modifier)
                            .clickable { preview.toggle(file.uri) },
                    ) {
                        WaveformArt(file.title.hashCode(), Modifier.align(Alignment.Center).fillMaxWidth().height(30.dp))
                        Text(file.title, Modifier.padding(start = 3.dp, top = 0.5.dp).fillMaxWidth(), color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(duration(file.durationMs), Modifier.padding(start = 3.dp, top = 15.dp).fillMaxWidth(), color = Color.White, fontSize = 9.sp, maxLines = 1)
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp).background(Color(0x32000000))
                                .clickable(enabled = !state.addingMedia) { add(listOf(Uri.parse(file.uri))) },
                            Alignment.Center,
                        ) { Icon(ChromeIcons.Plus, stringResource(R.string.audiotab_add), Modifier.size(16.dp), tint = Color.White) }
                    }
                }
            }
        }
    }
    if (speaking) VoiceSheet(state) { speaking = false }
    if (browsing) SoundLibrary(
        library.audio, view, allowed, blocked(), preview, state.addingMedia,
        onView = { view = it; state.settings.soundView = it }, onAsk = ::ask, onFiles = ::fromFiles,
        onAdd = { add(listOf(Uri.parse(it.uri))) },
    ) { preview.stop(); browsing = false }
}

@Composable
private fun ActionTile(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(Modifier.width(80.dp).fillMaxHeight(), Alignment.Center) {
        val press = remember { MutableInteractionSource() }
        Column(
            Modifier.size(75.dp).pressScale(press, TilePress).clip(RoundedCornerShape(2.dp)).background(Am.Well).clickable(press, LocalIndication.current, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            icon()
            Text(label, Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp), color = Am.OnDark, fontSize = 11.sp, lineHeight = 12.sp, maxLines = 2,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AccessNotice(modifier: Modifier, blocked: Boolean, onAsk: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(4.dp)).background(Am.Well).padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.sounds_permission_body), color = Am.OnDark, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(16.dp)).background(Am.Accent).clickable(onClick = onAsk), Alignment.Center) {
            Text(stringResource(if (blocked) R.string.camera_open_settings else R.string.sounds_permission_allow), color = Am.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun SoundLibrary(
    all: List<DeviceAudio>, view: SoundView, allowed: Boolean, blocked: Boolean, preview: SoundPreview, busy: Boolean,
    onView: (SoundView) -> Unit, onAsk: () -> Unit, onFiles: () -> Unit, onAdd: (DeviceAudio) -> Unit, onClose: () -> Unit,
) {
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
    val shelf = found?.second?.firstOrNull { it.key == shelfKey }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (shelfKey != null) shelfKey = null else onClose() }
        Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable { if (shelfKey != null) shelfKey = null else onClose() }, Alignment.Center) {
                    Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.OnDark)
                }
                Text(shelf?.let { shelfLabel(it, view.group) } ?: stringResource(R.string.sounds_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 18.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Am.Field).clickable { sortOpen = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(SortLabels.getValue(view.sort)) + " · " + stringResource(orderLabel(view.sort, view.descending)),
                            color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
                        Icon(ExportIcons.ChevronDown, stringResource(R.string.sounds_sort), Modifier.padding(start = 4.dp).size(16.dp), tint = Am.OnDark)
                    }
                    Presence(sortOpen) {
                        MenuPopup(DropOrRise, { sortOpen = false }) {
                            SoundSort.entries.forEach { sort ->
                                MenuRow(stringResource(SortLabels.getValue(sort)), trailing = if (sort == view.sort) PanelIcons.Check else null) {
                                    onView(view.copy(sort = sort, descending = if (sort == view.sort) view.descending else sort != SoundSort.Name))
                                    sortOpen = false
                                }
                            }
                            MenuRow(stringResource(orderLabel(view.sort, !view.descending)), divider = true) {
                                onView(view.copy(descending = !view.descending))
                                sortOpen = false
                            }
                        }
                    }
                }
                Box(Modifier.width(8.dp))
            }
            SearchField(search, { search = it }, Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(38.dp), hint = stringResource(R.string.audiotab_search_hint))
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp)) {
                SoundGroup.entries.forEach { group ->
                    val active = group == view.group
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { shelfKey = null; onView(view.copy(group = group)) }, Alignment.Center) {
                        Text(stringResource(GroupLabels.getValue(group)), color = if (active) Am.Accent else Am.OnDarkDim, fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(2.dp).background(if (active) Am.Accent else Color.Transparent))
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (shelfKey == null) {
                    item(key = "files") { FilesRow(onFiles) }
                    if (!allowed) item(key = "access") { AccessNotice(Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 10.dp, vertical = 6.dp), blocked, onAsk) }
                }
                val result = found
                when {
                    result == null -> Unit
                    shelf != null -> items(shelf.sounds, key = { it.uri }) { SoundRow(it, preview, busy, onAdd) }
                    view.group == SoundGroup.Songs -> items(result.first, key = { it.uri }) { SoundRow(it, preview, busy, onAdd) }
                    else -> items(result.second, key = { "shelf:" + it.key }) { ShelfRow(it, view.group) { shelfKey = it.key } }
                }
                if (result != null && result.first.isEmpty() && result.second.isEmpty()) item(key = "none") {
                    if (query.isNotBlank()) NoMatches(query, R.string.search_no_sounds, Modifier.fillMaxWidth().height(96.dp))
                    else if (allowed) Box(Modifier.fillMaxWidth().height(96.dp), Alignment.Center) {
                        Text(stringResource(R.string.sounds_empty), Modifier.padding(horizontal = 16.dp), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private val SortLabels = mapOf(SoundSort.Name to R.string.sounds_sort_name, SoundSort.Added to R.string.sounds_sort_added, SoundSort.Length to R.string.sounds_sort_length)

private val GroupLabels = mapOf(
    SoundGroup.Songs to R.string.sounds_tab_songs, SoundGroup.Albums to R.string.sounds_tab_albums,
    SoundGroup.Artists to R.string.sounds_tab_artists, SoundGroup.Folders to R.string.sounds_tab_folders,
)

private fun orderLabel(sort: SoundSort, descending: Boolean): Int = when (sort) {
    SoundSort.Name -> if (descending) R.string.sounds_order_za else R.string.sounds_order_az
    SoundSort.Added -> if (descending) R.string.sounds_order_newest else R.string.sounds_order_oldest
    SoundSort.Length -> if (descending) R.string.sounds_order_longest else R.string.sounds_order_shortest
}

@Composable
private fun shelfLabel(shelf: SoundShelf, group: SoundGroup): String = shelf.label.ifEmpty {
    stringResource(when (group) { SoundGroup.Artists -> R.string.sounds_unknown_artist; SoundGroup.Folders -> R.string.sounds_unknown_folder; else -> R.string.sounds_unknown_album })
}

@Composable
private fun FilesRow(onFiles: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onFiles).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(TextIcons.Import, null, Modifier.size(22.dp), tint = Am.Accent)
        Text(stringResource(R.string.sounds_choose_files), Modifier.padding(start = 14.dp).weight(1f), color = Am.Accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShelfRow(shelf: SoundShelf, group: SoundGroup, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(shelfLabel(shelf, group), color = Am.OnDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.sounds_count, shelf.sounds.size, shelf.sounds.size), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
        }
        Icon(ExportIcons.ChevronRight, null, Modifier.size(18.dp), tint = Am.OnDarkDim)
    }
}

@Composable
private fun SoundRow(file: DeviceAudio, preview: SoundPreview, busy: Boolean, onAdd: (DeviceAudio) -> Unit) {
    val playing = preview.uri == file.uri
    Row(Modifier.fillMaxWidth().height(60.dp).clickable { preview.toggle(file.uri) }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(if (playing) Am.Accent else Am.Well), Alignment.Center) {
            Icon(if (playing) ChromeIcons.Pause else ChromeIcons.Play, null, Modifier.size(18.dp), tint = if (playing) Am.OnAccent else Am.OnDark)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(file.title.ifBlank { file.name }, color = if (playing) Am.Accent else Am.OnDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(file.artist, duration(file.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(52.dp).clickable(enabled = !busy) { onAdd(file) }, Alignment.Center) {
            Icon(ChromeIcons.Plus, stringResource(R.string.audiotab_add), Modifier.size(20.dp), tint = Am.OnDark)
        }
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
}

@Composable
private fun WaveformArt(seed: Int, modifier: Modifier) {
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        var x = gap
        var i = 0
        while (x < size.width - gap) {
            val h = size.height * (0.15f + 0.85f * abs(sin((i + seed % 7) * 0.45f) * sin(i * 0.17f + 1f)))
            drawLine(Color.White.copy(alpha = 0.55f), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), 1.dp.toPx())
            x += gap; i++
        }
    }
}

private class SoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null
    var uri by mutableStateOf<String?>(null)
        private set

    fun toggle(next: String) {
        val same = uri == next
        stop()
        if (same) return
        try {
            val nextPlayer = MediaPlayer()
            player = nextPlayer
            nextPlayer.apply {
                setDataSource(context, Uri.parse(next))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepareAsync()
            }
            uri = next
        } catch (_: Exception) { stop() }
    }

    fun stop() {
        player?.release()
        player = null
        uri = null
    }
}
