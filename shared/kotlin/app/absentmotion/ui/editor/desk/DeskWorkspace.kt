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
import androidx.compose.ui.res.pluralStringResource
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
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.PressIndication
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap

private const val RailWidth = 56
private const val InspectorRail = 44
private const val DividerWidth = 7
private const val LibraryMin = 240f
private const val LibraryMax = 440f
private const val InspectorMin = 384f
private const val InspectorMax = 560f
private val MinPreview = 320.dp

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
    var libraryWidth by remember { mutableFloatStateOf(prefs.getFloat("libraryWidth", 300f).coerceIn(LibraryMin, LibraryMax)) }
    var inspectorWidth by remember { mutableFloatStateOf(prefs.getFloat("inspectorWidth", 400f).coerceIn(InspectorMin, InspectorMax)) }
    var timelineFraction by remember { mutableFloatStateOf(prefs.getFloat("timelineFraction", .34f).coerceIn(.1f, .6f)) }
    var libraryOpen by remember { mutableStateOf(prefs.getBoolean("libraryOpen", true)) }
    var inspectorOpen by remember { mutableStateOf(prefs.getBoolean("inspectorOpen", true)) }
    var soloLibrary by remember { mutableStateOf(false) }
    var soloInspector by remember { mutableStateOf(false) }
    var spacious by remember { mutableStateOf(true) }
    var libraryTab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var previewBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(libraryWidth, inspectorWidth, timelineFraction, libraryOpen, inspectorOpen) {
        kotlinx.coroutines.delay(300)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            prefs.edit().putFloat("libraryWidth", libraryWidth).putFloat("inspectorWidth", inspectorWidth).putFloat("timelineFraction", timelineFraction).putBoolean("libraryOpen", libraryOpen).putBoolean("inspectorOpen", inspectorOpen).apply()
        }
    }
    LaunchedEffect(state.selectedId) { if (state.selectedId != null) { soloInspector = true; soloLibrary = false } }
    LaunchedEffect(state.addOpen) { if (state.addOpen) state.addOpen = false }
    val modal = settings || state.effectBrowserOpen || state.presetsOpen || state.drawing != null || state.fullColor != null || EmbedRequest.templateId != null
    LaunchedEffect(modal) { if (!modal) focusRequester.requestFocus() }
    SizedBox(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }.background(Am.TopBar).onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed)) {
            when (it.key) {
                Key.One -> { if (spacious) libraryOpen = !libraryOpen else { soloLibrary = !soloLibrary; if (soloLibrary) soloInspector = false }; true }
                Key.Two -> { if (spacious) inspectorOpen = !inspectorOpen else { soloInspector = !soloInspector; if (soloInspector) soloLibrary = false }; true }
                else -> false
            }
        } else false
    }.focusRequester(focusRequester).focusable()) { maxWidth, maxHeight ->
        val heightPx = with(density) { maxHeight.roundToPx() }
        val both = (maxWidth - RailWidth.dp - 2 * DividerWidth.dp - MinPreview).value >= LibraryMin + InspectorMin
        SideEffect { spacious = both }
        val libraryShown = if (both) libraryOpen else soloLibrary
        val inspectorShown = if (both) inspectorOpen else soloInspector
        val room = (maxWidth - RailWidth.dp - 2 * DividerWidth.dp - MinPreview - (if (inspectorShown) 0.dp else InspectorRail.dp)).value
        val inspectorNow = if (inspectorShown) (if (libraryShown) inspectorWidth.coerceAtMost(room - LibraryMin) else inspectorWidth.coerceAtMost(room)).coerceAtLeast(InspectorMin) else 0f
        val libraryNow = if (libraryShown) libraryWidth.coerceAtMost(room - inspectorNow).coerceAtLeast(LibraryMin) else 0f
        val closeLibrary = { if (both) libraryOpen = false else soloLibrary = false }
        val closeInspector = { if (both) inspectorOpen = false else soloInspector = false }
        val timelineHeight = (maxHeight * timelineFraction).coerceIn(180.dp, maxHeight * .6f)
        Column(Modifier.fillMaxSize()) {
            DeskTopBar(state, onExit, { settings = true }, Modifier.fillMaxWidth().height(44.dp))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                DeskLibraryRail(libraryTab, libraryShown, Modifier.width(RailWidth.dp).fillMaxHeight()) { index ->
                    val next = !(libraryShown && libraryTab == index)
                    libraryTab = index
                    if (both) libraryOpen = next else { soloLibrary = next; if (next) soloInspector = false }
                }
                if (libraryShown) {
                    DeskLibrary(state, library, libraryTab, Modifier.width(libraryNow.dp).fillMaxHeight(), closeLibrary)
                    DeskDivider(true) { delta -> libraryWidth = (libraryNow + delta).coerceIn(LibraryMin, LibraryMax) }
                } else DeskDivider(true, null)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { val p = it.positionInWindow(); previewBounds = Rect(p, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }) {
                        Preview(state, Modifier.fillMaxSize())
                        Presence(state.previewPanelOpen) { PreviewModePanel(state, Modifier.align(Alignment.CenterEnd)) }
                        HistoryNotes(state, Modifier.align(Alignment.BottomCenter))
                    }
                    DeskTransport(state, Modifier.fillMaxWidth().height(40.dp))
                }
                if (inspectorShown) {
                    DeskDivider(true) { delta -> inspectorWidth = (inspectorNow - delta).coerceIn(InspectorMin, InspectorMax) }
                    DeskInspector(state, library, Modifier.width(inspectorNow.dp).fillMaxHeight(), closeInspector)
                } else {
                    DeskDivider(true, null)
                    DeskInspectorRail { if (both) inspectorOpen = true else { soloInspector = true; soloLibrary = false } }
                }
            }
            DeskDivider(false) { delta -> timelineFraction = (timelineFraction - with(density) { delta.dp.toPx() } / heightPx).coerceIn(.1f, .6f) }
            Timeline(state, Modifier.fillMaxWidth().height(timelineHeight))
        }
        app.absentmotion.platform.BackHandler(!both && (soloLibrary || soloInspector)) { if (soloInspector) soloInspector = false else soloLibrary = false }
        DeskDropGhost { rootOrigin }
        if (settings) DeskDialog(stringResource(R.string.topbar_project_settings), 440.dp, { settings = false }, onConfirm = { settings = false }) {
            Column(Modifier.deskVerticalScroll().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { DeskSettings(state, heading = false) }
        }
        if (state.effectBrowserOpen) DeskDialog(null, 1200.dp, { state.effectBrowserOpen = false }, height = 960.dp) { app.absentmotion.ui.editor.effects.EffectBrowser(state) }
        if (state.presetsOpen) DeskDialog(null, 800.dp, { state.presetsOpen = false }, height = 880.dp) { PresetScreen(state) }
        if (state.drawing != null) PhoneScreen(Dp.Infinity, onDismiss = { state.stepBack(); focus.clearFocus() }) { DrawingEditor(state) }
        DeskTemplateEmbed(state)
    }
    DisposableEffect(previewBounds) {
        DeskPreviewBounds.bounds = previewBounds
        onDispose { DeskPreviewBounds.bounds = Rect.Zero }
    }
}

