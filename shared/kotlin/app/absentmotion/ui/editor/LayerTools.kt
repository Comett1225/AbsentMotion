package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialTarget
import app.absentmotion.ui.tutorial.tutorialGuard
import app.absentmotion.model.finiteFloat
import app.absentmotion.platform.BackHandler
import androidx.annotation.StringRes
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.audio.gain
import app.absentmotion.model.PropRef
import app.absentmotion.model.contours
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.model.isShapeTemplate
import app.absentmotion.ui.editor.panels.SpeedPanel
import app.absentmotion.model.formatColor
import app.absentmotion.ui.editor.panels.EasingPanel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import app.absentmotion.editor.isPlainGroup
import app.absentmotion.editor.picksFrames
import app.absentmotion.model.FrameRef
import app.absentmotion.model.formatFloats
import app.absentmotion.model.shownFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.reverseMark
import app.absentmotion.ui.editor.effects.AiProgressDialog
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.editDrawing
import app.absentmotion.editor.requestPoints
import app.absentmotion.editor.canCrop
import android.widget.Toast
import kotlinx.coroutines.launch
import app.absentmotion.editor.screenshotFrame
import app.absentmotion.editor.canScreenshot
import app.absentmotion.editor.openCrop
import app.absentmotion.editor.moveClipEdges
import app.absentmotion.editor.split
import app.absentmotion.editor.stepBack
import app.absentmotion.editor.trimEnd
import app.absentmotion.editor.trimStart
import app.absentmotion.ui.editor.panels.BlendingPanel
import app.absentmotion.ui.editor.panels.BorderShadowPanel
import app.absentmotion.ui.editor.panels.CameraPanel
import app.absentmotion.ui.editor.panels.LightPanel
import app.absentmotion.ui.editor.panels.BorderShadowTabs
import app.absentmotion.ui.editor.panels.OpacityRef
import app.absentmotion.ui.editor.panels.borderShadowTarget
import app.absentmotion.ui.editor.panels.ColorFillPanel
import app.absentmotion.ui.editor.panels.EditShapePanel
import app.absentmotion.ui.editor.panels.ModelPanel
import app.absentmotion.ui.editor.panels.MoveTransformPanel
import app.absentmotion.ui.editor.panels.PanelDark
import app.absentmotion.ui.editor.panels.keypadChar
import app.absentmotion.ui.editor.panels.keypadOutside
import app.absentmotion.ui.editor.panels.keypadRange
import app.absentmotion.ui.editor.panels.keypadValue
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import app.absentmotion.ui.editor.panels.PanelGreen
import app.absentmotion.ui.editor.panels.PanelIcon
import app.absentmotion.ai.cutoutEdge
import app.absentmotion.ui.editor.panels.PanelShape
import app.absentmotion.ui.editor.panels.dimmed
import app.absentmotion.ui.editor.panels.formatPlain
import app.absentmotion.ui.editor.panels.px
import app.absentmotion.ui.editor.panels.turnFor
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.fadeBackground
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.editor.markBeatsNote

