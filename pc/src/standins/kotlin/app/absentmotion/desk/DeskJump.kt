package app.absentmotion.desk

import app.absentmotion.editor.EditorState
import app.absentmotion.editor.chosenLayers

object DeskJump {
    internal fun previousFrame(marks: List<Int>, frame: Int): Int = marks.lastOrNull { it < frame } ?: 0

    internal fun nextFrame(marks: List<Int>, frame: Int, last: Int): Int = marks.firstOrNull { it > frame } ?: last

    private fun frames(state: EditorState): List<Int> = state.scene.bookmarks.map(state.scene::frameAt)

    fun previous(state: EditorState) {
        state.playing = false
        state.frame = previousFrame(frames(state), state.frame).coerceIn(0, state.scene.lastFrame)
    }

    fun next(state: EditorState) {
        state.playing = false
        val last = state.scene.lastFrame
        val target = nextFrame(frames(state), state.frame, last)
        if (target >= last) state.parkAtEnd() else state.frame = target.coerceAtLeast(0)
    }

    private fun keyFrames(state: EditorState): List<Int> =
        (state.chosenLayers.ifEmpty { state.scene.layers }).flatMap { layer ->
            layer.keyframeTimes(if (layer.id == state.selectedId) state.keyScope else null).filter { it in layer.startTime..layer.endTime }
        }.map { state.scene.frameAt(it).coerceIn(0, maxOf(state.scene.lastFrame, 0)) }

    fun previousKey(state: EditorState) {
        state.playing = false
        keyFrames(state).filter { it < state.frame }.maxOrNull()?.let { state.frame = it }
    }

    fun nextKey(state: EditorState) {
        state.playing = false
        keyFrames(state).filter { it > state.frame }.minOrNull()?.let { state.frame = it }
    }
}
