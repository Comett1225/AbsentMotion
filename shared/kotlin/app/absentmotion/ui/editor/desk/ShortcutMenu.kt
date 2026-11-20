package app.absentmotion.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.desk.MenuKey
import app.absentmotion.desk.ModifierKeys
import app.absentmotion.desk.Shortcut
import app.absentmotion.ui.theme.Am

@Composable
internal fun ShortcutItems(action: Shortcut, divider: Boolean = false, onDone: () -> Unit) {
    var capturing by remember { mutableStateOf(false) }
    var clash by remember { mutableStateOf<List<MenuKey>?>(null) }
    val focus = remember { FocusRequester() }
    val take = rememberUpdatedState { event: KeyEvent ->
        when {
            !capturing -> false
            event.type != KeyEventType.KeyDown -> true
            event.key == Key.Escape -> { capturing = false; true }
            event.key in ModifierKeys || !DeskShortcuts.bindable(event) -> true
            else -> {
                val chord = DeskShortcuts.chord(event)
                capturing = false
                if (DeskShortcuts.conflict(action, chord) == null) { DeskShortcuts.bind(action, chord); onDone() } else clash = listOf(chord)
                true
            }
        }
    }
    DisposableEffect(capturing) {
        val active = capturing
        val before = DeskShortcuts.viewer
        if (active) DeskShortcuts.viewer = { take.value(it) }
        onDispose { if (active) DeskShortcuts.viewer = before }
    }
    app.absentmotion.platform.BackHandler(capturing) { capturing = false }
    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }
    val colors = MenuDefaults.itemColors(textColor = Am.OnDark, disabledTextColor = Am.OnDarkDim.copy(alpha = 0.6f))
    val keys = @Composable { text: String -> Text(text.ifEmpty { "–" }, color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1) }
    if (divider) HorizontalDivider(color = Am.Line)
    DropdownMenuItem(
        text = { Text(stringResource(if (capturing) R.string.desk_shortcuts_press else R.string.desk_shortcut_change)) },
        trailingIcon = { keys(DeskShortcuts.text(action)) },
        colors = colors,
        onClick = { clash = null; capturing = !capturing },
        modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { take.value(it) },
    )
    clash?.let { chords ->
        Box(Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.desk_shortcuts_conflict, DeskShortcuts.conflict(action, chords)?.text.orEmpty()), color = Am.Accent, fontSize = 12.sp)
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.template_replace)) }, colors = colors, onClick = { DeskShortcuts.assign(action, chords); clash = null; onDone() })
        DropdownMenuItem(text = { Text(stringResource(R.string.cancel)) }, colors = colors, onClick = { clash = null })
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.desk_shortcut_remove)) },
        enabled = DeskShortcuts.keys(action).isNotEmpty(),
        colors = colors,
        onClick = { capturing = false; clash = null; DeskShortcuts.clear(action); onDone() },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.desk_shortcut_reset)) },
        trailingIcon = { keys(DeskShortcuts.defaultText(action)) },
        enabled = action in DeskShortcuts.custom,
        colors = colors,
        onClick = {
            capturing = false
            if (DeskShortcuts.conflict(action, action.defaults) == null) { DeskShortcuts.reset(action); clash = null; onDone() } else clash = action.defaults
        },
    )
}

@Composable
internal fun ShortcutMenuBox(action: Shortcut, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.rightClick { open = true }) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Am.Field) {
            ShortcutItems(action) { open = false }
        }
    }
}
