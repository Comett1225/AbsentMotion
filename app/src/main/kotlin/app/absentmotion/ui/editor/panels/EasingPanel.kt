package app.absentmotion.ui.editor.panels

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.absentmotion.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.keyLayer
import app.absentmotion.editor.canPasteCurve
import app.absentmotion.editor.copyCurve
import app.absentmotion.editor.easing
import app.absentmotion.editor.gotoStretch
import app.absentmotion.editor.hasStretch
import app.absentmotion.editor.pasteCurve
import app.absentmotion.editor.pasteCurveToAll
import app.absentmotion.editor.setEasing
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.Easing
import app.absentmotion.model.EasingCatalog
import app.absentmotion.model.EasingHandle
import app.absentmotion.model.handles
import app.absentmotion.model.reversed
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import kotlin.math.ceil
import kotlin.math.hypot

private val GraphBg get() = Am.Chosen
private val PresetTile get() = Am.Button
private val NavButton get() = Am.Field
private val CaptionColour get() = Am.Caption
private val Yellow get() = Am.Gold
private val TileShape = RoundedCornerShape(6.dp)
private const val HANDLE_KEY = "easing-handle"
private const val SAMPLES = 201
private const val DIM = 0.35f

@Composable
internal fun EasingPanel(state: EditorState, modifier: Modifier = Modifier) {
    val easing = state.easing
    if (easing == null) {
        LaunchedEffect(Unit) { state.easingOpen = false }
        Box(modifier)
        return
    }
    val family = EasingCatalog.familyOf(easing)
    val basic = easing.type == "linear" || easing.type == "cubicBezier"
    var lockOvershoot by remember { mutableStateOf(false) }
    var disableOvershoot by remember(easing.type) { mutableStateOf(false) }
    val requiresOvershoot = easing.type == "cubicBezier" && easing.p.any { it < 0f || it > 1f }
    val overshoot = state.panel != EditorPanel.Speed && basic && !disableOvershoot && (state.overshoot || requiresOvershoot || lockOvershoot)
    LaunchedEffect(overshoot) { lockOvershoot = overshoot }
    Row(modifier.background(Am.Field)) {
        LeftColumn(state, easing, basic, overshoot, {
            state.overshoot = !overshoot
            disableOvershoot = overshoot
            lockOvershoot = !overshoot
        }, Modifier.weight(168f).fillMaxHeight())
        Graph(state, easing, overshoot, Modifier.weight(575f).fillMaxHeight())
        Presets(state, easing, family, Modifier.weight(169f).fillMaxHeight())
        Families(state, family, Modifier.weight(168f).fillMaxHeight())
    }
}

@Composable
private fun ColumnScope.Cell(enabled: Boolean = true, onClick: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        Alignment.Center, content = content,
    )
}

@Composable
private fun LeftColumn(state: EditorState, easing: Easing, basic: Boolean, overshoot: Boolean, toggleOvershoot: () -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier) {
        Cell(onClick = { state.easingOpen = false }) { PanelIcon(PanelIcons.Back, Am.OnDark, size = 26.dp, description = stringResource(R.string.topbar_back)) }
        Cell(enabled = false) {}
        val reversed = easing.reversed()
        Cell(enabled = reversed != null, onClick = { reversed?.let { state.setEasing(it) } }) {
            val reverse = stringResource(R.string.a11y_reverse_curve)
            ReverseIcon(Am.OnDark, (if (reversed != null) Modifier else Modifier.dimmed()).semantics { contentDescription = reverse })
        }
        Cell(onClick = { menu = true }) {
            PanelIcon(PanelIcons.More, Am.OnDark, size = 26.dp, description = stringResource(R.string.effects_more))
            if (menu) OverflowMenu(state, basic, overshoot, toggleOvershoot) { menu = false }
        }
    }
}

private class Geo(val k: Float, val h: Float, val s: Float, val c: Float, val vMin: Float, val vMax: Float) {
    fun x(u: Float) = 42f + 491f * u
    fun y(v: Float) = h / 2f - (v - c) * s
    fun u(x: Float) = (x - 42f) / 491f
    fun v(y: Float) = c + (h / 2f - y) / s
}

private fun geoOf(size: IntSize, easing: Easing, overshoot: Boolean): Geo? {
    if (size.width == 0 || size.height == 0) return null
    val k = size.width / 575f
    val h = size.height / k
    val reverseCount = easing.prefixes.count { it == "reverse" }
    var lo = when (easing.type) { "elastic" -> -0.3f; "cyclic" -> -0.5f; else -> -0.25f }
    var hi = when (easing.type) { "elastic" -> 2.05f; "cyclic" -> 1.5f; else -> 1.25f }
    repeat(reverseCount) { i ->
        val origin = if (easing.type == "elastic" && i == 0) 0.6f else 1f
        val bottom = origin - hi
        hi = origin - lo
        lo = bottom
    }
    if (overshoot) { lo = -2f; hi = 3f }
    return Geo(k, h, (h - 84f) / (hi - lo), (lo + hi) / 2f, lo, hi)
}

