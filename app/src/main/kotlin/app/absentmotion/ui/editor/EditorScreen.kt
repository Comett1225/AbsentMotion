package app.absentmotion.ui.editor

import app.absentmotion.platform.Log
import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.stepBack
import app.absentmotion.ui.editor.panels.FullColorPicker
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.enterFade
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
fun EditorScreen(state: EditorState, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        EditorBody(state, onExit)
        state.fullColor?.let { PhoneScreen { Box(Modifier.fillMaxSize().enterFade()) { FullColorPicker(state, it) } } }
        if (state.cameraOpen) EditorCamera(state, scope)
    }
}

@Composable
private fun EditorBody(state: EditorState, onExit: () -> Unit) {
    val wide = LocalWide.current
    BackHandler { if (state.previewPanelOpen) state.previewPanelOpen = false else if (!state.stepBack()) onExit() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.endGesture() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (state.panel == EditorPanel.EditText && state.selected?.kind == "text") {
        PhoneScreen { TextEditor(state) }
        return
    }
    if (state.drawing != null) {
        PhoneScreen { DrawingEditor(state) }
        return
    }
    val addLibrary = rememberAddLibrary(state.settings.demoMode)
    Playback(state)
    LaunchedEffect(state.playing) { state.engine.setPlaying(state.playing) }
    LaunchedEffect(state.panel, state.selectedId) {
        state.easingOpen = false
        state.keypad = null
        if (state.panel == EditorPanel.Tools) state.keyTarget = null
    }
    if (state.easingHint) AlertDialog(
        onDismissRequest = { state.easingHint = false }, title = { Text(stringResource(R.string.add_keyframes_first)) },
        text = { Text(stringResource(R.string.easing_needs_at_least_two_keyframes)) },
        confirmButton = { TextButton({ state.easingHint = false }) { Text(stringResource(R.string.ok), color = Am.Accent) } },
        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
    )
    if (state.effectBrowserOpen) {
        PhoneScreen { Box(Modifier.fillMaxSize().enterFade()) { app.absentmotion.ui.editor.effects.EffectBrowser(state) } }
        return
    }
    if (state.presetsOpen) {
        PhoneScreen { Box(Modifier.fillMaxSize().enterFade()) { PresetScreen(state) } }
        return
    }
    if (wide) {
        WideEditorBody(state, onExit, addLibrary)
        return
    }
    Column(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding().imePadding()) {
        EditorTopBar(state, onExit, Modifier.fillMaxWidth().height(42.dp).enterFade())
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Preview(state, Modifier.fillMaxSize())
            if (state.previewPanelOpen) PreviewModePanel(state, Modifier.align(Alignment.CenterEnd).enterFade())
        }
        Box(Modifier.fillMaxWidth().height(46.dp).enterFade()) {
            PlayBar(state, Modifier.fillMaxSize())
            LevelMeter(state, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.1.dp))
        }
        Box(Modifier.fillMaxWidth().height(280.dp).enterFade().background(Am.TimelineBg)) {
            Timeline(state, Modifier.fillMaxSize())
            LowerPanels(state, addLibrary)
        }
    }
}

@Composable
internal fun LowerPanels(state: EditorState, addLibrary: AddLibrary, top: Boolean = false) {
    Swap(
        when {
            state.addOpen -> "add"
            MultiSelect.active -> "multi"
            state.selectedId == null -> "button"
            state.panel == EditorPanel.Tools -> "tools"
            else -> state.panel to state.easingOpen
        },
        Modifier.fillMaxSize(),
    ) {
        val lower = Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth().pointerInput(Unit) {}
        val tall = top && !state.easingOpen && (state.panel == EditorPanel.Blending || state.panel == EditorPanel.Effects)
        when {
            state.addOpen -> AddPopup(state, lower.height(251.dp), addLibrary)
            MultiSelect.active -> MultiBar(state, lower.height(48.dp))
            state.selectedId == null -> {
                TransitionButton(state, Modifier.align(Alignment.BottomStart))
                AddButton(state, Modifier.align(Alignment.BottomEnd))
            }
            state.volumeOpen && state.panel == EditorPanel.Tools -> key(state.selectedId) { VolumePanel(state, lower.height(PanelHeight)) }
            state.panel == EditorPanel.Tools -> LayerTools(state, lower.height(PanelHeight))
            else -> LayerPanel(state, if (tall) lower.fillMaxHeight() else lower.height(PanelHeight))
        }
    }
    Swap(state.keypad, Modifier.fillMaxSize(), rise = 0.dp) {
        state.keypad?.let { ValueKeypad(it, onClose = { state.keypad = null }, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Playback(state: EditorState) {
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        if (state.frame >= state.scene.lastFrame) state.frame = 0
        state.engine.timing()
        val audio = state.engine.audio
        audio?.quality = state.settings.audioResampling
        var startFrame = state.frame
        var startMs = state.scene.frameStart(startFrame)
        var audible = audio?.play(startMs.toDouble()) == true
        var startNanos = withFrameNanos { it }
        var shown = startFrame
        try {
            while (state.playing) {
                val now = withFrameNanos { it }
                if (state.frame != shown) {
                    startFrame = state.frame
                    startMs = state.scene.frameStart(startFrame)
                    startNanos = now
                    audible = audio?.play(startMs.toDouble()) == true
                }
                val fps = state.scene.fps
                val frame = if (audible) {
                    val ms = audio?.positionMs() ?: startMs.toDouble()
                    startFrame + ((ms - startMs).coerceAtLeast(0.0) * fps / 1000.0 + 0.25).toInt()
                } else {
                    startFrame + (((now - startNanos) * fps + 250_000_000L) / 1_000_000_000L).toInt()
                }
                if (frame >= state.scene.lastFrame) {
                    if (state.settings.loopingPlay) {
                        startFrame = 0
                        startMs = state.scene.frameStart(0)
                        startNanos = now
                        audible = audio?.play(startMs.toDouble()) == true
                        state.frame = 0
                    } else {
                        state.frame = state.scene.lastFrame
                        state.playing = false
                    }
                } else {
                    state.frame = maxOf(frame, startFrame)
                }
                shown = state.frame
                audio?.frameShown(shown, state.scene.frameStart(shown))
            }
        } finally {
            audio?.stop()
            withContext(NonCancellable) { Log.i("AMPlay", state.engine.timing()) }
        }
    }
}
