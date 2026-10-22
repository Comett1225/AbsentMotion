package app.absentmotion.ui.editor.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.AiKind
import app.absentmotion.ai.AiMark
import app.absentmotion.ai.smoothingOf
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.ai.restorable
import app.absentmotion.ai.withoutAiMark
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.theme.Am

internal val AiMarkRowHeight = 37.dp
internal val AiMarkRowGap = 6.5.dp

@Composable
internal fun AiMarkRow(state: EditorState, mark: AiMark) {
    val kind = mark.kind ?: return
    val smooth = smoothingOf(mark.node["option"])?.takeIf { kind == AiKind.Smooth }
    val parts = listOfNotNull(mark.source?.title, smooth?.method?.takeIf { it != FrameInterpolation.Off }?.label,
        smooth?.level?.label?.takeIf { smooth.method != FrameInterpolation.Off }, R.string.export_motion_blur.takeIf { smooth != null && smooth.blur != MotionBlur.Off })
    val name = (listOf(stringResource(kind.title)) + parts.map { stringResource(it) }).joinToString(" · ")
    Row(Modifier.padding(start = 6.5.dp, end = 6.5.dp, top = AiMarkRowGap).fillMaxWidth().height(AiMarkRowHeight).clip(RoundedCornerShape(5.dp)).background(Am.Field),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f).padding(start = 12.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (kind.ai) Text(stringResource(R.string.ai), Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(3.dp)).background(Am.Accent).padding(horizontal = 5.dp, vertical = 1.dp),
            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (mark.restorable) Icon(ChromeIcons.Trash, stringResource(R.string.ai_mark_restore), Modifier.size(36.dp).clickable {
            state.selectedId?.let { id -> state.edit { it.withoutAiMark(id, kind) } }
        }.padding(7.dp), tint = Am.OnDark)
        else Spacer(Modifier.width(12.dp))
    }
}
