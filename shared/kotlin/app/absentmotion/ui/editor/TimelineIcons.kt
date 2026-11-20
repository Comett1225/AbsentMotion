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

    private val lockBody = "M6.5 11L17.5 11A1.5 1.5 0 0 1 19 12.5L19 19.5A1.5 1.5 0 0 1 17.5 21L6.5 21A1.5 1.5 0 0 1 5 19.5L5 12.5A1.5 1.5 0 0 1 6.5 11Z"

    private val timingLockPath = "M4.3 11.5L11.7 11.5A1.3 1.3 0 0 1 13 12.8L13 18.7A1.3 1.3 0 0 1 11.7 20L4.3 20A1.3 1.3 0 0 1 3 18.7L3 12.8A1.3 1.3 0 0 1 4.3 11.5Z" +
            "M5.5 11.5L5.5 8.5A2.5 2.5 0 0 1 10.5 8.5L10.5 11.5M14.6 16.5A4.2 4.2 0 1 0 23 16.5A4.2 4.2 0 1 0 14.6 16.5ZM18.8 14.4L18.8 16.5L20.2 17.4"

    val LockOpen: ImageVector get() = Am.OnDarkDim.copy(alpha = 0.45f).let { c -> made.getOrPut("LockOpen" to c) { padlock("LockOpen", c, lockBody + "M8 11L8 7.5A4 4 0 0 1 15.8 6.3") } }

    val Lock: ImageVector get() = Am.OnDark.let { c -> made.getOrPut("Lock" to c) { padlock("Lock", c, lockBody + "M8 11L8 7.5A4 4 0 0 1 16 7.5L16 11") } }

    val LockTiming: ImageVector get() = Am.OnDark.let { c -> made.getOrPut("LockTiming" to c) { padlock("LockTiming", c, timingLockPath) } }
}

private fun padlock(name: String, color: Color, path: String): ImageVector =
    ImageVector.Builder(name, 18.dp, 18.dp, 24f, 24f).apply {
        addPath(
            addPathNodes(path), stroke = SolidColor(color), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

private fun eye(name: String, color: Color, path: String): ImageVector =
    ImageVector.Builder(name, 20.dp, 15.dp, 24f, 18f).apply {
        addPath(
            addPathNodes(path), stroke = SolidColor(color), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
