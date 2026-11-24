package app.absentmotion.editor

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.ai.AI_TAG
import app.absentmotion.audio.AUDIO_FX_TAG
import app.absentmotion.audio.FxKind
import app.absentmotion.audio.audioEffects
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import kotlin.math.abs

data class HistoryNote(val id: Long, val text: UiText, val faint: Boolean = false, val spot: EditSpot? = null)

data class EditSpot(val path: List<Long>, val layer: Long, @StringRes val area: Int = 0, val effect: Int = -1, val effectType: String? = null, val keyT: Float? = null)

class EditSummary(val text: UiText, val spot: EditSpot?)

fun describeEdit(before: Scene, after: Scene, effectName: (String) -> String? = { null }): UiText = summarizeEdit(before, after, effectName).text

fun summarizeEdit(before: Scene, after: Scene, effectName: (String) -> String? = { null }, undone: Boolean = false): EditSummary {
    val diff = SceneDiff().apply { walk(before, after, emptyList()) }
    val added = diff.added
    val removed = diff.removed
    if (added.isNotEmpty() || removed.isNotEmpty()) {
        val text = when {
            removed.isEmpty() -> named(added.map { it.layer }, R.string.history_add_named, R.string.history_add_layer, R.string.history_add_layers)
            added.isEmpty() -> named(removed.map { it.layer }, R.string.history_delete_named, R.string.history_delete_layer, R.string.history_delete_layers)
            else -> txt(R.string.history_edit_layers)
        }
        return EditSummary(text, (if (undone) removed else added).firstOrNull()?.let { EditSpot(it.path, it.layer.id) })
    }
    val changed = diff.changed
    if (changed.isNotEmpty()) {
        val areas = changed.map { area(it.old, it.new) }.distinct()
        val audio = areas.singleOrNull() == R.string.history_audio_effects
        val fx = if (audio || areas.singleOrNull() == R.string.history_effects) changed.map { fxEdit(it.old, it.new, audio) } else null
        val keyframed = changed.singleOrNull()?.takeIf { fx == null }?.let { keyframeVerb(it.old.node, it.new.node) }
        val text = when {
            keyframed != null -> txt(keyframed)
            areas.size == 1 -> fx?.let { fxText(it, audio, effectName) } ?: txt(areas.single())
            areas.all { it == R.string.history_transform || it == R.string.history_position } -> txt(R.string.history_transform)
            else -> txt(R.string.history_edit_layers)
        }
        val first = changed.first()
        return EditSummary(text, spotOf(first, area(first.old, first.new), fx?.first(), audio, undone))
    }
    return when {
        diff.reorders > 0 -> EditSummary(txt(R.string.history_reorder), diff.moved?.let { EditSpot(it.path, it.layer.id) })
        before.node.attrs != after.node.attrs -> EditSummary(txt(R.string.history_project), null)
        else -> EditSummary(txt(R.string.history_edit), null)
    }
}

fun EditorState.goTo(spot: EditSpot): Boolean {
    val path = spot.path.takeIf { project.focused(it)?.layer(spot.layer) != null } ?: project.pathTo(spot.layer) ?: return false
    val layer = project.focused(path)?.layer(spot.layer) ?: return false
    val audio = spot.area == R.string.history_audio_effects
    val effect = if (spot.effect < 0) -1 else {
        val list = if (audio) layer.audioEffects.map { it.node } else layer.effects
        list.indices.filter { fxType(list[it], audio) == spot.effectType }.minByOrNull { abs(it - spot.effect) } ?: return false
    }
    drawing?.let { mode ->
        if (mode.layerId != spot.layer || path != groupPath) {
            if (mode !is PointMode || dropsOnLeave(mode)) return false
            leaveDrawing()
        }
    }
    endGesture()
    playing = false
    addOpen = false
    MultiSelect.clear()
    exitTo(groupPath.zip(path).takeWhile { (a, b) -> a == b }.size)
    path.drop(groupPath.size).forEach(::enterGroup)
    val here = scene
    val shown = here.layer(spot.layer) ?: return false
    selectedId = shown.id
    val last = maxOf(here.lastFrame, 0)
    val first = here.firstShownFrame(shown.startTime)
    if (spot.keyT != null) frame = here.frameAt(shown.timeOf(spot.keyT)).coerceIn(0, last)
    else if (frame !in first..here.lastShownFrame(shown.endTime)) frame = first.coerceIn(0, last)
    easingOpen = false
    keypad = null
    keyTarget = null
    effectIndex = if (spot.area == R.string.history_effects) effect else -1
    audioFxFocus = if (audio) effect else null
    volumeOpen = audio || spot.area == R.string.history_volume
    panel = when (spot.area) {
        R.string.history_effects -> EditorPanel.Effects
        R.string.history_position, R.string.history_size, R.string.history_rotation, R.string.history_transform -> EditorPanel.MoveTransform
        R.string.history_fill -> EditorPanel.ColorFill
        R.string.history_border, R.string.history_shadow -> EditorPanel.BorderShadow
        R.string.history_opacity, R.string.history_blend -> EditorPanel.Blending
        R.string.history_text -> if (shown.kind == "text") EditorPanel.EditText else EditorPanel.Tools
        R.string.history_speed -> EditorPanel.Speed
        R.string.model_pose -> EditorPanel.Pose
        R.string.model_textures -> EditorPanel.Textures
        else -> EditorPanel.Tools
    }
    revealCount++
    return true
}

