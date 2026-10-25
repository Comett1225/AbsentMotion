package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.rename
import app.absentmotion.editor.renameLayer
import app.absentmotion.editor.stepBack
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.animatedBackground
import app.absentmotion.ui.theme.enterFade

private val Underline get() = Am.Deepest

@Composable
fun EditorTopBar(state: EditorState, onExit: () -> Unit, modifier: Modifier = Modifier) {
    if (MultiSelect.active) {
        MultiTopBar(state, modifier)
        return
    }
    val layer = state.selected
    val inPanel = layer != null && state.panel != EditorPanel.Tools
    val focus = LocalFocusManager.current
    var settingsOpen by remember { mutableStateOf(false) }
    var layerMenuOpen by remember { mutableStateOf(false) }
    var parentMenuOpen by remember { mutableStateOf(false) }
    val color = when {
        layer == null -> Am.TimelineBg
        inPanel -> Am.Button
        else -> Am.TopBar
    }
    fun act(action: () -> Unit) {
        focus.clearFocus()
        action()
    }
    val cell = Modifier.width(40.dp).fillMaxHeight()
    Row(modifier.animatedBackground(color), verticalAlignment = Alignment.CenterVertically) {
        BarButton(
            if (layer == null) ChromeIcons.Exit else ChromeIcons.Back,
            stringResource(if (layer == null) R.string.topbar_exit_to_project_list else R.string.topbar_back), cell.tutorialTarget(if (layer == null) null else "back"),
        ) {
            act { if (layer == null) onExit() else state.stepBack() }
        }
        if (inPanel) {
            Text(stringResource(state.panel.title), Modifier.weight(1f).enterFade(), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
        } else {
            key(layer?.id) {
                val id = layer?.id
                NameField(layer?.label ?: state.scene.title, Modifier.weight(1f).fillMaxHeight().enterFade()) { name ->
                    if (id == null) state.rename(name) else state.renameLayer(id, name)
                }
            }
        }
        if (layer == null) {
            BarButton(ChromeIcons.Gear, stringResource(R.string.topbar_project_settings), cell.enterFade().tutorialTarget("gear")) { act { settingsOpen = true } }
            BarButton(ChromeIcons.Share, stringResource(R.string.topbar_export_share), cell.enterFade().tutorialTarget("export")) { act { state.playing = false; state.exportOpen = true } }
        } else if (!inPanel) {
            Spacer(Modifier.width(5.dp))
            Box(cell.enterFade().clickable { act { parentMenuOpen = true } }, contentAlignment = Alignment.Center) {
                LayerParentIcon()
                Presence(parentMenuOpen) { ParentMenu(state) { parentMenuOpen = false } }
            }
            Spacer(Modifier.width(6.dp))
            BarButton(ChromeIcons.Trash, stringResource(R.string.topbar_delete_layer), cell.enterFade()) { act { state.deleteSelected() } }
            Spacer(Modifier.width(1.dp))
            BarButton(ChromeIcons.More, stringResource(R.string.topbar_layer_menu), cell.enterFade()) { act { layerMenuOpen = true } }
        }
        Presence(layer != null && !inPanel && layerMenuOpen) { LayerMenu(state) { layerMenuOpen = false } }
        Presence(settingsOpen) { ProjectSettingsPopup(state) { settingsOpen = false } }
    }
}

internal fun typedName(typed: String, shown: String): String? = typed.trim().takeIf { typed != shown && it.isNotEmpty() && it != shown }

@Composable
private fun NameField(value: String, modifier: Modifier, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        val name = typedName(text, value)
        text = name ?: value
        if (name != null) onCommit(name)
    }
    val commitNow by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { commitNow() } }
    Box(
        modifier.drawBehind {
            val inset = 4.dp.toPx()
            drawRect(Underline, Offset(inset, 33.dp.toPx()), Size(size.width - 2 * inset, 1.4.dp.toPx()))
        },
    ) {
        BasicTextField(
            text, { text = it },
            Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 4.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            textStyle = TextStyle(color = Am.OnDark, fontSize = 15.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
            singleLine = true,
            cursorBrush = SolidColor(Am.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
    }
}
