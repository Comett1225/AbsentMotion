package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.TrackMode
import app.absentmotion.editor.TrackRange
import app.absentmotion.editor.leaveDrawing
import app.absentmotion.editor.runTracking
import app.absentmotion.editor.setTrackPointCount
import app.absentmotion.editor.trackTargets
import app.absentmotion.editor.videoToScene
import app.absentmotion.media.MotionTracker
import app.absentmotion.media.trackFactor
import app.absentmotion.model.Pt
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am
import kotlin.math.min
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
internal fun TrackOverlay(state: EditorState, mode: TrackMode, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scene = state.scene
    val scale = if (size.width == 0) 1f else min(size.width.toFloat() / scene.width, size.height.toFloat() / scene.height)
    val dx = (size.width - scene.width * scale) / 2
    val dy = (size.height - scene.height * scale) / 2
    val video = scene.layer(mode.layerId)
    val media = video?.mediaFill?.uri?.let(scene::media)
    val toScene = if (video != null && media != null) scene.videoToScene(video, state.frame, media.width, media.height) else null
    val current by rememberUpdatedState(toScene)
    val view by rememberUpdatedState(Triple(scale, dx, dy))
    val bounds by rememberUpdatedState((media?.width ?: 1) to (media?.height ?: 1))
    Canvas(
        modifier.onSizeChanged { size = it }.pointerInput(mode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val (s, ox, oy) = view
                val m = current ?: return@awaitEachGesture
                val inverse = m.inverse() ?: return@awaitEachGesture
                if (mode.running) return@awaitEachGesture
                fun screen(p: Pt) = m.map(p).let { Offset(it.x * s + ox, it.y * s + oy) }
                val index = mode.points.indices.minByOrNull { (screen(mode.points[it]) - down.position).getDistance() } ?: return@awaitEachGesture
                val grab = screen(mode.points[index]) - down.position
                if (grab.getDistance() > 48.dp.toPx()) return@awaitEachGesture
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val at = change.position + grab
                    val p = inverse.map(Pt((at.x - ox) / s, (at.y - oy) / s))
                    mode.points[index] = Pt(p.x.coerceIn(0f, bounds.first - 1f), p.y.coerceIn(0f, bounds.second - 1f))
                    change.consume()
                }
            }
        },
    ) {
        val m = toScene ?: return@Canvas
        val frame = media ?: return@Canvas
        fun screen(p: Pt) = m.map(p).let { Offset(it.x * scale + dx, it.y * scale + dy) }
        val shown = mode.live ?: mode.points.toList()
        val half = (MotionTracker.PATCH_RADIUS + 0.5f) * trackFactor(frame.width, frame.height)
        val mint = if (mode.lostFrame != null && !mode.running) Am.Warning else Am.Accent
        if (shown.size == 2) {
            drawLine(Color.Black, screen(shown[0]), screen(shown[1]), 3.dp.toPx())
            drawLine(Color.White, screen(shown[0]), screen(shown[1]), 1.dp.toPx())
        }
        for (p in shown) {
            val corners = listOf(Pt(p.x - half, p.y - half), Pt(p.x + half, p.y - half), Pt(p.x + half, p.y + half), Pt(p.x - half, p.y + half)).map(::screen)
            for (k in 0..3) {
                drawLine(Color.Black, corners[k], corners[(k + 1) % 4], 3.5.dp.toPx())
                drawLine(mint, corners[k], corners[(k + 1) % 4], 1.5.dp.toPx())
            }
            val c = screen(p)
            val arm = 9.dp.toPx()
            for ((ax, ay) in listOf(arm to 0f, 0f to arm)) {
                drawLine(Color.Black, c - Offset(ax, ay), c + Offset(ax, ay), 3.5.dp.toPx())
                drawLine(Color.White, c - Offset(ax, ay), c + Offset(ax, ay), 1.5.dp.toPx())
            }
            drawCircle(Color.Black, 15.dp.toPx(), c, style = Stroke(4.dp.toPx()))
            drawCircle(mint, 15.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}

private val RangeLabels = mapOf(
    TrackRange.Forward to R.string.track_forward, TrackRange.Backward to R.string.track_backward, TrackRange.Whole to R.string.track_whole_clip,
)

@Composable
internal fun TrackPanel(state: EditorState, mode: TrackMode) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(mode) { onDispose { mode.job?.cancel() } }
    val idle = !mode.running
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Choices(stringResource(R.string.track_track), listOf(stringResource(R.string.track_position) to 1, stringResource(R.string.track_position_rotation_scale) to 2), mode.points.size, idle) { state.setTrackPointCount(mode, it) }
        Choices(stringResource(R.string.track_direction), TrackRange.entries.map { stringResource(RangeLabels.getValue(it)) to it }, mode.range, idle && mode.lostFrame == null) { mode.range = it }
        TargetChoice(state, mode, idle && mode.lostFrame == null)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), Alignment.CenterStart) {
                if (mode.running) {
                    Column {
                        Text(stringResource(R.string.track_tracking, (mode.progress * 100).toInt().toString()), color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
                        LinearProgressIndicator(progress = { mode.progress }, Modifier.fillMaxWidth().padding(top = 6.dp), color = Am.Accent)
                    }
                } else {
                    Text(
                        mode.message.ifEmpty { stringResource(if (mode.points.size > 1) R.string.track_hint_two else R.string.track_hint_one) },
                        color = if (mode.lostFrame != null) Am.Warning else Am.Caption, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 4,
                    )
                }
            }
            val resume = mode.lostFrame != null
            ActionChip(stringResource(if (mode.running) R.string.cancel else if (resume) R.string.action_continue else R.string.track_track), true) {
                if (mode.running) mode.job?.cancel() else state.runTracking(context, mode, scope, resume)
            }
            ActionChip(stringResource(R.string.done), idle) { state.leaveDrawing() }
        }
    }
}

