package app.absentmotion.ui.camera

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object CameraIcons {
    val Camera = icon("Camera", "M13.997 4A2 2 0 0 1 15.757 5.05L16.243 5.95A2 2 0 0 0 18.003 7L20 7A2 2 0 0 1 22 9L22 18A2 2 0 0 1 20 20L4 20" +
        "A2 2 0 0 1 2 18L2 9A2 2 0 0 1 4 7L5.997 7A2 2 0 0 0 7.756 5.952L8.245 5.048A2 2 0 0 1 10.004 4ZM9 13A3 3 0 1 0 15 13A3 3 0 1 0 9 13Z")
    val Switch = icon("Switch Camera", "M11 19L4 19A2 2 0 0 1 2 17L2 7A2 2 0 0 1 4 5L9 5M13 5L20 5A2 2 0 0 1 22 7L22 17A2 2 0 0 1 20 19L15 19" +
        "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12ZM18 22L15 19L18 16M6 2L9 5L6 8")
    val Flash = icon("Flash", "M15.914 4A1.5 1.5 0 0 0 13.44 2.439L4.44 11.439A1.5 1.5 0 0 0 5.5 14L9.502 14A0.5 0.5 0 0 1 9.973 14.666L8.086 20" +
        "A1.5 1.5 0 0 0 10.561 21.56L19.561 12.56A1.5 1.5 0 0 0 18.5 10L14.503 10A0.5 0.5 0 0 1 14.031 9.333Z")
    val FlashOff = icon("Flash Off", "M10.768 5.111L13.44 2.44A1.5 1.5 0 0 1 15.914 4.001L14.281 8.626M18.889 13.232L19.561 12.56A1.5 1.5 0 0 0 18.5 10" +
        "L15.656 10M2 2L22 22M7.94 7.94L4.44 11.439A1.5 1.5 0 0 0 5.5 14L9.502 14A0.5 0.5 0 0 1 9.973 14.666L8.086 20A1.5 1.5 0 0 0 10.561 21.56L16.061 16.06")
    val Timer = icon("Timer", "M10 2L14 2M12 14L15 11M4 14A8 8 0 1 0 20 14A8 8 0 1 0 4 14Z")
    val TimerOff = icon("Timer Off", "M10 2L14 2M4.6 11A8 8 0 0 0 6.3 19.7A8 8 0 0 0 15 21.4M7.4 7.4A8 8 0 0 1 17.7 8.4A8 8 0 0 1 18.6 18.6M2 2L22 22M12 12L12 10")
    val Grid = icon("Grid", "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM3 9L21 9M3 15L21 15M9 3L9 21M15 3L15 21")

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
}