private fun Scene.pathTo(id: Long): List<Long>? {
    if (layer(id) != null) return emptyList()
    for (group in layers) if (group.isPlainGroup) group.nested?.pathTo(id)?.let { return listOf(group.id) + it }
    return null
}

private class Spotted(val path: List<Long>, val layer: Layer)

private class Changed(val path: List<Long>, val old: Layer, val new: Layer)

private class SceneDiff {
    val added = ArrayList<Spotted>()
    val removed = ArrayList<Spotted>()
    val changed = ArrayList<Changed>()
    var reorders = 0
    var moved: Spotted? = null

    fun walk(before: Scene, after: Scene, path: List<Long>): Boolean {
        val count = added.size + removed.size + changed.size + reorders
        val old = before.layers
        val new = after.layers
        val oldIds = old.mapTo(HashSet()) { it.id }
        val newIds = new.mapTo(HashSet()) { it.id }
        new.filter { it.id !in oldIds }.mapTo(added) { Spotted(path, it) }
        old.filter { it.id !in newIds }.mapTo(removed) { Spotted(path, it) }
        val byId = old.associateBy { it.id }
        for (layer in new) {
            val was = byId[layer.id] ?: continue
            if (was.node == layer.node) continue
            val inside = was.isPlainGroup && layer.isPlainGroup && was.node.withChild("scene", null) == layer.node.withChild("scene", null)
            if (!inside || !walk(was.nested!!, layer.nested!!, path + layer.id)) changed += Changed(path, was, layer)
        }
        val kept = old.map { it.id }.filter { it in newIds }
        val order = new.map { it.id }.filter { it in oldIds }
        if (kept != order) {
            reorders++
            if (moved == null) moved(kept, order)?.let { (_, to) -> moved = Spotted(path, after.layer(order[to])!!) }
        }
        return added.size + removed.size + changed.size + reorders != count
    }
}

private class FxEdit(@StringRes val verb: Int, val items: List<XmlNode>, val before: List<Int>, val after: List<Int>)

private fun fxType(node: XmlNode, audio: Boolean): String? = if (audio) node["type"] else node["id"]

private fun fxEdit(old: Layer, new: Layer, audio: Boolean): FxEdit? {
    val a = if (audio) old.audioEffects.map { it.node } else old.effects
    val b = if (audio) new.audioEffects.map { it.node } else new.effects
    if (a.size == b.size) {
        moved(a, b)?.let { (from, to) -> return FxEdit(R.string.history_move_effect, listOf(b[to]), listOf(from), listOf(to)) }
        if (!a.indices.all { fxType(a[it], audio) == fxType(b[it], audio) }) return null
        val changed = a.indices.filter { a[it] != b[it] }
        return if (changed.isEmpty()) null else FxEdit(0, changed.map { b[it] }, changed, changed)
    }
    if (a.size < b.size) return extra(a, b)?.let { FxEdit(R.string.history_add_effect, it.map(b::get), emptyList(), it) }
    return extra(b, a)?.let { FxEdit(R.string.history_remove_effect, it.map(a::get), it, emptyList()) }
}

private fun fxText(edits: List<FxEdit?>, audio: Boolean, effectName: (String) -> String?): UiText? {
    val verb = edits.map { it?.verb ?: return null }.distinct().singleOrNull() ?: return null
    val names = edits.flatMap { it!!.items }.map { node ->
        (if (audio) FxKind.entries.firstOrNull { it.xml == node["type"] }?.let { txt(it.label) } else node["id"]?.let(effectName)) ?: return null
    }.distinct()
    val joined = when (names.size) {
        1 -> names[0]
        2 -> txt(R.string.history_two_effects, names[0], names[1])
        else -> return null
    }
    return txt(if (verb == 0) R.string.library_text_value else verb, joined)
}

private fun spotOf(change: Changed, @StringRes area: Int, fx: FxEdit?, audio: Boolean, undone: Boolean): EditSpot? {
    val now = if (undone) change.old else change.new
    val key = { if (area == R.string.history_timing) null else changedKey(change.old.node, change.new.node, undone) }
    if (fx == null) return EditSpot(change.path, now.id, area, keyT = key())
    val index = (if (undone) fx.before else fx.after).firstOrNull() ?: return null
    val node = (if (audio) now.audioEffects.map { it.node } else now.effects)[index]
    return EditSpot(change.path, now.id, area, index, fxType(node, audio), if (fx.verb == 0) key() else null)
}

