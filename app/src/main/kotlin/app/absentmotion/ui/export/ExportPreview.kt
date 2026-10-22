package app.absentmotion.ui.export

import app.absentmotion.R
import app.absentmotion.i18n.Ltr
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.absentmotion.export.ExportDebug
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportResult
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private val Dim get() = Am.OnScrim.copy(alpha = 0.4f)
private val Track get() = Am.OnScrim.copy(alpha = 0.3f)
private const val MaxSide = 2048

@Composable
internal fun ExportMediaPreview(kind: ExportKind, uris: List<Uri>) {
    when(kind) {
        ExportKind.Video -> VideoPreview(uris.first())
        ExportKind.Gif -> GifPreview(uris.first())
        ExportKind.Frame, ExportKind.FrameAs -> StillImage(uris.first())
        ExportKind.Sequence -> SequencePreview(uris)
        ExportKind.Package, ExportKind.Xml, ExportKind.Audio -> FilePreview(uris.first())
    }
}

@Composable
private fun Modifier.fadeInWhen(ready: Boolean, from: Float = 0f): Modifier {
    val a = animateFloatAsState(if (ready) 1f else from, Motion.enter(), label = "arrive")
    return graphicsLayer { alpha = a.value }
}

@Composable
private fun StillImage(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) { loadImage(context, uri)?.let { bitmap = it } }
    Box(modifier.fillMaxSize().fadeInWhen(bitmap != null)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

private suspend fun loadImage(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / MaxSide))
        }.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun SequencePreview(uris: List<Uri>) {
    var index by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { StillImage(uris[index]) }
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton(ExportIcons.Prev, stringResource(R.string.export_previous), index > 0) { index-- }
            Text("${index + 1} / ${uris.size}", Modifier.weight(1f), color = Am.OnScrim, fontSize = 13.sp, textAlign = TextAlign.Center)
            StepButton(ExportIcons.Next, stringResource(R.string.export_next), index < uris.size - 1) { index++ }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(62.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = animatedColor(if (enabled) Am.OnScrim else Dim))
    }
}

