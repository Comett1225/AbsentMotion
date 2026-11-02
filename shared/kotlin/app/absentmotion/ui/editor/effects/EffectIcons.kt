package app.absentmotion.ui.editor.effects

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object EffectIcons {
    val Close = icon("Close", "M18.9 5.1L5.1 18.9M5.1 5.1L18.9 18.9")
    val Search = icon("Search", "M21 21L16.66 16.66M3 11A8 8 0 1 0 19 11A8 8 0 1 0 3 11Z")
    val Add = icon("AddEffect", "M5 12L19 12M12 5L12 19")
    val Expand = solid("ExpandEffect", "M8.5 6.5L16.5 12L8.5 17.5Z")
    val Expanded = solid("ExpandedEffect", "M6.5 8.5L17.5 8.5L12 16.5Z")
    val MoreVert = solid("MoreVert", "M11 12A1 1 0 1 0 13 12A1 1 0 1 0 11 12ZM11 5A1 1 0 1 0 13 5A1 1 0 1 0 11 5ZM11 19A1 1 0 1 0 13 19A1 1 0 1 0 11 19Z", 2.0f)
    val Grip = icon("EffectGrip", "M4 5L20 5M4 12L20 12M4 19L20 19")
    val Eye = icon("EffectVisible", "M2.062 12.348A1 1 0 0 1 2.062 11.652A10.75 10.75 0 0 1 21.938 11.652A1 1 0 0 1 21.938 12.348A10.75 10.75 0 0 1 2.062 12.348M9 12A3 3 0 1 0 15 12" +
            "A3 3 0 1 0 9 12Z")
    val EyeOff = icon("EffectHidden", "M10.733 5.076A10.744 10.744 0 0 1 21.938 11.651A1 1 0 0 1 21.938 12.347A10.747 10.747 0 0 1 20.494 14.837M14.084 14.158A3 3 0 0 1 9.842 9.916" +
            "M17.479 17.499A10.75 10.75 0 0 1 2.062 12.348A1 1 0 0 1 2.062 11.652A10.75 10.75 0 0 1 6.508 6.509M2 2L22 22")
    val Recent = icon("Recent", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM12 6L12 12L16 14")
    private fun solid(name: String, path: String, width: Float = 1.6f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes(path), fill = SolidColor(Color.White), stroke = SolidColor(Color.White), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes(path), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
}
