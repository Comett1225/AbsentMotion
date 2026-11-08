package app.absentmotion.ui.editor

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
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
import kotlinx.coroutines.launch
import app.absentmotion.editor.*
import app.absentmotion.model.shownName
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
import app.absentmotion.ui.tutorial.tutorialGuard
import app.absentmotion.ui.tutorial.tutorialTarget

private const val RailWidth = 56
private const val MeterWidth = 28
private const val InspectorRail = 44
private const val DividerWidth = 7
private const val LibraryMin = 212f
private const val LibraryMax = 440f
private const val InspectorMin = 384f
private const val InspectorMax = 560f
private val MinPreview = 320.dp
private const val TimelineMinDp = 180f

internal object DeskDividerMath {
    const val Reach = 12f

    fun band(over: Float): Float = Reach * over / (over + Reach * 3f)

    fun stretch(raw: Float, min: Float, max: Float): Float = when {
        raw > max -> band(raw - max)
        raw < min -> -band(min - raw)
        else -> 0f
    }

    fun limit(raw: Float, min: Float, max: Float): Float = raw.coerceIn(min, max.coerceAtLeast(min))
}

private class DividerDrag(val value: () -> Float, val min: () -> Float, val max: () -> Float, val grows: Int, val bounce: MutableFloatState, val set: (Float) -> Unit)

