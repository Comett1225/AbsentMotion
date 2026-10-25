package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import app.absentmotion.model.XmlNode
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Easing
import app.absentmotion.model.floats
import app.absentmotion.model.formatFloats
import app.absentmotion.model.isMask
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withBlend
import app.absentmotion.ai.trimmedClip
import app.absentmotion.ai.withClip
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.sin

object MultiSelect {
    var active by mutableStateOf(false)
    private var chosen by mutableStateOf(setOf<Long>())
    var revision = 0L
        private set
    var ids: Set<Long>
        get() = chosen
        set(value) { if (value != chosen) { chosen = value; revision++ } }

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun clear() {
        active = false
        ids = emptySet()
    }
}

private fun EditorState.numbered(base: String): String {
    val used = scene.layers.mapNotNull { it.label.removePrefix("$base ").toIntOrNull().takeIf { _ -> it.label.startsWith("$base ") } }
    return "$base ${(used.maxOrNull() ?: 0) + 1}"
}

private fun EditorState.newLayerAttrs(label: String): LinkedHashMap<String, String> = linkedMapOf(
    "id" to "${Layer.newId()}", "label" to label,
    "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, settings.defaultLayerDuration * 1000)}",
)

private fun EditorState.addAndSelect(node: XmlNode) {
    val layer = Layer(node)
    edit { it.add(layer) }
    selectedId = layer.id
    panel = EditorPanel.Tools
    addOpen = false
}

fun EditorState.addNull() = addAndSelect(
    XmlNode("nullobj", newLayerAttrs(numbered(AppText.get(R.string.model_layer_null))), listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, 0f))))))),
    ),
)

fun EditorState.addModel(uri: String, name: String) {
    fun value(name: String, v: Float) = XmlNode("property", mapOf("name" to name, "type" to "float", "value" to formatFloats(floatArrayOf(v))))
    addAndSelect(
        XmlNode("model", newLayerAttrs(name.substringBeforeLast('.').ifBlank { numbered(AppText.get(R.string.model_layer_model)) }) + ("src" to uri), listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, 0f)))))),
            value("size", min(scene.width, scene.height) * 0.3f), value("lightAngle", 135f), value("ambient", 0.35f), value("intensity", 1f), value("spin", 0f),
        )),
    )
}

internal val Scene.cameraZoom: Float get() = max(width, height) / tan(Math.toRadians(30.0)).toFloat() / 2f

fun EditorState.addCamera() {
    cameraTip = !cameraMode && scene.layers.none { it.kind == "camera" }
    val zoom = scene.cameraZoom
    val attrs = newLayerAttrs(numbered(AppText.get(R.string.model_layer_camera)))
    if (scene.totalTime > 0) attrs["endTime"] = "${scene.totalTime}"
    attrs["type"] = "perspective"
    fun value(name: String, v: Float) = XmlNode(name, mapOf("value" to formatFloats(floatArrayOf(v))))
    addAndSelect(
        XmlNode("camera", attrs, listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, -zoom)))))),
            value("fov", 60f),
            XmlNode("focus-blur", mapOf("enabled" to "false"), listOf(value("strength", 0.5f), value("depth-of-field", zoom / 10), value("distance", zoom))),
            XmlNode("fog", mapOf("enabled" to "false"), listOf(XmlNode("color", mapOf("value" to "#ffffffff")), value("near-z", zoom), value("far-z", zoom * 2))),
        )),
    )
}

private fun Scene.groupNode(attrs: Map<String, String>, children: List<XmlNode>, totalTime: Int): XmlNode = XmlNode(
    "embedScene", attrs, listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(width / 2f, height / 2f, 0f)))))),
        XmlNode(
            "scene",
            linkedMapOf(
                "title" to "", "width" to "$width", "height" to "$height", "exportWidth" to "$width", "exportHeight" to "$height",
                "bgcolor" to "#00000000", "totalTime" to "$totalTime", "fps" to "$fps", "retime" to "off",
            ),
            children,
        ),
    ),
)

