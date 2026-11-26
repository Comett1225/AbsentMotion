package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FaceMode
import app.absentmotion.editor.FacePick
import app.absentmotion.editor.leaveDrawing
import app.absentmotion.editor.lookForFaces
import app.absentmotion.editor.runFaceTracking
import app.absentmotion.editor.videoToScene
import app.absentmotion.model.Pt
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun FaceOverlay(state: EditorState, mode: FaceMode, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(mode, state.frame, mode.running) {
        if (mode.running || mode.again != null) return@LaunchedEffect
        delay(150)
        state.lookForFaces(context, mode, state.frame)
    }
    Canvas(
        modifier.onSizeChanged { size = it }.pointerInput(mode) {
            detectTapGestures { tap ->
                val (s, ox, oy) = view
                val inverse = current?.inverse() ?: return@detectTapGestures
                if (mode.running || mode.facesFrame != state.frame) return@detectTapGestures
                val at = inverse.map(Pt((tap.x - ox) / s, (tap.y - oy) / s))
                val face = mode.faces.orEmpty().filter { abs(it.x - at.x) <= it.size * 0.7f && abs(it.y - at.y) <= it.size * 0.7f }
                    .minByOrNull { abs(it.x - at.x) + abs(it.y - at.y) } ?: return@detectTapGestures
                state.runFaceTracking(context, mode, FacePick(mode.facesPts, face), scope)
            }
        },
    ) {
        val m = toScene ?: return@Canvas
        if (mode.facesFrame != state.frame) return@Canvas
        fun screen(p: Pt) = m.map(p).let { Offset(it.x * scale + dx, it.y * scale + dy) }
        for (face in mode.faces.orEmpty()) {
            val half = face.size * 0.6f
            val corners = listOf(Pt(face.x - half, face.y - half), Pt(face.x + half, face.y - half), Pt(face.x + half, face.y + half), Pt(face.x - half, face.y + half)).map(::screen)
            for (k in 0..3) {
                drawLine(Color.Black, corners[k], corners[(k + 1) % 4], 4.dp.toPx())
                drawLine(Am.Accent, corners[k], corners[(k + 1) % 4], 2.dp.toPx())
            }
        }
    }
}

@Composable
internal fun FacePanel(state: EditorState, mode: FaceMode) {
    app.absentmotion.ui.theme.ThemeFade.Hold(mode.running)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(mode) { onDispose { mode.job?.cancel() } }
    LaunchedEffect(mode) {
        val again = mode.again ?: return@LaunchedEffect
        mode.again = null
        state.runFaceTracking(context, mode, again, scope)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), Alignment.CenterStart) {
                if (mode.running) {
                    Column {
                        Text(stringResource(R.string.track_tracking, (mode.progress * 100).toInt().toString()), color = Am.OnDark, fontSize = 12.sp, maxLines = 1)
                        LinearProgressIndicator(progress = { mode.progress }, Modifier.fillMaxWidth().padding(top = 6.dp), color = Am.Accent)
                    }
                } else {
                    Text(if (mode.looking) stringResource(R.string.face_tracking_looking) else mode.message, color = Am.Caption, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 4)
                }
            }
            ActionChip(stringResource(R.string.cancel), mode.running) { mode.job?.cancel() }
            ActionChip(stringResource(R.string.done), !mode.running) { state.leaveDrawing() }
        }
    }
}
