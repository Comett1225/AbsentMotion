package app.absentmotion.desk

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
import app.absentmotion.editor.deselectLayer
import app.absentmotion.editor.chosenLayers
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.ui.editor.DeskTimelineControls
import app.absentmotion.ui.editor.TimelineMath
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.pasteLayer
import app.absentmotion.editor.split
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
    private val fieldKeys = setOf(Key.A, Key.C, Key.V, Key.X, Key.Z, Key.Y)

    internal fun commandKey(mac: Boolean, windows: Boolean, ctrl: Boolean, meta: Boolean, alt: Boolean): Boolean =
        if (mac) meta else ctrl && !(windows && alt)

    internal fun altGr(windows: Boolean, ctrl: Boolean, alt: Boolean): Boolean = windows && ctrl && alt

    fun isCommand(event: KeyEvent): Boolean = commandKey(mac, windows, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed)

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
            state.drawing != null || state.fullColor != null || state.keypad != null || state.easingHint || state.ungroupAsk ||
            app.absentmotion.ui.home.EmbedRequest.templateId != null

    var viewer: ((KeyEvent) -> Boolean)? = null

    fun handle(app: DeskAppState, event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown && viewer?.invoke(event) == true) return true
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
        if (modal(state) || altGr(windows, event.isCtrlPressed, event.isAltPressed)) return false
        val command = isCommand(event)
        if (DeskTyping.active > 0 && (!command || event.key in fieldKeys)) return false
        if (command && !event.isShiftPressed && (event.key == Key.One || event.key == Key.Two)) DeskDrop.panelKey?.let { it(event.key == Key.One); return true }
        val shift = event.isShiftPressed
        val last = state.scene.lastFrame
        fun step(by: Int) { state.playing = false; state.frame = (state.frame + by).coerceIn(0, last) }
        fun nudge(by: Int) {
            val layers = state.chosenLayers
            val firstLayer = layers.firstOrNull() ?: return
            val earliest = layers.minOf { state.scene.firstShownFrame(it.startTime) }
            val delta = by.coerceAtLeast(-earliest)
            val frame = state.scene.firstShownFrame(firstLayer.startTime) + delta
            if (delta != 0) state.edit { TimelineMath.movedClips(it, firstLayer, layers.drop(1), frame) }
        }
        when {
            event.isAltPressed && event.key == Key.DirectionLeft && !command -> DeskJump.previous(state)
            event.isAltPressed && event.key == Key.DirectionRight && !command -> DeskJump.next(state)
            event.key == Key.Comma && !command && !event.isAltPressed -> nudge(if (shift) -10 else -1)
            event.key == Key.Period && !command && !event.isAltPressed -> nudge(if (shift) 10 else 1)
            command && event.key == Key.A -> if (first) { state.deselectLayer(); MultiSelect.ids = state.scene.layers.map { it.id }.toSet(); MultiSelect.active = MultiSelect.ids.isNotEmpty() }
            !command && shift && event.key == Key.Z -> DeskTimelineControls.fit?.let { if (first) it() } ?: return false
            !command && (event.key == Key.Plus || event.key == Key.Equals || event.key == Key.NumPadAdd) -> DeskTimelineControls.zoom?.invoke(1.25f) ?: return false
            !command && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> DeskTimelineControls.zoom?.invoke(0.8f) ?: return false
            event.key == Key.Spacebar && !command && !event.isAltPressed -> if (first) state.playing = !state.playing
            event.key == Key.DirectionLeft && !command -> step(if (shift) -10 else -1)
            event.key == Key.DirectionRight && !command -> step(if (shift) 10 else 1)
            event.key == Key.MoveHome -> { state.playing = false; state.frame = 0 }
            event.key == Key.MoveEnd -> { state.playing = false; state.frame = last }
            command && event.key == Key.Z && !shift -> if (state.canUndo) state.undo()
            command && (event.key == Key.Z && shift || event.key == Key.Y) -> if (state.canRedo) state.redo()
            (event.key == Key.Delete || event.key == Key.Backspace) && !command && DeskDrop.removeHovered != null -> { if (first) DeskDrop.removeHovered?.invoke() }
            (event.key == Key.Delete || event.key == Key.Backspace) && !command -> if (MultiSelect.active || state.selectedId != null) { if (first) { if (MultiSelect.active) state.deleteChosen() else state.deleteSelected() } } else return false
            command && event.key == Key.D -> if (MultiSelect.active || state.selectedId != null) { if (first) { if (MultiSelect.active) state.duplicateChosen() else state.duplicateSelected() } } else return false
            command && event.key == Key.C -> if (state.chosenLayers.isNotEmpty()) { if (first) state.copySelected() } else return false
            command && event.key == Key.X -> if (state.chosenLayers.isNotEmpty()) { if (first) { state.copySelected(); if (MultiSelect.active) state.deleteChosen() else state.deleteSelected() } } else return false
            command && event.key == Key.V -> if (first) state.pasteLayer()
            command && event.key == Key.B -> if (MultiSelect.active || state.selectedId != null) { if (first) state.split() } else return false
            command && event.key == Key.E -> if (first) { state.playing = false; state.exportOpen = true }
            command && event.key == Key.W -> if (first) app.closeSoon()
            else -> return false
        }
        return true
    }
}
