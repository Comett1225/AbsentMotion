package app.absentmotion.ui.editor.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiTools
import app.absentmotion.ai.LONG_MATTE_SECONDS
import app.absentmotion.ai.MAX_MATTE_SECONDS
import app.absentmotion.ai.MatteSides
import app.absentmotion.ai.matteBytes
import app.absentmotion.ai.matteSeconds
import app.absentmotion.ai.matteSize
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import kotlin.math.roundToInt
import app.absentmotion.ui.theme.Am

@Composable
internal fun AiBackdrop(modifier: Modifier, action: AiAction? = null) {
    Canvas(modifier.background(Brush.linearGradient(listOf(Color(0xFF7862A8), Color(0xFF467B8B), Color(0xFF509C95))))) {
        for ((x, y, radius) in listOf(Triple(0.16f, 0.25f, 4f), Triple(0.83f, 0.32f, 3f), Triple(0.71f, 0.78f, 2f))) {
            val cx = size.width * x; val cy = size.height * y; val r = radius.dp.toPx()
            drawPath(Path().apply {
                moveTo(cx, cy - r); quadraticTo(cx, cy, cx + r, cy)
                quadraticTo(cx, cy, cx, cy + r); quadraticTo(cx, cy, cx - r, cy)
                quadraticTo(cx, cy, cx, cy - r); close()
            }, Color.White.copy(alpha = 0.65f))
        }
        val cx = size.width / 2; val cy = size.height / 2
        val unit = minOf(size.width, size.height)
        when (action) {
            AiAction.Background -> {
                drawCircle(Color.White.copy(alpha = 0.85f), unit * 0.1f, Offset(cx, cy - unit * 0.12f))
                drawPath(Path().apply {
                    moveTo(cx - unit * 0.22f, cy + unit * 0.22f)
                    cubicTo(cx - unit * 0.22f, cy - unit * 0.05f, cx + unit * 0.22f, cy - unit * 0.05f, cx + unit * 0.22f, cy + unit * 0.22f)
                    close()
                }, Color.White.copy(alpha = 0.85f))
            }
            AiAction.Noise -> {
                for (i in -4..4) {
                    val h = unit * (if (i % 2 == 0) 0.13f else 0.23f)
                    val x = cx + i * unit * 0.06f
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(x, cy - h), Offset(x, cy + h), unit * 0.022f, StrokeCap.Round)
                }
            }
            AiAction.Video -> drawPath(Path().apply {
                moveTo(cx - unit * 0.12f, cy - unit * 0.2f)
                lineTo(cx + unit * 0.2f, cy); lineTo(cx - unit * 0.12f, cy + unit * 0.2f); close()
            }, Color.White.copy(alpha = 0.85f))
            null -> Unit
        }
    }
}

@Composable
internal fun AiTile(action: AiAction, layer: Layer?, tools: AiTools) {
    val reason = action.unavailable(layer)
    Column(Modifier.width(100.dp).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.alpha(if (reason == null) 1f else 0.35f)
            .clickable(enabled = reason == null && tools.running == null) { tools.apply(action) }, horizontalAlignment = Alignment.CenterHorizontally) {
            AiBackdrop(Modifier.size(93.dp).clip(RoundedCornerShape(5.dp)), action)
            Text(action.title, Modifier.fillMaxWidth().height(26.dp).padding(top = 4.dp), color = Am.OnDark, fontSize = 8.sp,
                lineHeight = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
        }
        Text(reason ?: "", Modifier.fillMaxWidth().height(16.dp), color = Am.OnDark.copy(alpha = 0.7f), fontSize = 8.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
    }
}

@Composable
internal fun AiVideoDialog(tools: AiTools, scene: Scene) {
    val layer = tools.videoSetup ?: return
    val media = layer.mediaFill?.uri?.let(scene::media)
    val seconds = media?.let(layer::matteSeconds) ?: 0.0
    val frames = (seconds * (media?.fps ?: 30f)).roundToInt()
    val size = media?.let { matteSize(it.width, it.height, tools.videoSide) }
    val tooLong = seconds > MAX_MATTE_SECONDS
    AlertDialog(onDismissRequest = { tools.videoSetup = null },
        title = { Text(AiAction.Video.title, color = Am.OnDark) },
        text = {
            Column {
                Text("Every frame of this clip is processed on this phone. The result is a group with the video and a mask layer above it.",
                    color = Am.OnDark, fontSize = 14.sp)
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).background(Am.PanelInset)) {
                    for ((side, name) in MatteSides.zip(listOf("Small", "Medium", "Large"))) {
                        val on = side == tools.videoSide
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Am.FieldHi else Color.Transparent)
                            .clickable { tools.videoSide = side }, Alignment.Center) {
                            Text("$name ${side}p", color = if (on) Am.Accent else Am.OnDark, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Text(
                    when {
                        tooLong -> "Clips longer than ${MAX_MATTE_SECONDS / 60} minutes are not supported yet. Trim or split the clip first."
                        size == null -> "This video has no size information."
                        else -> "$frames frames at ${size.first} × ${size.second}, about ${matteBytes(seconds, size.first, size.second) / 1_000_000 + 1} MB." +
                            if (seconds > LONG_MATTE_SECONDS) " Long clip: this can take several minutes and use a lot of battery." else ""
                    },
                    Modifier.padding(top = 12.dp).height(54.dp), color = if (tooLong) Am.Warning else Am.Caption, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        },
        confirmButton = {
            TextButton({ tools.apply(AiAction.Video, confirmed = true) }, enabled = !tooLong && size != null) { Text("Start", color = Am.Accent) }
        },
        dismissButton = { TextButton({ tools.videoSetup = null }) { Text("Cancel", color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
internal fun AiProgressDialog(tools: AiTools) {
    if (tools.message.isEmpty()) return
    val action = tools.running
    AlertDialog(onDismissRequest = { if (action != null) tools.cancel() else tools.dismiss() },
        title = { Text(action?.title ?: "AI", color = Am.OnDark) },
        text = {
            Column {
                Text(tools.message, color = Am.OnDark, fontSize = 14.sp)
                if (action != null) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { tools.progress }, Modifier.fillMaxWidth(), color = Am.Accent)
                }
            }
        },
        confirmButton = {
            TextButton({ if (action != null) tools.cancel() else tools.retry?.let(tools::apply) ?: tools.dismiss() }) {
                Text(if (action != null) "Cancel" else if (tools.retry != null) "Retry" else "OK", color = Am.Accent)
            }
        },
        dismissButton = { if (action == null && tools.retry != null) TextButton(tools::dismiss) { Text("Cancel", color = Am.Accent) } },
        containerColor = Am.Field)
}
