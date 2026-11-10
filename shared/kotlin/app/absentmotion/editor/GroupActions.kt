package app.absentmotion.editor

import app.absentmotion.ai.cutoutMatte
import app.absentmotion.model.FrameRef
import app.absentmotion.model.HoldEasing
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframeAt
import app.absentmotion.model.keyframes
import app.absentmotion.model.mediaUris
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.nestedCut
import app.absentmotion.model.retimedVideo
import app.absentmotion.model.trimmedTo
import app.absentmotion.model.valueAt
import app.absentmotion.model.withoutValueLinks
import kotlin.math.roundToInt

internal val Layer.isPlainGroup: Boolean get() = kind == "embedScene" && link == null && nested != null && cutoutMatte == null

internal val Layer.picksFrames: Boolean get() = isPlainGroup || (kind == "embedScene" && link != null && nested != null)

internal fun Layer.withPickedFrame(frame: Int): Layer = withValue(FrameRef, formatFloats(floatArrayOf(frame.toFloat()))).withFrameHold()

internal fun Layer.withFrameHold(): Layer {
    val element = element(FrameRef) ?: return this
    val here = element.keyframeAt(snap)
    val keys = element.keyframes
    if (here < 0 || keys[here].e != null) return this
    return withElement(FrameRef, element.withKeyframes(keys.toMutableList().also { it[here] = it[here].copy(e = HoldEasing) }))
}

private fun Scene.groupNode(id: Long): XmlNode? = node.children.firstOrNull { it.name in Scene.LAYER_TAGS && Layer(it).id == id }

internal fun Scene.focused(path: List<Long>): Scene? {
    if (path.isEmpty()) return this
    var inner: Scene = this
    for (id in path) inner = inner.groupNode(id)?.let(::Layer)?.takeIf { it.isPlainGroup }?.nested ?: return null
    val have = inner.media.map { it.uri }.toSet()
    val shared = node.children.filter { it.name == "media" && it["uri"] !in have }
    return Scene(inner.node.copy(children = shared + inner.node.children))
}

internal fun Scene.openablePath(path: List<Long>): List<Long> {
    var inner: Scene = this
    path.forEachIndexed { i, id ->
        inner = inner.groupNode(id)?.let(::Layer)?.takeIf { it.isPlainGroup }?.nested ?: return path.take(i)
    }
    return path
}

internal fun Scene.withFocused(path: List<Long>, edited: Scene): Scene {
    if (path.isEmpty()) return edited
    val rootUris = media.map { it.uri }.toSet()
    val (mediaNodes, others) = edited.node.children.partition { it.name == "media" }
    val original = focused(path.dropLast(1))?.groupNode(path.last())?.let(::Layer)?.nested
    val ownUris = original?.media?.map { it.uri }?.toSet().orEmpty()
    val kept = mediaNodes.filter { it["uri"] in ownUris && it["uri"] !in rootUris }
    val added = mediaNodes.filter { it["uri"] !in ownUris && it["uri"] !in rootUris }
    val inner = Scene(edited.node.copy(children = kept + others))
    val placed = replaceInside(path, inner)
    val withAdded = if (added.isEmpty()) placed else Scene(placed.node.copy(children = added + placed.node.children))
    return withAdded.withoutOrphanMedia(node.mediaUris())
}

private fun Scene.withoutOrphanMedia(before: Set<String>): Scene {
    val gone = before - node.mediaUris()
    return if (gone.isEmpty()) this else Scene(node.copy(children = node.children.filterNot { it.name == "media" && it["uri"] in gone }))
}

private fun Scene.replaceInside(path: List<Long>, inner: Scene): Scene {
    val id = path.first()
    val group = groupNode(id) ?: return this
    val child = Layer(group).nested ?: return this
    val next = if (path.size == 1) inner else child.replaceInside(path.drop(1), inner)
    return Scene(node.copy(children = node.children.map { if (it === group) it.withChild("scene", next.node) else it }))
}

internal fun Layer.ungroupLosesStyle(): Boolean = isPlainGroup && !flattensIntoAsset()

internal fun Scene.ungrouped(id: Long): Scene? {
    val group = layer(id)?.takeIf { it.isPlainGroup } ?: return null
    val inner = group.nested ?: return null
    val cut = group.nestedCut
    val speed = if (inner.retime == "off") (group.node.child("speedMap")?.valueAt(0f)?.toDoubleOrNull() ?: group.node["speed"]?.toDoubleOrNull())?.takeIf { it > 0 && it.isFinite() } ?: 1.0 else 1.0
    val lo = cut * speed
    val hi = (cut + group.endTime - group.startTime) * speed
    val from = Math.ceil(lo).toInt()
    val to = Math.floor(hi).toInt()
    fun place(t: Int) = if (speed == 1.0) t - cut else ((t - lo) / speed).roundToInt()
    val clipped = Scene(inner.node.with("retime", null).copy(children = inner.node.children.mapNotNull { child ->
        if (child.name !in Scene.LAYER_TAGS) return@mapNotNull child
        val layer = Layer(child)
        if (layer.startTime >= hi || layer.endTime <= lo) return@mapNotNull null
        val kept = if (layer.startTime < lo || layer.endTime > hi) layer.trimmedTo(maxOf(layer.startTime, from), minOf(layer.endTime, to)) else layer
        when {
            speed == 1.0 -> if (kept !== layer || cut > 0) kept.movedTo(kept.startTime - cut).node else child
            else -> {
                val start = place(kept.startTime)
                val end = maxOf(place(kept.endTime), start + 1)
                if (kept.kind == "audio" || kept.mediaFill?.video == true) kept.retimedVideo(start, end).node else kept.node.with("startTime", "$start").with("endTime", "$end")
            }
        }
    }))
    val plainAttrs = LinkedHashMap(group.node.attrs.filterKeys { it in PLAIN_GROUP_ATTRS })
    val plainChildren = listOf(clipped.node) + listOfNotNull(group.node.child("transform")?.let { t -> t.copy(children = t.children.filter { it.name in PLACEMENT }) })
    val plain = Layer(XmlNode("embedScene", plainAttrs, plainChildren))
    val placed = Scene(node.copy(children = node.children.map { if (it.name in Scene.LAYER_TAGS && Layer(it).id == id) plain.node else it }))
    val host = placed.layers.filter { it.id != id && it.node["parent"]?.toLongOrNull() == id }
        .fold(placed) { acc, child -> acc.update(child.id) { acc.flattened(it) ?: it.copy(node = it.node.with("parent", null)) } }
    val loose = host.elementNodes(plain, null).map { Layer(it).movedTo(Layer(it).startTime + group.startTime).node }
    if (loose.isEmpty()) return host.remove(id)
    val known = media.map { it.uri }.toSet()
    val newMedia = clipped.node.children.filter { it.name == "media" && it["uri"] !in known }
    val result = Scene(host.node.copy(children = newMedia + host.node.children.flatMap { if (it === plain.node) loose else listOf(it.withoutValueLinks(setOf(id))) }))
    return result.withLayers(result.layers).withoutOrphanMedia(node.mediaUris())
}

fun EditorState.askUngroup() {
    if (selected?.ungroupLosesStyle() == true) ungroupAsk = true else ungroupSelected()
}

fun EditorState.ungroupSelected(): Boolean {
    val id = selectedId ?: return false
    val before = scene
    edit { it.ungrouped(id) ?: it }
    if (scene.node === before.node) return false
    MultiSelect.clear()
    selectedId = null
    panel = EditorPanel.Tools
    return true
}
