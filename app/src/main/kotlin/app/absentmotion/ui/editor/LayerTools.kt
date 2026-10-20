package app.absentmotion.ui.editor

import app.absentmotion.platform.BackHandler
import androidx.annotation.StringRes
import androidx.compose.runtime.SideEffect
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.editor.deleteSpeedKeyframes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.editDrawing
import app.absentmotion.editor.editPoints
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
import app.absentmotion.ui.editor.panels.PanelGreen
import app.absentmotion.ui.editor.panels.PanelIcon
import app.absentmotion.ui.editor.panels.PanelShape
import app.absentmotion.ui.editor.panels.dimmed
import app.absentmotion.ui.editor.panels.formatPlain
import app.absentmotion.ui.editor.panels.px
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.fadeBackground
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
fun LayerTools(state: EditorState, modifier: Modifier = Modifier) {
    val drawn = state.selected?.shape?.isEmpty() == true
    var properties by remember(state.selectedId) { mutableStateOf(false) }
    if (properties) {
        ElementProperties(state, { properties = false }, modifier)
        return
    }
    Column(modifier.background(Am.Panel).padding(start = 62.px, top = 42.px, end = 62.px)) {
        Row(Modifier.fillMaxWidth().height(107.px)) {
            SmallTool(PanelIcons.Speed, stringResource(R.string.a11y_speed), Modifier.width(183.px), ToolShape, enabled = state.selected?.mediaFill?.video == true || state.selected?.kind == "audio") { state.panel = EditorPanel.Speed }
            Spacer(Modifier.width(17.px))
            Row(Modifier.weight(1f).fillMaxHeight(), Arrangement.spacedBy(3.px)) {
                val clip = state.selected
                val past = clip != null && state.timeMs > clip.endTime
                val before = clip != null && state.timeMs < clip.startTime
                SmallTool(PanelIcons.TrimLeft, stringResource(if (before) R.string.a11y_extend_start else R.string.a11y_trim_start), Modifier.weight(1f), ToolShape.copy(topEnd = Square, bottomEnd = Square), enabled = !past) {
                    if (before) state.moveClipEdges(false, true) else state.trimStart()
                }
                SmallTool(if (past) ChromeIcons.SkipForward else if (before) ChromeIcons.SkipBack else PanelIcons.Split, stringResource(if (past || before) R.string.a11y_move_to_playhead else R.string.a11y_split), Modifier.weight(1f), RoundedCornerShape(0.dp)) {
                    if (past || before) state.moveClipEdges(past, false) else state.split()
                }
                SmallTool(PanelIcons.TrimRight, stringResource(if (past) R.string.a11y_extend_end else R.string.a11y_trim_end), Modifier.weight(1f), ToolShape.copy(topStart = Square, bottomStart = Square), enabled = !before) {
                    if (past) state.moveClipEdges(true, true) else state.trimEnd()
                }
            }
            Spacer(Modifier.width(17.px))
            val sound = state.selected?.let { it.kind == "audio" || it.mediaFill?.video == true } == true
            SmallTool(if (!sound || (state.selected?.gain ?: 1f) <= 0f) PanelIcons.AudioMuted else AudioIcons.Speaker, stringResource(R.string.volume_volume), Modifier.width(183.px), ToolShape, enabled = sound) { state.volumeOpen = true }
        }
        Spacer(Modifier.height(28.px))
        if (state.selected?.kind == "audio") {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val press = remember { MutableInteractionSource() }
                Column(Modifier.fillMaxSize().pressScale(press, TilePress).clip(ToolShape).background(Am.Button).clickable(press, LocalIndication.current) { openPresets(state) },
                    Arrangement.Center, Alignment.CenterHorizontally) {
                    PanelIcon(PanelIcons.Star, Am.OnDark, size = 28.dp)
                    Text(stringResource(R.string.presets), color = ToolLabel, fontSize = 13.sp)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp, 28.dp).clip(ToolShape)
                    .background(Am.Button).clickable { state.effectBrowserOpen = true }, Alignment.Center) {
                    Text(stringResource(R.string.ai), color = Am.OnDark, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(49.px))
            return@Column
        }
        if (state.selected?.kind == "camera") {
            var moving by remember(state.selectedId) { mutableStateOf(false) }
            if (moving) state.selectedId?.let { CameraMovesSheet(state, it) { moving = false } }
            Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
                ToolButton(CameraOptions, Modifier.weight(1f)) { state.panel = EditorPanel.Camera }
                ToolButton(MoveTransform, Modifier.weight(1f)) { state.panel = EditorPanel.MoveTransform }
                ToolButton(Effects, Modifier.weight(1f)) { state.effectIndex = -1; state.panel = EditorPanel.Effects }
            }
            Spacer(Modifier.height(17.px))
            Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
                ToolButton(Presets, Modifier.weight(1f)) { openPresets(state) }
                ToolButton(CameraMoves, Modifier.weight(1f)) { state.playing = false; moving = true }
            }
            return@Column
        }
        if (state.selected?.kind == "model") {
            Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
                ToolButton(ModelOptions, Modifier.weight(1f)) { state.panel = EditorPanel.Model }
                ToolButton(Blending, Modifier.weight(1f)) { state.panel = EditorPanel.Blending }
            }
            Spacer(Modifier.height(17.px))
            Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
                ToolButton(MoveTransform, Modifier.weight(1f)) { state.panel = EditorPanel.MoveTransform }
                ToolButton(Presets, Modifier.weight(1f)) { openPresets(state) }
                ToolButton(Effects, Modifier.weight(1f)) { state.effectIndex = -1; state.panel = EditorPanel.Effects }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
            ToolButton(ColorFill, Modifier.weight(1f)) { state.panel = EditorPanel.ColorFill }
            ToolButton(BorderShadow, Modifier.weight(1f)) { state.panel = EditorPanel.BorderShadow }
            ToolButton(Blending, Modifier.weight(1f)) { state.panel = EditorPanel.Blending }
        }
        Spacer(Modifier.height(17.px))
        Row(Modifier.fillMaxWidth().height(160.px), Arrangement.spacedBy(17.px)) {
            ToolButton(MoveTransform, Modifier.weight(1f)) { state.panel = EditorPanel.MoveTransform }
            if (state.selected?.kind == "text") ToolButton(EditText, Modifier.weight(1f)) { state.panel = EditorPanel.EditText }
            else if (state.selected?.kind == "embedScene") {
                if (state.selected?.link != null) ToolButton(ElementPropsButton, Modifier.weight(1f)) { properties = true }
            }
            else if (state.selected?.kind == "drawing") ToolButton(EditDrawing, Modifier.weight(1f)) { state.editDrawing() }
            else if (drawn) ToolButton(EditPoints, Modifier.weight(1f), enabled = state.selected?.contours != null) { state.editPoints() }
            else ToolButton(EditShape, Modifier.weight(1f)) { state.panel = EditorPanel.EditShape }
            ToolButton(Presets, Modifier.weight(1f)) { openPresets(state) }
            ToolButton(Effects, Modifier.weight(1f)) { state.effectIndex = -1; state.panel = EditorPanel.Effects }
        }
    }
}

