package app.absentmotion.desk

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PointMode
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.ui.editor.DrawingKeys
import app.absentmotion.ui.home.DeskModals

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
    private val mac = DeskOs.mac
    private val windows = DeskOs.windows
    private val down = HashMap<Long, Long>()
    private const val STALE_NS = 2_500_000_000L

    internal fun commandKey(mac: Boolean, windows: Boolean, ctrl: Boolean, meta: Boolean, alt: Boolean): Boolean =
        if (mac) meta else ctrl && !(windows && alt)

    internal fun altGr(windows: Boolean, ctrl: Boolean, alt: Boolean): Boolean = windows && ctrl && alt

    fun isCommand(event: KeyEvent): Boolean = commandKey(mac, windows, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed)

    internal fun bindable(mac: Boolean, windows: Boolean, ctrl: Boolean, meta: Boolean, alt: Boolean): Boolean =
        !(if (mac) ctrl else meta) && !altGr(windows, ctrl, alt)

    fun bindable(event: KeyEvent): Boolean = bindable(mac, windows, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed)

    fun released(event: KeyEvent) {
        if (event.type == KeyEventType.KeyUp) down.remove(event.key.keyCode)
    }

    fun releaseAll() = down.clear()

    private fun fresh(key: Key): Boolean {
        val now = System.nanoTime()
        val before = down.put(key.keyCode, now)
        return before == null || now - before > STALE_NS
    }

    private fun modal(state: EditorState): Boolean =
        DeskModals.open > 0 || state.exportOpen || state.effectBrowserOpen || state.historyOpen || state.presetsOpen || state.cameraOpen ||
            (state.drawing != null && state.drawing !is PointMode) || state.fullColor != null || state.keypad != null || state.easingHint || state.ungroupAsk ||
            app.absentmotion.ui.home.EmbedRequest.templateId != null

    private val pointModeKeys = setOf(Shortcut.PlayPause, Shortcut.PreviousFrame, Shortcut.NextFrame, Shortcut.Back10, Shortcut.Forward10, Shortcut.GoStart, Shortcut.GoEnd,
        Shortcut.PreviousBookmark, Shortcut.NextBookmark, Shortcut.PreviousKeyframe, Shortcut.NextKeyframe, Shortcut.TimelineFit, Shortcut.TimelineZoomIn,
        Shortcut.TimelineZoomOut, Shortcut.Undo, Shortcut.Redo)

    var viewer: ((KeyEvent) -> Boolean)?
        get() = DeskShortcuts.viewer
        set(value) { DeskShortcuts.viewer = value }

    fun handle(app: DeskAppState, event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown && viewer?.invoke(event) == true) return true
        app.editor?.let { if (DeskTyping.active == 0 && DrawingKeys.handle(it, event)) { released(event); return true } }
        if (event.type == KeyEventType.KeyUp) {
            released(event)
            if (viewer != null && event.key == Key.Spacebar) return true
            val open = app.editor?.let { !modal(it) } == true
            return open && DeskTyping.active == 0 && event.key == Key.Spacebar && !isCommand(event) && !event.isAltPressed
        }
        if (event.type != KeyEventType.KeyDown) return false
        if (DeskTyping.active == 0 && DeskDrop.browserKey?.invoke(event) == true) return true
        val first = fresh(event.key)
        val state = app.editor ?: return false
        if (modal(state) || !bindable(event) || DeskTyping.active > 0) return false
        val action = DeskShortcuts.find(DeskShortcuts.chord(event)) { !it.global } ?: return false
        if (state.drawing is PointMode && action !in pointModeKeys) return false
        if (!first && !action.repeats) return true
        return run(app, state, action)
    }

    internal fun run(app: DeskAppState, state: EditorState, action: Shortcut): Boolean = DeskShortcuts.run(state, action, app::closeSoon)
}
