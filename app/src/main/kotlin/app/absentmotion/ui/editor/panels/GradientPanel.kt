package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.absentmotion.editor.EditorState
import app.absentmotion.model.GradientStyle
import app.absentmotion.model.Layer
import app.absentmotion.model.gradient
import app.absentmotion.model.withGradient
import app.absentmotion.ui.theme.Am

private val Types = listOf("linear", "radial", "sweep")
private val TypeActive get() = Am.PanelInset
private val TypeIdle get() = Am.Field
private val Dark get() = Am.FieldHi

@Composable
internal fun GradientPanel(state: EditorState, layer: Layer, modifier: Modifier) {
    val g = layer.gradient
    var editing by remember { mutableIntStateOf(-1) }
    fun change(next: GradientStyle, key: Any? = null) = state.editSelected(key) { it.withGradient(next) }
    Box(modifier) {
        Row(Modifier.offset(198.px, 205.px).size(513.px, 77.px).clip(PanelShape), Arrangement.spacedBy(3.px)) {
            Types.forEach { t ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(if (t == g.type) TypeActive else TypeIdle).clickable { change(g.copy(type = t)) },
                    contentAlignment = Alignment.Center,
                ) { Canvas(Modifier.size(63.px, 47.px)) { typePicture(t) } }
            }
        }
        Canvas(Modifier.offset(97.px, 384.px).size(718.px, 34.px)) {
            drawRect(Brush.horizontalGradient(listOf(Color(g.startColor), Color(g.endColor))))
        }
        ColourSwatch(g.startColor, 69.px, Modifier.offset(42.px, 367.px)) { editing = 0 }
        ColourSwatch(g.endColor, 69.px, Modifier.offset(801.px, 367.px)) { editing = 1 }
        if (editing >= 0) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { editing = -1 })
            key(editing) {
                ColorPicker(state, layer, Modifier.offset(28.px, 0.px).size(856.px, 359.px), if (editing == 0) g.startColor else g.endColor) { c, key ->
                    change(if (editing == 0) g.copy(startColor = c) else g.copy(endColor = c), key)
                }
            }
        }
    }
}

private fun DrawScope.typePicture(type: String) {
    val u = 1.px.toPx()
    val corner = CornerRadius(6 * u)
    val half = u
    val inner = Size(size.width - 2 * half, size.height - 2 * half)
    val at = Offset(half, half)
    when (type) {
        "linear" -> drawRoundRect(Brush.verticalGradient(listOf(Am.OnDark, Am.Chosen)), at, inner, corner)
        "radial" -> {
            drawRoundRect(Am.OnDark, at, inner, corner)
            drawCircle(Brush.radialGradient(listOf(Dark, Am.OnDark), center, 21 * u), 21 * u, center)
        }
        else -> drawRoundRect(Brush.sweepGradient(listOf(Dark, Am.OnDark), center), at, inner, corner)
    }
    if (type != "radial") drawRoundRect(Am.OnDark, at, inner, corner, style = Stroke(2 * u))
}
