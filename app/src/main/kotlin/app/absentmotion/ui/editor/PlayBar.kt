package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.skipBack
import app.absentmotion.editor.skipForward
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence

@Composable
fun PlayBar(state: EditorState, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier.background(Am.TimelineBg)) {
        val cell = Modifier.weight(1f).fillMaxHeight()
        BarButton(ChromeIcons.Undo, stringResource(R.string.play_undo), cell, enabled = state.canUndo) { state.undo() }
        BarButton(ChromeIcons.Redo, stringResource(R.string.play_redo), cell, enabled = state.canRedo) { state.redo() }
        BarButton(ChromeIcons.SkipBack, stringResource(R.string.play_skip_back), cell) { state.skipBack() }
        val settings = state.settings
        BarButton(
            if (state.playing) ChromeIcons.Pause else if (settings.loopingPlay) ChromeIcons.PlayRepeat else ChromeIcons.Play,
            stringResource(if (state.playing) R.string.play_pause else R.string.play_play), cell,
            onLongClick = if (state.playing) null else ({ settings.loopingPlay = !settings.loopingPlay }),
        ) { state.playing = !state.playing }
        BarButton(ChromeIcons.SkipForward, stringResource(R.string.play_skip_forward), cell) { state.skipForward() }
        BarButton(ChromeIcons.CopyPaste, stringResource(R.string.play_copy_paste), cell) { menuOpen = true }
        Box(cell) {
            BarButton(
                if (state.previewPanelOpen) ChromeIcons.PreviewModeOpen else ChromeIcons.PreviewMode, stringResource(R.string.play_preview_mode), Modifier.fillMaxSize(),
                iconSize = DpSize(24.dp, 36.dp),
            ) { state.previewPanelOpen = !state.previewPanelOpen }
            if (state.cameraMode && !state.previewPanelOpen) Icon(ChromeIcons.PreviewCamera, null, Modifier.align(Alignment.Center).size(20.dp, 37.dp), tint = Am.Accent)
        }
        Presence(menuOpen) { CopyPasteMenu(state) { menuOpen = false } }
    }
}
