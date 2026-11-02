package app.absentmotion.ui.editor

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.platform.Hint
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.resizeCursor
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.PressIndication
import app.absentmotion.ui.theme.Presence

private const val RailWidth = 56
private const val InspectorRail = 44

@Composable
internal fun DeskWorkspace(state: EditorState, onExit: () -> Unit, library: AddLibrary) {
    val press = remember(Am.PressEditor) { PressIndication(Am.PressEditor, true) }
    CompositionLocalProvider(LocalIndication provides press) {
        DeskCursorHost(Modifier.fillMaxSize()) { DeskWorkspaceBody(state, onExit, library) }
    }
}

@Composable
private fun DeskWorkspaceBody(state: EditorState, onExit: () -> Unit, library: AddLibrary) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("desk-workspace", android.content.Context.MODE_PRIVATE) }
    var libraryWidth by remember { mutableFloatStateOf(prefs.getFloat("libraryWidth", 300f).coerceIn(240f, 440f)) }
    var inspectorWidth by remember { mutableFloatStateOf(prefs.getFloat("inspectorWidth", 400f).coerceIn(384f, 560f)) }
    var timelineFraction by remember { mutableFloatStateOf(prefs.getFloat("timelineFraction", .34f).coerceIn(.1f, .6f)) }
    var libraryOpen by remember { mutableStateOf(prefs.getBoolean("libraryOpen", true)) }
    var inspectorOpen by remember { mutableStateOf(prefs.getBoolean("inspectorOpen", true)) }
    var narrowLibrary by remember { mutableStateOf(false) }
    var narrowInspector by remember { mutableStateOf(false) }
    var libraryTab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var previewBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var windowWidth by remember { mutableStateOf(1200.dp) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(libraryWidth, inspectorWidth, timelineFraction, libraryOpen, inspectorOpen) {
        kotlinx.coroutines.delay(300)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            prefs.edit().putFloat("libraryWidth", libraryWidth).putFloat("inspectorWidth", inspectorWidth).putFloat("timelineFraction", timelineFraction).putBoolean("libraryOpen", libraryOpen).putBoolean("inspectorOpen", inspectorOpen).apply()
        }
    }
    LaunchedEffect(state.selectedId) { if (state.selectedId != null) narrowInspector = true }
    LaunchedEffect(state.addOpen) { if (state.addOpen) state.addOpen = false }
    val modal = settings || state.effectBrowserOpen || state.presetsOpen || state.drawing != null || state.fullColor != null || EmbedRequest.templateId != null
    LaunchedEffect(modal) { if (!modal) focusRequester.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }.background(Am.TopBar).onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed)) {
            when (it.key) {
                Key.One -> { if (windowWidth < 1100.dp) narrowLibrary = !narrowLibrary else libraryOpen = !libraryOpen; true }
                Key.Two -> { if (windowWidth < 900.dp) narrowInspector = !narrowInspector else inspectorOpen = !inspectorOpen; true }
                else -> false
            }
        } else false
    }.focusRequester(focusRequester).focusable()) {
        SideEffect { windowWidth = maxWidth }
        val heightPx = constraints.maxHeight
        val narrow = maxWidth < 1100.dp
        val narrowRight = maxWidth < 900.dp
        app.absentmotion.platform.BackHandler((narrow && narrowLibrary) || (narrowRight && narrowInspector)) {
            if (narrowRight && narrowInspector) narrowInspector = false else narrowLibrary = false
        }
        val timelineHeight = (maxHeight * timelineFraction).coerceIn(180.dp, maxHeight * .6f)
        Column(Modifier.fillMaxSize()) {
            DeskTopBar(state, onExit, { settings = true }, Modifier.fillMaxWidth().height(44.dp))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                DeskLibraryRail(libraryTab, if (narrow) narrowLibrary else libraryOpen, Modifier.width(RailWidth.dp).fillMaxHeight()) { index ->
                    val open = if (narrow) narrowLibrary else libraryOpen
                    val next = !(open && libraryTab == index)
                    libraryTab = index
                    if (narrow) { narrowLibrary = next; if (next) narrowInspector = false } else libraryOpen = next
                }
                if (!narrow && libraryOpen) {
                    DeskLibrary(state, library, libraryTab, Modifier.width(libraryWidth.dp).fillMaxHeight()) { libraryOpen = false }
                    DeskDivider(true) { delta -> libraryWidth = (libraryWidth + delta).coerceIn(240f, 440f) }
                } else DeskDivider(true, null)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { val p = it.positionInWindow(); previewBounds = Rect(p, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }) {
                        Preview(state, Modifier.fillMaxSize())
                        Presence(state.previewPanelOpen) { PreviewModePanel(state, Modifier.align(Alignment.CenterEnd)) }
                        HistoryNotes(state, Modifier.align(Alignment.BottomCenter))
                    }
                    DeskTransport(state, Modifier.fillMaxWidth().height(40.dp))
                }
                if (!narrowRight && inspectorOpen) {
                    DeskDivider(true) { delta -> inspectorWidth = (inspectorWidth - delta).coerceIn(384f, 560f) }
                    DeskInspector(state, library, Modifier.width(inspectorWidth.dp).fillMaxHeight()) { inspectorOpen = false }
                } else {
                    DeskDivider(true, null)
                    DeskInspectorRail { if (narrowRight) { narrowInspector = true; narrowLibrary = false } else inspectorOpen = true }
                }
            }
            DeskDivider(false) { delta -> timelineFraction = (timelineFraction - with(density) { delta.dp.toPx() } / heightPx).coerceIn(.1f, .6f) }
            Timeline(state, Modifier.fillMaxWidth().height(timelineHeight))
        }
        if ((narrow && narrowLibrary) || (narrowRight && narrowInspector)) Box(Modifier.fillMaxSize().padding(top = 44.dp, start = if (narrow) RailWidth.dp else 0.dp).background(Color.Black.copy(alpha = .35f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { narrowLibrary = false; narrowInspector = false })
        if (narrow && narrowLibrary) DeskLibrary(state, library, libraryTab, Modifier.align(Alignment.TopStart).padding(start = RailWidth.dp, top = 44.dp, bottom = timelineHeight + 6.dp).width(libraryWidth.dp).fillMaxHeight()) { narrowLibrary = false }
        if (narrowRight && narrowInspector) DeskInspector(state, library, Modifier.align(Alignment.TopEnd).padding(top = 44.dp, bottom = timelineHeight + 6.dp).width(inspectorWidth.dp).fillMaxHeight()) { narrowInspector = false }
        if (DeskDrop.item != null && DeskDrop.at.isSpecified) Box(Modifier.offset { IntOffset((DeskDrop.at.x - rootOrigin.x).toInt() + 12, (DeskDrop.at.y - rootOrigin.y).toInt() + 12) }.size(56.dp).alpha(.7f).clip(RoundedCornerShape(8.dp)).background(Am.Field), Alignment.Center) {
            DeskGhost.picture?.invoke()
        }
        if (settings) DeskDialog(stringResource(R.string.topbar_project_settings), 440.dp, { settings = false }, onConfirm = { settings = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { DeskSettings(state, heading = false) }
        }
        if (state.effectBrowserOpen) DeskDialog(null, 960.dp, { state.effectBrowserOpen = false }, height = 720.dp) { app.absentmotion.ui.editor.effects.EffectBrowser(state) }
        if (state.presetsOpen) DeskDialog(null, 720.dp, { state.presetsOpen = false }, height = 720.dp) { PresetScreen(state) }
        if (state.drawing != null) PhoneScreen(Dp.Infinity, onDismiss = { state.stepBack(); focus.clearFocus() }) { DrawingEditor(state) }
        DeskTemplateEmbed(state)
    }
    DisposableEffect(previewBounds) {
        DeskPreviewBounds.bounds = previewBounds
        onDispose { DeskPreviewBounds.bounds = Rect.Zero }
    }
}

internal object DeskPreviewBounds { var bounds = Rect.Zero }

@Composable
private fun DeskDivider(horizontal: Boolean, onDelta: ((Float) -> Unit)?) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragged by remember { mutableStateOf(false) }
    val base = if (horizontal) Modifier.width(7.dp).fillMaxHeight() else Modifier.height(7.dp).fillMaxWidth()
    val interactive = if (onDelta == null) Modifier else Modifier.hoverable(source).resizeCursor(horizontal).pointerInput(horizontal) {
        detectDragGestures(onDragStart = { dragged = true }, onDragEnd = { dragged = false }, onDragCancel = { dragged = false }) { change, amount ->
            change.consume()
            onDelta(with(density) { (if (horizontal) amount.x else amount.y).toDp().value })
        }
    }
    Box(base.background(Am.TopBar).then(interactive), Alignment.Center) {
        val line = Modifier.background(if (onDelta != null && (hovered || dragged)) Am.Accent else Am.Line)
        Box(if (horizontal) line.width(if (hovered) 2.dp else 1.dp).fillMaxHeight() else line.height(if (hovered) 2.dp else 1.dp).fillMaxWidth())
    }
}

