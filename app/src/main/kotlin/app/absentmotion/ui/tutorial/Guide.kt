package app.absentmotion.ui.tutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class Mark(val id: String, val area: Area)

internal object Guide {
    const val ADD = "add"

    fun tapTarget(marks: List<Mark>, limit: Float): Mark? =
        marks.firstOrNull()?.takeIf { it.area.width <= limit && it.area.height <= limit }

    fun accented(marks: List<Mark>, index: Int): Boolean = index == 0 && marks[index].id != ADD

    fun rings(tap: Mark?): Boolean = tap != null && tap.id != ADD

    fun pointerAt(tap: Mark?, framed: Boolean): Offset? = if (tap == null || !framed) null else Offset(tap.area.centreX, tap.area.centreY)

    fun inFrame(at: Offset, frame: Area?): Offset = if (frame == null) at else Offset(at.x - frame.left, at.y - frame.top)
}

internal enum class Flight { Stay, Appear, Glide, Snap, Leave }

internal object Pointer {
    const val NEAR = 0.5f
    const val SEEN = 0.05f

    fun flight(alpha: Float, at: Offset, to: Offset?, reduced: Boolean): Flight {
        if (to == null) return if (alpha > 0f) Flight.Leave else Flight.Stay
        if (alpha < SEEN) return Flight.Appear
        val dx = to.x - at.x
        val dy = to.y - at.y
        if (sqrt(dx * dx + dy * dy) < NEAR) return Flight.Stay
        return if (reduced) Flight.Snap else Flight.Glide
    }

    fun key(to: Offset?): Offset? = to?.let { Offset(it.x.roundToInt().toFloat(), it.y.roundToInt().toFloat()) }
}

internal class PointerFlight {
    val position = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    val alpha = Animatable(0f)

    suspend fun fly(to: Offset?, reduced: Boolean) {
        when (Pointer.flight(alpha.value, position.value, to, reduced)) {
            Flight.Stay -> if (to != null) fade(1f, reduced)
            Flight.Appear, Flight.Snap -> {
                position.snapTo(checkNotNull(to))
                fade(1f, reduced)
            }
            Flight.Glide -> coroutineScope {
                launch { fade(1f, false) }
                position.animateTo(checkNotNull(to), spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = GLIDE_STIFFNESS, visibilityThreshold = Offset.VisibilityThreshold))
            }
            Flight.Leave -> fade(0f, reduced)
        }
    }

    private suspend fun fade(to: Float, reduced: Boolean) {
        if (reduced) alpha.snapTo(to) else alpha.animateTo(to, tween(FADE_MS, easing = FastOutSlowInEasing))
    }

    private companion object {
        const val GLIDE_STIFFNESS = 320f
        const val FADE_MS = 180
    }
}

@Composable
internal fun rememberPointerFlight(target: Offset?, reduced: Boolean): PointerFlight {
    val flight = remember { PointerFlight() }
    val key = Pointer.key(target)
    LaunchedEffect(key, reduced) { flight.fly(key, reduced) }
    return flight
}