private fun changedKey(old: XmlNode, new: XmlNode, undone: Boolean): Float? {
    val was = ArrayList<Pair<String, XmlNode>>().also { keys(old, "", it) }
    val now = ArrayList<Pair<String, XmlNode>>().also { keys(new, "", it) }
    val (here, there) = if (undone) was to now else now to was
    return (here.firstOrNull { it !in there } ?: there.firstOrNull { it !in here })?.second?.get("t")?.toFloatOrNull()?.takeIf { it.isFinite() }
}

@StringRes
private fun keyframeVerb(old: XmlNode, new: XmlNode): Int? {
    if (old.attrs != new.attrs) return null
    val was = ArrayList<Pair<String, XmlNode>>().also { keys(old, "", it) }
    val now = ArrayList<Pair<String, XmlNode>>().also { keys(new, "", it) }
    return when {
        now.size > was.size -> R.string.a11y_add_keyframe
        now.size < was.size -> R.string.a11y_remove_keyframe
        was.map { it.first to it.second["t"] } != now.map { it.first to it.second["t"] } -> R.string.history_move_keyframe
        else -> null
    }
}

private fun keys(node: XmlNode, path: String, out: MutableList<Pair<String, XmlNode>>) {
    val seen = HashMap<String, Int>()
    for (child in node.children) {
        if (child.name == "scene" || child.name == "speedMap") continue
        if (child.name == "kf") { out += path to child; continue }
        val tag = child.name + "=" + (child["name"] ?: "")
        keys(child, "$path/$tag#${seen.merge(tag, 1, Int::plus)}", out)
    }
}

private fun <T> extra(short: List<T>, long: List<T>): List<Int>? {
    val out = ArrayList<Int>()
    var at = 0
    for (i in long.indices) if (at < short.size && short[at] == long[i]) at++ else out += i
    return out.takeIf { at == short.size }
}

private fun <T> moved(a: List<T>, b: List<T>): Pair<Int, Int>? {
    if (a.size != b.size) return null
    val p = a.indices.firstOrNull { a[it] != b[it] } ?: return null
    val q = a.indices.last { a[it] != b[it] }
    if (b[q] == a[p] && (p until q).all { b[it] == a[it + 1] }) return p to q
    if (b[p] == a[q] && (p until q).all { b[it + 1] == a[it] }) return q to p
    return null
}

private fun named(layers: List<Layer>, @StringRes one: Int, @StringRes plain: Int, @StringRes many: Int): UiText {
    if (layers.size > 1) return txt(many)
    val label = layers.single().label.trim()
    return if (label.isEmpty()) txt(plain) else txt(one, if (label.length > 24) label.take(23) + "…" else label)
}

@StringRes
private fun area(old: Layer, new: Layer): Int {
    val a = old.node
    val b = new.node
    for (name in (a.children.map { it.name } + b.children.map { it.name }).distinct()) {
        val before = a.children.filter { it.name == name }
        val after = b.children.filter { it.name == name }
        if (before != after) childArea(name, before, after)?.let { return it }
    }
    for (key in a.attrs.keys + b.attrs.keys) if (a[key] != b[key]) attributeArea(key)?.let { return it }
    return if (new.kind == "text") R.string.history_text else R.string.history_edit
}

@StringRes
private fun childArea(name: String, before: List<XmlNode>, after: List<XmlNode>): Int? = when (name) {
    "transform" -> {
        val x = before.firstOrNull()
        val y = after.firstOrNull()
        val changed = if (x?.child("pivot") != y?.child("pivot")) "pivot" else ((x?.children?.map { it.name }.orEmpty()) + (y?.children?.map { it.name }.orEmpty())).distinct()
            .firstOrNull { part -> x?.child(part) != y?.child(part) }
        when (changed) {
            "location" -> R.string.history_position
            "scale" -> R.string.history_size
            "rotation" -> R.string.history_rotation
            "opacity" -> R.string.history_opacity
            else -> R.string.history_transform
        }
    }
    "fillColor", "gradient" -> R.string.history_fill
    "border" -> R.string.history_border
    "shadow" -> R.string.history_shadow
    "effect" -> R.string.history_effects
    AUDIO_FX_TAG -> R.string.history_audio_effects
    "gain" -> R.string.history_volume
    "speedMap" -> R.string.history_speed
    "content" -> R.string.history_text
    "property" -> if (((before - after.toSet()) + (after - before.toSet())).all { it["name"]?.startsWith("bone.") == true }) R.string.model_pose else R.string.history_shape
    "material" -> R.string.model_textures
    AI_TAG -> R.string.history_ai
    else -> null
}

@StringRes
private fun attributeArea(key: String): Int? = when (key) {
    "startTime", "endTime", "inTime", "outTime", "loop" -> R.string.history_timing
    "label" -> R.string.history_rename
    "src", "fillImage", "fillVideo", "mediaFillMode" -> R.string.history_media
    "fillType" -> R.string.history_fill
    "speed" -> R.string.history_speed
    "hidden" -> R.string.history_visibility
    "lock" -> R.string.history_lock
    "blending" -> R.string.history_blend
    "clippingMask" -> R.string.history_mask
    else -> null
}
