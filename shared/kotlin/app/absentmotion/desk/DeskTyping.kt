package app.absentmotion.desk

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

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