@Composable
fun LayerTools(state: EditorState, modifier: Modifier = Modifier, side: Boolean = false) {
    val still = state.selectedId?.let { state.scene.layer(it) }
    val drawn = still?.let { it.shape.isEmpty() && !it.isShapeTemplate } == true
    var properties by remember(state.selectedId) { mutableStateOf(false) }
    var moving by remember(state.selectedId) { mutableStateOf(false) }
    var captions by remember(state.selectedId) { mutableStateOf(false) }
    var controls by remember(state.selectedId) { mutableStateOf(false) }
    var marking by remember(state.selectedId) { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember(state, context, scope) { app.absentmotion.ai.AiTools(context.applicationContext, state, scope) }
    if (captions) CaptionsSheet(state, clipId = state.selectedId) { captions = false }
    if (properties) {
        ElementProperties(state, { properties = false }, modifier)
        return
    }
    Column(modifier.background(Am.Panel).deskVerticalScroll().padding(start = 62.px, top = 42.px, end = 62.px)) {
        Row(Modifier.fillMaxWidth().height(107.px)) {
            SmallTool(PanelIcons.Speed, stringResource(R.string.a11y_speed), Modifier.width(183.px), ToolShape, enabled = still?.mediaFill?.video == true || still?.kind == "audio") { state.panel = EditorPanel.Speed }
            Spacer(Modifier.width(17.px))
            Row(Modifier.weight(1f).fillMaxHeight(), Arrangement.spacedBy(3.px)) {
                val clipStart = still?.startTime
                val clipEnd = still?.endTime
                val past by remember(state, clipEnd) { derivedStateOf { clipEnd != null && state.sampleMs > clipEnd } }
                val before by remember(state, clipStart) { derivedStateOf { clipStart != null && state.timeMs < clipStart } }
                if (!past) SmallTool(PanelIcons.TrimLeft, stringResource(if (before) R.string.a11y_extend_start else R.string.a11y_trim_start), Modifier.weight(1f), ToolShape.copy(topEnd = Square, bottomEnd = Square)) {
                    if (before) state.moveClipEdges(false, true) else state.trimStart()
                }
                val middle = when { past -> ToolShape.copy(topEnd = Square, bottomEnd = Square); before -> ToolShape.copy(topStart = Square, bottomStart = Square); else -> RoundedCornerShape(0.dp) }
                SmallTool(if (past) ChromeIcons.SkipForward else if (before) ChromeIcons.SkipBack else PanelIcons.Split, stringResource(if (past || before) R.string.a11y_move_to_playhead else R.string.a11y_split), Modifier.weight(1f).tutorialTarget("tool_split"), middle) {
                    if (past || before) state.moveClipEdges(past, false) else state.split()
                }
                if (!before) SmallTool(PanelIcons.TrimRight, stringResource(if (past) R.string.a11y_extend_end else R.string.a11y_trim_end), Modifier.weight(1f), ToolShape.copy(topStart = Square, bottomStart = Square)) {
                    if (past) state.moveClipEdges(true, true) else state.trimEnd()
                }
            }
            Spacer(Modifier.width(17.px))
            val sound = still?.let { it.kind == "audio" || it.mediaFill?.video == true } == true
            val silent by remember(state) { derivedStateOf { (state.selected?.gain ?: 1f) <= 0f } }
            val muted = !sound || silent
            SmallTool(if (muted) PanelIcons.AudioMuted else AudioIcons.Speaker, stringResource(R.string.volume_volume), Modifier.width(183.px).tutorialTarget("tool_volume"), ToolShape,
                enabled = sound, iconSize = if (muted) 24.dp else 32.dp) { state.volumeOpen = true }
        }
        Spacer(Modifier.height(28.px))
        val kind = still?.kind
        if (kind == "camera" && moving) state.selectedId?.let { CameraMovesSheet(state, it) { moving = false } }
        val presets = Tile(Presets) { openPresets(state) }
        val effects = Tile(Effects) { state.effectIndex = -1; state.panel = EditorPanel.Effects }
        val transform = Tile(MoveTransform, onLongClick = if (app.absentmotion.platform.PlatformFeatures.desktop) { { controls = true } } else null, menu = {
            Presence(controls) { TransformControlsMenu(state) { basic -> controls = false; if (basic != null) state.panel = EditorPanel.MoveTransform } }
        }) { state.panel = EditorPanel.MoveTransform }
        val blending = Tile(Blending) { state.panel = EditorPanel.Blending }
        val rows = when (kind) {
            "audio" -> listOf(
                listOfNotNull(presets, Tile(Effects) { openEffectBrowser(state, "audio") }, Tile(MarkBeats, enabled = !marking) {
                    marking = true
                    scope.launch {
                        try { android.widget.Toast.makeText(context, state.markBeatsNote(context), android.widget.Toast.LENGTH_SHORT).show() } finally { marking = false }
                    }
                }, if (app.absentmotion.platform.PlatformFeatures.ai) Tile(AiTools) { openEffectBrowser(state, "ai") } else null),
            )
            "camera" -> listOf(
                listOf(Tile(CameraOptions) { state.panel = EditorPanel.Camera }, transform, effects),
                listOf(presets, Tile(CameraMoves) { state.playing = false; moving = true }),
            )
            "model" -> listOf(
                listOf(Tile(ModelOptions) { state.panel = EditorPanel.Model }, blending),
                listOf(transform, presets, effects),
            )
            "light" -> listOf(listOf(Tile(LightOptions) { state.panel = EditorPanel.Light }, transform))
            "nullobj" -> listOf(listOf(transform, presets, effects))
            else -> {
                val edit = when {
                    kind == "text" -> Tile(EditText) { state.panel = EditorPanel.EditText }
                    kind == "embedScene" -> if (still.link != null) Tile(ElementPropsButton) { properties = true }
                        else if (still.cutoutEdge != null) Tile(CutoutEdge) { state.panel = EditorPanel.Edge }
                        else if (still.isPlainGroup == true) Tile(EditGroup) { state.selectedId?.let(state::enterGroup) } else null
                    kind == "drawing" -> Tile(EditDrawing) { state.editDrawing() }
                    drawn -> Tile(EditPoints) { state.requestPoints(enter = true) }
                    else -> Tile(EditShape) { state.panel = EditorPanel.EditShape }
                }
                listOf(
                    listOfNotNull(Tile(ColorFill) { state.panel = EditorPanel.ColorFill }, Tile(BorderShadow) { state.panel = EditorPanel.BorderShadow }, blending,
                        if (still?.picksFrames == true) Tile(FramePick) { state.panel = EditorPanel.Frame } else null),
                    listOfNotNull(transform, edit, presets, effects),
                )
            }
        }
        val video = still?.mediaFill?.video == true
        val editorScope = LocalEditorScope.current
        var shooting by remember { mutableStateOf(false) }
        val storage = app.absentmotion.platform.rememberStorageAccess()
        val outside = stringResource(R.string.screenshot_outside)
        val saved = stringResource(R.string.screenshot_saved)
        val failed = stringResource(R.string.screenshot_failed)
        val more = listOfNotNull(
            if (kind == "shape" && still.isShapeTemplate == true) Tile(EditPoints) { state.requestPoints(enter = true) } else null,
            if (kind == "shape" && drawn && still.let(ShapeCatalog::paramsFor).orEmpty().isNotEmpty()) Tile(EditShape) { state.panel = EditorPanel.EditShape } else null,
            if (state.scene.depthOrder && kind in setOf("shape", "text", "drawing", "embedScene")) Tile(ThreeDLayer, active = still?.node?.get("threeD") == "true") {
                state.editSelected { it.copy(node = it.node.with("threeD", if (it.node["threeD"] == "true") null else "true")) }
            } else null,
            if (still?.canCrop == true) Tile(Crop) { state.openCrop() } else null,
            if (app.absentmotion.platform.PlatformFeatures.ai && still?.mediaFill != null) Tile(AiTools) { openEffectBrowser(state, "ai") } else null,
            if (app.absentmotion.platform.PlatformFeatures.ai && video) Tile(CaptionTools) { state.playing = false; captions = true } else null,
            if (app.absentmotion.platform.PlatformFeatures.ai && video) Tile(ReverseTool, enabled = ai.running == null, active = still.reverseMark != null) { state.playing = false; ai.apply(AiAction.Reverse) } else null,
            if (video) Tile(ScreenshotTool, enabled = !shooting) {
                state.playing = false
                fun shoot() {
                    shooting = true
                    editorScope.launch {
                        val done = try { state.screenshotFrame(context, editorScope) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false } finally { shooting = false }
                        Toast.makeText(context, if (done) saved else failed, Toast.LENGTH_SHORT).show()
                    }
                }
                if (!state.canScreenshot(state.selected)) Toast.makeText(context, outside, Toast.LENGTH_SHORT).show()
                else if (storage.ready { shoot() }) shoot()
            } else null,
        )
        if (side) (rows.flatten() + more).let { tiles -> sideColumns(tiles.size, LocalSideHeight.current).let { TileRows(tiles.chunked(it), it) } }
        else if (more.isEmpty()) TileRows(rows, null)
        else key(state.selectedId) {
            val pager = rememberPagerState(state.toolPage.takeIf { it.first == state.selectedId }?.second?.coerceIn(0, 1) ?: 0) { 2 }
            androidx.compose.runtime.LaunchedEffect(pager) { androidx.compose.runtime.snapshotFlow { pager.currentPage }.collect { state.toolPage = state.selectedId to it } }
            HorizontalPager(pager, Modifier.fillMaxWidth().height((rows.size * 160 + (rows.size - 1) * 17).px), pageSpacing = 17.px, verticalAlignment = Alignment.Top,
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(ToolPageSnapMs, easing = FastOutSlowInEasing))) { page ->
                Column { TileRows(if (page == 0) rows else more.chunked(4), if (page == 0) null else 4) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.px, bottom = 14.px), Arrangement.Center) {
                repeat(2) { i ->
                    Box(Modifier.padding(horizontal = 4.dp).size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(animatedColor(if (pager.currentPage == i) Am.OnDark else Am.OnDarkDim.copy(alpha = 0.4f))))
                }
            }
        }
    }
    AiProgressDialog(ai)
}

