package app.absentmotion.ui.editor

import app.absentmotion.platform.scaledFrame
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.absentmotion.media.VideoProxies
import app.absentmotion.media.VideoSources
import app.absentmotion.media.fileVersion
import app.absentmotion.media.ptsAt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.videoTimeUs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import app.absentmotion.library.openableUri

internal data class FrameKey(val uri: String, val version: String, val timeUs: Long)

private data class StripRequest(val uri: String, val version: String?, val tiles: List<Pair<Int, Long>>)

private val thumbnails = BoundedCache<FrameKey, ImageBitmap>(256)
private val versions = MutableStateFlow(SourceVersions())
private val retrieving = Mutex()

internal fun dropVideoThumbnails() = thumbnails.clear()

internal fun distrustVideoThumbnails() = versions.update { it.copy(trusted = false) }

internal suspend fun recheckVideoThumbnails(context: Context) {
    val known = versions.value.versions.keys
    val fresh = withContext(Dispatchers.IO) { known.associateWith { fileVersion(context.contentResolver, Uri.parse(it)) } }
    versions.update { current -> fresh.entries.fold(current) { next, (uri, version) -> next.with(uri, version) }.copy(trusted = true) }
}

private fun cachedFrames(uri: String, version: String?, tiles: List<Pair<Int, Long>>): List<Pair<Int, ImageBitmap>> =
    if (version == null) emptyList() else cachedTiles(tiles) { thumbnails[FrameKey(uri, version, it)] }

@Composable
internal fun VideoLayerThumbnail(layer: Layer, modifier: Modifier) {
    val scene = Scene(app.absentmotion.model.XmlNode("scene", mapOf("fps" to "30"), listOf(layer.node)))
    val picture = videoStripPicture(layer, scene, layer.startTime.toFloat(), layer.startTime.toFloat(), 100f)
    picture?.let { Image(it.bitmap, layer.label, modifier) } ?: Box(modifier)
}

@Composable
internal fun videoStripPicture(layer: Layer, scene: Scene, fromMs: Float, toMs: Float, tileMs: Float): StripPicture? {
    val context = LocalContext.current.applicationContext
    val uri = layer.mediaFill?.uri ?: return null
    if (fromMs > layer.endTime || toMs < layer.startTime) return null
    val tiles = remember(layer.node, fromMs, toMs, tileMs, scene.fps) { thumbnailTimes(layer, scene, fromMs, toMs, tileMs) }
    val checked by versions.collectAsState()
    val request = StripRequest(uri, checked.versions[uri], tiles)
    val seed = remember(request, checked.trusted) { cachedFrames(uri, checked.seed(uri), tiles) }
    val shown by produceState(null as Stamped<StripRequest, List<Pair<Int, ImageBitmap>>>?, request) {
        val version = withContext(Dispatchers.IO) { fileVersion(context.contentResolver, Uri.parse(uri)) }
        if (version != request.version) { versions.update { it.with(uri, version) }; return@produceState }
        val known = cachedFrames(uri, version, tiles)
        value = Stamped(request, known)
        if (version != null && known.size < tiles.size) value = Stamped(request, withContext(Dispatchers.IO) { retrieving.withLock { thumbnailsAt(context, uri, version, tiles) } }.ifEmpty { known })
    }
    val pictures = shown.of(request) ?: seed
    val bitmap = pictures.firstOrNull()?.second ?: return null
    return StripPicture(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), pictures)
}

internal fun thumbnailTimes(layer: Layer, scene: Scene, fromMs: Float, toMs: Float, tileMs: Float): List<Pair<Int, Long>> {
    val step = tileMs.coerceAtLeast(1f)
    val first = floor(((fromMs - layer.startTime).coerceAtLeast(0f)) / step).toInt()
    val last = floor(((toMs.coerceAtMost(layer.endTime.toFloat()) - layer.startTime).coerceAtLeast(0f)) / step).toInt().coerceAtLeast(first)
    val timing = timingOf(layer)
    return (first..last).map { i ->
        val projectTime = (layer.startTime + i * step).toInt()
        projectTime to layer.videoTimeUs(projectTime, scene, timing)
    }
}

private suspend fun thumbnailsAt(context: Context, uri: String, version: String, tiles: List<Pair<Int, Long>>): List<Pair<Int, ImageBitmap>> {
    var retriever: MediaMetadataRetriever? = null
    return try {
        val pts = VideoSources.timestamps(context, uri)
        val source = VideoProxies.ready(context, uri, version) ?: uri
        tiles.mapNotNull { (projectTime, timeUs) ->
            coroutineContext.ensureActive()
            while (VideoProxies.paused) {
                retriever?.release()
                retriever = null
                delay(250)
            }
            val time = pts.ptsAt(timeUs)
            val key = FrameKey(uri, version, timeUs)
            val picture = thumbnails[key] ?: (retriever ?: MediaMetadataRetriever().also {
                retriever = it
                it.setDataSource(context, Uri.parse(openableUri(source)))
            }).scaledFrame(time, MediaMetadataRetriever.OPTION_CLOSEST, 160, 96)?.asImageBitmap()?.also { thumbnails[key] = it }
            picture?.let { projectTime to it }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } finally { retriever?.release() }
}
