package app.absentmotion.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.effects.EffectIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.animatedColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class ToolSlide {
    var order: List<String> = emptyList()
    var at by mutableStateOf<Offset?>(null)
    var size = IntSize.Zero
    var from: Offset = Offset.Zero
    var pending by mutableStateOf(false)
    val progress = Animatable(1f)
    var job: Job? = null
}

internal class ToolBoard(var order: List<String>) {
    var coords: LayoutCoordinates? = null
    var viewport: LayoutCoordinates? = null
    val slides = HashMap<String, ToolSlide>()
    var dragging by mutableStateOf<String?>(null)
    var dragAt by mutableStateOf(Offset.Zero)
    var grab = Offset.Zero

    fun lifted(id: String): Offset = dragAt - grab - (slides[id]?.at ?: Offset.Zero)

    fun under(point: Offset): String? = order.firstOrNull { id ->
        val slide = slides[id]
        val at = slide?.at
        slide != null && at != null && point.x in at.x..at.x + slide.size.width && point.y in at.y..at.y + slide.size.height
    }

    fun target(): Int? {
        val id = dragging ?: return null
        val size = slides[id]?.size ?: return null
        val center = dragAt - grab + Offset(size.width / 2f, size.height / 2f)
        return order.indexOfFirst { other ->
            val slide = slides[other]
            val at = slide?.at
            other != id && slide != null && at != null && abs(center.x - at.x - slide.size.width / 2f) < slide.size.width / 4f &&
                abs(center.y - at.y - slide.size.height / 2f) < slide.size.height / 4f
        }.takeIf { it >= 0 }
    }

    fun drop(scope: kotlinx.coroutines.CoroutineScope) {
        val id = dragging ?: return
        slides[id]?.let { slide ->
            slide.from = lifted(id)
            slide.pending = true
            slide.job?.cancel()
            slide.job = scope.launch {
                slide.progress.snapTo(0f)
                slide.pending = false
                slide.progress.animateTo(1f, Motion.enter())
                slide.from = Offset.Zero
            }
        }
        dragging = null
    }
}

internal fun Modifier.animateToolPlace(board: ToolBoard, id: String, scope: kotlinx.coroutines.CoroutineScope): Modifier {
    val slide = board.slides.getOrPut(id) { ToolSlide().also { it.order = board.order } }
    return onPlaced { coords ->
        val host = board.coords
        if (host == null || !host.isAttached || !coords.isAttached) return@onPlaced
        val current = host.localPositionOf(coords, Offset.Zero)
        val prev = slide.at
        slide.at = current
        slide.size = coords.size
        if (slide.order != board.order) {
            slide.order = board.order
            if (prev != null) {
                val visual = if (slide.pending) slide.from else slide.from * (1f - slide.progress.value)
                val delta = (prev + visual) - current
                if (delta.getDistanceSquared() > 1f) {
                    slide.from = delta
                    slide.pending = true
                    slide.job?.cancel()
                    slide.job = scope.launch {
                        slide.progress.snapTo(0f)
                        slide.pending = false
                        slide.progress.animateTo(1f, Motion.enter())
                        slide.from = Offset.Zero
                    }
                }
            }
        }
    }.graphicsLayer {
        val lifted = board.dragging == id
        val d = if (lifted) board.lifted(id) else if (slide.pending) slide.from else slide.from * (1f - slide.progress.value)
        translationX = d.x
        translationY = d.y
        scaleX = if (lifted) 1.03f else 1f
        scaleY = scaleX
    }.zIndex(if (board.dragging == id) 1f else 0f)
}

@Composable
internal fun CustomizeToolsSheet(settings: AppSettings, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val project = tab == 1
    val order = if (project) settings.projectToolOrder else settings.toolOrder
    val hidden = if (project) settings.projectToolsHidden else settings.toolsHidden
    val listed = order.filter { !project || it != "downloader" || app.absentmotion.platform.videoDownloader().available }
    val shown = listed.filterNot { it in hidden }
    val off = listed.filter { it in hidden }
    fun write(next: List<String>, nextHidden: Set<String>) {
        if (project) { settings.projectToolOrder = next; settings.projectToolsHidden = nextHidden }
        else { settings.toolOrder = next; settings.toolsHidden = nextHidden }
    }
    val board = remember(tab) { ToolBoard(shown) }
    board.order = shown
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    fun reorder() {
        val to = board.target() ?: return
        val next = movedToolOrder(board.order, board.order.indexOf(board.dragging), to)
        if (next != board.order) { board.order = next; write(next + order.filterNot { it in next }, hidden) }
    }
    val canReset = order != DefaultToolOrder || hidden.isNotEmpty()
    ToolSheet(stringResource(R.string.tools_customize), onClose, bottom = {
        Row(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp), Arrangement.spacedBy(12.dp)) {
            BigButton(stringResource(R.string.audiofx_reset), Modifier.weight(1f), primary = false, enabled = canReset) { write(DefaultToolOrder, emptySet()) }
            BigButton(stringResource(R.string.done), Modifier.weight(1f), onClick = onClose)
        }
    }) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Card).padding(4.dp).selectableGroup()) {
            listOf(R.string.tools_customize_page, R.string.tools_customize_project).forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(animatedColor(if (on) Am.Navy else Color.Transparent))
                        .selectable(on, role = Role.Tab) { board.dragging = null; tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), color = animatedColor(if (on) Am.OnNavy else Am.Navy), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().onPlaced { board.coords = it }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { id ->
                key(id) {
                    var handle by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    CustomizeRow(id, true, Modifier.animateToolPlace(board, id, scope), { write(order, hidden + id) }) {
                        Box(
                            Modifier.size(44.dp).testTag("grip-" + id).onPlaced { handle = it }.pointerInput(tab, id) {
                                fun at(local: Offset): Offset? {
                                    val host = board.coords ?: return null
                                    val own = handle ?: return null
                                    return if (host.isAttached && own.isAttached) host.localPositionOf(own, local) else null
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val point = at(down.position) ?: return@awaitEachGesture
                                    down.consume()
                                    board.grab = point - (board.slides[id]?.at ?: Offset.Zero)
                                    board.dragAt = point
                                    board.dragging = id
                                    if (settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    try {
                                        drag(down.id) { change ->
                                            change.consume()
                                            at(change.position)?.let { board.dragAt = it; reorder() }
                                        }
                                    } finally {
                                        board.drop(scope)
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(EffectIcons.Grip, null, Modifier.size(20.dp), tint = Am.HomeMid)
                        }
                    }
                }
            }
        }
        if (off.isNotEmpty()) {
            Text(stringResource(R.string.tools_not_shown), Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), color = Am.HomeDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                off.forEach { id -> key(id) { CustomizeRow(id, false, Modifier, { write(order, hidden - id) }) { Spacer(Modifier.size(44.dp)) } } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CustomizeRow(id: String, on: Boolean, modifier: Modifier, onToggle: () -> Unit, end: @Composable () -> Unit) {
    val tool = toolInfo(id)
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card)
            .toggleable(on, role = Role.Checkbox) { onToggle() }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(animatedColor(if (on) Am.Accent else Color.Transparent))
                .border(2.dp, animatedColor(if (on) Am.Accent else Am.HomeMid), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(PanelIcons.Check, null, Modifier.size(16.dp), tint = Am.OnAccent)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(40.dp).background(Am.Navy, CircleShape), contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, Modifier.size(20.dp), tint = Am.OnNavy)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(tool.title), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(tool.about), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        end()
    }
}
