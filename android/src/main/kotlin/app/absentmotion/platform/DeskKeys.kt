package app.absentmotion.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.deselectLayer
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.split
import app.absentmotion.ui.adaptive.deskLayout

@OptIn(ExperimentalComposeUiApi::class)
object DeskTyping {
    var active = 0
        private set

    private object Counter : PlatformTextInputInterceptor {
        override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest, nextHandler: PlatformTextInputSession): Nothing {
            active++
            try { nextHandler.startInputMethod(request) } finally { active-- }
        }
    }

    @Composable
    fun Track(content: @Composable () -> Unit) = InterceptPlatformTextInput(Counter, content)
}

object DeskKeys {
    private fun modal(state: EditorState): Boolean =
        state.exportOpen || state.effectBrowserOpen || state.historyOpen || state.presetsOpen || state.cameraOpen || state.toolOpen != 0 ||
            state.drawing != null || state.fullColor != null || state.keypad != null || state.easingHint || state.ungroupAsk ||
            app.absentmotion.ui.home.DeskModals.open > 0 || app.absentmotion.ui.home.EmbedRequest.templateId != null

    fun handle(state: EditorState, event: KeyEvent): Boolean {
        if (!deskLayout || event.type != KeyEventType.KeyDown || DeskTyping.active > 0 || modal(state)) return false
        val command = event.isCtrlPressed || event.isMetaPressed
        if (event.isAltPressed) return false
        if (event.nativeKeyEvent.repeatCount > 0 && event.key !in setOf(Key.DirectionLeft, Key.DirectionRight, Key.Z, Key.Y))
            return event.key in if (command) setOf(Key.C, Key.X, Key.V, Key.D, Key.B, Key.A, Key.E, Key.One, Key.Two) else setOf(Key.Spacebar, Key.MoveHome, Key.MoveEnd, Key.Delete, Key.Backspace)
        val shift = event.isShiftPressed
        val picked = MultiSelect.active || state.selectedId != null
        fun step(by: Int) { state.playing = false; state.stepTo(state.playheadFrame + by) }
        fun delete() { if (MultiSelect.active) state.deleteChosen() else state.deleteSelected() }
        if (command) {
            when (event.key) {
                Key.Z -> if (shift) { if (state.canRedo) state.redo() } else if (state.canUndo) state.undo()
                Key.Y -> if (state.canRedo) state.redo()
                Key.C -> if (state.chosenLayers.isNotEmpty()) state.copySelected() else return false
                Key.X -> if (state.chosenLayers.isNotEmpty()) { state.copySelected(); delete() } else return false
                Key.V -> state.pasteLayer()
                Key.D -> if (picked) { if (MultiSelect.active) state.duplicateChosen() else state.duplicateSelected() } else return false
                Key.B -> if (picked) state.split() else return false
                Key.A -> { state.deselectLayer(); MultiSelect.ids = state.scene.layers.map { it.id }.toSet(); MultiSelect.active = MultiSelect.ids.isNotEmpty() }
                Key.E -> { state.playing = false; state.exportOpen = true }
                Key.One, Key.Two -> app.absentmotion.ui.editor.DeskDrop.panelKey?.invoke(event.key == Key.One) ?: return false
                else -> return false
            }
            return true
        }
        when (event.key) {
            Key.Spacebar -> state.togglePlayback()
            Key.DirectionLeft -> step(if (shift) -10 else -1)
            Key.DirectionRight -> step(if (shift) 10 else 1)
            Key.MoveHome -> { state.playing = false; state.frame = 0 }
            Key.MoveEnd -> { state.playing = false; state.parkAtEnd() }
            Key.Delete, Key.Backspace -> if (picked) delete() else return false
            else -> return false
        }
        return true
    }
}
