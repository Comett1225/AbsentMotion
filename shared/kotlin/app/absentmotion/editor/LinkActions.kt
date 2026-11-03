package app.absentmotion.editor

import app.absentmotion.model.NumberRef
import app.absentmotion.model.ValueLink
import app.absentmotion.model.canLink
import app.absentmotion.model.withValueLink

fun EditorState.setValueLink(target: NumberRef, link: ValueLink?, fallback: String): Boolean {
    val id = selectedId ?: return false
    if (link != null && !scene.canLink(id, target, link)) return false
    edit { it.withValueLink(id, target, link, fallback) }
    endGesture()
    return true
}
