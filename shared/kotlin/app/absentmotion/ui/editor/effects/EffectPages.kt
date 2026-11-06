package app.absentmotion.ui.editor.effects

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import kotlin.math.abs

internal object EffectPages {
    const val SWIPE_DP = 48f
    const val STEP_DP = 12f

    fun swiped(dx: Float, dy: Float, threshold: Float): Int? =
        if (abs(dx) >= threshold && abs(dx) > abs(dy) * 1.5f) (if (dx < 0f) -1 else 1) else null

    fun target(way: Int, rtl: Boolean): Boolean = (way < 0) != rtl

    fun scrub(fav: Boolean, dx: Float, step: Float, rtl: Boolean): Boolean = when {
        dx >= step -> !rtl
        dx <= -step -> rtl
        else -> fav
    }
}

@Composable
internal fun Modifier.pageSwipe(fav: Boolean, rtl: Boolean, go: (Boolean) -> Unit): Modifier {
    val latest by rememberUpdatedState(go)
    val onFav by rememberUpdatedState(fav)
    val threshold = with(LocalDensity.current) { EffectPages.SWIPE_DP.dp.toPx() }
    val pull = remember { floatArrayOf(0f) }
    val nested = remember(rtl, threshold) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!onFav || source != NestedScrollSource.UserInput) return Offset.Zero
                pull[0] = (pull[0] + available.x).let { if (rtl) it.coerceAtMost(0f) else it.coerceAtLeast(0f) }
                if (abs(pull[0]) >= threshold) { pull[0] = 0f; latest(false) }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity { pull[0] = 0f; return Velocity.Zero }
        }
    }
    return nestedScroll(nested).pointerInput(rtl, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            pull[0] = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                val way = EffectPages.swiped(dx, dy, threshold) ?: continue
                val toFav = EffectPages.target(way, rtl)
                if (toFav != onFav) { change.consume(); latest(toFav) }
                break
            }
        }
    }
}

@Composable
internal fun PageDots(fav: Boolean, rtl: Boolean, tick: (HapticFeedbackType) -> Unit, modifier: Modifier, go: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(go)
    val onFav by rememberUpdatedState(fav)
    val buzz by rememberUpdatedState(tick)
    val step = with(LocalDensity.current) { EffectPages.STEP_DP.dp.toPx() }
    val behind by animateColorAsState(if (held) Am.Button else Color.Transparent, tween(120), label = "dots")
    Row(modifier.clip(RoundedCornerShape(11.dp)).background(behind).pointerInput(rtl, step) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var done = false
                while (!done) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    done = change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
            }
            if (early != null) return@awaitEachGesture
            held = true
            buzz(HapticFeedbackType.LongPress)
            var anchor = down.position.x
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val next = EffectPages.scrub(onFav, change.position.x - anchor, step, rtl)
                    if (next != onFav) {
                        anchor = change.position.x
                        latest(next)
                        buzz(HapticFeedbackType.SegmentTick)
                    }
                }
            } finally { held = false }
        }
    }) {
        Box(Modifier.size(40.dp, 22.dp).clickable { latest(false) }, Alignment.CenterEnd) {
            Icon(EffectIcons.Recent, stringResource(R.string.effectbrowser_recent), Modifier.size(12.dp), tint = if (!fav) Am.Accent else Am.OnDarkDim)
        }
        Box(Modifier.size(40.dp, 22.dp).clickable { latest(true) }, Alignment.CenterStart) {
            Icon(PanelIcons.Star, stringResource(R.string.effectbrowser_favorite), Modifier.size(12.dp), tint = if (fav) Am.Accent else Am.OnDarkDim)
        }
    }
}