@Composable
private fun <T> Choices(label: String, options: List<Pair<String, T>>, chosen: T, enabled: Boolean, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).alpha(if (enabled) 1f else DimAlpha), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), color = Am.Caption, fontSize = 12.sp, maxLines = 1)
        Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Am.PanelInset)) {
            for ((text, value) in options) {
                val on = value == chosen
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Am.FieldHi else Color.Transparent)
                        .clickable(enabled = enabled && !on) { onPick(value) },
                    Alignment.Center,
                ) { Text(text, color = if (on) Am.Accent else Am.OnDark, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun TargetChoice(state: EditorState, mode: TrackMode, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val video = state.scene.layer(mode.layerId)
    val targets = video?.let(state.scene::trackTargets).orEmpty()
    val chosen = mode.target?.let { id -> targets.firstOrNull { it.id == id } }
    Row(Modifier.fillMaxWidth().height(34.dp).alpha(if (enabled) 1f else DimAlpha), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.apply_to), Modifier.width(72.dp), color = Am.Caption, fontSize = 12.sp, maxLines = 1)
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Am.PanelInset).clickable(enabled = enabled) { open = true }.padding(horizontal = 10.dp), Alignment.CenterStart) {
            Text(chosen?.let { it.label.ifEmpty { it.kind } } ?: stringResource(R.string.track_new_null), color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
            if (open) MenuPopup(DropOrRise, { open = false }) {
                MenuRow(stringResource(R.string.track_new_null)) { mode.target = null; open = false }
                for (layer in targets) MenuRow(layer.label.ifEmpty { layer.kind }) { mode.target = layer.id; open = false }
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 8.dp).width(84.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Am.Button)
            .alpha(if (enabled) 1f else DimAlpha).clickable(enabled = enabled, onClick = onClick),
        Alignment.Center,
    ) { Text(text, color = Am.Accent, fontSize = 13.sp, maxLines = 1) }
}
