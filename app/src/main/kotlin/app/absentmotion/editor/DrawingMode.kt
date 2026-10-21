package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.platform.Color
import app.absentmotion.platform.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.model.Contour
import app.absentmotion.model.DrawTool
import app.absentmotion.model.Handle
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.contours
import app.absentmotion.model.formatColor
import app.absentmotion.model.formatFloats
import app.absentmotion.model.parseSvgPath
import app.absentmotion.model.strokes
import app.absentmotion.model.withContours
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

sealed class DrawingMode(val layerId: Long, val created: Boolean) {
    abstract val handlesCode: Int
    internal var added: Scene? = null
}

private object DrawingDefaults {
    var tool = DrawTool.Pen
    var width = 10f
    var color = 0xFFE53935.toInt()
}

class FreehandMode(layerId: Long, created: Boolean) : DrawingMode(layerId, created) {
    var tool by mutableStateOf(DrawingDefaults.tool)
        private set
    var width by mutableFloatStateOf(DrawingDefaults.width)
        private set
    var color by mutableIntStateOf(DrawingDefaults.color)
        private set

    fun pick(tool: DrawTool) { this.tool = tool; DrawingDefaults.tool = tool }
    fun resize(width: Float) { this.width = width; DrawingDefaults.width = width }
    fun recolour(color: Int) { this.color = color; DrawingDefaults.color = color }

    override val handlesCode: Int get() = -11
}

class PointMode(layerId: Long, created: Boolean, val vector: Boolean) : DrawingMode(layerId, created) {
    var contour by mutableIntStateOf(0)
    var point by mutableIntStateOf(0)
    var handle by mutableStateOf(Handle.Main)

    var angleLock by mutableStateOf(false)
    var distanceLock by mutableStateOf(true)

    var ghost by mutableStateOf<Pt?>(null)
    var curveAt by mutableFloatStateOf(0.5f)

    var previewScale by mutableFloatStateOf(1f)

    override val handlesCode: Int
        get() = app.absentmotion.model.POINT_EDIT or (contour.coerceIn(0, 127) shl 20) or (point.coerceIn(0, 0xffff) shl 4) or handle.ordinal
}

private fun EditorState.newLayer(name: String, tag: String, fill: String, color: Int, children: List<XmlNode>): Layer {
    val number = (scene.layers.mapNotNull { l -> l.label.removePrefix("$name ").toIntOrNull().takeIf { l.label.startsWith("$name ") } }.maxOrNull() ?: 0) + 1
    val id = Layer.newId()
    val node = XmlNode(
        tag,
        linkedMapOf(
            "id" to "$id", "label" to "$name $number",
            "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, settings.defaultLayerDuration * 1000)}",
            "fillType" to fill,
        ),
        listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, 0f))))))) +
            (if (fill == "color") listOf(XmlNode("fillColor", mapOf("value" to formatColor(color)))) else emptyList()) + children,
    )
    return Layer(node)
}

private fun EditorState.enter(layer: Layer, mode: DrawingMode, created: Boolean) {
    if (created) { edit { it.add(layer) }; mode.added = scene }
    else if (!layer.activeAt(timeMs)) frame = scene.frameAt(layer.startTime)
    selectedId = layer.id
    playing = false
    addOpen = false
    panel = EditorPanel.Tools
    drawing = mode
}

fun EditorState.startFreehand() {
    val layer = newLayer(AppText.get(R.string.model_layer_drawing), "drawing", "none", 0, emptyList())
    enter(layer, FreehandMode(layer.id, created = true), created = true)
}

fun EditorState.editDrawing() {
    val layer = selected?.takeIf { it.kind == "drawing" } ?: return
    enter(layer, FreehandMode(layer.id, created = false), created = false)
}

fun EditorState.startVectorDrawing() {
    val colour = Color.rgb(Random.nextInt(200) + 55, Random.nextInt(200) + 55, Random.nextInt(200) + 55)
    val layer = newLayer(AppText.get(R.string.shape_name_shape), "shape", "color", colour, listOf(XmlNode("path", mapOf("d" to ""))))
    enter(layer, PointMode(layer.id, created = true, vector = true).apply { point = -1; handle = Handle.Add }, created = true)
}

fun EditorState.editPoints() {
    val layer = selected ?: return
    val contours = layer.contours ?: return
    enter(layer, PointMode(layer.id, created = false, vector = false).apply { if (contours.none { it.knots.isNotEmpty() }) { point = -1; handle = Handle.Add } }, created = false)
}

