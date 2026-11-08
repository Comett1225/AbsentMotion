package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FullColor
import app.absentmotion.editor.changeSettings
import app.absentmotion.editor.rename
import app.absentmotion.editor.setElementOption
import app.absentmotion.editor.toggleHidden
import app.absentmotion.model.Layer
import app.absentmotion.model.shownName
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am

@Composable
internal fun DeskProject(state: EditorState, onDismiss: () -> Unit) {
    DeskDialog(stringResource(R.string.topbar_project_settings), 440.dp, onDismiss, onConfirm = onDismiss) {
        Column(Modifier.deskVerticalScroll().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (!state.inGroup) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.desk_name), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
                DeskNameField(state.scene.title, Modifier.weight(1f)) { state.rename(it) }
            }
            DeskSettings(state, heading = false)
        }
    }
}

@Composable
internal fun DeskProjectPanel(state: EditorState, modifier: Modifier = Modifier) {
    val layers = remember(state.scene) { state.scene.layers.asReversed() }
    val list = rememberLazyListState()
    LazyColumn(modifier.deskScrollbar(list).padding(horizontal = 12.dp), list, contentPadding = PaddingValues(bottom = 12.dp)) {
        if (!state.inGroup) item(key = "name") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.desk_name), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
                DeskNameField(state.scene.title, Modifier.weight(1f)) { state.rename(it) }
            }
        }
        item(key = "settings") { DeskSettings(state) }
        item(key = "layers-title") { DeskHeading(stringResource(R.string.desk_layers), Modifier.padding(top = 16.dp, bottom = 4.dp)) }
        items(layers, key = { it.id }) { layer ->
            Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).hoverFill(6.dp, Am.Field).handCursor().clickable { state.selectedId = layer.id; state.panel = EditorPanel.Tools }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(deskLayerIcon(layer), null, Modifier.size(18.dp), tint = Am.OnDarkDim)
                Text(layer.shownName(), Modifier.weight(1f).padding(horizontal = 8.dp), color = if (layer.hidden) Am.OnDarkDim else Am.OnDark, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val label = stringResource(if (layer.hidden) R.string.a11y_show_layer else R.string.a11y_hide_layer)
                BarButton(if (layer.hidden) TimelineIcons.EyeOff else TimelineIcons.Eye, label, Modifier.size(32.dp), iconSize = androidx.compose.ui.unit.DpSize(18.dp, 18.dp)) { state.toggleHidden(layer.id) }
            }
        }
    }
}

internal fun deskLayerIcon(layer: Layer) = when {
    layer.kind == "audio" -> ChromeIcons.AudioTab
    layer.kind == "text" -> ChromeIcons.TextTool
    layer.kind == "camera" -> PanelIcons.Camera
    layer.kind == "light" -> PanelIcons.Brightness
    layer.kind == "drawing" -> ChromeIcons.Freehand
    layer.kind == "embedScene" -> SelectionIcons.Group
    layer.kind == "null" -> ChromeIcons.NullObject
    layer.mediaFill != null -> ChromeIcons.MediaTab
    else -> ChromeIcons.ShapeTab
}

