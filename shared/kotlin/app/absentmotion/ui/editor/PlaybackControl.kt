package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am

@Composable
internal fun PlaybackControl(state: EditorState, modifier: Modifier = Modifier, desktop: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val settings = state.settings
    val active = state.playing || state.countInRemaining > 0
    val secondary = if (desktop) Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && (event.buttons.isSecondaryPressed || mac && event.keyboardModifiers.isCtrlPressed)) {
                    open = true
                    event.changes.forEach { it.consume() }
                }
            }
        }
    } else Modifier
    Box(modifier.then(secondary)) {
        BarButton(
            if (active) ChromeIcons.Pause else if (settings.loopingPlay) ChromeIcons.PlayRepeat else ChromeIcons.Play,
            stringResource(if (active) R.string.play_pause else R.string.play_play), Modifier.fillMaxSize(),
            onLongClick = { open = true },
        ) { state.togglePlayback() }
        if (settings.countIn != 0 || settings.playbackSpeed != 4) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp).background(Am.Accent, CircleShape))
        val colors = MenuDefaults.itemColors(textColor = Am.OnDark)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Am.Field) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play_count_in), color = Am.OnDarkDim, fontSize = 12.sp) }, onClick = {}, enabled = false)
            listOf(0 to R.string.play_count_off, 1 to R.string.play_count_one, 2 to R.string.play_count_two, 3 to R.string.play_count_three).forEach { (value, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, trailingIcon = { if (settings.countIn == value) Text("✓", color = Am.Accent) }, colors = colors, onClick = { settings.countIn = value; open = false })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.a11y_speed), color = Am.OnDarkDim, fontSize = 12.sp) }, onClick = {}, enabled = false)
            listOf(1 to R.string.play_speed_quarter, 2 to R.string.play_speed_half, 4 to R.string.play_speed_normal).forEach { (value, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, trailingIcon = { if (settings.playbackSpeed == value) Text("✓", color = Am.Accent) }, colors = colors, onClick = { settings.playbackSpeed = value; open = false })
            }
            if (!desktop) DropdownMenuItem(text = { Text(stringResource(R.string.desk_loop)) }, trailingIcon = { if (settings.loopingPlay) Text("✓", color = Am.Accent) }, colors = colors, onClick = { settings.loopingPlay = !settings.loopingPlay; open = false })
            if (desktop && app.absentmotion.ui.adaptive.deskLayout) ShortcutItems(app.absentmotion.desk.Shortcut.PlayPause, divider = true) { open = false }
        }
    }
}
