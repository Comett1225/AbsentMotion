package app.absentmotion.ui.editor.panels

import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.rememberMediaPicker
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.absentmotion.R
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.keepCopy
import app.absentmotion.editor.FullColor
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.gradient
import app.absentmotion.model.withGradient
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun ColorFillPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val type = layer.fillType
    Box(modifier) {
        FillTabs(state, layer, Modifier.offset(28.px, 37.px).size(856.px, 83.px))
        if (type == "color") ColorPicker(state, layer, Modifier.offset(28.px, 162.px).size(856.px, 359.px))
        if (type == "gradient") GradientPanel(state, layer, Modifier.fillMaxSize())
    }
}

private val TabActive get() = Am.PanelInset

@Composable
private fun FillTabs(state: EditorState, layer: Layer, modifier: Modifier) {
    val type = layer.fillType
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = layer.id
    val picker = rememberMediaPicker { uri ->
        if (uri != null) scope.launch { mediaDeclaration(context, uri)?.let { state.keepCopy(context, it) }?.let { state.fillWithMedia(id, it) } }
    }
    Row(modifier.clip(PanelShape), Arrangement.spacedBy(3.px)) {
        FillTab(PanelIcons.FillNone, stringResource(R.string.none), active = type == "none", enabled = true) { state.editSelected { it.withFill("none") } }
        FillTab(PanelIcons.PaintBucket, stringResource(R.string.color), active = type == "color", enabled = true) { state.editSelected { it.withFill("color") } }
        FillTab(PanelIcons.FillGradient, stringResource(R.string.a11y_gradient), active = type == "gradient", enabled = true) {
            state.editSelected { l -> l.withFill("gradient").let { if (it.node.child("gradient") == null) it.withGradient(it.gradient) else it } }
        }
        val stored = layer.node["fillImage"] != null || layer.node["fillVideo"] != null
        FillTab(PanelIcons.FillMedia, stringResource(R.string.add_tab_media), active = type == "media", enabled = layer.kind == "text" || stored) {
            if (type != "media" && stored) state.editSelected { it.copy(node = it.node.with("fillType", "media")) } else if (layer.kind == "text") picker.launch()
        }
    }
}

private fun EditorState.fillWithMedia(id: Long, media: MediaDeclaration) {
    edit { scene -> scene.withMedia(media).update(id) { it.withMediaFill(media) } }
}

internal fun Layer.withMediaFill(media: MediaDeclaration): Layer {
    val video = media.type.startsWith("video/")
    val end = media.duration / 1000
    val start = node["inTime"]?.toLongOrNull()?.takeIf { video && it in 1 until end }
    return copy(
        node = node.with("fillType", "media").with("fillImage", media.uri.takeUnless { video }).with("fillVideo", media.uri.takeIf { video })
            .with("mediaFillMode", "fill").with("inTime", start?.toString()).with("outTime", if (video) "$end" else null),
    )
}

@Composable
private fun RowScope.FillTab(icon: ImageVector, description: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }.background(if (active) TabActive else Am.Field)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.dimmed()),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) Am.Accent else Am.OnDark
        if (icon == PanelIcons.FillGradient) Canvas(Modifier.size(58.px, 48.px)) {
            val corner = CornerRadius(7.px.toPx())
            drawRoundRect(Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0f))), cornerRadius = corner)
            drawRoundRect(tint, cornerRadius = corner, style = Stroke(1.5.dp.toPx()))
        } else PanelIcon(icon, tint)
    }
}

internal val FixedSwatches = listOf(
    0xE5484D, 0xF08A24, 0xF5D90A, 0x30A46C, 0x12A594, 0x3E63DD, 0x8E4EC6,
    0xFFFFFF, 0xC9CCD1, 0x8B8F97, 0x4A4D55, 0x000000, 0xE93D82, 0x7CE2FE,
)

private val IconGrey get() = Am.Caption