@Composable
internal fun DeskHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun DeskSettings(state: EditorState, modifier: Modifier = Modifier, heading: Boolean = true) {
    val scene = state.scene
    if (state.inGroup) {
        val options = listOf("off" to R.string.group_retime_off) + RetimingOptions
        Column(modifier.fillMaxWidth()) {
            DeskSetting(stringResource(R.string.menu_retiming)) {
                val shown = options.firstOrNull { it.first == scene.retime } ?: options[0]
                DeskSelect(stringResource(shown.second), options.map { stringResource(it.second) }) { state.setElementOption("retime", options[it].first) }
            }
        }
        return
    }
    val focus = LocalFocusManager.current
    val derived = aspectOf(scene.width, scene.height)
    var custom by remember { mutableStateOf(derived == CUSTOM) }
    val aspect = if (custom) CUSTOM else derived
    val resolution = minOf(scene.width, scene.height)
    fun change(width: Int = scene.width, height: Int = scene.height, fps: Int = scene.fps, bgColor: Int = scene.bgColor) = state.changeSettings(width, height, fps, bgColor)
    fun resize(ratio: Aspect, short: Int) { val (w, h) = sizeFor(ratio, short); change(width = w, height = h) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (heading) DeskHeading(stringResource(R.string.topbar_project_settings), Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Aspects.forEach { a -> DeskChip(a.key, aspect == a.key, Modifier.weight(1f)) { focus.clearFocus(); custom = false; resize(a, resolution) } }
            DeskChip(stringResource(R.string.menu_custom), aspect == CUSTOM, Modifier.weight(1.5f)) { focus.clearFocus(); custom = true }
        }
        DeskSetting(stringResource(if (scene.isElement) R.string.menu_asset_resolution else R.string.menu_resolution)) {
            if (aspect == CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DeskNumber(scene.width, Modifier.width(64.dp)) { change(width = it) }
                Text("×", color = Am.OnDarkDim, fontSize = 13.sp)
                DeskNumber(scene.height, Modifier.width(64.dp)) { change(height = it) }
                DeskSavedSizes(state.settings, scene.width, scene.height) { w, h -> change(width = w, height = h) }
            } else {
                val ratio = Aspects.first { it.key == aspect }
                DeskSelect("${resolution}p", Resolutions.map { it.second }) { resize(ratio, Resolutions[it].first) }
            }
        }
        DeskSetting(stringResource(R.string.menu_frame_rate)) { DeskSelect("${scene.fps} fps", FrameRates.map { "$it fps" }) { change(fps = FrameRates[it]) } }
        DeskSetting(stringResource(R.string.menu_background)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val presets = Backgrounds.map { it.argb }
                val matched = Backgrounds.firstOrNull { it.argb == scene.bgColor || (it.argb ushr 24 == 0 && scene.bgColor ushr 24 == 0) }
                Backgrounds.forEach { choice -> DeskSwatch(Color(choice.argb), choice.argb ushr 24 == 0, matched === choice, stringResource(choice.name)) { change(bgColor = choice.argb) } }
                DeskSwatch(Color(scene.bgColor), false, matched == null || scene.bgColor !in presets, stringResource(R.string.menu_custom), picker = true) {
                    focus.clearFocus()
                    state.fullColor = FullColor(scene.bgColor) { color, drag -> state.edit(if (drag) "desk-background" else null) { it.withSettings(it.width, it.height, it.fps, color) } }
                }
            }
        }
        DeskSetting(stringResource(R.string.desk_duration)) { Text(TimelineMath.timecode(scene.lastFrame + 1, scene.fps), color = Am.OnDark, fontSize = 13.sp) }
        if (scene.isElement) {
            DeskSetting(stringResource(R.string.menu_precompose)) {
                val shown = PrecomposeOptions.firstOrNull { it.first == scene.precompose } ?: PrecomposeOptions[0]
                DeskSelect(stringResource(shown.second), PrecomposeOptions.map { stringResource(it.second) }) { state.setElementOption("precompose", PrecomposeOptions[it].first) }
            }
            DeskSetting(stringResource(R.string.menu_retiming)) {
                val shown = RetimingOptions.firstOrNull { it.first == scene.retime } ?: RetimingOptions[0]
                DeskSelect(stringResource(shown.second), RetimingOptions.map { stringResource(it.second) }) { state.setElementOption("retime", RetimingOptions[it].first) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).hoverFill(6.dp, Color.White.copy(alpha = .08f)).handCursor().clickable { state.historyOpen = true }.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.earlier_versions), Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
            Icon(ChromeIcons.ChevronRight, null, Modifier.size(20.dp), tint = Am.OnDarkDim)
        }
    }
}

@Composable
private fun DeskSetting(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}

