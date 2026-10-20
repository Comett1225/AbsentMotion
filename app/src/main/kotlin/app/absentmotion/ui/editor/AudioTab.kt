package app.absentmotion.ui.editor

import app.absentmotion.editor.keepCopy
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

private val TilePalettes = listOf(
    0xFF85A776 to 0xFF8C7968, 0xFFE9B96A to 0xFF895060, 0xFFD26E72 to 0xFF772F54, 0xFFB1BD93 to 0xFF4F8F7A,
)

@Composable
internal fun AudioTab(state: EditorState, modifier: Modifier, library: AddLibrary) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val query = debounced(search)
    val files = library.audio
    val shown = remember(files, query) { files.filter { matchesSearch(query, it.title, it.name, it.album, it.artist) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { library.audioRevision++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { adding = true; try { audioDeclaration(context, uri)?.let { state.keepCopy(context, it) }?.let { state.addAudio(it) } } finally { adding = false } }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("media", Context.MODE_PRIVATE)
        if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("askedAudio", false)) {
            prefs.edit().putBoolean("askedAudio", true).apply()
            permission.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }
    val preview = remember { SoundPreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }

    Column(modifier) {
        SearchField(search, { search = it }, Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp).fillMaxWidth().height(32.dp), hint = "Search music and sounds")
        LazyHorizontalGrid(GridCells.Fixed(2), Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            item(key = "viewAll") {
                Box(Modifier.width(80.dp).fillMaxHeight(), Alignment.Center) {
                    Column(
                        Modifier.size(75.dp).clip(RoundedCornerShape(2.dp)).background(Am.Well).clickable { picker.launch(arrayOf("audio/*")) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        MediaGridIcon(Modifier.size(20.dp))
                        Text("View All", Modifier.padding(top = 4.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
            item(key = "speech") {
                Box(Modifier.width(80.dp).fillMaxHeight(), Alignment.Center) {
                    Column(
                        Modifier.size(75.dp).clip(RoundedCornerShape(2.dp)).background(Am.Well).clickable { speaking = true },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(ChromeIcons.Captions, null, Modifier.size(20.dp), tint = Am.OnDark)
                        Text("Text to Speech", Modifier.padding(top = 4.dp), color = Am.OnDark, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            if (shown.isEmpty() && query.isNotBlank()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                NoMatches(query, "sounds", Modifier.width(200.dp).fillMaxHeight())
            }
            items(shown, key = { it.uri.toString() }) { file ->
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
                                .clickable(enabled = !adding) {
                                    scope.launch { adding = true; try { audioDeclaration(context, file.uri)?.let { state.keepCopy(context, it) }?.let { state.addAudio(it) } } finally { adding = false } }
                                },
                            Alignment.Center,
                        ) { Icon(ChromeIcons.Plus, "Add", Modifier.size(16.dp), tint = Color.White) }
                    }
                }
            }
        }
    }
    if (speaking) VoiceSheet(state) { speaking = false }
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
    var uri by mutableStateOf<Uri?>(null)
        private set

    fun toggle(next: Uri) {
        val same = uri == next
        stop()
        if (same) return
        try {
            val nextPlayer = MediaPlayer()
            player = nextPlayer
            nextPlayer.apply {
                setDataSource(context, next)
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
