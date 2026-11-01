package app.absentmotion.pc.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
    var selected by mutableLongStateOf(0)
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
        dirty = scene.node != saved
        historyVersion++
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.addLast(scene)
        scene = redo.removeLast()
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
    fun step(frames: Int) { time = scene.frameStart((scene.frameAt(time) + frames).coerceIn(0, scene.lastFrame)) }
    fun delete() { val id = selected; edit { it.remove(id) }; selected = 0 }
    fun duplicate() {
        val original = layer ?: return
        val copy = original.withId(Layer.newId())
        edit { it.addAbove(copy, original.id) }
        selected = copy.id
    }
    fun split() {
        val original = layer ?: return
        val next = scene.withSplit(scene.pinnedPath(original).withPinnedClock(), scene.frameAt(time), Layer.newId()) ?: return
        edit { next }
    }
    fun add(layer: Layer) { edit { it.add(layer) }; selected = layer.id }
}