@Composable
internal fun ColorPicker(
    state: EditorState, layer: Layer, modifier: Modifier,
    argb: Int = layer.fillColor, onColor: ((Int, key: String?) -> Unit)? = null,
) {
    var mini by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Hsva?>(null) }
    val hsva = held?.takeIf { it.toArgb() == argb } ?: Hsva.from(argb)
    fun apply(c: Int, drag: Boolean) {
        if (onColor != null) onColor(c, if (drag) "style-colour" else null)
        else state.editSelected(if (drag) "fill-colour" else null) { it.withFill("color", c) }
    }
    fun change(f: (Hsva) -> Hsva) {
        val next = f(hsva)
        held = next
        apply(next.toArgb(), true)
    }
    val latestApply by rememberUpdatedState<(Int, Boolean) -> Unit> { c, drag -> apply(c, drag) }
    val latestArgb by rememberUpdatedState(argb)
    var picking by remember { mutableStateOf(false) }
    if (picking) DisposableEffect(state) {
        state.eyedropper = { rgb -> latestApply((latestArgb and 0xFF000000.toInt()) or (rgb and 0xFFFFFF), true) }
        onDispose { state.eyedropper = null }
    }

    val eyedropper = stringResource(R.string.a11y_eyedropper)
    val sliders = stringResource(R.string.a11y_color_sliders)
    val full = stringResource(R.string.a11y_full_color_picker)
    Box(modifier.clip(PanelShape).background(PanelDark)) {
        if (mini) MiniPicker(hsva, ::change) { state.endGesture() } else PaletteView(state, argb, onColor)
        Column(Modifier.offset(676.px, 0.dp).size(180.px, 359.px).background(PanelDark), Arrangement.spacedBy(3.px)) {
            PickerButton(Modifier.semantics { contentDescription = eyedropper }.clickable { picking = !picking }, if (picking) Am.Accent else Am.PanelInset) {
                if (picking) PanelIcon(PanelIcons.CheckCircle, PanelDark, size = 30.dp) else PanelIcon(PanelIcons.Eyedropper, IconGrey)
            }
            PickerButton(Modifier.semantics { contentDescription = sliders }.clickable { mini = !mini }, if (mini) PanelDark else Am.PanelInset) { MiniPickerIcon(mini) }
            PickerButton(
                Modifier.semantics { contentDescription = full }.clickable { picking = false; state.fullColor = FullColor(argb) { c, drag -> latestApply(c, drag) } }, Am.PanelInset,
            ) { PanelIcon(PanelIcons.Palette, IconGrey) }
        }
    }
}

@Composable
private fun ColumnScope.PickerButton(modifier: Modifier, background: Color, icon: @Composable () -> Unit) {
    Box(modifier.weight(1f).fillMaxWidth().background(background), contentAlignment = Alignment.Center) { icon() }
}

@Composable
private fun MiniPickerIcon(active: Boolean) {
    Canvas(Modifier.size(67.px, 55.px)) {
        val u = 1.px.toPx()
        val line = if (active) Am.Accent else IconGrey
        val half = 1.5f * u
        drawRoundRect(
            if (active) Am.AccentDeep else Am.Inactive, Offset(half, half), Size(size.width - 3 * u, size.height - 3 * u),
            CornerRadius(9 * u),
        )
        drawRoundRect(line, Offset(half, half), Size(size.width - 3 * u, size.height - 3 * u), CornerRadius(9 * u), style = Stroke(3 * u))
        drawCircle(line, 7 * u, Offset(size.width - 20 * u, 18 * u), style = Stroke(3 * u))
    }
}

