package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object SelectionIcons {
    val Cancel = line("Cancel selection", "M18.9 5.1L5.1 18.9M5.1 5.1L18.9 18.9")
    val Next = line("Alignment options", "M9 18L15 12L9 6")
    val Previous = line("Group options", "M15 18L9 12L15 6")
    val Group = line("Group", "M3 7L3 5C3 3.9 3.9 3 5 3L7 3M17 3L19 3C20.1 3 21 3.9 21 5L21 7M21 17L21 19C21 20.1 20.1 21 19 21L17 21M7 21L5 21C3.9 21 3 20.1 3 19L3 17M8 7L13 7" +
            "A1 1 0 0 1 14 8L14 11A1 1 0 0 1 13 12L8 12A1 1 0 0 1 7 11L7 8A1 1 0 0 1 8 7ZM11 12L16 12A1 1 0 0 1 17 13L17 16A1 1 0 0 1 16 17L11 17A1 1 0 0 1 10 16" +
            "L10 13A1 1 0 0 1 11 12Z")
    val Mask = line("Group and mask", "M3 7L3 5A2 2 0 0 1 5 3L7 3M17 3L19 3A2 2 0 0 1 21 5L21 7M21 17L21 19A2 2 0 0 1 19 21L17 21M7 21L5 21A2 2 0 0 1 3 19L3 17M8.3 7.5L13.2 7.5" +
            "A0.8 0.8 0 0 1 14 8.3L14 13.2A0.8 0.8 0 0 1 13.2 14L8.3 14A0.8 0.8 0 0 1 7.5 13.2L7.5 8.3A0.8 0.8 0 0 1 8.3 7.5ZM10.4 13.8A3.4 3.4 0 1 0 17.2 13.8" +
            "A3.4 3.4 0 1 0 10.4 13.8Z")
    val Exclude = line("Group and exclude", "M3 7L3 5A2 2 0 0 1 5 3L7 3M17 3L19 3A2 2 0 0 1 21 5L21 7M21 17L21 19A2 2 0 0 1 19 21L17 21M7 21L5 21A2 2 0 0 1 3 19L3 17M8.3 7.5L13.2 7.5" +
            "A0.8 0.8 0 0 1 14 8.3L14 13.2A0.8 0.8 0 0 1 13.2 14L8.3 14A0.8 0.8 0 0 1 7.5 13.2L7.5 8.3A0.8 0.8 0 0 1 8.3 7.5ZM10.4 13.8A3.4 3.4 0 1 0 17.2 13.8" +
            "A3.4 3.4 0 1 0 10.4 13.8ZM11.4 11.4L13.6 13.6M13.6 11.4L11.4 13.6")
    val Align = listOf(
        line("Align left", "M8 14L13 14A2 2 0 0 1 15 16L15 18A2 2 0 0 1 13 20L8 20A2 2 0 0 1 6 18L6 16A2 2 0 0 1 8 14ZM8 4L20 4A2 2 0 0 1 22 6L22 8A2 2 0 0 1 20 10L8 10" +
            "A2 2 0 0 1 6 8L6 6A2 2 0 0 1 8 4ZM2 2L2 22"),
        line("Align centre", "M12 2L12 22M8 10L4 10A2 2 0 0 1 2 8L2 6C2 4.9 2.9 4 4 4L8 4M16 10L20 10A2 2 0 0 0 22 8L22 6A2 2 0 0 0 20 4L16 4M8 20L7 20A2 2 0 0 1 5 18L5 16" +
            "C5 14.9 5.9 14 7 14L8 14M16 14L17 14A2 2 0 0 1 19 16L19 18A2 2 0 0 1 17 20L16 20"),
        line("Align right", "M4 4L16 4A2 2 0 0 1 18 6L18 8A2 2 0 0 1 16 10L4 10A2 2 0 0 1 2 8L2 6A2 2 0 0 1 4 4ZM11 14L16 14A2 2 0 0 1 18 16L18 18A2 2 0 0 1 16 20L11 20" +
            "A2 2 0 0 1 9 18L9 16A2 2 0 0 1 11 14ZM22 22L22 2"),
        line("Align top", "M6 6L8 6A2 2 0 0 1 10 8L10 20A2 2 0 0 1 8 22L6 22A2 2 0 0 1 4 20L4 8A2 2 0 0 1 6 6ZM16 6L18 6A2 2 0 0 1 20 8L20 13A2 2 0 0 1 18 15L16 15" +
            "A2 2 0 0 1 14 13L14 8A2 2 0 0 1 16 6ZM22 2L2 2"),
        line("Align middle", "M2 12L22 12M10 16L10 20A2 2 0 0 1 8 22L6 22A2 2 0 0 1 4 20L4 16M10 8L10 4A2 2 0 0 0 8 2L6 2A2 2 0 0 0 4 4L4 8M20 16L20 17A2 2 0 0 1 18 19L16 19" +
            "A2 2 0 0 1 14 17L14 16M14 8L14 7C14 5.9 14.9 5 16 5L18 5A2 2 0 0 1 20 7L20 8"),
        line("Align bottom", "M6 2L8 2A2 2 0 0 1 10 4L10 16A2 2 0 0 1 8 18L6 18A2 2 0 0 1 4 16L4 4A2 2 0 0 1 6 2ZM16 9L18 9A2 2 0 0 1 20 11L20 16A2 2 0 0 1 18 18L16 18" +
            "A2 2 0 0 1 14 16L14 11A2 2 0 0 1 16 9ZM22 22L2 22"),
    )
    val DistributeHorizontal = line("Distribute horizontally", "M5 5L7 5A2 2 0 0 1 9 7L9 17A2 2 0 0 1 7 19L5 19A2 2 0 0 1 3 17L3 7A2 2 0 0 1 5 5ZM17 7L19 7A2 2 0 0 1 21 9L21 15A2 2 0 0 1 19 17L17 17A2 2 0 0 1 15 15" +
            "L15 9A2 2 0 0 1 17 7ZM3 2L3 22M21 2L21 22")
    val DistributeVertical = line("Distribute vertically", "M7 15L17 15A2 2 0 0 1 19 17L19 19A2 2 0 0 1 17 21L7 21A2 2 0 0 1 5 19L5 17A2 2 0 0 1 7 15ZM9 3L15 3A2 2 0 0 1 17 5L17 7A2 2 0 0 1 15 9L9 9" +
            "A2 2 0 0 1 7 7L7 5A2 2 0 0 1 9 3ZM2 21L22 21M2 3L22 3")
}

private fun line(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(addPathNodes(path), stroke = SolidColor(Color.White), strokeLineWidth = 1.3f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()
