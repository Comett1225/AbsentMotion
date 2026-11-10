package app.absentmotion.ui.editor

import kotlinx.coroutines.flow.drop
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.stepBack
import app.absentmotion.editor.ungroupSelected
import app.absentmotion.ui.editor.panels.FullColorPicker
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import androidx.compose.animation.core.tween
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.presence
import app.absentmotion.editor.FullColor
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.tutorial.tutorialGuard
import app.absentmotion.ui.tutorial.tutorialTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

internal val LocalEditorScope = staticCompositionLocalOf<CoroutineScope> { error("outside the editor") }

@Composable
fun EditorScreen(state: EditorState, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalEditorScope provides scope, LocalDeskEditor provides app.absentmotion.platform.PlatformFeatures.desktop) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    app.absentmotion.editor.PreviewCache.interaction()
                }
            }
        }) {
            EditorBody(state, onExit)
            val color = remember { arrayOfNulls<FullColor>(1) }.also { if (state.fullColor != null) it[0] = state.fullColor }[0]
            Presence(state.fullColor != null) { color?.let { PhoneScreen(onDismiss = { state.endGesture(); state.fullColor = null }, title = stringResource(R.string.color), onConfirm = { state.endGesture(); state.fullColor = null }) { Box(Modifier.fillMaxSize().presence(rise = 16.dp)) { FullColorPicker(state, it) } } } }
            if (state.cameraOpen) EditorCamera(state, scope)
        }
    }
}

