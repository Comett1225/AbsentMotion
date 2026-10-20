package app.absentmotion.ui.editor.panels

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.blend
import app.absentmotion.model.withBlend
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am

private class Mode(val id: String, @StringRes val name: Int)

private val Categories: List<Pair<Int, List<Mode>>> = listOf(
    R.string.blend_cat_normal to listOf(Mode("normal", R.string.blend_normal)),
    R.string.blend_cat_darken to listOf(Mode("multiply", R.string.blend_multiply), Mode("darken", R.string.blend_darken), Mode("darker-color", R.string.blend_darker_color), Mode("color-burn", R.string.blend_color_burn), Mode("linear-burn", R.string.blend_linear_burn)),
    R.string.blend_cat_lighten to listOf(Mode("screen", R.string.blend_screen), Mode("lighten", R.string.blend_lighten), Mode("lighter-color", R.string.blend_lighter_color), Mode("color-dodge", R.string.blend_color_dodge), Mode("linear-dodge", R.string.blend_linear_dodge)),
    R.string.blend_cat_contrast to listOf(Mode("overlay", R.string.blend_overlay), Mode("soft-light", R.string.blend_soft_light), Mode("hard-light", R.string.blend_hard_light), Mode("soft-overlay", R.string.blend_soft_overlay), Mode("vivid-light", R.string.blend_vivid_light), Mode("linear-light", R.string.blend_linear_light), Mode("pin-light", R.string.blend_pin_light)),
    R.string.blend_cat_difference to listOf(Mode("diff", R.string.blend_difference), Mode("exclusion", R.string.blend_exclusion), Mode("subtract", R.string.blend_subtract), Mode("divide", R.string.blend_divide)),
    R.string.blend_cat_color to listOf(Mode("hue", R.string.blend_hue), Mode("saturation", R.string.blend_saturation), Mode("color", R.string.blend_color), Mode("luminance", R.string.blend_luminance)),
    R.string.blend_cat_mask to listOf(Mode("mask", R.string.blend_mask), Mode("exclude", R.string.blend_exclude)),
)

private val RowFill get() = Am.Button
private val ValueFill get() = Am.Well
private val SliderMint get() = Am.AccentLit
private val IconGrey get() = Am.Faint

internal val OpacityRef = PropRef.Opacity

@Composable
internal fun BlendingPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val opacity = layer.opacity
    val blend = layer.blend
    val expanded = remember { mutableStateOf(setOf(Categories.firstOrNull { c -> c.second.any { it.id == blend } }?.first ?: R.string.blend_cat_normal)) }
    Box(modifier) {
        PanelIcon(PanelIcons.Opacity, IconGrey, Modifier.offset(19.px, 43.px), size = 56.px)
        OpacitySlider(state, opacity, Modifier.offset(122.px, 14.px).size(560.px, 113.px))
        Box(
            Modifier.offset(734.px, 14.px).size(158.px, 113.px).clip(PanelShape).background(ValueFill)
                .clickable { state.askNumber("", opacity * 100) { v -> state.setNumber(OpacityRef, (v / 100f).coerceIn(0f, 1f)) } },
            contentAlignment = Alignment.Center,
        ) { Text("${Math.round(opacity * 100)}%", color = Am.OnDark, fontSize = 15.5.sp, maxLines = 1, softWrap = false) }
        Column(
            Modifier.offset(0.dp, 145.px).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.px),
            verticalArrangement = Arrangement.spacedBy(18.px),
        ) {
            Categories.forEach { (name, modes) ->
                val open = name in expanded.value
                val current = modes.firstOrNull { it.id == blend }
                Row(
                    Modifier.fillMaxWidth().height(104.px).clip(PanelShape).background(RowFill)
                        .clickable { if (modes.size == 1) state.editSelected { it.withBlend(modes[0].id) } else expanded.value = if (open) expanded.value - name else expanded.value + name },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(if (modes.size > 1) 101.px else 26.px), contentAlignment = Alignment.Center) {
                        if (modes.size > 1) Chevron(open)
                    }
                    Text(stringResource(name), Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp, maxLines = 1, softWrap = false)
                    if (current != null && (!open || modes.size == 1)) {
                        Text(stringResource(current.name), color = Am.Accent, fontSize = 15.sp, maxLines = 1, softWrap = false)
                        Tick()
                    }
                }
                if (open && modes.size > 1) {
                    modes.forEach { mode ->
                        val selected = mode.id == blend
                        Row(
                            Modifier.fillMaxWidth().height(104.px).clip(PanelShape).background(Am.Field).clickable { state.editSelected { it.withBlend(mode.id) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(mode.name), Modifier.padding(start = 101.px).weight(1f), color = if (selected) Am.Accent else Am.OnDark, fontSize = 15.sp, maxLines = 1, softWrap = false)
                            if (selected) Tick()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tick() {
    PanelIcon(PanelIcons.CheckCircle, Am.Accent, Modifier.padding(start = 24.px, end = 40.px), size = 56.px)
}

@Composable
private fun Chevron(open: Boolean) {
    Canvas(Modifier.size(14.dp)) {
        val path = Path().apply {
            if (open) { moveTo(size.width * 0.1f, size.height * 0.3f); lineTo(size.width * 0.9f, size.height * 0.3f); lineTo(size.width * 0.5f, size.height * 0.85f) }
            else { moveTo(size.width * 0.25f, size.height * 0.1f); lineTo(size.width * 0.25f, size.height * 0.9f); lineTo(size.width * 0.85f, size.height * 0.5f) }
            close()
        }
        drawPath(path, Am.OnDarkDim)
    }
}

@Composable
private fun OpacitySlider(state: EditorState, value: Float, modifier: Modifier) {
    Box(
        modifier.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val knob = 17.px.toPx()
                fun apply(x: Float) = state.setNumber(OpacityRef, ((x - knob) / (size.width - 2 * knob).coerceAtLeast(1f)).coerceIn(0f, 1f), "opacity")
                down.consume()
                apply(down.position.x)
                drag(down.id) { change ->
                    change.consume()
                    apply(change.position.x)
                }
                state.endGesture()
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val knob = 17.px.toPx()
            val x = knob + (size.width - 2 * knob) * value
            drawLine(Am.Well, Offset(knob, y), Offset(size.width - knob, y), 6.px.toPx(), StrokeCap.Round)
            drawLine(SliderMint, Offset(knob, y), Offset(x, y), 6.px.toPx(), StrokeCap.Round)
            drawCircle(SliderMint, knob, Offset(x, y))
        }
    }
}