@Composable
private fun PaletteView(state: EditorState, argb: Int, onColor: ((Int, key: String?) -> Unit)?) {
    val saved = state.settings.savedColours
    val swatches = FixedSwatches + saved
    val rgb = argb and 0xFFFFFF
    val rows = (swatches.size + 6) / 7
    Box(Modifier.size(676.px, 359.px)) {
        Box(Modifier.size(676.px, 73.px).background(Color(argb or 0xFF000000.toInt())), contentAlignment = Alignment.Center) {
            Text(colourText(argb), color = textOn(argb), fontSize = 15.sp, maxLines = 1, softWrap = false)
        }
        if (rgb !in swatches) {
            Box(Modifier.offset(603.px, 0.dp).size(73.px).clickable { state.settings.savedColours = saved + rgb }, contentAlignment = Alignment.Center) {
                PanelIcon(PanelIcons.AddColor, Am.OnDark.copy(alpha = 0.35f), size = 26.dp)
            }
        }
        Box(Modifier.offset(47.px, 81.px).size(591.px, 270.px).verticalScroll(rememberScrollState())) {
            Box(Modifier.size(591.px, (rows * 84 + 8).px)) {
                swatches.forEachIndexed { i, swatch ->
                    Box(
                        Modifier.offset((4f + (i % 7) * 84.33f).px, (8 + (i / 7) * 84).px).size(76.px).clip(RoundedCornerShape(5.dp))
                            .background(Color(swatch or 0xFF000000.toInt()))
                            .clickable {
                                if (onColor != null) onColor((argb and 0xFF000000.toInt()) or swatch, null)
                                else state.editSelected { it.withFill("color", (it.fillColor and 0xFF000000.toInt()) or swatch) }
                            },
                    )
                }
            }
        }
    }
}

internal fun colourText(argb: Int, separator: String = " "): String =
    String.format(Locale.US, "#%06X%s(%d%%)", argb and 0xFFFFFF, separator, ((argb ushr 24) * 100f / 255f).roundToInt())

internal fun textOn(argb: Int): Color {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return if ((0.299f * r + 0.587f * g + 0.114f * b) / 255f > 0.6f) Color.Black else Color.White
}

internal data class Hsva(val h: Float, val s: Float, val v: Float, val a: Float) {
    fun toArgb(): Int = app.absentmotion.platform.Color.HSVToColor((a * 255f).roundToInt(), floatArrayOf(h % 360f, s, v))

    fun opaque(): Color = Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(h % 360f, s, v)))

    companion object {
        fun from(argb: Int): Hsva {
            val hsv = FloatArray(3)
            app.absentmotion.platform.Color.colorToHSV(argb, hsv)
            return Hsva(hsv[0], hsv[1], hsv[2], (argb ushr 24) / 255f)
        }
    }
}

private const val SV_LEFT = 23f
private const val SV_TOP = 22f
private const val SV_WIDTH = 243f
private const val BAR_HEIGHT = 314f
private const val RING_X = 434f
private const val RING_Y = 179f
private const val RING_RADIUS = 129.5f
private const val RING_WIDTH = 33.5f
private const val DISC_RADIUS = 95f
private const val ALPHA_LEFT = 614f
private const val ALPHA_WIDTH = 34f
private const val KNOB_RADIUS = 23f