@Composable
private fun EditorBody(state: EditorState, onExit: () -> Unit) {
    val wide = LocalWide.current
    BackHandler { if (state.previewPanelOpen) state.previewPanelOpen = false else if (!state.stepBack() && !app.absentmotion.platform.PlatformFeatures.desktop) onExit() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.endGesture() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!app.absentmotion.platform.PlatformFeatures.desktop && !wide && state.panel == EditorPanel.EditText && state.selected?.kind == "text") {
        PhoneScreen(plain = true) { TextEditor(state) }
        return
    }
    if (!app.absentmotion.platform.PlatformFeatures.desktop && state.drawing != null && state.drawing !is app.absentmotion.editor.PointMode) {
        PhoneScreen(fillWidth = Dp.Infinity) { DrawingEditor(state) }
        return
    }
    PointSession(state)
    val addLibrary = rememberAddLibrary(state.settings.demoMode)
    Playback(state)
    LaunchedEffect(state.playing) { state.engine.setPlaying(state.playing) }
    LaunchedEffect(state) { app.absentmotion.media.ModelDetail.budget.drop(1).collect { state.engine.refreshMedia() } }
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
    if (state.ungroupAsk) androidx.compose.material3.AlertDialog(
        onDismissRequest = { state.ungroupAsk = false },
        text = { Text(stringResource(R.string.ungroup_loses_style), color = Am.OnDark) },
        confirmButton = { androidx.compose.material3.TextButton({ state.ungroupAsk = false; state.ungroupSelected() }) { Text(stringResource(R.string.layer_tool_ungroup), color = Am.Accent) } },
        dismissButton = { androidx.compose.material3.TextButton({ state.ungroupAsk = false }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field,
    )
    state.pointsAsk?.let { go ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { state.pointsAsk = null },
            text = { Text(stringResource(R.string.shape_points_lose_animation), color = Am.OnDark) },
            confirmButton = { androidx.compose.material3.TextButton({ state.pointsAsk = null; go() }) { Text(stringResource(R.string.action_continue), color = Am.Accent) } },
            dismissButton = { androidx.compose.material3.TextButton({ state.pointsAsk = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
            containerColor = Am.Field,
        )
    }
    if (app.absentmotion.platform.PlatformFeatures.desktop) {
        DeskWorkspace(state, onExit, addLibrary)
        return
    }
    Box(Modifier.fillMaxSize()) {
        if (wide) WideEditorBody(state, onExit, addLibrary) else PhoneEditorBody(state, onExit, addLibrary)
        SlideOver(state.effectBrowserOpen) { PhoneScreen(fillWidth = 720.dp) { app.absentmotion.ui.editor.effects.EffectBrowser(state) } }
        SlideOver(state.presetsOpen) { PhoneScreen { PresetScreen(state) } }
        SlideOver(state.toolOpen != 0) { PhoneScreen(fillWidth = 720.dp) { EditorTools(state, LocalEditorScope.current) } }
    }
}

@Composable
internal fun EditorTools(state: EditorState, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var shown by remember { mutableIntStateOf(state.toolOpen) }
    if (state.toolOpen != 0) shown = state.toolOpen
    var compressFrom by remember { mutableStateOf<android.net.Uri?>(null) }
    val close = { state.toolOpen = 0 }
    val add: (android.net.Uri) -> Unit = { uri ->
        state.toolOpen = 0
        val sound = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase() in SoundFiles
        scope.launch { if (sound) state.addSounds(context, listOf(uri)) else state.addPicked(context, uri) }
    }
    androidx.compose.runtime.CompositionLocalProvider(app.absentmotion.ui.home.LocalAddToProject provides add) {
        Box(Modifier.fillMaxSize().background(Am.HomeBg)) {
            when (shown) {
                1 -> app.absentmotion.ui.home.CompressScreen(compressFrom) { close() }
                2 -> app.absentmotion.ui.home.DownloaderScreen(state.store, state.settings, {}, { uri -> compressFrom = uri; state.toolOpen = 1 }) { close() }
                3 -> app.absentmotion.ui.home.TrimScreen { close() }
                4 -> app.absentmotion.ui.home.ExtractAudioScreen { close() }
                5 -> app.absentmotion.ui.home.GifScreen { close() }
            }
        }
    }
}

private val SoundFiles = setOf("m4a", "mp3", "wav", "aac", "ogg", "opus", "flac")

@Composable
private fun SlideOver(open: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        open,
        enter = androidx.compose.animation.slideInHorizontally(tween(320, easing = Motion.EaseOut)) { it },
        exit = androidx.compose.animation.slideOutHorizontally(tween(260, easing = Motion.EaseIn)) { it },
    ) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {}) { content() }
    }
}

@Composable
private fun PhoneEditorBody(state: EditorState, onExit: () -> Unit, addLibrary: AddLibrary) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding().imePadding()) {
            val available = maxHeight
            Column(Modifier.fillMaxSize()) {
                EditorTopBar(state, onExit, Modifier.fillMaxWidth().height(42.dp).enterFade().tutorialGuard(any = true))
                Box(Modifier.fillMaxWidth().weight(1f).tutorialTarget("preview")) {
                    Preview(state, Modifier.fillMaxSize())
                    (state.drawing as? app.absentmotion.editor.PointMode)?.let { PointOverlay(state, it, Modifier.fillMaxSize()) }
                    Presence(state.previewPanelOpen) { PreviewModePanel(state, Modifier.align(Alignment.CenterEnd).presence(slideX = 0.4f).tutorialGuard(any = true)) }
                    AnimateButton(state, Modifier.align(Alignment.BottomEnd))
                    if (state.settings.transitionInPreview) TransitionButton(state, Modifier.align(Alignment.BottomStart).padding(12.dp).tutorialTarget("transition"))
                    HistoryNotes(state, Modifier.align(Alignment.BottomCenter))
                }
                Box(Modifier.fillMaxWidth().height(46.dp).enterFade().tutorialTarget("playbar")) {
                    PlayBar(state, Modifier.fillMaxSize().tutorialGuard(any = true))
                    LevelMeter(state, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.1.dp))
                }
                val screen = LocalConfiguration.current
                val wanted = if (isTablet(screen.smallestScreenWidthDp)) maxOf(280, screen.screenHeightDp * 2 / 5).dp else 280.dp
                val lower = lowerPanelHeight(available, wanted)
                val scroll = androidx.compose.foundation.rememberScrollState()
                LaunchedEffect(scroll) { androidx.compose.runtime.snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
                Box(Modifier.fillMaxWidth().height(lower).enterFade().background(Am.TimelineBg).tutorialTarget("timeline").verticalScroll(scroll, enabled = lower < wanted)) {
                    Box(Modifier.fillMaxWidth().height(wanted)) {
                        Timeline(state, Modifier.fillMaxSize())
                        LowerPanels(state, addLibrary)
                    }
                }
            }
        }
}