@Composable
internal fun PanelRail(state: EditorState, modifier: Modifier) {
    val layer = state.selected
    val panels = when {
        layer == null || app.absentmotion.editor.MultiSelect.active || state.drawing != null -> emptyList()
        else -> when (layer.kind) {
            "audio" -> emptyList()
            "camera" -> listOf(CameraOptions to EditorPanel.Camera, MoveTransform to EditorPanel.MoveTransform, Effects to EditorPanel.Effects)
            "model" -> listOf(ModelOptions to EditorPanel.Model, Blending to EditorPanel.Blending, MoveTransform to EditorPanel.MoveTransform, Effects to EditorPanel.Effects)
            "light" -> listOf(LightOptions to EditorPanel.Light, MoveTransform to EditorPanel.MoveTransform)
            "nullobj" -> listOf(MoveTransform to EditorPanel.MoveTransform, Effects to EditorPanel.Effects)
            else -> listOf(ColorFill to EditorPanel.ColorFill, BorderShadow to EditorPanel.BorderShadow, Blending to EditorPanel.Blending, MoveTransform to EditorPanel.MoveTransform, Effects to EditorPanel.Effects)
        }
    }
    Column(modifier.background(Am.Button).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp)) {
        if (layer != null && !app.absentmotion.editor.MultiSelect.active && state.drawing == null) RailButton(ChromeIcons.More, stringResource(R.string.topbar_layer_menu), state.panel == EditorPanel.Tools) { state.volumeOpen = false; state.panel = EditorPanel.Tools }
        for ((tool, panel) in panels) RailButton(tool.icon, stringResource(tool.label), state.panel == panel) {
            if (panel == EditorPanel.Effects) state.effectIndex = -1
            state.volumeOpen = false
            state.panel = panel
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) Am.Accent else Am.OnDark
    Column(Modifier.fillMaxWidth().height(58.dp).tutorialGuard().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(6.5.dp))
        androidx.compose.material3.Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.height(5.dp))
        Text(label, Modifier.fillMaxWidth().padding(horizontal = 2.dp), color = tint, fontSize = 9.sp, lineHeight = 10.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
    }
}

internal fun sideColumns(count: Int, available: Dp): Int =
    (2..3).firstOrNull { columns -> SideToolsTop + SideToolsRow * ((count + columns - 1) / columns) <= available } ?: 4

private val SideToolsTop = 57.dp
private val SideToolsRow = 63.dp

