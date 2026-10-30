package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode

internal fun previewOrder(last: Int, playhead: Int, fps: Int): List<Int> {
    val at = playhead.coerceIn(0, last.coerceAtLeast(0))
    val ahead = fps.coerceIn(1, 120)
    val behind = ahead / 3
    return buildList {
        add(at)
        for (offset in 1..ahead) {
            if (at + offset <= last) add(at + offset)
            if (offset <= behind && at - offset >= 0) add(at - offset)
        }
    }
}

private fun XmlNode.visual(): XmlNode = copy(attrs = attrs - "label", children = children.map { it.visual() })

private fun Scene.visualLayers(): List<Layer> = layers.filter { it.kind != "audio" }

private fun Scene.visualHeader(): XmlNode = node.copy(
    attrs = node.attrs - setOf("title", "modifiedTime", "totalTime", "createdTime"),
    children = node.children.filter { it.name !in Scene.LAYER_TAGS && it.name != "bookmark" },
)

private fun Layer.externalDependencies(): Boolean = kind == "camera" || kind == "embedScene" ||
    node["parent"]?.let { it != "0" } == true || node["pathLayer"]?.let { it != "0" } == true || node["clippingMask"] == "true" ||
    node.children.any { it.name == "effect" && it["hidden"] != "true" }

internal fun previewDirtyRanges(before: Scene, after: Scene): List<IntRange> {
    val whole = listOf(0..maxOf(before.lastFrame, after.lastFrame))
    if (before.visualHeader() != after.visualHeader()) return whole
    val old = before.visualLayers()
    val next = after.visualLayers()
    if (old.map { it.node.visual() } == next.map { it.node.visual() }) return emptyList()
    if ((old + next).any { it.externalDependencies() }) return whole
    val oldIds = old.map { it.id }
    val newIds = next.map { it.id }
    if (oldIds.filter { it in newIds } != newIds.filter { it in oldIds }) return whole
    val previous = old.associateBy { it.id }
    val current = next.associateBy { it.id }
    return (previous.keys + current.keys).flatMap { id ->
        val a = previous[id]
        val b = current[id]
        if (a?.node?.visual() == b?.node?.visual()) emptyList()
        else listOfNotNull(a, b).map { layer ->
            before.frameAt(layer.startTime).coerceAtLeast(0)..maxOf(before.frameAt(layer.endTime), 0)
        }
    }
}
