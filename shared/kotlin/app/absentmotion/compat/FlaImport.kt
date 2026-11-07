package app.absentmotion.compat

import app.absentmotion.R
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatColor
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

enum class FlaPart(val label: Int, val imported: Boolean) {
    Shapes(R.string.fla_part_shapes, true),
    Symbols(R.string.fla_part_symbols, true),
    Pictures(R.string.fla_part_pictures, true),
    Tweens(R.string.fla_part_tweens, true),
    Masks(R.string.fla_part_masks, true),
    Sounds(R.string.fla_part_sounds, true),
    Text(R.string.fla_part_text, false),
    Scripts(R.string.fla_part_scripts, false),
    ShapeTweens(R.string.fla_part_shape_tweens, false),
    MotionTweens(R.string.fla_part_motion_tweens, false),
    Eases(R.string.fla_part_eases, false),
    Gradients(R.string.fla_part_gradients, false),
    StrokeStyles(R.string.fla_part_stroke_styles, false),
    Filters(R.string.fla_part_filters, false),
    ColourEffects(R.string.fla_part_colour_effects, false),
    BlendModes(R.string.fla_part_blend_modes, false),
    MotionGuides(R.string.fla_part_motion_guides, false),
    Cameras(R.string.fla_part_cameras, false),
    Components(R.string.fla_part_components, false),
    Videos(R.string.fla_part_videos, false),
    Bones(R.string.fla_part_bones, false),
    UnreadPictures(R.string.fla_part_unread_pictures, false),
    UnreadSounds(R.string.fla_part_unread_sounds, false),
    Unused(R.string.fla_part_unused, false),
    Overflow(R.string.fla_part_overflow, false),
}

class FlaScene(val root: XmlNode, val media: Map<String, String>, val counts: Map<FlaPart, Int>)

const val FLA_MEDIA = "fla:"

fun readFla(files: Map<String, String>, maxLayers: Int = 40_000): FlaScene = FlaReader(files, maxLayers).read()

fun flaPicture(data: ByteArray): Pair<ByteArray, String>? {
    fun at(i: Int) = data[i].toInt() and 0xFF
    if (data.size >= 3 && at(0) == 0xFF && at(1) == 0xD8) return data to "jpg"
    if (data.size >= 8 && at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code) return data to "png"
    if (data.size >= 6 && at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code) return data to "gif"
    if (data.size < 26 || at(1) != 5 || at(0) > 3) return null
    val major = at(0)
    fun u16(i: Int) = at(i) or (at(i + 1) shl 8)
    val width = u16(4)
    val height = u16(6)
    if (width <= 0 || height <= 0 || width.toLong() * height > MaxPixels) return null
    var p = 24
    var alpha = false
    if (major >= 3) { if (p >= data.size) return null; alpha = at(p++) and 1 == 1 }
    val size = width * height * 4
    val pixels = ByteArray(size)
    val compressed = major >= 2 && p < data.size && at(p++) == 1
    if (compressed) {
        val packed = ByteArrayOutputStream()
        while (p + 2 <= data.size) {
            val n = u16(p)
            p += 2
            if (n == 0 || p + n > data.size) break
            packed.write(data, p, n)
            p += n
        }
        val inflater = Inflater()
        try {
            inflater.setInput(packed.toByteArray())
            var got = 0
            while (got < size) {
                val n = inflater.inflate(pixels, got, size - got)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                got += n
            }
            if (got < size) return null
        } catch (e: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    } else {
        if (data.size - p < size) return null
        System.arraycopy(data, p, pixels, 0, size)
    }
    val rgba = ByteArray(size)
    for (i in 0 until width * height) {
        var a = pixels[i * 4].toInt() and 0xFF
        var r = pixels[i * 4 + 1].toInt() and 0xFF
        var g = pixels[i * 4 + 2].toInt() and 0xFF
        var b = pixels[i * 4 + 3].toInt() and 0xFF
        if (!alpha) a = 255
        else if (a != 0 && a != 255) {
            a -= 1
            if (a == 0) { r = 0; g = 0; b = 0 } else {
                r = min(255, r * 256 / a); g = min(255, g * 256 / a); b = min(255, b * 256 / a)
            }
        }
        rgba[i * 4] = r.toByte(); rgba[i * 4 + 1] = g.toByte(); rgba[i * 4 + 2] = b.toByte(); rgba[i * 4 + 3] = a.toByte()
    }
    return png(width, height, rgba) to "png"
}

fun flaSound(data: ByteArray): Pair<ByteArray, String>? {
    if (data.size < 4) return null
    val b0 = data[0].toInt() and 0xFF
    val b1 = data[1].toInt() and 0xFF
    if (b0 == 0xFF && b1 and 0xE0 == 0xE0) return data to "mp3"
    if (b0 == 'I'.code && b1 == 'D'.code && data[2] == '3'.code.toByte()) return data to "mp3"
    return null
}

private const val MaxPixels = 40_000_000L

private fun png(width: Int, height: Int, rgba: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    fun chunk(type: String, body: ByteArray) {
        val head = type.toByteArray(Charsets.US_ASCII)
        val n = body.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(head)
        out.write(body)
        val crc = CRC32().apply { update(head); update(body) }.value.toInt()
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
    }
    fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
    chunk("IHDR", int(width) + int(height) + byteArrayOf(8, 6, 0, 0, 0))
    val packed = ByteArrayOutputStream()
    val deflater = Deflater(4)
    DeflaterOutputStream(packed, deflater, 64 * 1024).use { z ->
        for (y in 0 until height) {
            z.write(0)
            z.write(rgba, y * width * 4, width * 4)
        }
    }
    deflater.end()
    chunk("IDAT", packed.toByteArray())
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
}

private class Box(var x0: Double, var y0: Double, var x1: Double, var y1: Double) {
    fun add(x: Double, y: Double) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
    companion object {
        fun of(points: Sequence<Pair<Double, Double>>): Box? {
            var box: Box? = null
            for ((x, y) in points) if (x.isFinite() && y.isFinite()) box?.add(x, y) ?: run { box = Box(x, y, x, y) }
            return box
        }
        fun union(boxes: List<Box?>): Box? = of(boxes.asSequence().filterNotNull().flatMap { sequenceOf(it.x0 to it.y0, it.x1 to it.y1) })
    }
}

private class Mat(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    fun x(px: Double, py: Double) = a * px + c * py + tx
    fun y(px: Double, py: Double) = b * px + d * py + ty
    operator fun times(o: Mat) = Mat(a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d, a * o.tx + c * o.ty + tx, b * o.tx + d * o.ty + ty)
}

private class Pose(
    val x: Double, val y: Double, val px: Double, val py: Double, val rot: Double, val skew: Double, val sx: Double, val sy: Double, val alpha: Double,
    val tr: Double = 1.0, val tg: Double = 1.0, val tb: Double = 1.0, val tint: Double = 0.0,
) {
    fun values() = doubleArrayOf(x, y, px, py, rot, skew, sx, sy, alpha, tr, tg, tb, tint)
    fun moved(dx: Double, dy: Double) = Pose(x + dx, y + dy, px, py, rot, skew, sx, sy, alpha, tr, tg, tb, tint)
    fun tinted(color: Int, amount: Double) =
        Pose(x, y, px, py, rot, skew, sx, sy, alpha, (color shr 16 and 255) / 255.0, (color shr 8 and 255) / 255.0, (color and 255) / 255.0, amount)
    fun map(ux: Double, uy: Double): Pair<Double, Double> {
        val lx = (ux - px) * sx
        val ly = (uy - py) * sy
        val kx = lx + tan(skew) * ly
        val r = rot * PI / 180
        return x + cos(r) * kx - sin(r) * ly to y + sin(r) * kx + cos(r) * ly
    }
    companion object {
        fun of(v: DoubleArray) = Pose(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12])
        fun lerp(a: Pose, b: Pose, f: Double): Pose {
            val u = a.values()
            val w = b.values()
            return of(DoubleArray(u.size) { u[it] + (w[it] - u[it]) * f })
        }
    }
}