internal fun lowerPanelHeight(available: Dp, wanted: Dp): Dp = minOf(wanted, maxOf(available - EditorBars - MinPreview, MinLower))

private val EditorBars = 88.dp
private val MinPreview = 120.dp
private val MinLower = 140.dp

@Composable
internal fun LowerPanels(state: EditorState, addLibrary: AddLibrary, top: Boolean = false, transitionHere: Boolean = true) {
    Swap(
        when {
            state.drawing is app.absentmotion.editor.PointMode -> "points"
            state.addOpen -> "add"
            MultiSelect.active -> "multi"
            state.selectedId == null -> "button"
            state.panel == EditorPanel.Tools -> if (state.volumeOpen) "volume" to state.easingOpen else "tools"
            else -> state.panel to state.easingOpen
        },
        Modifier.fillMaxSize(),
    ) {
        val lower = Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth().pointerInput(Unit) {}.tutorialGuard(any = true)
        val tall = top && !state.easingOpen && (state.panel == EditorPanel.Blending || state.panel == EditorPanel.Effects)
        when {
            state.drawing is app.absentmotion.editor.PointMode -> Box(lower.height(PanelHeight).background(Am.Panel).tutorialTarget("panel")) { (state.drawing as? app.absentmotion.editor.PointMode)?.let { PointPanel(state, it) } }
            state.addOpen -> AddPopup(state, lower.height(AddPopupHeight).tutorialTarget("add_panel"), addLibrary)
            MultiSelect.active -> MultiBar(state, lower.height(48.dp))
            state.selectedId == null -> {
                if (transitionHere && !state.settings.transitionInPreview) TransitionButton(state, Modifier.align(Alignment.BottomEnd).padding(bottom = 58.dp).tutorialTarget("transition"), fromEnd = true)
                AddButton(state, Modifier.align(Alignment.BottomEnd).tutorialTarget("add"))
            }
            state.volumeOpen && state.panel == EditorPanel.Tools -> key(state.selectedId) { VolumePanel(state, lower.height(PanelHeight).tutorialTarget("panel")) }
            state.panel == EditorPanel.Tools -> LayerTools(state, (if (top) lower else lower.height(PanelHeight)).tutorialTarget("tools"), side = top)
            else -> LayerPanel(state, (if (tall) lower.fillMaxHeight() else lower.height(PanelHeight)).tutorialTarget("panel"))
        }
    }
    Swap(state.keypad, Modifier.fillMaxSize(), rise = 0.dp) {
        state.keypad?.let { ValueKeypad(it, onClose = { state.keypad = null }, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Playback(state: EditorState) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        if (state.frame >= state.scene.lastFrame) state.frame = 0
        state.engine.timing()
        val audio = state.engine.audio
        audio?.quality = state.settings.audioResampling
        try {
            var startFrame = state.frame
            var startMs = state.scene.frameStart(startFrame)
            var audible = audio?.play(startMs.toDouble()) == true
            var startNanos = withFrameNanos { it }
            var shown = startFrame
            while (state.playing) {
                val now = withFrameNanos { it }
                if (audio?.interrupted == true) { state.playing = false; break }
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
                if (frame > state.scene.lastFrame) {
                    if (state.settings.loopingPlay && state.scene.lastFrame > 0) {
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
            withContext(NonCancellable) { state.engine.timing().takeUnless { "\"samples\":0," in it || it == "{}" }?.let { Log.i("AMPlay", it) } }
        }
    }
}
