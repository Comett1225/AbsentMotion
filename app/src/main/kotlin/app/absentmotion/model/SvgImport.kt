package app.absentmotion.model

import app.absentmotion.i18n.AppText
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class SvgImport(val layer: Layer?, val skipped: List<UiText>, val shapes: Int)

fun importSvg(text: String, title: String, host: Scene, startTime: Int, endTime: Int): SvgImport {
    val root = XmlNode.parse(text)
    require(root.local == "svg") { "This file is not an SVG image." }
    val reader = SvgReader(root)
    val items = reader.read()
    val built = reader.place(items, host.width.toFloat(), host.height.toFloat())
    val writer = LayerWriter(host, endTime - startTime)
    val top = when {
        built.isEmpty() -> null
        built.size == 1 -> built[0]
        else -> SvgGroup(title.ifBlank { AppText.get(R.string.model_layer_svg) }, built, 1f)
    }
    val node = top?.let { writer.node(it, startTime, endTime) }
    return SvgImport(node?.let(::Layer), reader.skipped.toList(), reader.shapeCount)
}

private val XmlNode.local: String get() = name.substringAfter(':')

private fun XmlNode.attr(key: String): String? = attrs[key] ?: attrs.entries.firstOrNull { it.key.substringAfter(':') == key }?.value

private data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d, a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )
    fun map(p: Pt) = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)
    val scale: Float get() = sqrt(abs(a * d - b * c))
}

private fun Contour.mapped(m: Affine) = copy(knots = knots.map { k -> Knot(m.map(k.p), k.inHandle?.let(m::map), k.outHandle?.let(m::map)) })

private sealed interface SvgItem

private class SvgShape(val label: String, val contours: List<Contour>, val fill: SvgFill?, val evenOdd: Boolean, val stroke: SvgStroke?,
                       val opacity: Float) : SvgItem

private class SvgGroup(val label: String, val children: List<SvgItem>, val opacity: Float) : SvgItem

private sealed interface SvgFill
private class SolidFill(val argb: Int) : SvgFill
private class GradientFill(val radial: Boolean, val start: Int, val end: Int, val p0: Pt, val p1: Pt) : SvgFill

private class SvgStroke(val argb: Int, val width: Float, val cap: String, val join: String)

private class CssRule(val tag: String?, val id: String?, val classes: List<String>, val specificity: Int, val order: Int, val decls: Map<String, String>)

private val Inherited = setOf(
    "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin",
    "stroke-dasharray", "color", "visibility",
)
private val Own = setOf("opacity", "display", "stop-color", "stop-opacity", "clip-path", "mask", "filter", "marker-start", "marker-mid", "marker-end", "marker")
private val Styled = Inherited + Own

private val NumberPattern = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
private val TransformPattern = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")
private val SelectorPattern = Regex("^([a-zA-Z][\\w-]*|\\*)?((?:[.#][\\w-]+)*)$")
private const val Kappa = 0.5522848f
private const val MaxShapes = 1000
private const val MaxVisits = 100_000

private fun numbers(text: String?): List<Float> = text?.let { NumberPattern.findAll(it).map { m -> m.value.toFloat() }.toList() } ?: emptyList()

private class SvgReader(private val root: XmlNode) {
    val skipped = LinkedHashSet<UiText>()
    var shapeCount = 0
    private var visits = 0
    private val ids = HashMap<String, XmlNode>()
    private val rules = ArrayList<CssRule>()
    private val counters = HashMap<String, Int>()
    private var viewW = 0f
    private var viewH = 0f
    private var sized = true

    init {
        fun walk(n: XmlNode) {
            n.attr("id")?.let { ids.putIfAbsent(it, n) }
            if (n.local == "style") readCss(n.text)
            n.children.forEach(::walk)
        }
        walk(root)
    }

