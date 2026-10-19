package app.absentmotion.model

import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.sign

internal data class SceneTime(val ms: Double, val rate: Double = 1.0)

private fun nearest(value: Double) = sign(value) * floor(abs(value) + 0.5)

private class NestedClock(private val layer: Layer, hostFps: Int) {
    private val scene = layer.nested!!
    private val method = scene.node["retime"] ?: "off"
    private val speed = (layer.node.child("speedMap")?.get("value")?.toDoubleOrNull() ?: layer.node["speed"]?.toDoubleOrNull() ?: 1.0).let { if (it > 0 && it.isFinite()) it else 1.0 }
    private fun middle(length: Int, fps: Int) = floor((2 * floor(length.toDouble() * fps / 1000 + 1e-9) + 1) * 500 / fps)
    private val sceneEnd = middle(scene.totalTime, scene.fps)
    private val layerEnd = middle(layer.endTime - layer.startTime, hostFps)
    private val intro = scene.retimeIn.toDouble().coerceIn(0.0, sceneEnd)
    private val outro = if (scene.retimeOut > 0) scene.retimeOut.toDouble().coerceIn(intro, sceneEnd) else sceneEnd
    private val outroStart = layerEnd - (sceneEnd - outro)
    private val room = outroStart - intro
    private val span = outro - intro

    fun at(at: Double, quantize: Boolean): SceneTime {
        val local = at - layer.startTime
        if (method == "off") return SceneTime(local * speed, speed)
        if (method == "blank") return if (local < sceneEnd) SceneTime(if (quantize) nearest(local) else local) else SceneTime(-1.0, 0.0)
        val mapped = when {
            local >= outroStart -> SceneTime(outro + local - outroStart)
            local < intro -> SceneTime(local)
            else -> {
                val into = local - intro
                when {
                    method == "stretch" && room > 0 -> SceneTime(intro + into * span / room, span / room)
                    method == "loop" -> SceneTime(intro + if (span > 0) into % span else 0.0, if (span > 0) 1.0 else 0.0)
                    method == "loop-stretch" && span > 0 && room > 0 -> {
                        val each = room / nearest(room / span).coerceAtLeast(1.0)
                        SceneTime(intro + into % each * span / each, span / each)
                    }
                    else -> SceneTime(intro + minOf(into, span), if (into < span) 1.0 else 0.0)
                }
            }
        }
        return if (quantize) mapped.copy(ms = nearest(mapped.ms)) else mapped
    }
}

internal fun Layer.nestedTime(at: Double, hostFps: Int, quantize: Boolean = true): SceneTime =
    if (nested == null) SceneTime(at) else NestedClock(this, hostFps).at(at, quantize)

internal class LayerTrack(val layer: Layer, val scene: Scene, val parents: List<Pair<Layer, Int>>) {
    private val clocks = parents.map { (parent, fps) -> NestedClock(parent, fps) }
    val startMs: Int get() = parents.firstOrNull()?.first?.startTime ?: layer.startTime
    val endMs: Int get() = (parents.firstOrNull()?.first?.endTime ?: layer.endTime) + 1

    fun timeAt(rootMs: Double, activeOnly: Boolean, quantize: Boolean = true): SceneTime? {
        var time = SceneTime(rootMs)
        for (i in parents.indices) {
            val parent = parents[i].first
            if (activeOnly && (parent.hidden || time.ms < parent.startTime || time.ms >= parent.endTime + 1.0)) return null
            val next = clocks[i].at(time.ms, quantize)
            if (activeOnly && next.ms < 0) return null
            time = SceneTime(next.ms, time.rate * next.rate)
        }
        if (activeOnly && (layer.hidden || time.ms < layer.startTime || time.ms >= layer.endTime + 1.0)) return null
        return time
    }
}

internal fun Scene.layerTracks(): List<LayerTrack> = buildList {
    fun walk(scene: Scene, parents: List<Pair<Layer, Int>>) {
        for (layer in scene.layers) {
            if (layer.hidden) continue
            val nested = layer.nested
            if (nested == null) add(LayerTrack(layer, scene, parents)) else walk(nested, parents + (layer to scene.fps))
        }
    }
    walk(this@layerTracks, emptyList())
}

internal fun Scene.fontNames(): List<String> {
    fun walk(scene: Scene): List<String> = scene.layers.flatMap { layer ->
        if (layer.kind == "text") listOf(layer.font) else layer.nested?.let(::walk).orEmpty()
    }
    return walk(this).distinct()
}

internal fun XmlNode.mapTimedLayers(fps: Int, timeMs: Double, change: (Layer, Int, Double) -> XmlNode): XmlNode = copy(children = children.map { node ->
    if (node.name !in Scene.LAYER_TAGS) node
    else {
        val layer = Layer(node)
        val nested = layer.nested
        if (nested == null) change(layer, fps, timeMs)
        else node.withChild("scene", nested.node.mapTimedLayers(nested.fps, layer.nestedTime(timeMs, fps).ms, change))
    }
})