@Composable
private fun TileRows(rows: List<List<Tile>>, columns: Int?) {
    rows.forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(17.px))
        Row(Modifier.fillMaxWidth().height(if (app.absentmotion.platform.PlatformFeatures.desktop) 68.dp else 160.px), Arrangement.spacedBy(17.px)) {
            for (tile in row) ToolButton(tile.tool, Modifier.weight(1f).tutorialTarget(if (tile.tool === MoveTransform) "tool_transform" else if (tile.tool === Effects) "tool_effects" else null), tile.enabled, tile.active, tile.onLongClick, tile.menu, tile.onClick)
            if (columns != null) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun openEffectBrowser(state: EditorState, category: String) {
    state.playing = false
    state.effectCategory = category
    state.effectBrowserOpen = true
}

private fun openPresets(state: EditorState) {
    state.playing = false
    state.presetsOpen = true
}

private val ToolShape = RoundedCornerShape(4.dp)
private val Square = androidx.compose.foundation.shape.CornerSize(0.dp)
private val ToolLabel get() = Am.OnDarkDim

private class Tool(@StringRes val label: Int, val icon: ImageVector, val iconY: Float)

private class Tile(val tool: Tool, val enabled: Boolean = true, val active: Boolean = false, val onLongClick: (() -> Unit)? = null, val menu: (@Composable () -> Unit)? = null, val onClick: () -> Unit)

private val ColorFill = Tool(R.string.layer_tool_fill_color, PanelIcons.PaintBucket, 18.1f)
private val BorderShadow = Tool(R.string.layer_tool_frame_shadow, PanelIcons.BorderShadow, 18.1f)
private val Blending = Tool(R.string.layer_tool_opacity_blend, PanelIcons.Blending, 18.3f)
private val MoveTransform = Tool(R.string.layer_tool_transform, PanelIcons.MoveTransform, 18.1f)
private val EditShape = Tool(R.string.layer_tool_shape_options, PanelIcons.EditShape, 16f)
private val EditText = Tool(R.string.layer_tool_edit_text, TextIcons.Edit, 16f)
private val ElementPropsButton = Tool(R.string.asset_properties, PanelIcons.EditShape, 16f)
private val EditPoints = Tool(R.string.layer_tool_edit_points, PanelIcons.EditShape, 16f)
private val EditDrawing = Tool(R.string.layer_tool_edit_drawing, PanelIcons.EditShape, 16f)
private val CameraOptions = Tool(R.string.layer_tool_camera_options, PanelIcons.Camera, 19.4f)
private val CameraMoves = Tool(R.string.camera_moves, PanelIcons.Animate, 19.4f)
private val ModelOptions = Tool(R.string.layer_tool_model_options, PanelIcons.TabTilt, 19.4f)
private val LightOptions = Tool(R.string.light_options, PanelIcons.Brightness, 19.4f)
private val ThreeDLayer = Tool(R.string.three_d_layer, PanelIcons.TabTilt, 19.4f)
private val Presets = Tool(R.string.presets, PanelIcons.Star, 19.4f)
private val Effects = Tool(R.string.layer_tool_effects, PanelIcons.Effects, 19.6f)
private val Crop = Tool(R.string.layer_tool_crop, PanelIcons.Crop, 18.1f)
private val CaptionTools = Tool(R.string.auto_captions, ChromeIcons.Captions, 19.4f)
private val AiTools = Tool(R.string.ai, PanelIcons.Bot, 19.4f)
private val MarkBeats = Tool(R.string.menu_mark_beats, AudioIcons.Note, 19.4f)
private const val ToolPageSnapMs = 120
private val EditGroup = Tool(R.string.layer_tool_edit_group, SelectionIcons.EnterGroup, 18.1f)
private val FramePick = Tool(R.string.layer_tool_frame, app.absentmotion.ui.home.HomeIcons.FrameRate, 18.1f)
private val CutoutEdge = Tool(R.string.cutout_edge, PanelIcons.TabBorder, 18.1f)
private val ReverseTool = Tool(R.string.speed_reverse, PanelIcons.Reverse, 19.4f)
private val ScreenshotTool = Tool(R.string.layer_tool_screenshot, PanelIcons.Screenshot, 19.4f)

@Composable
private fun SmallTool(icon: ImageVector, description: String, modifier: Modifier, shape: RoundedCornerShape, enabled: Boolean = true, iconSize: Dp = 24.dp, onClick: () -> Unit = {}) {
    val press = remember { MutableInteractionSource() }
    val hovered = if (app.absentmotion.platform.PlatformFeatures.desktop) press.collectIsHoveredAsState().value else false
    Box(
        modifier.tutorialGuard().fillMaxHeight().pressScale(press, TilePress).then(if (enabled) Modifier else Modifier.dimmed()).clip(shape)
            .background(if (app.absentmotion.platform.PlatformFeatures.desktop && hovered) Am.Field else Am.Button)
            .then(if (app.absentmotion.platform.PlatformFeatures.desktop && enabled) Modifier.hoverable(press).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .then(if (enabled) Modifier.clickable(press, LocalIndication.current, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon(icon, Am.OnDark, size = iconSize, description = description)
    }
}

@Composable
private fun ToolButton(tool: Tool, modifier: Modifier, enabled: Boolean = true, active: Boolean = false, onLongClick: (() -> Unit)? = null, menu: (@Composable () -> Unit)? = null, onClick: () -> Unit = {}) {
    val press = remember { MutableInteractionSource() }
    val hovered = if (app.absentmotion.platform.PlatformFeatures.desktop) press.collectIsHoveredAsState().value else false
    Box(
        modifier.tutorialGuard().fillMaxHeight().pressScale(press, TilePress).then(if (enabled) Modifier else Modifier.dimmed()).clip(ToolShape)
            .background(if (app.absentmotion.platform.PlatformFeatures.desktop && hovered) Am.Field else Am.Button)
            .then(if (app.absentmotion.platform.PlatformFeatures.desktop && enabled) Modifier.hoverable(press).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .then(if (app.absentmotion.platform.PlatformFeatures.desktop && onLongClick != null) Modifier.pointerInput(onLongClick) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && (event.buttons.isSecondaryPressed || mac && event.keyboardModifiers.isCtrlPressed)) { onLongClick(); event.changes.forEach { it.consume() } }
                    }
                }
            } else Modifier)
            .then(if (enabled) Modifier.combinedClickable(press, LocalIndication.current, onLongClick = onLongClick, onClick = onClick) else Modifier),
    ) {
        menu?.invoke()
        PanelIcon(tool.icon, if (active) Am.Accent else Am.OnDark, Modifier.align(Alignment.TopCenter).offset(y = (tool.iconY - 12f).dp))
        Text(
            stringResource(tool.label), Modifier.align(Alignment.TopCenter).offset(y = 32.dp).fillMaxWidth().height(if (app.absentmotion.platform.PlatformFeatures.desktop) 30.dp else 24.dp).padding(horizontal = 4.dp)
                .wrapContentHeight(Alignment.CenterVertically),
            color = ToolLabel, fontSize = if (app.absentmotion.platform.PlatformFeatures.desktop) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            lineHeight = if (app.absentmotion.platform.PlatformFeatures.desktop) 14.sp else 11.5.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            autoSize = if (app.absentmotion.platform.PlatformFeatures.desktop) null else androidx.compose.foundation.text.TextAutoSize.StepBased(7.sp, 10.sp, 0.5.sp),
        )
    }
}

@Composable
fun LayerPanel(state: EditorState, modifier: Modifier = Modifier) {
    val selected = state.selected ?: return
    val panel = state.panel
    app.absentmotion.ui.editor.panels.BorderShadowLifetime(state, selected.id, panel)
    val layer = selected.copy(displayNumbers = linkedNumbers(state, selected, listOf(PropRef.Location, PropRef.Scale, PropRef.Rotation, PropRef.Pivot, PropRef.Opacity, PropRef.Tilt, PropRef.Skew)))
    if (panel == EditorPanel.Effects) {
        app.absentmotion.ui.editor.panels.EffectsPanel(state, modifier)
        return
    }
    if (panel == EditorPanel.Crop) {
        app.absentmotion.ui.editor.panels.CropPanel(state, modifier)
        return
    }
    val move = panel == EditorPanel.MoveTransform
    val desk = app.absentmotion.platform.PlatformFeatures.desktop
    var tab by remember(selected.kind) { mutableIntStateOf(0) }
    var pivotMode by remember(selected.kind) { mutableStateOf(false) }
    val basic = state.settings.basicTransform
    if (tab !in transformTabs(basic, desk)) SideEffect { tab = 0 }
    val canPivot = !desk && selected.kind != "camera"
    val shown = if (tab == 0 && pivotMode && canPivot) PIVOT_TAB else tab
    val catalog by state.engine.effects.collectAsState()
    val threeD = !desk && shown == ROTATE_TAB && (state.settings.rotate3d || layer.hasTilt()) && (selected.kind != "camera" || state.scene.depthOrder)
    val turn = if (move) turnFor(selected, catalog, shown, threeD) else null
    var menuOpen by remember { mutableStateOf(false) }
    val named = panel != EditorPanel.EditShape && panel != EditorPanel.Camera && panel != EditorPanel.Model && panel != EditorPanel.Light && !(move && shown == ROTATE_TAB && turn != null)
    val known = if (!named) null else when {
        move -> if (selected.kind == "camera" && shown == SIZE_TAB) KeyTarget(app.absentmotion.model.CameraFov, "60.000000") else TransformTargets[shown]
        panel == EditorPanel.ColorFill && layer.fillType == "color" -> KeyTarget(PropRef.FillColor, formatColor(layer.fillColor))
        panel == EditorPanel.BorderShadow -> borderShadowTarget(layer)
        panel == EditorPanel.Blending -> KeyTarget(OpacityRef, "1.000000")
        panel == EditorPanel.Speed && layer.node.child("timeRemap") != null -> KeyTarget(app.absentmotion.model.RemapRef, "0.000000")
        panel == EditorPanel.Frame && layer.picksFrames -> KeyTarget(FrameRef, formatFloats(floatArrayOf(layer.shownFrame(state.sampleMs.toDouble(), state.scene.fps).toFloat())))
        else -> null
    }
    if (named) SideEffect { state.keyTarget = known }
    if (state.easingOpen) {
        EasingPanel(state, modifier)
        return
    }
    val target = if (named) known else state.keyTarget
    val buttons: @Composable (Modifier) -> Unit = { cell ->
        FrameButton(PanelIcons.Back, stringResource(R.string.topbar_back), cell.tutorialTarget(if (app.absentmotion.platform.PlatformFeatures.desktop) "back" else null)) { state.stepBack() }
        if (target == null) {
            FrameButton(PanelIcons.Keyframe, stringResource(R.string.a11y_add_keyframe), cell.dimmed(), enabled = false)
            FrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), cell.dimmed(), enabled = false)
        } else {
            val linked = layer.element(target.ref)?.children?.any { it.name == "valueLink" } == true
            val onKeyframe = layer.onKeyframe(target.ref)
            FrameButton(if (onKeyframe) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (onKeyframe) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), cell.tutorialTarget("keyframe"), enabled = !linked) {
                state.toggleKeyframe()
            }
            FrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), cell.tutorialTarget("easing"), enabled = !linked) { if (layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true }
        }
        if (move) {
            Box(cell) {
                FrameButton(PanelIcons.More, stringResource(R.string.effects_more), Modifier.fillMaxSize()) { menuOpen = true }
                Presence(menuOpen) {
                    TransformMenu(state, shown, turn, extra = if (desk) null else { close ->
                        if (canPivot && tab == 0) MenuRow(stringResource(R.string.a11y_tab_pivot), trailing = if (pivotMode) PanelIcons.Check else null, divider = true) { pivotMode = !pivotMode; close() }
                        if (tab == ROTATE_TAB && (selected.kind != "camera" || state.scene.depthOrder)) {
                            MenuRow(stringResource(R.string.transform_3d_rotation), enabled = !layer.hasTilt() && (turn?.effect ?: -1) < 0, trailing = if (state.settings.rotate3d || turn != null) PanelIcons.Check else null, divider = turn == null) { state.settings.rotate3d = !state.settings.rotate3d; close() }
                            if (turn != null) MenuRow(stringResource(R.string.transform_free_rotate), trailing = if (state.settings.rotateFree) PanelIcons.Check else null, divider = true) { state.settings.rotateFree = !state.settings.rotateFree; close() }
                        }
                    }) { menuOpen = false }
                }
            }
        } else {
            Spacer(cell)
        }
    }
    val pad: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        if (panel == EditorPanel.BorderShadow) BorderShadowTabs(Modifier.align(if (app.absentmotion.platform.PlatformFeatures.desktop) Alignment.TopEnd else Alignment.CenterEnd).width(168.px).then(if (app.absentmotion.platform.PlatformFeatures.desktop) Modifier.height(PanelHeight) else Modifier.fillMaxHeight()).background(Am.Panel))
        when (panel) {
            EditorPanel.MoveTransform -> Swap(shown, Modifier.fillMaxSize().tutorialTarget("pad"), rise = 0.dp) { MoveTransformPanel(state, layer, shown, turn, Modifier.fillMaxSize(), if (canPivot) { { pivotMode = !pivotMode } } else null) }
            EditorPanel.ColorFill -> ColorFillPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.EditShape -> EditShapePanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Speed -> SpeedPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.BorderShadow -> BorderShadowPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Blending -> BlendingPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Camera -> CameraPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Model -> ModelPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Light -> LightPanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Edge -> app.absentmotion.ui.editor.panels.EdgePanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Frame -> app.absentmotion.ui.editor.panels.FramePanel(state, layer, Modifier.fillMaxSize())
            EditorPanel.Tools, EditorPanel.EditText, EditorPanel.Effects, EditorPanel.Crop -> {}
        }
        Box(Modifier.width(2.px).fillMaxHeight().background(PanelDark))
        if (move) Box(Modifier.align(Alignment.CenterEnd).width(2.px).fillMaxHeight().background(PanelDark))
    }
    val tabs: @Composable (Modifier) -> Unit = { cell ->
        val names = listOf(R.string.a11y_tab_move, R.string.a11y_tab_rotate, R.string.panel_size, R.string.a11y_tab_tilt, R.string.a11y_tab_skew, R.string.a11y_tab_pivot)
        val icons = listOf(PanelIcons.TabMove, PanelIcons.TabRotate, PanelIcons.TabSize, PanelIcons.TabTilt, PanelIcons.TabSkew, PanelIcons.TabPivot)
        transformTabs(state.settings.basicTransform, desk).forEach { i ->
            val icon = if (i == 0 && shown == PIVOT_TAB) icons[PIVOT_TAB] else icons[i]
            val usable = i <= SIZE_TAB || selected.kind != "camera" || (i == TILT_TAB && state.scene.depthOrder)
            val tip = if (!app.absentmotion.platform.PlatformFeatures.desktop) "" else when (i) {
                SIZE_TAB -> deskShortcut(R.string.panel_size, "${DeskAlt}${stringResource(R.string.desk_scroll_key)}")
                ROTATE_TAB -> deskShortcut(R.string.a11y_tab_rotate, "${DeskShift}${DeskAlt}${stringResource(R.string.desk_scroll_key)}")
                else -> ""
            }
            Box(cell) { DeskTip(tip, Modifier.fillMaxSize()) { Box(
                Modifier.fillMaxSize().then(if (usable) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Tab) { tab = i } else Modifier.dimmed())
                    .semantics(mergeDescendants = true) { this.selected = tab == i }.padding(start = 10.px, end = 11.px, top = 5.px, bottom = 5.px),
            ) {
                Column(
                    Modifier.fillMaxSize().clip(PanelShape).fadeBackground(tab == i, PanelDark),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    val tint = if (tab == i) Am.Accent else Am.OnDark
                    PanelIcon(icon, tint, size = 17.dp)
                    Text(
                        stringResource(names[if (i == 0 && shown == PIVOT_TAB) PIVOT_TAB else i]), Modifier.fillMaxWidth().padding(horizontal = 2.dp), color = animatedColor(tint), maxLines = 1, softWrap = false,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = if (app.absentmotion.platform.PlatformFeatures.desktop) 14.sp else 9.sp,
                        fontSize = if (app.absentmotion.platform.PlatformFeatures.desktop) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                        autoSize = if (app.absentmotion.platform.PlatformFeatures.desktop) null else androidx.compose.foundation.text.TextAutoSize.StepBased(6.sp, 9.sp, 0.5.sp),
                    )
                }
            } } }
        }
    }
    val sideHeight = LocalSideHeight.current
    if (move && !desk && sideHeight != androidx.compose.ui.unit.Dp.Infinity) androidx.compose.foundation.layout.BoxWithConstraints(modifier.tutorialGuard(any = true).background(Am.Panel)) {
        val beside = maxWidth >= PhoneWidth + 200.dp
        val padWidth = PhoneWidth - 168.px * 2
        val scale = minOf(PhoneWidth / padWidth, (maxHeight - 98.dp - if (beside) 0.dp else 150.dp) / PanelHeight)
        if (scale >= 0.85f) {
            val summary: @Composable (Modifier) -> Unit = { app.absentmotion.ui.editor.panels.TransformSummary(state, layer, it) }
            val control: @Composable () -> Unit = {
                Column(Modifier.width(PhoneWidth)) {
                    Row(Modifier.fillMaxWidth().height(46.dp)) { buttons(Modifier.weight(1f).fillMaxHeight()) }
                    Box(Modifier.fillMaxWidth().height(PanelHeight * scale).background(Am.PanelInset), Alignment.Center) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density * scale, density.fontScale)) {
                            Box(Modifier.width(padWidth).height(PanelHeight).tutorialGuard(), content = pad)
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(52.dp)) { tabs(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
            if (beside) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) { summary(Modifier.weight(1f).fillMaxHeight()); control() }
            else Column(Modifier.fillMaxSize()) { summary(Modifier.weight(1f).fillMaxWidth()); control() }
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxWidth().height(PanelHeight)) {
            Column(Modifier.width(168.px).fillMaxHeight()) { buttons(Modifier.weight(1f).fillMaxWidth()) }
            Box(Modifier.weight(1f).fillMaxHeight().tutorialGuard().background(Am.PanelInset), content = pad)
            Column(Modifier.width(168.px).fillMaxHeight().tutorialGuard()) { tabs(Modifier.weight(1f).fillMaxWidth()) }
        }
    } else Row(modifier.tutorialGuard(any = true).background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) { buttons(Modifier.weight(1f).fillMaxWidth()) }
        Box(Modifier.weight(1f).fillMaxHeight().tutorialGuard().background(if (move) Am.PanelInset else Am.Panel), content = pad)
        if (move) Column(Modifier.width(168.px).then(if (app.absentmotion.platform.PlatformFeatures.desktop) Modifier.height(PanelHeight) else Modifier.fillMaxHeight()).tutorialGuard()) { tabs(Modifier.weight(1f).fillMaxWidth()) }
    }
}