    fun read(): List<SvgItem> {
        val box = numbers(root.attr("viewBox")).takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
        viewW = length(root.attr("width"), box?.get(2) ?: 0f, box?.get(2) ?: 0f)
        viewH = length(root.attr("height"), box?.get(3) ?: 0f, box?.get(3) ?: 0f)
        sized = viewW > 0 && viewH > 0
        val start = if (box != null && sized) viewBox(box, viewW, viewH, root.attr("preserveAspectRatio")) else Affine()
        val style = computed(emptyMap(), root)
        return children(root, start, style, 0)
    }

    fun place(items: List<SvgItem>, canvasW: Float, canvasH: Float): List<SvgItem> {
        val bounds = if (sized) floatArrayOf(0f, 0f, viewW, viewH) else bounds(items) ?: return items
        val w = max(bounds[2] - bounds[0], 1e-3f)
        val h = max(bounds[3] - bounds[1], 1e-3f)
        val s = min(canvasW * 0.8f / w, canvasH * 0.8f / h)
        val m = Affine(s, 0f, 0f, s, canvasW / 2f - (bounds[0] + w / 2f) * s, canvasH / 2f - (bounds[1] + h / 2f) * s)
        fun move(item: SvgItem): SvgItem = when (item) {
            is SvgGroup -> SvgGroup(item.label, item.children.map(::move), item.opacity)
            is SvgShape -> SvgShape(
                item.label, item.contours.map { it.mapped(m) },
                (item.fill as? GradientFill)?.let { GradientFill(it.radial, it.start, it.end, m.map(it.p0), m.map(it.p1)) } ?: item.fill,
                item.evenOdd, item.stroke?.let { SvgStroke(it.argb, it.width * s, it.cap, it.join) }, item.opacity,
            )
        }
        return items.map(::move)
    }

    private fun bounds(items: List<SvgItem>): FloatArray? {
        var out: FloatArray? = null
        fun walk(item: SvgItem) {
            when (item) {
                is SvgGroup -> item.children.forEach(::walk)
                is SvgShape -> tightBounds(item.contours)?.let { b ->
                    out = out?.let { floatArrayOf(min(it[0], b[0]), min(it[1], b[1]), max(it[2], b[2]), max(it[3], b[3])) } ?: b
                }
            }
        }
        items.forEach(::walk)
        return out
    }

    private fun readCss(css: String) {
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (block in Regex("([^{}]+)\\{([^{}]*)\\}").findAll(clean)) {
            val decls = declarations(block.groupValues[2])
            for (raw in block.groupValues[1].split(',')) {
                val selector = raw.trim()
                val match = SelectorPattern.matchEntire(selector)
                if (selector.isEmpty() || selector.startsWith("@") || match == null) continue
                val tag = match.groupValues[1].takeIf { it.isNotEmpty() && it != "*" }
                val parts = Regex("[.#][\\w-]+").findAll(match.groupValues[2]).map { it.value }.toList()
                val id = parts.firstOrNull { it.startsWith("#") }?.drop(1)
                val classes = parts.filter { it.startsWith(".") }.map { it.drop(1) }
                val specificity = (if (id != null) 100 else 0) + classes.size * 10 + (if (tag != null) 1 else 0)
                rules += CssRule(tag, id, classes, specificity, rules.size, decls)
            }
        }
    }