@Composable
private fun DeskDropGhost(origin: () -> Offset) {
    if (DeskDrop.item == null) return
    Box(Modifier.offset {
        val at = DeskDrop.at
        if (at.isSpecified) IntOffset((at.x - origin().x).toInt() + 12, (at.y - origin().y).toInt() + 12) else IntOffset(-1000, -1000)
    }.size(56.dp).alpha(.7f).clip(RoundedCornerShape(8.dp)).background(Am.Field), Alignment.Center) {
        DeskGhost.picture?.invoke()
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
        Column(Modifier.width(InspectorRail.dp).fillMaxHeight().background(Am.Panel).hoverFill(0.dp, Am.Field).handCursor().clickable(onClick = open), horizontalAlignment = Alignment.CenterHorizontally) {
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
    SizedBox(modifier.background(Am.TimelineBg)) { maxWidth, _ ->
        val steps = maxWidth >= 640.dp
        val total = maxWidth >= 520.dp
        val skips = maxWidth >= 440.dp
        val fit = maxWidth >= 400.dp
        val loop = maxWidth >= 380.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(TimelineMath.timecode(state.frame, state.scene.fps), color = Am.OnDark, fontSize = 13.sp, style = Tabular, maxLines = 1, softWrap = false)
                if (total) Text(" / " + TimelineMath.timecode(state.scene.lastFrame + 1, state.scene.fps), color = Am.OnDarkDim, fontSize = 13.sp, style = Tabular, maxLines = 1, softWrap = false)
            }
            if (skips) DeskIcon(ChromeIcons.SkipBack, deskShortcut(R.string.desk_start, "Home"), size = 32.dp) { state.playing = false; state.frame = 0 }
            DeskIcon(DrawingIcons.Prev, deskShortcut(R.string.desk_previous_frame, "←"), size = 32.dp) { state.playing = false; state.frame = (state.frame - 1).coerceAtLeast(0) }
            DeskIcon(if (state.playing) ChromeIcons.Pause else ChromeIcons.Play, deskShortcut(if (state.playing) R.string.play_pause else R.string.play_play, "Space"), size = 32.dp) { state.playing = !state.playing }
            DeskIcon(DrawingIcons.Next, deskShortcut(R.string.desk_next_frame, "→"), size = 32.dp) { state.playing = false; state.frame = (state.frame + 1).coerceAtMost(state.scene.lastFrame) }
            if (skips) DeskIcon(ChromeIcons.SkipForward, deskShortcut(R.string.desk_end, "End"), size = 32.dp) { state.playing = false; state.frame = state.scene.lastFrame }
            if (loop) DeskIcon(ChromeIcons.PlayRepeat, stringResource(R.string.desk_loop), active = state.settings.loopingPlay, size = 32.dp) { state.settings.loopingPlay = !state.settings.loopingPlay }
            Row(Modifier.weight(1f), Arrangement.End, Alignment.CenterVertically) {
                Box(Modifier.size(32.dp), Alignment.Center) { Hint(stringResource(R.string.a11y_animate)) { AnimateButton(state, Modifier) } }
                Box(Modifier.size(32.dp), Alignment.Center) { Hint(stringResource(R.string.transition_title)) { TransitionButton(state, Modifier, compact = true) } }
                if (steps) DeskIcon(ChromeIcons.Minus, stringResource(R.string.a11y_zoom_out), size = 32.dp) { state.zoomStep(false) }
                if (fit) Hint(stringResource(R.string.desk_fit)) {
                    Box(Modifier.height(32.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable { state.resetCanvasView() }.padding(horizontal = 6.dp), Alignment.Center) {
                        Text("${(state.canvasZoom * 100).toInt()}%", color = Am.OnDark, fontSize = 12.sp, style = Tabular, maxLines = 1)
                    }
                }
                if (steps) DeskIcon(ChromeIcons.Plus, stringResource(R.string.a11y_zoom_in), size = 32.dp) { state.zoomStep(true) }
                var copyMenu by remember { mutableStateOf(false) }
                Box { DeskIcon(ChromeIcons.CopyPaste, stringResource(R.string.play_copy_paste), size = 32.dp) { copyMenu = true }; Presence(copyMenu) { CopyPasteMenu(state, below = true) { copyMenu = false } } }
                DeskIcon(ChromeIcons.PreviewMode, stringResource(R.string.play_preview_mode), active = state.previewPanelOpen, size = 32.dp) { state.previewPanelOpen = !state.previewPanelOpen }
            }
        }
    }
}

@Composable
private fun DeskInspector(state: EditorState, library: AddLibrary, modifier: Modifier, close: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var parentMenu by remember { mutableStateOf(false) }
    val textUi = remember { TextEditUi() }
    val layer = state.selected
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectedId != null && state.panel == EditorPanel.EditText) DeskIcon(ChromeIcons.Back, stringResource(R.string.topbar_back)) { state.stepBack() }
            else if (layer != null && !MultiSelect.active) Icon(deskLayerIcon(layer), null, Modifier.padding(start = 4.dp).size(20.dp), tint = Am.OnDarkDim)
            if (layer != null && !MultiSelect.active) key(layer.id) { DeskNameField(layer.label, Modifier.weight(1f).padding(horizontal = 2.dp)) { state.renameLayer(layer.id, it) } }
            else Text(
                if (MultiSelect.active) pluralStringResource(R.plurals.structure_layers_selected, MultiSelect.ids.size, MultiSelect.ids.size.toString()) else layer?.label ?: stringResource(R.string.desk_inspector),
                Modifier.weight(1f).padding(horizontal = 8.dp), color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (state.selectedId != null && !MultiSelect.active) {
                Box { DeskParentButton { parentMenu = true }; Presence(parentMenu) { ParentMenu(state, below = true) { parentMenu = false } } }
                DeskIcon(ChromeIcons.Duplicate, deskShortcut(R.string.effects_duplicate, "${DeskMod}D")) { state.duplicateSelected() }
                DeskIcon(ChromeIcons.Trash, deskShortcut(R.string.topbar_delete_layer, "Delete")) { state.deleteSelected() }
                Box { DeskIcon(ChromeIcons.More, stringResource(R.string.topbar_layer_menu)) { menu = true }; if (menu) LayerMenu(state) { menu = false } }
            }
            DeskIcon(ChromeIcons.Close, deskShortcut(R.string.action_close, "${DeskMod}2"), action = close)
        }
        when {
            MultiSelect.active -> DeskMulti(state, Modifier.weight(1f).fillMaxWidth())
            state.selectedId == null -> DeskProject(state, Modifier.weight(1f).fillMaxWidth())
            state.panel == EditorPanel.EditText && state.selected?.kind == "text" -> TextEditor(state, textUi, embedded = true)
            else -> DeskLayerArea(state, Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (textUi.browser) DeskDialog(null, 560.dp, { textUi.browser = false }, height = 640.dp) { TextFontBrowser(state, textUi) }
}

@Composable
private fun DeskLayerArea(state: EditorState, modifier: Modifier) {
    Box(modifier) {
        Swap(
            when {
                state.volumeOpen && state.panel == EditorPanel.Tools -> "volume" to state.easingOpen
                state.panel == EditorPanel.Tools -> "tools"
                else -> state.panel to state.easingOpen
            },
            Modifier.fillMaxSize(),
        ) {
            when {
                state.volumeOpen && state.panel == EditorPanel.Tools -> key(state.selectedId) { VolumePanel(state, Modifier.fillMaxSize()) }
                state.panel == EditorPanel.Tools -> LayerTools(state, Modifier.fillMaxWidth().align(Alignment.TopCenter), side = true)
                else -> LayerPanel(state, Modifier.fillMaxSize())
            }
        }
        Swap(state.keypad, Modifier.fillMaxSize(), rise = 0.dp) {
            state.keypad?.let { ValueKeypad(it, onClose = { state.keypad = null }, Modifier.fillMaxSize()) }
        }
    }
}

internal fun finishDeskDrop(state: EditorState, at: Offset, add: () -> Unit) {
    val frame = DeskDrop.frameAt(at)
    if (frame != null) { state.frame = frame.coerceAtLeast(0); add() }
    else if (DeskPreviewBounds.bounds.contains(at)) add()
}