@Composable
private fun Graph(state: EditorState, easing: Easing, overshoot: Boolean, modifier: Modifier) {
    val samples = remember(easing) { NativeEngine.ease(easing.toString(), FloatArray(SAMPLES) { it / (SAMPLES - 1f) }) }
    val handles = easing.handles()
    val progress = state.keyTarget?.ref?.let { state.keyLayer?.stretchProgress(it) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geo = geoOf(size, easing, overshoot)

    val liveGeo by rememberUpdatedState(geo)
    val liveEasing by rememberUpdatedState(easing)
    val liveOvershoot by rememberUpdatedState(overshoot)

    Box(modifier.background(GraphBg)) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
                .pointerInput(Unit) {
                    var grabbed = -1
                    var start = Offset.Zero
                    var position = Offset.Zero
                    var damping = 1f
                    var handleOvershoot = false
                    detectDragGestures(
                        onDragStart = { at ->
                            val g = liveGeo
                            grabbed = -1
                            if (g != null) {
                                val hs = liveEasing.handles()
                                val reach = 24.dp.toPx()
                                val nearest = hs.indices.minByOrNull { hypot(at.x - g.x(hs[it].x) * g.k, at.y - g.y(hs[it].y) * g.k) }
                                if (nearest != null) {
                                    val corner = Offset(g.x(hs[nearest].x) * g.k, g.y(hs[nearest].y) * g.k)
                                    if ((corner - at).getDistance() <= reach) {
                                        grabbed = nearest
                                        start = at
                                        position = Offset(hs[nearest].x, hs[nearest].y)
                                        damping = hs[nearest].dampingFactor
                                        handleOvershoot = liveOvershoot || hs[nearest].allowOvershoot
                                    }
                                }
                            }
                        },
                        onDragEnd = { grabbed = -1; state.endGesture() },
                        onDragCancel = { grabbed = -1; state.endGesture() },
                    ) { change, _ ->
                        val g = liveGeo ?: return@detectDragGestures
                        val hs = liveEasing.handles()
                        if (grabbed < 0 || grabbed >= hs.size) return@detectDragGestures
                        change.consume()
                        val delta = (change.position - start) * damping
                        val u = (position.x + delta.x / (491f * g.k)).coerceIn(0f, 1f)
                        val v = (position.y - delta.y / (g.s * g.k)).let {
                            if (handleOvershoot) it.coerceIn(g.vMin, g.vMax) else it.coerceIn(0f, 1f)
                        }
                        state.setEasing(hs[grabbed].moved(u, v), HANDLE_KEY)
                    }
                },
        ) {
            if (geo != null) scale(geo.k, geo.k, Offset.Zero) { drawGraph(geo, samples, handles, progress, easing.type == "random" && "reverse" !in easing.prefixes) }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(98.px), verticalAlignment = Alignment.CenterVertically) {
            StretchButton(state, -1)
            Box(Modifier.weight(1f), Alignment.Center) {
                Text(stringResource(EasingCatalog.presetOf(easing).label), color = CaptionColour, fontSize = 9.sp, maxLines = 1, softWrap = false)
            }
            StretchButton(state, 1)
        }
    }
}

@Composable
private fun StretchButton(state: EditorState, direction: Int) {
    val enabled = state.hasStretch(direction)
    Box(
        Modifier.size(98.px).then(if (enabled) Modifier.clickable { state.gotoStretch(direction) } else Modifier),
        Alignment.Center,
    ) {
        Box(Modifier.size(70.px).clip(RoundedCornerShape(14.px)).background(NavButton), Alignment.Center) {
            ChevronIcon(next = direction > 0, tint = Am.OnDark.copy(alpha = if (enabled) 0.6f else 0.1f))
        }
    }
}

