package app.absentmotion.editor

import app.absentmotion.platform.Color
import kotlin.math.roundToInt
import app.absentmotion.model.Scene
import app.absentmotion.model.copyLayers
import app.absentmotion.model.splitAt
import app.absentmotion.model.trimEndAt
import app.absentmotion.model.trimStartAt
import app.absentmotion.model.SpeedRef
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.withSpeedKeyframeToggled
import app.absentmotion.model.Easing
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.mediaUris
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.keyframes
import app.absentmotion.model.stretchAt
import app.absentmotion.model.ShapeDef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatColor
import app.absentmotion.model.formatFloats
import kotlin.random.Random

fun EditorState.addShape(def: ShapeDef) {
    val scene = scene
    val durationSec = settings.defaultLayerDuration
    val number = (scene.layers.mapNotNull { it.label.removePrefix(def.name + " ").toIntOrNull().takeIf { _ -> it.label.startsWith(def.name + " ") } }.maxOrNull() ?: 0) + 1
    fun randomColor() = formatColor(Color.HSVToColor(floatArrayOf(Random.nextFloat() * 360f, 0.3f + Random.nextFloat() * 0.5f, 0.55f + Random.nextFloat() * 0.45f)))
    val id = Layer.newId()
    val attrs = linkedMapOf(
        "id" to "$id", "label" to "${def.name} $number",
        "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.frameEnd(frame + durationSec * scene.fps)}",
        "fillType" to def.fillType, "mediaFillMode" to "fill",
    )
    if (def.id.isNotEmpty()) attrs["s"] = def.id
    val node = XmlNode(
        "shape",
        attrs,
        listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(scene.width / 2f, scene.height / 2f, 0f)))))),
            XmlNode("fillColor", mapOf("value" to randomColor())),
        ) + def.children.map { if (it.name == "path-stroke") it.withChild("color", XmlNode("color", mapOf("value" to randomColor()))) else it },
    )
    edit { it.add(Layer(node)) }
    selectedId = id
    panel = EditorPanel.Tools
}

fun EditorState.stepBack(): Boolean {
    when {
        MultiSelect.active -> MultiSelect.clear()
        selectedId == null -> return false
        easingOpen -> easingOpen = false
        panel == EditorPanel.Effects && effectIndex >= 0 -> effectIndex = -1
        panel != EditorPanel.Tools -> panel = EditorPanel.Tools
        else -> selectedId = null
    }
    return true
}

fun EditorState.deselectLayer() {
    endGesture()
    selectedId = null
    panel = EditorPanel.Tools
    effectIndex = -1
    volumeOpen = false
    easingOpen = false
    keypad = null
    keyTarget = null
}

fun EditorState.clearSelection() {
    MultiSelect.clear()
    deselectLayer()
}

fun EditorState.deleteSelected() {
    val id = selectedId ?: return
    edit { it.unparentChildrenOf(setOf(id), sampleMs).remove(id) }
}

fun EditorState.duplicateSelected() {
    val layer = selected ?: return
    val copy = layer.withId(Layer.newId()).withLabel(layer.label + " Copy")
    edit { it.add(copy) }
    selectedId = copy.id
}

fun EditorState.toggleHidden(id: Long) = edit { it.update(id) { layer -> layer.withHidden(!layer.hidden) } }

fun EditorState.trimStart() {
    val trimmed = selected?.let { scene.trimStartAt(it, frame) } ?: return
    editSelected { trimmed }
}

fun EditorState.trimEnd() {
    val trimmed = selected?.let { scene.trimEndAt(it, frame) } ?: return
    editSelected { trimmed }
}

fun EditorState.split() {
    val (first, second) = selected?.let { scene.splitAt(it, frame, Layer.newId()) } ?: return
    edit { it.update(first.id) { first }.add(second) }
    selectedId = null
}

private fun EditorState.stops(): List<Int> {
    val layer = selected ?: return listOf(0, scene.lastFrame)
    return (listOf(layer.startTime, layer.endTime) + layer.keyframeTimes()).map { scene.frameAt(it) }.distinct().sorted()
}

fun EditorState.skipBack() {
    frame = stops().lastOrNull { it < frame } ?: frame
}

fun EditorState.skipForward() {
    frame = stops().firstOrNull { it > frame } ?: frame
}

fun EditorState.resetTransform(tabs: List<Int>) = editSelected { layer ->
    tabs.fold(layer) { l, tab ->
        when (tab) {
            0 -> l.withTransform("location", scene.width / 2f, scene.height / 2f, if (l.kind == "camera") -scene.cameraZoom else 0f)
            1 -> l.withRotation(0f)
            2 -> l.withTransform("scale", 1f, 1f)
            else -> l.withSkew(0f, 0f)
        }
    }
}

