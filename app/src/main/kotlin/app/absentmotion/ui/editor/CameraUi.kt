package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay

private val TipShape = RoundedCornerShape(8.dp)
private val TipArrow = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2, size.height)
    close()
}

@Composable
internal fun CameraTip(state: EditorState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.previewPanelOpen) {
        if (!state.previewPanelOpen) delay(8000)
        state.cameraTip = false
    }
    Column(modifier.padding(end = 6.dp).width(236.dp).clickable(interactionSource = null, indication = null) { state.cameraTip = false },
        horizontalAlignment = Alignment.End) {
        Column(Modifier.fillMaxWidth().clip(TipShape).background(Am.Field).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ChromeIcons.PreviewMode, null, Modifier.size(16.dp, 24.dp), tint = Am.OnDark)
                Text("›", Modifier.padding(horizontal = 8.dp), color = Am.OnDarkDim, fontSize = 18.sp)
                Icon(ChromeIcons.PreviewCamera, null, Modifier.size(13.dp, 24.dp), tint = Am.Accent)
            }
            Text(stringResource(R.string.camera_tip), Modifier.padding(top = 6.dp), color = Am.OnDark, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Box(Modifier.padding(end = 22.dp).size(14.dp, 8.dp).clip(TipArrow).background(Am.Field))
    }
}