private fun DrawScope.drawGraph(g: Geo, samples: FloatArray, handles: List<EasingHandle>, progress: Float?, random: Boolean) {
    val left = g.x(0f)
    val right = g.x(1f)
    val top = g.y(1f)
    val bottom = g.y(0f)

    val step = (right - left) / 32f
    for (i in 0..32) {
        val x = left + i * step
        val inside = if (i % 8 == 0) 0.13f else 0.07f
        drawLine(Am.OnDark.copy(alpha = 0.035f), Offset(x, 0f), Offset(x, g.h), 2f)
        drawLine(Am.OnDark.copy(alpha = inside), Offset(x, top), Offset(x, bottom), 2f)
    }
    var y = bottom - ceil(bottom / step) * step
    while (y <= g.h) {
        if (y >= 0f) {
            drawLine(Am.OnDark.copy(alpha = 0.035f), Offset(left, y), Offset(right, y), 2f)
            if (y >= top - 1f && y <= bottom + 1f) drawLine(Am.OnDark.copy(alpha = 0.07f), Offset(left, y), Offset(right, y), 2f)
        }
        y += step
    }

    val dashes = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
    for (edge in listOf(top, bottom)) drawLine(Am.OnDark.copy(alpha = 0.45f), Offset(left, edge), Offset(right, edge), 3.5f, pathEffect = dashes)
    if (progress != null) {
        val x = g.x(progress.coerceIn(0f, 1f))
        drawLine(Am.OnDark, Offset(x, top), Offset(x, bottom), 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)))
    }

    for (h in handles) {
        val from = Offset(g.x(h.fromX ?: continue), g.y(h.fromY ?: continue))
        val colour = when { h.lineAccent -> Am.Accent; h.lineParam -> Yellow; else -> Am.OnDark }
        if (!h.dashed) drawLine(colour, from, Offset(g.x(h.x), g.y(h.y)), 8f, StrokeCap.Round)
        else if (random) drawLine(colour, from, Offset(g.x(h.x), g.y(0f)), 3.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)))
    }
    val path = Path()
    for (i in samples.indices) {
        val p = Offset(g.x(i / (samples.size - 1f)), g.y(samples[i]))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, Am.Accent, style = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(Am.Accent, 8f, Offset(left, g.y(samples.first())))
    drawCircle(Am.Accent, 8f, Offset(right, g.y(samples.last())))
    for (h in handles) drawCircle(if (h.param) Yellow else Am.OnDark, 33.5f, Offset(g.x(h.x), g.y(h.y)))
}

@Composable
private fun Presets(state: EditorState, easing: Easing, family: Int, modifier: Modifier) {
    Column(modifier.background(Am.Field)) {
        for (preset in EasingCatalog.families[family]) {
            val lit = EasingCatalog.matches(preset.easing, easing.copy(prefixes = emptyList())) ||
                (easing.type != "cubicBezier" && preset.easing.type == easing.type)
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable { state.setEasing(preset.easing) }
                    .padding(start = 22.px, end = 22.px, top = 11.px, bottom = 12.px)
                    .then(if (lit) Modifier.clip(TileShape).background(PresetTile) else Modifier),
                Alignment.Center,
            ) {
                val icon = if (preset.easing.type == "linear") Easing("cubicBezier", listOf(0.25f, 0.25f, 0.75f, 0.75f)) else preset.easing
                CurveThumb(icon, if (lit) Am.Accent else Am.OnDark)
            }
        }
    }
}

@Composable
private fun Families(state: EditorState, family: Int, modifier: Modifier) {
    Column(
        modifier.background(Am.Panel).drawBehind {
            val k = size.width / 168f
            val cy = (family + 0.5f) * size.height / 3f
            drawPath(
                Path().apply { moveTo(0f, cy - 17f * k); lineTo(0f, cy + 17f * k); lineTo(18f * k, cy); close() },
                Am.Field,
            )
        },
        verticalArrangement = Arrangement.Top,
    ) {
        for (i in 0..2) {
            val chosen = i == family
            Box(
                Modifier.weight(1f).fillMaxWidth().alpha(if (state.panel == EditorPanel.Speed && i != 0) 0.35f else 1f).clickable(enabled = state.panel != EditorPanel.Speed || i == 0) { if (!chosen) state.setEasing(EasingCatalog.families[i][0].easing) }
                    .padding(start = 22.px, end = 22.px, top = 11.px, bottom = 12.px)
                    .then(if (chosen) Modifier.clip(TileShape).background(PanelDark) else Modifier),
                Alignment.Center,
            ) {
                val icon = EasingCatalog.families[i].let { it[if (i == 0) 3 else 0] }.easing
                CurveThumb(icon, if (chosen) Am.Accent else Am.OnDark)
            }
        }
    }
}

private val MenuBg get() = Am.Field
private val MenuLine get() = Am.MenuLine

private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(anchorBounds.left, anchorBounds.top - popupContentSize.height)
}

@Composable
private fun OverflowMenu(state: EditorState, bezier: Boolean, overshoot: Boolean, toggleOvershoot: () -> Unit, onDismiss: () -> Unit) {
    Popup(AboveAnchor, onDismiss, PopupProperties(focusable = true)) {
        Column(Modifier.width(250.dp).clip(RoundedCornerShape(8.dp)).background(MenuBg)) {
            MenuRow(stringResource(R.string.easing_copy_curve), true, false) { state.copyCurve(); onDismiss() }
            MenuRow(stringResource(R.string.easing_paste_curve), state.canPasteCurve, true) { state.pasteCurve(); onDismiss() }
            MenuRow(stringResource(R.string.easing_apply_to_all), state.canPasteCurve, true) { state.pasteCurveToAll(); onDismiss() }
            if (bezier && state.panel != EditorPanel.Speed) MenuRow(stringResource(if (overshoot) R.string.easing_disable_overshoot else R.string.easing_enable_overshoot), true, true) {
                toggleOvershoot()
                onDismiss()
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, enabled: Boolean, divider: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { if (divider) drawRect(MenuLine, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) },
    ) {
        Text(
            text, color = Am.OnDark, fontSize = 15.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).alpha(if (enabled) 1f else DIM),
        )
    }
}
