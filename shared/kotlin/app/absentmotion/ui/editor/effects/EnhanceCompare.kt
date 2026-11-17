package app.absentmotion.ui.editor.effects

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.turned
import app.absentmotion.library.openableUri
import app.absentmotion.media.decodePicture
import app.absentmotion.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CompareImages(val before: String, val after: String, val beforeTurn: Int = 0, val afterTurn: Int = 0)

internal fun compareFractions(fraction: Float): Pair<Float, Float> {
    val before = fraction.coerceIn(0f, 1f)
    return before to 1f - before
}

@Composable
internal fun EnhanceCompare(images: CompareImages, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    suspend fun load(uri: String, turn: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bitmap = decodePicture(context.contentResolver, Uri.parse(openableUri(uri)), 2048) ?: return@withContext null
        bitmap.turned(turn).also { if (it !== bitmap) bitmap.recycle() }.asImageBitmap()
    }
    val before by produceState<ImageBitmap?>(null, images.before, images.beforeTurn) { value = load(images.before, images.beforeTurn) }
    val after by produceState<ImageBitmap?>(null, images.after, images.afterTurn) { value = load(images.after, images.afterTurn) }
    var fraction by remember(images) { mutableFloatStateOf(0.5f) }
    val picture = after ?: before
    if (picture != null) BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = picture.width.toFloat() / picture.height
        val width = minOf(maxWidth, 500.dp * ratio)
        Box(Modifier.width(width).height(width / ratio).background(Color.Black)
        .pointerInput(images) { detectTapGestures { fraction = (it.x / size.width).coerceIn(0f, 1f) } }
        .pointerInput(images) { detectDragGestures(onDragStart = { fraction = (it.x / size.width).coerceIn(0f, 1f) }) { change, _ ->
            fraction = (change.position.x / size.width).coerceIn(0f, 1f)
            change.consume()
        } }) {
        after?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        before?.let { Image(it, null, Modifier.fillMaxSize().drawWithContent {
            clipRect(right = size.width * compareFractions(fraction).first) { this@drawWithContent.drawContent() }
        }, contentScale = ContentScale.FillBounds) }
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
        }
    }
}