fun EditorState.toggleKeyframe() {
    val target = keyTarget ?: return
    editKeyValue { layer ->
        val missing = layer.element(target.ref) == null
        val present = when {
            !missing -> layer
            target.ref.kind == PropRef.Kind.Property -> layer.withProp(target.ref.name, target.type, target.default)
            target.ref.kind == PropRef.Kind.Effect ->
                layer.withEffect(target.ref.effectIndex) { it.withEffectProperty(target.ref.name, target.type, target.default, layer.t, layer.snap) }
            else -> layer
        }
        if (target.ref == SpeedRef) present.withSpeedKeyframeToggled(sampleMs, scene.fps)
        else present.withKeyframeToggled(target.ref, target.default)
    }
}

val EditorState.easing: Easing? get() = keyTarget?.let { keyLayer?.easing(it.ref) }

fun EditorState.setEasing(easing: Easing, key: Any? = null) {
    val ref = keyTarget?.ref ?: return
    if (ref == SpeedRef && easing.type !in setOf("linear", "cubicBezier")) return
    val curve = if (ref == SpeedRef) easing.copy(p = easing.p.map { it.coerceIn(0f, 1f) }) else easing
    editKeyValue(key) { it.withEasing(ref, curve) }
}

fun EditorState.gotoStretch(direction: Int) {
    val target = stretchFrame(direction) ?: return
    frame = target
}

fun EditorState.hasStretch(direction: Int): Boolean = stretchFrame(direction) != null

private fun EditorState.stretchFrame(direction: Int): Int? {
    val ref = keyTarget?.ref ?: return null
    val layer = keyLayer ?: return null
    val element = layer.element(ref) ?: return null
    val keys = element.keyframes
    val end = element.stretchAt(layer.t, layer.snap) + direction
    if (end < 1 || end > keys.lastIndex || element.stretchAt(layer.t, layer.snap) < 0) return null
    val middle = (keys[end - 1].t + keys[end].t) / 2
    val time = if (ref == SpeedRef) layer.startTime + VideoTiming(layer).localMs(middle.toDouble() / 1_000_000).roundToInt() else layer.timeOf(middle)
    return scene.frameAt(time)
}

private var copiedCurve: Easing? = null

val EditorState.canPasteCurve: Boolean get() = copiedCurve != null

fun EditorState.copyCurve() {
    copiedCurve = easing
}

fun EditorState.pasteCurve() {
    setEasing(copiedCurve ?: return)
}

fun EditorState.pasteCurveToAll() {
    val ref = keyTarget?.ref ?: return
    val curve = copiedCurve ?: return
    if (ref == SpeedRef && (curve.type !in setOf("linear", "cubicBezier") || curve.p.any { it < 0 || it > 1 })) return
    editKeyValue { it.withEasingEverywhere(ref, curve) }
}

fun EditorState.flip(horizontal: Boolean) = editSelected {
    val s = it.scale
    if (horizontal) it.withTransform("scale", -s[0], s[1]) else it.withTransform("scale", s[0], -s[1])
}

private var copiedLayers: List<Layer> = emptyList()
private var copiedMedia: List<MediaDeclaration> = emptyList()

val EditorState.canPaste: Boolean get() = copiedLayers.isNotEmpty()
val EditorState.pasteCount: Int get() = copiedLayers.size

internal val copiedLayerForStyle: Layer? get() = copiedLayers.lastOrNull()
internal val copiedMediaForStyle: List<MediaDeclaration> get() = copiedMedia

val EditorState.chosenLayers: List<Layer>
    get() = if (MultiSelect.active) scene.layers.filter { it.id in MultiSelect.ids }
    else listOfNotNull(selectedId?.let { scene.layer(it) })

fun EditorState.copySelected() {
    val chosen = chosenLayers
    val ids = chosen.map { it.id }.toSet()
    val outsideParents = chosen.mapNotNull { it.node["parent"]?.toLongOrNull() }.filter { it !in ids }.toSet()
    val flat = scene.unparentChildrenOf(outsideParents, sampleMs)
    copiedLayers = chosen.mapNotNull { flat.layer(it.id) }
    copiedMedia = scene.media
}

fun EditorState.duplicateChosen() {
    if (!MultiSelect.active) return duplicateSelected()
    val copies = chosenLayers.zip(copyLayers(chosenLayers, preserveOutsideParents = true))
        .associate { (source, copy) -> source.id to copy.withLabel(source.label + " Copy") }
    if (copies.isEmpty()) return
    edit { s -> Scene(s.node.copy(children = s.node.children.flatMap { node ->
        val copy = if (node.name in Scene.LAYER_TAGS) node["id"]?.toLongOrNull()?.let { copies[it] } else null
        if (copy == null) listOf(node) else listOf(node, copy.node)
    })) }
    MultiSelect.clear()
    deselectLayer()
}