fun EditorState.addEmptyGroup() {
    val attrs = newLayerAttrs(numbered(AppText.get(R.string.model_layer_group)))
    addAndSelect(scene.groupNode(attrs, emptyList(), attrs.getValue("endTime").toInt() - attrs.getValue("startTime").toInt()))
}

fun EditorState.groupLayers(ids: Set<Long>, blend: String? = null) {
    val id = Layer.newId()
    val label = numbered(AppText.get(R.string.model_layer_group))
    edit { it.grouped(ids, blend, id, label) }
    if (scene.layer(id) == null) return
    MultiSelect.clear()
    selectedId = id
    panel = EditorPanel.Tools
}

internal fun Scene.grouped(ids: Set<Long>, blend: String?, id: Long, label: String): Scene {
    val list = layers
    val inside = list.filter { it.id in ids && it.kind != "camera" }.map { it.id }.toSet()
    if (inside.isEmpty()) return this
    val stranded = list.indices.filter { i -> list[i].id in inside && list.isMask(i) && list[(i - 1 downTo 0).first { !list.isMask(it) }].id !in inside }
        .map { list[it].id }.toSet()
    val flat = layers.filter { it.id in inside && it.node["parent"]?.toLongOrNull().let { p -> p != null && p !in inside } }
        .fold(unparentChildrenOf(inside)) { acc, layer -> acc.update(layer.id) { acc.rebased(it, null) ?: it } }
    val members = flat.layers.filter { it.id in inside }
    val start = members.minOf { it.startTime }
    val end = members.maxOf { it.endTime }
    val top = members.last().id
    val nested = members.map { layer ->
        val shifted = layer.movedTo(layer.startTime - start).let { if (it.id in stranded) it.copy(node = it.node.with("clippingMask", null)) else it }
        (if (layer.id == top && blend != null && members.size > 1) shifted.withBlend(blend) else shifted).node
    }
    val attrs = linkedMapOf("id" to "$id", "label" to label, "startTime" to "$start", "endTime" to "$end")
    if (top in stranded) attrs["clippingMask"] = "true"
    val group = groupNode(attrs, nested, end - start)
    return Scene(flat.node.copy(children = flat.node.children.mapNotNull { node ->
        val layerId = if (node.name in Scene.LAYER_TAGS) Layer(node).id else null
        when (layerId) {
            top -> group
            in inside -> null
            else -> node
        }
    }))
}

fun EditorState.toggleClippingMask() {
    val layer = selected ?: return
    val on = layer.node["clippingMask"] == "true"
    editSelected { it.copy(node = it.node.with("clippingMask", if (on) null else "true")) }
}

val EditorState.canClip: Boolean get() = selectedId?.let { id -> scene.layers.indexOfFirst { it.id == id } > 0 } == true

internal data class Place(val x: Float, val y: Float, val px: Float, val py: Float, val sx: Float, val sy: Float, val rot: Float, val z: Float = 0f) {
    fun part(name: String): FloatArray = when (name) {
        "location" -> floatArrayOf(x, y, z)
        "scale" -> floatArrayOf(sx, sy)
        else -> floatArrayOf(rot)
    }

    fun withPart(name: String, v: FloatArray): Place = when (name) {
        "location" -> copy(x = v[0], y = v[1], z = v[2])
        "scale" -> copy(sx = v[0], sy = v[1])
        else -> copy(rot = v[0])
    }
}

private val MOVED = listOf("location", "scale", "rotation")
private val PLACED = MOVED + "pivot"

private fun ownPlace(layer: Layer, timeMs: Int, fps: Int): Place {
    val at = layer.at(timeMs, fps)
    val loc = at.location
    val piv = at.pivot
    val scale = at.scale
    return Place(loc[0], loc[1], piv[0], piv[1], scale[0], scale[1], at.transform("rotation", 0f)[0], loc[2])
}

