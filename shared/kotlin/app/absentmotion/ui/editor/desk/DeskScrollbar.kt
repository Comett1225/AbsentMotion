package app.absentmotion.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.theme.Am

private class Range(val position: Float, val total: Float)

private val Reach = 14.dp
private val Thickness = 6.dp
private val Inset = 3.dp
private val MinThumb = 32.dp

@Composable
internal fun Modifier.deskVerticalScroll(state: ScrollState = rememberScrollState(), color: Color = Am.OnDark): Modifier =
    if (PlatformFeatures.desktop) deskScrollbar(state, color).verticalScroll(state) else verticalScroll(state)

@Composable
internal fun Modifier.deskScrollbar(state: ScrollState, color: Color = Am.OnDark): Modifier =
    bar(state, color) { viewport -> if (state.maxValue in 1 until Int.MAX_VALUE) Range(state.value.toFloat(), viewport + state.maxValue) else null }

@Composable
internal fun Modifier.deskScrollbar(state: LazyListState, color: Color = Am.OnDark): Modifier =
    bar(state, color) { viewport ->
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || !(state.canScrollBackward || state.canScrollForward)) null
        else {
            val step = (visible.last().offset + visible.last().size - visible.first().offset).toFloat() / visible.size
            val total = maxOf(step * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding, viewport + 1f)
            Range(state.firstVisibleItemIndex * step + state.firstVisibleItemScrollOffset, total)
        }
    }

@Composable
internal fun Modifier.deskScrollbar(state: LazyGridState, color: Color = Am.OnDark): Modifier =
    bar(state, color) { viewport ->
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || !(state.canScrollBackward || state.canScrollForward)) null
        else {
            val first = visible.first()
            val perRow = visible.count { it.row == first.row }.coerceAtLeast(1)
            val rows = visible.map { it.row }.distinct().size
            val step = if (rows > 1) (visible.last().offset.y - first.offset.y).toFloat() / (visible.last().row - first.row).coerceAtLeast(1)
                else (first.size.height + info.mainAxisItemSpacing).toFloat()
            val lines = (info.totalItemsCount + perRow - 1) / perRow
            val total = maxOf(step * lines + info.beforeContentPadding + info.afterContentPadding - info.mainAxisItemSpacing, viewport + 1f)
            Range((state.firstVisibleItemIndex / perRow) * step + state.firstVisibleItemScrollOffset, total)
        }
    }

@Composable
private fun Modifier.bar(state: ScrollableState, color: Color, range: (Float) -> Range?): Modifier {
    if (!PlatformFeatures.desktop) return this
    val measure by rememberUpdatedState(range)
    var hover by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (drag) .8f else if (hover) .6f else .3f, label = "scrollbar")
    return pointerInput(state) {
        val reach = Reach.toPx()
        val inset = Inset.toPx()
        val minimum = MinThumb.toPx()
        fun thumb(r: Range): FloatArray {
            val viewport = size.height.toFloat()
            val track = (viewport - inset * 2).coerceAtLeast(1f)
            val length = (viewport / r.total * track).coerceIn(minimum.coerceAtMost(track), track)
            return floatArrayOf(inset + (track - length) * (r.position / (r.total - viewport)).coerceIn(0f, 1f), length, track)
        }
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.first()
                val r = measure(size.height.toFloat())
                val near = r != null && event.type != PointerEventType.Exit && change.position.x >= size.width - reach && change.position.x <= size.width && change.position.y in 0f..size.height.toFloat()
                if (hover != near) hover = near
                if (r == null || !near || event.type != PointerEventType.Press || !event.buttons.isPrimaryPressed) continue
                val metrics = thumb(r)
                val grab = if (change.position.y in metrics[0]..metrics[0] + metrics[1]) change.position.y - metrics[0] else metrics[1] / 2
                change.consume()
                drag = true
                try {
                    var current = change
                    while (current.pressed) {
                        val moved = awaitPointerEvent(PointerEventPass.Initial)
                        current = moved.changes.firstOrNull { it.id == change.id } ?: break
                        current.consume()
                        val now = measure(size.height.toFloat()) ?: break
                        val shape = thumb(now)
                        val free = (shape[2] - shape[1]).coerceAtLeast(1f)
                        val wanted = ((current.position.y - grab - inset) / free).coerceIn(0f, 1f) * (now.total - size.height)
                        state.dispatchRawDelta(wanted - now.position)
                    }
                } finally { drag = false }
            }
        }
    }.drawWithContent {
        drawContent()
        val r = measure(size.height) ?: return@drawWithContent
        val viewport = size.height
        val track = (viewport - Inset.toPx() * 2).coerceAtLeast(1f)
        val length = (viewport / r.total * track).coerceIn(MinThumb.toPx().coerceAtMost(track), track)
        val top = Inset.toPx() + (track - length) * (r.position / (r.total - viewport)).coerceIn(0f, 1f)
        val width = Thickness.toPx()
        drawRoundRect(color.copy(alpha = alpha), Offset(size.width - width - Inset.toPx(), top), Size(width, length), CornerRadius(width / 2))
    }
}
