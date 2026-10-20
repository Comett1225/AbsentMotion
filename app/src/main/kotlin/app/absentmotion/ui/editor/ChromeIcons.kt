package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.theme.Am

internal const val DimAlpha = 0.35f

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BarButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    enabled: Boolean = true,
    iconSize: DpSize = DpSize(24.dp, 24.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Box(
        modifier.alpha(if (enabled) 1f else DimAlpha).then(
            if (!enabled) Modifier else if (onLongClick == null) Modifier.clickable(onClick = onClick) else Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(iconSize), tint = Am.OnDark)
    }
}

internal object ChromeIcons {
    val Exit = icon("Exit", stroke = "M8 17L3 12L8 7M3 12L15 12M15 21L19 21A2 2 0 0 0 21 19L21 5A2 2 0 0 0 19 3L15 3")
    val Back = icon("Back", stroke = "M12 19L5 12L12 5M19 12L5 12")
    val Gear = icon("Gear", stroke = "M9.671 4.136A2.34 2.34 0 0 1 14.33 4.136A2.34 2.34 0 0 0 17.649 6.051A2.34 2.34 0 0 1 19.979 10.084A2.34 2.34 0 0 0 19.979 13.915" +
            "A2.34 2.34 0 0 1 17.649 17.948A2.34 2.34 0 0 0 14.33 19.863A2.34 2.34 0 0 1 9.671 19.863A2.34 2.34 0 0 0 6.351 17.948A2.34 2.34 0 0 1 4.021 13.915" +
            "A2.34 2.34 0 0 0 4.021 10.084A2.34 2.34 0 0 1 6.35 6.051A2.34 2.34 0 0 0 9.669 4.136M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z")
    val Share = icon("Share", stroke = "M21 13L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3L11 3M21 3L12 12M15 3L21 3L21 9")
    val Trash = icon("Trash", stroke = "M10 11L10 17M14 11L14 17M19 6L19 20A2 2 0 0 1 17 22L7 22A2 2 0 0 1 5 20L5 6M3 6L21 6M8 6L8 4A2 2 0 0 1 10 2L14 2A2 2 0 0 1 16 4L16 6")
    val More = icon("More", fill = "M5.5 12A1.5 1.5 0 1 0 8.5 12A1.5 1.5 0 1 0 5.5 12ZM10.5 12A1.5 1.5 0 1 0 13.5 12A1.5 1.5 0 1 0 10.5 12ZM15.5 12A1.5 1.5 0 1 0 18.5 12" +
            "A1.5 1.5 0 1 0 15.5 12Z", stroke = "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12Z")

    val Undo = icon("Undo", stroke = "M9 14L4 9L9 4M4 9L14.5 9A5.5 5.5 0 0 1 20 14.5A5.5 5.5 0 0 1 14.5 20L11 20")
    val Redo = icon("Redo", stroke = "M15 14L20 9L15 4M20 9L9.5 9A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20L13 20")
    val SkipBack = icon("SkipBack", stroke = "M3 19L3 5M13 6L7 12L13 18M7 12L21 12")
    val SkipForward = icon("SkipForward", stroke = "M17 12L3 12M11 18L17 12L11 6M21 5L21 19")
    val Play = icon("Play", fill = "M6.4 6.4A1.6 1.6 0 0 1 8.806 5.018L18.404 10.616A1.6 1.6 0 0 1 18.406 13.382L8.806 18.982A1.6 1.6 0 0 1 6.4 17.6Z", stroke = "M6.4 6.4A1.6 1.6 0 0 1 8.806 5.018L18.404 10.616A1.6 1.6 0 0 1 18.406 13.382L8.806 18.982A1.6 1.6 0 0 1 6.4 17.6Z", width = 1.2f)
    val PlayRepeat = icon("PlayRepeat", fill = "M10.3 9.4L10.3 14.6L14.7 12Z", stroke = "M17 2L21 6L17 10M3 11L3 10A4 4 0 0 1 7 6L21 6M7 22L3 18L7 14M21 13L21 14A4 4 0 0 1 17 18L3 18", width = 1.4f)
    val Pause = icon("Pause", fill = "M14.1 5.7L16.2 5.7A0.7 0.7 0 0 1 16.9 6.4L16.9 17.6A0.7 0.7 0 0 1 16.2 18.3L14.1 18.3A0.7 0.7 0 0 1 13.4 17.6L13.4 6.4A0.7 0.7 0 0 1 14.1 5.7ZM7.8 5.7" +
            "L9.9 5.7A0.7 0.7 0 0 1 10.6 6.4L10.6 17.6A0.7 0.7 0 0 1 9.9 18.3L7.8 18.3A0.7 0.7 0 0 1 7.1 17.6L7.1 6.4A0.7 0.7 0 0 1 7.8 5.7Z", stroke = "M14.1 5.7L16.2 5.7A0.7 0.7 0 0 1 16.9 6.4L16.9 17.6A0.7 0.7 0 0 1 16.2 18.3L14.1 18.3A0.7 0.7 0 0 1 13.4 17.6L13.4 6.4A0.7 0.7 0 0 1 14.1 5.7ZM7.8 5.7" +
            "L9.9 5.7A0.7 0.7 0 0 1 10.6 6.4L10.6 17.6A0.7 0.7 0 0 1 9.9 18.3L7.8 18.3A0.7 0.7 0 0 1 7.1 17.6L7.1 6.4A0.7 0.7 0 0 1 7.8 5.7Z", width = 1f)
    val Transition = icon("Transition", stroke = "M3 5L11 12L3 19ZM21 5L13 12L21 19Z")
    val CopyPaste = icon("CopyPaste", stroke = "M9 2L15 2A1 1 0 0 1 16 3L16 5A1 1 0 0 1 15 6L9 6A1 1 0 0 1 8 5L8 3A1 1 0 0 1 9 2ZM8 4L6 4A2 2 0 0 0 4 6L4 20A2 2 0 0 0 6 22L18 22A2 2 0 0 0 20 20" +
            "L20 18M16 4L18 4A2 2 0 0 1 20 6L20 10M21 14L11 14M15 10L11 14L15 18")

