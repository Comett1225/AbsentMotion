package app.absentmotion.editor

import app.absentmotion.ai.cutoutMatte
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode

internal val Layer.isPlainGroup: Boolean get() = kind == "embedScene" && link == null && nested != null && cutoutMatte == null

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
    return if (added.isEmpty()) placed else Scene(placed.node.copy(children = added + placed.node.children))
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
    val length = group.endTime - group.startTime
    val clipped = Scene(inner.node.with("retime", null).copy(children = inner.node.children.mapNotNull { child ->
        if (child.name !in Scene.LAYER_TAGS) return@mapNotNull child
        val layer = Layer(child)
        when {
            layer.startTime >= length -> null
            layer.endTime > length -> child.with("endTime", "$length")
            else -> child
        }
    }))
    val plainAttrs = LinkedHashMap(group.node.attrs.filterKeys { it in PLAIN_GROUP_ATTRS })
    val plainChildren = listOf(clipped.node) + listOfNotNull(group.node.child("transform")?.let { t -> t.copy(children = t.children.filter { it.name in PLACEMENT }) })
    val plain = Layer(XmlNode("embedScene", plainAttrs, plainChildren))
    val host = Scene(node.copy(children = node.children.map { if (it.name in Scene.LAYER_TAGS && Layer(it).id == id) plain.node else it }))
    val loose = host.elementNodes(plain, null).map { Layer(it).movedTo(Layer(it).startTime + group.startTime).node }
    if (loose.isEmpty()) return host.remove(id)
    val known = media.map { it.uri }.toSet()
    val newMedia = clipped.node.children.filter { it.name == "media" && it["uri"] !in known }
    return Scene(host.node.copy(children = newMedia + host.node.children.flatMap { if (it === plain.node) loose else listOf(it) }))
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
