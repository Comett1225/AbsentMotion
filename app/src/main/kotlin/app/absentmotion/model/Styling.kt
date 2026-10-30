package app.absentmotion.model

import java.util.Locale

fun XmlNode.path(path: String): XmlNode? {
    var node: XmlNode = this
    for (segment in path.split('/')) node = node.pick(segment) ?: return null
    return node
}

private fun XmlNode.pick(segment: String): XmlNode? {
    val open = segment.indexOf('[')
    if (open < 0) return child(segment)
    return children.filter { it.name == segment.substring(0, open) }.getOrNull(segment.substring(open + 1, segment.length - 1).toInt())
}

fun XmlNode.withPath(path: String, element: XmlNode): XmlNode {
    val head = path.substringBefore('/')
    val rest = path.substringAfter('/', "")
    val name = head.substringBefore('[')
    val index = if ('[' in head) head.substring(head.indexOf('[') + 1, head.length - 1).toInt() else 0
    val current = pick(head)
    val updated = if (rest.isEmpty()) element.copy(name = name) else (current ?: XmlNode(name)).withPath(rest, element)
    val positions = children.indices.filter { children[it].name == name }
    return copy(children = if (positions.size > index) children.toMutableList().also { it[positions[index]] = updated } else children + updated)
}

private fun floatText(v: Float) = String.format(Locale.US, "%.6f", if (v.isFinite()) v else 0f)

data class Border(val index: Int, val enabled: Boolean, val direction: String, val color: Int, val size: Float) {
    val colorRef get() = PropRef(PropRef.Kind.Child, "border[$index]/color")
    val sizeRef get() = PropRef(PropRef.Kind.Child, "border[$index]/size")
}

private fun Layer.number(path: String, fallback: Float): Float =
    element(PropRef(PropRef.Kind.Child, path))?.valueAt(t)?.finiteFloat() ?: fallback

private fun Layer.colour(path: String, fallback: Int): Int =
    parseColor(element(PropRef(PropRef.Kind.Child, path))?.valueAt(t)) ?: fallback

val Layer.borders: List<Border>
    get() = node.children.filter { it.name == "border" }.mapIndexed { i, b ->
        val direction = b["direction"] ?: "inside"
        Border(i, b["enabled"] != "false", direction, colour("border[$i]/color", StyleColors.White),
            number("border[$i]/size", if (direction == "centered") 4f else 6f))
    }

private fun Layer.mapBorders(change: (List<XmlNode>) -> List<XmlNode>): Layer {
    val kept = node.children.filter { it.name != "border" }
    val first = node.children.indexOfFirst { it.name == "border" }
    val borders = change(node.children.filter { it.name == "border" })
    val at = if (first < 0) kept.size else first
    return copy(node = node.copy(children = kept.take(at) + borders + kept.drop(at)))
}

private object NewStyle {
    const val BorderSize = 6f
    const val ShadowSize = 4f
    const val ShadowOffset = 3f
    const val StrokeWidth = 4f
}

private fun valueNode(name: String, value: String) = XmlNode(name, mapOf("value" to value))

fun Layer.withBorderAdded(): Layer = mapBorders { list ->
    list + XmlNode(
        "border", mapOf("direction" to "outside"),
        listOf(valueNode("color", formatColor(StyleColors.White)), valueNode("size", floatText(NewStyle.BorderSize))),
    )
}

fun Layer.withBorderRemoved(index: Int): Layer = mapBorders { list -> list.filterIndexed { i, _ -> i != index } }

fun Layer.withBorderMoved(from: Int, to: Int): Layer = mapBorders { list ->
    if (from !in list.indices || to !in list.indices) list else list.toMutableList().also { it.add(to, it.removeAt(from)) }
}

fun Layer.withBorderDirection(index: Int, direction: String): Layer = mapBorders { list ->
    list.mapIndexed { i, b -> if (i == index) b.with("direction", direction) else b }
}

data class Shadow(val present: Boolean, val enabled: Boolean, val color: Int, val opacity: Float, val size: Float, val x: Float, val y: Float)

