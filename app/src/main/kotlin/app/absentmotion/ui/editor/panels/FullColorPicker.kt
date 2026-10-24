package app.absentmotion.ui.editor.panels

import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.readPreviewPixel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FullColor
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.ValueKeypad
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val ScreenBg get() = Am.PreviewBg
private val GridBg get() = Am.Deepest
private val CloseBg get() = Am.Panel
private val Idle get() = Am.Faint

private const val WHEEL = 965f
private const val RING_MID = 441f
private const val RING_THICK = 84f
private const val TICK_FROM = 347f
private const val TICK_TO = 373f
private const val HUE_KNOB = 35f
private const val SLIDER_PAD = 37f
private const val SLIDER_SPAN = 680f

@Composable
internal fun FullColorPicker(state: EditorState, request: FullColor, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var argb by remember { mutableIntStateOf(request.start) }
    var held by remember { mutableStateOf<Hsva?>(null) }
    var keypad by remember { mutableStateOf<KeypadRequest?>(null) }
    val hsva = held?.takeIf { it.toArgb() == argb } ?: Hsva.from(argb)
    val saved = state.settings.savedColours
    val close = { state.endGesture(); state.fullColor = null }
    BackHandler(onBack = close)

    fun setArgb(c: Int, drag: Boolean) { argb = c; request.apply(c, drag) }
    fun setHsva(h: Hsva) { held = h; setArgb(h.toArgb(), true) }
    fun enterHex() {
        val shifts = intArrayOf(16, 8, 0, 24)
        keypad = KeypadRequest(listOf("R", "G", "B", "A").mapIndexed { i, l -> KeypadField(l, ((argb ushr shifts[i]) and 255).toFloat(), "", 0f, 255f) }, 0) { v ->
            setArgb(v.indices.fold(0) { out, i -> out or (v[i].roundToInt().coerceIn(0, 255) shl shifts[i]) }, false)
        }
    }
    val end = { state.endGesture() }

    Box(modifier.fillMaxSize().background(ScreenBg).pointerInput(Unit) {}) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(127.px)) {
                TopTab(PanelIcons.ColorWheel, stringResource(R.string.a11y_color_wheel), tab == 0, Modifier.weight(469f)) { tab = 0 }
                TopTab(PanelIcons.RgbSliders, stringResource(R.string.a11y_color_sliders), tab == 1, Modifier.weight(470f)) { tab = 1 }
                Box(Modifier.weight(141f).fillMaxHeight().background(CloseBg).clickable(onClick = close), Alignment.Center) {
                    PanelIcon(PanelIcons.Check, Am.OnDark, size = 27.dp, description = stringResource(R.string.done))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                if (tab == 0) Wheel(hsva, request.start, argb, ::setHsva, end, ::enterHex) { save(state, argb) }
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ColourCard(request.start, argb, 394.px, 197.px) { save(state, argb) }
                    Spacer(Modifier.height(14.px))
                    HexText(argb, ::enterHex)
                }
            }
            if (tab == 0) {
                SliderRow(hsva.s, listOf(Hsva(hsva.h, 0f, hsva.v, 1f).opaque(), Hsva(hsva.h, 1f, hsva.v, 1f).opaque()), false, "${(hsva.s * 100).roundToInt()}%", { setHsva(hsva.copy(s = it)) }, end) {
                    PanelIcon(PanelIcons.Saturation, Idle, size = 19.dp)
                }
                SliderRow(hsva.v, listOf(Color.Black, Hsva(hsva.h, hsva.s, 1f, 1f).opaque()), false, "${(hsva.v * 100).roundToInt()}%", { setHsva(hsva.copy(v = it)) }, end) {
                    PanelIcon(PanelIcons.Brightness, Idle, size = 19.dp)
                }
                SliderRow(hsva.a, alphaTrack(hsva.opaque()), true, "${(hsva.a * 100).roundToInt()}%", { setHsva(hsva.copy(a = it)) }, end) {
                    PanelIcon(PanelIcons.Opacity, Idle, size = 19.dp)
                }
            } else {
                for ((i, name) in listOf("R", "G", "B").withIndex()) {
                    val shift = 16 - 8 * i
                    val value = (argb shr shift) and 255
                    val low = (argb or (255 shl 24)) and (255 shl shift).inv()
                    SliderRow(
                        value / 255f, listOf(Color(low), Color(low or (255 shl shift))), false, "$value",
                        { setArgb((argb and (255 shl shift).inv()) or ((it * 255f).roundToInt() shl shift), true) }, end,
                    ) { Text(name, color = Idle, fontSize = 20.sp) }
                }
                SliderRow((argb ushr 24) / 255f, alphaTrack(Color(argb or (255 shl 24))), true, "${argb ushr 24}", { setArgb((argb and 0xFFFFFF) or ((it * 255f).roundToInt() shl 24), true) }, end) {
                    PanelIcon(PanelIcons.Opacity, Idle, size = 19.dp)
                }
            }
            Box(Modifier.fillMaxWidth().height(315.px).background(GridBg)) {
                Column(Modifier.offset(211.px, 5.px).size(658.px, 304.px).verticalScroll(rememberScrollState()).padding(5.px, 11.px), Arrangement.spacedBy(11.px)) {
                    (FixedSwatches + saved).chunked(7).forEach { row ->
                        Row(Modifier.height(84.px), Arrangement.spacedBy(10.px)) {
                            row.forEach { swatch ->
                                Box(
                                    Modifier.size(84.px).clip(RoundedCornerShape(5.dp)).background(Color(swatch or 0xFF000000.toInt()))
                                        .buttonLabel(colourText(swatch or 0xFF000000.toInt())).clickable { setArgb((argb and 0xFF000000.toInt()) or swatch, false) },
                                )
                            }
                        }
                    }
                }
            }
        }
        Swap(keypad, Modifier.fillMaxSize(), rise = 0.dp) { keypad?.let { ValueKeypad(it, { keypad = null }, Modifier.fillMaxSize()) } }
    }
}