private class PathData {
    val ops = StringBuilder()
    val pts = ArrayList<Double>()
    fun move(x: Double, y: Double) { ops.append('M'); pts += x; pts += y }
    fun line(x: Double, y: Double) { ops.append('L'); pts += x; pts += y }
    fun quad(cx: Double, cy: Double, x: Double, y: Double) { ops.append('Q'); pts += cx; pts += cy; pts += x; pts += y }
    fun cubic(ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double) { ops.append('C'); pts += ax; pts += ay; pts += bx; pts += by; pts += x; pts += y }
    fun close() { ops.append('Z') }
    fun box(): Box? = Box.of(pts.indices.step(2).asSequence().map { pts[it] to pts[it + 1] })
    fun svg(cx: Double, cy: Double): String {
        val out = StringBuilder()
        var i = 0
        for (op in ops) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(op)
            val n = when (op) { 'M', 'L' -> 1; 'Q' -> 2; 'C' -> 3; else -> 0 }
            repeat(n) {
                out.append(' ').append(num(pts[i] - cx)).append(' ').append(num(pts[i + 1] - cy))
                i += 2
            }
        }
        return out.toString()
    }
}

private class EdgePoint(val x: Double, val y: Double, val control: Boolean) {
    val key: Long get() = (floor(x * 64).toLong() shl 32) xor (floor(y * 64).toLong() and 0xFFFFFFFFL)
}

private fun num(v: Double): String {
    val text = String.format(Locale.US, "%.3f", if (v.isFinite()) v else 0.0).trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}

private fun f6(v: Double) = String.format(Locale.US, "%.6f", if (v.isFinite()) v else 0.0)

private sealed class Item { abstract val box: Box? }

private class Leaf(override val box: Box?, val make: (Double, Double) -> XmlNode) : Item()

private class Wrap(val label: String, val hidden: Boolean, val blending: String?, val items: List<Item>) : Item() {
    override val box: Box? = Box.union(items.map { it.box })
}

private class SymbolScene(val scene: XmlNode, val frames: Int, val box: Box?, val dx: Double, val dy: Double, val size: Int)