    private fun declarations(text: String): Map<String, String> = text.split(';').mapNotNull { part ->
        val colon = part.indexOf(':')
        if (colon < 0) null else part.substring(0, colon).trim().lowercase(Locale.ROOT) to part.substring(colon + 1).replace("!important", "").trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    private fun computed(parent: Map<String, String>, n: XmlNode): Map<String, String> {
        val own = HashMap<String, String>()
        for (key in Styled) n.attrs[key]?.let { own[key] = it.trim() }
        val classes = n.attr("class")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        val id = n.attr("id")
        rules.filter { r -> (r.tag == null || r.tag == n.local) && (r.id == null || r.id == id) && classes.containsAll(r.classes) }
            .sortedWith(compareBy({ it.specificity }, { it.order })).forEach { own.putAll(it.decls) }
        n.attr("style")?.let { own.putAll(declarations(it)) }
        val out = HashMap(parent.filterKeys { it in Inherited })
        own.forEach { (k, v) -> if (v != "inherit") out[k] = v }
        return out
    }

    private fun children(n: XmlNode, m: Affine, style: Map<String, String>, depth: Int): List<SvgItem> =
        n.children.mapNotNull { element(it, m, style, depth + 1) }

    private fun element(n: XmlNode, parent: Affine, inherited: Map<String, String>, depth: Int): SvgItem? {
        if (depth > 64) return null
        if (++visits > MaxVisits) { skipped += txt(R.string.library_text_value, "parts repeated too many times"); return null }
        val tag = n.local
        when (tag) {
            "defs", "symbol", "style", "title", "desc", "metadata", "linearGradient", "radialGradient", "clipPath", "mask",
            "pattern", "marker", "script" -> return null
            "text", "tspan", "textPath" -> { skipped += txt(R.string.model_svg_skipped_text); return null }
            "image" -> { skipped += txt(R.string.model_svg_skipped_images); return null }
            "filter" -> return null
            "foreignObject" -> { skipped += txt(R.string.model_svg_skipped_embedded); return null }
            "animate", "animateTransform", "animateMotion", "set" -> { skipped += txt(R.string.model_svg_skipped_animations); return null }
        }
        val style = computed(inherited, n)
        if (style["display"] == "none") return null
        if (style["clip-path"].let { it != null && it != "none" }) skipped += txt(R.string.model_svg_skipped_clipping)
        if (style["mask"].let { it != null && it != "none" }) skipped += txt(R.string.model_svg_skipped_masks)
        if (style["filter"].let { it != null && it != "none" }) skipped += txt(R.string.model_svg_skipped_filters)
        if (listOf("marker", "marker-start", "marker-mid", "marker-end").any { style[it].let { v -> v != null && v != "none" } }) skipped += txt(R.string.model_svg_skipped_markers)
        val m = parent * transform(n.attr("transform"))
        val opacity = (style["opacity"]?.let(::unit) ?: 1f).coerceIn(0f, 1f)
        return when (tag) {
            "g", "a", "switch" -> group(label(n, AppText.get(R.string.model_layer_group)), children(n, m, style, depth), opacity)
            "svg" -> {
                val x = length(n.attr("x"), viewW, 0f)
                val y = length(n.attr("y"), viewH, 0f)
                val box = numbers(n.attr("viewBox")).takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                val w = length(n.attr("width"), viewW, box?.get(2) ?: viewW)
                val h = length(n.attr("height"), viewH, box?.get(3) ?: viewH)
                val inner = m * Affine(e = x, f = y) * (if (box != null) viewBox(box, w, h, n.attr("preserveAspectRatio")) else Affine())
                group(label(n, AppText.get(R.string.model_layer_group)), children(n, inner, style, depth), opacity)
            }
            "use" -> {
                val ref = (n.attr("href"))?.removePrefix("#")?.let(ids::get) ?: return null
                if (ref === n || depth > 32) return null
                val moved = m * Affine(e = length(n.attr("x"), viewW, 0f), f = length(n.attr("y"), viewH, 0f))
                val item = if (ref.local == "symbol") {
                    val box = numbers(ref.attr("viewBox")).takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                    val w = length(n.attr("width"), viewW, box?.get(2) ?: 0f)
                    val h = length(n.attr("height"), viewH, box?.get(3) ?: 0f)
                    val inner = if (box != null && w > 0 && h > 0) moved * viewBox(box, w, h, ref.attr("preserveAspectRatio")) else moved
                    group(label(ref, AppText.get(R.string.model_layer_group)), children(ref, inner, computed(style, ref), depth), 1f)
                } else element(ref, moved, style, depth + 1)
                when {
                    item == null -> null
                    opacity >= 1f -> item
                    else -> group(label(n, AppText.get(R.string.model_layer_group)), listOf(item), opacity)
                }
            }
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> shape(n, tag, m, style, opacity)
            else -> null
        }
    }

    private fun group(label: String, children: List<SvgItem>, opacity: Float): SvgItem? = when {
        children.isEmpty() -> null
        children.size == 1 -> when (val only = children[0]) {
            is SvgShape -> SvgShape(only.label, only.contours, only.fill, only.evenOdd, only.stroke, only.opacity * opacity)
            is SvgGroup -> SvgGroup(only.label, only.children, only.opacity * opacity)
        }
        else -> SvgGroup(label, children, opacity)
    }

    private fun label(n: XmlNode, kind: String): String = n.attr("label")?.takeIf { it.isNotBlank() } ?: n.attr("id")?.takeIf { it.isNotBlank() }
        ?: "$kind ${counters.merge(kind, 1, Int::plus)}"

    private fun shape(n: XmlNode, tag: String, m: Affine, style: Map<String, String>, opacity: Float): SvgItem? {
        if (style["visibility"].let { it == "hidden" || it == "collapse" }) return null
        if (shapeCount >= MaxShapes) { skipped += txt(R.string.model_svg_skipped_shapes, MaxShapes.toString()); return null }
        val local = geometry(n, tag).filter { c -> c.knots.isNotEmpty() && c.knots.all { it.p.x.isFinite() && it.p.y.isFinite() } }
        if (local.isEmpty()) return null
        val fill = if (tag == "line") null else paint(style["fill"] ?: "black", style, "fill-opacity", local, m)
        val strokePaint = paint(style["stroke"] ?: "none", style, "stroke-opacity", local, m)
        val width = length(style["stroke-width"], diagonal(), 1f) * m.scale
        val stroke = if (strokePaint == null || !(width > 0f)) null else {
            if (strokePaint is GradientFill) skipped += txt(R.string.model_svg_skipped_gradient_strokes)
            if (style["stroke-dasharray"].let { it != null && it != "none" }) skipped += txt(R.string.model_svg_skipped_dashed)
            SvgStroke(
                when (strokePaint) { is SolidFill -> strokePaint.argb; is GradientFill -> strokePaint.start },
                width, style["stroke-linecap"]?.takeIf { it in setOf("butt", "round", "square") } ?: "butt",
                style["stroke-linejoin"]?.let { if (it == "miter-clip" || it == "arcs") "miter" else it }?.takeIf { it in setOf("miter", "round", "bevel") } ?: "miter",
            )
        }
        if (fill == null && stroke == null) return null
        shapeCount++
        val name = AppText.get(when (tag) { "rect" -> R.string.shape_name_rectangle; "circle" -> R.string.shape_name_circle; "ellipse" -> R.string.shape_name_ellipse; "line" -> R.string.shape_name_line; "polyline" -> R.string.shape_name_polyline; "polygon" -> R.string.shape_name_polygon; else -> R.string.shape_name_path })
        return SvgShape(label(n, name), local.map { it.mapped(m) }, fill, style["fill-rule"] == "evenodd", stroke, opacity)
    }

    private fun diagonal() = sqrt((viewW * viewW + viewH * viewH) / 2f)

    private fun geometry(n: XmlNode, tag: String): List<Contour> {
        fun len(key: String, ref: Float) = length(n.attr(key), ref, 0f)
        return when (tag) {
            "path" -> parseSvgPath(n.attr("d").orEmpty())
            "rect" -> {
                val w = len("width", viewW); val h = len("height", viewH)
                if (w <= 0f || h <= 0f) return emptyList()
                val rxText = n.attr("rx")?.takeIf { it != "auto" }; val ryText = n.attr("ry")?.takeIf { it != "auto" }
                var rx = rxText?.let { length(it, viewW, 0f) } ?: -1f
                var ry = ryText?.let { length(it, viewH, 0f) } ?: -1f
                if (rx < 0f) rx = max(ry, 0f)
                if (ry < 0f) ry = rx
                listOf(rect(len("x", viewW), len("y", viewH), w, h, min(rx, w / 2f), min(ry, h / 2f)))
            }
            "circle" -> {
                val r = len("r", diagonal())
                if (r <= 0f) emptyList() else listOf(ellipse(len("cx", viewW), len("cy", viewH), r, r))
            }
            "ellipse" -> {
                val rx = len("rx", viewW); val ry = len("ry", viewH)
                if (rx <= 0f || ry <= 0f) emptyList() else listOf(ellipse(len("cx", viewW), len("cy", viewH), rx, ry))
            }
            "line" -> listOf(Contour(listOf(Knot(Pt(len("x1", viewW), len("y1", viewH))), Knot(Pt(len("x2", viewW), len("y2", viewH)))), closed = false))
            else -> {
                val v = numbers(n.attr("points"))
                val points = (0 until v.size / 2).map { Knot(Pt(v[2 * it], v[2 * it + 1])) }
                if (points.size < 2) emptyList() else listOf(Contour(points, closed = tag == "polygon"))
            }
        }
    }

    private fun paint(value: String, style: Map<String, String>, opacityKey: String, local: List<Contour>, m: Affine): SvgFill? {
        val alpha = (style[opacityKey]?.let(::unit) ?: 1f).coerceIn(0f, 1f)
        val text = value.trim()
        if (text.startsWith("url(")) {
            val id = text.substringAfter("url(").substringBefore(')').trim().trim('\'', '"').removePrefix("#")
            val target = ids[id]
            if (target != null && (target.local == "linearGradient" || target.local == "radialGradient")) return gradient(target, alpha, local, m)
            if (target?.local == "pattern") skipped += txt(R.string.model_svg_skipped_patterns)
            val fallback = text.substringAfter(')').trim()
            return if (fallback.isEmpty()) null else paint(fallback, style, opacityKey, local, m)
        }
        val argb = when (text) {
            "none", "transparent" -> return null
            "currentColor", "currentcolor" -> parseSvgColor(style["color"] ?: "black")
            else -> parseSvgColor(text)
        } ?: 0xFF000000.toInt()
        return SolidFill(withAlpha(argb, alpha))
    }

    private fun gradient(node: XmlNode, alpha: Float, local: List<Contour>, m: Affine): SvgFill? {
        val chain = ArrayList<XmlNode>()
        var at: XmlNode? = node
        while (at != null && at !in chain && chain.size < 16) {
            chain += at
            at = at.attr("href")?.removePrefix("#")?.let(ids::get)
        }
        fun attr(key: String) = chain.firstNotNullOfOrNull { it.attr(key) }
        val stops = chain.firstOrNull { c -> c.children.any { it.local == "stop" } }?.children?.filter { it.local == "stop" }.orEmpty()
        if (stops.isEmpty()) return null
        if (stops.size > 2) skipped += txt(R.string.model_svg_skipped_gradient_colours)
        fun stop(s: XmlNode): Pair<Float, Int> {
            val style = computed(emptyMap(), s)
            val colour = parseSvgColor(style["stop-color"] ?: "black") ?: 0xFF000000.toInt()
            return (unit(s.attr("offset") ?: "0") ?: 0f).coerceIn(0f, 1f) to withAlpha(colour, (style["stop-opacity"]?.let(::unit) ?: 1f).coerceIn(0f, 1f) * alpha)
        }
        val first = stop(stops.first())
        val last = stop(stops.last())
        if (stops.size == 1) return SolidFill(first.second)
        val box = attr("gradientUnits") != "userSpaceOnUse"
        val radial = node.local == "radialGradient"
        fun coord(key: String, fallback: Float, ref: Float): Float {
            val v = attr(key) ?: return fallback
            return if (box) unit(v) ?: fallback else length(v, ref, fallback)
        }
        val space = if (box) {
            val b = tightBounds(local) ?: return SolidFill(first.second)
            Affine(max(b[2] - b[0], 1e-3f), 0f, 0f, max(b[3] - b[1], 1e-3f), b[0], b[1])
        } else Affine()
        val g = m * space * transform(attr("gradientTransform"))
        val (p0, p1) = if (radial) {
            val cx = coord("cx", if (box) 0.5f else viewW / 2f, viewW)
            val cy = coord("cy", if (box) 0.5f else viewH / 2f, viewH)
            val r = coord("r", if (box) 0.5f else diagonal() / 2f, diagonal()) * last.first
            Pt(cx, cy) to Pt(cx + r, cy)
        } else {
            val a = Pt(coord("x1", 0f, viewW), coord("y1", 0f, viewH))
            val b = Pt(coord("x2", if (box) 1f else viewW, viewW), coord("y2", 0f, viewH))
            (a + (b - a) * first.first) to (a + (b - a) * last.first)
        }
        return GradientFill(radial, first.second, last.second, g.map(p0), g.map(p1))
    }

    private fun length(text: String?, ref: Float, fallback: Float): Float {
        val t = text?.trim()?.lowercase(Locale.ROOT) ?: return fallback
        val number = NumberPattern.find(t)?.takeIf { it.range.first == 0 }?.value?.finiteFloat() ?: return fallback
        val factor = when (t.substring(NumberPattern.find(t)!!.value.length).trim()) {
            "", "px" -> 1f
            "pt" -> 4f / 3f
            "pc" -> 16f
            "mm" -> 96f / 25.4f
            "cm" -> 96f / 2.54f
            "in" -> 96f
            "em" -> 16f
            "ex" -> 8f
            "%" -> ref / 100f
            else -> return fallback
        }
        return number * factor
    }
}

private fun unit(text: String): Float? {
    val t = text.trim()
    return if (t.endsWith("%")) t.dropLast(1).trim().finiteFloat()?.div(100f) else t.finiteFloat()
}

private fun withAlpha(argb: Int, alpha: Float): Int {
    val a = ((argb ushr 24) * alpha).toInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0xFFFFFF)
}

