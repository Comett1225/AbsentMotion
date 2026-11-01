package app.absentmotion.editor

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.stillFits
import app.absentmotion.library.MediaKeeper
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SCREENSHOT_UNDO_MS = 10_000L

private val RawParts = setOf("speedMap")
private val Dropped = setOf("blending", "parent", "clippingMask", "hidden", "pathLayer", "link")

data class RawFrame(val scene: Scene, val width: Int, val height: Int)

fun Scene.rawFrame(layer: Layer, media: MediaDeclaration, fits: (Int, Int) -> Boolean = { w, h -> stillFits(w, h) }): RawFrame? {
    val turned = media.orientation == 90 || media.orientation == 270
    var w = if (turned) media.height else media.width
    var h = if (turned) media.width else media.height
    if (w <= 0 || h <= 0) return null
    while (!fits(w, h) && w > 1 && h > 1) { w = (w / 2).coerceAtLeast(1); h = (h / 2).coerceAtLeast(1) }
    val blank = Scene(Scene.create("frame", w, h, fps, 0).node.with("totalTime", "$totalTime"))
    val placed = blank.videoLayer(media, 0) ?: return null
    val own = layer.node
    val attrs = LinkedHashMap(own.attrs.filterKeys { it !in Dropped })
    attrs["mediaFillMode"] = "fill"
    attrs["s"] = ".rect"
    val node = own.copy(attrs = attrs, children = placed.node.children + own.children.filter { it.name in RawParts })
    return RawFrame(blank.add(Layer(node), listOf(media)), w, h)
}

fun EditorState.canScreenshot(layer: Layer?): Boolean =
    layer?.mediaFill?.video == true && timeMs >= layer.startTime && timeMs < layer.endTime

suspend fun EditorState.screenshotFrame(context: Context, scope: CoroutineScope): Boolean {
    val layer = selected?.takeIf { canScreenshot(it) } ?: return false
    val media = scene.media.firstOrNull { it.uri == layer.mediaFill?.uri } ?: return false
    val raw = scene.rawFrame(layer, media) ?: return false
    val png = File(context.cacheDir, "screenshot-${System.nanoTime()}.png")
    try {
        if (!engine.stillPng(raw.scene.toXml(), timeMs, raw.width, raw.height, png)) return false
        val name = "Screenshot " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date()) + ".png"
        val gallery = MediaStoreExport(context)
        val saved = withContext(Dispatchers.IO) { gallery.save(null, name, "image/png") { out -> png.inputStream().use { it.copyTo(out) } } }
        val picture = mediaDeclaration(context, saved) ?: run { withContext(Dispatchers.IO) { gallery.delete(saved) }; return false }
        val own = withContext(Dispatchers.IO) {
            runCatching { MediaKeeper(store, { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) }).keep(projectId, picture, scene) }.getOrNull()
        }
        addImage(own?.takeIf { store.inMediaFolder(it.uri) } ?: keepCopy(context, picture))
        val added = selectedId ?: return true
        scope.launch { dropIfRemoved(added, saved, gallery) }
        return true
    } finally {
        withContext(Dispatchers.IO) { png.delete() }
    }
}

private suspend fun EditorState.dropIfRemoved(id: Long, saved: Uri, store: MediaStoreExport) {
    val gone = withTimeoutOrNull(SCREENSHOT_UNDO_MS) { snapshotFlow { scene.layer(id) == null }.first { it } } == true
    if (gone) withContext(Dispatchers.IO) { runCatching { store.delete(saved) } }
}
