package app.absentmotion.pc.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.model.*
import java.io.File

fun emptyScene() = Scene.parse("<scene title=\"Untitled\" width=\"1920\" height=\"1080\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff14161a\"/>")

class ProjectState {
    var scene by mutableStateOf(emptyScene())
        private set
    var file by mutableStateOf<File?>(null)
        private set
    var time by mutableIntStateOf(0)
    var selectedIds by mutableStateOf<List<Long>>(emptyList())
        private set
    var selected: Long
        get() = selectedIds.lastOrNull() ?: 0L
        set(value) { selectMany(if (value == 0L) emptyList() else listOf(value)) }
    var fillOpen by mutableStateOf(true)
    var safeArea by mutableStateOf(false)

    fun selectMany(ids: Collection<Long>) {
        selectedIds = ids.distinct().filter { scene.layer(it)?.node?.get("locked") != "true" && scene.layer(it) != null }
    }
    fun select(id: Long, add: Boolean = false) {
        if (scene.layer(id)?.node?.get("locked") == "true") return
        selectMany(if (!add) listOf(id) else if (id in selectedIds) selectedIds - id else selectedIds + id)
    }
    fun selectAll() = selectMany(scene.layers.map { it.id })
    private fun cleanSelection() = selectMany(selectedIds)
    var playing by mutableStateOf(false)
    var dirty by mutableStateOf(false)
        private set
    private var saved: XmlNode? = scene.node
    private val undo = ArrayDeque<Scene>()
    private val redo = ArrayDeque<Scene>()
    private var gestureStart: Scene? = null
    var historyVersion by mutableIntStateOf(0)
        private set
    val canUndo get() = historyVersion.let { undo.isNotEmpty() }
    val canRedo get() = historyVersion.let { redo.isNotEmpty() }
    val layer get() = scene.layer(selected)

    fun open(next: Scene, source: File?) {
        scene = next
        file = source
        saved = next.node
        dirty = false
        time = 0
        selected = 0
        playing = false
        undo.clear()
        redo.clear()
        gestureStart = null
        historyVersion++
    }

    fun edit(preserveDuration: Boolean = true, change: (Scene) -> Scene) {
        val before = scene
        val changed = change(before)
        val next = if (preserveDuration && changed.totalTime < before.totalTime) Scene(changed.node.with("totalTime", "${before.totalTime}")) else changed
        if (next == before) return
        undo.addLast(before)
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        scene = next
        cleanSelection()
        dirty = next.node != saved
        historyVersion++
    }

    fun beginGesture() { gestureStart = scene }
    fun gesture(change: (Scene) -> Scene) {
        val start = gestureStart ?: return
        scene = change(start)
        dirty = scene.node != saved
    }
    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (scene == start) return
        undo.addLast(start)
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        historyVersion++
    }
    fun cancelGesture() {
        scene = gestureStart ?: return
        gestureStart = null
        dirty = scene.node != saved
    }

    fun undo() {
        if (undo.isEmpty()) return
        redo.addLast(scene)
        scene = undo.removeLast()
        cleanSelection()
        dirty = scene.node != saved
        historyVersion++
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.addLast(scene)
        scene = redo.removeLast()
        cleanSelection()
        dirty = scene.node != saved
        historyVersion++
    }

    fun recovered() { saved = null; dirty = true }

    fun saved(destination: File) {
        file = destination
        saved = scene.node
        dirty = false
    }

    fun update(change: (Layer) -> Layer) = edit { it.update(selected, time, change) }
    fun updateSelection(change: (Layer) -> Layer) {
        val ids = selectedIds.toList()
        edit { scene -> ids.fold(scene) { next, id -> next.update(id, time, change) } }
    }
    fun step(frames: Int) { time = scene.frameStart((scene.frameAt(time) + frames).coerceIn(0, scene.lastFrame)) }
    fun delete() { val ids = selectedIds.toList(); edit { scene -> ids.fold(scene) { next, id -> next.remove(id) } }; selectMany(emptyList()) }
    fun duplicate() {
        val originals = scene.layers.filter { it.id in selectedIds }
        if (originals.isEmpty()) return
        val copies = copyLayers(originals, preserveOutsideParents = true)
        edit { scene -> originals.zip(copies).fold(scene) { next, (original, copy) -> next.addAbove(copy, original.id) } }
        selectMany(copies.map { it.id })
    }
    fun paste(layers: List<Layer>, media: List<MediaDeclaration> = emptyList()) {
        if (layers.isEmpty()) return
        val copies = copyLayers(layers, time, move = scene::movedClip)
        edit { scene -> copies.fold(media.fold(scene) { next, item -> next.withMedia(item) }) { next, layer -> next.add(layer) } }
        selectMany(copies.map { it.id })
    }
    fun split() {
        val ids = selectedIds.toList()
        edit { scene -> ids.fold(scene) { next, id ->
            val layer = next.layer(id) ?: return@fold next
            next.withSplit(next.pinnedPath(layer).withPinnedClock(), next.frameAt(time), Layer.newId()) ?: next
        } }
    }
    fun reorderSelection(forward: Boolean) {
        val ids = scene.layers.map { it.id }.filter { it in selectedIds }.let { if (forward) it.asReversed() else it }
        edit { scene -> ids.fold(scene) { next, id ->
            val index = next.layers.indexOfFirst { it.id == id }
            val target = index + if (forward) 1 else -1
            if (target !in next.layers.indices || next.layers[target].id in selectedIds) next else next.reorder(id, target)
        } }
    }
    fun add(layer: Layer) { edit { it.add(layer) }; selected = layer.id }
}