    val PreviewMode = icon("PreviewMode", h = 36f, fill = "M9.7 4.3L14.3 4.3L12 1.2Z", stroke = PREVIEW_FRAME)
    val PreviewModeOpen = icon("PreviewModeOpen", h = 36f, fill = "M9.7 1.2L14.3 1.2L12 4.3Z", stroke = PREVIEW_FRAME)
    val PreviewCamera = icon("PreviewCamera", w = 20f, h = 37f, fill = "M12.4 18.6L15.534 20.689A0.3 0.3 0 0 0 16 20.44L16 15.522A0.3 0.3 0 0 0 15.549 15.263L12.4 17.1M5.2 14.4L11.2 14.4A1.2 1.2 0 0 1 12.4 15.6L12.4 20.4" +
            "A1.2 1.2 0 0 1 11.2 21.6L5.2 21.6A1.2 1.2 0 0 1 4 20.4L4 15.6A1.2 1.2 0 0 1 5.2 14.4Z", stroke = "M12.4 18.6L15.534 20.689A0.3 0.3 0 0 0 16 20.44L16 15.522A0.3 0.3 0 0 0 15.549 15.263L12.4 17.1M5.2 14.4L11.2 14.4A1.2 1.2 0 0 1 12.4 15.6L12.4 20.4" +
            "A1.2 1.2 0 0 1 11.2 21.6L5.2 21.6A1.2 1.2 0 0 1 4 20.4L4 15.6A1.2 1.2 0 0 1 5.2 14.4Z", width = 1.2f)

    val Plus = icon("Plus", stroke = "M3.95 12L20.05 12M12 3.95L12 20.05", width = 2.5f)