private fun save(state: EditorState, argb: Int) {
    val rgb = argb and 0xFFFFFF
    if (rgb !in FixedSwatches + state.settings.savedColours) state.settings.savedColours += rgb
}

private fun alphaTrack(colour: Color) = listOf(colour.copy(alpha = 0f), colour)

@Composable
private fun TopTab(icon: ImageVector, description: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(onClick = onClick), Alignment.Center) { PanelIcon(icon, if (active) Am.Accent else Idle, size = 27.dp, description = description) }
}

@Composable
private fun Wheel(hsva: Hsva, start: Int, argb: Int, onChange: (Hsva) -> Unit, onEnd: () -> Unit, onHex: () -> Unit, onSave: () -> Unit) {
    val u = with(LocalDensity.current) { 1.px.toPx() }
    Box(Modifier.size(WHEEL.px)) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val hues = List(13) { Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, 1f))) }
            drawCircle(Brush.sweepGradient(hues, centre), RING_MID * u, centre, style = Stroke(RING_THICK * u))
            for (i in 0 until 12) {
                val angle = Math.toRadians(i * 30.0)
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(hues[i], centre + dir * (TICK_FROM * u), centre + dir * (TICK_TO * u), 3 * u)
            }
            val angle = Math.toRadians(hsva.h.toDouble())
            val knob = centre + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (RING_MID * u)
            drawCircle(Color.White, HUE_KNOB * u, knob)
            drawCircle(Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(hsva.h % 360f, 1f, 1f))), (HUE_KNOB - 8) * u, knob)
        }
        DragArea(
            Modifier.fillMaxSize(), onEnd,
            accept = { hypot(it.x - WHEEL / 2 * u, it.y - WHEEL / 2 * u) >= 380f * u },
        ) { p ->
            val degrees = Math.toDegrees(atan2(p.y - WHEEL / 2 * u, p.x - WHEEL / 2 * u).toDouble()).toFloat()
            onChange(hsva.copy(h = (degrees + 360f) % 360f))
        }
        Text("${hsva.h.roundToInt() % 360}°", Modifier.offset(0.dp, 189.px).fillMaxWidth().height(80.px), color = Am.OnDark, fontSize = 30.sp, textAlign = TextAlign.Center)
        Box(Modifier.offset(((WHEEL - 197) / 2).px, 405.px)) { ColourCard(start, argb, 197.px, 141.px, onSave) }
        Box(Modifier.offset(0.dp, 560.px)) { HexText(argb, onHex) }
    }
}