fun EditorState.deleteChosen() {
    val ids = chosenLayers.map { it.id }.toSet()
    if (ids.isEmpty()) return
    edit { s -> ids.fold(s.unparentChildrenOf(ids, sampleMs)) { acc, id -> acc.remove(id) } }
    MultiSelect.clear()
    deselectLayer()
}

fun EditorState.pasteLayer() {
    val copies = copyLayers(copiedLayers, timeMs)
    if (copies.isEmpty()) return
    val wanted = copies.flatMap { it.node.mediaUris() }.toSet()
    edit { s -> copies.fold(copiedMedia.filter { it.uri in wanted }.fold(s) { acc, media -> acc.withMedia(media) }) { acc, layer -> acc.add(layer) } }
    MultiSelect.clear()
    deselectLayer()
}

fun EditorState.changeSettings(width: Int, height: Int, fps: Int, bgColor: Int) =
    edit { it.withSettings(width, height, fps, bgColor) }

fun EditorState.rename(title: String) = edit { it.withTitle(title) }

fun EditorState.renameLayer(id: Long, label: String) = edit { it.update(id) { layer -> layer.withLabel(label) } }

fun EditorState.addImage(media: MediaDeclaration) {
    if (media.width <= 0 || media.height <= 0) return
    val scene = scene
    val turned = media.orientation == 90 || media.orientation == 270
    val w = if (turned) media.height else media.width
    val h = if (turned) media.width else media.height
    val heightAtWidth = h.toLong() * scene.width / w
    val fitted = if (heightAtWidth <= scene.height) scene.width to heightAtWidth.toInt()
        else (w.toLong() * scene.height / h).toInt() to scene.height
    val raw = if (turned) fitted.second to fitted.first else fitted
    val id = Layer.newId()
    var layer = Layer(XmlNode("shape", linkedMapOf("id" to "$id", "label" to media.title,
        "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.frameEnd(frame + settings.defaultLayerDuration * scene.fps)}",
        "fillType" to "media", "fillImage" to media.uri, "mediaFillMode" to "fill", "s" to ".rect")))
        .withTransform("location", scene.width / 2f, scene.height / 2f, 0f)
        .withProp("size", "vec2", raw.first / 2f, raw.second / 2f)
    if (media.orientation != 0) layer = layer.withTransform("rotation", media.orientation.toFloat())
    edit { it.add(layer, listOf(media)) }
    selectedId = id
    panel = EditorPanel.Tools
    addOpen = false
}

suspend fun EditorState.compositionArea(mode: String) {
    val layer = selected ?: return
    val document = scene
    val bounds = engine.layerBounds(layer.id, sampleMs) ?: return
    if (scene != document || selectedId != layer.id) return
    val width = bounds[2] - bounds[0]
    val height = bounds[3] - bounds[1]
    if (width <= 0f || height <= 0f) return
    var sx = document.width / width
    var sy = document.height / height
    if (mode != "stretch") {
        val factor = if (mode == "fit") minOf(sx, sy) else maxOf(sx, sy)
        sx = factor; sy = factor
    }
    val at = layer.location
    val dx = document.width / 2f - (bounds[0] + bounds[2]) / 2f + (1 - sx) * ((bounds[0] + bounds[2]) / 2f - at[0])
    val dy = document.height / 2f - (bounds[1] + bounds[3]) / 2f + (1 - sy) * ((bounds[1] + bounds[3]) / 2f - at[1])
    edit { it.update(layer.id) { current ->
        fun map(name: String, defaults: FloatArray, change: (FloatArray) -> Unit): XmlNode {
            val n = current.node.child("transform")?.child(name) ?: XmlNode(name)
            fun value(text: String?): String {
                val parts = text?.split(',')?.map { it.toFloatOrNull() } ?: emptyList()
                val v = FloatArray(maxOf(parts.size, defaults.size)) { parts.getOrNull(it) ?: defaults.getOrElse(it) { 0f } }
                change(v)
                return formatFloats(v)
            }
            val mapped = if (n["value"] != null) n.with("value", value(n["value"])) else n
            return if (n.children.any { child -> child.name == "kf" }) mapped.copy(children = n.children.map { child ->
                if (child.name == "kf") child.with("v", value(child["v"])) else child
            }) else n.with("value", value(n["value"]))
        }
        val transform = (current.node.child("transform") ?: XmlNode("transform"))
            .withChild("location", map("location", floatArrayOf(0f, 0f, 0f)) { v -> v[0] += dx; v[1] += dy })
            .withChild("scale", map("scale", floatArrayOf(1f, 1f)) { v -> v[0] *= sx; v[1] *= sy })
        current.copy(node = current.node.withChild("transform", transform))
    } }
}
