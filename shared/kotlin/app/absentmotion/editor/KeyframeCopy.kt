package app.absentmotion.editor

import app.absentmotion.model.KeyScope
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes

class CopiedKey(val path: List<Int>, val names: List<String>, val key: Keyframe)

private val XmlNode.tag: String get() = listOfNotNull(name, this["name"], this["id"]).joinToString(":")

internal fun Layer.keysAtPlayhead(scope: KeyScope?): List<CopiedKey> {
    val only = scope?.let { s -> s.ref?.let(::element) ?: effects.getOrNull(s.effectIndex) ?: return emptyList() }
    val out = mutableListOf<CopiedKey>()
    fun walk(n: XmlNode, path: List<Int>, names: List<String>, inside: Boolean) {
        if (n.name == "speedMap" || n.name == "scene") return
        val here = inside || n === only
        if (here) n.keyframes.firstOrNull { it.t in snap }?.let { out += CopiedKey(path, names, it) }
        n.children.forEachIndexed { i, child -> if (child.name != "kf") walk(child, path + i, names + child.tag, here) }
    }
    walk(node, emptyList(), emptyList(), only == null)
    return out
}

internal fun Layer.withKeysPasted(keys: List<CopiedKey>): Layer {
    if (t < 0f || t > 1f) return this
    fun XmlNode.pasted(key: CopiedKey, depth: Int): XmlNode? {
        if (depth == key.path.size) return withKeyframes(keyframes.filterNot { it.t in snap } + key.key.copy(t = t))
        val index = key.path[depth]
        val child = children.getOrNull(index)?.takeIf { it.tag == key.names[depth] } ?: return null
        val next = child.pasted(key, depth + 1) ?: return null
        return copy(children = children.toMutableList().also { it[index] = next })
    }
    return copy(node = keys.fold(node) { n, key -> n.pasted(key, 0) ?: n })
}

private var copiedKeys: List<CopiedKey> = emptyList()

fun EditorState.copyKeyframes(): Boolean {
    val layer = (selectedId ?: chosenLayers.firstOrNull()?.id)?.let { scene.layerAt(it, sampleMs) } ?: return false
    val keys = layer.keysAtPlayhead(if (layer.id == selectedId) keyScope else null)
    if (keys.isEmpty()) return false
    copiedKeys = keys
    return true
}

fun EditorState.pasteKeyframes(): Boolean {
    val ids = chosenLayers.map { it.id }.toSet()
    if (ids.isEmpty() || copiedKeys.isEmpty()) return false
    val keys = copiedKeys
    edit { it.updateAll(ids, sampleMs) { layer -> layer.withKeysPasted(keys) } }
    return true
}

fun EditorState.addKeyframe(): Boolean {
    val target = keyTarget ?: return false
    if (keyLayer?.onKeyframe(target.ref) != true) toggleKeyframe()
    return true
}