private fun transform(text: String?): Affine {
    if (text.isNullOrBlank()) return Affine()
    var m = Affine()
    for (match in TransformPattern.findAll(text)) {
        val v = numbers(match.groupValues[2])
        if (v.isEmpty()) continue
        fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()
        m *= when (match.groupValues[1]) {
            "matrix" -> if (v.size >= 6) Affine(v[0], v[1], v[2], v[3], v[4], v[5]) else Affine()
            "translate" -> Affine(e = v[0], f = v.getOrElse(1) { 0f })
            "scale" -> Affine(a = v[0], d = v.getOrElse(1) { v[0] })
            "rotate" -> {
                val r = rad(v[0])
                val turn = Affine(cos(r), sin(r), -sin(r), cos(r))
                if (v.size >= 3) Affine(e = v[1], f = v[2]) * turn * Affine(e = -v[1], f = -v[2]) else turn
            }
            "skewX" -> Affine(c = tan(rad(v[0])))
            else -> Affine(b = tan(rad(v[0])))
        }
    }
    return m
}

private fun viewBox(box: List<Float>, w: Float, h: Float, aspect: String?): Affine {
    val parts = (aspect ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "defer" }
    val align = parts.getOrNull(0) ?: "xMidYMid"
    var sx = w / box[2]
    var sy = h / box[3]
    if (align != "none") {
        val s = if (parts.getOrNull(1) == "slice") max(sx, sy) else min(sx, sy)
        sx = s
        sy = s
    }
    var tx = -box[0] * sx
    var ty = -box[1] * sy
    if (align != "none") {
        tx += (w - box[2] * sx) * (if ("xMid" in align) 0.5f else if ("xMax" in align) 1f else 0f)
        ty += (h - box[3] * sy) * (if ("YMid" in align) 0.5f else if ("YMax" in align) 1f else 0f)
    }
    return Affine(sx, 0f, 0f, sy, tx, ty)
}

