package app.absentmotion.editor

import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.mediaUris
import app.absentmotion.model.withCopiedLayerIds
import kotlin.math.min

object ElementNav {
    var open: ((String) -> Unit)? = null
}

internal fun XmlNode.mapTransform(name: String, change: (FloatArray) -> Unit): XmlNode {
    val defaults = if (name == "scale") floatArrayOf(1f, 1f) else floatArrayOf(0f, 0f, 0f)
    fun value(text: String?): String = formatFloats(defaults.copyOf().also { values ->
        val parts = text?.split(',').orEmpty()
        for (i in values.indices) values[i] = parts.getOrNull(i)?.trim()?.toFloatOrNull() ?: values[i]
        change(values)
    })
    val transform = child("transform") ?: XmlNode("transform")
    val moved = transform.child(name) ?: XmlNode(name)
    val next = moved.with("value", value(moved["value"]))
        .copy(children = moved.children.map { if (it.name == "kf") it.with("v", value(it["v"])) else it })
    return withChild("transform", transform.withChild(name, next))
}

suspend fun EditorState.saveToMyElements(store: ProjectStore): Boolean {
    val layer = selected ?: return false
    val host = scene
    val group = layer.nested
    val width: Int
    val height: Int
    val nodes: List<XmlNode>
    if (group != null) {
        width = host.width
        height = host.height
        val at = layer.location
        val pivot = layer.pivot
        val scale = layer.scale
        nodes = group.layers.map { inner ->
            inner.node.mapTransform("location") { v ->
                v[0] = at[0] + scale[0] * (v[0] - group.width / 2f - pivot[0])
                v[1] = at[1] + scale[1] * (v[1] - group.height / 2f - pivot[1])
            }.mapTransform("scale") { v -> v[0] *= scale[0]; v[1] *= scale[1] }
        }
    } else {
        val bounds = lifetimeBounds(layer)
        width = ((bounds?.let { it[2] - it[0] } ?: host.width.toFloat()) + 2).toInt()
        height = ((bounds?.let { it[3] - it[1] } ?: host.height.toFloat()) + 2).toInt()
        val shifted = layer.node.with("startTime", "0").with("endTime", "${layer.endTime - layer.startTime}").with("parent", null)
        nodes = listOf(if (bounds == null) shifted else shifted.mapTransform("location") { v -> v[0] -= bounds[0]; v[1] -= bounds[1] })
    }
    val element = Scene(Scene.create(layer.label.ifBlank { "Element" }, width, height, host.fps, 0).node.with("type", "element"))
    val full = nodes.fold(element) { acc, node ->
        acc.add(Layer(node), node.mediaUris().mapNotNull { host.media(it) })
    }
    store.createElement(full)
    return true
}

private suspend fun EditorState.lifetimeBounds(layer: Layer): FloatArray? {
    val times = (listOf(layer.startTime, (layer.startTime + layer.endTime) / 2, layer.endTime) + layer.keyframeTimes()).distinct()
    val picked = times.filterIndexed { i, _ -> i % ((times.size + 23) / 24) == 0 }
    return picked.mapNotNull { engine.layerBounds(layer.id, it) }.reduceOrNull { a, b ->
        floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
    }
}

fun EditorState.addElement(store: ProjectStore, id: String, link: Boolean) {
    val element = store.load(id)?.withCopiedLayerIds() ?: return
    val start = timeMs
    val attrs = linkedMapOf(
        "id" to "${Layer.newId()}", "label" to element.title,
        "startTime" to "$start", "endTime" to "${scene.frameEnd(scene.frameAt(start + element.totalTime))}",
    )
    if (link) attrs["link"] = store.linkOf(id)
    val node = XmlNode("embedScene", attrs, listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, 0f)))))),
        element.node,
    ))
    val layer = Layer(node)
    edit { it.add(layer, element.media.filter { m -> m.uri in node.mediaUris() }) }
    selectedId = layer.id
    panel = EditorPanel.Tools
    addOpen = false
}

fun EditorState.unlinkElement() = editSelected { it.copy(node = it.node.with("link", null)) }

fun EditorState.setElementOption(attr: String, value: String) = edit { Scene(it.node.with(attr, value)) }

fun EditorState.markIntroEnd() = edit { s ->
    Scene(s.node.with("retimeIn", timeMs.takeIf { it != 0 }?.toString()).with("retimeOut", s.retimeOut.takeIf { it != 0 && it >= timeMs }?.toString()))
}

fun EditorState.markOutroStart() = edit { s ->
    val end = scene.frameEnd(frame)
    Scene(s.node.with("retimeOut", "$end").with("retimeIn", s.retimeIn.takeIf { it in 1..end }?.toString()))
}
