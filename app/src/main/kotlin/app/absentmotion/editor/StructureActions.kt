package app.absentmotion.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withBlend
import app.absentmotion.model.trimmedTo
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.tan
import kotlin.math.sin

object MultiSelect {
    var active by mutableStateOf(false)
    var ids by mutableStateOf(setOf<Long>())

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun clear() {
        active = false
        ids = emptySet()
    }
}

private fun EditorState.numbered(base: String): String {
    val used = scene.layers.mapNotNull { it.label.removePrefix("$base ").toIntOrNull().takeIf { _ -> it.label.startsWith("$base ") } }
    return "$base ${(used.maxOrNull() ?: 0) + 1}"
}

private fun EditorState.newLayerAttrs(label: String): LinkedHashMap<String, String> = linkedMapOf(
    "id" to "${Layer.newId()}", "label" to label,
    "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.frameEnd(frame + settings.defaultLayerDuration * scene.fps)}",
)

private fun EditorState.addAndSelect(node: XmlNode) {
    val layer = Layer(node)
    edit { it.add(layer) }
    selectedId = layer.id
    panel = EditorPanel.Tools
    addOpen = false
}

fun EditorState.addNull() = addAndSelect(
    XmlNode("nullobj", newLayerAttrs(numbered("Null")), listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, 0f))))))),
    ),
)

internal val Scene.cameraZoom: Float get() = max(width, height) / tan(Math.toRadians(30.0)).toFloat() / 2f

fun EditorState.addCamera() {
    val zoom = scene.cameraZoom
    val attrs = newLayerAttrs(numbered("Camera"))
    if (scene.totalTime > 0) attrs["endTime"] = "${scene.totalTime}"
    attrs["type"] = "perspective"
    fun value(name: String, v: Float) = XmlNode(name, mapOf("value" to formatFloats(floatArrayOf(v))))
    addAndSelect(
        XmlNode("camera", attrs, listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, -zoom)))))),
            value("fov", 60f),
            XmlNode("focus-blur", mapOf("enabled" to "false"), listOf(value("strength", 0.5f), value("depth-of-field", zoom / 10), value("distance", zoom))),
            XmlNode("fog", mapOf("enabled" to "false"), listOf(XmlNode("color", mapOf("value" to "#ffffffff")), value("near-z", zoom), value("far-z", zoom * 2))),
        )),
    )
}

private fun Scene.groupNode(attrs: Map<String, String>, children: List<XmlNode>, totalTime: Int): XmlNode = XmlNode(
    "embedScene", attrs, listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(width / 2f, height / 2f, 0f)))))),
        XmlNode(
            "scene",
            linkedMapOf(
                "title" to "", "width" to "$width", "height" to "$height", "exportWidth" to "$width", "exportHeight" to "$height",
                "bgcolor" to "#00000000", "totalTime" to "$totalTime", "fps" to "$fps", "retime" to "off",
            ),
            children,
        ),
    ),
)

fun EditorState.addEmptyGroup() {
    val attrs = newLayerAttrs(numbered("Group"))
    addAndSelect(scene.groupNode(attrs, emptyList(), attrs.getValue("endTime").toInt() - attrs.getValue("startTime").toInt()))
}

fun EditorState.groupLayers(ids: Set<Long>, blend: String? = null) {
    val id = Layer.newId()
    val label = numbered("Group")
    edit { it.grouped(ids, blend, sampleMs, id, label) }
    if (scene.layer(id) == null) return
    MultiSelect.clear()
    selectedId = id
    panel = EditorPanel.Tools
}

internal fun Scene.grouped(ids: Set<Long>, blend: String?, timeMs: Int, id: Long, label: String): Scene {
    val inside = layers.filter { it.id in ids }.map { it.id }.toSet()
    if (inside.isEmpty()) return this
    val flat = layers.filter { it.id in inside && it.node["parent"]?.toLongOrNull().let { p -> p != null && p !in inside } }
        .fold(unparentChildrenOf(inside, timeMs)) { acc, layer -> acc.update(layer.id, timeMs) { detach(it, timeMs) } }
    val members = flat.layers.filter { it.id in inside }
    val start = members.minOf { it.startTime }
    val end = members.maxOf { it.endTime }
    val top = members.last().id
    val nested = members.map { layer ->
        val shifted = layer.movedTo(layer.startTime - start)
        (if (layer.id == top && blend != null && members.size > 1) shifted.withBlend(blend) else shifted).node
    }
    val group = groupNode(linkedMapOf("id" to "$id", "label" to label, "startTime" to "$start", "endTime" to "$end"), nested, end - start)
    return Scene(flat.node.copy(children = flat.node.children.mapNotNull { node ->
        val layerId = if (node.name in Scene.LAYER_TAGS) Layer(node).id else null
        when (layerId) {
            top -> group
            in inside -> null
            else -> node
        }
    }))
}