val Layer.shadow: Shadow
    get() {
        val s = node.child("shadow")
        val offset = element(PropRef(PropRef.Kind.Child, "shadow/offset"))?.valueAt(t)?.split(',')?.map { it.trim().finiteFloat() }
        return Shadow(s != null, s != null && s["enabled"] != "false", colour("shadow/color", StyleColors.Black), number("shadow/opacity", 1f),
            number("shadow/size", 4f), offset?.getOrNull(0) ?: 3f, offset?.getOrNull(1) ?: 3f)
    }

fun Layer.withShadowEnabled(on: Boolean): Layer {
    val existing = node.child("shadow")
    val shadow = existing?.with("enabled", if (on) null else "false") ?: if (!on) return this else XmlNode(
        "shadow", mapOf("direction" to "outside"),
        listOf(
            valueNode("color", formatColor(StyleColors.Black)), valueNode("size", floatText(NewStyle.ShadowSize)),
            valueNode("offset", "${floatText(NewStyle.ShadowOffset)},${floatText(NewStyle.ShadowOffset)}"),
        ),
    )
    return copy(node = node.withChild("shadow", shadow))
}

data class StrokeStyle(val present: Boolean, val enabled: Boolean, val color: Int, val width: Float, val cap: String, val join: String,
                       val start: String, val end: String, val endSize: Float)

val Layer.stroke: StrokeStyle
    get() {
        val s = node.child("path-stroke")
        return StrokeStyle(s != null, s != null && s["enabled"] != "false", colour("path-stroke/color", StyleColors.Black), number("path-stroke/size", 4f),
            s?.get("cap") ?: "round", s?.get("join") ?: "bevel", s?.get("start") ?: "none", s?.get("end") ?: "none", s?.get("end-size")?.finiteFloat() ?: 1f)
    }

fun Layer.withStrokeEnabled(on: Boolean): Layer {
    val existing = node.child("path-stroke")
    val stroke = existing?.with("enabled", if (on) null else "false") ?: if (!on) return this else XmlNode(
        "path-stroke", linkedMapOf("cap" to "round", "join" to "bevel"),
        listOf(valueNode("color", formatColor(StyleColors.Black)), valueNode("size", floatText(NewStyle.StrokeWidth))),
    )
    return copy(node = node.withChild("path-stroke", stroke))
}

fun Layer.withStrokeAttr(key: String, value: String): Layer =
    node.child("path-stroke")?.let { copy(node = node.withChild("path-stroke", it.with(key, value))) } ?: this

val Layer.blend: String get() = node["blending"] ?: "normal"

fun Layer.withBlend(id: String): Layer = copy(node = node.with("blending", id.takeUnless { it == "normal" }))

object StyleColors {
    const val White = 0xFFFFFFFF.toInt()
    const val Black = 0xFF000000.toInt()
}

fun formatNumber(v: Float): String = floatText(v)

data class GradientStyle(val type: String, val startColor: Int, val endColor: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

val Layer.gradient: GradientStyle
    get() {
        val g = node.child("gradient")
        fun point(key: String, dx: Float, dy: Float): Pair<Float, Float> {
            val v = g?.get(key)?.split(',')?.map { it.trim().finiteFloat() }
            return (v?.getOrNull(0) ?: dx) to (v?.getOrNull(1) ?: dy)
        }
        val a = point("start", 0f, 0f)
        val b = point("end", 1f, 1f)
        return GradientStyle(g?.get("type") ?: "linear", parseColor(g?.get("startColor")) ?: StyleColors.Black,
            parseColor(g?.get("endColor")) ?: StyleColors.White, a.first, a.second, b.first, b.second)
    }

fun Layer.withGradient(g: GradientStyle): Layer = copy(
    node = node.withChild(
        "gradient",
        XmlNode(
            "gradient",
            linkedMapOf(
                "type" to g.type, "startColor" to formatColor(g.startColor), "endColor" to formatColor(g.endColor),
                "start" to "${g.x0},${g.y0}", "end" to "${g.x1},${g.y1}",
            ),
        ),
    ),
)

internal fun strokeCapIndex(cap: String): Int = listOf("butt", "round", "square").indexOf(cap).takeIf { it >= 0 } ?: 1
