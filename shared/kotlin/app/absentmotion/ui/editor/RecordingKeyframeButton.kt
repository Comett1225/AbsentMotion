package app.absentmotion.ui.editor

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am

@Composable
internal fun RecordingKeyframeButton(
    state: EditorState,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val secondary = if (enabled && app.absentmotion.ui.adaptive.deskLayout) Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && (event.buttons.isSecondaryPressed || mac && event.keyboardModifiers.isCtrlPressed)) {
                    open = true
                    event.changes.forEach { it.consume() }
                }
            }
        }
    } else Modifier
    Box(modifier.then(secondary).then(if (enabled) Modifier.combinedClickable(onLongClick = { open = true }, onClick = onClick) else Modifier), Alignment.Center) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Am.Field) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.record_overwrite)) },
                trailingIcon = { if (state.settings.overwriteRecording) Text("✓", color = Am.Accent) },
                colors = MenuDefaults.itemColors(textColor = Am.OnDark),
                onClick = { state.settings.overwriteRecording = !state.settings.overwriteRecording; open = false },
            )
            if (app.absentmotion.ui.adaptive.deskLayout) ShortcutItems(app.absentmotion.desk.Shortcut.AddKeyframe, divider = true) { open = false }
        }
    }
}
