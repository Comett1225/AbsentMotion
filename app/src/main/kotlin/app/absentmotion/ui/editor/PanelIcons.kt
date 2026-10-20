package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object PanelIcons {
    val Speed = icon("Speed", width = 1.7f, stroke = "M12 14L16 10M3.34 19A10 10 0 1 1 20.66 19")
    val Animate = icon("Animate", width = 1.7f, stroke = "M9 12A6 6 0 1 0 21 12A6 6 0 1 0 9 12ZM2 8L6 8M3 12L6 12M2 16L6 16")
    val TrimLeft = icon("TrimLeft", width = 1.7f, stroke = "M12 2.5L12 21.5M13.5 7.5L19.5 7.5A1.5 1.5 0 0 1 21 9L21 15A1.5 1.5 0 0 1 19.5 16.5L13.5 16.5A1.5 1.5 0 0 1 12 15L12 9A1.5 1.5 0 0 1 13.5 7.5ZM3.5 7.5" +
            "L5.3 7.5M6.8 7.5L8.6 7.5M3.5 16.5L5.3 16.5M6.8 16.5L8.6 16.5")
    val Split = icon("Split", width = 1.7f, stroke = "M3 6A3 3 0 1 0 9 6A3 3 0 1 0 3 6ZM8.12 8.12L12 12M20 4L8.12 15.88M3 18A3 3 0 1 0 9 18A3 3 0 1 0 3 18ZM14.8 14.8L20 20")
    val TrimRight = icon("TrimRight", width = 1.7f, stroke = "M12 2.5L12 21.5M10.5 7.5L4.5 7.5A1.5 1.5 0 0 0 3 9L3 15A1.5 1.5 0 0 0 4.5 16.5L10.5 16.5A1.5 1.5 0 0 0 12 15L12 9A1.5 1.5 0 0 0 10.5 7.5ZM20.5 7.5" +
            "L18.7 7.5M17.2 7.5L15.4 7.5M20.5 16.5L18.7 16.5M17.2 16.5L15.4 16.5")
    val AudioMuted = icon("AudioMuted", width = 1.7f, stroke = "M11 4.702A0.7 0.7 0 0 0 9.797 4.204L6.413 7.587A1.4 1.4 0 0 1 5.416 8L3 8A1 1 0 0 0 2 9L2 15A1 1 0 0 0 3 16L5.416 16A1.4 1.4 0 0 1 6.413 16.413" +
            "L9.796 19.797A0.7 0.7 0 0 0 11 19.298ZM16.5 14.5L21.5 9.5M16.5 9.5L21.5 14.5")

    val PaintBucket = icon("PaintBucket", width = 1.7f, stroke = "M11 7L6 2M18.992 12L2.041 12M21.145 18.38A3.34 3.34 0 0 1 20 16.5A3.3 3.3 0 0 1 18.855 18.38C18.28 18.84 18 19.4 18 19.975A2 2 0 0 0 20 22" +
            "A2 2 0 0 0 22 19.975C22 19.395 21.715 18.845 21.145 18.38M8.5 4.5L10.648 2.352A1.205 1.205 0 0 1 12.352 2.352L19.648 9.648" +
            "A1.205 1.205 0 0 1 19.648 11.352L12.056 18.944A3.615 3.615 0 0 1 6.944 18.944L3.056 15.056A3.615 3.615 0 0 1 3.056 9.944L5.67 7.33")
    val BorderShadow = icon("BorderShadow", width = 1.7f, stroke = "M6.5 3.5L13 3.5A3 3 0 0 1 16 6.5L16 13A3 3 0 0 1 13 16L6.5 16A3 3 0 0 1 3.5 13L3.5 6.5A3 3 0 0 1 6.5 3.5ZM20 8.5L20 16.5A3.5 3.5 0 0 1 16.5 20L8.5 20")
    val Blending = icon("Blending", width = 1.7f, stroke = "M8 9A7 7 0 1 0 22 9A7 7 0 1 0 8 9ZM2 15A7 7 0 1 0 16 15A7 7 0 1 0 2 15Z")
    val MoveTransform = icon("MoveTransform", width = 1.7f, stroke = "M5 3L5 19L21 19M5 19L11 13M2 6L5 3L8 6M18 16L21 19L18 22")
    val EditShape = icon("EditShape", width = 1.7f, stroke = "M17 5A2 2 0 1 0 21 5A2 2 0 1 0 17 5ZM3 19A2 2 0 1 0 7 19A2 2 0 1 0 3 19ZM5 17A12 12 0 0 1 17 5")
    val Star = icon("Star", width = 1.7f, stroke = "M11.525 2.295A0.53 0.53 0 0 1 12.475 2.295L14.785 6.974A2.123 2.123 0 0 0 16.38 8.134L21.546 8.89A0.53 0.53 0 0 1 21.84 9.794L18.104 13.432" +
            "A2.123 2.123 0 0 0 17.493 15.31L18.375 20.45A0.53 0.53 0 0 1 17.604 21.01L12.986 18.582A2.122 2.122 0 0 0 11.013 18.582L6.396 21.01" +
            "A0.53 0.53 0 0 1 5.626 20.45L6.507 15.311A2.122 2.122 0 0 0 5.896 13.432L2.16 9.795A0.53 0.53 0 0 1 2.454 8.889L7.619 8.134" +
            "A2.122 2.122 0 0 0 9.216 6.974Z")
    val Effects = icon("Effects", width = 1.8f, stroke = "M11.017 2.814A1 1 0 0 1 12.983 2.814L14.034 8.372A2 2 0 0 0 15.628 9.966L21.186 11.017A1 1 0 0 1 21.186 12.983L15.628 14.034A2 2 0 0 0 14.034 15.628" +
            "L12.983 21.186A1 1 0 0 1 11.017 21.186L9.966 15.628A2 2 0 0 0 8.372 14.034L2.814 12.983A1 1 0 0 1 2.814 11.017L8.372 9.966A2 2 0 0 0 9.966 8.372ZM20 2" +
            "L20 6M22 4L18 4M2 20A2 2 0 1 0 6 20A2 2 0 1 0 2 20Z")

    val Turtle = icon("Turtle", stroke = "M12 10L14 14L14 17A1 1 0 0 0 15 18L17 18A1 1 0 0 0 18 17L18 14A8 8 0 1 0 2 14L2 17A1 1 0 0 0 3 18L5 18A1 1 0 0 0 6 17L6 14L8 10L12 10ZM4.82 7.9L8 10M15.18 7.9L12 10M16.93 10L20 10A2 2 0 0 1 20 14L2 14")
    val Rabbit = icon("Rabbit", stroke = "M13 16A3 3 0 0 1 15.24 21M18 12L18.01 12M18 21L10 21A4 4 0 0 1 6 17A7 7 0 0 1 13 10L13.2 10L9.6 6.4A1 1 0 1 1 12.4 3.6L15.8 7L16 7C19.3 7 22 9.7 22 13L22 14A2 2 0 0 1 20 16L19 16A3 3 0 0 0 16 19" +
            "M20 8.54L20 4A2 2 0 1 0 16 4L16 7M7.612 12.524A3 3 0 1 0 6.012 16.824")

    val Camera = icon("Camera", width = 1.7f, stroke = "M16 13L21.223 16.482A0.5 0.5 0 0 0 22 16.066L22 7.87A0.5 0.5 0 0 0 21.248 7.438L16 10.5M4 6L14 6A2 2 0 0 1 16 8L16 16A2 2 0 0 1 14 18L4 18" +
            "A2 2 0 0 1 2 16L2 8A2 2 0 0 1 4 6Z")

    val Back = icon("Back", stroke = "M12 19L5 12L12 5M19 12L5 12")
    val TabStroke = icon("TabStroke", stroke = "M3 19A3 3 0 1 0 9 19A3 3 0 1 0 3 19ZM9 19L17.5 19A3.5 3.5 0 0 0 17.5 12L6.5 12A3.5 3.5 0 0 1 6.5 5L15 5M15 5A3 3 0 1 0 21 5A3 3 0 1 0 15 5Z")
    val TabBorder = icon("TabBorder", stroke = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3Z")
    val Keyframe = icon("Keyframe", fill = "M12 3.2L18.6 12L12 20.8L5.4 12ZM11.1 8.8L12.9 8.8L12.9 11.1L15.2 11.1L15.2 12.9L12.9 12.9L12.9 15.2L11.1 15.2L11.1 12.9L8.8 12.9L8.8 11.1L11.1 11.1Z")
    val KeyframeRemove = icon("KeyframeRemove", fill = "M12 3.2L18.6 12L12 20.8L5.4 12ZM8.8 11.1L15.2 11.1L15.2 12.9L8.8 12.9Z")
    val Easing = icon("Easing", width = 1.5f, stroke = "M4.5 3.5L4.5 19.5L20.5 19.5M7.5 16.2C13.2 16.2 10.8 7.8 16.5 7.8", fill = "M5.8 16.2A1.7 1.7 0 1 0 9.2 16.2A1.7 1.7 0 1 0 5.8 16.2ZM14.8 7.8A1.7 1.7 0 1 0 18.2 7.8A1.7 1.7 0 1 0 14.8 7.8Z")
    val More = icon("More", width = 2.4f, stroke = "M11 12A1 1 0 1 0 13 12A1 1 0 1 0 11 12ZM18 12A1 1 0 1 0 20 12A1 1 0 1 0 18 12ZM4 12A1 1 0 1 0 6 12A1 1 0 1 0 4 12Z")
    val TabMove = icon("TabMove", stroke = "M12 2L12 22M15 19L12 22L9 19M19 9L22 12L19 15M2 12L22 12M5 9L2 12L5 15M9 5L12 2L15 5")
    val TabRotate = icon("TabRotate", stroke = "M21 12A9 9 0 1 1 12 3C14.52 3 16.93 4 18.74 5.74L21 8M21 3L21 8L16 8")
    val TabTilt = icon("TabTilt", stroke = "M8 3.5L17 6.5L17 14.5L8 17.5ZM3.5 18.5C6.5 21.5 17.5 21.5 20.5 18.5M20.5 18.5L17.6 18.4M20.5 18.5L20.2 15.7")
    val TabSize = icon("TabSize", stroke = "M12 3L5 3A2 2 0 0 0 3 5L3 19A2 2 0 0 0 5 21L19 21A2 2 0 0 0 21 19L21 12M14 15L9 15L9 10M16 3L21 3L21 8M21 3L9 15")
    val TabSkew = icon("TabSkew", stroke = "M9.5 6.5L20.5 6.5L14.5 17.5L3.5 17.5ZM3.5 6.5L3.5 8.1M3.5 9.5L3.5 11.1M3.5 12.5L3.5 14.1M3.5 15.5L3.5 17.1")
    val Link = icon("Link", stroke = "M9 17L7 17A5 5 0 0 1 7 7L9 7M15 7L17 7A5 5 0 1 1 17 17L15 17M8 12L16 12")
    val Unlink = icon("Unlink", stroke = "M9 17L7 17A5 5 0 0 1 7 7M15 7L17 7A5 5 0 0 1 21 15M8 12L12 12M2 2L22 22")

    val FillNone = icon("FillNone", stroke = "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM4.929 4.929L19.07 19.071")
    val FillGradient = icon("FillGradient", stroke = "M5.5 5.5L18.5 5.5A2 2 0 0 1 20.5 7.5L20.5 16.5A2 2 0 0 1 18.5 18.5L5.5 18.5A2 2 0 0 1 3.5 16.5L3.5 7.5A2 2 0 0 1 5.5 5.5ZM7 9L7 15M10 9L10 15M13.6 9" +
            "L13.6 15M17.6 9L17.6 15")
    val FillMedia = icon("FillMedia", stroke = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM7 9A2 2 0 1 0 11 9A2 2 0 1 0 7 9ZM21 15L17.914 11.914" +
            "A2 2 0 0 0 15.086 11.914L6 21")
    val AddColor = icon("AddColor", stroke = "M5 3L19 3A2 2 0 0 1 21 5L21 19A2 2 0 0 1 19 21L5 21A2 2 0 0 1 3 19L3 5A2 2 0 0 1 5 3ZM8 12L16 12M12 8L12 16")
    val Eyedropper = icon("Eyedropper", stroke = "M12 9L3.586 17.414A2 2 0 0 0 3 18.828L3 20.172A2 2 0 0 1 2.414 21.586A2 2 0 0 1 3.828 21L5.172 21A2 2 0 0 0 6.586 20.414L15 12M18 9L18.4 9.4" +
            "A1 1 0 1 1 15.4 12.4L11.6 8.6A1 1 0 1 1 14.6 5.6L15 6L18.4 2.6A1 1 0 1 1 21.4 5.6ZM2 22L2.414 21.586")
    val Palette = icon("Palette", stroke = "M12 22A1 1 0 0 1 12 2A10 9 0 0 1 22 11A5 5 0 0 1 17 16L14.75 16A1.75 1.75 0 0 0 13.35 18.8L13.65 19.2A1.75 1.75 0 0 1 12.25 22ZM13 6.5" +
            "A0.5 0.5 0 1 0 14 6.5A0.5 0.5 0 1 0 13 6.5ZM17 10.5A0.5 0.5 0 1 0 18 10.5A0.5 0.5 0 1 0 17 10.5ZM6 12.5A0.5 0.5 0 1 0 7 12.5A0.5 0.5 0 1 0 6 12.5Z" +
            "M8 7.5A0.5 0.5 0 1 0 9 7.5A0.5 0.5 0 1 0 8 7.5Z")

    val ColorWheel = icon("ColorWheel", width = 1.8f, stroke = "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12ZM12 9L12 3M14.598 13.5L19.794 16.5M9.402 13.5L4.206 16.5")
    val RgbSliders = icon("RgbSliders", width = 1.8f, stroke = "M10 5L3 5M12 19L3 19M14 3L14 7M16 17L16 21M21 12L12 12M21 19L16 19M21 5L14 5M8 10L8 14M8 12L3 12")
    val Saturation = icon("Saturation", stroke = "M12 22A7 7 0 0 0 19 15C19 13 18 11.1 16 9.5C14 7.9 12.5 5.5 12 3C11.5 5.5 10 7.9 8 9.5C6 11.1 5 13 5 15A7 7 0 0 0 12 22Z")
    val Brightness = icon("Brightness", stroke = "M8 12A4 4 0 1 0 16 12A4 4 0 1 0 8 12ZM12 2L12 4M12 20L12 22M4.93 4.93L6.34 6.34M17.66 17.66L19.07 19.07M2 12L4 12M20 12L22 12M6.34 17.66L4.93 19.07" +
            "M19.07 4.93L17.66 6.34")
    val Opacity = icon("Opacity", stroke = "M3.4 12A8.6 8.6 0 1 0 20.6 12A8.6 8.6 0 1 0 3.4 12Z", fill = "M8.4 8.4L12 8.4L12 12L8.4 12ZM12 12L15.6 12L15.6 15.6L12 15.6Z")
    val CheckCircle = icon("CheckCircle", width = 1.7f, stroke = "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM16 9L10.5 14.5L8 12")

    val Backspace = icon("Backspace", stroke = "M10 5A2 2 0 0 0 8.656 5.519L2.328 11.259A1 1 0 0 0 2.328 12.74L8.656 18.481A2 2 0 0 0 10 19L20 19A2 2 0 0 0 22 17L22 7A2 2 0 0 0 20 5ZM12 9L18 15M18 9" +
            "L12 15")
    val Check = icon("Check", width = 1.8f, stroke = "M20 6L9 17L4 12")
}

private fun icon(name: String, stroke: String? = null, fill: String? = null, width: Float = 1.6f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
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