fun EditorState.leaveDrawing() {
    val mode = drawing ?: return
    drawing = null
    endGesture()
    val layer = scene.layer(mode.layerId)
    val empty = when (mode) {
        is FreehandMode -> layer?.strokes.isNullOrEmpty()
        is PointMode -> layer?.contours?.all { it.knots.isEmpty() } == true
        is TrackMode -> false
    }
    if (mode.created && empty && layer != null) {
        if (!forgetEdit(mode.added)) edit { it.remove(mode.layerId) }
        selectedId = null
    }
    if (mode is TrackMode) {
        mode.job?.cancel()
        mode.apply?.targetId?.takeIf { scene.layer(it) != null }?.let { selectedId = it }
    }
    panel = EditorPanel.Tools
}

suspend fun EditorState.convertToOutline() {
    val layer = selected ?: return
    if (layer.kind != "text" && !(layer.kind == "shape" && layer.shape.isNotEmpty())) return
    val document = scene
    val svg = engine.layerOutline(layer.id, timeMs) ?: return
    if (scene != document) return
    fun area(c: Contour) = c.knots.indices.sumOf { i -> val a = c.knots[i].p; val b = c.knots[(i + 1) % c.knots.size].p; (a.x * b.y - b.x * a.y).toDouble() }
    val parsed = parseSvgPath(svg)
    if (parsed.none { it.knots.isNotEmpty() }) return
    val contours = parsed.mapIndexed { i, c -> c.copy(exclude = i > 0 && area(c) * area(parsed[0]) < 0) }
    edit { s ->
        s.update(layer.id) { l ->
            val keep = l.node.children.filter { child ->
                child.name != "path" && child.name != "content" && child.name != "property" &&
                    !(child.name == "effect" && child.attrs["id"] == app.absentmotion.compat.ForeignEffectIds.TEXT_SPACING)
            }
            val attrs = l.node.attrs - setOf("s", "size", "font", "wrapWidth", "align")
            l.copy(node = l.node.copy(name = "shape", attrs = if ("label" in attrs) attrs else attrs + ("label" to l.label), children = keep)).withContours(contours)
        }
    }
}

fun Scene.worldMatrix(id: Long, timeMs: Int): Matrix? {
    fun effective(l: Layer, depth: Int): FloatArray {
        val c = floatArrayOf(l.location[0], l.location[1], l.pivot[0], l.pivot[1], l.scale[0], l.scale[1], l.transform("rotation", 0f)[0])
        val parent = l.node["parent"]?.toLongOrNull()?.takeIf { it != l.id }?.let { layerAt(it, timeMs) }
        if (parent == null || depth > 16) return c
        val p = effective(parent, depth + 1)
        val vx = (c[0] + c[2] - p[2]) * p[4]
        val vy = (c[1] + c[3] - p[3]) * p[5]
        val a = Math.toRadians(p[6].toDouble())
        val rx = (vx * cos(a) - vy * sin(a)).toFloat()
        val ry = (vx * sin(a) + vy * cos(a)).toFloat()
        c[0] = p[0] + rx - c[2] + p[2]
        c[1] = p[1] + ry - c[3] + p[3]
        c[4] *= p[4]; c[5] *= p[5]; c[6] += p[6]
        return c
    }
    val layer = layerAt(id, timeMs) ?: return null
    val e = effective(layer, 0)
    val skew = layer.transform("skew", 0f, 0f)
    return Matrix().apply {
        setTranslate(e[0], e[1])
        preRotate(e[6])
        preSkew(tan(skew[0]), tan(skew[1]))
        preScale(e[4], e[5])
        preTranslate(-e[2], -e[3])
    }
}

internal fun Matrix.map(p: Pt): Pt = floatArrayOf(p.x, p.y).also { mapPoints(it) }.let { Pt(it[0], it[1]) }

internal fun Matrix.unmapVector(v: Pt): Pt {
    val inverse = Matrix()
    if (!invert(inverse)) return v
    return floatArrayOf(v.x, v.y).also { inverse.mapVectors(it) }.let { Pt(it[0], it[1]) }
}

internal fun Matrix.inverseMap(p: Pt): Pt {
    val inverse = Matrix()
    if (!invert(inverse)) return p
    return inverse.map(p)
}

internal fun List<Contour>.withEmptyTail(selected: Int): List<Contour> =
    if (selected >= size) this + List(selected - size + 1) { Contour(emptyList(), closed = false) } else this
