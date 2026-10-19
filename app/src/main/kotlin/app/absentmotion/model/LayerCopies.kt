package app.absentmotion.model

fun copyLayers(layers: List<Layer>, startTime: Int? = null, preserveOutsideParents: Boolean = false, newId: () -> Long = Layer::newId): List<Layer> {
    val ids = mutableMapOf<Long, Long>()
    fun collect(node: XmlNode) {
        if (node.name in Scene.LAYER_TAGS) node["id"]?.toLongOrNull()?.let { ids.getOrPut(it, newId) }
        node.children.forEach(::collect)
    }
    layers.forEach { collect(it.node) }
    fun remap(node: XmlNode): XmlNode {
        var result = node
        if (node.name in Scene.LAYER_TAGS) {
            node["id"]?.toLongOrNull()?.let { result = result.with("id", ids[it]?.toString()) }
            node["parent"]?.toLongOrNull()?.let { result = result.with("parent", ids[it]?.toString() ?: node["parent"].takeIf { preserveOutsideParents }) }
        }
        return result.copy(children = node.children.map(::remap))
    }
    val shift = if (startTime == null || layers.isEmpty()) 0 else startTime - layers.minOf { it.startTime }
    return layers.map { Layer(remap(it.node)).movedTo(it.startTime + shift) }
}

internal fun Scene.withCopiedLayerIds(): Scene {
    val copies = copyLayers(layers).iterator()
    return Scene(node.copy(children = node.children.map { if (it.name in Scene.LAYER_TAGS) copies.next().node else it }))
}