private fun openPresets(state: EditorState) {
    state.playing = false
    state.presetsOpen = true
}

private val ToolShape = RoundedCornerShape(4.dp)
private val Square = androidx.compose.foundation.shape.CornerSize(0.dp)
private val ToolLabel get() = Am.OnDarkDim

private class Tool(@StringRes val label: Int, val icon: ImageVector, val iconY: Float)

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

@Composable
private fun SmallTool(icon: ImageVector, description: String, modifier: Modifier, shape: RoundedCornerShape, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxHeight().pressScale(press, TilePress).then(if (enabled) Modifier else Modifier.dimmed()).clip(shape).background(Am.Button)
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
        modifier.fillMaxHeight().pressScale(press, TilePress).then(if (enabled) Modifier else Modifier.dimmed()).clip(ToolShape).background(Am.Button)
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
    val move = panel == EditorPanel.MoveTransform
    var tab by remember(selected.kind) { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var askDeleteSpeed by remember { mutableStateOf(false) }
    val named = panel != EditorPanel.EditShape && panel != EditorPanel.Camera && panel != EditorPanel.Model
    val known = if (!named) null else when {
        move -> if (selected.kind == "camera" && tab == 3) KeyTarget(app.absentmotion.model.CameraFov, "60.000000") else TransformTargets[tab]
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
    if (askDeleteSpeed) AlertDialog(onDismissRequest = { askDeleteSpeed = false }, title = { Text(stringResource(R.string.remove_every_keyframe)) },
        confirmButton = { TextButton({ askDeleteSpeed = false; state.deleteSpeedKeyframes() }) { Text(stringResource(R.string.delete), color = Am.Accent) } },
        dismissButton = { TextButton({ askDeleteSpeed = false }) { Text(stringResource(R.string.cancel), color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark)
    Row(modifier.background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) {
            FrameButton(PanelIcons.Back, stringResource(R.string.topbar_back), Modifier.weight(1f).fillMaxWidth()) { state.stepBack() }
            if (target == null) {
                FrameButton(PanelIcons.Keyframe, stringResource(R.string.a11y_add_keyframe), Modifier.weight(1f).fillMaxWidth().dimmed(), enabled = false)
                FrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), Modifier.weight(1f).fillMaxWidth().dimmed(), enabled = false)
            } else {
                val onKeyframe = layer.onKeyframe(target.ref)
                FrameButton(if (onKeyframe) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (onKeyframe) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), Modifier.weight(1f).fillMaxWidth()) {
                    if (panel == EditorPanel.Speed && onKeyframe) askDeleteSpeed = true else state.toggleKeyframe()
                }
                FrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), Modifier.weight(1f).fillMaxWidth()) { if (layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true }
            }
            if (move) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    FrameButton(PanelIcons.More, stringResource(R.string.effects_more), Modifier.fillMaxSize()) { menuOpen = true }
                    Presence(menuOpen) { TransformMenu(state, tab) { menuOpen = false } }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(if (move) Am.PanelInset else Am.Panel)) {
            if (panel == EditorPanel.BorderShadow) BorderShadowTabs(Modifier.align(Alignment.CenterEnd).width(168.px).fillMaxHeight().background(Am.Panel))
            when (panel) {
                EditorPanel.MoveTransform -> Swap(tab, Modifier.fillMaxSize(), rise = 0.dp) { MoveTransformPanel(state, layer, tab, Modifier.fillMaxSize()) }
                EditorPanel.ColorFill -> ColorFillPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.EditShape -> EditShapePanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Speed -> SpeedPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.BorderShadow -> BorderShadowPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Blending -> BlendingPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Camera -> CameraPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Model -> ModelPanel(state, layer, Modifier.fillMaxSize())
                EditorPanel.Tools, EditorPanel.EditText, EditorPanel.Effects -> {}
            }
            Box(Modifier.width(2.px).fillMaxHeight().background(PanelDark))
            if (move) Box(Modifier.align(Alignment.CenterEnd).width(2.px).fillMaxHeight().background(PanelDark))
        }
        if (move) {
            Column(Modifier.width(168.px).fillMaxHeight()) {
                val names = listOf(R.string.a11y_tab_move, R.string.a11y_tab_rotate, R.string.a11y_tab_tilt, R.string.panel_size, R.string.a11y_tab_skew)
                listOf(PanelIcons.TabMove, PanelIcons.TabRotate, PanelIcons.TabTilt, PanelIcons.TabSize, PanelIcons.TabSkew).forEachIndexed { i, icon ->
                    val usable = i < 2 || i == 3 || selected.kind != "camera"
                    Box(Modifier.weight(1f).fillMaxWidth().then(if (usable) Modifier.clickable { tab = i } else Modifier.dimmed()).padding(start = 10.px, end = 11.px, top = 11.px, bottom = 11.px)) {
                        Box(
                            Modifier.fillMaxSize().clip(PanelShape).fadeBackground(tab == i, PanelDark),
                            contentAlignment = Alignment.Center,
                        ) {
                            PanelIcon(icon, if (tab == i) Am.Accent else Am.OnDark, description = stringResource(names[i]))
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
    KeyTarget(PropRef.Tilt, "0.000000,0.000000"),
    KeyTarget(PropRef.Scale, "1.000000,1.000000"),
    KeyTarget(PropRef.Skew, "0.000000,0.000000"),
)

@Composable
private fun FrameButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Box(modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
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
        request.onDone(fields.indices.map { texts[it].toFloatOrNull() ?: fields[it].value })
        onClose()
    }

    BackHandler(onBack = onClose)
    val silent = remember { MutableInteractionSource() }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black.copy(alpha = 0.8f)).clickable(silent, null, onClick = onClose))
        Column(Modifier.fillMaxWidth().height(247.dp).background(Am.TimelineBg).clickable(silent, null) {}.padding(horizontal = 6.dp)) {
            Spacer(Modifier.height(7.5.dp))
            Row(Modifier.height(40.dp), Arrangement.spacedBy(5.dp)) {
                fields.forEachIndexed { i, field ->
                    KeypadBox(
                        field.label, texts[i], KeypadLabels[i % KeypadLabels.size], i == active, selected,
                        Modifier.weight(1f),
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
private fun KeypadBox(label: String, text: String, labelColor: Color, active: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
            color = Am.OnDark, fontSize = 17.5.sp, maxLines = 1, softWrap = false,
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
