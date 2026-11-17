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
import app.absentmotion.editor.copyKeyframes
import app.absentmotion.editor.pasteKeyframes
import app.absentmotion.editor.addKeyframe
import app.absentmotion.editor.toggleBookmark
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
        if (modal(state) || altGr(windows, event.isCtrlPressed, event.isAltPressed) || DeskTyping.active > 0) return false
        val action = DeskShortcuts.find(DeskShortcuts.chord(event)) { !it.global } ?: return false
        if (!first && !action.repeats) return true
        return run(app, state, action)
    }

    internal fun run(app: DeskAppState, state: EditorState, action: Shortcut): Boolean {
        val picked = MultiSelect.active || state.selectedId != null
        fun step(by: Int) { state.playing = false; state.stepTo(state.playheadFrame + by) }
        fun nudge(by: Int) {
            val layers = state.chosenLayers
            val firstLayer = layers.firstOrNull() ?: return
            val earliest = layers.minOf { state.scene.firstShownFrame(it.startTime) }
            val delta = by.coerceAtLeast(-earliest)
            val frame = state.scene.firstShownFrame(firstLayer.startTime) + delta
            if (delta != 0) state.edit { TimelineMath.movedClips(it, firstLayer, layers.drop(1), frame) }
        }
        fun delete() { if (MultiSelect.active) state.deleteChosen() else state.deleteSelected() }
        when (action) {
            Shortcut.PlayPause -> state.playing = !state.playing
            Shortcut.PreviousFrame -> step(-1)
            Shortcut.NextFrame -> step(1)
            Shortcut.Back10 -> step(-10)
            Shortcut.Forward10 -> step(10)
            Shortcut.GoStart -> { state.playing = false; state.frame = 0 }
            Shortcut.GoEnd -> { state.playing = false; state.parkAtEnd() }
            Shortcut.PreviousBookmark -> DeskJump.previous(state)
            Shortcut.NextBookmark -> DeskJump.next(state)
            Shortcut.PreviousKeyframe -> DeskJump.previousKey(state)
            Shortcut.NextKeyframe -> DeskJump.nextKey(state)
            Shortcut.NudgeEarlier -> nudge(-1)
            Shortcut.NudgeLater -> nudge(1)
            Shortcut.NudgeEarlier10 -> nudge(-10)
            Shortcut.NudgeLater10 -> nudge(10)
            Shortcut.SelectAll -> { state.deselectLayer(); MultiSelect.ids = state.scene.layers.map { it.id }.toSet(); MultiSelect.active = MultiSelect.ids.isNotEmpty() }
            Shortcut.TimelineFit -> DeskTimelineControls.fit?.invoke() ?: return false
            Shortcut.TimelineZoomIn -> DeskTimelineControls.zoom?.invoke(1.25f) ?: return false
            Shortcut.TimelineZoomOut -> DeskTimelineControls.zoom?.invoke(0.8f) ?: return false
            Shortcut.Undo -> if (state.canUndo) state.undo()
            Shortcut.Redo -> if (state.canRedo) state.redo()
            Shortcut.Delete -> DeskDrop.removeHovered?.invoke() ?: if (picked) delete() else return false
            Shortcut.Duplicate -> if (picked) { if (MultiSelect.active) state.duplicateChosen() else state.duplicateSelected() } else return false
            Shortcut.Copy -> if (state.chosenLayers.isNotEmpty()) state.copySelected() else return false
            Shortcut.Cut -> if (state.chosenLayers.isNotEmpty()) { state.copySelected(); delete() } else return false
            Shortcut.Paste -> state.pasteLayer()
            Shortcut.Split -> if (picked) state.split() else return false
            Shortcut.CopyKeyframes -> return state.copyKeyframes()
            Shortcut.PasteKeyframes -> return state.pasteKeyframes()
            Shortcut.AddKeyframe -> return state.addKeyframe()
            Shortcut.AddBookmark -> state.toggleBookmark()
            Shortcut.Library, Shortcut.Inspector -> DeskDrop.panelKey?.invoke(action == Shortcut.Library) ?: return false
            Shortcut.Export -> { state.playing = false; state.exportOpen = true }
            Shortcut.CloseProject -> app.closeSoon()
            else -> return false
        }
        return true
    }
}
