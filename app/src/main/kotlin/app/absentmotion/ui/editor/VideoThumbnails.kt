package app.absentmotion.ui.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import app.absentmotion.library.openableUri

private val thumbnails = LruCache<Pair<String, Long>, ImageBitmap>(256)
private val retrieving = Mutex()

internal fun dropVideoThumbnails() = thumbnails.evictAll()

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
    val tiles = thumbnailTimes(layer, scene, fromMs, toMs, tileMs)
    val pictures by produceState(emptyList<Pair<Int, ImageBitmap>>(), uri, tiles) {
        value = withContext(Dispatchers.IO) { retrieving.withLock { thumbnailsAt(context, uri, tiles) } }
    }
    val bitmap = pictures.firstOrNull()?.second ?: return null
    return StripPicture(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), pictures)
}

internal fun thumbnailTimes(layer: Layer, scene: Scene, fromMs: Float, toMs: Float, tileMs: Float): List<Pair<Int, Long>> {
    val step = tileMs.coerceAtLeast(1f)
    val first = floor(((fromMs - layer.startTime).coerceAtLeast(0f)) / step).toInt()
    val last = floor(((toMs.coerceAtMost(layer.endTime.toFloat()) - layer.startTime).coerceAtLeast(0f)) / step).toInt().coerceAtLeast(first)
    return (first..last).map { i ->
        val projectTime = (layer.startTime + i * step).toInt()
        projectTime to layer.videoTimeUs(projectTime, scene)
    }
}

private suspend fun thumbnailsAt(context: Context, uri: String, tiles: List<Pair<Int, Long>>): List<Pair<Int, ImageBitmap>> {
    var retriever: MediaMetadataRetriever? = null
    return try {
        val pts = VideoSources.timestamps(context, uri)
        val source = VideoProxies.ready(context, uri, fileVersion(context.contentResolver, Uri.parse(uri))) ?: uri
        tiles.mapNotNull { (projectTime, timeUs) ->
            coroutineContext.ensureActive()
            val time = pts.ptsAt(timeUs)
            val key = uri to time
            val picture = synchronized(thumbnails) { thumbnails.get(key) } ?: (retriever ?: MediaMetadataRetriever().also {
                retriever = it
                it.setDataSource(context, Uri.parse(openableUri(source)))
            }).getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 160, 96)?.asImageBitmap()?.also { bitmap ->
                synchronized(thumbnails) { thumbnails.put(key, bitmap) }
            }
            picture?.let { projectTime to it }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } finally { retriever?.release() }
}
