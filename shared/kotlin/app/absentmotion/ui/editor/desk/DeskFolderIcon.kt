package app.absentmotion.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.theme.Am

private const val BACK = "M6 15A6 6 0 0 1 12 9L24.6 9A6 6 0 0 1 29.2 11.1L31.6 14A3 3 0 0 0 33.9 15L52 15A6 6 0 0 1 58 21L58 50A6 6 0 0 1 52 56L12 56A6 6 0 0 1 6 50Z"
private const val SHEET = "M12 21L52 21A2 2 0 0 1 54 23L54 32L10 32L10 23A2 2 0 0 1 12 21Z"
private const val FRONT = "M6 28A6 6 0 0 1 12 22L52 22A6 6 0 0 1 58 28L58 50A6 6 0 0 1 52 56L12 56A6 6 0 0 1 6 50Z"
private const val SHINE = "M8.6 26.4A4 4 0 0 1 12 24.5L52 24.5A4 4 0 0 1 55.4 26.4"

internal fun deskFolderIcon(accent: Color, lit: Color, deep: Color, badge: Color, pinned: Boolean): ImageVector =
    ImageVector.Builder(if (pinned) "FolderPinned" else "Folder", 64.dp, 64.dp, 64f, 64f).apply {
        addPath(addPathNodes(BACK), fill = Brush.verticalGradient(listOf(deep, deep.copy(alpha = .78f)), 9f, 56f))
        addPath(addPathNodes(SHEET), fill = SolidColor(Color.White.copy(alpha = .86f)), pathFillType = PathFillType.NonZero)
        addPath(addPathNodes(FRONT), fill = Brush.verticalGradient(listOf(lit, accent), 22f, 56f))
        addPath(addPathNodes(SHINE), stroke = SolidColor(Color.White.copy(alpha = .38f)), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round)
        if (pinned) {
            addPath(addPathNodes("M60.5 44A10.5 10.5 0 1 1 39.5 44A10.5 10.5 0 1 1 60.5 44Z"), fill = SolidColor(badge))
            addPath(addPathNodes("M58 44A8 8 0 1 1 42 44A8 8 0 1 1 58 44Z"), fill = SolidColor(accent))
            addPath(addPathNodes("M47.4 38.8L52.6 38.8L52.1 42.4L53.6 44.4L46.4 44.4L47.9 42.4ZM50 44.4L50 49.6"), fill = SolidColor(Color.White), stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round)
        }
    }.build()

@Composable
internal fun rememberDeskFolderIcon(pinned: Boolean): ImageVector {
    val accent = Am.Accent
    val lit = Am.AccentLit
    val deep = Am.AccentDeep
    val badge = Am.Field
    return remember(accent, lit, deep, badge, pinned) { deskFolderIcon(accent, lit, deep, badge, pinned) }
}
