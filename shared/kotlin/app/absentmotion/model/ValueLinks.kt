package app.absentmotion.model

data class NumberRef(val ref: PropRef, val component: Int = 0)

data class ValueLink(
    val layer: Long,
    val source: NumberRef,
    val multiply: Float = 1f,
    val add: Float = 0f,
    val delay: Float = 0f,
    val fallback: Float = 0f,
) {
    fun node(component: Int) = XmlNode("valueLink", mapOf(
        "component" to "$component", "layer" to "$layer", "kind" to source.ref.kind.name.lowercase(),
        "property" to source.ref.name, "effect" to "${source.ref.effectIndex}", "sourceComponent" to "${source.component}",
        "multiply" to "$multiply", "add" to "$add", "delay" to "$delay", "fallback" to "$fallback",
    ))
}

fun XmlNode.valueLink(component: Int): ValueLink? {
    val n = children.firstOrNull { it.name == "valueLink" && (it["component"]?.toIntOrNull() ?: 0) == component } ?: return null
    val kind = PropRef.Kind.entries.firstOrNull { it.name.equals(n["kind"], true) } ?: return null
    return ValueLink(n["layer"]?.toLongOrNull() ?: return null,
        NumberRef(PropRef(kind, n["property"] ?: return null, n["effect"]?.toIntOrNull() ?: -1), n["sourceComponent"]?.toIntOrNull() ?: 0),
        n["multiply"]?.finiteFloat() ?: 1f, n["add"]?.finiteFloat() ?: 0f, n["delay"]?.finiteFloat() ?: 0f, n["fallback"]?.finiteFloat() ?: 0f)
}

fun Layer.withValueLink(target: NumberRef, link: ValueLink?, fallback: String = "0"): Layer {
    val old = element(target.ref) ?: XmlNode(target.ref.name, mapOf("value" to fallback))
    return withElement(target.ref, old.copy(children = old.children.filterNot {
        it.name == "valueLink" && (it["component"]?.toIntOrNull() ?: 0) == target.component
    } + listOfNotNull(link?.node(target.component))))
}

fun Scene.canLink(layer: Long, target: NumberRef, link: ValueLink): Boolean {
    val targetLayer = this.layer(layer) ?: return false
    val sourceLayer = this.layer(link.layer) ?: return false
    fun valid(l: Layer, n: NumberRef): Boolean = when (n.ref.kind) {
        PropRef.Kind.Transform -> n.component in 0 until when (n.ref.name) {
            "location", "pivot" -> 3
            "scale", "skew", "tilt" -> 2
            "rotation", "opacity" -> 1
            else -> 0
        }
        PropRef.Kind.Effect -> l.effects.getOrNull(n.ref.effectIndex) != null && l.element(n.ref)?.get("type") !in setOf("color", "bool", "string")
        else -> false
    }
    if (!valid(targetLayer, target) || !valid(sourceLayer, link.source)) return false
    if (target.component !in 0..2 || link.source.component !in 0..2) return false
    if (target.ref.kind !in listOf(PropRef.Kind.Transform, PropRef.Kind.Effect) || link.source.ref.kind !in listOf(PropRef.Kind.Transform, PropRef.Kind.Effect)) return false
    if (!link.multiply.isFinite() || !link.add.isFinite() || !link.delay.isFinite() || !link.fallback.isFinite()) return false
    val visited = hashSetOf<Pair<Long, NumberRef>>()
    fun follows(id: Long, number: NumberRef, depth: Int): Boolean {
        if (id == layer && number == target) return true
        if (depth >= 64) return true
        if (!visited.add(id to number)) return false
        val source = this.layer(id) ?: return false
        val next = source.element(number.ref)?.valueLink(number.component)
        if (next != null && follows(next.layer, next.source, depth + 1)) return true
        if (number.ref.kind == PropRef.Kind.Transform) {
            val parent = source.node["parent"]?.toLongOrNull()
            if ((parent != null || source.effects.any { it["id"] == "absent.randomdisplace" && it["hidden"] != "true" }) && number.ref == PropRef.Location && (0..2).any { c -> c != number.component && follows(id, NumberRef(PropRef.Location, c), depth + 1) }) return true
            if (parent != null && number.ref.name in setOf("location", "scale", "rotation")) {
                val refs = if (number.ref.name == "location") listOf(PropRef.Location, PropRef.Scale, PropRef.Pivot, PropRef.Rotation, PropRef.Tilt) else listOf(number.ref)
                if (refs.any { ref -> (0..if (ref == PropRef.Location) 2 else if (ref == PropRef.Rotation) 0 else 1).any { follows(parent, NumberRef(ref, it), depth + 1) } }) return true
            }
            if (source.effects.any { e -> e["hidden"] != "true" && e.children.any { p -> (0..2).any { c -> p.valueLink(c)?.let { follows(it.layer, it.source, depth + 1) } == true } } }) return true
        }
        return false
    }
    return !follows(link.layer, link.source, 0)
}

fun Scene.withValueLink(layer: Long, target: NumberRef, link: ValueLink?, fallback: String = "0"): Scene =
    if (link != null && !canLink(layer, target, link)) this else update(layer) { it.withValueLink(target, link, fallback) }

fun XmlNode.withoutValueLinks(ids: Set<Long>): XmlNode {
    if (name == "scene") return this
    val next = children.filterNot { it.name == "valueLink" && it["layer"]?.toLongOrNull() in ids }.map { it.withoutValueLinks(ids) }
    return if (next == children) this else copy(children = next)
}

fun XmlNode.withoutAnyValueLinks(): XmlNode {
    val next = children.filterNot { it.name == "valueLink" }.map { it.withoutAnyValueLinks() }
    return if (next == children) this else copy(children = next)
}

fun Scene.withValueLinksFollowing(before: Scene): Scene {
    val oldLayers = before.layers.associateBy { it.id }
    val newLayers = layers.associateBy { it.id }
    fun walk(node: XmlNode): XmlNode? {
        if (node.name == "scene") return node
        if (node.name == "valueLink" && node["kind"] == "effect") {
            val id = node["layer"]?.toLongOrNull() ?: return node
            val index = node["effect"]?.toIntOrNull() ?: return node
            val oldEffects = oldLayers[id]?.effects ?: return node
            val old = oldEffects.getOrNull(index) ?: return node
            val next = newLayers[id]?.effects ?: return null
            val moved = next.indexOfFirst { it === old }.takeIf { it >= 0 }
                ?: next.indexOfFirst { it == old }.takeIf { it >= 0 }
                ?: index.takeIf { next.size == oldEffects.size && next.getOrNull(index)?.get("id") == old["id"] }
                ?: return null
            return if (moved == index) node else node.with("effect", "$moved")
        }
        val children = node.children.mapNotNull(::walk)
        return if (children == node.children) node else node.copy(children = children)
    }
    return Scene(node.copy(children = node.children.mapNotNull(::walk)))
}
