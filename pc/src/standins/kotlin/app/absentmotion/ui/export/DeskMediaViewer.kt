package app.absentmotion.ui.export

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.R
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.ExportKind
import app.absentmotion.platform.Hint
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.home.deskPress
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.Locale

private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

private const val ViewerSide = 1280

@Composable
internal fun DeskMediaViewer(kind: ExportKind, uris: List<Uri>, onClose: () -> Unit) {
    DeskBack.Handler(true, onClose)
    DisposableEffect(Unit) {
        DeskModals.open++
        onDispose { DeskModals.open-- }
    }
    val video = kind == ExportKind.Video || kind == ExportKind.Gif
    var playing by remember { mutableStateOf(video) }
    var index by remember { mutableIntStateOf(0) }
    val toggle = { playing = !playing }
    val keys = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        when (event.key) {
            Key.Spacebar -> if (video) { toggle(); true } else false
            Key.DirectionLeft -> if (index > 0 && !video) { index--; true } else false
            Key.DirectionRight -> if (index < uris.size - 1 && !video) { index++; true } else false
            else -> false
        }
    }
    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = { keys.value(it) }
        DeskKeys.viewer = handler
        onDispose { if (DeskKeys.viewer === handler) DeskKeys.viewer = null }
    }
    Popup(popupPositionProvider = WholeWindow, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val file = uris.getOrNull(index)?.toFile()
            if (file != null) {
                if (video) VideoStage(file, playing, { playing = it }, toggle) else PictureStage(file)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Hint(stringResource(R.string.action_close) + " (Esc)") {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).deskPress(20.dp).clickable(onClick = onClose),
                        Alignment.Center,
                    ) { androidx.compose.material3.Icon(ExportIcons.Close, stringResource(R.string.action_close), Modifier.size(22.dp), tint = Color.White) }
                }
            }
            if (!video && uris.size > 1) Text(
                "${index + 1} / ${uris.size}", Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), color = Color.White, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun PictureStage(file: File) {
    val picture by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull() }
    }
    picture?.let { Image(it, null, Modifier.fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit) }
}

@Composable
private fun VideoStage(file: File, playing: Boolean, setPlaying: (Boolean) -> Unit, toggle: () -> Unit) {
    val facts by produceState<Map<String, String>?>(null, file) { value = withContext(Dispatchers.IO) { DeskMedia.probe(file) } }
    facts?.let { VideoStage(file, it, playing, setPlaying, toggle) }
}

@Composable
private fun VideoStage(file: File, facts: Map<String, String>, playing: Boolean, setPlaying: (Boolean) -> Unit, toggle: () -> Unit) {
    val duration = facts["duration"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1L
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var restart by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    val currentPlaying by rememberUpdatedState(playing)
    LaunchedEffect(file, playing, restart) {
        if (!playing || scrubbing) return@LaunchedEffect
        val from = if (position >= duration - 80) 0L else position
        withContext(Dispatchers.IO) {
            streamFrames(file, facts, from) { image, ms -> frame = image; position = ms; isActive }
        }
        if (isActive && currentPlaying) { position = duration; setPlaying(false) }
    }
    LaunchedEffect(file, position.takeIf { !playing || scrubbing }, restart) {
        if (playing && !scrubbing) return@LaunchedEffect
        withContext(Dispatchers.IO) { DeskMedia.frame(file, position, ViewerSide) }?.let { frame = it.toComposeImageBitmap() }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle), Alignment.Center) {
            frame?.let { Image(it, null, Modifier.fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit) }
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint(stringResource(if (playing) R.string.export_pause else R.string.export_play) + " (Space)") {
                Box(Modifier.size(40.dp).clip(CircleShape).deskPress(20.dp).clickable(onClick = toggle), Alignment.Center) {
                    androidx.compose.material3.Icon(if (playing) ExportIcons.Pause else ExportIcons.Play, stringResource(if (playing) R.string.export_pause else R.string.export_play), Modifier.size(20.dp), tint = Color.White)
                }
            }
            Text(clock(position), Modifier.padding(start = 10.dp).width(52.dp), color = Color.White, fontSize = 13.sp, maxLines = 1)
            Scrubber(position.toFloat() / duration, Modifier.weight(1f)) { fraction, done ->
                scrubbing = !done
                position = (fraction * duration).toLong().coerceIn(0, duration)
                if (done) restart++
            }
            Text(clock(duration), Modifier.padding(start = 10.dp).width(52.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Scrubber(fraction: Float, modifier: Modifier, onSeek: (Float, Boolean) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val seek by rememberUpdatedState(onSeek)
    Box(
        modifier.height(40.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { seek((it.x / width).coerceIn(0f, 1f), true) } }
            .pointerInput(Unit) {
                var at = 0f
                detectDragGestures(
                    onDragStart = { at = it.x; seek((at / width).coerceIn(0f, 1f), false) },
                    onDragEnd = { seek((at / width).coerceIn(0f, 1f), true) },
                    onDragCancel = { seek((at / width).coerceIn(0f, 1f), true) },
                ) { change, drag -> change.consume(); at += drag.x; seek((at / width).coerceIn(0f, 1f), false) }
            },
        Alignment.CenterStart,
    ) {
        val shown = fraction.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(shown).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Am.Accent))
        Box(Modifier.fillMaxWidth(shown).height(14.dp), Alignment.CenterEnd) { Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White)) }
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

private fun streamFrames(file: File, facts: Map<String, String>, fromMs: Long, show: (ImageBitmap, Long) -> Boolean) {
    val w0 = facts["width"]?.toIntOrNull() ?: return
    val h0 = facts["height"]?.toIntOrNull() ?: return
    val turned = facts["rotation"]?.toIntOrNull()?.let { it % 180 != 0 } == true
    val sw = if (turned) h0 else w0
    val sh = if (turned) w0 else h0
    val scale = minOf(1.0, ViewerSide.toDouble() / maxOf(sw, sh))
    val w = maxOf(2, (sw * scale).toInt() and 1.inv())
    val h = maxOf(2, (sh * scale).toInt() and 1.inv())
    val process = ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-ss", "%.3f".format(Locale.ROOT, fromMs / 1000.0), "-re", "-i", file.path, "-an", "-vf", "scale=$w:$h", "-f", "rawvideo", "-pix_fmt", "rgba", "-")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val input = process.inputStream
        val started = System.nanoTime()
        while (true) {
            val bytes = input.readNBytes(w * h * 4)
            if (bytes.size < w * h * 4) return
            val at = fromMs + (System.nanoTime() - started) / 1_000_000
            if (!show(org.jetbrains.skia.Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap(), at)) return
        }
    } finally {
        process.destroyForcibly()
    }
}