fun EditorState.toggleClippingMask() {
    val layer = selected ?: return
    val on = layer.node["clippingMask"] == "true"
    editSelected { it.copy(node = it.node.with("clippingMask", if (on) null else "true")) }
}

val EditorState.canClip: Boolean get() = selectedId?.let { id -> scene.layers.indexOfFirst { it.id == id } > 0 } == true

private class Place(val x: Float, val y: Float, val px: Float, val py: Float, val sx: Float, val sy: Float, val rot: Float)

private fun Scene.place(layer: Layer, timeMs: Int, depth: Int = 0): Place {
    val at = layer.at(timeMs, fps)
    val loc = at.location
    val piv = at.pivot
    val scale = at.scale
    var p = Place(loc[0], loc[1], piv[0], piv[1], scale[0], scale[1], at.transform("rotation", 0f)[0])
    val parent = at.node["parent"]?.toLongOrNull()?.let { id -> layers.firstOrNull { it.id == id } }
    if (parent == null || depth > 16) return p
    val q = place(parent, timeMs, depth + 1)
    val vx = (p.x + p.px - q.px) * q.sx
    val vy = (p.y + p.py - q.py) * q.sy
    val a = Math.toRadians(q.rot.toDouble())
    val rx = (vx * cos(a) - vy * sin(a)).toFloat()
    val ry = (vx * sin(a) + vy * cos(a)).toFloat()
    p = Place(q.x + rx - p.px + q.px, q.y + ry - p.py + q.py, p.px, p.py, p.sx * q.sx, p.sy * q.sy, p.rot + q.rot)
    return p
}

private fun Scene.detach(layer: Layer, timeMs: Int): Layer {
    val here = place(layer, timeMs)
    return layer.copy(node = layer.node.with("parent", null))
        .withTransform("location", here.x, here.y, layer.location[2]).withTransform("scale", here.sx, here.sy).withTransform("rotation", here.rot)
}

internal fun Scene.unparentChildrenOf(ids: Set<Long>, timeMs: Int): Scene =
    layers.filter { it.id !in ids && it.node["parent"]?.toLongOrNull() in ids }
        .fold(this) { acc, child -> acc.update(child.id, timeMs) { detach(it, timeMs) } }

fun EditorState.setParent(parentId: Long?) {
    val layer = selected ?: return
    if (parentId == layer.id) return
    val here = scene.place(layer, sampleMs)
    val old = layer.node["parent"]?.toLongOrNull()
    if (old == parentId) return
    var walk = parentId
    var guard = 0
    while (walk != null && guard++ < 32) {
        if (walk == layer.id) return
        walk = scene.layer(walk)?.node?.get("parent")?.toLongOrNull()
    }
    val q = parentId?.let { id -> scene.layer(id)?.let { scene.place(it, sampleMs) } }
    if (q != null && (q.sx == 0f || q.sy == 0f)) return
    editSelected { l ->
        var next = l.copy(node = l.node.with("parent", parentId?.toString()))
        val own = scene.place(l.copy(node = l.node.with("parent", null)), sampleMs)
        if (q != null) {
            val a = Math.toRadians(-q.rot.toDouble())
            val dx = here.x - q.x + own.px - q.px
            val dy = here.y - q.y + own.py - q.py
            val vx = (dx * cos(a) - dy * sin(a)).toFloat() / q.sx
            val vy = (dx * sin(a) + dy * cos(a)).toFloat() / q.sy
            next = next.withTransform("location", vx - own.px + q.px, vy - own.py + q.py, l.location[2])
                .withTransform("scale", here.sx / q.sx, here.sy / q.sy).withTransform("rotation", here.rot - q.rot)
        } else {
            next = scene.detach(l, sampleMs)
        }
        next
    }
}

suspend fun EditorState.alignSelection(edge: Int) {
    val layers = MultiSelect.ids.mapNotNull { scene.layer(it) }
    val bounds = layers.mapNotNull { l -> engine.layerBounds(l.id, sampleMs)?.let { l to it } }
    if (bounds.isEmpty()) return
    val horizontal = edge < 3
    val lo = if (bounds.size == 1) 0f else bounds.minOf { (_, b) -> if (horizontal) b[0] else b[1] }
    val hi = if (bounds.size == 1) (if (horizontal) scene.width else scene.height).toFloat() else bounds.maxOf { (_, b) -> if (horizontal) b[2] else b[3] }
    edit { s ->
        bounds.fold(s) { acc, (l, b) ->
            val from = if (horizontal) b[0] else b[1]
            val to = if (horizontal) b[2] else b[3]
            val d = when (edge % 3) { 0 -> lo - from; 1 -> (lo + hi) / 2 - (from + to) / 2; else -> hi - to }
            acc.update(l.id, sampleMs) { layer ->
                val p = layer.location
                if (horizontal) layer.withTransform("location", p[0] + d, p[1], p[2]) else layer.withTransform("location", p[0], p[1] + d, p[2])
            }
        }
    }
}