private fun compose(p: Place, q: Place?): Place {
    if (q == null) return p
    val vx = (p.x + p.px - q.px) * q.sx
    val vy = (p.y + p.py - q.py) * q.sy
    val a = Math.toRadians(q.rot.toDouble())
    val rx = (vx * cos(a) - vy * sin(a)).toFloat()
    val ry = (vx * sin(a) + vy * cos(a)).toFloat()
    return Place(q.x + rx - p.px + q.px, q.y + ry - p.py + q.py, p.px, p.py, p.sx * q.sx, p.sy * q.sy, p.rot + q.rot, p.z + q.z)
}

internal fun decompose(w: Place, q: Place): Place? {
    if (q.sx == 0f || q.sy == 0f) return null
    val a = Math.toRadians(-q.rot.toDouble())
    val dx = w.x - q.x + w.px - q.px
    val dy = w.y - q.y + w.py - q.py
    val vx = (dx * cos(a) - dy * sin(a)).toFloat() / q.sx
    val vy = (dx * sin(a) + dy * cos(a)).toFloat() / q.sy
    return Place(vx - w.px + q.px, vy - w.py + q.py, w.px, w.py, w.sx / q.sx, w.sy / q.sy, w.rot - q.rot, w.z - q.z)
}

private fun Scene.parentOf(layer: Layer): Layer? =
    layer.node["parent"]?.toLongOrNull()?.takeIf { it != layer.id }?.let { id -> layers.firstOrNull { it.id == id } }

internal fun Scene.place(layer: Layer, timeMs: Int, depth: Int = 0): Place =
    compose(ownPlace(layer, timeMs, fps), parentOf(layer)?.takeIf { depth <= 16 }?.let { place(it, timeMs, depth + 1) })

internal fun Scene.chain(layer: Layer?): List<Layer> = generateSequence(layer) { parentOf(it) }.take(17).toList()

internal fun Layer.moves(): Boolean = PLACED.any { node.child("transform")?.child(it)?.isKeyframed == true }

private fun XmlNode.withMoved(name: String, element: XmlNode): XmlNode =
    withChild("transform", (child("transform") ?: XmlNode("transform")).withChild(name, element))

