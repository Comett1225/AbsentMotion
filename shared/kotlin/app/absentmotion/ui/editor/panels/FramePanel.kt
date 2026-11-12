package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.withPickedFrame
import app.absentmotion.model.FrameRef
import app.absentmotion.model.Layer
import app.absentmotion.model.frameCount
import app.absentmotion.model.shownFrame
import app.absentmotion.ui.editor.DrawingIcons
import app.absentmotion.ui.theme.Am
import kotlin.math.roundToInt

@Composable
internal fun FramePanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val count = layer.nested?.frameCount ?: return
    val picking = layer.element(FrameRef) != null
    val shown = layer.shownFrame(state.sampleMs.toDouble(), state.scene.fps)
    val label = stringResource(R.string.layer_tool_frame)
    fun pick(frame: Int) = state.editSelected { it.withPickedFrame(Math.floorMod(frame, count)) }
    Row(modifier.padding(horizontal = 40.px), Arrangement.spacedBy(17.px, Alignment.CenterHorizontally), Alignment.CenterVertically) {
        Step(DrawingIcons.Prev, stringResource(R.string.draw_previous_frame)) { pick(shown - 1) }
        Box(
            Modifier.width(300.px).height(113.px).clip(PanelShape).background(Am.Well)
                .clickable { state.askNumber(label, (shown + 1).toFloat(), "", 1f, count.toFloat()) { pick(it.roundToInt() - 1) } },
            Alignment.Center,
        ) { Text("${shown + 1} / $count", color = if (picking) Am.OnDark else Am.OnDarkDim, fontSize = 15.5.sp, maxLines = 1, softWrap = false) }
        Step(DrawingIcons.Next, stringResource(R.string.draw_next_frame)) { pick(shown + 1) }
        if (picking) Box(
            Modifier.height(113.px).clip(PanelShape).background(Am.Button).clickable { state.editSelected { it.copy(node = it.node.withChild("frame", null)) } }
                .padding(horizontal = 34.px),
            Alignment.Center,
        ) { Text(stringResource(R.string.group_retime_off), color = Am.OnDark, maxLines = 1, softWrap = false, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, 14.sp, 0.5.sp)) }
    }
}

@Composable
private fun Step(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.width(113.px).height(113.px).clip(PanelShape).background(Am.Button).clickable(onClick = onClick), Alignment.Center) {
        PanelIcon(icon, Am.OnDark, description = description)
    }
}
