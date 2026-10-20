package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.model.CAMERA_MOVE_MAX_MS
import app.absentmotion.model.CAMERA_MOVE_MIN_MS
import app.absentmotion.model.CameraMove
import app.absentmotion.model.cameraMoveSpan
import app.absentmotion.model.withCameraMove
import app.absentmotion.ui.theme.Am
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

@Composable
internal fun CameraMovesSheet(state: EditorState, id: Long, onClose: () -> Unit) {
    val start = remember { state.sampleMs }
    val before = remember { state.scene }
    var ms by remember { mutableIntStateOf(3000) }
    var chosen by remember { mutableStateOf<CameraMove?>(null) }
    val clock = rememberClock()
    val span = state.scene.cameraMoveSpan(id, start, ms)
    fun apply(move: CameraMove, key: Any? = null) {
        if (before.cameraMoveSpan(id, start, ms) == null) return
        state.edit(key) { before.withCameraMove(id, move, start, ms) }
        chosen = move
    }
    MotionSheet(stringResource(R.string.camera_moves), onClose) {
        Text(stringResource(R.string.camera_moves_start, seconds(span?.first ?: start)), Modifier.fillMaxWidth().height(24.dp),
            color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TileGrid(CameraMove.entries) { move, modifier ->
            Tile(stringResource(move.label), move == chosen, modifier, { apply(move) }) { drawCameraMove(move, clock.value) }
        }
        DurationLine(ms, CAMERA_MOVE_MIN_MS, CAMERA_MOVE_MAX_MS, { ms = it; chosen?.let { move -> apply(move, "camera-move-duration") } }, state::endGesture)
        val short = span?.let { (from, to) -> to - from < ms } == true
        Text(if (short && span != null) stringResource(R.string.camera_moves_shortened, seconds(span.second - span.first)) else "",
            Modifier.fillMaxWidth().height(40.dp), color = Warning, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
    }
}

private fun seconds(ms: Int) = String.format(Locale.US, "%.1f s", ms / 1000f)

private class Shot(val dx: Float = 0f, val dy: Float = 0f, val near: Float = 1f, val far: Float = 1f, val turn: Float = 0f)

private fun ease(u: Float): Float = u.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

private fun shotOf(move: CameraMove, clock: Float): Shot {
    val u = ease((clock - 0.1f) / 0.75f)
    val wave = 2 * PI.toFloat() * u
    return when (move) {
        CameraMove.PushIn -> Shot(near = 1 + 0.6f * u, far = 1 + 0.2f * u)
        CameraMove.PullOut -> Shot(near = 1 - 0.4f * u, far = 1 - 0.12f * u)
        CameraMove.Orbit -> Shot(dx = sin(wave), dy = 1 - cos(wave))
        CameraMove.Pan -> Shot(dx = 2 * u)
        CameraMove.DollyZoom -> Shot(far = 1 - 0.45f * u)
        CameraMove.Handheld -> Shot(dx = 0.12f * sin(clock * 97f), dy = 0.12f * cos(clock * 71f), turn = 1.5f * sin(clock * 53f))
    }
}

private fun DrawScope.drawCameraMove(move: CameraMove, clock: Float) {
    val shot = shotOf(move, clock)
    val w = size.width
    val h = size.height
    val step = w * 0.12f
    clipRect {
        rotate(shot.turn, center) {
            scale(shot.far, center) {
                for (i in -3..3) {
                    val x = w / 2 + i * w * 0.22f - shot.dx * step * 0.4f
                    drawCircle(Am.OnDarkDim.copy(alpha = 0.55f), w * 0.035f, Offset(x, h * 0.28f - shot.dy * step * 0.4f))
                    drawCircle(Am.OnDarkDim.copy(alpha = 0.35f), w * 0.025f, Offset(x + w * 0.11f, h * 0.72f - shot.dy * step * 0.4f))
                }
            }
            scale(shot.near, center) {
                val subject = Size(w * 0.22f, h * 0.42f)
                drawRoundRect(Am.Accent, Offset(w / 2 - subject.width / 2 - shot.dx * step, h / 2 - subject.height / 2 - shot.dy * step), subject,
                    androidx.compose.ui.geometry.CornerRadius(4f))
            }
        }
        val inset = 4.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.5f), Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset), style = Stroke(1.dp.toPx()))
    }
}
