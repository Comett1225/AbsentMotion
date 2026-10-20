package app.absentmotion.ui.editor

import app.absentmotion.platform.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
private fun rememberShapePicture(shape: XmlNode, color: Int, engine: EngineThread): ImageBitmap? {
    val revision by engine.mediaRevision.collectAsState()
    val media = shape["fillType"] == "media" || shape.name == "text"
    val key = shape to color
    val picture by produceState(if (media) null else icons.get(key), key, if (media) revision else 0L) {
        value = if (media) null else icons.get(key)
        if (value == null) {
            val scene = XmlNode("scene", mapOf("width" to "1", "height" to "1"), listOf(shape))
            value = engine.shapeIcon(scene.toXml(), 96, color)?.asImageBitmap()?.also { if (!media) icons.put(key, it) }
        }
    }
    return picture
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

private val icons = LruCache<Pair<XmlNode, Int>, ImageBitmap>(200)