private class Key(val node: XmlNode) {
    val index = node["index"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val duration = node["duration"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val elements: List<XmlNode> = node.child("elements")?.children.orEmpty()
    val end get() = index + duration
    val tween get() = node["tweenType"]
}

private class Placed(val key: Key, val inst: XmlNode)

private const val MaxNesting = 32

private class FlaReader(private val files: Map<String, String>, private val maxLayers: Int) {
    private val counts = LinkedHashMap<FlaPart, Int>()
    private val media = LinkedHashMap<String, String>()
    private val records = LinkedHashMap<String, XmlNode>()
    private val symbols = HashMap<String, XmlNode>()
    private val items = HashMap<String, XmlNode>()
    private val scenes = HashMap<String, SymbolScene>()
    private val building = HashSet<String>()
    private val audio = ArrayList<XmlNode>()
    private var fps = 24
    private var layers = 0

    private fun count(part: FlaPart, n: Int = 1) { if (n > 0) counts[part] = (counts[part] ?: 0) + n }

    private fun ms(frame: Int): Int = floor(frame * 1000.0 / fps).toInt()

    private fun endMs(frame: Int): Int = ms(frame) - 1

    private fun parse(path: String): XmlNode? = files[path]?.let { runCatching { XmlNode.parse(it.removePrefix("\uFEFF"), XmlNode.IMPORT_NODES) }.getOrNull() }

    fun read(): FlaScene {
        val doc = parse("DOMDocument.xml") ?: throw IllegalArgumentException("DOMDocument.xml")
        val width = (doc["width"]?.toDoubleOrNull() ?: 550.0).roundToInt().coerceIn(1, 16384)
        val height = (doc["height"]?.toDoubleOrNull() ?: 400.0).roundToInt().coerceIn(1, 16384)
        fps = (doc["frameRate"]?.toDoubleOrNull() ?: 24.0).roundToInt().coerceIn(1, 240)
        val background = color(doc["backgroundColor"] ?: "#FFFFFF", 1.0)
        doc.child("media")?.children?.forEach { item -> item["name"]?.let { items[it] = item } }
        for (path in files.keys.filter { it.startsWith("LIBRARY/") }.sorted()) {
            val item = parse(path)?.takeIf { it.name == "DOMSymbolItem" } ?: continue
            symbols.putIfAbsent(item["name"] ?: path.removePrefix("LIBRARY/").removeSuffix(".xml"), item)
        }
        val timelines = doc.child("timelines")?.children?.filter { it.name == "DOMTimeline" }.orEmpty()
        val built = ArrayList<Item>()
        var base = 0
        for (timeline in timelines) {
            built += timeline(timeline, base, null, true)
            base += frameCount(timeline)
        }
        count(FlaPart.Unused, symbols.keys.count { name -> scenes.keys.none { it.substringBefore('\u0000') == name } })
        val total = max(1, base)
        val layers = finish(built, width.toDouble(), height.toDouble(), 0.0, 0.0, endMs(total))
        val root = XmlNode("scene", linkedMapOf(
            "title" to "", "width" to "$width", "height" to "$height", "exportWidth" to "$width", "exportHeight" to "$height",
            "fps" to "$fps", "bgcolor" to formatColor(background), "totalTime" to "${endMs(total)}",
        ), records.values.toList() + layers + audio)
        return FlaScene(root, media, counts)
    }

    private fun frameCount(timeline: XmlNode): Int =
        timeline.child("layers")?.children.orEmpty().flatMap { it.child("frames")?.children.orEmpty() }.maxOfOrNull { Key(it).end } ?: 0

    private fun finish(items: List<Item>, width: Double, height: Double, dx: Double, dy: Double, end: Int): List<XmlNode> = items.map { item ->
        when (item) {
            is Leaf -> item.make(dx, dy)
            is Wrap -> {
                val attrs = linkedMapOf("id" to "${Layer.newId()}", "label" to item.label, "startTime" to "0", "endTime" to "$end")
                if (item.hidden) attrs["hidden"] = "true"
                item.blending?.let { attrs["blending"] = it }
                XmlNode("embedScene", attrs, listOf(
                    transform(width / 2, height / 2, 0, end),
                    innerScene(width.roundToInt(), height.roundToInt(), end, finish(item.items, width, height, dx, dy, end)),
                ))
            }
        }
    }

    private fun innerScene(width: Int, height: Int, end: Int, children: List<XmlNode>) = XmlNode("scene", linkedMapOf(
        "title" to "", "width" to "$width", "height" to "$height", "exportWidth" to "$width", "exportHeight" to "$height",
        "bgcolor" to "#00000000", "totalTime" to "$end", "fps" to "$fps", "retime" to "off",
    ), children)

    private fun timeline(timeline: XmlNode, base: Int, only: Int?, root: Boolean): List<Item> {
        val list = timeline.child("layers")?.children?.filter { it.name == "DOMLayer" }.orEmpty()
        val out = ArrayList<Item>()
        for (i in list.indices.reversed()) {
            val layer = list[i]
            when (layer["layerType"]) {
                "folder" -> continue
                "guide" -> {
                    if (only == null && list.any { it["parentLayerIndex"]?.toIntOrNull() == i }) count(FlaPart.MotionGuides)
                    continue
                }
                "camera" -> { if (only == null) count(FlaPart.Cameras); continue }
            }
            val parent = layer["parentLayerIndex"]?.toIntOrNull()?.let(list::getOrNull)
            if (parent?.get("layerType") == "mask") continue
            if (layer["layerType"] == "mask") {
                val inside = list.indices.filter { list[it]["parentLayerIndex"]?.toIntOrNull() == i && list[it]["layerType"] != "guide" }
                    .reversed().mapNotNull { named(list[it], content(list[it], base, only, root)) }
                val mask = content(layer, base, only, root)
                if (mask.isEmpty() || inside.isEmpty()) { out += inside; continue }
                if (only == null) count(FlaPart.Masks)
                out += Wrap(layer["name"] ?: "", false, null, inside + Wrap(layer["name"] ?: "", false, "mask", mask))
                continue
            }
            named(layer, content(layer, base, only, root))?.let(out::add)
        }
        return out
    }

    private fun named(layer: XmlNode, items: List<Item>): Item? {
        val label = layer["name"] ?: ""
        val hidden = layer["visible"] == "false"
        return when {
            items.isEmpty() -> null
            items.size == 1 && items[0] is Leaf -> (items[0] as Leaf).let { leaf ->
                Leaf(leaf.box) { dx, dy ->
                    val node = leaf.make(dx, dy)
                    val named = if (layer["autoNamed"] == "false" || node["label"].isNullOrEmpty()) node.with("label", label) else node
                    if (hidden) named.with("hidden", "true") else named
                }
            }
            else -> Wrap(label, hidden, null, items)
        }
    }

    private fun content(layer: XmlNode, base: Int, only: Int?, root: Boolean): List<Item> {
        val keys = layer.child("frames")?.children.orEmpty().filter { it.name == "DOMFrame" }.map(::Key).sortedBy { it.index }
        val out = ArrayList<Item>()
        if (only != null) {
            val at = keys.indexOfLast { it.index <= only }
            val key = keys.getOrNull(at)?.takeIf { only < it.end } ?: return out
            val inst = single(key)
            if (inst == null) out += statics(key.elements, 0, 0, key, only)
            else {
                val next = keys.getOrNull(at + 1)?.let(::single)?.let { Placed(keys[at + 1], it) }?.takeIf { continues(Placed(key, inst), it) }
                out += instance(listOf(Placed(key, inst)) + listOfNotNull(next), 0, only)
            }
            return out
        }
        var run = ArrayList<Placed>()
        fun flush() {
            if (run.isNotEmpty()) out += instance(run, base, null)
            run = ArrayList()
        }
        for (key in keys) {
            note(key, base, root)
            val inst = single(key)
            if (inst != null && run.isNotEmpty() && continues(run.last(), Placed(key, inst))) { run += Placed(key, inst); continue }
            flush()
            if (inst != null) run += Placed(key, inst) else out += statics(key.elements, base + key.index, base + key.end - 1, null, null)
        }
        flush()
        return out
    }

    private fun note(key: Key, base: Int, root: Boolean) {
        if (key.node.child("Actionscript") != null) count(FlaPart.Scripts)
        when (key.tween) {
            "shape" -> count(FlaPart.ShapeTweens)
            "motion object" -> count(FlaPart.MotionTweens)
            "IK pose" -> count(FlaPart.Bones)
        }
        val name = key.node["soundName"] ?: return
        if (key.node["soundSync"] == "stop") return
        val item = items[name]
        if (!root || item == null || item.name != "DOMSoundItem") { count(FlaPart.UnreadSounds); return }
        val href = item["soundDataHRef"] ?: run { count(FlaPart.UnreadSounds); return }
        val rate = when (item["format"]?.substringBefore(' ')) { "5kHz" -> 5512.5; "11kHz" -> 11025.0; "22kHz" -> 22050.0; "48kHz" -> 48000.0; else -> 44100.0 }
        val length = ((item["sampleCount"]?.toLongOrNull() ?: 0L) * 1000.0 / rate).toInt()
        if (length <= 0) { count(FlaPart.UnreadSounds); return }
        val inMs = ((key.node["inPoint44"]?.toLongOrNull() ?: 0L) / 44.1).toInt().coerceIn(0, length)
        val outMs = key.node["outPoint44"]?.toLongOrNull()?.let { (it / 44.1).toInt().coerceIn(inMs, length) } ?: length
        val start = ms(base + key.index)
        val end = if (key.node["soundSync"] == "stream") min(start + outMs - inMs, endMs(base + key.end)) else start + outMs - inMs
        if (end <= start) return
        val uri = FLA_MEDIA + href
        media[uri] = "bin/$href"
        records.getOrPut(uri) {
            XmlNode("media", linkedMapOf("uri" to uri, "filename" to leafName(name), "title" to leafName(name), "type" to "audio/mpeg", "duration" to "${length * 1000L}"))
        }
        val attrs = linkedMapOf("id" to "${Layer.newId()}", "label" to leafName(name), "startTime" to "$start", "endTime" to "$end", "src" to uri)
        if (inMs > 0) attrs["inTime"] = "$inMs"
        attrs["outTime"] = "$outMs"
        attrs["mediaFillMode"] = "fill"
        audio += XmlNode("audio", attrs)
        count(FlaPart.Sounds)
    }

    private fun single(key: Key): XmlNode? = key.elements.singleOrNull()?.takeIf { it.name == "DOMSymbolInstance" && symbols.containsKey(it["libraryItemName"]) }

    private fun kind(inst: XmlNode): String = inst["symbolType"] ?: symbols[inst["libraryItemName"]]?.get("symbolType") ?: "movie clip"

    private fun loop(inst: XmlNode): String = inst["loop"] ?: "loop"

    private fun first(inst: XmlNode): Int = inst["firstFrame"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun continues(a: Placed, b: Placed): Boolean {
        if (a.key.end != b.key.index || a.inst["libraryItemName"] != b.inst["libraryItemName"] || kind(a.inst) != kind(b.inst)) return false
        if (kind(a.inst) != "graphic") return true
        if (loop(a.inst) != loop(b.inst)) return false
        val frames = symbols[a.inst["libraryItemName"]]?.let { s -> s.child("timeline")?.child("DOMTimeline")?.let(::frameCount) } ?: 1
        return when (loop(a.inst)) {
            "single frame" -> first(a.inst) == first(b.inst)
            "play once" -> min(first(a.inst) + b.key.index - a.key.index, frames - 1) == first(b.inst)
            else -> Math.floorMod(first(a.inst) + b.key.index - a.key.index, max(1, frames)) == first(b.inst)
        }
    }

    private fun statics(elements: List<XmlNode>, from: Int, to: Int, key: Key?, sample: Int?): List<Item> {
        val out = ArrayList<Item>()
        for (e in elements) {
            if (layers >= maxLayers) { count(FlaPart.Overflow); break }
            when (e.name) {
                "DOMShape" -> { out += shape(e, matrix(e), from, to); count(FlaPart.Shapes) }
                "DOMGroup" -> out += statics(e.child("members")?.children.orEmpty(), from, to, key, sample)
                "DOMRectangleObject", "DOMOvalObject" -> { out += primitive(e, from, to); count(FlaPart.Shapes) }
                "DOMBitmapInstance" -> bitmap(e, from, to)?.let { out += it }
                "DOMSymbolInstance" -> if (symbols.containsKey(e["libraryItemName"])) {
                    val at = if (sample != null && key != null) key else Key(XmlNode("DOMFrame", mapOf("index" to "$from", "duration" to "${to - from + 1}")))
                    out += instance(listOf(Placed(at, e)), 0, sample)
                }
                "DOMStaticText", "DOMDynamicText", "DOMInputText", "DOMTLFText" -> count(FlaPart.Text)
                "DOMVideoInstance" -> count(FlaPart.Videos)
                "DOMCompiledClipInstance", "DOMComponentInstance" -> count(FlaPart.Components)
            }
        }
        return out
    }

    private fun matrix(node: XmlNode): Mat {
        val m = node.child("matrix")?.child("Matrix") ?: return Mat()
        fun v(key: String, fallback: Double) = m[key]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback
        return Mat(v("a", 1.0), v("b", 0.0), v("c", 0.0), v("d", 1.0), v("tx", 0.0), v("ty", 0.0))
    }

    private fun color(text: String, alpha: Double): Int {
        val rgb = text.removePrefix("#").take(6).toIntOrNull(16) ?: 0
        return ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
    }

    private fun base(id: Long, label: String, from: Int, to: Int) =
        linkedMapOf("id" to "$id", "label" to label, "startTime" to "${ms(from)}", "endTime" to "${endMs(to + 1)}")

    private fun pathLayer(path: PathData, from: Int, to: Int, style: (Box, MutableMap<String, String>, MutableList<XmlNode>) -> Unit): Leaf? {
        val box = path.box() ?: return null
        val cx = (box.x0 + box.x1) / 2
        val cy = (box.y0 + box.y1) / 2
        layers++
        return Leaf(box) { dx, dy ->
            val attrs = base(Layer.newId(), "", from, to)
            val children = mutableListOf(transform(cx + dx, cy + dy, ms(from), endMs(to + 1)))
            style(box, attrs, children)
            XmlNode("shape", attrs, children)
        }
    }

    private fun fillLayer(path: PathData, style: XmlNode, m: Mat, from: Int, to: Int): Item? {
        val kind = style.children.firstOrNull() ?: return null
        val evenOdd = { d: String -> XmlNode("path", linkedMapOf("d" to d, "fillRule" to "evenodd")) }
        return when (kind.name) {
            "SolidColor" -> pathLayer(path, from, to) { box, attrs, children ->
                attrs["fillType"] = "color"
                children += XmlNode("fillColor", mapOf("value" to formatColor(color(kind["color"] ?: "#000000", kind["alpha"]?.toDoubleOrNull() ?: 1.0))))
                children += evenOdd(path.svg((box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2))
            }
            "LinearGradient", "RadialGradient" -> pathLayer(path, from, to) { box, attrs, children ->
                attrs["fillType"] = "gradient"
                children += gradient(kind, m * matrix(kind), box)
                children += evenOdd(path.svg((box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2))
            }
            "BitmapFill" -> bitmapFill(path, kind, m, from, to)
            else -> null
        }
    }

    private fun gradient(g: XmlNode, m: Mat, box: Box): XmlNode {
        val entries = g.children.filter { it.name == "GradientEntry" }
        val first = entries.firstOrNull()
        val last = entries.lastOrNull()
        if (entries.size > 2) count(FlaPart.Gradients)
        fun c(e: XmlNode?) = color(e?.get("color") ?: "#000000", e?.get("alpha")?.toDoubleOrNull() ?: 1.0)
        val r0 = first?.get("ratio")?.toDoubleOrNull() ?: 0.0
        val r1 = last?.get("ratio")?.toDoubleOrNull() ?: 1.0
        val w = max(box.x1 - box.x0, 1e-3)
        val h = max(box.y1 - box.y0, 1e-3)
        fun frac(x: Double, y: Double) = "${f6((x - box.x0) / w)},${f6((y - box.y0) / h)}"
        val radial = g.name == "RadialGradient"
        val (start, end) = if (radial) {
            Pair(m.x(0.0, 0.0) to m.y(0.0, 0.0), m.x(GradientHalf * r1, 0.0) to m.y(GradientHalf * r1, 0.0))
        } else {
            val a = -GradientHalf + 2 * GradientHalf * r0
            val b = -GradientHalf + 2 * GradientHalf * r1
            Pair(m.x(a, 0.0) to m.y(a, 0.0), m.x(b, 0.0) to m.y(b, 0.0))
        }
        return XmlNode("gradient", linkedMapOf(
            "type" to if (radial) "radial" else "linear", "startColor" to formatColor(c(first)), "endColor" to formatColor(c(last)),
            "start" to frac(start.first, start.second), "end" to frac(end.first, end.second),
        ))
    }

    private fun bitmapItem(name: String?): Pair<String, XmlNode>? {
        val item = items[name ?: return null]?.takeIf { it.name == "DOMBitmapItem" } ?: return null
        val href = item["bitmapDataHRef"] ?: return null
        val uri = FLA_MEDIA + href
        val w = (item["frameRight"]?.toDoubleOrNull() ?: 0.0) / 20
        val h = (item["frameBottom"]?.toDoubleOrNull() ?: 0.0) / 20
        if (w <= 0 || h <= 0) return null
        media[uri] = "bin/$href"
        records.getOrPut(uri) {
            val file = leafName(item["href"] ?: name)
            XmlNode("media", linkedMapOf("uri" to uri, "filename" to file, "title" to file, "type" to if (item["isJPEG"] == "true") "image/jpeg" else "image/png",
                "width" to "${w.roundToInt()}", "height" to "${h.roundToInt()}"))
        }
        return uri to item
    }

    private fun picture(uri: String, label: String, w: Double, h: Double, m: Mat, from: Int, to: Int, blending: String?): Leaf {
        val cx = m.x(w / 2, h / 2)
        val cy = m.y(w / 2, h / 2)
        val pose = decompose(m, cx, cy, 0.0, 0.0, 1.0, null)
        val box = Box.of(sequenceOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h).map { (x, y) -> m.x(x, y) to m.y(x, y) })
        layers++
        return Leaf(box) { dx, dy ->
            val attrs = base(Layer.newId(), label, from, to)
            attrs["fillType"] = "media"
            attrs["fillImage"] = uri
            attrs["mediaFillMode"] = "stretch"
            attrs["s"] = ".rect"
            blending?.let { attrs["blending"] = it }
            XmlNode("shape", attrs, listOf(
                poseTransform(listOf(Triple(ms(from), pose.moved(dx, dy), null)), ms(from), endMs(to + 1)),
                XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2", "value" to "${f6(w / 2)},${f6(h / 2)}")),
            ))
        }
    }

    private fun bitmap(e: XmlNode, from: Int, to: Int): Item? {
        val (uri, item) = bitmapItem(e["libraryItemName"]) ?: return null
        count(FlaPart.Pictures)
        val w = (item["frameRight"]?.toDoubleOrNull() ?: 0.0) / 20
        val h = (item["frameBottom"]?.toDoubleOrNull() ?: 0.0) / 20
        return picture(uri, leafName(item["name"] ?: ""), w, h, matrix(e), from, to, null)
    }

    private fun bitmapFill(path: PathData, fill: XmlNode, m: Mat, from: Int, to: Int): Item? {
        val (uri, item) = bitmapItem(fill["bitmapPath"]) ?: return null
        val w = (item["frameRight"]?.toDoubleOrNull() ?: 0.0) / 20
        val h = (item["frameBottom"]?.toDoubleOrNull() ?: 0.0) / 20
        val f = matrix(fill)
        val placed = m * Mat(f.a / 20, f.b / 20, f.c / 20, f.d / 20, f.tx, f.ty)
        val box = path.box() ?: return null
        val rect = Box.of(sequenceOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h).map { (x, y) -> placed.x(x, y) to placed.y(x, y) }) ?: return null
        val straight = abs(placed.b) < 1e-6 && abs(placed.c) < 1e-6 && placed.a > 0 && placed.d > 0
        if (straight && abs(rect.x0 - box.x0) < 1 && abs(rect.y0 - box.y0) < 1 && abs(rect.x1 - box.x1) < 1 && abs(rect.y1 - box.y1) < 1) {
            return pathLayer(path, from, to) { b, attrs, children ->
                attrs["fillType"] = "media"
                attrs["fillImage"] = uri
                attrs["mediaFillMode"] = "stretch"
                children += XmlNode("path", linkedMapOf("d" to path.svg((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2), "fillRule" to "evenodd"))
            }
        }
        val shape = pathLayer(path, from, to) { b, attrs, children ->
            attrs["fillType"] = "color"
            attrs["blending"] = "mask"
            children += XmlNode("fillColor", mapOf("value" to "#ffffffff"))
            children += XmlNode("path", linkedMapOf("d" to path.svg((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2), "fillRule" to "evenodd"))
        } ?: return null
        return Wrap("", false, null, listOf(picture(uri, leafName(item["name"] ?: ""), w, h, placed, from, to, null), shape))
    }

    private fun strokeLayer(path: PathData, style: XmlNode, from: Int, to: Int): Item? {
        val stroke = style.children.firstOrNull() ?: return null
        val fill = stroke.child("fill")?.children?.firstOrNull()
        val paint = when (fill?.name) {
            "SolidColor" -> color(fill["color"] ?: "#000000", fill["alpha"]?.toDoubleOrNull() ?: 1.0)
            "LinearGradient", "RadialGradient" -> fill.children.firstOrNull { it.name == "GradientEntry" }.let { color(it?.get("color") ?: "#000000", it?.get("alpha")?.toDoubleOrNull() ?: 1.0) }
            else -> color("#000000", 1.0)
        }
        if (stroke.name != "SolidStroke") count(FlaPart.StrokeStyles)
        val weight = (stroke["weight"]?.toDoubleOrNull() ?: 1.0).let { if (stroke["solidStyle"] == "hairline" || it < 0.1) 1.0 else it }
        val cap = when (stroke["caps"]) { "none" -> "butt"; "square" -> "square"; else -> "round" }
        val join = when (stroke["joints"]) { "miter" -> "miter"; "bevel" -> "bevel"; else -> "round" }
        return pathLayer(path, from, to) { box, attrs, children ->
            attrs["fillType"] = "none"
            children += XmlNode("path", linkedMapOf("d" to path.svg((box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2)))
            children += XmlNode("path-stroke", linkedMapOf("cap" to cap, "join" to join), listOf(
                XmlNode("color", mapOf("value" to formatColor(paint))), XmlNode("size", mapOf("value" to f6(weight))),
            ))
        }
    }

    private fun shape(node: XmlNode, m: Mat, from: Int, to: Int): List<Item> {
        val fills = node.child("fills")?.children.orEmpty().associateBy { it["index"] ?: "" }
        val strokes = node.child("strokes")?.children.orEmpty().associateBy { it["index"] ?: "" }
        val fillSegs = LinkedHashMap<String, MutableList<List<EdgePoint>>>()
        val strokeSegs = LinkedHashMap<String, MutableList<List<EdgePoint>>>()
        for (edge in node.child("edges")?.children.orEmpty()) {
            val text = edge["edges"] ?: continue
            val left = edge["fillStyle0"]
            val right = edge["fillStyle1"]
            val line = edge["strokeStyle"]
            for (seg in segments(text)) {
                if (left != right) {
                    if (left != null && left in fills) fillSegs.getOrPut(left) { ArrayList() } += seg
                    if (right != null && right in fills) fillSegs.getOrPut(right) { ArrayList() } += seg.asReversed()
                }
                if (line != null && line in strokes) strokeSegs.getOrPut(line) { ArrayList() } += seg
            }
        }
        val out = ArrayList<Item>()
        for ((id, segs) in fillSegs.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
            val path = PathData()
            for (loop in loops(segs)) trace(path, loop, m, true)
            fillLayer(path, fills.getValue(id), m, from, to)?.let(out::add)
        }
        for ((id, segs) in strokeSegs.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
            val path = PathData()
            for (chain in chains(segs)) trace(path, chain, m, chain.first().key == chain.last().key && chain.size > 2)
            strokeLayer(path, strokes.getValue(id), from, to)?.let(out::add)
        }
        return out
    }

    private fun trace(path: PathData, points: List<EdgePoint>, m: Mat, close: Boolean) {
        if (points.size < 2) return
        path.move(m.x(points[0].x, points[0].y), m.y(points[0].x, points[0].y))
        var i = 1
        while (i < points.size) {
            val p = points[i]
            if (p.control && i + 1 < points.size) {
                val q = points[i + 1]
                path.quad(m.x(p.x, p.y), m.y(p.x, p.y), m.x(q.x, q.y), m.y(q.x, q.y))
                i += 2
            } else {
                if (!p.control) path.line(m.x(p.x, p.y), m.y(p.x, p.y))
                i++
            }
        }
        if (close) path.close()
    }

    private fun loops(segs: List<List<EdgePoint>>): List<List<EdgePoint>> {
        val out = ArrayList<List<EdgePoint>>()
        val starts = HashMap<Long, ArrayDeque<Int>>()
        val used = BooleanArray(segs.size)
        segs.forEachIndexed { i, s -> starts.getOrPut(s.first().key) { ArrayDeque() }.addLast(i) }
        for (i in segs.indices) {
            if (used[i]) continue
            used[i] = true
            val loop = ArrayList(segs[i])
            val origin = loop.first().key
            var guard = segs.size
            while (loop.last().key != origin && guard-- > 0) {
                val queue = starts[loop.last().key] ?: break
                var next = -1
                while (queue.isNotEmpty()) { val j = queue.removeFirst(); if (!used[j]) { next = j; break } }
                if (next < 0) break
                used[next] = true
                loop.addAll(segs[next].drop(1))
            }
            out += loop
        }
        return out
    }

    private fun chains(segs: List<List<EdgePoint>>): List<List<EdgePoint>> {
        val out = ArrayList<MutableList<EdgePoint>>()
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && last.last().key == s.first().key) last.addAll(s.drop(1)) else out += ArrayList(s)
        }
        return out
    }

    private fun primitive(e: XmlNode, from: Int, to: Int): List<Item> {
        val m = matrix(e)
        val x = e["x"]?.toDoubleOrNull() ?: 0.0
        val y = e["y"]?.toDoubleOrNull() ?: 0.0
        val w = e["objectWidth"]?.toDoubleOrNull() ?: 0.0
        val h = e["objectHeight"]?.toDoubleOrNull() ?: 0.0
        if (w <= 0 || h <= 0) return emptyList()
        val path = PathData()
        fun p(px: Double, py: Double) = m.x(px, py) to m.y(px, py)
        if (e.name == "DOMOvalObject") {
            val k = 0.5522847498
            val cx = x + w / 2
            val cy = y + h / 2
            val rx = w / 2
            val ry = h / 2
            val pts = listOf(cx + rx to cy, cx to cy + ry, cx - rx to cy, cx to cy - ry, cx + rx to cy)
            p(pts[0].first, pts[0].second).let { (a, b) -> path.move(a, b) }
            for (i in 0 until 4) {
                val (sx, sy) = pts[i]
                val (ex, ey) = pts[i + 1]
                val c1 = if (i % 2 == 0) sx to sy + (ey - sy) * k else sx + (ex - sx) * k to sy
                val c2 = if (i % 2 == 0) ex + (sx - ex) * k to ey else ex to ey + (sy - ey) * k
                val (ax, ay) = p(c1.first, c1.second)
                val (bx, by) = p(c2.first, c2.second)
                val (qx, qy) = p(ex, ey)
                path.cubic(ax, ay, bx, by, qx, qy)
            }
        } else {
            val r = (e["topLeftRadius"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, min(w, h) / 2)
            val corners = listOf(x to y, x + w to y, x + w to y + h, x to y + h)
            if (r <= 0) {
                corners.forEachIndexed { i, (cx, cy) -> val (a, b) = p(cx, cy); if (i == 0) path.move(a, b) else path.line(a, b) }
            } else {
                val dirs = listOf(1.0 to 0.0, 0.0 to 1.0, -1.0 to 0.0, 0.0 to -1.0)
                for (i in 0 until 4) {
                    val (cx, cy) = corners[i]
                    val (ix, iy) = dirs[(i + 3) % 4]
                    val (ox, oy) = dirs[i]
                    val (ax, ay) = p(cx - ix * r, cy - iy * r)
                    if (i == 0) path.move(ax, ay) else path.line(ax, ay)
                    val (kx, ky) = p(cx, cy)
                    val (bx, by) = p(cx + ox * r, cy + oy * r)
                    path.quad(kx, ky, bx, by)
                }
            }
        }
        path.close()
        val out = ArrayList<Item>()
        e.child("fill")?.let { fill -> fillLayer(path, fill, m, from, to)?.let(out::add) }
        e.child("stroke")?.let { stroke -> strokeLayer(path, stroke, from, to)?.let(out::add) }
        return out
    }

    private fun segments(text: String): List<List<EdgePoint>> {
        val out = ArrayList<List<EdgePoint>>()
        var current: ArrayList<EdgePoint>? = null
        var i = 0
        fun number(): Double? {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) return null
            if (text[i] == '#') {
                val start = ++i
                while (i < text.length && (Character.digit(text[i], 16) >= 0 || text[i] == '.')) i++
                val parts = text.substring(start, i).split('.')
                val whole = parts[0].takeLast(6).padStart(6, '0')
                val part = parts.getOrElse(1) { "" }.take(2).padEnd(2, '0')
                val raw = (whole + part).toLongOrNull(16) ?: return null
                return raw.toInt().toDouble() / 256.0 / 20.0
            }
            val start = i
            if (text[i] == '-' || text[i] == '+') i++
            while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
            return text.substring(start, i).toDoubleOrNull()?.div(20.0)
        }
        fun point(control: Boolean): EdgePoint? {
            val x = number() ?: return null
            val y = number() ?: return null
            return EdgePoint(x, y, control)
        }
        while (i < text.length) {
            when (text[i++]) {
                '!' -> {
                    val p = point(false) ?: break
                    val last = current?.lastOrNull()
                    if (last == null || last.key != p.key) {
                        current?.takeIf { it.size > 1 }?.let(out::add)
                        current = arrayListOf(p)
                    }
                }
                '|', '/' -> { val p = point(false) ?: break; current?.add(p) }
                '[', ']' -> {
                    val c = point(true) ?: break
                    val p = point(false) ?: break
                    current?.let { it.add(c); it.add(p) }
                }
                'S' -> while (i < text.length && text[i].isDigit()) i++
            }
        }
        current?.takeIf { it.size > 1 }?.let(out::add)
        return out
    }

    private fun symbolScene(name: String, frame: Int?): SymbolScene? {
        val key = "$name\u0000${frame ?: -1}"
        scenes[key]?.let { return it }
        if (building.size >= MaxNesting) { count(FlaPart.Overflow); return null }
        if (!building.add(name)) return null
        val start = layers
        try {
            val item = symbols[name] ?: return null
            val timeline = item.child("timeline")?.child("DOMTimeline") ?: return null
            val frames = if (frame == null) max(1, frameCount(timeline)) else 1
            val content = timeline(timeline, 0, frame, false)
            val box = Box.union(content.map { it.box })
            val pad = 1.0
            val w = if (box == null) 2.0 else ceil(box.x1 - box.x0 + 2 * pad).coerceIn(1.0, 8192.0)
            val h = if (box == null) 2.0 else ceil(box.y1 - box.y0 + 2 * pad).coerceIn(1.0, 8192.0)
            val ox = if (box == null) 0.0 else pad - box.x0
            val oy = if (box == null) 0.0 else pad - box.y0
            val end = endMs(frames)
            val scene = innerScene(w.roundToInt(), h.roundToInt(), end, finish(content, w, h, ox, oy, end))
            return SymbolScene(scene, frames, box, ox - w / 2, oy - h / 2, layers - start).also { scenes[key] = it }
        } finally {
            building.remove(name)
        }
    }

    private fun instance(run: List<Placed>, base: Int, sample: Int?): List<Item> {
        val head = run.first().inst
        val name = head["libraryItemName"] ?: return emptyList()
        val type = kind(head)
        val mode = loop(head)
        val frozen = when {
            type == "button" -> 0
            type == "graphic" && mode == "single frame" -> first(head)
            else -> null
        }
        val before = layers
        val symbol = symbolScene(name, frozen) ?: return emptyList()
        val copies = symbol.size - (layers - before)
        if (layers + copies >= maxLayers) { count(FlaPart.Overflow); return emptyList() }
        layers += copies + 1
        if (sample == null) count(FlaPart.Symbols)
        for (p in run) {
            val inst = p.inst
            if (inst.child("filters") != null) count(FlaPart.Filters)
            inst["blendMode"]?.takeIf { it != "normal" && it != "layer" && it != "1" }?.let { count(FlaPart.BlendModes) }
            if (inst.child("color")?.child("Color")?.attrs?.keys.orEmpty().any { it !in Tints }) count(FlaPart.ColourEffects)
        }
        val poses = ArrayList<Pose>()
        for ((i, p) in run.withIndex()) {
            val tp = p.inst.child("transformationPoint")?.child("Point")
            val tx = tp?.get("x")?.toDoubleOrNull() ?: 0.0
            val ty = tp?.get("y")?.toDoubleOrNull() ?: 0.0
            val alpha = p.inst.child("color")?.child("Color")?.get("alphaMultiplier")?.toDoubleOrNull() ?: 1.0
            val m = matrix(p.inst)
            val previous = poses.lastOrNull()
            val spin = if (i > 0 && run[i - 1].key.tween == "motion") run[i - 1].key.node else null
            val effect = p.inst.child("color")?.child("Color")
            val bright = effect?.get("brightness")?.toDoubleOrNull()?.coerceIn(-1.0, 1.0) ?: 0.0
            val share = effect?.get("tintMultiplier")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
            val pose = decompose(m, m.x(tx, ty), m.y(tx, ty), tx + symbol.dx, ty + symbol.dy, alpha, previous, spin)
            poses += when {
                bright != 0.0 -> pose.tinted(if (bright > 0) 0xFFFFFF else 0, abs(bright))
                share > 0 -> pose.tinted(color(effect?.get("tintColor") ?: "#000000", 1.0), share)
                else -> previous?.let { pose.tinted(((it.tr * 255).roundToInt() shl 16) or ((it.tg * 255).roundToInt() shl 8) or (it.tb * 255).roundToInt(), 0.0) } ?: pose
            }
        }
        val keys = ArrayList<Triple<Int, Pose, String?>>()
        val start: Int
        val end: Int
        if (sample != null) {
            val k = run.first().key
            val next = run.getOrNull(1)
            var pose = poses.first()
            if (next != null && k.tween == "motion") {
                val x = (sample - k.index).toDouble() / max(1, next.key.index - k.index)
                pose = Pose.lerp(poses[0], poses[1], easeAt(eases(k.node), x))
            }
            keys += Triple(0, pose, null)
            start = 0
            end = endMs(1)
        } else {
            start = ms(base + run.first().key.index)
            end = endMs(base + run.last().key.end)
            for ((i, p) in run.withIndex()) {
                val at = ms(base + p.key.index)
                if (i == 0) { keys += Triple(at, poses[i], null); continue }
                val before = run[i - 1].key
                val from = ms(base + before.index)
                if (before.tween == "motion") {
                    count(FlaPart.Tweens)
                    val segs = eases(before.node)
                    for ((j, seg) in segs.withIndex()) {
                        val time = if (j == segs.lastIndex) at else (from + (at - from) * seg[6]).roundToInt().coerceIn(from + 1, at - 1)
                        val pose = if (j == segs.lastIndex) poses[i] else Pose.lerp(poses[i - 1], poses[i], seg[7])
                        if (time > keys.last().first) keys += Triple(time, pose, bezier(seg))
                    }
                } else {
                    if (at - 1 > keys.last().first) keys += Triple(at - 1, poses[i - 1], null)
                    keys += Triple(at, poses[i], null)
                }
            }
        }
        val frames = symbol.frames
        val point = when {
            frozen != null -> 0
            type == "graphic" -> {
                val k = run.first()
                val f = first(k.inst) + (if (sample != null) sample - k.key.index else 0)
                if (mode == "play once") min(f, frames - 1) else Math.floorMod(f, frames)
            }
            else -> 0
        }
        val retime = when {
            frozen != null || frames <= 1 -> "freeze"
            type == "graphic" && mode == "play once" -> "freeze"
            else -> "loop"
        }
        val label = head["name"]?.takeIf { it.isNotBlank() } ?: leafName(name)
        val hidden = head["isVisible"] == "false"
        val box = symbol.box?.let { b ->
            val corners = listOf(b.x0 to b.y0, b.x1 to b.y0, b.x0 to b.y1, b.x1 to b.y1).map { (x, y) -> x + symbol.dx to y + symbol.dy }
            val samples = keys.flatMapIndexed { i, (_, pose, _) ->
                val after = keys.getOrNull(i + 1)?.second
                listOf(pose) + if (after == null) emptyList() else listOf(0.25, 0.5, 0.75).map { Pose.lerp(pose, after, it) }
            }
            Box.of(samples.asSequence().flatMap { pose -> corners.asSequence().map { (x, y) -> pose.map(x, y) } })
        }
        return listOf(Leaf(box) { dx, dy ->
            val attrs = linkedMapOf("id" to "${Layer.newId()}", "label" to label, "startTime" to "$start", "endTime" to "$end")
            if (point > 0) attrs["inTime"] = "${ms(point)}"
            if (hidden) attrs["hidden"] = "true"
            val inner = if (retime == "loop") symbol.scene.with("retime", "loop").with("totalTime", "${endMs(frames + 1)}").with("retimeOut", "${ms(frames)}")
                else symbol.scene.with("retime", retime)
            val moved = keys.map { (t, pose, e) -> Triple(t, pose.moved(dx, dy), e) }
            XmlNode("embedScene", attrs, listOfNotNull(poseTransform(moved, start, end), tint(moved, start, end), inner))
        })
    }

    private fun decompose(m: Mat, x: Double, y: Double, px: Double, py: Double, alpha: Double, previous: Pose?, spin: XmlNode? = null): Pose {
        val sx = hypot(m.a, m.b)
        val sy = hypot(m.c, m.d)
        val ky = atan2(m.b, m.a)
        val kx = atan2(-m.c, m.d)
        var rot = ky * 180 / PI
        if (previous != null) {
            var delta = rot - previous.rot
            delta -= 360 * floor(delta / 360)
            val times = spin?.get("motionTweenRotateTimes")?.toIntOrNull()?.coerceIn(0, 1000) ?: 0
            delta = when (spin?.get("motionTweenRotate")) {
                "clockwise" -> delta + 360 * times
                "counter-clockwise" -> (if (delta > 0) delta - 360 else delta) - 360 * times
                else -> if (delta > 180) delta - 360 else delta
            }
            rot = previous.rot + delta
        }
        val shear = ky - kx
        val c = cos(shear)
        val skew = if (abs(c) < 1e-6) 0.0 else atan(tan(shear))
        return Pose(x, y, px, py, rot, skew, sx, sy * c.coerceIn(-1.0, 1.0).let { if (abs(it) < 1e-6) 1e-6 else it }, alpha.coerceIn(0.0, 1.0))
    }

    private fun poseTransform(keys: List<Triple<Int, Pose, String?>>, start: Int, end: Int): XmlNode {
        val location = keys.map { (t, p, e) -> Triple(t, doubleArrayOf(p.x, p.y, 0.0), e) }
        fun pick(f: (Pose) -> DoubleArray) = keys.map { (t, p, e) -> Triple(t, f(p), e) }
        val children = ArrayList<XmlNode>()
        children += values("location", location, start, end)
        val pivot = pick { doubleArrayOf(it.px, it.py, 0.0) }
        if (pivot.any { (_, v, _) -> abs(v[0]) > 1e-9 || abs(v[1]) > 1e-9 }) children += values("pivot", pivot, start, end)
        val scale = pick { doubleArrayOf(it.sx, it.sy) }
        if (scale.any { (_, v, _) -> abs(v[0] - 1) > 1e-9 || abs(v[1] - 1) > 1e-9 }) children += values("scale", scale, start, end)
        val rotation = pick { doubleArrayOf(it.rot) }
        if (rotation.any { (_, v, _) -> abs(v[0]) > 1e-9 }) children += values("rotation", rotation, start, end)
        val skew = pick { doubleArrayOf(it.skew, 0.0) }
        if (skew.any { (_, v, _) -> abs(v[0]) > 1e-9 }) children += values("skew", skew, start, end)
        val opacity = pick { doubleArrayOf(it.alpha) }
        if (opacity.any { (_, v, _) -> abs(v[0] - 1) > 1e-9 }) children += values("opacity", opacity, start, end)
        return XmlNode("transform", children = children)
    }

    private fun tint(keys: List<Triple<Int, Pose, String?>>, start: Int, end: Int): XmlNode? {
        if (keys.none { it.second.tint > 0 }) return null
        val span = max(1, end - start).toDouble()
        fun prop(name: String, type: String, text: (Pose) -> String): XmlNode {
            val first = text(keys.first().second)
            val attrs = linkedMapOf("name" to name, "type" to type)
            if (keys.all { text(it.second) == first }) return XmlNode("property", attrs + ("value" to first))
            return XmlNode("property", attrs, keys.map { (t, p, e) ->
                XmlNode("kf", linkedMapOf("t" to f6((t - start) / span), "v" to text(p)).also { a -> e?.let { a["e"] = it } })
            })
        }
        val shade = { p: Pose -> formatColor((0xFF shl 24) or ((p.tr * 255).roundToInt().coerceIn(0, 255) shl 16) or ((p.tg * 255).roundToInt().coerceIn(0, 255) shl 8) or (p.tb * 255).roundToInt().coerceIn(0, 255)) }
        return XmlNode("effect", linkedMapOf("id" to "absent.tint"), listOf(
            prop("amount", "float") { f6(it.tint) }, prop("shadowColor", "color", shade), prop("highlightColor", "color", shade),
        ))
    }

    private fun transform(x: Double, y: Double, start: Int, end: Int): XmlNode =
        XmlNode("transform", children = listOf(values("location", listOf(Triple(start, doubleArrayOf(x, y, 0.0), null)), start, end)))

    private fun values(name: String, keys: List<Triple<Int, DoubleArray, String?>>, start: Int, end: Int): XmlNode {
        fun text(v: DoubleArray) = v.joinToString(",") { f6(it) }
        val first = text(keys.first().second)
        if (keys.all { text(it.second) == first }) return XmlNode(name, mapOf("value" to first))
        val span = max(1, end - start).toDouble()
        return XmlNode(name, children = keys.map { (t, v, e) ->
            val attrs = linkedMapOf("t" to f6((t - start) / span), "v" to text(v))
            e?.let { attrs["e"] = it }
            XmlNode("kf", attrs)
        })
    }

    private fun eases(frame: XmlNode): List<DoubleArray> {
        val tweens = frame.child("tweens")?.children.orEmpty()
        val custom = tweens.firstOrNull { it.name == "CustomEase" && it["target"] == "all" } ?: tweens.firstOrNull { it.name == "CustomEase" }
        if (custom != null) {
            if (tweens.count { it.name == "CustomEase" } > 1 && custom["target"] != "all") count(FlaPart.Eases)
            val pts = custom.children.filter { it.name == "Point" }.map { (it["x"]?.toDoubleOrNull() ?: 0.0) to (it["y"]?.toDoubleOrNull() ?: 0.0) }
            if (pts.size >= 4 && (pts.size - 1) % 3 == 0) {
                val out = ArrayList<DoubleArray>()
                for (s in 0 until (pts.size - 1) / 3) {
                    val p = pts.subList(s * 3, s * 3 + 4)
                    out += doubleArrayOf(p[0].first, p[0].second, p[1].first, p[1].second, p[2].first, p[2].second, p[3].first, p[3].second)
                }
                if (out.all { it[6] > it[0] }) return out
            }
            count(FlaPart.Eases)
            return listOf(Linear)
        }
        val ease = tweens.firstOrNull { it.name == "Ease" && it["target"] == "all" } ?: tweens.firstOrNull { it.name == "Ease" }
        val method = ease?.get("method") ?: frame["easeMethodName"]
        if (method != null && method != "classicEase") {
            Named[method]?.let { (a, b, c, d) -> return listOf(doubleArrayOf(0.0, 0.0, a, b, c, d, 1.0, 1.0)) }
            if (method != "none" && method != "noEase") count(FlaPart.Eases)
            return listOf(Linear)
        }
        val intensity = (ease?.get("intensity") ?: frame["acceleration"])?.toDoubleOrNull()?.coerceIn(-100.0, 100.0) ?: 0.0
        if (intensity == 0.0) return listOf(Linear)
        val b = 1 + intensity / 100
        return listOf(doubleArrayOf(0.0, 0.0, 1.0 / 3, b / 3, 2.0 / 3, (1 + b) / 3, 1.0, 1.0))
    }

    private fun bezier(seg: DoubleArray): String? {
        val w = seg[6] - seg[0]
        val h = seg[7] - seg[1]
        if (w <= 0 || abs(h) < 1e-9) return null
        val x1 = (seg[2] - seg[0]) / w
        val y1 = (seg[3] - seg[1]) / h
        val x2 = (seg[4] - seg[0]) / w
        val y2 = (seg[5] - seg[1]) / h
        if (abs(x1 - y1) < 1e-6 && abs(x2 - y2) < 1e-6) return null
        return "cubicBezier ${num4(x1.coerceIn(0.0, 1.0))} ${num4(y1)} ${num4(x2.coerceIn(0.0, 1.0))} ${num4(y2)}"
    }

    private fun num4(v: Double) = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun easeAt(segs: List<DoubleArray>, x: Double): Double {
        val seg = segs.firstOrNull { x <= it[6] } ?: segs.last()
        var lo = 0.0
        var hi = 1.0
        fun cubic(a: Double, b: Double, c: Double, d: Double, t: Double) = (1 - t) * (1 - t) * (1 - t) * a + 3 * (1 - t) * (1 - t) * t * b + 3 * (1 - t) * t * t * c + t * t * t * d
        repeat(40) {
            val mid = (lo + hi) / 2
            if (cubic(seg[0], seg[2], seg[4], seg[6], mid) < x) lo = mid else hi = mid
        }
        return cubic(seg[1], seg[3], seg[5], seg[7], (lo + hi) / 2)
    }

    private fun leafName(name: String): String = name.substringAfterLast('/')

    companion object {
        private const val GradientHalf = 819.2
        private val Tints = setOf("alphaMultiplier", "tintColor", "tintMultiplier", "brightness")
        private val Linear = doubleArrayOf(0.0, 0.0, 1.0 / 3, 1.0 / 3, 2.0 / 3, 2.0 / 3, 1.0, 1.0)
        private val Named: Map<String, List<Double>> = mapOf(
            "quadIn" to listOf(0.11, 0.0, 0.5, 0.0), "quadOut" to listOf(0.5, 1.0, 0.89, 1.0), "quadInOut" to listOf(0.45, 0.0, 0.55, 1.0),
            "cubicIn" to listOf(0.32, 0.0, 0.67, 0.0), "cubicOut" to listOf(0.33, 1.0, 0.68, 1.0), "cubicInOut" to listOf(0.65, 0.0, 0.35, 1.0),
            "quartIn" to listOf(0.5, 0.0, 0.75, 0.0), "quartOut" to listOf(0.25, 1.0, 0.5, 1.0), "quartInOut" to listOf(0.76, 0.0, 0.24, 1.0),
            "quintIn" to listOf(0.64, 0.0, 0.78, 0.0), "quintOut" to listOf(0.22, 1.0, 0.36, 1.0), "quintInOut" to listOf(0.83, 0.0, 0.17, 1.0),
            "sineIn" to listOf(0.12, 0.0, 0.39, 0.0), "sineOut" to listOf(0.61, 1.0, 0.88, 1.0), "sineInOut" to listOf(0.37, 0.0, 0.63, 1.0),
            "circIn" to listOf(0.55, 0.0, 1.0, 0.45), "circOut" to listOf(0.0, 0.55, 0.45, 1.0), "circInOut" to listOf(0.85, 0.0, 0.15, 1.0),
            "backIn" to listOf(0.36, 0.0, 0.66, -0.56), "backOut" to listOf(0.34, 1.56, 0.64, 1.0), "backInOut" to listOf(0.68, -0.6, 0.32, 1.6),
        )
    }
}