@Composable
private fun DeskChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) Am.Accent else Am.Field).hoverFill(6.dp, Color.White.copy(alpha = .08f)).handCursor().clickable(onClick = onClick), Alignment.Center) {
        Text(label, color = if (selected) Am.OnAccent else Am.OnDark, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DeskSwatch(color: Color, transparent: Boolean, selected: Boolean, description: String, picker: Boolean = false, onClick: () -> Unit) {
    app.absentmotion.platform.Hint(description) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (transparent) Color(0xFFCCCCCC) else color)
            .border(if (selected) 2.dp else 1.dp, if (selected) Am.Accent else Color(0x40808080), CircleShape).handCursor().clickable(onClick = onClick), Alignment.Center) {
            if (transparent) Text("/", color = Color(0xFFE44D4D), fontSize = 14.sp)
            if (picker) Icon(ChromeIcons.Pencil, null, Modifier.size(12.dp), tint = if (color.red * 0.3f + color.green * 0.6f + color.blue * 0.1f > 0.55f) Color.Black else Color.White)
        }
    }
}

@Composable
private fun DeskSelect(value: String, options: List<String>, pick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Box {
        Row(Modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).hoverFill(6.dp, Color.White.copy(alpha = .08f)).handCursor().clickable { focus.clearFocus(); open = true }.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
            Icon(ChromeIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(20.dp), tint = Am.OnDarkDim)
        }
        DropdownMenu(open, { open = false }, Modifier.background(Am.Field), shape = RoundedCornerShape(10.dp)) {
            options.forEachIndexed { index, option -> DropdownMenuItem(text = { Text(option, color = Am.OnDark, fontSize = 13.sp) }, onClick = { open = false; pick(index) }) }
        }
    }
}

@Composable
private fun DeskSavedSizes(settings: app.absentmotion.library.AppSettings, width: Int, height: Int, onPick: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val saved = settings.savedSizes
    Box {
        app.absentmotion.platform.Hint(stringResource(R.string.newproject_saved_sizes)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).hoverFill(6.dp, Color.White.copy(alpha = .08f)).handCursor().clickable { focus.clearFocus(); open = true }, Alignment.Center) {
                Icon(ChromeIcons.SavePreset, stringResource(R.string.newproject_saved_sizes), Modifier.size(18.dp), tint = Am.OnDarkDim)
            }
        }
        DropdownMenu(open, { open = false }, Modifier.background(Am.Field), shape = RoundedCornerShape(10.dp)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.newproject_save_size_title), color = Am.Accent, fontSize = 13.sp) }, onClick = { open = false; naming = true })
            saved.forEach { size ->
                DropdownMenuItem(text = { Text("${size.name}  ${size.label}", color = Am.OnDark, fontSize = 13.sp) }, onClick = { open = false; onPick(size.width, size.height) })
            }
        }
    }
    if (naming) {
        app.absentmotion.ui.home.SizeNameDialog(stringResource(R.string.newproject_save_size_title), "$width × $height", dark = true, onDismiss = { naming = false }) { name ->
            naming = false
            settings.savedSizes = app.absentmotion.library.SavedSizes.with(settings.savedSizes, name, width, height)
        }
    }
}

@Composable
private fun DeskNumber(value: Int, modifier: Modifier, commit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun save() { val number = text.toIntOrNull()?.coerceIn(MIN_SIDE, MAX_SIDE); if (number != null && number != value) commit(number); text = (number ?: value).toString() }
    BasicTextField(text, { if (it.length <= 4 && it.all(Char::isDigit)) text = it }, modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).padding(horizontal = 8.dp)
        .onFocusChanged { if (focused && !it.isFocused) save(); focused = it.isFocused },
        textStyle = TextStyle(color = Am.OnDark, fontSize = 13.sp, textAlign = TextAlign.Center), singleLine = true, cursorBrush = SolidColor(Am.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save(); focus.clearFocus() }),
        decorationBox = { inner -> Box(Modifier.fillMaxHeight(), Alignment.Center) { inner() } })
}
