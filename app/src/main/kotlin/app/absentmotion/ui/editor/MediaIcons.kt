package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
internal fun MediaGridIcon(modifier: Modifier) = Canvas(modifier) {
    val unit = size.width / 24
    for (y in 0..1) for (x in 0..2) {
        drawRoundRect(Color.White, Offset((1 + x * 8) * unit, (5 + y * 8) * unit), Size(6 * unit, 6 * unit), CornerRadius(1.2f * unit))
    }
}

@Composable
internal fun MediaAlbumArrow(modifier: Modifier) = Canvas(modifier) {
    val path = Path().apply { moveTo(size.width * 0.12f, size.height * 0.3f); lineTo(size.width * 0.5f, size.height * 0.72f); lineTo(size.width * 0.88f, size.height * 0.3f) }
    drawPath(path, Color(0xFF8F9198), style = Stroke(size.width * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
internal fun MediaPlayIcon(modifier: Modifier) = Canvas(modifier) {
    val inset = size.width * 0.1f
    val path = Path().apply { moveTo(inset, inset); lineTo(size.width - inset, size.height / 2); lineTo(inset, size.height - inset); close() }
    drawPath(path, Color.White)
    drawPath(path, Color.White, style = Stroke(inset * 1.6f, join = StrokeJoin.Round, cap = StrokeCap.Round))
}
