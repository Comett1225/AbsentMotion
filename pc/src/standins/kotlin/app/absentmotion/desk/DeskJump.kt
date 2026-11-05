package app.absentmotion.desk

import app.absentmotion.editor.EditorState

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
        state.frame = nextFrame(frames(state), state.frame, last).coerceIn(0, last)
    }
}