@Composable
private fun GifPreview(uri: Uri) {
    val context = LocalContext.current
    var drawable by remember { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(uri) {
        drawable = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) }.getOrNull()
        }
    }
    DisposableEffect(drawable) {
        val shown = drawable
        onDispose { (shown as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = Modifier.fillMaxSize().fadeInWhen(drawable != null),
        update = { v ->
            if (v.drawable !== drawable) {
                v.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
            }
        },
    )
}

@Composable
private fun FilePreview(uri: Uri) {
    val context = LocalContext.current
    val name = remember(uri) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(ExportIcons.File, null, Modifier.size(72.dp), tint = Am.OnScrim)
        Spacer(Modifier.height(14.dp))
        Text(name, color = Am.OnScrim, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf<VideoFacts?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var stopped by remember { mutableStateOf(false) }
    var playWhenReady by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    val asked = remember { arrayOfNulls<MediaPlayer>(1) }
    var sourced by remember { mutableStateOf<MediaPlayer?>(null) }
    val player = remember(attempt, stopped) {
        if (stopped) null else MediaPlayer().apply {
            setOnPreparedListener {
                durationMs = it.duration
                it.seekTo(positionMs.coerceAtLeast(1).toLong(), MediaPlayer.SEEK_CLOSEST)
                ready = true
                failed = false
                if (playWhenReady) { playWhenReady = false; it.start(); playing = true }
            }
            setOnCompletionListener { playing = false; positionMs = 0; it.seekTo(1, MediaPlayer.SEEK_CLOSEST) }
            setOnErrorListener { _, what, extra ->
                Log.w("AMExport", "the result player failed ($what, $extra)")
                ready = false; playing = false; failed = true
                true
            }
        }
    }
    val current by rememberUpdatedState(player)
    DisposableEffect(player) { onDispose { player?.release(); ready = false; playing = false } }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        if (ExportDebug.failPlayers > 0) {
            ExportDebug.failPlayers--
            failed = true
        } else if (runCatching { p.setDataSource(context, uri) }.isFailure) failed = true
        else sourced = p
    }
    LaunchedEffect(sourced, surface) {
        val p = sourced?.takeIf { it === player } ?: return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        runCatching { p.setSurface(s) }
        if (asked[0] !== p) {
            asked[0] = p
            if (runCatching { p.prepareAsync() }.isFailure) failed = true
        } else if (ready) p.seekTo(p.currentPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
    }
    LaunchedEffect(failed) {
        if (failed && attempt == 0) {
            delay(RetryMs)
            failed = false
            attempt++
        }
    }
    LaunchedEffect(uri) { facts = withContext(Dispatchers.IO) { videoFacts(context, uri) } }
    LaunchedEffect(playing) {
        while (playing) {
            if (!scrubbing) current?.let { positionMs = it.currentPosition }
            delay(50)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> if (playing) { current?.pause(); playing = false }
                Lifecycle.Event.ON_STOP -> { current?.takeIf { ready }?.let { positionMs = it.currentPosition }; stopped = true }
                Lifecycle.Event.ON_START -> stopped = false
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun toggle() {
        if (failed) { failed = false; playWhenReady = true; attempt++; return }
        val p = current ?: return
        if (!ready) return
        if (playing) p.pause() else p.start()
        playing = !playing
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toggle() },
            contentAlignment = Alignment.Center,
        ) {
            val shown = facts
            if (shown != null) {
                Box(Modifier.aspectRatio(shown.aspect.takeIf { it > 0f } ?: (16f / 9))) {
                    shown.still?.takeIf { !ready }?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { surface = Surface(texture) }

                                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        runCatching { current?.setSurface(null) }
                                        surface?.release()
                                        surface = null
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().fadeInWhen(ready, from = 0.01f),
                    )
                }
            }
            if (failed) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(Am.Scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Icon(ExportIcons.Play, stringResource(R.string.export_play), Modifier.size(28.dp), tint = Am.OnScrim)
                }
            }
        }
        Ltr { Row(Modifier.fillMaxWidth().height(62.dp).fadeInWhen(ready || failed, from = 0.35f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).clickable(enabled = ready || failed, onClick = ::toggle), contentAlignment = Alignment.Center) {
                Crossfade(playing, animationSpec = Motion.fast(), label = "play") {
                    Icon(if (it) ExportIcons.Pause else ExportIcons.Play, stringResource(if (it) R.string.export_pause else R.string.export_play), tint = Am.OnScrim)
                }
            }
            SeekBar(
                if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
                onSeek = { f ->
                    if (ready) {
                        scrubbing = true
                        positionMs = (f * durationMs).toInt()
                        current?.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                    }
                },
                onDone = { scrubbing = false },
                Modifier.weight(1f).fillMaxHeight(),
            )
            Text("${time(positionMs)} / ${time(durationMs)}", Modifier.width(92.dp), color = Am.OnScrim, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
        } }
    }
}

private class VideoFacts(val aspect: Float, val still: ImageBitmap?)

private fun time(ms: Int) = "%d:%02d".format(Locale.ROOT, ms / 60000, ms / 1000 % 60)

private fun videoFacts(context: Context, uri: Uri): VideoFacts {
    val reader = MediaMetadataRetriever()
    try {
        reader.setDataSource(context, uri)
        val w = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 } ?: return VideoFacts(0f, null)
        val h = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 } ?: return VideoFacts(0f, null)
        val scale = minOf(1f, StillSide.toFloat() / maxOf(w, h))
        val still = runCatching { reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)) }
            .onFailure { Log.w("AMExport", "cannot read a still of the export", it) }.getOrNull()
        return VideoFacts(w.toFloat() / h, still?.asImageBitmap())
    } catch (e: RuntimeException) {
        return VideoFacts(0f, null)
    } finally {
        reader.release()
    }
}

private const val StillSide = 1280

private const val RetryMs = 1000L

@Composable
private fun SeekBar(fraction: Float, onSeek: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val done by rememberUpdatedState(onDone)
    Canvas(
        modifier.pointerInput(Unit) {
            val pad = 8.dp.toPx()
            fun at(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                seek(at(down.position.x))
                drag(down.id) { seek(at(it.position.x)); it.consume() }
                done()
            }
        },
    ) {
        val pad = 8.dp.toPx()
        val thick = 4.dp.toPx()
        val top = (size.height - thick) / 2
        val x = pad + fraction.coerceIn(0f, 1f) * (size.width - 2 * pad)
        drawRoundRect(Track, Offset(pad, top), Size(size.width - 2 * pad, thick), CornerRadius(thick / 2))
        drawRoundRect(Am.OnNavyAccent, Offset(pad, top), Size(x - pad, thick), CornerRadius(thick / 2))
        drawCircle(Am.OnNavyAccent, 7.dp.toPx(), Offset(x, size.height / 2))
    }
}