internal fun Layer.rebaked(changing: Boolean, outerTimes: List<Int>, fps: Int, convert: (Place, Int) -> Place?): Layer? {
    val transform = node.child("transform")
    fun element(name: String) = transform?.child(name) ?: XmlNode(name)
    fun plain(name: String, v: FloatArray) = element(name).copy(children = element(name).children.filterNot { it.name == "kf" }).with("value", formatFloats(v))
    var out = node
    if (!changing && transform?.child("pivot")?.isKeyframed != true) {
        val own = ownPlace(this, startTime, fps)
        for (name in MOVED) {
            val keys = element(name).keyframes
            out = out.withMoved(name, if (keys.isEmpty()) plain(name, (convert(own, startTime) ?: return null).part(name)) else {
                val defaults = own.part(name).let { d -> if (name == "scale") floatArrayOf(1f, 1f) else FloatArray(d.size) }
                element(name).withKeyframes(keys.map { k ->
                    k.copy(v = formatFloats((convert(own.withPart(name, floats(k.v, defaults)), startTime) ?: return null).part(name)))
                })
            })
        }
        return copy(node = out)
    }
    val length = (endTime - startTime).coerceAtLeast(1)
    val marks = (listOf(startTime, endTime) + keyframeTimes() + outerTimes).filter { it in startTime..endTime }.toSortedSet().toList()
    val frames = generateSequence(((startTime.toLong() * fps) / 1000).toInt()) { it + 1 }
        .map { ((2L * it + 1) * 500 / fps).toInt() }.dropWhile { it < startTime }.takeWhile { it <= endTime }.toList()
    val samples = HashMap<Int, Place>()
    for (ms in marks) samples[ms] = convert(ownPlace(this, ms, fps), ms) ?: return null
    val keys = MOVED.associateWith { name -> element(name).keyframes.associateBy { timeOf(it.t) } }
    fun curve(name: String, a: Int, b: Int): Boolean? {
        val own = keys.getValue(name)
        val before = own.keys.filter { it <= a }.maxOrNull() ?: return null
        val after = own.keys.filter { it >= b }.minOrNull() ?: return null
        if (Easing.parse(own.getValue(after).e).isLinear) return null
        return before == a && after == b
    }
    val probes = listOf(Place(0f, 0f, 0f, 0f, 1f, 1f, 0f), Place(97f, -53f, 11f, 7f, 1.7f, 0.6f, 33f, 5f))
    val pivotMoves = transform?.child("pivot")?.isKeyframed == true
    val calm = marks.zipWithNext().associateWith { (a, b) ->
        !pivotMoves && probes.all { p -> convert(p, a)?.let { ref -> (frames.filter { it > a && it < b } + b).all { convert(p, it) == ref } } == true }
    }
    for ((segment, quiet) in calm) {
        val (a, b) = segment
        if (quiet && MOVED.none { curve(it, a, b) == false }) continue
        for (ms in frames) if (ms > a && ms < b && ms !in samples) samples[ms] = convert(ownPlace(this, ms, fps), ms) ?: return null
    }
    for (name in MOVED) {
        fun v(ms: Int) = samples.getValue(ms).part(name)
        val own = keys.getValue(name)
        val kept = marks.toMutableList()
        val eased = HashSet<Int>()
        for ((segment, quiet) in calm) {
            val (a, b) = segment
            val clean = curve(name, a, b)
            if (quiet && clean != false) {
                if (clean == true) eased += b
                continue
            }
            val inside = frames.filter { it > a && it < b }
            val bent = inside.any { ms ->
                val u = (ms - a).toFloat() / (b - a)
                v(ms).indices.any { i -> abs(v(ms)[i] - (v(a)[i] + (v(b)[i] - v(a)[i]) * u)) > 1e-3f * max(1f, abs(v(ms)[i])) }
            }
            if (bent) kept += inside
        }
        val first = v(startTime)
        out = out.withMoved(name, if (kept.all { v(it).contentEquals(first) }) plain(name, first)
            else element(name).withKeyframes(kept.sorted().map { ms ->
                val source = own[ms]
                Keyframe((ms - startTime).toFloat() / length, formatFloats(v(ms)), source?.e?.takeIf { ms in eased }, source?.gen, source?.extra.orEmpty())
            }))
    }
    return copy(node = out)
}

internal fun Scene.rebased(layer: Layer, parentId: Long?): Layer? {
    val old = parentOf(layer)
    val new = parentId?.let { id -> layers.firstOrNull { it.id == id } }
    val outer = chain(old) + chain(new)
    val moved = Layer(layer.node).rebaked(outer.any { it.moves() }, outer.flatMap { it.keyframeTimes() }, fps) { own, ms ->
        val world = compose(own, old?.let { place(it, ms) })
        if (new == null) world else decompose(world, place(new, ms))
    } ?: return null
    return moved.copy(node = moved.node.with("parent", parentId?.toString()))
}

internal fun Scene.withWorldLocation(layer: Layer, timeMs: Int, change: (x: Float, y: Float) -> Pair<Float, Float>): Layer {
    val world = place(layer, timeMs)
    val (x, y) = change(world.x, world.y)
    val local = chain(layer).getOrNull(1)?.let { decompose(world.copy(x = x, y = y), place(it, timeMs)) }
    return layer.withTransform("location", local?.x ?: x, local?.y ?: y, layer.location[2])
}

internal fun Scene.unparentChildrenOf(ids: Set<Long>): Scene =
    layers.filter { it.id !in ids && it.node["parent"]?.toLongOrNull() in ids }
        .fold(this) { acc, child -> acc.update(child.id) { acc.rebased(it, null) ?: it } }

