package app.absentmotion.ui.export

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object ExportIcons {
    val Close = icon("Close", "M19.2 4.8L4.8 19.2M4.8 4.8L19.2 19.2", 1.8f)
    val Back = icon("Back", "M12 19L5 12L12 5M19 12L5 12", 1.5f, mirror = true)
    val ChevronRight = icon("ChevronRight", "M8.7 18.6L15.3 12L8.7 5.4", 1.5f, mirror = true)
    val ChevronDown = icon("ChevronDown", "M5.4 8.7L12 15.3L18.6 8.7", 1.6f)
    val Info = icon("Info", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM12 16L12 12M12 8L12.01 8", 1.4f)
    val Help = icon("Help", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM9.2 9.4A2.9 2.9 0 0 1 14.8 10.2C14.8 12.1 12 12.5 12 14.3M12 17.6L12.01 17.6", 1.4f)
    val Video = icon("Video", "M12.296 3.464L15.316 7.42M20.2 6L3 11L2.1 8.6C1.8 7.5 2.4 6.4 3.4 6.1L16.9 2.1C18 1.8 19.1 2.4 19.4 3.4ZM3 11L21 11L21 19A2 2 0 0 1 19 21L5 21" +
            "A2 2 0 0 1 3 19ZM6.18 5.276L9.28 9.175", 1.25f)
    val Package = icon("Package", "M11 21.73A2 2 0 0 0 13 21.73L20 17.73A2 2 0 0 0 21 16L21 8A2 2 0 0 0 20 6.27L13 2.27A2 2 0 0 0 11 2.27L4 6.27A2 2 0 0 0 3 8L3 16A2 2 0 0 0 4 17.73Z" +
            "M12 22L12 12M3.29 7L12 12L20.71 7M7.5 4.27L16.5 9.42", 1.25f)
    val Frame = icon("Frame", "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM7 9A2 2 0 1 0 11 9A2 2 0 1 0 7 9ZM21 15L17.914 11.914" +
            "A2 2 0 0 0 15.086 11.914L6 21", 1.25f)
    val Sequence = icon("Sequence", "M22 11L20.704 9.704A2.4 2.4 0 0 0 17.296 9.704L11 16M4 8A2 2 0 0 0 2 10L2 20A2 2 0 0 0 4 22L14 22A2 2 0 0 0 16 20M12 7A1 1 0 1 0 14 7A1 1 0 1 0 12 7Z" +
            "M10 2L20 2A2 2 0 0 1 22 4L22 14A2 2 0 0 1 20 16L10 16A2 2 0 0 1 8 14L8 4A2 2 0 0 1 10 2Z", 1.25f)
    val Xml = icon("Xml", "M18 16L22 12L18 8M6 8L2 12L6 16M14.5 4L9.5 20", 1.4f)
    val Lock = icon("Lock", "M6.5 11L17.5 11A1.5 1.5 0 0 1 19 12.5L19 19.5A1.5 1.5 0 0 1 17.5 21L6.5 21A1.5 1.5 0 0 1 5 19.5L5 12.5A1.5 1.5 0 0 1 6.5 11Z" +
            "M8 11L8 7.5A4 4 0 0 1 16 7.5L16 11", 1.8f)

    val File = icon("File", "M6 22A2 2 0 0 1 4 20L4 4A2 2 0 0 1 6 2L14 2A2.4 2.4 0 0 1 15.704 2.706L19.292 6.294A2.4 2.4 0 0 1 20 8L20 20A2 2 0 0 1 18 22ZM14 2L14 7A1 1 0 0 0 15 8" +
            "L20 8", 1.25f)

    val Play = filled("Play", 12f, 16f, "M0 2.4A1.6 1.6 0 0 1 2.406 1.018L12.004 6.616A1.6 1.6 0 0 1 12.006 9.382L2.406 14.982A1.6 1.6 0 0 1 0 13.6Z")
    val Pause = filled("Pause", 12f, 16f, "M8.5 0.35L11.05 0.35A0.85 0.85 0 0 1 11.9 1.2L11.9 14.8A0.85 0.85 0 0 1 11.05 15.65L8.5 15.65A0.85 0.85 0 0 1 7.65 14.8L7.65 1.2" +
            "A0.85 0.85 0 0 1 8.5 0.35ZM0.85 0.35L3.4 0.35A0.85 0.85 0 0 1 4.25 1.2L4.25 14.8A0.85 0.85 0 0 1 3.4 15.65L0.85 15.65A0.85 0.85 0 0 1 0 14.8L0 1.2" +
            "A0.85 0.85 0 0 1 0.85 0.35Z")
    val Prev = stroked("Prev", 11f, 19f, 11f, 19f, "M9 2L2 9.5L9 17", 1.6f, mirror = true)
    val Next = stroked("Next", 11f, 19f, 11f, 19f, "M2 2L9 9.5L2 17", 1.6f, mirror = true)
    val Share = stroked("Share", 20f, 20f, 24f, 24f, "M21 13L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3L11 3M21 3L12 12M15 3L21 3L21 9", 1.8f)

    private fun filled(name: String, w: Float, h: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, w.dp, h.dp, w, h).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private fun stroked(name: String, w: Float, h: Float, vw: Float, vh: Float, path: String, width: Float, mirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, w.dp, h.dp, vw, vh, autoMirror = mirror).apply {
            addPath(
                addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun icon(name: String, path: String, width: Float, mirror: Boolean = false): ImageVector = stroked(name, 24f, 24f, 24f, 24f, path, width, mirror)
}