    val ShapeTab = icon("ShapeTab", stroke = "M8.3 10A0.7 0.7 0 0 1 7.674 8.921L11.4 3A0.7 0.7 0 0 1 12.598 2.957L16.3 8.9A0.7 0.7 0 0 1 15.728 10ZM4 14L9 14A1 1 0 0 1 10 15L10 20A1 1 0 0 1 9 21" +
            "L4 21A1 1 0 0 1 3 20L3 15A1 1 0 0 1 4 14ZM14 17.5A3.5 3.5 0 1 0 21 17.5A3.5 3.5 0 1 0 14 17.5Z")
    val MediaTab = icon("MediaTab", stroke = "M15 15.003A1 1 0 0 1 16.517 14.144L21.514 17.141A1 1 0 0 1 21.514 18.859L16.517 21.856A1 1 0 0 1 15 20.996ZM21 12.17L21 5A2 2 0 0 0 19 3L5 3" +
            "A2 2 0 0 0 3 5L3 19A2 2 0 0 0 5 21L11 21M6 21L11 16M7 9A2 2 0 1 0 11 9A2 2 0 1 0 7 9Z")
    val AudioTab = icon("AudioTab", stroke = "M21 16L21 3A1 1 0 0 0 19.72 2.04L9.72 4.957A1 1 0 0 0 9 5.917L9 19M15 16A3 3 0 1 0 21 16A3 3 0 1 0 15 16ZM3 19A3 3 0 1 0 9 19A3 3 0 1 0 3 19Z")
    val ObjectTab = icon("ObjectTab", stroke = "M21 8A2 2 0 0 0 20 6.27L13 2.27A2 2 0 0 0 11 2.27L4 6.27A2 2 0 0 0 3 8L3 16A2 2 0 0 0 4 17.73L11 21.73A2 2 0 0 0 13 21.73L20 17.73A2 2 0 0 0 21 16Z" +
            "M3.3 7L12 12L20.7 7M12 22L12 12")
    val TemplateTab = icon("TemplateTab", stroke = "M4 3L20 3A1 1 0 0 1 21 4L21 9A1 1 0 0 1 20 10L4 10A1 1 0 0 1 3 9L3 4A1 1 0 0 1 4 3ZM4 14L11 14A1 1 0 0 1 12 15L12 20A1 1 0 0 1 11 21L4 21" +
            "A1 1 0 0 1 3 20L3 15A1 1 0 0 1 4 14ZM17 14L20 14A1 1 0 0 1 21 15L21 20A1 1 0 0 1 20 21L17 21A1 1 0 0 1 16 20L16 15A1 1 0 0 1 17 14Z")

    val Freehand = icon("Freehand", stroke = "M13 21L21 21M15 5L19 9M21.174 6.812A1 1 0 0 0 17.188 2.825L3.842 16.174A2 2 0 0 0 3.342 17.004L2.021 21.356A0.5 0.5 0 0 0 2.644 21.978L6.997 20.658" +
            "A2 2 0 0 0 7.827 20.161Z")
    val VectorDrawing = icon("VectorDrawing", stroke = "M18 13L17.346 8.264A4 4 0 0 0 14.59 4.998L5.427 2.098A2 2 0 0 0 3.41 2.591L2.59 3.41A2 2 0 0 0 2.098 5.427L4.998 14.59A4 4 0 0 0 8.264 17.346L13 18" +
            "M18.648 12.352A1.205 1.205 0 0 1 20.352 12.352L21.648 13.648A1.205 1.205 0 0 1 21.648 15.352L15.352 21.648A1.205 1.205 0 0 1 13.648 21.648" +
            "L12.352 20.352A1.205 1.205 0 0 1 12.352 18.648ZM3 3L9.586 9.586M9 11A2 2 0 1 0 13 11A2 2 0 1 0 9 11Z")
    val TextTool = icon("TextTool", stroke = "M12 4L12 20M4 7L4 5A1 1 0 0 1 5 4L19 4A1 1 0 0 1 20 5L20 7M9 20L15 20")
    val Captions = icon("Captions", stroke = "M22 17A2 2 0 0 1 20 19L6.828 19A2 2 0 0 0 5.414 19.586L3.212 21.788A0.71 0.71 0 0 1 2 21.286L2 5A2 2 0 0 1 4 3L20 3A2 2 0 0 1 22 5ZM7 11L17 11M7 15" +
            "L13 15M7 7L15 7")
    val CloseThick = icon("CloseThick", stroke = "M17.4 6.6L6.6 17.4M6.6 6.6L17.4 17.4", width = 2.2f)

    val Close = icon("Close", stroke = "M18.9 5.1L5.1 18.9M5.1 5.1L18.9 18.9")
    val ChevronDown = icon("ChevronDown", stroke = "M6 9L12 15L18 9")
    val Pencil = icon("Pencil", stroke = "M21.174 6.812A1 1 0 0 0 17.188 2.825L3.842 16.174A2 2 0 0 0 3.342 17.004L2.021 21.356A0.5 0.5 0 0 0 2.644 21.978L6.997 20.658A2 2 0 0 0 7.827 20.161Z" +
            "M15 5L19 9")

