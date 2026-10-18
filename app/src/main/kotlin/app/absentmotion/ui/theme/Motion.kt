package app.absentmotion.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

object Motion {
    const val FastMs = 150
    const val EnterMs = 210
    const val ExitMs = 150

    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> enter() = tween<T>(EnterMs, easing = EaseOut)
    fun <T> exit() = tween<T>(ExitMs, easing = EaseIn)
    fun <T> fast() = tween<T>(FastMs, easing = EaseOut)
}

@Composable
fun animatedColor(target: Color): Color = animateColorAsState(target, Motion.fast(), label = "tint").value

@Composable
fun Modifier.animatedBackground(color: Color): Modifier {
    val c = animateColorAsState(color, Motion.enter(), label = "background")
    return drawBehind { drawRect(c.value) }
}

@Composable
fun Modifier.fadeBackground(on: Boolean, color: Color): Modifier {
    val a = animateFloatAsState(if (on) 1f else 0f, Motion.fast(), label = "highlight")
    return drawBehind { drawRect(color, alpha = a.value) }
}

@Composable
fun Modifier.enterFade(rise: Dp = 0.dp, delayMs: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(Motion.EnterMs, delayMs, Motion.EaseOut)) }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * risePx
    }
}

@Composable
fun Modifier.enterOnce(seen: MutableSet<Any>, id: Any, index: Int = 0): Modifier {
    val first = remember { seen.add(id) }
    return if (first) enterFade(10.dp, minOf(index, 6) * 30) else this
}

@Stable
class PresenceState internal constructor(val progress: State<Float>, private val transition: MutableTransitionState<Boolean>) {
    val leaving: Boolean get() = !transition.targetState
}

internal val LocalPresence: ProvidableCompositionLocal<PresenceState?> = compositionLocalOf { null }

@Composable
fun Presence(visible: Boolean, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val transition = rememberTransition(state, label = "presence")
    val progress = transition.animateFloat(
        transitionSpec = { if (targetState) Motion.enter() else Motion.exit() },
        label = "presence",
    ) { if (it) 1f else 0f }
    if (state.currentState || state.targetState) {
        val presence = remember(state) { PresenceState(progress, state) }
        CompositionLocalProvider(LocalPresence provides presence, content = content)
    }
}

@Composable
fun Modifier.presence(
    scaleFrom: Float = 1f,
    rise: Dp = 0.dp,
    slide: Float = 0f,
    fade: Boolean = true,
    origin: TransformOrigin = TransformOrigin.Center,
): Modifier {
    val p = LocalPresence.current ?: return this
    val risePx = with(LocalDensity.current) { rise.toPx() }
    val moving = this.graphicsLayer {
        val t = p.progress.value
        if (fade) alpha = t
        val s = scaleFrom + (1f - scaleFrom) * t
        scaleX = s
        scaleY = s
        transformOrigin = origin
        translationY = (1f - t) * (risePx + slide * size.height)
    }
    return if (p.leaving) moving.pointerInput(Unit) { consumeAll() } else moving
}

@Composable
fun Modifier.presenceScrim(color: Color): Modifier {
    val p = LocalPresence.current ?: return background(color)
    return drawBehind { drawRect(color, alpha = p.progress.value) }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.consumeAll() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

private class Stage(val layer: GraphicsLayer, shown: Boolean) {
    val enter = Animatable(if (shown) 1f else 0f)
    val exit = Animatable(0f)
    var started = false
    var leaving = false
}

private class Board {
    var current: Stage? = null
    val ghosts = mutableStateListOf<Stage>()
}

@Composable
fun Swap(
    key: Any?,
    modifier: Modifier = Modifier,
    rise: Dp = 8.dp,
    animateFirst: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val layers = arrayOf(rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer())
    val board = remember { Board() }
    val scope = rememberCoroutineScope()
    val risePx = with(LocalDensity.current) { rise.toPx() }
    val stage = remember(key) {
        val prev = board.current
        if (prev != null && prev !in board.ghosts) board.ghosts.add(prev)
        if (board.ghosts.size >= layers.size) board.ghosts.removeAt(0)
        val layer = layers.first { l -> board.ghosts.none { it.layer === l } }
        Stage(layer, shown = prev == null && !animateFirst).also { board.current = it }
    }
    SideEffect {
        if (!stage.started) {
            stage.started = true
            if (stage.enter.value < 1f) scope.launch { stage.enter.animateTo(1f, Motion.enter()) }
        }
        for (ghost in board.ghosts.toList()) {
            if (ghost.leaving) continue
            ghost.leaving = true
            scope.launch {
                ghost.exit.animateTo(1f, Motion.exit())
                board.ghosts.remove(ghost)
            }
        }
    }
    Box(modifier) {
        Box(
            Modifier.matchParentSize().drawBehind {
                for (ghost in board.ghosts) {
                    val a = ghost.enter.value * (1f - ghost.exit.value)
                    if (a <= 0f) continue
                    ghost.layer.alpha = a
                    drawLayer(ghost.layer)
                }
            },
        )
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val t = stage.enter.value
                    alpha = t
                    translationY = (1f - t) * risePx
                }
                .drawWithContent { recordInto(stage.layer) },
            content = content,
        )
    }
}

private fun ContentDrawScope.recordInto(layer: GraphicsLayer) {
    layer.record { this@recordInto.drawContent() }
    layer.alpha = 1f
    drawLayer(layer)
}

class PressIndication(private val tint: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, tint)
    override fun equals(other: Any?) = other is PressIndication && other.tint == tint
    override fun hashCode() = tint.hashCode()

    private class PressNode(private val source: InteractionSource, private val tint: Color) : Modifier.Node(), DrawModifierNode {
        private val level = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                val presses = mutableListOf<PressInteraction.Press>()
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> presses.add(i)
                        is PressInteraction.Release -> presses.remove(i.press)
                        is PressInteraction.Cancel -> presses.remove(i.press)
                        else -> return@collect
                    }
                    launch { if (presses.isEmpty()) level.animateTo(0f, Motion.exit()) else level.animateTo(1f, tween(60)) }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val l = level.value
            if (l > 0f) drawRect(tint, alpha = l)
        }
    }
}

@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.93f): Modifier {
    val down by source.collectIsPressedAsState()
    val s = animateFloatAsState(if (down) pressed else 1f, Motion.fast(), label = "press")
    return graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}
