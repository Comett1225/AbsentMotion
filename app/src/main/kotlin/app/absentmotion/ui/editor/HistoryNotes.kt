package app.absentmotion.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.HistoryNote
import app.absentmotion.i18n.text
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay

private const val SHOWN_MS = 2200L
private const val FADE_MS = 300L

@Composable
internal fun HistoryNotes(state: EditorState, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally) {
        for (note in state.historyNotes) androidx.compose.runtime.key(note.id) { HistoryNoteRow(state, note) }
    }
}

@Composable
private fun HistoryNoteRow(state: EditorState, note: HistoryNote) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        delay(SHOWN_MS)
        shown = false
        delay(FADE_MS)
        state.historyNotes.remove(note)
    }
    AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        Row(Modifier.widthIn(max = 280.dp).semantics { liveRegion = LiveRegionMode.Polite }.clip(RoundedCornerShape(18.dp)).background(Color(0xE6222222))
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (note.text.id == app.absentmotion.R.string.history_undid) ChromeIcons.Undo else ChromeIcons.Redo, null, Modifier.size(16.dp), tint = Am.OnDark)
            Text(note.text.text(), Modifier.padding(start = 8.dp), color = Am.OnDark, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
