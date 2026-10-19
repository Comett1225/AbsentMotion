package app.absentmotion.editor

import app.absentmotion.library.PresetStore
import app.absentmotion.library.SavedPreset
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withVideoSpeedMap

private val STYLED = setOf("shape", "text", "drawing", "embedScene")
private val STROKED = setOf("shape", "text")
private val MOVING = listOf("scale", "location", "rotation", "skew", "pivot")

fun canSavePreset(layer: Layer): Boolean {
    val n = layer.node
    val transform = n.child("transform")
    fun XmlNode?.plain(one: Float) = this == null || (!isKeyframed && this["value"]?.toFloatOrNull().let { it == null || it == one })
    return n.children.any { it.name in setOf("path-stroke", "border", "shadow", "effect") } ||
        (n["blending"] ?: "normal") != "normal" || !transform?.child("opacity").plain(1f) ||
        MOVING.any { transform?.child(it)?.isKeyframed == true } ||
        !n.child("gain").plain(1f) || !n.child("speedMap").plain(1f) || (n["speed"]?.toFloatOrNull() ?: 1f) != 1f ||
        (n["font"] ?: "") != "" || (n["size"]?.toFloatOrNull() ?: 18f) != 18f || (n["align"] ?: "left") != "left"
}

fun presetTitle(layer: Layer, taken: Collection<String>): String =
    layer.label.takeIf { it.isNotBlank() } ?: (1..1000).map { "Preset $it" }.firstOrNull { it !in taken } ?: "Preset"

fun presetScene(scene: Scene, layer: Layer, title: String): Scene {
    val parent = layer.node["parent"]?.toLongOrNull()
    val flat = if (parent == null) layer else scene.unparentChildrenOf(setOf(parent)).layer(layer.id) ?: layer
    val base = Scene.create(title, scene.width, scene.height, 30, 0)
    return Scene(base.node.with("type", "preset").with("ffver", "107")).add(flat.withHidden(false))
}

fun EditorState.savePreset(store: PresetStore): SavedPreset? {
    val layer = selected?.takeIf { canSavePreset(it) } ?: return null
    return store.load(store.save(presetScene(scene, layer, presetTitle(layer, store.list().map { it.title }))))
}

fun EditorState.applyPreset(preset: SavedPreset) {
    val id = selectedId ?: return
    val from = preset.layer ?: return
    edit { s -> s.update(id) { applyPreset(it, from, preset.id) } }
}

fun applyPreset(target: Layer, preset: Layer, presetId: String? = null): Layer {
    val retimed = target.withPresetSpeed(preset)
    val p = preset.node
    var n = retimed.node
    if (retimed.kind in STYLED) {
        n = n.replaced(setOf("border", "shadow") + if (retimed.kind in STROKED && preset.kind in STROKED) setOf("path-stroke") else emptySet(), p)
            .with("blending", p["blending"]).withTransformPart("opacity", p.child("transform")?.child("opacity"))
    }
    if (retimed.kind != "audio") {
        for (name in MOVING) p.child("transform")?.child(name)?.takeIf { it.isKeyframed }?.let { n = n.withTransformPart(name, it) }
        n = n.replaced(setOf("effect"), p)
    }
    if (retimed.kind == "text" && preset.kind == "text") for (a in listOf("size", "align", "font")) n = n.with(a, p[a])
    if ((retimed.kind == "audio" || retimed.mediaFill?.video == true) && (preset.kind == "audio" || preset.mediaFill?.video == true)) n = n.replaced(setOf("gain"), p)
    return retimed.copy(node = n.with("presetId", presetId ?: n["presetId"]))
}

private fun XmlNode.replaced(names: Set<String>, from: XmlNode): XmlNode {
    val at = children.indexOfFirst { it.name in names }.takeIf { it >= 0 } ?: children.size
    val kept = children.filter { it.name !in names }
    return copy(children = kept.take(at) + from.children.filter { it.name in names } + kept.drop(at))
}

private fun XmlNode.withTransformPart(name: String, part: XmlNode?): XmlNode {
    val next = (child("transform") ?: XmlNode("transform")).withChild(name, part)
    return withChild("transform", next.takeIf { it.children.isNotEmpty() || it.attrs.isNotEmpty() })
}

private fun Layer.withPresetSpeed(preset: Layer): Layer {
    fun domain(layer: Layer) = if (layer.kind == "audio" || layer.mediaFill?.video == true) "media" else layer.kind
    if (domain(this) != domain(preset)) return this
    fun speedOf(l: Layer) = l.node.child("speedMap") ?: XmlNode("speedMap", mapOf("value" to (l.node["speed"] ?: "1")))
    val map = speedOf(preset)
    if (kind == "embedScene" && map.isKeyframed) return this
    val own = speedOf(this)
    if (!map.isKeyframed && !own.isKeyframed && own["value"]?.toFloatOrNull() == map["value"]?.toFloatOrNull()) return this
    fun span(l: Layer) = VideoTiming(l).let { it.mediaMs((l.endTime - l.startTime).toDouble()) - it.mediaMs(0.0) }
    val stretch = (span(this) / span(preset).coerceAtLeast(1e-6)).toFloat()
    return withVideoSpeedMap(if (map.isKeyframed) map.withKeyframes(map.keyframes.map { it.copy(t = it.t * stretch) }) else map)
}
