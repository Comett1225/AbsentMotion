package app.absentmotion.model

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

enum class DrawTool(val xml: String) {
    Pen("pen"), Brush("brush"), Fill("fill"), Eraser("eraser");

    companion object {
        fun of(text: String?): DrawTool = entries.firstOrNull { it.xml.equals(text, ignoreCase = true) } ?: Pen
    }
}

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

data class Stroke(val points: List<StrokePoint>, val color: Int, val tool: DrawTool, val width: Float, val hardness: Float = 1f) {
    fun toNode(): XmlNode = XmlNode(
        "stroke",
        linkedMapOf(
            "color" to formatColor(color), "width" to String.format(Locale.US, "%.1f", width), "type" to tool.xml,
            "points" to points.joinToString(";") { String.format(Locale.US, "%.2f,%.2f,%.2f", it.x, it.y, it.pressure) },
        ).apply { if (hardness < 1f) put("hardness", String.format(Locale.US, "%.2f", hardness.coerceAtLeast(0f))) },
    )

    fun touches(x: Float, y: Float, radius: Float): Boolean {
        if (tool == DrawTool.Eraser || points.isEmpty()) return false
        if (tool == DrawTool.Fill) {
            var inside = false
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                if ((a.y > y) != (b.y > y) && x < a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)) inside = !inside
                if (segmentDistance(x, y, a, b) <= radius) return true
            }
            return inside
        }
        fun reach(p: StrokePoint) = radius + width / 2 * (if (tool == DrawTool.Brush) p.pressure else 1f)
        if (points.size == 1) return hypot(points[0].x - x, points[0].y - y) <= reach(points[0])
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (segmentDistance(x, y, a, b) <= max(reach(a), reach(b))) return true
        }
        return false
    }

    companion object {
        fun parse(node: XmlNode): Stroke = Stroke(
            node["points"].orEmpty().split(';').mapNotNull { group ->
                val v = group.split(',').map { it.trim().finiteFloat() ?: return@mapNotNull null }
                if (v.size >= 3) StrokePoint(v[0], v[1], v[2]) else null
            },
            parseColor(node["color"]) ?: 0xFF0000FF.toInt(),
            DrawTool.of(node["type"]),
            node["width"]?.finiteFloat() ?: 5f,
            node["hardness"]?.finiteFloat()?.coerceIn(0f, 1f) ?: 1f,
        )
    }
}

private fun segmentDistance(x: Float, y: Float, a: StrokePoint, b: StrokePoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val span = dx * dx + dy * dy
    val t = if (span <= 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / span).coerceIn(0f, 1f)
    return hypot(a.x + dx * t - x, a.y + dy * t - y)
}

fun MutableList<StrokePoint>.smoothTo(raw: List<StrokePoint>, amount: Float) {
    val follow = 1f - 0.95f * sqrt(amount.coerceIn(0f, 1f))
    for (i in size until raw.size) {
        val p = raw[i]
        val prev = lastOrNull()
        add(if (prev == null) p else StrokePoint(prev.x + (p.x - prev.x) * follow, prev.y + (p.y - prev.y) * follow, p.pressure))
    }
}

fun List<StrokePoint>.caughtUp(end: StrokePoint): List<StrokePoint> {
    val from = lastOrNull() ?: return listOf(end)
    if (hypot(end.x - from.x, end.y - from.y) <= 0.01f) return this
    return this + (1..6).map { k -> StrokePoint(from.x + (end.x - from.x) * k / 6, from.y + (end.y - from.y) * k / 6, end.pressure) }
}

fun speedPressure(previous: Float, distanceDp: Float, millis: Long): Float {
    val target = (1.15f - distanceDp / max(1L, millis) * 0.45f).coerceIn(0.2f, 1f)
    return previous + (target - previous) * 0.3f
}

fun List<Stroke>.touchedBy(path: List<StrokePoint>, radius: Float): Set<Int> {
    if (path.isEmpty()) return emptySet()
    val hit = HashSet<Int>()
    val step = max(radius / 2, 0.5f)
    fun test(x: Float, y: Float) { forEachIndexed { i, s -> if (i !in hit && s.touches(x, y, radius)) hit += i } }
    test(path[0].x, path[0].y)
    for (k in 1 until path.size) {
        val a = path[k - 1]
        val b = path[k]
        val pieces = ceil(hypot(b.x - a.x, b.y - a.y) / step).toInt().coerceIn(1, 10_000)
        for (j in 1..pieces) test(a.x + (b.x - a.x) * j / pieces, a.y + (b.y - a.y) * j / pieces)
    }
    return hit
}

val Layer.strokes: List<Stroke> get() = node.children.filter { it.name == "stroke" }.map(Stroke::parse)

fun Layer.withStroke(stroke: Stroke): Layer = copy(node = node.copy(children = node.children + stroke.toNode()))

fun Layer.withoutStrokes(drop: Set<Int>): Layer {
    var i = -1
    return copy(node = node.copy(children = node.children.filter { it.name != "stroke" || ++i !in drop }))
}