@Composable
private fun MiniPicker(hsva: Hsva, onChange: ((Hsva) -> Hsva) -> Unit, onEnd: () -> Unit) {
    val u = with(LocalDensity.current) { 1.px.toPx() }
    val colour = hsva.opaque()
    val argb = hsva.toArgb()
    Box(Modifier.size(676.px, 359.px)) {
        Canvas(Modifier.fillMaxSize()) { drawPicker(hsva, colour) }
        Box(Modifier.offset((RING_X - DISC_RADIUS).px, (RING_Y - DISC_RADIUS).px).size((DISC_RADIUS * 2).px), contentAlignment = Alignment.Center) {
            Text(
                colourText(argb, "\n"), color = textOn(argb), fontSize = 11.sp, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        DragArea(Modifier.size(282.px, 359.px), onEnd) { p ->
            onChange {
                it.copy(
                    s = ((p.x - SV_LEFT * u) / (SV_WIDTH * u)).coerceIn(0f, 1f),
                    v = 1f - ((p.y - SV_TOP * u) / (BAR_HEIGHT * u)).coerceIn(0f, 1f),
                )
            }
        }
        DragArea(
            Modifier.offset(282.px, 0.dp).size(304.px, 359.px), onEnd,
            accept = { hypot(it.x - (RING_X - 282f) * u, it.y - RING_Y * u) >= 100f * u },
        ) { p ->
            val degrees = Math.toDegrees(atan2(p.y - RING_Y * u, p.x - (RING_X - 282f) * u).toDouble()).toFloat()
            onChange { it.copy(h = (degrees + 360f) % 360f) }
        }
        DragArea(Modifier.offset(586.px, 0.dp).size(90.px, 359.px), onEnd) { p ->
            onChange { it.copy(a = 1f - ((p.y - SV_TOP * u) / (BAR_HEIGHT * u)).coerceIn(0f, 1f)) }
        }
    }
}

private fun DrawScope.drawPicker(hsva: Hsva, colour: Color) {
    val u = 1.px.toPx()
    val pure = Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(hsva.h % 360f, 1f, 1f)))

    val left = SV_LEFT * u
    val top = SV_TOP * u
    val corner = CornerRadius(3 * u)
    drawRoundRect(Brush.horizontalGradient(listOf(Color.White, pure), left, left + SV_WIDTH * u), Offset(left, top), Size(SV_WIDTH * u, BAR_HEIGHT * u), corner)
    drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), top, top + BAR_HEIGHT * u), Offset(left, top), Size(SV_WIDTH * u, BAR_HEIGHT * u), corner)
    knob(Offset(left + hsva.s * SV_WIDTH * u, top + (1f - hsva.v) * BAR_HEIGHT * u), colour)

    val centre = Offset(RING_X * u, RING_Y * u)
    val hues = List(13) { Color(app.absentmotion.platform.Color.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, 1f))) }
    drawCircle(Brush.sweepGradient(hues, centre), RING_RADIUS * u, centre, style = Stroke(RING_WIDTH * u))
    drawCircle(colour, DISC_RADIUS * u, centre)
    val angle = Math.toRadians(hsva.h.toDouble())
    knob(centre + Offset((RING_RADIUS * u * cos(angle)).toFloat(), (RING_RADIUS * u * sin(angle)).toFloat()), pure)

    val barLeft = ALPHA_LEFT * u
    val bar = Path().apply { addRoundRect(RoundRect(barLeft, top, barLeft + ALPHA_WIDTH * u, top + BAR_HEIGHT * u, CornerRadius(3 * u))) }
    clipPath(bar) {
        drawRect(Color(0xFFCCCCCC), Offset(barLeft, top), Size(ALPHA_WIDTH * u, BAR_HEIGHT * u))
        val cell = 11 * u
        for (row in 0..(BAR_HEIGHT / 11f).toInt()) for (col in 0..(ALPHA_WIDTH / 11f).toInt()) {
            if ((row + col) % 2 == 1) drawRect(Color(0xFF888888), Offset(barLeft + col * cell, top + row * cell), Size(cell, cell))
        }
        drawRect(Brush.verticalGradient(listOf(colour, colour.copy(alpha = 0f)), top, top + BAR_HEIGHT * u), Offset(barLeft, top), Size(ALPHA_WIDTH * u, BAR_HEIGHT * u))
    }
    knob(Offset(barLeft + ALPHA_WIDTH * u / 2f, top + (1f - hsva.a) * BAR_HEIGHT * u), colour)
}

private fun DrawScope.knob(at: Offset, fill: Color) {
    val u = 1.px.toPx()
    drawCircle(Color.Black, KNOB_RADIUS * u, at)
    drawCircle(fill, (KNOB_RADIUS - 1.5f) * u, at)
}

@Composable
internal fun DragArea(modifier: Modifier, onEnd: () -> Unit, accept: (Offset) -> Boolean = { true }, onPoint: (Offset) -> Unit) {
    val point by rememberUpdatedState(onPoint)
    val accepted by rememberUpdatedState(accept)
    val ended by rememberUpdatedState(onEnd)
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (!accepted(down.position)) return@awaitEachGesture
                down.consume()
                point(down.position)
                drag(down.id) { change ->
                    change.consume()
                    point(change.position)
                }
                ended()
            }
        },
    )
}
