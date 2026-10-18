package app.absentmotion.model

import java.util.Locale
import kotlin.math.hypot

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    val length: Float get() = hypot(x, y)
    fun distanceTo(o: Pt) = (this - o).length
}

data class Knot(val p: Pt, val inHandle: Pt? = null, val outHandle: Pt? = null) {
    fun movedBy(d: Pt) = Knot(p + d, inHandle?.plus(d), outHandle?.plus(d))
    val sharp: Boolean get() = inHandle == null && outHandle == null
}

data class Contour(val knots: List<Knot>, val closed: Boolean, val exclude: Boolean = false)

val Layer.contours: List<Contour>? get() {
    val path = node.child("path") ?: return emptyList()
    val out = ArrayList<Contour>()
    for (source in listOf(path) + path.children.filter { it.name == "contour" }) {
        val knots = source.children.filter { it.name == "knot" }
        if (knots.isNotEmpty()) {
            if (source.children.any { it.name == "knot" && it.hasKeys() }) return null
            out += Contour(
                knots.map { k ->
                    val p = k.vec("p") ?: Pt(0f, 0f)
                    Knot(p, k.vec("in")?.plus(p), k.vec("out")?.plus(p))
                },
                source["closed"] == "true", source["exclude"] == "true",
            )
        } else {
            source["d"]?.let { d -> out += parseSvgPath(d).map { it.copy(exclude = source["exclude"] == "true") } }
        }
    }
    return out
}

private fun XmlNode.hasKeys() = children.any { part -> part.children.any { it.name == "kf" } }

private fun XmlNode.vec(name: String): Pt? = child(name)?.get("value")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
    ?.takeIf { it.size >= 2 }?.let { Pt(it[0], it[1]) }

fun Layer.withContours(contours: List<Contour>): Layer {
    val real = contours.filter { it.knots.isNotEmpty() }
    fun part(name: String, c: Contour, attrs: LinkedHashMap<String, String>): XmlNode =
        if (c.knots.size == 1) XmlNode(name, attrs.also { it["closed"] = "false" }, listOf(c.knots[0].toNode())) else XmlNode(name, attrs.also { it["d"] = c.toSvg() })
    val path = when {
        real.isEmpty() -> XmlNode("path", linkedMapOf("d" to ""))
        real.size == 1 -> part("path", real[0], linkedMapOf())
        else -> XmlNode("path", children = real.map { c -> part("contour", c, linkedMapOf<String, String>().also { if (c.exclude) it["exclude"] = "true" }) })
    }
    return copy(node = node.withChild("path", path))
}

private fun Knot.toNode() = XmlNode("knot", children = listOf(XmlNode("p", mapOf("value" to formatFloats(floatArrayOf(p.x, p.y))))))

private fun num(v: Float): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').let { if (it == "-0" || it.isEmpty()) "0" else it }
private fun Pt.svg() = "${num(x)} ${num(y)}"

fun Contour.toSvg(): String {
    if (knots.isEmpty()) return ""
    val sb = StringBuilder("M ").append(knots[0].p.svg())
    val segments = if (closed) knots.size else knots.size - 1
    for (i in 0 until segments) {
        val a = knots[i]
        val b = knots[(i + 1) % knots.size]
        if (a.outHandle == null && b.inHandle == null) sb.append("L ").append(b.p.svg())
        else sb.append("C ").append((a.outHandle ?: a.p).svg()).append(", ").append((b.inHandle ?: b.p).svg()).append(", ").append(b.p.svg())
    }
    if (closed) sb.append("Z")
    return sb.toString()
}

fun parseSvgPath(d: String): List<Contour> {
    val tokens = Regex("[MLHVCQZmlhvcqz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
    val out = ArrayList<Contour>()
    var knots = ArrayList<Knot>()
    var i = 0
    var command = ' '
    fun number(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
    fun point() = Pt(number(), number())
    fun add(c1: Pt?, c2: Pt?, p: Pt) {
        val last = knots.lastOrNull() ?: run { knots.add(Knot(p)); return }
        if (c1 != null && c1 != last.p) knots[knots.lastIndex] = last.copy(outHandle = c1)
        knots.add(Knot(p, inHandle = c2?.takeIf { it != p }))
    }
    fun finish(closed: Boolean) {
        if (knots.isEmpty()) return
        if (closed && knots.size > 2 && knots.last().p == knots.first().p) {
            val last = knots.last()
            val first = knots.first()
            if (last.outHandle == null && last.inHandle != null && first.inHandle == null) {
                knots[0] = first.copy(inHandle = last.inHandle)
                knots.removeAt(knots.lastIndex)
            } else if (last.inHandle == first.inHandle && last.outHandle == first.outHandle) {
                knots.removeAt(knots.lastIndex)
            }
        }
        out += Contour(knots, closed)
        knots = ArrayList()
    }
    while (i < tokens.size) {
        val t = tokens[i]
        if (t[0].isLetter()) { command = t[0]; i++ } else if (command == ' ') { i++; continue }
        when (command.uppercaseChar()) {
            'M' -> { finish(false); knots.add(Knot(point())); command = 'L' }
            'L' -> add(null, null, point())
            'H' -> { val last = knots.lastOrNull()?.p ?: Pt(0f, 0f); add(null, null, Pt(number(), last.y)) }
            'V' -> { val last = knots.lastOrNull()?.p ?: Pt(0f, 0f); add(null, null, Pt(last.x, number())) }
            'C' -> { val c1 = point(); val c2 = point(); add(c1, c2, point()) }
            'Q' -> {
                val last = knots.lastOrNull()?.p ?: Pt(0f, 0f)
                val q = point()
                val p = point()
                add(last + (q - last) * (2f / 3f), p + (q - p) * (2f / 3f), p)
            }
            'Z' -> { finish(true); command = ' ' }
            else -> i++
        }
    }
    finish(false)
    return out
}