internal val TransformTargets = listOf(
    KeyTarget(PropRef.Location, "0.000000,0.000000,0.000000"),
    KeyTarget(PropRef.Rotation, "0.000000"),
    KeyTarget(PropRef.Scale, "1.000000,1.000000"),
    KeyTarget(PropRef.Tilt, "0.000000,0.000000"),
    KeyTarget(PropRef.Skew, "0.000000,0.000000"),
    KeyTarget(PropRef.Pivot, "0.000000,0.000000,0.000000"),
)

internal fun transformTabs(basic: Boolean, desktop: Boolean = true) = if (basic || !desktop) listOf(0, ROTATE_TAB, SIZE_TAB, SKEW_TAB) else (0..PIVOT_TAB).toList()

internal fun app.absentmotion.model.Layer.hasTilt(): Boolean =
    tilt.any { kotlin.math.abs(it) > 1e-4f } || element(PropRef.Tilt)?.children?.any { it.name == "kf" } == true

internal const val ROTATE_TAB = 1
internal const val SIZE_TAB = 2
internal const val TILT_TAB = 3
internal const val SKEW_TAB = 4
internal const val PIVOT_TAB = 5

@Composable
private fun FrameButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Box(modifier.tutorialGuard().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        PanelIcon(icon, Am.OnDark, description = description)
    }
}