@Composable
private fun ColourCard(start: Int, now: Int, width: Dp, height: Dp, onSave: () -> Unit) {
    Canvas(Modifier.size(width, height).clickable(onClick = onSave)) {
        val half = size.width / 2f
        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(20 * 1.px.toPx()))) }) {
            checker(Offset.Zero, size, 20 * 1.px.toPx())
            drawRect(Color(start), Offset.Zero, Size(half, size.height))
            drawRect(Color(now), Offset(half, 0f), Size(half, size.height))
        }
    }
}

@Composable
private fun HexText(argb: Int, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(57.px).clickable(onClick = onClick), Alignment.Center) {
        Text(colourText(argb), color = Am.OnDark, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SliderRow(
    value: Float, track: List<Color>, checkered: Boolean, text: String, onChange: (Float) -> Unit, onEnd: () -> Unit, label: @Composable () -> Unit,
) {
    val u = with(LocalDensity.current) { 1.px.toPx() }
    Box(Modifier.fillMaxWidth().height(124.px)) {
        Box(Modifier.width(140.px).fillMaxHeight(), Alignment.Center) { label() }
        Box(Modifier.offset(163.px, 0.dp).size(754.px, 124.px)) {
            Canvas(Modifier.fillMaxSize()) {
                val left = SLIDER_PAD * u
                val top = (size.height - 34 * u) / 2f
                val bar = Size(SLIDER_SPAN * u, 34 * u)
                clipPath(Path().apply { addRoundRect(RoundRect(left, top, left + bar.width, top + bar.height, CornerRadius(17 * u))) }) {
                    if (checkered) checker(Offset(left, top), bar, 17 * u)
                    drawRect(Brush.horizontalGradient(track, left, left + bar.width), Offset(left, top), bar)
                }
                drawCircle(Color.White, 35 * u, Offset(left + value.coerceIn(0f, 1f) * bar.width, size.height / 2f))
            }
            DragArea(Modifier.fillMaxSize().sliderSemantics(value, 0f..1f) { onChange(it); onEnd() }, onEnd) { p -> onChange(((p.x - SLIDER_PAD * u) / (SLIDER_SPAN * u)).coerceIn(0f, 1f)) }
        }
        Box(Modifier.offset(932.px, 0.dp).size(138.px, 124.px), Alignment.CenterEnd) { Text(text, Modifier.padding(end = 9.px), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, softWrap = false) }
    }
}

private fun DrawScope.checker(at: Offset, area: Size, cell: Float) {
    drawRect(Color(0xFFCCCCCC), at, area)
    for (row in 0..(area.height / cell).toInt()) for (col in 0..(area.width / cell).toInt()) {
        if ((row + col) % 2 == 1) drawRect(Color(0xFF888888), at + Offset(col * cell, row * cell), Size(cell, cell))
    }
}

@Composable
internal fun EyedropperLoupe(state: EditorState, modifier: Modifier = Modifier) {
    if (state.eyedropper == null) return
    var box by remember { mutableStateOf(IntSize.Zero) }
    var moved by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun at() = moved ?: Offset(box.width / 2f, box.height / 2f)

    fun sample(last: Boolean) {
        if (box.width == 0 || box.height == 0 || (busy && !last)) {
            if (last) state.endGesture()
            return
        }
        busy = true
        readPreviewPixel(state.previewSurface, at().x / box.width, at().y / box.height) { colour ->
            busy = false
            if (colour != null) state.eyedropper?.invoke(colour)
            if (last) state.endGesture()
        }
    }

    Canvas(
        modifier.onSizeChanged { box = it }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                drag(down.id) { change ->
                    moved = (at() + change.positionChange()).let { Offset(it.x.coerceIn(0f, box.width.toFloat()), it.y.coerceIn(0f, box.height.toFloat())) }
                    change.consume()
                    sample(false)
                }
                sample(true)
            }
        },
    ) {
        val u = 1.dp.toPx()
        val c = at()
        drawCircle(Color.White.copy(alpha = 0.9f), 23 * u, c, style = Stroke(1.5f * u))
        drawLine(Color.White, c - Offset(5 * u, 0f), c + Offset(5 * u, 0f), 1 * u)
        drawLine(Color.White, c - Offset(0f, 5 * u), c + Offset(0f, 5 * u), 1 * u)
    }
}
