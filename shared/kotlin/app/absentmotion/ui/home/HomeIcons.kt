package app.absentmotion.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object HomeIcons {
    val Menu = icon("Menu", stroke = "M3.2 4.3L20.8 4.3M3.2 12L20.8 12M3.2 19.7L20.8 19.7", width = 1.8f)
    val ChevronDown = icon("ChevronDown", stroke = "M5.1 8.55L12 15.45L18.9 8.55", width = 1.8f)
    val ChevronRight = icon("ChevronRight", stroke = "M9 18L15 12L9 6", mirror = true)
    val Close = icon("Close", stroke = "M19.2 4.8L4.8 19.2M4.8 4.8L19.2 19.2", width = 1.8f)
    val Discord = icon("Discord", fill = "M14.983 3l.123 .006c2.014 .214 3.527 .672 4.966 1.673a1 1 0 0 1 .371 .488c1.876 5.315 2.373 9.987 1.451 12.28c-1.003 2.005 -2.606 3.553 -4.394 3.553c-.732 0 -1.693 -.968 -2.328 -2.045a21.512 21.512 0 0 0 2.103 -.493a1 1 0 1 0 -.55 -1.924c-3.32 .95 -6.13 .95 -9.45 0a1 1 0 0 0 -.55 1.924c.717 .204 1.416 .37 2.103 .494c-.635 1.075 -1.596 2.044 -2.328 2.044c-1.788 0 -3.391 -1.548 -4.428 -3.629c-.888 -2.217 -.39 -6.89 1.485 -12.204a1 1 0 0 1 .371 -.488c1.439 -1.001 2.952 -1.459 4.966 -1.673a1 1 0 0 1 .935 .435l.063 .107l.651 1.285l.137 -.016a12.97 12.97 0 0 1 2.643 0l.134 .016l.65 -1.284a1 1 0 0 1 .754 -.54l.122 -.009zm-5.983 7a2 2 0 0 0 -1.977 1.697l-.018 .154l-.005 .149l.005 .15a2 2 0 1 0 1.995 -2.15zm6 0a2 2 0 0 0 -1.977 1.697l-.018 .154l-.005 .149l.005 .15a2 2 0 1 0 1.995 -2.15z")
    val Plus = icon("Plus", stroke = "M1.85 12L22.15 12M12 1.85L12 22.15", width = 1.8f)
    val ClearCircle = icon("ClearCircle", stroke = "M4.2 12A7.8 7.8 0 1 0 19.8 12A7.8 7.8 0 1 0 4.2 12ZM14.34 9.66L9.66 14.34M9.66 9.66L14.34 14.34", width = 1.4f)
    val More = icon("More", stroke = "M10.9 12A1.1 1.1 0 1 0 13.1 12A1.1 1.1 0 1 0 10.9 12ZM18.6 12A1.1 1.1 0 1 0 20.8 12A1.1 1.1 0 1 0 18.6 12ZM3.2 12A1.1 1.1 0 1 0 5.4 12" +
            "A1.1 1.1 0 1 0 3.2 12Z", width = 3.4f)
    val Pencil = icon("Pencil", stroke = "M21.174 6.812A1 1 0 0 0 17.188 2.825L3.842 16.174A2 2 0 0 0 3.342 17.004L2.021 21.356A0.5 0.5 0 0 0 2.644 21.978L6.997 20.658A2 2 0 0 0 7.827 20.161Z" +
            "M15 5L19 9")
    val Upload = icon("Upload", stroke = "M6 22A2 2 0 0 1 4 20L4 4A2 2 0 0 1 6 2L14 2A2.4 2.4 0 0 1 15.704 2.706L19.292 6.294A2.4 2.4 0 0 1 20 8L20 20A2 2 0 0 1 18 22ZM14 2L14 7A1 1 0 0 0 15 8" +
            "L20 8M12 12L12 18M15 15L12 12L9 15")
    val Link = icon("Link", stroke = "M9 17L7 17A5 5 0 0 1 7 7L9 7M15 7L17 7A5 5 0 1 1 17 17L15 17M8 12L16 12")
    val ArrowDown = icon("ArrowDown", stroke = "M12 5L12 19M19 12L12 19L5 12", width = 1f)

    val Aspect = icon("Aspect", stroke = "M15 15L21 21M15 9L21 3M21 16L21 21L16 21M21 8L21 3L16 3M3 16L3 21L8 21M3 21L9 15M3 8L3 3L8 3M9 9L3 3")
    val Resolution = icon("Resolution", stroke = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM3 9L21 9M3 15L21 15M9 3L9 21M15 3L15 21")
    val Folder = icon("Folder", stroke = "M20 20A2 2 0 0 0 22 18L22 8A2 2 0 0 0 20 6L12.1 6A2 2 0 0 1 10.41 5.1L9.6 3.9A2 2 0 0 0 7.93 3L4 3A2 2 0 0 0 2 5L2 18A2 2 0 0 0 4 20Z")
    val FrameRate = icon("FrameRate", stroke = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM7 3L7 21M3 7.5L7 7.5M3 12L21 12M3 16.5L7 16.5M17 3L17 21M17 7.5" +
            "L21 7.5M17 16.5L21 16.5")

    val Select = icon("Select", stroke = "M22 14A8 8 0 0 1 14 22M18 11L18 10A2 2 0 0 0 16 8A2 2 0 0 0 14 10M14 10L14 9A2 2 0 0 0 12 7A2 2 0 0 0 10 9L10 10M10 9.5L10 4A2 2 0 0 0 8 2" +
            "A2 2 0 0 0 6 4L6 14M18 11A2 2 0 1 1 22 11L22 14A8 8 0 0 1 14 22L12 22C9.2 22 7.5 21.14 6.01 19.66L2.41 16.06A2 2 0 0 1 5.24 13.24L7 15")
    val Export = icon("Export", stroke = "M12 2L12 15M16 6L12 2L8 6M4 12L4 20A2 2 0 0 0 6 22L18 22A2 2 0 0 0 20 20L20 12")
    val Duplicate = icon("Duplicate", stroke = "M10 8L20 8A2 2 0 0 1 22 10L22 20A2 2 0 0 1 20 22L10 22A2 2 0 0 1 8 20L8 10A2 2 0 0 1 10 8ZM4 16C2.9 16 2 15.1 2 14L2 4C2 2.9 2.9 2 4 2L14 2" +
            "C15.1 2 16 2.9 16 4")
    val Delete = icon("Delete", stroke = "M10 11L10 17M14 11L14 17M19 6L19 20A2 2 0 0 1 17 22L7 22A2 2 0 0 1 5 20L5 6M3 6L21 6M8 6L8 4A2 2 0 0 1 10 2L14 2A2 2 0 0 1 16 4L16 6")

    val Replace = icon("Replace", stroke = "M16 3L20 7L16 11M20 7L4 7M8 21L4 17L8 13M4 17L20 17")
    val CropFill = icon("CropFill", stroke = "M5 5.5L19 5.5A2 2 0 0 1 21 7.5L21 16.5A2 2 0 0 1 19 18.5L5 18.5A2 2 0 0 1 3 16.5L3 7.5A2 2 0 0 1 5 5.5Z", fill = "M5 5.5L19 5.5A2 2 0 0 1 21 7.5L21 16.5A2 2 0 0 1 19 18.5L5 18.5A2 2 0 0 1 3 16.5L3 7.5A2 2 0 0 1 5 5.5Z")
    val CropFit = icon("CropFit", stroke = "M5 5.5L19 5.5A2 2 0 0 1 21 7.5L21 16.5A2 2 0 0 1 19 18.5L5 18.5A2 2 0 0 1 3 16.5L3 7.5A2 2 0 0 1 5 5.5Z", fill = "M9 9L15 9A1 1 0 0 1 16 10L16 14A1 1 0 0 1 15 15L9 15A1 1 0 0 1 8 14L8 10A1 1 0 0 1 9 9Z")
    val CropStretch = icon("CropStretch", stroke = "M5 5.5L19 5.5A2 2 0 0 1 21 7.5L21 16.5A2 2 0 0 1 19 18.5L5 18.5A2 2 0 0 1 3 16.5L3 7.5A2 2 0 0 1 5 5.5ZM15.72 9.52L18.2 12L15.72 14.48M5.8 12L18.2 12" +
            "M8.28 9.52L5.8 12L8.28 14.48")

    val ProjectsTab = icon("ProjectsTab", fill = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM9 9.003A1 1 0 0 1 10.517 8.144L15.514 11.141" +
            "A1 1 0 0 1 15.514 12.859L10.517 15.856A1 1 0 0 1 9 14.996Z", width = 1.8f)
    val ProjectsTabOff = icon("ProjectsTabOff", stroke = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM9 9.003A1 1 0 0 1 10.517 8.144L15.514 11.141" +
            "A1 1 0 0 1 15.514 12.859L10.517 15.856A1 1 0 0 1 9 14.996Z", width = 1.8f)
    val TemplatesTabOn = icon("TemplatesTabOn", stroke = "M4 3L20 3A1 1 0 0 1 21 4L21 9A1 1 0 0 1 20 10L4 10A1 1 0 0 1 3 9L3 4A1 1 0 0 1 4 3ZM4 14L11 14A1 1 0 0 1 12 15L12 20A1 1 0 0 1 11 21L4 21" +
            "A1 1 0 0 1 3 20L3 15A1 1 0 0 1 4 14ZM17 14L20 14A1 1 0 0 1 21 15L21 20A1 1 0 0 1 20 21L17 21A1 1 0 0 1 16 20L16 15A1 1 0 0 1 17 14Z", fill = "M4 3L20 3A1 1 0 0 1 21 4L21 9A1 1 0 0 1 20 10L4 10A1 1 0 0 1 3 9L3 4A1 1 0 0 1 4 3Z", width = 1.8f)
    val ToolsTab = icon("ToolsTab", stroke = WRENCH, width = 1.8f)
    val ToolsTabOn = icon("ToolsTabOn", stroke = WRENCH, fill = WRENCH, width = 1.8f)
    val Compress = icon("Compress", stroke = "M15 15L21 21M15 15L15 19.8M15 15L19.8 15M9 19.8L9 15M9 15L4.2 15M9 15L3 21M15 4.2L15 9M15 9L19.8 9M15 9L21 3M9 4.2L9 9M9 9L4.2 9" +
            "M9 9L3 3", width = 1.8f)
    val Audio = icon("Audio", stroke = "M9 18L9 5L21 3L21 16M3 18A3 3 0 1 0 9 18A3 3 0 1 0 3 18ZM15 16A3 3 0 1 0 21 16A3 3 0 1 0 15 16Z", width = 1.8f)
    val Gif = icon("Gif", stroke = "M17 2L21 6L17 10M3 11L3 10A4 4 0 0 1 7 6L21 6M7 22L3 18L7 14M21 13L21 14A4 4 0 0 1 17 18L3 18", width = 1.8f)
    val Trim = icon("Trim", stroke = "M3 6A3 3 0 1 0 9 6A3 3 0 1 0 3 6ZM3 18A3 3 0 1 0 9 18A3 3 0 1 0 3 18ZM20 4L8.12 15.88M14.47 14.48L20 20M8.12 8.12L12 12", width = 1.8f)
    val Download = icon("Download", stroke = "M12 15L12 3M21 15L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 15M7 10L12 15L17 10", width = 1.8f)
    val TemplatesTab = icon("TemplatesTab", stroke ="M4 3L20 3A1 1 0 0 1 21 4L21 9A1 1 0 0 1 20 10L4 10A1 1 0 0 1 3 9L3 4A1 1 0 0 1 4 3ZM4 14L11 14A1 1 0 0 1 12 15L12 20A1 1 0 0 1 11 21L4 21" +
            "A1 1 0 0 1 3 20L3 15A1 1 0 0 1 4 14ZM17 14L20 14A1 1 0 0 1 21 15L21 20A1 1 0 0 1 20 21L17 21A1 1 0 0 1 16 20L16 15A1 1 0 0 1 17 14Z", width = 1.8f)
}

private const val WRENCH = "M14.7 6.3A1 1 0 0 0 14.7 7.7L16.3 9.3A1 1 0 0 0 17.7 9.3L20.806 6.195C21.126 5.873 21.669 5.975 21.789 6.413A6 6 0 0 1 13.53 13.47L5.62 21.38" +
    "A1 1 0 0 1 2.621 18.38L10.531 10.47A6 6 0 0 1 17.588 2.211C18.026 2.331 18.128 2.873 17.807 3.195Z"

private fun icon(name: String, stroke: String? = null, fill: String? = null, width: Float = 1.6f, mirror: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
        if (fill != null) {
            addPath(addPathNodes(fill), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        }
        if (stroke != null) {
            addPath(
                addPathNodes(stroke), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