private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float): Contour {
    if (rx <= 0f || ry <= 0f) return Contour(listOf(Knot(Pt(x, y)), Knot(Pt(x + w, y)), Knot(Pt(x + w, y + h)), Knot(Pt(x, y + h))), closed = true)
    val kx = rx * Kappa
    val ky = ry * Kappa
    return Contour(
        listOf(
            Knot(Pt(x + rx, y), inHandle = Pt(x + rx - kx, y)),
            Knot(Pt(x + w - rx, y), outHandle = Pt(x + w - rx + kx, y)),
            Knot(Pt(x + w, y + ry), inHandle = Pt(x + w, y + ry - ky)),
            Knot(Pt(x + w, y + h - ry), outHandle = Pt(x + w, y + h - ry + ky)),
            Knot(Pt(x + w - rx, y + h), inHandle = Pt(x + w - rx + kx, y + h)),
            Knot(Pt(x + rx, y + h), outHandle = Pt(x + rx - kx, y + h)),
            Knot(Pt(x, y + h - ry), inHandle = Pt(x, y + h - ry + ky)),
            Knot(Pt(x, y + ry), outHandle = Pt(x, y + ry - ky)),
        ),
        closed = true,
    )
}

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): Contour {
    val kx = rx * Kappa
    val ky = ry * Kappa
    return Contour(
        listOf(
            Knot(Pt(cx + rx, cy), Pt(cx + rx, cy - ky), Pt(cx + rx, cy + ky)),
            Knot(Pt(cx, cy + ry), Pt(cx + kx, cy + ry), Pt(cx - kx, cy + ry)),
            Knot(Pt(cx - rx, cy), Pt(cx - rx, cy + ky), Pt(cx - rx, cy - ky)),
            Knot(Pt(cx, cy - ry), Pt(cx - kx, cy - ry), Pt(cx + kx, cy - ry)),
        ),
        closed = true,
    )
}

