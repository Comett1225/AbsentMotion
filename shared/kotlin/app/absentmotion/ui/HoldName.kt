package app.absentmotion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val NAME_LINGER_MS = 1000L

@Composable
fun BoxScope.HoldName(name: String) {
    if (name.isBlank()) return
    val hold = remember { NameHold() }
    if (hold.shown && !hold.held) LaunchedEffect(Unit) { delay(NAME_LINGER_MS); hold.shown = false }
    Box(Modifier.matchParentSize().then(HoldElement(hold))) {
        if (hold.shown) {
            val density = LocalDensity.current
            val top = WindowInsets.statusBars.union(WindowInsets.captionBar).getTop(density)
            val spot = with(density) { NameSpot(8.dp.roundToPx(), top) }
            Popup(spot, properties = app.absentmotion.platform.NameLabelWindow) {
                Text(
                    name, Modifier.widthIn(max = 240.dp).background(Color(0xE6222222), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private class NameHold {
    var shown by mutableStateOf(false)
    var held by mutableStateOf(false)
}

private class HoldElement(val hold: NameHold) : ModifierNodeElement<HoldNode>() {
    override fun create() = HoldNode(hold)
    override fun update(node: HoldNode) { node.hold = hold }
    override fun equals(other: Any?) = other is HoldElement && other.hold === hold
    override fun hashCode() = System.identityHashCode(hold)
}

private class HoldNode(var hold: NameHold) : Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private var finger: PointerId? = null
    private var start = Offset.Zero
    private var timer: Job? = null
    private var fired = false

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val changes = pointerEvent.changes
        val tracked = finger
        if (pass == PointerEventPass.Final) {
            val change = changes.firstOrNull { it.id == tracked } ?: return
            if (change.pressed && change.previousPressed && change.isConsumed) { hold.shown = false; end() }
            return
        }
        if (pass != PointerEventPass.Initial) return
        if (tracked == null) {
            val down = changes.singleOrNull { it.changedToDownIgnoreConsumed() } ?: return
            if (changes.count { it.pressed } != 1 || down.type != PointerType.Touch && down.type != PointerType.Stylus) return
            finger = down.id
            start = down.position
            hold.shown = false
            timer = coroutineScope.launch {
                delay(currentValueOf(LocalViewConfiguration).longPressTimeoutMillis)
                fired = true
                hold.held = true
                hold.shown = true
                if (AppSettings.vibrates(currentValueOf(LocalContext))) currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.LongPress)
            }
            return
        }
        val change = changes.firstOrNull { it.id == tracked }
        val at = change?.position ?: start
        when {
            change == null -> end()
            !change.pressed -> { if (fired) change.consume(); end() }
            changes.count { it.pressed } > 1 -> { hold.shown = false; end() }
            fired -> if (at.x < 0 || at.y < 0 || at.x > bounds.width || at.y > bounds.height) { hold.shown = false; hold.held = false }
            (at - start).getDistance() > currentValueOf(LocalViewConfiguration).touchSlop -> end()
        }
    }

    private fun end() {
        timer?.cancel()
        timer = null
        finger = null
        fired = false
        hold.held = false
    }

    override fun onCancelPointerInput() {
        end()
        hold.shown = false
    }

    override fun onDetach() = end()

    override fun sharePointerInputWithSiblings() = true
}

internal class NameSpot(private val gap: Int, private val top: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val above = anchorBounds.top - gap - h
        if (above >= top + gap) return IntOffset((anchorBounds.center.x - w / 2).coerceIn(gap, (windowSize.width - w - gap).coerceAtLeast(gap)), above)
        val right = anchorBounds.right + gap
        val left = anchorBounds.left - gap - w
        val sides = listOf(right, left).filter { it >= gap && it + w <= windowSize.width - gap }
        val side = if (layoutDirection == LayoutDirection.Rtl) sides.lastOrNull() else sides.firstOrNull()
        if (side != null) return IntOffset(side, (anchorBounds.center.y - h / 2).coerceIn(top + gap, (windowSize.height - h - gap).coerceAtLeast(top + gap)))
        val x = (anchorBounds.center.x - w / 2).coerceIn(gap, (windowSize.width - w - gap).coerceAtLeast(gap))
        return IntOffset(x, (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - h - gap).coerceAtLeast(0)))
    }
}
