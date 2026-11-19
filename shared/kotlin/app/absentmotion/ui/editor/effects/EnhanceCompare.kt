package app.absentmotion.ui.editor.effects

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.turned
import app.absentmotion.library.openableUri
import app.absentmotion.media.decodePicture
import app.absentmotion.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

internal data class CompareImages(val before: String, val after: String, val beforeTurn: Int = 0, val afterTurn: Int = 0)

internal fun compareFractions(fraction: Float): Pair<Float, Float> {
    val before = fraction.coerceIn(0f, 1f)
    return before to 1f - before
}

internal const val COMPARE_MAX_ZOOM = 8f

internal data class CompareView(val zoom: Float = 1f, val pan: Offset = Offset.Zero)

internal fun CompareView.zoomed(factor: Float, focus: Offset, size: Size, moved: Offset = Offset.Zero): CompareView {
    val next = (zoom * factor).coerceIn(1f, COMPARE_MAX_ZOOM)
    if (next == 1f || factor < 1f && next < 1.001f) return CompareView()
    val pan = focus - (focus - pan - moved) * (next / zoom)
    return CompareView(next, Offset(pan.x.coerceIn(size.width * (1f - next), 0f), pan.y.coerceIn(size.height * (1f - next), 0f)))
}

internal fun CompareView.moved(by: Offset, size: Size): CompareView = zoomed(1f, Offset.Zero, size, by)

internal fun compareWheel(scroll: Float): Float = exp(-scroll * 0.2f).coerceIn(0.5f, 2f)

internal fun compareZoomLabel(zoom: Float): String? = if ((zoom * 10f).roundToInt() > 10) "%.1f×".format(zoom) else null

@Composable
internal fun EnhanceCompare(images: CompareImages, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    suspend fun load(uri: String, turn: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodePicture(context.contentResolver, Uri.parse(openableUri(uri)), 2048) }.getOrNull() ?: return@withContext null
        val shown = try { bitmap.turned(turn) } catch (_: OutOfMemoryError) { bitmap }
        shown.also { if (it !== bitmap) bitmap.recycle() }.asImageBitmap()
    }
    val before by produceState<ImageBitmap?>(null, images.before, images.beforeTurn) { value = load(images.before, images.beforeTurn) }
    val after by produceState<ImageBitmap?>(null, images.after, images.afterTurn) { value = load(images.after, images.afterTurn) }
    var fraction by remember(images) { mutableFloatStateOf(0.5f) }
    var view by remember(images) { mutableStateOf(CompareView()) }
    val picture = after ?: before
    if (picture != null) BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = picture.width.toFloat() / picture.height
        val width = minOf(maxWidth, 500.dp * ratio)
        Box(Modifier.width(width).height(width / ratio).clipToBounds().background(Color.Black)
        .pointerInput(images) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    view = view.zoomed(compareWheel(change.scrollDelta.y), change.position, size.toSize())
                    change.consume()
                }
            }
        }
        .pointerInput(images) {
            var lastUp = Long.MIN_VALUE / 2
            awaitEachGesture {
                val down = awaitFirstDown()
                val box = size.toSize()
                val slider = view.zoom <= 1f || abs(down.position.x - box.width * fraction) <= 24.dp.toPx()
                var moved = false
                var pinched = false
                var travel = Offset.Zero
                var upAt = down.uptimeMillis
                do {
                    val event = awaitPointerEvent()
                    upAt = event.changes.first().uptimeMillis
                    if (event.changes.count { it.pressed } > 1 || pinched) {
                        pinched = true
                        val centroid = event.calculateCentroid()
                        if (centroid.isSpecified) view = view.zoomed(event.calculateZoom(), centroid, box, event.calculatePan())
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = event.changes.first()
                    val step = if (moved) change.positionChange() else {
                        travel += change.positionChange()
                        if (travel.getDistance() > viewConfiguration.touchSlop) travel.also { moved = true } else Offset.Zero
                    }
                    if (moved && change.pressed) {
                        if (slider) fraction = (change.position.x / box.width).coerceIn(0f, 1f) else view = view.moved(step, box)
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
                if (moved || pinched) return@awaitEachGesture
                if (view.zoom <= 1f) fraction = (down.position.x / box.width).coerceIn(0f, 1f)
                if (down.uptimeMillis - lastUp < viewConfiguration.doubleTapTimeoutMillis) {
                    view = CompareView()
                    lastUp = Long.MIN_VALUE / 2
                } else lastUp = upAt
            }
        }) {
        val zoomed = Modifier.fillMaxSize().graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = view.zoom
            scaleY = view.zoom
            translationX = view.pan.x
            translationY = view.pan.y
        }
        after?.let { Image(it, null, zoomed, contentScale = ContentScale.FillBounds) }
        before?.let { Image(it, null, Modifier.fillMaxSize().drawWithContent {
            clipRect(right = size.width * compareFractions(fraction).first) { this@drawWithContent.drawContent() }
        }.then(zoomed), contentScale = ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * fraction
            val y = size.height / 2
            val radius = 20.dp.toPx()
            val stroke = 2.dp.toPx()
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), stroke)
            drawCircle(Color.Black.copy(alpha = 0.7f), radius, Offset(x, y))
            drawCircle(Color.White, radius, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val arrow = 6.dp.toPx()
            drawLine(Color.White, Offset(x - 3.dp.toPx(), y - arrow), Offset(x - 9.dp.toPx(), y), stroke)
            drawLine(Color.White, Offset(x - 9.dp.toPx(), y), Offset(x - 3.dp.toPx(), y + arrow), stroke)
            drawLine(Color.White, Offset(x + 3.dp.toPx(), y - arrow), Offset(x + 9.dp.toPx(), y), stroke)
            drawLine(Color.White, Offset(x + 9.dp.toPx(), y), Offset(x + 3.dp.toPx(), y + arrow), stroke)
        }
        Text(stringResource(R.string.ai_compare_before), Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, fontSize = 12.sp)
        Text(stringResource(R.string.ai_compare_after), Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, fontSize = 12.sp)
        compareZoomLabel(view.zoom)?.let { Text(it, Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp, maxLines = 1) }
        }
    }
}