private fun controlBounds(contours: List<Contour>): FloatArray? {
    val points = contours.flatMap { c -> c.knots.flatMap { listOfNotNull(it.p, it.inHandle, it.outHandle) } }
    if (points.isEmpty()) return null
    return floatArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

private fun tightBounds(contours: List<Contour>): FloatArray? {
    val points = ArrayList<Pt>()
    for (c in contours) {
        val n = c.knots.size
        if (n == 0) continue
        points += c.knots[0].p
        for (i in 0 until if (c.closed) n else n - 1) {
            val a = c.knots[i]
            val b = c.knots[(i + 1) % n]
            val c1 = a.outHandle ?: a.p
            val c2 = b.inHandle ?: b.p
            for (k in 1..16) {
                val t = k / 16f
                val u = 1 - t
                points += a.p * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + b.p * (t * t * t)
            }
        }
    }
    if (points.isEmpty()) return null
    return floatArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

private fun area(c: Contour): Double = c.knots.indices.sumOf { i ->
    val a = c.knots[i].p
    val b = c.knots[(i + 1) % c.knots.size].p
    (a.x * b.y - b.x * a.y).toDouble()
}

private class LayerWriter(private val host: Scene, private val duration: Int) {
    fun node(item: SvgItem, start: Int, end: Int): XmlNode {
        val attrs = linkedMapOf("id" to "${Layer.newId()}", "label" to "", "startTime" to "$start", "endTime" to "$end")
        return when (item) {
            is SvgGroup -> {
                attrs["label"] = item.label
                XmlNode("embedScene", attrs, listOf(
                    transformNode(host.width / 2f, host.height / 2f, item.opacity),
                    XmlNode("scene", linkedMapOf(
                        "title" to "", "width" to "${host.width}", "height" to "${host.height}", "exportWidth" to "${host.width}",
                        "exportHeight" to "${host.height}", "bgcolor" to "#00000000", "totalTime" to "$duration", "fps" to "${host.fps}", "retime" to "off",
                    ), item.children.map { node(it, 0, duration) }),
                ))
            }
            is SvgShape -> shape(item, attrs)
        }
    }

    private fun transformNode(x: Float, y: Float, opacity: Float) = XmlNode("transform", children = listOfNotNull(
        XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(x, y, 0f)))),
        if (opacity < 1f) XmlNode("opacity", mapOf("value" to formatFloats(floatArrayOf(opacity)))) else null,
    ))

    private fun shape(s: SvgShape, attrs: LinkedHashMap<String, String>): XmlNode {
        val box = controlBounds(s.contours) ?: floatArrayOf(0f, 0f, 0f, 0f)
        val centre = Pt((box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f)
        val shift = Affine(e = -centre.x, f = -centre.y)
        val local = s.contours.map { it.mapped(shift) }
        attrs["label"] = s.label
        attrs["fillType"] = when (s.fill) { is SolidFill -> "color"; is GradientFill -> "gradient"; null -> "none" }
        val children = ArrayList<XmlNode>()
        children += transformNode(centre.x, centre.y, s.opacity)
        when (val f = s.fill) {
            is SolidFill -> children += XmlNode("fillColor", mapOf("value" to formatColor(f.argb)))
            is GradientFill -> {
                val w = max(box[2] - box[0], 1e-3f)
                val h = max(box[3] - box[1], 1e-3f)
                fun norm(p: Pt) = "${fmt((p.x - box[0]) / w)},${fmt((p.y - box[1]) / h)}"
                children += XmlNode("gradient", linkedMapOf(
                    "type" to if (f.radial) "radial" else "linear", "startColor" to formatColor(f.start), "endColor" to formatColor(f.end),
                    "start" to norm(f.p0), "end" to norm(f.p1),
                ))
            }
            null -> Unit
        }
        val multi = s.fill != null && local.size > 1 && (s.evenOdd || s.stroke == null)
        val path = if (multi) {
            val first = area(local[0])
            val marked = local.mapIndexed { i, c -> c.copy(exclude = i > 0 && (s.evenOdd || area(c) * first < 0)) }
            Layer(XmlNode("shape")).withContours(marked).node.child("path")!!
        } else XmlNode("path", mapOf("d" to local.joinToString(" ") { it.toSvg() }))
        children += path
        s.stroke?.let { st ->
            children += XmlNode("path-stroke", linkedMapOf("cap" to st.cap, "join" to st.join), listOf(
                XmlNode("color", mapOf("value" to formatColor(st.argb))), XmlNode("size", mapOf("value" to formatNumber(st.width))),
            ))
        }
        return XmlNode("shape", attrs, children)
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.5f", v)
}

fun parseSvgColor(text: String): Int? {
    val t = text.trim().lowercase(Locale.ROOT)
    if (t.startsWith("#")) {
        val hex = t.drop(1)
        if (hex.any { Character.digit(it, 16) < 0 }) return null
        val full = when (hex.length) {
            3 -> "ff" + hex.map { "$it$it" }.joinToString("")
            4 -> hex[3].toString().repeat(2) + hex.take(3).map { "$it$it" }.joinToString("")
            6 -> "ff$hex"
            8 -> hex.substring(6) + hex.take(6)
            else -> return null
        }
        return full.toLong(16).toInt()
    }
    if (t.startsWith("rgb")) {
        val inside = t.substringAfter('(').substringBefore(')')
        val parts = inside.split(Regex("[\\s,/]+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun channel(p: String): Int? = (if (p.endsWith("%")) p.dropLast(1).finiteFloat()?.times(2.55f) else p.finiteFloat())?.toInt()?.coerceIn(0, 255)
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = parts.getOrNull(3)?.let { unit(it) } ?: 1f
        return ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
    }
    return NamedColours[t]?.let { (0xFF000000.toInt()) or it }
}

private val NamedColours: Map<String, Int> by lazy {
    ("aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4 black 000000 " +
        "blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 " +
        "chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b " +
        "darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f " +
        "darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f " +
        "darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 " +
        "dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff " +
        "gold ffd700 goldenrod daa520 gray 808080 grey 808080 green 008000 greenyellow adff2f honeydew f0fff0 hotpink ff69b4 " +
        "indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa lavenderblush fff0f5 lawngreen 7cfc00 " +
        "lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3 " +
        "lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa " +
        "lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 " +
        "magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db " +
        "mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 " +
        "midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 " +
        "olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 " +
        "paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd " +
        "powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513 " +
        "salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd " +
        "slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 " +
        "tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32")
        .split(' ').chunked(2).associate { (name, hex) -> name to hex.toInt(16) }
}
