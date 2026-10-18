package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.theme.Am

internal object TimelineIcons {
    private val eyePath = "M2.062 9.348A1 1 0 0 1 2.062 8.652A10.75 10.75 0 0 1 21.938 8.652A1 1 0 0 1 21.938 9.348A10.75 10.75 0 0 1 2.062 9.348M9 9A3 3 0 1 0 15 9" +
            "A3 3 0 1 0 9 9Z"

    private val eyeOffPath = "M10.733 2.076A10.744 10.744 0 0 1 21.938 8.651A1 1 0 0 1 21.938 9.347A10.747 10.747 0 0 1 20.494 11.837M14.084 11.158A3 3 0 0 1 9.842 6.916" +
            "M17.479 14.499A10.75 10.75 0 0 1 2.062 9.348A1 1 0 0 1 2.062 8.652A10.75 10.75 0 0 1 6.508 3.509M2 -1L22 19"

    private val made = HashMap<Pair<String, Color>, ImageVector>()

    val Eye: ImageVector get() = Am.OnDark.let { c -> made.getOrPut("Eye" to c) { eye("Eye", c, eyePath) } }

    val EyeOff: ImageVector get() = Am.OnDarkDim.let { c -> made.getOrPut("EyeOff" to c) { eye("EyeOff", c, eyeOffPath) } }
}

private fun eye(name: String, color: Color, path: String): ImageVector =
    ImageVector.Builder(name, 20.dp, 15.dp, 24f, 18f).apply {
        addPath(
            addPathNodes(path), stroke = SolidColor(color), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
