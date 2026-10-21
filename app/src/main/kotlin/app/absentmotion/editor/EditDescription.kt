package app.absentmotion.editor

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.ai.AI_TAG
import app.absentmotion.audio.AUDIO_FX_TAG
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode

data class HistoryNote(val id: Long, val text: UiText)

fun describeEdit(before: Scene, after: Scene): UiText {
    val old = before.layers
    val new = after.layers
    val oldIds = old.map { it.id }
    val newIds = new.map { it.id }
    val added = new.filter { it.id !in oldIds }
    val removed = old.filter { it.id !in newIds }
    if (added.isNotEmpty() || removed.isNotEmpty()) return when {
        removed.isEmpty() -> named(added, R.string.history_add_named, R.string.history_add_layer, R.string.history_add_layers)
        added.isEmpty() -> named(removed, R.string.history_delete_named, R.string.history_delete_layer, R.string.history_delete_layers)
        else -> txt(R.string.history_edit_layers)
    }
    val byId = old.associateBy { it.id }
    val areas = new.filter { byId[it.id]?.node != it.node }.map { area(byId.getValue(it.id), it) }.distinct()
    return when {
        areas.size == 1 -> txt(areas.single())
        areas.size > 1 -> txt(R.string.history_edit_layers)
        newIds != oldIds -> txt(R.string.history_reorder)
        before.node.attrs != after.node.attrs -> txt(R.string.history_project)
        else -> txt(R.string.history_edit)
    }
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
        val changed = ((x?.children?.map { it.name }.orEmpty()) + (y?.children?.map { it.name }.orEmpty())).distinct()
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
    "property" -> R.string.history_shape
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
    "blending" -> R.string.history_blend
    "clippingMask" -> R.string.history_mask
    else -> null
}
