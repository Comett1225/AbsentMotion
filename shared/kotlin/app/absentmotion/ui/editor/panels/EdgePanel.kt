package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskVerticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.MatteEdge
import app.absentmotion.ai.cutoutEdge
import app.absentmotion.ai.withCutoutEdge
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Layer
import app.absentmotion.ui.editor.DimAlpha
import app.absentmotion.ui.theme.Am
import kotlin.math.roundToInt

@Composable
internal fun EdgeSlider(label: String, value: Int, range: IntRange, onDone: () -> Unit = {}, onChange: (Int) -> Unit) {
    val sent = androidx.compose.runtime.remember { intArrayOf(value) }
    androidx.compose.runtime.SideEffect { sent[0] = value }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = Am.OnDark, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 2)
        ThinSlider(value.toFloat(), { v -> v.roundToInt().coerceIn(range).let { if (it != sent[0]) { sent[0] = it; onChange(it) } } }, Modifier.weight(1f),
            valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = onDone)
        Text(if (value > 0 && range.first < 0) "+$value" else "$value", Modifier.width(36.dp), color = Am.OnDark, fontSize = 14.sp, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
internal fun EdgePanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val edge = layer.cutoutEdge ?: return
    val id = layer.id
    fun set(next: MatteEdge, key: Any? = "cutout-edge") = state.edit(key) { scene -> scene.update(id) { it.withCutoutEdge(next) } }
    Column(modifier.deskVerticalScroll().padding(horizontal = 16.dp, vertical = 8.dp)) {
        EdgeSlider(stringResource(R.string.cutout_softness), edge.softness, 0..MatteEdge.MAX_SOFTNESS, state::endGesture) { set(edge.copy(softness = it)) }
        EdgeSlider(stringResource(R.string.cutout_grow_shrink), edge.grow, -MatteEdge.MAX_GROW..MatteEdge.MAX_GROW, state::endGesture) { set(edge.copy(grow = it)) }
        Row(Modifier.padding(top = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cutout_edge_hint), Modifier.weight(1f), color = Am.Caption, fontSize = 12.sp, lineHeight = 16.sp)
            val changed = edge != MatteEdge()
            TextButton({ set(MatteEdge(), null) }, enabled = changed) {
                Text(stringResource(R.string.cutout_edge_reset), color = Am.Accent.copy(alpha = if (changed) 1f else DimAlpha), fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}
