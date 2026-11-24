package app.absentmotion.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.desk.Shortcut
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FreehandMode
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.deletePoint
import app.absentmotion.editor.pointContours
import app.absentmotion.model.DrawTool
import app.absentmotion.model.Handle
import app.absentmotion.ui.home.DeskModals

object DrawingKeys {
    var space by mutableStateOf(false)

    private val digits = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine, Key.Zero)

    fun handle(state: EditorState, event: KeyEvent): Boolean {
        val mode = state.drawing
        if (mode !is FreehandMode && mode !is PointMode) { space = false; return false }
        if (DeskModals.open > (if (mode is FreehandMode) 1 else 0) || state.keypad != null || state.fullColor != null || !DeskShortcuts.bindable(event)) return false
        val chord = DeskShortcuts.chord(event)
        val plain = !chord.command && !chord.alt && !event.isCtrlPressed && !event.isMetaPressed
        val down = event.type == KeyEventType.KeyDown
        if (mode is FreehandMode && event.key == Key.Spacebar && plain) { space = down; return true }
        if (!down) return false
        if (chord.alt && !chord.command) when (event.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> { state.zoomStep(true); return true }
            Key.Minus, Key.NumPadSubtract -> { state.zoomStep(false); return true }
            Key.Zero, Key.NumPad0 -> { state.resetCanvasView(); return true }
        }
        if (mode is PointMode) return plain && pointKey(state, mode, event.key)
        when (DeskShortcuts.find(chord) { it == Shortcut.Undo || it == Shortcut.Redo }) {
            Shortcut.Undo -> { if (state.canUndo) state.undo(); return true }
            Shortcut.Redo -> { if (state.canRedo) state.redo(); return true }
            else -> {}
        }
        if (!plain) return false
        mode as FreehandMode
        when (event.key) {
            Key.LeftBracket, Key.RightBracket -> if (chord.shift) mode.stepHardness(event.key == Key.RightBracket) else mode.stepSize(event.key == Key.RightBracket)
            Key.B -> mode.pick(DrawTool.Brush)
            Key.P -> mode.pick(DrawTool.Pen)
            Key.F -> mode.pick(DrawTool.Fill)
            Key.E -> mode.pick(DrawTool.Eraser)
            in digits -> mode.opacity((digits.indexOf(event.key) + 1) / 10f)
            else -> return false
        }
        return true
    }

    private fun pointKey(state: EditorState, mode: PointMode, key: Key): Boolean {
        val contour = state.pointContours(mode).getOrNull(mode.contour) ?: return false
        return when (key) {
            Key.Enter, Key.NumPadEnter, Key.Escape -> {
                if (mode.handle != Handle.Add || contour.knots.isEmpty()) return false
                mode.point = mode.point.coerceIn(0, contour.knots.lastIndex)
                mode.handle = Handle.Main
                true
            }
            Key.Delete, Key.Backspace -> {
                if (mode.handle == Handle.Add || mode.point !in contour.knots.indices) return false
                state.deletePoint(mode)
                true
            }
            else -> false
        }
    }
}
