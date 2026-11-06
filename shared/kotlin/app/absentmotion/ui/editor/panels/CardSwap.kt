package app.absentmotion.ui.editor.panels

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.theme.Swap
import androidx.compose.ui.unit.dp

internal object CardMotion {
    const val MOVE_MS = 280
    const val FADE_MS = 160
    fun focus(current: Int, target: Int): Int = if (target >= 0) target else current
    fun away(index: Int, focus: Int): Int = if (index < focus) -1 else 1
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal class CardScope(val shared: SharedTransitionScope?, val visibility: AnimatedVisibilityScope?, val focus: Int)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CardSwap(open: Int, modifier: Modifier, list: @Composable BoxScope.(CardScope) -> Unit, card: @Composable BoxScope.(CardScope, Int) -> Unit) {
    if (PlatformFeatures.desktop) {
        Swap(open, modifier, rise = 0.dp) { if (open < 0) list(CardScope(null, null, -1)) else card(CardScope(null, null, open), open) }
        return
    }
    SharedTransitionLayout(modifier.clipToBounds()) {
        val transition = updateTransition(open, "cards")
        transition.AnimatedContent(Modifier.fillMaxSize(), transitionSpec = {
            fadeIn(tween(CardMotion.FADE_MS)) togetherWith fadeOut(tween(CardMotion.FADE_MS))
        }) { at ->
            val scope = CardScope(this@SharedTransitionLayout, this, CardMotion.focus(transition.currentState, transition.targetState))
            Box(Modifier.fillMaxSize()) { if (at < 0) list(scope) else card(scope, at) }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.cardBounds(scope: CardScope, key: Any): Modifier {
    val shared = scope.shared ?: return this
    val visibility = scope.visibility ?: return this
    return with(shared) {
        sharedBounds(
            rememberSharedContentState("card-$key"), visibility,
            enter = fadeIn(tween(CardMotion.FADE_MS)), exit = fadeOut(tween(CardMotion.FADE_MS)),
            boundsTransform = { _, _ -> tween(CardMotion.MOVE_MS, easing = FastOutSlowInEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

@Composable
internal fun Modifier.cardAway(scope: CardScope, index: Int): Modifier {
    val visibility = scope.visibility ?: return this
    if (index == scope.focus || scope.focus < 0) return this
    val way = CardMotion.away(index, scope.focus)
    return with(visibility) {
        animateEnterExit(
            enter = slideInVertically(tween(CardMotion.MOVE_MS, easing = FastOutSlowInEasing)) { it * way } + fadeIn(tween(CardMotion.FADE_MS)),
            exit = slideOutVertically(tween(CardMotion.MOVE_MS, easing = FastOutSlowInEasing)) { it * way } + fadeOut(tween(CardMotion.FADE_MS)),
        )
    }
}
