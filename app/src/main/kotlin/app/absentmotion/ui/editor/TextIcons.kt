package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object TextIcons {
    val Edit = icon("Edit Text", "M2 16L6.039 6.31A0.5 0.5 0 0 1 6.962 6.31L11 16M22 9L22 16M3.304 13L9.696 13M15 12.5A3.5 3.5 0 1 0 22 12.5A3.5 3.5 0 1 0 15 12.5Z")
    val Left = icon("Align Left", "M21 5L3 5M15 12L3 12M17 19L3 19")
    val Centre = icon("Align Centre", "M21 5L3 5M17 12L7 12M19 19L5 19")
    val Right = icon("Align Right", "M21 5L3 5M21 12L9 12M21 19L7 19")
    val Justify = icon("Justify", "M3 5L21 5M3 12L21 12M3 19L21 19")
    val Tick = icon("Done", "M20 6L9 17L4 12")
    val Menu = icon("Menu", "M4 5L20 5M4 12L20 12M4 19L20 19")
    val Search = icon("Search", "M21 21L16.66 16.66M3 11A8 8 0 1 0 19 11A8 8 0 1 0 3 11Z")
    val Import = icon("Import Font", "M12 3L12 15M8 11L12 15L16 11M8 5L4 5A2 2 0 0 0 2 7L2 17A2 2 0 0 0 4 19L20 19A2 2 0 0 0 22 17L22 7A2 2 0 0 0 20 5L16 5")
    val Recent = icon("Recent", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM12 6L12 12L16 14")
    val Down = ImageVector.Builder("Dropdown", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M6 9.5L18 9.5L12 16.5Z"), fill = SolidColor(Color.White)).build()
    val Up = ImageVector.Builder("Dropdown Up", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M6 14.5L18 14.5L12 7.5Z"), fill = SolidColor(Color.White)).build()
    val DownOutline = icon("Dropdown", "M6 9L12 15L18 9")
    val ViewAll = ImageVector.Builder("View All", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M4 3L9 3A1 1 0 0 1 10 4L10 9A1 1 0 0 1 9 10L4 10A1 1 0 0 1 3 9L3 4A1 1 0 0 1 4 3ZM15 3L20 3A1 1 0 0 1 21 4L21 9A1 1 0 0 1 20 10L15 10A1 1 0 0 1 14 9" +
            "L14 4A1 1 0 0 1 15 3ZM15 14L20 14A1 1 0 0 1 21 15L21 20A1 1 0 0 1 20 21L15 21A1 1 0 0 1 14 20L14 15A1 1 0 0 1 15 14ZM4 14L9 14A1 1 0 0 1 10 15L10 20" +
            "A1 1 0 0 1 9 21L4 21A1 1 0 0 1 3 20L3 15A1 1 0 0 1 4 14Z"), fill = SolidColor(Color.White))
        addPath(addPathNodes("M4 3L9 3A1 1 0 0 1 10 4L10 9A1 1 0 0 1 9 10L4 10A1 1 0 0 1 3 9L3 4A1 1 0 0 1 4 3ZM15 3L20 3A1 1 0 0 1 21 4L21 9A1 1 0 0 1 20 10L15 10A1 1 0 0 1 14 9" +
            "L14 4A1 1 0 0 1 15 3ZM15 14L20 14A1 1 0 0 1 21 15L21 20A1 1 0 0 1 20 21L15 21A1 1 0 0 1 14 20L14 15A1 1 0 0 1 15 14ZM4 14L9 14A1 1 0 0 1 10 15L10 20" +
            "A1 1 0 0 1 9 21L4 21A1 1 0 0 1 3 20L3 15A1 1 0 0 1 4 14Z"), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
    val Star = icon("Favorite", "M11.525 2.295A0.53 0.53 0 0 1 12.475 2.295L14.785 6.974A2.123 2.123 0 0 0 16.38 8.134L21.546 8.89A0.53 0.53 0 0 1 21.84 9.794L18.104 13.432" +
            "A2.123 2.123 0 0 0 17.493 15.31L18.375 20.45A0.53 0.53 0 0 1 17.604 21.01L12.986 18.582A2.122 2.122 0 0 0 11.013 18.582L6.396 21.01" +
            "A0.53 0.53 0 0 1 5.626 20.45L6.507 15.311A2.122 2.122 0 0 0 5.896 13.432L2.16 9.795A0.53 0.53 0 0 1 2.454 8.889L7.619 8.134" +
            "A2.122 2.122 0 0 0 9.216 6.974Z")
    val FilledStar = ImageVector.Builder("Favorite", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M11.525 2.295A0.53 0.53 0 0 1 12.475 2.295L14.785 6.974A2.123 2.123 0 0 0 16.38 8.134L21.546 8.89A0.53 0.53 0 0 1 21.84 9.794L18.104 13.432" +
            "A2.123 2.123 0 0 0 17.493 15.31L18.375 20.45A0.53 0.53 0 0 1 17.604 21.01L12.986 18.582A2.122 2.122 0 0 0 11.013 18.582L6.396 21.01" +
            "A0.53 0.53 0 0 1 5.626 20.45L6.507 15.311A2.122 2.122 0 0 0 5.896 13.432L2.16 9.795A0.53 0.53 0 0 1 2.454 8.889L7.619 8.134" +
            "A2.122 2.122 0 0 0 9.216 6.974Z"), fill = SolidColor(Color.White))
        addPath(addPathNodes("M11.525 2.295A0.53 0.53 0 0 1 12.475 2.295L14.785 6.974A2.123 2.123 0 0 0 16.38 8.134L21.546 8.89A0.53 0.53 0 0 1 21.84 9.794L18.104 13.432" +
            "A2.123 2.123 0 0 0 17.493 15.31L18.375 20.45A0.53 0.53 0 0 1 17.604 21.01L12.986 18.582A2.122 2.122 0 0 0 11.013 18.582L6.396 21.01" +
            "A0.53 0.53 0 0 1 5.626 20.45L6.507 15.311A2.122 2.122 0 0 0 5.896 13.432L2.16 9.795A0.53 0.53 0 0 1 2.454 8.889L7.619 8.134" +
            "A2.122 2.122 0 0 0 9.216 6.974Z"), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
}
