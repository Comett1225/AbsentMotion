package app.absentmotion.platform

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import app.absentmotion.desk.DeskMenuHost
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.desk.DeskTyping
import app.absentmotion.desk.Shortcut
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.adaptive.deskLayout

object DeskKeys {
    private fun modal(state: EditorState): Boolean =
        state.exportOpen || state.effectBrowserOpen || state.historyOpen || state.presetsOpen || state.cameraOpen || state.toolOpen != 0 ||
            state.drawing != null || state.fullColor != null || state.keypad != null || state.easingHint || state.ungroupAsk ||
            app.absentmotion.ui.home.DeskModals.open > 0 || app.absentmotion.ui.home.EmbedRequest.templateId != null

    fun handle(state: EditorState, event: KeyEvent, close: () -> Unit): Boolean {
        if (!deskLayout && !Peripherals.keyboard) return false
        if (event.type == KeyEventType.KeyDown && DeskShortcuts.viewer?.invoke(event) == true) return true
        if (DeskTyping.active == 0 && app.absentmotion.ui.editor.DrawingKeys.handle(state, event)) return true
        if (event.type != KeyEventType.KeyDown) return false
        if (DeskTyping.active > 0 || modal(state)) return false
        val action = DeskShortcuts.find(DeskShortcuts.chord(event)) { !it.global } ?: return false
        if (event.nativeKeyEvent.repeatCount > 0 && !action.repeats) return true
        return DeskShortcuts.run(state, action, close)
    }

    fun global(event: KeyEvent, host: DeskMenuHost): Boolean {
        if (!deskLayout || event.type != KeyEventType.KeyDown || DeskTyping.active > 0) return false
        when (DeskShortcuts.find(DeskShortcuts.chord(event)) { it.global }) {
            Shortcut.Settings -> host.openSettings()
            Shortcut.NewProject -> if (host.canStartProject) host.newProject() else return false
            else -> return false
        }
        return true
    }
}
