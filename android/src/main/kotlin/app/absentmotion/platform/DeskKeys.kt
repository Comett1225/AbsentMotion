package app.absentmotion.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.editor.EditorState
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

    fun handle(state: EditorState, event: KeyEvent, close: () -> Unit): Boolean {
        if (!deskLayout) return false
        if (event.type == KeyEventType.KeyDown && DeskShortcuts.viewer?.invoke(event) == true) return true
        if (DeskTyping.active == 0 && app.absentmotion.ui.editor.DrawingKeys.handle(state, event)) return true
        if (event.type != KeyEventType.KeyDown) return false
        if (DeskTyping.active > 0 || modal(state)) return false
        val action = DeskShortcuts.find(DeskShortcuts.chord(event)) { !it.global } ?: return false
        if (event.nativeKeyEvent.repeatCount > 0 && !action.repeats) return true
        return DeskShortcuts.run(state, action, close)
    }
}
