package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object AudioIcons {
    val Speaker = icon("Speaker", 32.dp, 32.dp, 32f, 32f, 1.7f, "M15 8.702A0.705 0.705 0 0 0 13.797 8.204L10.413 11.587A1.4 1.4 0 0 1 9.416 12L7 12A1 1 0 0 0 6 13L6 19A1 1 0 0 0 7 20L9.416 20" +
            "A1.4 1.4 0 0 1 10.413 20.413L13.796 23.797A0.705 0.705 0 0 0 15 23.298ZM20 13A5 5 0 0 1 20 19M23.364 22.364A9 9 0 0 0 23.364 9.636")

    val Note = icon("Note", 16.dp, 16.dp * 40f / 36f, 24f, 24f * 40f / 36f, 2f, "M21 17.333L21 4.333A1 1 0 0 0 19.72 3.373L9.72 6.29A1 1 0 0 0 9 7.25L9 20.333M15 17.333A3 3 0 1 0 21 17.333A3 3 0 1 0 15 17.333ZM3 20.333" +
            "A3 3 0 1 0 9 20.333A3 3 0 1 0 3 20.333Z")

    val Level = icon("Level", 15.dp, 12.dp, 24f, 19.2f, 2f, "M11 2.302A0.705 0.705 0 0 0 9.797 1.804L6.413 5.187A1.4 1.4 0 0 1 5.416 5.6L3 5.6A1 1 0 0 0 2 6.6L2 12.6A1 1 0 0 0 3 13.6L5.416 13.6" +
            "A1.4 1.4 0 0 1 6.413 14.013L9.796 17.397A0.705 0.705 0 0 0 11 16.898ZM16 6.6A5 5 0 0 1 16 12.6M19.364 15.964A9 9 0 0 0 19.364 3.236")

    private fun icon(name: String, width: Dp, height: Dp, vw: Float, vh: Float, stroke: Float, path: String): ImageVector =
        ImageVector.Builder(name, width, height, vw, vh).apply {
            addPath(
                addPathNodes(path), pathFillType = PathFillType.EvenOdd, stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}
