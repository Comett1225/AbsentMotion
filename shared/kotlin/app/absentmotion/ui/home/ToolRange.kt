package app.absentmotion.ui.home

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.platform.LocalContext
import app.absentmotion.tools.TrimRange
import app.absentmotion.tools.clock
import app.absentmotion.tools.moveEnd
import app.absentmotion.tools.moveStart
import app.absentmotion.tools.snapStart
import app.absentmotion.tools.stepKeyframe
import app.absentmotion.tools.videoStills
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Handle { Start, End }

private const val FRAMES_IN_STRIP = 8
private const val STEP_US = 100_000L

@Composable
internal fun RangePicker(
    uri: Uri, durationUs: Long, range: TrimRange, keys: LongArray?, snap: Boolean, maxSpanUs: Long, enabled: Boolean, onRange: (TrimRange) -> Unit,
) = key(uri) {
    val context = LocalContext.current
    var active by remember { mutableStateOf(Handle.Start) }
    val rangeNow by rememberUpdatedState(range)
    val keysNow by rememberUpdatedState(keys)
    val snapNow by rememberUpdatedState(snap)
    val send by rememberUpdatedState(onRange)
    val frames by produceState<List<ImageBitmap?>>(emptyList(), uri, durationUs) {
        value = videoStills(context, uri, List(FRAMES_IN_STRIP) { ((it + 0.5) * durationUs / FRAMES_IN_STRIP).toLong() }, 160)
    }
    val shownAt = if (active == Handle.Start) range.startUs else (range.endUs - 1).coerceAtLeast(0)
    val still by produceState<ImageBitmap?>(null, shownAt) {
        delay(120)
        videoStills(context, uri, listOf(shownAt), 640, exact = true).firstOrNull()?.let { value = it }
    }

    fun place(which: Handle, timeUs: Long) {
        val current = rangeNow
        val clamped = timeUs.coerceIn(0, durationUs)
        val next = if (which == Handle.Start) {
            val target = if (snapNow) snapStart(clamped, keysNow ?: LongArray(0), current.endUs) else clamped
            moveStart(current, target, durationUs, maxSpanUs)
        } else moveEnd(current, clamped, durationUs, maxSpanUs)
        if (next != current) send(next)
    }

    fun step(which: Handle, forward: Boolean) {
        active = which
        val from = if (which == Handle.Start) range.startUs else range.endUs
        val moved = if (which == Handle.Start && snap && keys != null && keys.isNotEmpty()) stepKeyframe(from, keys, forward) else from + if (forward) STEP_US else -STEP_US
        place(which, moved)
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(Am.Scrim), contentAlignment = Alignment.Center) {
            still?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            Text(
                clock(shownAt), Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(60.dp).testTag("tool-strip").clip(RoundedCornerShape(10.dp)).background(Am.Card).alpha(if (enabled) 1f else 0.5f).pointerInput(durationUs, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val now = rangeNow
                    val start = now.startUs.toFloat() / durationUs * width
                    val end = now.endUs.toFloat() / durationUs * width
                    val which = if (down.position.x < (start + end) / 2) Handle.Start else Handle.End
                    val edge = if (which == Handle.Start) start else end
                    val offset = if (abs(down.position.x - edge) <= 36.dp.toPx()) edge - down.position.x else 0f
                    active = which
                    place(which, ((down.position.x + offset) / width * durationUs).toLong())
                    drag(down.id) { change ->
                        place(which, ((change.position.x + offset) / width * durationUs).toLong())
                        change.consume()
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                repeat(FRAMES_IN_STRIP) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        frames.getOrNull(i)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val dim = Color.Black.copy(alpha = 0.6f)
                val x0 = range.startUs.toFloat() / durationUs * size.width
                val x1 = range.endUs.toFloat() / durationUs * size.width
                val edge = 3.dp.toPx()
                val grip = 14.dp.toPx()
                drawRect(dim, Offset.Zero, Size(x0, size.height))
                drawRect(dim, Offset(x1, 0f), Size(size.width - x1, size.height))
                drawRect(Am.Accent, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(0f), edge))
                drawRect(Am.Accent, Offset(x0, size.height - edge), Size((x1 - x0).coerceAtLeast(0f), edge))
                for ((x, left) in listOf(x0 to true, x1 to false)) {
                    val at = if (left) x else x - grip
                    drawRoundRect(Am.Accent, Offset(at, 0f), Size(grip, size.height), CornerRadius(4.dp.toPx()))
                    drawRoundRect(Am.OnAccent, Offset(at + grip / 2 - 1.dp.toPx(), size.height / 2 - 9.dp.toPx()), Size(2.dp.toPx(), 18.dp.toPx()), CornerRadius(1.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stepper(stringResource(R.string.tools_start), range.startUs, enabled, active == Handle.Start, { step(Handle.Start, false) }, { step(Handle.Start, true) }, Modifier.weight(1f))
            Stepper(stringResource(R.string.tools_end), range.endUs, enabled, active == Handle.End, { step(Handle.End, false) }, { step(Handle.End, true) }, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.tools_length, clock(range.lengthUs)), Modifier.fillMaxWidth().padding(top = 8.dp).height(20.dp),
            color = Am.HomeMid, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Stepper(label: String, timeUs: Long, enabled: Boolean, selected: Boolean, onLess: () -> Unit, onMore: () -> Unit, modifier: Modifier) {
    Row(
        modifier.height(52.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(12.dp)).background(if (selected) Am.HomeLine else Am.Card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clickable(enabled = enabled, onClick = onLess), contentAlignment = Alignment.Center) {
            Icon(ExportIcons.Prev, stringResource(R.string.tools_earlier), Modifier.size(18.dp), tint = Am.Navy)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Am.HomeDim, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
            Text(clock(timeUs), color = Am.Navy, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.size(48.dp).clickable(enabled = enabled, onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(ExportIcons.Next, stringResource(R.string.tools_later), Modifier.size(18.dp), tint = Am.Navy)
        }
    }
}