fun EditorState.setParent(parentId: Long?) {
    val layer = selected ?: return
    if (parentId == layer.id) return
    val old = layer.node["parent"]?.toLongOrNull()
    if (old == parentId) return
    var walk = parentId
    var guard = 0
    while (walk != null && guard++ < 32) {
        if (walk == layer.id) return
        walk = scene.layer(walk)?.node?.get("parent")?.toLongOrNull()
    }
    val next = scene.rebased(layer, parentId) ?: return
    editSelected { next }
}

suspend fun EditorState.alignSelection(edge: Int) {
    val document = scene
    val at = sampleMs
    val layers = MultiSelect.ids.mapNotNull { scene.layer(it) }
    val bounds = layers.mapNotNull { l -> engine.layerBounds(l.id, at)?.let { l to it } }
    if (bounds.isEmpty() || scene.node !== document.node) return
    val horizontal = edge < 3
    val lo = if (bounds.size == 1) 0f else bounds.minOf { (_, b) -> if (horizontal) b[0] else b[1] }
    val hi = if (bounds.size == 1) (if (horizontal) scene.width else scene.height).toFloat() else bounds.maxOf { (_, b) -> if (horizontal) b[2] else b[3] }
    val moves = bounds.map { (l, b) ->
        val from = if (horizontal) b[0] else b[1]
        val to = if (horizontal) b[2] else b[3]
        l to when (edge % 3) { 0 -> lo - from; 1 -> (lo + hi) / 2 - (from + to) / 2; else -> hi - to }
    }
    edit { it.shiftedInWorld(moves, horizontal, at) }
}

internal fun Scene.shiftedInWorld(moves: List<Pair<Layer, Float>>, horizontal: Boolean, timeMs: Int): Scene =
    moves.sortedBy { (l, _) -> chain(l).size }.fold(this) { acc, (l, d) ->
        val w = place(layer(l.id) ?: return@fold acc, timeMs)
        acc.update(l.id, timeMs) { layer -> acc.withWorldLocation(layer, timeMs) { _, _ -> if (horizontal) w.x + d to w.y else w.x to w.y + d } }
    }

suspend fun EditorState.distributeSelection(horizontal: Boolean) {
    val document = scene
    val at = sampleMs
    val items = MultiSelect.ids.mapNotNull { scene.layer(it) }.mapNotNull { l -> engine.layerBounds(l.id, at)?.let { l to it } }
        .sortedBy { (_, b) -> if (horizontal) b[0] else b[1] }
    if (items.size < 3 || scene.node !== document.node) return
    fun from(b: FloatArray) = if (horizontal) b[0] else b[1]
    fun to(b: FloatArray) = if (horizontal) b[2] else b[3]
    val gap = (to(items.last().second) - from(items.first().second) - items.sumOf { (to(it.second) - from(it.second)).toDouble() }.toFloat()) / (items.size - 1)
    var cursor = to(items.first().second) + gap
    val moves = items.drop(1).dropLast(1).map { (l, b) ->
        val d = cursor - from(b)
        cursor += to(b) - from(b) + gap
        l to d
    }
    edit { it.shiftedInWorld(moves, horizontal, at) }
}

enum class StylePart { ColorFill, BorderShadow, Blending }

fun EditorState.pasteStyle(parts: Set<StylePart>) {
    val from = copiedLayerForStyle ?: return
    val id = selectedId ?: return
    edit { it.withPastedStyle(id, sampleMs, from, copiedMediaForStyle, parts) }
}

