package app.absentmotion.desk

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.split

object DeskKeys {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    fun handle(app: DeskAppState, event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val state = app.editor ?: return false
        if (state.exportOpen || state.effectBrowserOpen || state.historyOpen) return false
        val command = if (mac) event.isMetaPressed else event.isCtrlPressed
        val shift = event.isShiftPressed
        val last = state.scene.lastFrame
        fun step(by: Int) { state.playing = false; state.frame = (state.frame + by).coerceIn(0, last) }
        when {
            event.key == Key.Spacebar && !command -> state.playing = !state.playing
            event.key == Key.DirectionLeft && !command -> step(if (shift) -10 else -1)
            event.key == Key.DirectionRight && !command -> step(if (shift) 10 else 1)
            event.key == Key.MoveHome -> { state.playing = false; state.frame = 0 }
            event.key == Key.MoveEnd -> { state.playing = false; state.frame = last }
            command && event.key == Key.Z && !shift -> if (state.canUndo) state.undo()
            command && (event.key == Key.Z && shift || event.key == Key.Y) -> if (state.canRedo) state.redo()
            (event.key == Key.Delete || event.key == Key.Backspace) && !command -> if (MultiSelect.active) state.deleteChosen() else if (state.selectedId != null) state.deleteSelected() else return false
            command && event.key == Key.D -> if (MultiSelect.active) state.duplicateChosen() else if (state.selectedId != null) state.duplicateSelected() else return false
            command && event.key == Key.C -> if (state.selectedId != null) state.copySelected() else return false
            command && event.key == Key.V -> state.pasteLayer()
            command && event.key == Key.B -> if (state.selectedId != null) state.split() else return false
            command && event.key == Key.E -> { state.playing = false; state.exportOpen = true }
            else -> return false
        }
        return true
    }
}