private fun Modifier.rubber(bounce: MutableFloatState, horizontal: Boolean, anchor: Float): Modifier = graphicsLayer {
    val extent = if (horizontal) size.width else size.height
    val scale = if (extent > 0f) 1f + bounce.floatValue * density / extent else 1f
    transformOrigin = if (horizontal) TransformOrigin(anchor, .5f) else TransformOrigin(.5f, anchor)
    if (horizontal) scaleX = scale else scaleY = scale
}

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
    val libraryBounce = remember { mutableFloatStateOf(0f) }
    val inspectorBounce = remember { mutableFloatStateOf(0f) }
    val timelineBounce = remember { mutableFloatStateOf(0f) }
    var libraryOpen by remember { mutableStateOf(prefs.getBoolean("libraryOpen", true)) }
    var inspectorOpen by remember { mutableStateOf(prefs.getBoolean("inspectorOpen", true)) }
    var soloLibrary by remember { mutableStateOf(false) }
    var soloInspector by remember { mutableStateOf(false) }
    var spacious by remember { mutableStateOf(true) }
    var libraryTab by remember { mutableIntStateOf(0) }
    var settings by DeskGear::open
    DisposableEffect(Unit) { onDispose { DeskGear.open = false } }
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
    LaunchedEffect(state.selectedId, MultiSelect.active, state.drawing) { if (state.selectedId != null || MultiSelect.active || state.drawing is PointMode) { soloInspector = true; soloLibrary = false } }
    LaunchedEffect(state.addOpen) { if (state.addOpen) state.addOpen = false }
    val modal = settings || state.effectBrowserOpen || state.presetsOpen || (state.drawing != null && state.drawing !is PointMode) || state.fullColor != null || EmbedRequest.templateId != null
    LaunchedEffect(modal) { if (!modal) focusRequester.requestFocus() }
    val togglePanel: (Boolean) -> Unit = remember {
        { library ->
            if (library) { if (spacious) libraryOpen = !libraryOpen else { soloLibrary = !soloLibrary; if (soloLibrary) soloInspector = false } }
            else { if (spacious) inspectorOpen = !inspectorOpen else { soloInspector = !soloInspector; if (soloInspector) soloLibrary = false } }
        }
    }
    DisposableEffect(togglePanel) {
        app.absentmotion.ui.editor.DeskDrop.panelKey = togglePanel
        onDispose { if (app.absentmotion.ui.editor.DeskDrop.panelKey === togglePanel) app.absentmotion.ui.editor.DeskDrop.panelKey = null }
    }
    SizedBox(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }.background(Am.TopBar).onPreviewKeyEvent {
        val panel = it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed) && !it.isAltPressed && (it.key == Key.One || it.key == Key.Two)
        if (panel) togglePanel(it.key == Key.One)
        panel
    }.focusRequester(focusRequester).focusable()) { maxWidth, maxHeight ->
        val both = (maxWidth - RailWidth.dp - MeterWidth.dp - 2 * DividerWidth.dp - MinPreview).value >= LibraryMin + InspectorMin
        SideEffect { spacious = both }
        val libraryShown = if (both) libraryOpen else soloLibrary
        val inspectorShown = if (both) inspectorOpen else soloInspector
        val room = (maxWidth - RailWidth.dp - MeterWidth.dp - 2 * DividerWidth.dp - MinPreview - (if (inspectorShown) 0.dp else InspectorRail.dp)).value
        val inspectorNow = if (inspectorShown) (if (libraryShown) inspectorWidth.coerceAtMost(room - LibraryMin) else inspectorWidth.coerceAtMost(room)).coerceAtLeast(InspectorMin) else 0f
        val libraryNow = if (libraryShown) libraryWidth.coerceAtMost(room - inspectorNow).coerceAtLeast(LibraryMin) else 0f
        val closeLibrary = { if (both) libraryOpen = false else soloLibrary = false }
        val closeInspector = { if (both) inspectorOpen = false else soloInspector = false }
        val timelineHeight = (maxHeight * timelineFraction).coerceIn(180.dp, maxHeight * .6f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                DeskLibraryRail(libraryTab, libraryShown, Modifier.width(RailWidth.dp).fillMaxHeight()) { index ->
                    val next = !(libraryShown && libraryTab == index)
                    libraryTab = index
                    if (both) libraryOpen = next else { soloLibrary = next; if (next) soloInspector = false }
                }
                DeskMasterMeter(state, DeskRailHeight, Modifier.width(MeterWidth.dp).fillMaxHeight())
                if (libraryShown) {
                    DeskLibrary(state, library, libraryTab, Modifier.width(libraryNow.dp).fillMaxHeight().rubber(libraryBounce, true, 0f), closeLibrary)
                    DeskDivider(true, DividerDrag({ libraryNow }, { LibraryMin }, { minOf(LibraryMax, room - inspectorNow) }, 1, libraryBounce) { libraryWidth = it })
                } else DeskDivider(true, null)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(1f).tutorialTarget("preview").onGloballyPositioned { val p = it.positionInWindow(); previewBounds = Rect(p, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }) {
                        Preview(state, Modifier.fillMaxSize())
                        (state.drawing as? PointMode)?.let { PointOverlay(state, it, Modifier.fillMaxSize()) }
                        Presence(state.previewPanelOpen) { PreviewModePanel(state, Modifier.align(Alignment.CenterEnd)) }
                        HistoryNotes(state, Modifier.align(Alignment.BottomCenter))
                    }
                    DeskTransport(state, Modifier.fillMaxWidth().height(40.dp).tutorialTarget("playbar").tutorialGuard(any = true))
                }
                if (inspectorShown) {
                    DeskDivider(true, DividerDrag({ inspectorNow }, { InspectorMin }, { minOf(InspectorMax, room - libraryNow) }, -1, inspectorBounce) { inspectorWidth = it })
                    DeskInspector(state, library, Modifier.width(inspectorNow.dp).fillMaxHeight().rubber(inspectorBounce, true, 1f), closeInspector)
                } else {
                    DeskDivider(true, null)
                    DeskInspectorRail { if (both) inspectorOpen = true else { soloInspector = true; soloLibrary = false } }
                }
            }
            DeskDivider(false, DividerDrag({ timelineHeight.value }, { TimelineMinDp }, { maxHeight.value * .6f }, -1, timelineBounce) { timelineFraction = (it / maxHeight.value).coerceIn(.1f, .6f) })
            Timeline(state, Modifier.fillMaxWidth().height(timelineHeight).rubber(timelineBounce, false, 1f).tutorialTarget("timeline"))
        }
        app.absentmotion.platform.BackHandler(!both && state.drawing !is PointMode && (soloLibrary || soloInspector)) { if (soloInspector) soloInspector = false else soloLibrary = false }
        DeskDropGhost { rootOrigin }
        if (settings) DeskProject(state) { settings = false }
        if (state.effectBrowserOpen) DeskDialog(null, 1200.dp, { state.effectBrowserOpen = false }, height = 960.dp) { app.absentmotion.ui.editor.effects.EffectBrowser(state) }
        if (state.presetsOpen) DeskDialog(null, 800.dp, { state.presetsOpen = false }, height = 880.dp) { PresetScreen(state) }
        if (state.drawing != null && state.drawing !is PointMode) PhoneScreen(Dp.Infinity, onDismiss = { state.stepBack(); focus.clearFocus() }) { DrawingEditor(state) }
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
private fun DeskDivider(horizontal: Boolean, drag: DividerDrag?) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(drag)
    val box = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val base = (if (horizontal) Modifier.width(7.dp).fillMaxHeight() else Modifier.height(7.dp).fillMaxWidth()).graphicsLayer {
        val shift = (drag?.let { it.grows * it.bounce.floatValue } ?: 0f) * this.density
        if (horizontal) translationX = shift else translationY = shift
    }
    val interactive = if (drag == null) Modifier else Modifier.hoverable(source).resizeCursor(horizontal).onGloballyPositioned { box[0] = it }.pointerInput(horizontal) {
        val run = arrayOfNulls<kotlinx.coroutines.Job>(1)
        fun along(at: Offset): Float = box[0]?.localToRoot(at)?.let { if (horizontal) it.x else it.y } ?: 0f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val first = latest ?: return@awaitEachGesture
            run[0]?.cancel()
            val origin = along(down.position)
            val start = first.value()
            dragged = true
            drag(down.id) { change ->
                change.consume()
                val d = latest ?: return@drag
                val raw = start + d.grows * (along(change.position) - origin) / density.density
                val lo = d.min()
                val hi = d.max().coerceAtLeast(lo)
                d.set(DeskDividerMath.limit(raw, lo, hi))
                d.bounce.floatValue = DeskDividerMath.stretch(raw, lo, hi)
            }
            dragged = false
            val d = latest ?: return@awaitEachGesture
            val from = d.bounce.floatValue
            run[0] = scope.launch { animate(from, 0f, animationSpec = spring(dampingRatio = 0.45f, stiffness = 380f)) { v, _ -> d.bounce.floatValue = v } }
        }
    }
    Box(base.background(Am.TopBar).then(interactive), Alignment.Center) {
        val line = Modifier.background(if (drag != null && (hovered || dragged)) Am.Accent else Am.Line)
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

internal object DeskGear { var open by mutableStateOf(false) }

@Composable
internal fun RowScope.DeskTopBar(state: EditorState, onExit: () -> Unit, menu: @Composable () -> Unit) {
    DeskIcon(ChromeIcons.Exit, stringResource(R.string.topbar_exit_to_project_list), action = onExit)
    menu()
    Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.inGroup) GroupCrumbs(state, 14.sp) { it() }
        else Text(state.project.title, Modifier.weight(1f, fill = false), color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${state.scene.width} × ${state.scene.height} · ${state.scene.fps} fps", Modifier.padding(start = 12.dp), color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
    Box(Modifier.tutorialTarget("undo")) { DeskIcon(ChromeIcons.Undo, deskShortcut(R.string.play_undo, "${DeskMod}Z"), state.canUndo) { state.undo() } }
    Box(Modifier.tutorialTarget("redo")) { DeskIcon(ChromeIcons.Redo, deskShortcut(R.string.play_redo, DeskRedoKeys), state.canRedo) { state.redo() } }
    Box(Modifier.tutorialTarget("gear")) { DeskIcon(ChromeIcons.Gear, stringResource(R.string.topbar_project_settings)) { DeskGear.open = true } }
    Hint(deskShortcut(R.string.desk_export, "${DeskMod}E")) {
        Box(Modifier.padding(start = 8.dp, end = 2.dp).height(32.dp).tutorialTarget("export").clip(RoundedCornerShape(8.dp)).background(Am.Accent).clickable { state.playing = false; state.exportOpen = true }.padding(horizontal = 16.dp), Alignment.Center) {
            Text(stringResource(R.string.desk_export), color = Am.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
        val centre = 32.dp * (3 + (if (skips) 2 else 0) + (if (loop) 1 else 0))
        val tools = 32.dp * 4 + (if (fit) 48.dp else 0.dp) + (if (steps) 64.dp else 0.dp)
        val centred = (maxWidth - 16.dp - centre) / 2 >= tools
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
            Row(if (centred) Modifier.weight(1f) else Modifier, Arrangement.End, Alignment.CenterVertically) {
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
                DeskIcon(if (state.previewPanelOpen) ChromeIcons.PreviewModeOpen else ChromeIcons.PreviewMode, stringResource(R.string.play_preview_mode), active = state.previewPanelOpen, size = 32.dp, iconSize = DpSize(20.dp, 30.dp)) { state.previewPanelOpen = !state.previewPanelOpen }
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
    Column(modifier.background(Am.Panel).tutorialTarget("inspector")) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectedId != null && state.panel == EditorPanel.EditText) Box(Modifier.tutorialTarget("back")) { DeskIcon(ChromeIcons.Back, stringResource(R.string.topbar_back)) { state.stepBack() } }
            else if (layer != null && !MultiSelect.active) Icon(deskLayerIcon(layer), null, Modifier.padding(start = 4.dp).size(20.dp), tint = Am.OnDarkDim)
            if (layer != null && !MultiSelect.active) key(layer.id) { DeskNameField(layer.shownName(), Modifier.weight(1f).padding(horizontal = 2.dp)) { state.renameLayer(layer.id, it) } }
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
            state.selectedId == null -> DeskProjectPanel(state, Modifier.weight(1f).fillMaxWidth())
            state.panel == EditorPanel.EditText && state.selected?.kind == "text" -> TextEditor(state, textUi, embedded = true)
            else -> DeskLayerArea(state, Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (textUi.browser) DeskDialog(null, 560.dp, { textUi.browser = false }, height = 640.dp) { TextFontBrowser(state, textUi) }
}

@Composable
private fun DeskLayerArea(state: EditorState, modifier: Modifier) {
    Box(modifier.tutorialTarget(if (state.panel == EditorPanel.Tools) "tools" else "panel")) {
        Swap(
            when {
                state.drawing is PointMode -> "points"
                state.volumeOpen && state.panel == EditorPanel.Tools -> "volume" to state.easingOpen
                state.panel == EditorPanel.Tools -> "tools"
                else -> state.panel to state.easingOpen
            },
            Modifier.fillMaxSize(),
        ) {
            when {
                state.drawing is PointMode -> (state.drawing as? PointMode)?.let { mode ->
                    Column(Modifier.fillMaxSize()) {
                        PointTopBar(state, mode, Modifier.fillMaxWidth().height(40.dp))
                        Box(Modifier.fillMaxWidth().weight(1f)) { PointPanel(state, mode) }
                    }
                }
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
