package app.absentmotion.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeDef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.imageUris
import kotlinx.coroutines.flow.MutableStateFlow
import app.absentmotion.ui.theme.Am

@Composable
fun ShapeThumbnail(def: ShapeDef, engine: EngineThread, modifier: Modifier = Modifier) {
    val attrs = linkedMapOf("fillType" to def.fillType)
    if (def.id.isNotEmpty()) attrs["s"] = def.id
    ShapeIcon(XmlNode("shape", attrs, def.children), Am.Caption.toArgb(), def.name, engine, modifier)
}

@Composable
fun LayerThumbnail(layer: Layer, engine: EngineThread, modifier: Modifier = Modifier) {
    if (layer.mediaFill?.video == true) { VideoLayerThumbnail(layer, modifier); return }
    if (layer.kind == "audio") { androidx.compose.material3.Icon(AudioIcons.Note, layer.label, modifier, tint = Am.OnDark.copy(alpha = 0.8f)); return }
    if (layer.kind == "model") { androidx.compose.material3.Icon(PanelIcons.TabTilt, layer.label, modifier, tint = Am.OnDark.copy(alpha = 0.8f)); return }
    if (layer.kind == "camera") { androidx.compose.material3.Icon(PanelIcons.Camera, layer.label, modifier, tint = Am.OnDark.copy(alpha = 0.8f)); return }
    val shape = XmlNode(layer.kind, layer.node.attrs.filterKeys { it in setOf("s", "fillType", "fillImage", "fillVideo", "mediaFillMode", "id", "size", "font", "wrapWidth", "align") }, layer.node.children.filter { it.name != "transform" && it.name != "fillColor" })
    ShapeIcon(shape, layer.fillColor or 0xFF000000.toInt(), layer.label, engine, modifier)
}

@Composable
private fun ShapeIcon(shape: XmlNode, color: Int, description: String, engine: EngineThread, modifier: Modifier) {
    val picture = rememberShapePicture(shape, color, engine)
    picture?.let { Image(it, contentDescription = description, modifier = modifier) } ?: Box(modifier)
}

internal data class IconKey(val shape: XmlNode, val color: Int, val sources: String?, val fonts: Long)

@Composable
private fun rememberShapePicture(shape: XmlNode, color: Int, engine: EngineThread): ImageBitmap? {
    val installed by engine.sources.collectAsState()
    val fonts by remember(engine) { engine.fonts?.revision ?: MutableStateFlow(0L) }.collectAsState()
    val uris = remember(shape) { shape.imageUris() }
    val text = shape.name == "text"
    val key = IconKey(shape, color, sourceIdentity(uris, installed), if (text) fonts else 0L)
    val seed = remember(key) { key.takeIf { it.sources != null }?.let { icons[it] } }
    val shown by produceState<Stamped<IconKey, ImageBitmap>?>(null, key) {
        if (seed != null) return@produceState
        val scene = XmlNode("scene", mapOf("width" to "1", "height" to "1"), listOf(shape))
        val bitmap = engine.shapeIcon(scene.toXml(), 96, color)?.asImageBitmap()
        val after = key.copy(sources = sourceIdentity(uris, engine.sources.value), fonts = if (text) engine.fonts?.revision?.value ?: 0L else 0L)
        val steady = storableIdentity(key.sources, after.sources) != null && after.fonts == key.fonts
        if (steady && bitmap != null) icons[after] = bitmap
        value = Stamped(if (steady) after else key, bitmap)
    }
    return seed ?: shown.of(key)
}

internal data class StripPicture(val bitmap: ImageBitmap, val offset: IntOffset, val size: IntSize, val frames: List<Pair<Int, ImageBitmap>> = emptyList())

@Composable
internal fun mediaStripPicture(layer: Layer, scene: Scene, engine: EngineThread, fromMs: Float = 0f, toMs: Float = 0f, tileMs: Float = 400f): StripPicture? {
    val fill = layer.mediaFill ?: return null
    if (fill.video) return videoStripPicture(layer, scene, fromMs, toMs, tileMs)
    val record = scene.media(fill.uri)
    val w = record?.width?.takeIf { it > 0 } ?: 1
    val h = record?.height?.takeIf { it > 0 } ?: 1
    val shape = XmlNode("shape", mapOf("id" to "${layer.id}", "s" to ".rect", "fillType" to "media",
        "fillImage" to fill.uri, "mediaFillMode" to "stretch"),
        listOf(XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "${w / 2f},${h / 2f}"))))
    val bitmap = rememberShapePicture(shape, 0, engine) ?: return null
    val turned = record?.orientation == 90 || record?.orientation == 270
    val width = if (turned) h else w
    val height = if (turned) w else h
    val scale = bitmap.width * 0.9f / maxOf(width, height)
    val cropped = IntSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    return StripPicture(bitmap, IntOffset((bitmap.width - cropped.width) / 2, (bitmap.height - cropped.height) / 2), cropped)
}

private val icons = BoundedCache<IconKey, ImageBitmap>(256)

internal fun dropShapeIcons() = icons.clear()