suspend fun EditorState.distributeSelection(horizontal: Boolean) {
    val items = MultiSelect.ids.mapNotNull { scene.layer(it) }.mapNotNull { l -> engine.layerBounds(l.id, sampleMs)?.let { l to it } }
        .sortedBy { (_, b) -> if (horizontal) b[0] else b[1] }
    if (items.size < 3) return
    fun from(b: FloatArray) = if (horizontal) b[0] else b[1]
    fun to(b: FloatArray) = if (horizontal) b[2] else b[3]
    val gap = (to(items.last().second) - from(items.first().second) - items.sumOf { (to(it.second) - from(it.second)).toDouble() }.toFloat()) / (items.size - 1)
    var cursor = to(items.first().second) + gap
    edit { s ->
        items.drop(1).dropLast(1).fold(s) { acc, (l, b) ->
            val d = cursor - from(b)
            cursor += to(b) - from(b) + gap
            acc.update(l.id, sampleMs) { layer ->
                val p = layer.location
                if (horizontal) layer.withTransform("location", p[0] + d, p[1], p[2]) else layer.withTransform("location", p[0], p[1] + d, p[2])
            }
        }
    }
}

enum class StylePart(val label: String) {
    ColorFill("Paste Fill & Color"), BorderShadow("Paste Frame & Shadow"), Blending("Paste Opacity & Blend Mode"),
}

fun EditorState.pasteStyle(parts: Set<StylePart>) {
    val from = copiedLayerForStyle ?: return
    val id = selectedId ?: return
    edit { it.withPastedStyle(id, sampleMs, from, copiedMediaForStyle, parts) }
}

internal fun Scene.withPastedStyle(id: Long, timeMs: Int, from: Layer, media: List<MediaDeclaration>, parts: Set<StylePart>): Scene {
    val styled = update(id, timeMs) { l ->
        var node = l.node
        fun take(names: List<String>, attrs: List<String>) {
            node = node.copy(children = node.children.filterNot { it.name in names } + from.node.children.filter { it.name in names })
            for (a in attrs) node = node.with(a, from.node[a])
        }
        if (StylePart.ColorFill in parts) {
            take(listOf("fillColor", "gradient", "speedMap", "gain"), listOf("fillType", "fillImage", "fillVideo", "mediaFillMode", "inTime", "outTime", "speed", "loop"))
        }
        if (StylePart.BorderShadow in parts) take(listOf("border", "shadow", "path-stroke"), emptyList())
        if (StylePart.Blending in parts) {
            take(emptyList(), listOf("blending"))
            val opacity = from.node.child("transform")?.child("opacity")
            node = node.withChild("transform", (node.child("transform") ?: XmlNode("transform")).withChild("opacity", opacity))
        }
        l.copy(node = node)
    }
    val used = styled.layer(id)?.node?.mediaUris().orEmpty()
    return media.filter { it.uri in used }.fold(styled) { scene, record -> scene.withMedia(record) }
}

fun EditorState.alignClipTimes(mode: Int) {
    val layers = chosenLayers.sortedBy { it.startTime }
    if (layers.size < 2 || mode == 1 && layers.size < 3) return
    val first = layers.first().startTime
    val last = layers.maxOf { it.endTime }
    val lastStart = layers.last().startTime
    val starts = layers.mapIndexed { index, layer ->
        layer.id to when (mode) {
            0 -> first
            2 -> last - (layer.endTime - layer.startTime)
            else -> first + (lastStart - first).toLong().times(index).div(layers.lastIndex).toInt()
        }
    }.toMap()
    edit { s -> layers.fold(s) { acc, layer -> acc.update(layer.id) { it.movedTo(starts.getValue(layer.id)) } } }
}

fun EditorState.moveClipEdges(end: Boolean, extend: Boolean) {
    val layers = chosenLayers
    val target = if (end && extend) scene.frameEnd(frame) else timeMs
    edit { s -> layers.fold(s) { acc, layer ->
        acc.update(layer.id) {
            if (!extend) it.movedTo(if (end) target - (it.endTime - it.startTime) else target)
            else if (end && target > it.startTime && s.showsAnyFrame(it.startTime, target)) it.trimmedTo(it.startTime, target)
            else if (!end && target < it.endTime && s.showsAnyFrame(target, it.endTime)) it.trimmedTo(target, it.endTime)
            else it
        }
    } }
}