@Composable
fun ValueKeypad(request: KeypadRequest, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val fields = request.fields
    if (fields.isEmpty()) return
    var active by remember(request) { mutableIntStateOf(request.active.coerceIn(0, fields.lastIndex)) }
    val texts = remember(request) { mutableStateListOf<String>().apply { fields.forEach { add(formatPlain(it.value)) } } }
    var selected by remember(request) { mutableStateOf(true) }

    fun type(c: Char) {
        val t = texts[active]
        texts[active] = when {
            selected -> if (c == '.') "0." else "$c"
            c == '.' -> if ('.' in t) t else if (t.isEmpty() || t == "-") t + "0." else "$t."
            t == "0" -> "$c"
            t == "-0" -> "-$c"
            t.length >= MAX_DIGITS -> t
            else -> t + c
        }
        selected = false
    }
    fun backspace() {
        texts[active] = if (selected) "" else texts[active].dropLast(1)
        selected = false
    }
    fun flipSign() {
        val t = texts[active]
        texts[active] = if (t.startsWith("-")) t.drop(1) else "-$t"
        selected = false
    }
    fun done() {
        request.onDone(fields.indices.map { keypadValue(texts[it], fields[it].value, fields[it].min, fields[it].max) })
        onClose()
    }

    BackHandler(onBack = onClose)
    val silent = remember { MutableInteractionSource() }
    val keys = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
    Column(modifier.focusRequester(keys).focusable().onPreviewKeyEvent { event ->
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            androidx.compose.ui.input.key.Key.Backspace -> backspace()
            androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> done()
            androidx.compose.ui.input.key.Key.Tab -> { active = (active + 1) % fields.size; selected = true }
            else -> when (val c = keypadChar(event.utf16CodePoint.toChar())) {
                null -> return@onPreviewKeyEvent false
                '-' -> flipSign()
                else -> type(c)
            }
        }
        true
    }) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black.copy(alpha = 0.8f)).clickable(silent, null, onClick = onClose))
        Column(Modifier.fillMaxWidth().height(247.dp).background(Am.TimelineBg).clickable(silent, null) {}.padding(horizontal = 6.dp)) {
            Spacer(Modifier.height(7.5.dp))
            Row(Modifier.height(40.dp), Arrangement.spacedBy(5.dp)) {
                fields.forEachIndexed { i, field ->
                    KeypadBox(
                        listOfNotNull(field.label.ifEmpty { null }, keypadRange(field.min, field.max, field.unit).takeIf { fields.size <= 2 }).joinToString("  "),
                        texts[i] + field.unit, KeypadLabels[i % KeypadLabels.size], i == active, selected,
                        Modifier.weight(1f), keypadOutside(texts[i], field.min, field.max),
                    ) {
                        active = i
                        selected = true
                    }
                }
            }
            Spacer(Modifier.height(12.8.dp))
            KeyGrid(::type, ::backspace, ::flipSign, ::done)
        }
    }
}

