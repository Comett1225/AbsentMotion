package app.absentmotion.ui.editor

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
import app.absentmotion.media.VideoSources
import app.absentmotion.media.ptsAt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.videoTimeUs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.floor

private val thumbnails = LruCache<Pair<String, Long>, ImageBitmap>(256)

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
    val step = tileMs.coerceAtLeast(1f)
    val first = floor(((fromMs - layer.startTime).coerceAtLeast(0f)) / step).toInt()
    val last = floor(((toMs.coerceAtMost(layer.endTime.toFloat()) - layer.startTime).coerceAtLeast(0f)) / step).toInt().coerceAtLeast(first)
    val pictures by produceState(emptyList<Pair<Int, ImageBitmap>>(), uri, layer.node, first, last, step) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val pts = VideoSources.timestamps(context, uri)
                retriever.setDataSource(context, Uri.parse(uri))
                buildList {
                    for (i in first..last) {
                        coroutineContext.ensureActive()
                        val projectTime = (layer.startTime + i * step).toInt()
                        val time = pts.ptsAt(layer.videoTimeUs(projectTime, scene))
                        val key = uri to time
                        val picture = synchronized(thumbnails) { thumbnails.get(key) } ?: retriever.getScaledFrameAtTime(
                            time, MediaMetadataRetriever.OPTION_CLOSEST, 160, 96)?.asImageBitmap()?.also { bitmap ->
                            synchronized(thumbnails) { thumbnails.put(key, bitmap) }
                        }
                        if (picture != null) add(projectTime to picture)
                    }
                }
            } catch (_: Exception) { emptyList() } finally { retriever.release() }
        }
    }
    val bitmap = pictures.firstOrNull()?.second ?: return null
    return StripPicture(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), pictures)
}