    val SelectAll = icon("SelectAll", stroke = "M13 5L21 5M13 12L21 12M13 19L21 19M3 17L5 19L9 15M3 7L5 9L9 5")
    val Duplicate = icon("Duplicate", stroke = "M15 12L15 18M12 15L18 15M10 8L20 8A2 2 0 0 1 22 10L22 20A2 2 0 0 1 20 22L10 22A2 2 0 0 1 8 20L8 10A2 2 0 0 1 10 8ZM4 16C2.9 16 2 15.1 2 14L2 4" +
            "C2 2.9 2.9 2 4 2L14 2C15.1 2 16 2.9 16 4")
    val SavePreset = icon("SavePreset", stroke = "M15.2 3A2 2 0 0 1 16.6 3.6L20.4 7.4A2 2 0 0 1 21 8.8L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM17 21L17 14A1 1 0 0 0 16 13L8 13" +
            "A1 1 0 0 0 7 14L7 21M7 3L7 7A1 1 0 0 0 8 8L15 8")
    val Copy = icon("Copy", stroke = "M10 8L20 8A2 2 0 0 1 22 10L22 20A2 2 0 0 1 20 22L10 22A2 2 0 0 1 8 20L8 10A2 2 0 0 1 10 8ZM4 16C2.9 16 2 15.1 2 14L2 4C2 2.9 2.9 2 4 2L14 2" +
            "C15.1 2 16 2.9 16 4")
    val Paste = icon("Paste", stroke = "M11 14L21 14M16 4L18 4A2 2 0 0 1 20 6L20 7.344M17 18L21 14L17 10M8 4L6 4A2 2 0 0 0 4 6L4 20A2 2 0 0 0 6 22L18 22A2 2 0 0 0 19.793 20.887M9 2L15 2" +
            "A1 1 0 0 1 16 3L16 5A1 1 0 0 1 15 6L9 6A1 1 0 0 1 8 5L8 3A1 1 0 0 1 9 2Z")
    val PasteStyle = icon("PasteStyle", stroke = "M14.622 17.897L3.942 14.984M18.376 2.622A1 1 0 1 1 21.378 5.624L17.36 9.643A0.5 0.5 0 0 0 17.36 10.35L18.304 11.294A2.41 2.41 0 0 1 18.304 14.702" +
            "L17.36 15.646A0.5 0.5 0 0 1 16.653 15.646L8.354 7.348A0.5 0.5 0 0 1 8.354 6.641L9.298 5.697A2.41 2.41 0 0 1 12.706 5.697L13.65 6.641" +
            "A0.5 0.5 0 0 0 14.357 6.641ZM9 8C7.196 10.71 5.03 11.46 2.417 11.948A0.507 0.507 0 0 0 2.115 12.767L9.435 21.65A1 1 0 0 0 10.62 21.854" +
            "C12.735 20.405 16 16.792 16 15")
    val ChevronRight = icon("ChevronRight", stroke = "M9 18L15 12L9 6")
}

private val PREVIEW_FRAME = "M3 13L3 11A2 2 0 0 1 5 9L7 9M17 9L19 9A2 2 0 0 1 21 11L21 13M21 23L21 25A2 2 0 0 1 19 27L17 27M7 27L5 27A2 2 0 0 1 3 25L3 23M9 14.6L15 14.6" +
            "A1.5 1.5 0 0 1 16.5 16.1L16.5 19.9A1.5 1.5 0 0 1 15 21.4L9 21.4A1.5 1.5 0 0 1 7.5 19.9L7.5 16.1A1.5 1.5 0 0 1 9 14.6Z"

private fun icon(name: String, stroke: String? = null, fill: String? = null, width: Float = 1.6f, w: Float = 24f, h: Float = 24f): ImageVector =
    ImageVector.Builder(name, w.dp, h.dp, w, h).apply {
        if (fill != null) addPath(addPathNodes(fill), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.White))
        if (stroke != null) {
            addPath(
                addPathNodes(stroke), stroke = SolidColor(Color.White), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

@Composable
internal fun LayerParentIcon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp, 42.dp)) {
        val d = density
        val line = 1.6f * d
        drawRoundRect(
            Color.White, Offset(9f * d, 11.5f * d), Size(14f * d, 14f * d), CornerRadius(2.5f * d),
            style = Stroke(line, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.6f * d, 2.6f * d))),
        )
        drawRoundRect(Am.TopBar, Offset(16.5f * d, 18.5f * d), Size(13.5f * d, 13.5f * d), CornerRadius(2.5f * d))
        drawRoundRect(Color.White, Offset(16.5f * d, 18.5f * d), Size(13.5f * d, 13.5f * d), CornerRadius(2.5f * d), style = Stroke(line))
        val centre = Offset(32.5f * d, 11.5f * d)
        drawCircle(Am.TopBar, 6f * d, centre)
        drawCircle(Am.Accent, 6f * d, centre, style = Stroke(line))
        drawCircle(Am.Accent, 2.2f * d, centre)
    }
}
