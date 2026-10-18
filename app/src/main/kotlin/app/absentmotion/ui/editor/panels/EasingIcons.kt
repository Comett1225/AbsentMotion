package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import app.absentmotion.model.Easing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

private fun svg(data: String): Path = PathParser().parsePathString(data).toPath()

private const val REVERSE_ARROWS = "M16 3L20 7L16 11M20 7L4 7M8 21L4 17L8 13M4 17L20 17"
private const val CHEVRON_LEFT = "M15 18L9 12L15 6"
private const val CHEVRON_RIGHT = "M9 18L15 12L9 6"

@Composable
internal fun ReverseIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(85.px, 70.px)) {
        val k = size.height / 24f
        withTransform({ translate((size.width - size.height) / 2f, 0f); scale(k, k, Offset.Zero) }) {
            drawPath(svg(REVERSE_ARROWS), tint, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun ChevronIcon(next: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(70.px)) {
        val k = size.width / 24f
        scale(k, k, Offset.Zero) {
            drawPath(svg(if (next) CHEVRON_RIGHT else CHEVRON_LEFT), tint, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun CurveThumb(easing: Easing, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(68.px)) {
        val k = size.width / 68f
        scale(k, k, Offset.Zero) { drawThumb(easing, tint) }
    }
}

private fun tx(u: Float) = 3f + 61f * u
private fun ty(v: Float) = 64f - 60f * v

private fun DrawScope.drawThumb(easing: Easing, tint: Color) {
    val line = if (tint == Color.White) tint.copy(alpha = 0.83f) else tint
    drawRoundRect(tint.copy(alpha = 0.16f), Offset(3f, 4f), Size(61f, 60f), CornerRadius(8f))
    drawRoundRect(tint.copy(alpha = 0.4f), Offset(3f, 4f), Size(61f, 60f), CornerRadius(8f), style = Stroke(3f))
    val curve = Stroke(3.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun dot(u: Float, v: Float) = drawCircle(tint, 5.5f, Offset(tx(u), ty(v)))
    fun segment(u0: Float, u1: Float, v0: Float, v1: Float = v0) =
        drawLine(tint, Offset(tx(u0), ty(v0)), Offset(tx(u1), ty(v1)), 4.4f, StrokeCap.Round)
    fun polyline(f: (Float) -> Float, count: Int = 48, scaleY: Float = 1f) {
        val path = Path()
        for (i in 0 until count) {
            val u = i / (count - 1f)
            val x = tx(u)
            val y = ty((f(u) * scaleY).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = curve)
    }
    when (easing.type) {
        "cubicBezier" -> if (easing.p.size >= 4) {
            val p = easing.p
            drawPath(
                Path().apply { moveTo(tx(0f), ty(0f)); cubicTo(tx(p[0]), ty(p[1]), tx(p[2]), ty(p[3]), tx(1f), ty(1f)) },
                line, style = curve,
            )
            segment(0f, p[0], 0f, p[1]); dot(p[0], p[1])
            segment(1f, p[2], 1f, p[3]); dot(p[2], p[3])
        }
        "bounce" -> { polyline(::bounceOut, 60); dot(1f / 2.75f, 1f) }
        "elastic" -> { polyline({ 1f - exp(-5f * it) * cos(2f * PI.toFloat() * 1.7f * it) }, 60, 0.8f); dot(0.15f, 0.8f) }
        "cyclic" -> { polyline({ 0.5f + 0.42f * cos(2f * PI.toFloat() * 1.25f * it) }, 60); dot(0f, 0.92f) }
        "random" -> {
            val noise = floatArrayOf(0f, 1f, -1f, 1f, -0.6f, 1f, -1f, 0.8f, -1f, 1f, -0.8f, 1f, -1f, 0.7f, -0.5f, 0f)
            polyline({ 0.1f + 0.8f * it + 0.12f * noise[(it * 15f + 0.5f).toInt()] }, 16)
            dot(0.2f, 0.27f)
        }
        "steps" -> {
            segment(0f, 0.37f, 0f); dot(0.37f, 0f)
            segment(0.18f, 0.49f, 0.27f); segment(0.35f, 0.66f, 0.5f); segment(0.53f, 0.83f, 0.73f); segment(0.69f, 1f, 1f)
        }
        else -> {
            segment(0f, 0.45f, 0f); dot(0.45f, 0f)
            for ((u0, v) in listOf(0f to 0.3f, 0.3f to 0.6f, 0.55f to 0.9f)) {
                val path = Path().apply {
                    moveTo(tx(u0), ty(v))
                    for (i in 1..6) lineTo(tx(u0 + 0.07f * i), ty(v + if (i % 2 == 1) 0.07f else -0.04f))
                }
                drawPath(path, line, style = Stroke(3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun bounceOut(t: Float): Float {
    val n = 7.5625f
    val d = 2.75f
    return when {
        t < 1 / d -> n * t * t
        t < 2 / d -> (t - 1.5f / d).let { n * it * it + 0.75f }
        t < 2.5f / d -> (t - 2.25f / d).let { n * it * it + 0.9375f }
        else -> (t - 2.625f / d).let { n * it * it + 0.984375f }
    }
}