@Composable
private fun DeskInspectorRail(open: () -> Unit) {
    Hint(deskShortcut(R.string.desk_inspector, "${DeskMod}2")) {
        Column(Modifier.width(InspectorRail.dp).fillMaxHeight().background(Am.Panel).clickable(onClick = open), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(PanelIcons.RgbSliders, stringResource(R.string.desk_inspector), Modifier.padding(top = 12.dp).size(24.dp), tint = Am.OnDark)
        }
    }
}

@Composable
private fun DeskTopBar(state: EditorState, onExit: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    Row(modifier.background(Am.TopBar).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        DeskIcon(ChromeIcons.Exit, stringResource(R.string.topbar_exit_to_project_list), action = onExit)
        Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.project.title, Modifier.weight(1f, fill = false).clip(RoundedCornerShape(4.dp)).clickable(enabled = state.inGroup) { state.leaveGroups() }, color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.groupNames.forEachIndexed { index, name ->
                Text("/", Modifier.padding(horizontal = 6.dp), color = Am.OnDarkDim, fontSize = 14.sp)
                Text(name, Modifier.weight(1f, fill = false).clip(RoundedCornerShape(4.dp)).clickable(enabled = index < state.groupNames.lastIndex) { state.exitTo(index + 1) }, color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${state.scene.width} × ${state.scene.height} · ${state.scene.fps} fps", Modifier.padding(start = 12.dp), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        DeskIcon(ChromeIcons.Undo, deskShortcut(R.string.play_undo, "${DeskMod}Z"), state.canUndo) { state.undo() }
        DeskIcon(ChromeIcons.Redo, deskShortcut(R.string.play_redo, DeskRedoKeys), state.canRedo) { state.redo() }
        DeskIcon(ChromeIcons.Gear, stringResource(R.string.topbar_project_settings), action = onSettings)
        Hint(deskShortcut(R.string.desk_export, "${DeskMod}E")) {
            Box(Modifier.padding(start = 8.dp, end = 2.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(Am.Accent).clickable { state.playing = false; state.exportOpen = true }.padding(horizontal = 16.dp), Alignment.Center) {
                Text(stringResource(R.string.desk_export), color = Am.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun DeskTransport(state: EditorState, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Am.TimelineBg)) {
        val compact = maxWidth < 640.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(TimelineMath.timecode(state.frame, state.scene.fps), color = Am.OnDark, fontSize = 13.sp, style = Tabular, maxLines = 1)
                Text(" / " + TimelineMath.timecode(state.scene.lastFrame + 1, state.scene.fps), color = Am.OnDarkDim, fontSize = 13.sp, style = Tabular, maxLines = 1)
            }
            DeskIcon(ChromeIcons.SkipBack, deskShortcut(R.string.desk_start, "Home"), size = 32.dp) { state.playing = false; state.frame = 0 }
            DeskIcon(DrawingIcons.Prev, deskShortcut(R.string.desk_previous_frame, "←"), size = 32.dp) { state.playing = false; state.frame = (state.frame - 1).coerceAtLeast(0) }
            DeskIcon(if (state.playing) ChromeIcons.Pause else ChromeIcons.Play, deskShortcut(if (state.playing) R.string.play_pause else R.string.play_play, "Space"), size = 32.dp) { state.playing = !state.playing }
            DeskIcon(DrawingIcons.Next, deskShortcut(R.string.desk_next_frame, "→"), size = 32.dp) { state.playing = false; state.frame = (state.frame + 1).coerceAtMost(state.scene.lastFrame) }
            DeskIcon(ChromeIcons.SkipForward, deskShortcut(R.string.desk_end, "End"), size = 32.dp) { state.playing = false; state.frame = state.scene.lastFrame }
            DeskIcon(ChromeIcons.PlayRepeat, stringResource(R.string.desk_loop), active = state.settings.loopingPlay, size = 32.dp) { state.settings.loopingPlay = !state.settings.loopingPlay }
            Row(Modifier.weight(1f), Arrangement.End, Alignment.CenterVertically) {
                Box(Modifier.size(32.dp), Alignment.Center) { Hint(stringResource(R.string.a11y_animate)) { AnimateButton(state, Modifier) } }
                Box(Modifier.size(32.dp), Alignment.Center) { Hint(stringResource(R.string.transition_title)) { TransitionButton(state, Modifier, compact = true) } }
                if (!compact) DeskIcon(ChromeIcons.Minus, stringResource(R.string.a11y_zoom_out), size = 32.dp) { state.zoomStep(false) }
                Hint(stringResource(R.string.desk_fit)) {
                    Box(Modifier.height(32.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable { state.resetCanvasView() }.padding(horizontal = 6.dp), Alignment.Center) {
                        Text("${(state.canvasZoom * 100).toInt()}%", color = Am.OnDark, fontSize = 12.sp, style = Tabular, maxLines = 1)
                    }
                }
                if (!compact) DeskIcon(ChromeIcons.Plus, stringResource(R.string.a11y_zoom_in), size = 32.dp) { state.zoomStep(true) }
                DeskIcon(ChromeIcons.PreviewMode, stringResource(R.string.play_preview_mode), active = state.previewPanelOpen, size = 32.dp) { state.previewPanelOpen = !state.previewPanelOpen }
            }
        }
    }
}

@Composable
private fun DeskInspector(state: EditorState, library: AddLibrary, modifier: Modifier, close: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val textUi = remember { TextEditUi() }
    val layer = state.selected
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectedId != null && state.panel == EditorPanel.EditText) DeskIcon(ChromeIcons.Back, stringResource(R.string.topbar_back)) { state.stepBack() }
            else if (layer != null && !MultiSelect.active) Icon(deskLayerIcon(layer), null, Modifier.padding(start = 4.dp).size(20.dp), tint = Am.OnDarkDim)
            Text(if (MultiSelect.active) stringResource(R.string.desk_inspector) else layer?.label ?: stringResource(R.string.desk_inspector), Modifier.weight(1f).padding(horizontal = 8.dp), color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.selectedId != null && !MultiSelect.active) {
                DeskIcon(ChromeIcons.Duplicate, deskShortcut(R.string.effects_duplicate, "${DeskMod}D")) { state.duplicateSelected() }
                DeskIcon(ChromeIcons.Trash, deskShortcut(R.string.topbar_delete_layer, "Delete")) { state.deleteSelected() }
                Box { DeskIcon(ChromeIcons.More, stringResource(R.string.topbar_layer_menu)) { menu = true }; if (menu) LayerMenu(state) { menu = false } }
            }
            DeskIcon(ChromeIcons.Close, deskShortcut(R.string.action_close, "${DeskMod}2"), action = close)
        }
        when {
            MultiSelect.active -> MultiBar(state, Modifier.fillMaxWidth().height(48.dp))
            state.selectedId == null -> DeskProject(state, Modifier.weight(1f).fillMaxWidth())
            state.panel == EditorPanel.EditText && state.selected?.kind == "text" -> TextEditor(state, textUi, embedded = true)
            else -> Box(Modifier.weight(1f)) { LowerPanels(state, library, top = true) }
        }
    }
    if (textUi.browser) DeskDialog(null, 560.dp, { textUi.browser = false }, height = 640.dp) { TextFontBrowser(state, textUi) }
}

internal fun finishDeskDrop(state: EditorState, at: Offset, add: () -> Unit) {
    val frame = DeskDrop.frameAt(at)
    if (frame != null) { state.frame = frame.coerceAtLeast(0); add() }
    else if (DeskPreviewBounds.bounds.contains(at)) add()
}