private const val MAX_DIGITS = 12
private val KeypadLabels get() = listOf(PanelGreen, Am.OnDark, Am.Cool)
private val KeyShape = RoundedCornerShape(4.dp)

@Composable
private fun KeypadBox(label: String, text: String, labelColor: Color, active: Boolean, selected: Boolean, modifier: Modifier, outside: Boolean, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(KeyShape).background(if (active) PanelDark else Am.PanelInset)
            .then(if (active) Modifier.border(1.dp, PanelGreen, KeyShape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Text(label, Modifier.padding(start = 5.dp, top = 5.dp), color = labelColor, fontSize = 10.5.sp, maxLines = 1, softWrap = false)
        Text(
            text,
            Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 1.dp)
                .then(if (active && selected) Modifier.background(Am.CoolDeep) else Modifier),
            color = if (outside) Am.Gold else Am.OnDark, fontSize = 17.5.sp, maxLines = 1, softWrap = false,
        )
    }
}

@Composable
private fun KeyGrid(onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSign: () -> Unit, onDone: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(174.2.dp)) {
        val gap = 5.dp
        val w = (maxWidth - gap * 3) / 4
        val h = 39.8.dp
        fun at(col: Int, row: Int, rows: Int = 1) = Modifier.offset((w + gap) * col, (h + gap) * row).size(w, h * rows + gap * (rows - 1))

        val digits = listOf("789", "456", "123")
        digits.forEachIndexed { row, chars ->
            chars.forEachIndexed { col, c -> KeyCap(at(col, row), DigitKey, { onDigit(c) }) { KeyText(c.toString()) } }
        }
        KeyCap(at(0, 3), SymbolKey, onSign) { KeyText("\u00B1") }
        KeyCap(at(1, 3), DigitKey, { onDigit('0') }) { KeyText("0") }
        KeyCap(at(2, 3), SymbolKey, { onDigit('.') }) { KeyText(".") }
        KeyCap(at(3, 0), SymbolKey, onBackspace) { PanelIcon(PanelIcons.Backspace, Am.OnDark, description = stringResource(R.string.a11y_backspace)) }
        KeyCap(at(3, 1, 3), TickKey, onDone) { PanelIcon(PanelIcons.Check, Am.OnDark, size = 30.dp, description = stringResource(R.string.done)) }
    }
}

private val DigitKey get() = Am.KeyDigit
private val SymbolKey get() = Am.KeySymbol
private val TickKey get() = Am.Well

@Composable
private fun KeyCap(modifier: Modifier, color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.shadow(2.dp, KeyShape).clip(KeyShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
private fun KeyText(text: String) {
    Text(text, color = Am.OnDark, fontSize = 21.5.sp, maxLines = 1, softWrap = false)
}
