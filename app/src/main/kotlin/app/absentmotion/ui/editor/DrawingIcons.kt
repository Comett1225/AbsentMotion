package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object DrawingIcons {
    val Pen = vec("Pen", 25f, 26f, 25f, 26f) {
        stroke("M21.674 7.812A1 1 0 0 0 17.688 3.825L4.342 17.174A2 2 0 0 0 3.842 18.004L2.521 22.356A0.5 0.5 0 0 0 3.144 22.978L7.497 21.658A2 2 0 0 0 8.327 21.161Z", 1.6f)
    }

    val Brush = vec("Brush", 26f, 26f, 26f, 26f) {
        stroke("M12 11L15 14M7.5 22A3.5 3.5 0 1 0 4 18.5A2.62 2.62 0 0 1 3.292 20.292A1 1 0 0 0 4 22ZM10.969 18.031L22.378 6.624A1 1 0 0 0 19.376 3.622L7.967 15.031", 1.6f)
    }

    val Fill = vec("Fill", 20f, 24f, 20f, 24f) {
        stroke("M9.17 7.85L5.02 3.7M15.803 12L1.734 12M17.59 17.295A2.772 2.772 0 0 1 16.64 15.735A2.739 2.739 0 0 1 15.69 17.295" +
            "C15.212 17.677 14.98 18.142 14.98 18.619A1.66 1.66 0 0 0 16.64 20.3A1.66 1.66 0 0 0 18.3 18.619C18.3 18.138 18.063 17.681 17.59 17.295M7.095 5.775" +
            "L8.878 3.992A1 1 0 0 1 10.292 3.992L16.348 10.048A1 1 0 0 1 16.348 11.462L10.046 17.764A3 3 0 0 1 5.804 17.764L2.576 14.536A3 3 0 0 1 2.576 10.294" +
            "L4.746 8.124", 1.9f)
    }

    val Eraser = vec("Eraser", 25f, 21f, 25f, 21f) {
        stroke("M20.375 18.375L9 18.375A1.75 1.75 0 0 1 7.758 17.861L4.263 14.362A1.75 1.75 0 0 1 4.263 11.888L13.013 3.138A1.75 1.75 0 0 1 15.488 3.138L20.737 8.388" +
            "A1.75 1.75 0 0 1 20.737 10.862L13.23 18.375M6.447 9.704L14.171 17.428", 1.8f)
    }

    val PathClosed = vec("PathClosed", 23f, 23f, 24f, 24f) {
        stroke("M4.5 12A7.5 7.5 0 1 0 19.5 12A7.5 7.5 0 1 0 4.5 12Z", 1.5f)
        fill("M10 4.5A2 2 0 1 0 14 4.5A2 2 0 1 0 10 4.5ZM17.5 12A2 2 0 1 0 21.5 12A2 2 0 1 0 17.5 12ZM10 19.5A2 2 0 1 0 14 19.5A2 2 0 1 0 10 19.5ZM2.5 12" +
            "A2 2 0 1 0 6.5 12A2 2 0 1 0 2.5 12Z")
    }

    val PathOpen = vec("PathOpen", 23f, 23f, 24f, 24f) {
        stroke("M12 4.5A7.5 7.5 0 1 1 4.5 12", 1.5f)
        fill("M10 4.5A2 2 0 1 0 14 4.5A2 2 0 1 0 10 4.5ZM17.5 12A2 2 0 1 0 21.5 12A2 2 0 1 0 17.5 12ZM10 19.5A2 2 0 1 0 14 19.5A2 2 0 1 0 10 19.5ZM2.5 12" +
            "A2 2 0 1 0 6.5 12A2 2 0 1 0 2.5 12Z")
    }

    val VertexCurved = vec("VertexCurved", 25f, 13f, 25f, 13f) {
        stroke("M2 11.5C6 3 19 3 23 11.5", 1.2f, 0.3f)
        stroke("M4 6.5H21M10.1 6.5A2.4 2.4 0 1 0 14.9 6.5A2.4 2.4 0 1 0 10.1 6.5Z", 1.5f)
        fill("M2.1 6.5A1.9 1.9 0 1 0 5.9 6.5A1.9 1.9 0 1 0 2.1 6.5ZM19.1 6.5A1.9 1.9 0 1 0 22.9 6.5A1.9 1.9 0 1 0 19.1 6.5Z")
    }

    val VertexSharp = vec("VertexSharp", 22f, 13f, 22f, 13f) {
        stroke("M2.5 11L11 3.2L19.5 11", 1.2f, 0.3f)
        stroke("M9.4 4.7L4.9 9.2M12.6 4.7L17.1 9.2M9.3 1.1L12.7 1.1A0.6 0.6 0 0 1 13.3 1.7L13.3 5.1A0.6 0.6 0 0 1 12.7 5.7L9.3 5.7A0.6 0.6 0 0 1 8.7 5.1L8.7 1.7" +
            "A0.6 0.6 0 0 1 9.3 1.1Z", 1.5f)
        fill("M2.9 8.7L5.5 8.7A0.5 0.5 0 0 1 6 9.2L6 11.8A0.5 0.5 0 0 1 5.5 12.3L2.9 12.3A0.5 0.5 0 0 1 2.4 11.8L2.4 9.2A0.5 0.5 0 0 1 2.9 8.7ZM16.5 8.7L19.1 8.7" +
            "A0.5 0.5 0 0 1 19.6 9.2L19.6 11.8A0.5 0.5 0 0 1 19.1 12.3L16.5 12.3A0.5 0.5 0 0 1 16 11.8L16 9.2A0.5 0.5 0 0 1 16.5 8.7Z")
    }

    val DeletePoint = vec("DeletePoint", 16.25f, 16.25f, 24f, 24f) {
        stroke("M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM8 12L16 12", 2.2f)
    }

    val AddPoint = vec("AddPoint", 20f, 20f, 24f, 24f) {
        stroke("M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM8 12L16 12M12 8L12 16", 2.2f)
    }

    val SizeDot = vec("SizeDot", 22f, 22f, 22f, 22f) {
        fill("M1 11A10 10 0 1 0 21 11A10 10 0 1 0 1 11Z", 0.2f)
        fill("M4.2 11A6.8 6.8 0 1 0 17.8 11A6.8 6.8 0 1 0 4.2 11Z", 0.3f)
        fill("M7.2 11A3.8 3.8 0 1 0 14.8 11A3.8 3.8 0 1 0 7.2 11Z", 0.45f)
    }

    val Contours = vec("Contours", 24f, 24f, 24f, 24f) {
        stroke("M2.5 12A9.5 9.5 0 1 0 21.5 12A9.5 9.5 0 1 0 2.5 12ZM7.2 12A4.8 4.8 0 1 0 16.8 12A4.8 4.8 0 1 0 7.2 12Z", 1.6f)
    }

    val Prev = vec("Prev", 24f, 24f, 24f, 24f) {
        stroke("M15 18L9 12L15 6", 2.2f)
    }

    val Next = vec("Next", 24f, 24f, 24f, 24f) {
        stroke("M9 18L15 12L9 6", 2.2f)
    }

    val AngleLock = vec("AngleLock", 26f, 25f, 26f, 25f) {
        stroke("M4.39 8.485A9.5 9.5 0 0 1 21.61 8.485M21.61 16.515A9.5 9.5 0 0 1 4.39 16.515", 1.3f)
        stroke("M5.5 12.5H20.5", 1.2f, 0.3f)
        fill("M2.5 11L4.5 11A0.5 0.5 0 0 1 5 11.5L5 13.5A0.5 0.5 0 0 1 4.5 14L2.5 14A0.5 0.5 0 0 1 2 13.5L2 11.5A0.5 0.5 0 0 1 2.5 11ZM21.5 11L23.5 11" +
            "A0.5 0.5 0 0 1 24 11.5L24 13.5A0.5 0.5 0 0 1 23.5 14L21.5 14A0.5 0.5 0 0 1 21 13.5L21 11.5A0.5 0.5 0 0 1 21.5 11ZM11.4 12.5A1.6 1.6 0 1 0 14.6 12.5" +
            "A1.6 1.6 0 1 0 11.4 12.5Z", 0.3f)
    }

    val AngleLockOn = vec("AngleLockOn", 26f, 25f, 26f, 25f) {
        stroke("M4.39 8.485A9.5 9.5 0 0 1 21.61 8.485M21.61 16.515A9.5 9.5 0 0 1 4.39 16.515", 1.3f)
        stroke("M5.5 12.5H20.5", 1.2f, 0.9f)
        fill("M2.5 11L4.5 11A0.5 0.5 0 0 1 5 11.5L5 13.5A0.5 0.5 0 0 1 4.5 14L2.5 14A0.5 0.5 0 0 1 2 13.5L2 11.5A0.5 0.5 0 0 1 2.5 11ZM21.5 11L23.5 11" +
            "A0.5 0.5 0 0 1 24 11.5L24 13.5A0.5 0.5 0 0 1 23.5 14L21.5 14A0.5 0.5 0 0 1 21 13.5L21 11.5A0.5 0.5 0 0 1 21.5 11ZM11.4 12.5A1.6 1.6 0 1 0 14.6 12.5" +
            "A1.6 1.6 0 1 0 11.4 12.5Z", 0.9f)
    }

    val DistanceLock = vec("DistanceLock", 26f, 16f, 26f, 16f) {
        stroke("M8 8H18M10.5 5.5L8 8L10.5 10.5M15.5 5.5L18 8L15.5 10.5", 1.3f)
        stroke("M5 8H6.2M19.8 8H21", 1.2f, 0.3f)
        fill("M2.5 6.5L4.5 6.5A0.5 0.5 0 0 1 5 7L5 9A0.5 0.5 0 0 1 4.5 9.5L2.5 9.5A0.5 0.5 0 0 1 2 9L2 7A0.5 0.5 0 0 1 2.5 6.5ZM21.5 6.5L23.5 6.5A0.5 0.5 0 0 1 24 7" +
            "L24 9A0.5 0.5 0 0 1 23.5 9.5L21.5 9.5A0.5 0.5 0 0 1 21 9L21 7A0.5 0.5 0 0 1 21.5 6.5Z", 0.3f)
    }

    val DistanceLockOn = vec("DistanceLockOn", 26f, 16f, 26f, 16f) {
        stroke("M8 8H18M10.5 5.5L8 8L10.5 10.5M15.5 5.5L18 8L15.5 10.5", 1.3f)
        stroke("M5 8H6.2M19.8 8H21", 1.2f, 0.9f)
        fill("M2.5 6.5L4.5 6.5A0.5 0.5 0 0 1 5 7L5 9A0.5 0.5 0 0 1 4.5 9.5L2.5 9.5A0.5 0.5 0 0 1 2 9L2 7A0.5 0.5 0 0 1 2.5 6.5ZM21.5 6.5L23.5 6.5A0.5 0.5 0 0 1 24 7" +
            "L24 9A0.5 0.5 0 0 1 23.5 9.5L21.5 9.5A0.5 0.5 0 0 1 21 9L21 7A0.5 0.5 0 0 1 21.5 6.5Z", 0.9f)
    }

    private fun vec(name: String, width: Float, height: Float, vw: Float, vh: Float, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, width.dp, height.dp, vw, vh).apply(block).build()

    private fun ImageVector.Builder.stroke(path: String, width: Float, alpha: Float = 1f) = addPath(
        addPathNodes(path), stroke = SolidColor(Color.Black), strokeAlpha = alpha, strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.fill(path: String, alpha: Float = 1f) = addPath(
        addPathNodes(path), fill = SolidColor(Color.Black), fillAlpha = alpha, pathFillType = PathFillType.NonZero,
    )
}