internal fun Scene.withPastedStyle(id: Long, timeMs: Int, from: Layer, media: List<MediaDeclaration>, parts: Set<StylePart>): Scene {
    val styled = update(id, timeMs) { l ->
        var node = l.node
        fun take(names: List<String>, attrs: List<String>) {
            node = node.copy(children = node.children.filterNot { it.name in names } + from.node.children.filter { it.name in names })
            for (a in attrs) node = node.with(a, from.node[a])
        }
        if (StylePart.ColorFill in parts) {
            take(listOf("fillColor", "gradient", "speedMap", "gain"), listOf("fillType", "fillImage", "fillVideo", "mediaFillMode", "inTime", "outTime", "speed", "loop", "loopIn"))
        }
        if (StylePart.BorderShadow in parts) take(listOf("border", "shadow", "path-stroke"), emptyList())
        if (StylePart.Blending in parts) {
            take(emptyList(), listOf("blending"))
            val opacity = from.node.child("transform")?.child("opacity")
            node = node.withChild("transform", (node.child("transform") ?: XmlNode("transform")).withChild("opacity", opacity))
        }
        l.copy(node = node)
    }
    val used = styled.layer(id)?.node?.mediaUris().orEmpty()
    return media.filter { it.uri in used }.fold(styled) { scene, record -> scene.withMedia(record) }
}

fun EditorState.alignClipTimes(mode: Int) {
    val layers = chosenLayers.sortedBy { it.startTime }
    if (layers.size < 2 || mode == 1 && layers.size < 3) return
    val first = layers.first().startTime
    val last = layers.maxOf { it.endTime }
    val lastStart = layers.last().startTime
    val starts = layers.mapIndexed { index, layer ->
        layer.id to when (mode) {
            0 -> first
            2 -> last - (layer.endTime - layer.startTime)
            else -> first + (lastStart - first).toLong().times(index).div(layers.lastIndex).toInt()
        }
    }.toMap()
    edit { s -> layers.fold(s) { acc, layer -> acc.update(layer.id) { acc.movedClip(it, starts.getValue(layer.id), keepEnd = mode == 2) } } }
}

private class BlockMove(val scene: Scene, val end: Boolean, val frame: Int, val selection: Long)

private var lastBlockMove: BlockMove? = null

fun EditorState.moveClipEdges(end: Boolean, extend: Boolean) {
    val ids = chosenLayers.map { it.id }
    val again = lastBlockMove?.let { it.scene.node === scene.node && it.end == end && it.frame == frame && it.selection == MultiSelect.revision } == true
    lastBlockMove = null
    if (extend || ids.size < 2 || again) {
        edit { it.withClipEdgesAt(ids, frame, end, extend) }
        return
    }
    val block = scene.withBlockAt(ids, frame, end)
    val lined = scene.withClipEdgesAt(ids, frame, end, false)
    edit { block }
    if (block == lined) return
    lastBlockMove = BlockMove(scene, end, frame, MultiSelect.revision)
    hint(R.string.history_line_up_hint)
}

internal fun Scene.withBlockAt(ids: List<Long>, frame: Int, end: Boolean): Scene {
    val chosen = layers.filter { it.id in ids }
    if (chosen.isEmpty()) return this
    val shift = frameStart(frame) - if (end) chosen.maxOf { it.endTime } else chosen.minOf { it.startTime }
    return chosen.fold(this) { acc, layer -> acc.update(layer.id) { movedClip(layer, layer.startTime + shift, keepEnd = end) } }
}

internal fun Scene.withClipEdgesAt(ids: List<Long>, frame: Int, end: Boolean, extend: Boolean): Scene {
    val target = if (end && extend) frameEnd(frame) else frameStart(frame)
    return ids.fold(this) { acc, id ->
        val layer = acc.layer(id) ?: return@fold acc
        when {
            !extend -> acc.update(id) { movedClip(it, if (end) target - (it.endTime - it.startTime) else target, keepEnd = end) }
            end && target > layer.endTime && showsAnyFrame(layer.startTime, target) -> acc.withClip(id, acc.trimmedClip(layer, layer.startTime, target))
            !end && target < layer.startTime && showsAnyFrame(target, layer.endTime) -> acc.withClip(id, acc.trimmedClip(layer, target, layer.endTime))
            else -> acc
        }
    }
}
