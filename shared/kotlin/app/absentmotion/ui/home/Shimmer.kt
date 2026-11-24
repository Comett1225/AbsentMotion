package app.absentmotion.ui.home

import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import app.absentmotion.ui.theme.Am

private const val SHIMMER_NS = 1_500_000_000L
private const val SHIMMER_TILT = 0.2126f
private const val SHIMMER_WIDTH = 0.55f
private const val SHIMMER_PEAK = 0.75f

internal fun shimmerCentre(timeNs: Long, rootWidth: Float, rootHeight: Float): Float {
    val width = rootWidth * SHIMMER_WIDTH
    val phase = (timeNs % SHIMMER_NS).toFloat() / SHIMMER_NS
    return -width + phase * (rootWidth + rootHeight * SHIMMER_TILT + 2 * width)
}

internal fun Modifier.shimmer(shape: Shape): Modifier = composed {
    val light = Am.homeIsLight
    val base = lerp(Am.Card, Am.Navy, if (light) 0.105f else 0.075f)
    val glow = if (light) Am.Card else lerp(Am.Card, Am.Navy, 0.2f)
    val clock = produceState(-1L) {
        if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) return@produceState
        while (true) withFrameNanos { value = it }
    }
    val place = remember { FloatArray(4) }
    onGloballyPositioned {
        val at = it.positionInRoot()
        val root = it.findRootCoordinates()
        place[0] = at.x; place[1] = at.y; place[2] = root.size.width.toFloat(); place[3] = root.size.height.toFloat()
    }.clip(shape).drawBehind {
        drawRect(base)
        val now = clock.value
        if (now < 0 || place[2] <= 0f) return@drawBehind
        val width = place[2] * SHIMMER_WIDTH
        val step = Offset(1f, SHIMMER_TILT) * (width / (1 + SHIMMER_TILT * SHIMMER_TILT))
        val centre = Offset(shimmerCentre(now, place[2], place[3]) - place[0], -place[1])
        drawRect(Brush.linearGradient(
            0f to glow.copy(alpha = 0f),
            0.25f to glow.copy(alpha = SHIMMER_PEAK / 2),
            0.5f to glow.copy(alpha = SHIMMER_PEAK),
            0.75f to glow.copy(alpha = SHIMMER_PEAK / 2),
            1f to glow.copy(alpha = 0f),
            start = centre - step, end = centre + step
        ))
    }
}
