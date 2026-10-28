package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialTarget
import app.absentmotion.ui.tutorial.tutorialGuard
import app.absentmotion.model.finiteFloat
import app.absentmotion.platform.BackHandler
import androidx.annotation.StringRes
import androidx.compose.runtime.SideEffect
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.audio.gain
import app.absentmotion.model.PropRef
import app.absentmotion.model.contours
import app.absentmotion.model.SpeedRef
import app.absentmotion.model.atVideoTime
import app.absentmotion.ui.editor.panels.SpeedPanel
import app.absentmotion.model.formatColor
import app.absentmotion.ui.editor.panels.EasingPanel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.editDrawing
import app.absentmotion.editor.editPoints
import app.absentmotion.editor.canCrop
import app.absentmotion.editor.openCrop
import app.absentmotion.editor.moveClipEdges
import app.absentmotion.editor.split
import app.absentmotion.editor.stepBack
import app.absentmotion.editor.trimEnd
import app.absentmotion.editor.trimStart
import app.absentmotion.ui.editor.panels.BlendingPanel
import app.absentmotion.ui.editor.panels.BorderShadowPanel
import app.absentmotion.ui.editor.panels.CameraPanel
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
fun LayerTools(state: EditorState, modifier: Modifier = Modifier, side: Boolean = false) {
    val drawn = state.selected?.shape?.isEmpty() == true
    var properties by remember(state.selectedId) { mutableStateOf(false) }
    var moving by remember(state.selectedId) { mutableStateOf(false) }
    var captions by remember(state.selectedId) { mutableStateOf(false) }
    if (captions) CaptionsSheet(state, clipId = state.selectedId) { captions = false }
    if (properties) {
        ElementProperties(state, { properties = false }, modifier)
        return
    }
    Column(modifier.background(Am.Panel).verticalScroll(rememberScrollState()).padding(start = 62.px, top = 42.px, end = 62.px)) {
        Row(Modifier.fillMaxWidth().height(107.px)) {
            SmallTool(PanelIcons.Speed, stringResource(R.string.a11y_speed), Modifier.width(183.px), ToolShape, enabled = state.selected?.mediaFill?.video == true || state.selected?.kind == "audio") { state.panel = EditorPanel.Speed }
            Spacer(Modifier.width(17.px))
            Row(Modifier.weight(1f).fillMaxHeight(), Arrangement.spacedBy(3.px)) {
                val clip = state.selected
                val past = clip != null && state.sampleMs > clip.endTime
                val before = clip != null && state.timeMs < clip.startTime
                SmallTool(PanelIcons.TrimLeft, stringResource(if (before) R.string.a11y_extend_start else R.string.a11y_trim_start), Modifier.weight(1f), ToolShape.copy(topEnd = Square, bottomEnd = Square), enabled = !past) {
                    if (before) state.moveClipEdges(false, true) else state.trimStart()
                }
                SmallTool(if (past) ChromeIcons.SkipForward else if (before) ChromeIcons.SkipBack else PanelIcons.Split, stringResource(if (past || before) R.string.a11y_move_to_playhead else R.string.a11y_split), Modifier.weight(1f).tutorialTarget("tool_split"), RoundedCornerShape(0.dp)) {
                    if (past || before) state.moveClipEdges(past, false) else state.split()
                }
                SmallTool(PanelIcons.TrimRight, stringResource(if (past) R.string.a11y_extend_end else R.string.a11y_trim_end), Modifier.weight(1f), ToolShape.copy(topStart = Square, bottomStart = Square), enabled = !before) {
                    if (past) state.moveClipEdges(true, true) else state.trimEnd()
                }
            }
            Spacer(Modifier.width(17.px))
            val sound = state.selected?.let { it.kind == "audio" || it.mediaFill?.video == true } == true
            SmallTool(if (!sound || (state.selected?.gain ?: 1f) <= 0f) PanelIcons.AudioMuted else AudioIcons.Speaker, stringResource(R.string.volume_volume), Modifier.width(183.px).tutorialTarget("tool_volume"), ToolShape, enabled = sound) { state.volumeOpen = true }
        }
        Spacer(Modifier.height(28.px))
        val kind = state.selected?.kind
        if (kind == "camera" && moving) state.selectedId?.let { CameraMovesSheet(state, it) { moving = false } }
        val presets = Tile(Presets) { openPresets(state) }
        val effects = Tile(Effects) { state.effectIndex = -1; state.panel = EditorPanel.Effects }
        val transform = Tile(MoveTransform) { state.panel = EditorPanel.MoveTransform }
        val blending = Tile(Blending) { state.panel = EditorPanel.Blending }
        val rows = when (kind) {
            "audio" -> listOf(
                listOf(presets, Tile(Effects) { openEffectBrowser(state, "audio") }, Tile(AiTools) { openEffectBrowser(state, "ai") }),
            )
            "camera" -> listOf(
                listOf(Tile(CameraOptions) { state.panel = EditorPanel.Camera }, transform, effects),
                listOf(presets, Tile(CameraMoves) { state.playing = false; moving = true }),
            )
            "model" -> listOf(
                listOf(Tile(ModelOptions) { state.panel = EditorPanel.Model }, blending),
                listOf(transform, presets, effects),
            )
            "nullobj" -> listOf(listOf(transform, presets, effects))
            else -> {
                val edit = when {
                    kind == "text" -> Tile(EditText) { state.panel = EditorPanel.EditText }
                    kind == "embedScene" -> if (state.selected?.link != null) Tile(ElementPropsButton) { properties = true } else null
                    kind == "drawing" -> Tile(EditDrawing) { state.editDrawing() }
                    drawn -> Tile(EditPoints, enabled = state.selected?.contours != null) { state.editPoints() }
                    else -> Tile(EditShape) { state.panel = EditorPanel.EditShape }
                }
                listOf(
                    listOfNotNull(if (state.selected?.canCrop == true) Tile(Crop) { state.openCrop() } else null, Tile(ColorFill) { state.panel = EditorPanel.ColorFill }, Tile(BorderShadow) { state.panel = EditorPanel.BorderShadow }, blending),
                    listOfNotNull(transform, edit, presets, effects),
                    *if (state.selected?.mediaFill?.video == true) arrayOf(listOf(Tile(AiTools) { openEffectBrowser(state, "ai") }, Tile(CaptionTools) { state.playing = false; captions = true })) else emptyArray(),
                )
            }
        }
        val shown = if (side) rows.flatten().chunked(2) else rows
        shown.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(17.px))
            Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
                for (tile in row) ToolButton(tile.tool, Modifier.weight(1f).tutorialTarget(if (tile.tool === MoveTransform) "tool_transform" else if (tile.tool === Effects) "tool_effects" else null), tile.enabled, tile.onClick)
                if (side) repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
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

private class Tile(val tool: Tool, val enabled: Boolean = true, val onClick: () -> Unit)

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
private val Presets = Tool(R.string.presets, PanelIcons.Star, 19.4f)
private val Effects = Tool(R.string.layer_tool_effects, PanelIcons.Effects, 19.6f)
private val Crop = Tool(R.string.layer_tool_crop, PanelIcons.Crop, 18.1f)
private val CaptionTools = Tool(R.string.auto_captions, ChromeIcons.Captions, 19.4f)
private val AiTools = Tool(R.string.ai, PanelIcons.Bot, 19.4f)

@Composable
private fun SmallTool(icon: ImageVector, description: String, modifier: Modifier, shape: RoundedCornerShape, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.tutorialGuard().fillMaxHeight().pressScale(press, TilePress).then(if (enabled) Modifier else Modifier.dimmed()).clip(shape).background(Am.Button)
            .then(if (enabled) Modifier.clickable(press, LocalIndication.current, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon(icon, Am.OnDark, description = description)
    }
}

@Composable
private fun ToolButton(tool: Tool, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.tutorialGuard().fillMaxHeight().pressScale(press, TilePress).then(if (enabled) Modifier else Modifier.dimmed()).clip(ToolShape).background(Am.Button)
            .then(if (enabled) Modifier.clickable(press, LocalIndication.current, onClick = onClick) else Modifier),
    ) {
        PanelIcon(tool.icon, Am.OnDark, Modifier.align(Alignment.TopCenter).offset(y = (tool.iconY - 12f).dp))
        Text(
            stringResource(tool.label), Modifier.align(Alignment.TopCenter).offset(y = 32.dp).fillMaxWidth().height(24.dp).padding(horizontal = 4.dp)
                .wrapContentHeight(Alignment.CenterVertically),
            color = ToolLabel, lineHeight = 11.5.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(7.sp, 10.sp, 0.5.sp),
        )
    }
}

@Composable
fun LayerPanel(state: EditorState, modifier: Modifier = Modifier) {
    val selected = state.selected ?: return
    val panel = state.panel
    app.absentmotion.ui.editor.panels.BorderShadowLifetime(state, selected.id, panel)
    val layer = if (panel == EditorPanel.Speed) selected.atVideoTime(state.sampleMs, state.scene.fps) else selected
    if (panel == EditorPanel.Effects) {
        app.absentmotion.ui.editor.panels.EffectsPanel(state, modifier)
        return
    }
    if (panel == EditorPanel.Crop) {
        app.absentmotion.ui.editor.panels.CropPanel(state, modifier)
        return
    }
    val move = panel == EditorPanel.MoveTransform
    var tab by remember(selected.kind) { mutableIntStateOf(0) }
    val catalog by state.engine.effects.collectAsState()
    val turn = if (move) turnFor(selected, catalog, tab) else null
    var menuOpen by remember { mutableStateOf(false) }
    val named = panel != EditorPanel.EditShape && panel != EditorPanel.Camera && panel != EditorPanel.Model && !(move && tab == ROTATE_TAB && turn != null)
    val known = if (!named) null else when {
        move -> if (selected.kind == "camera" && tab == SIZE_TAB) KeyTarget(app.absentmotion.model.CameraFov, "60.000000") else TransformTargets[tab]
        panel == EditorPanel.Speed -> KeyTarget(SpeedRef, layer.node["speed"] ?: "1")
        panel == EditorPanel.ColorFill && layer.fillType == "color" -> KeyTarget(PropRef.FillColor, formatColor(layer.fillColor))
        panel == EditorPanel.BorderShadow -> borderShadowTarget(layer)
        panel == EditorPanel.Blending -> KeyTarget(OpacityRef, "1.000000")
        else -> null
    }
    if (named) SideEffect { state.keyTarget = known }
    if (state.easingOpen) {
        EasingPanel(state, modifier)
        return
    }
    val target = if (named) known else state.keyTarget
    Row(modifier.tutorialGuard(any = true).background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) {
            FrameButton(PanelIcons.Back, stringResource(R.string.topbar_back), Modifier.weight(1f).fillMaxWidth()) { state.stepBack() }
            if (target == null) {
                FrameButton(PanelIcons.Keyframe, stringResource(R.string.a11y_add_keyframe), Modifier.weight(1f).fillMaxWidth().dimmed(), enabled = false)
                FrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), Modifier.weight(1f).fillMaxWidth().dimmed(), enabled = false)
            } else {
                val onKeyframe = layer.onKeyframe(target.ref)
                FrameButton(if (onKeyframe) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (onKeyframe) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), Modifier.weight(1f).fillMaxWidth().tutorialTarget("keyframe")) {
                    state.toggleKeyframe()
                }
                FrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), Modifier.weight(1f).fillMaxWidth().tutorialTarget("easing")) { if (layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true }
            }
            if (move) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    FrameButton(PanelIcons.More, stringResource(R.string.effects_more), Modifier.fillMaxSize()) { menuOpen = true }
                    Presence(menuOpen) { TransformMenu(state, tab, turn) { menuOpen = false } }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().tutorialGuard().background(if (move) Am.PanelInset else Am.Panel)) {
            if (panel == EditorPanel.BorderShadow) BorderShadowTabs(Modifier.align(Alignment.CenterEnd).width(168.px).fillMaxHeight().background(Am.Panel))
            when (panel) {
                EditorPanel.MoveTransform -> Swap(tab, Modifier.fillMaxSize().tutorialTarget("pad"), rise = 0.dp) { MoveTransformPanel(state, layer, tab, turn, Modifier.fillMaxSize()) }
                EditorPanel.ColorFill -> ColorFillPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.EditShape -> EditShapePanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Speed -> SpeedPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.BorderShadow -> BorderShadowPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Blending -> BlendingPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Camera -> CameraPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Model -> ModelPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Tools, EditorPanel.EditText, EditorPanel.Effects, EditorPanel.Crop -> {}
            }
            Box(Modifier.width(2.px).fillMaxHeight().background(PanelDark))
            if (move) Box(Modifier.align(Alignment.CenterEnd).width(2.px).fillMaxHeight().background(PanelDark))
        }
        if (move) {
            Column(Modifier.width(168.px).fillMaxHeight().tutorialGuard()) {
                val names = listOf(R.string.a11y_tab_move, R.string.a11y_tab_rotate, R.string.panel_size, R.string.a11y_tab_tilt, R.string.a11y_tab_skew, R.string.a11y_tab_pivot)
                listOf(PanelIcons.TabMove, PanelIcons.TabRotate, PanelIcons.TabSize, PanelIcons.TabTilt, PanelIcons.TabSkew, PanelIcons.TabPivot).forEachIndexed { i, icon ->
                    val usable = i <= SIZE_TAB || selected.kind != "camera"
                    Box(
                        Modifier.weight(1f).fillMaxWidth().then(if (usable) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Tab) { tab = i } else Modifier.dimmed())
                            .semantics(mergeDescendants = true) { this.selected = tab == i }.padding(start = 10.px, end = 11.px, top = 5.px, bottom = 5.px),
                    ) {
                        Column(
                            Modifier.fillMaxSize().clip(PanelShape).fadeBackground(tab == i, PanelDark),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            val tint = if (tab == i) Am.Accent else Am.OnDark
                            PanelIcon(icon, tint, size = 17.dp)
                            Text(
                                stringResource(names[i]), Modifier.fillMaxWidth().padding(horizontal = 2.dp), color = animatedColor(tint), maxLines = 1, softWrap = false,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 9.sp,
                                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(6.sp, 9.sp, 0.5.sp),
                            )
                        }
                    }
                }
            }
        }
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
