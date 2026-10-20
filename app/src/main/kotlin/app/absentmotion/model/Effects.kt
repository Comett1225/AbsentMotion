package app.absentmotion.model

val Layer.effects: List<XmlNode> get() = node.children.filter { it.name == "effect" }

fun Layer.withEffect(index: Int, change: (XmlNode) -> XmlNode): Layer {
    var current = -1
    return copy(node = node.copy(children = node.children.map {
        if (it.name == "effect" && ++current == index) change(it) else it
    }))
}

fun Layer.addEffect(effect: XmlNode) = copy(node = node.copy(children = node.children + effect))

fun Scene.effectTargets(ids: Set<Long>): List<Layer> = layers.filter { it.id in ids && it.canAnimate }

fun Scene.withEffectsPasted(ids: Set<Long>, effects: List<XmlNode>): Scene =
    effectTargets(ids).fold(this) { scene, target ->
        scene.update(target.id) { layer -> effects.fold(layer) { next, effect -> next.addEffect(effect) } }
    }

fun Layer.removeEffect(index: Int): Layer {
    var current = -1
    return copy(node = node.copy(children = node.children.filterNot { it.name == "effect" && ++current == index }))
}

fun Layer.duplicateEffect(index: Int): Layer {
    var current = -1
    return copy(node = node.copy(children = node.children.flatMap { if (it.name == "effect" && ++current == index) listOf(it, it) else listOf(it) }))
}

fun Layer.moveEffect(from: Int, to: Int): Layer {
    val ordered = effects.toMutableList()
    if (from !in ordered.indices || to !in ordered.indices) return this
    ordered.add(to, ordered.removeAt(from))
    var current = 0
    return copy(node = node.copy(children = node.children.map { if (it.name == "effect") ordered[current++] else it }))
}

fun XmlNode.effectProperty(name: String): XmlNode? = children.firstOrNull { it.name == "property" && it["name"] == name }

fun XmlNode.withEffectProperty(name: String, type: String, value: String, t: Float, snap: Float): XmlNode {
    val old = effectProperty(name)
    val property = (old ?: XmlNode("property", mapOf("name" to name, "type" to type))).withValueAt(t, value, snap)
    return copy(children = if (old == null) children + property else children.map { if (it === old) property else it })
}

val XmlNode.appliesToChildren: Boolean get() = this["applyToChildren"] == "true"

fun XmlNode.applyingToChildren(on: Boolean): XmlNode = with("applyToChildren", if (on) "true" else null)

fun Scene.hasChildren(layer: Layer): Boolean = layers.any { it.id != layer.id && it.node["parent"]?.toLongOrNull() == layer.id }
