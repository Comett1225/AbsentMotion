package app.absentmotion.ui.editor.panels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import app.absentmotion.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.model.PropRef
import app.absentmotion.model.formatNumber
import app.absentmotion.ui.theme.Am
import java.util.Locale

internal fun EditorState.setNumber(ref: PropRef, value: Float, key: Any? = null) =
    editSelected(key) { it.withValue(ref, formatNumber(value)) }

internal fun EditorState.setColour(ref: PropRef, argb: Int, key: Any? = null) =
    editSelected(key) { it.withValue(ref, String.format(Locale.US, "#%08x", argb)) }

internal fun EditorState.askNumber(label: String, value: Float, onDone: (Float) -> Unit) {
    keypad = KeypadRequest(listOf(KeypadField(label, value)), 0) { onDone(it[0]) }
}

@Composable
internal fun ThinSwitch(
    on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true,
    offTrack: Color = Am.Well, onTrack: Color = Am.AccentDeep, onThumb: Color = Am.AccentLit, onChange: (Boolean) -> Unit,
) {
    val thumbX by animateDpAsState(if (on) 19.5.dp else 3.5.dp, Motion.fast(), label = "switch thumb")
    Box(modifier.size(47.dp, 27.dp).toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Box(Modifier.offset(7.5.dp, 6.5.dp).size(28.dp, 14.dp).background(if (on) onTrack else offTrack, RoundedCornerShape(7.dp)))
        Box(Modifier.offset(thumbX, 3.5.dp).size(20.dp).shadow(2.dp, CircleShape).background(if (on) onThumb else Am.Thumb, CircleShape))
    }
}

@Composable
internal fun ThinSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, onValueChangeFinished: () -> Unit = {},
    active: Color = Am.AccentLit, inactive: Color = Am.Well, thumb: Color = active,
) {
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    Canvas(modifier.height(32.dp).alpha(if (enabled) 1f else 0.35f).pointerInput(enabled, valueRange) {
        if (!enabled) return@pointerInput
        val r = 6.4.dp.toPx()
        fun at(x: Float) = valueRange.start + ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f) * span
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            change(at(down.position.x))
            drag(down.id) { it.consume(); change(at(it.position.x)) }
            finish()
        }
    }) {
        val r = 6.4.dp.toPx()
        val y = size.height / 2
        val x = r + ((value - valueRange.start) / span).coerceIn(0f, 1f) * (size.width - 2 * r)
        drawLine(inactive, Offset(r, y), Offset(size.width - r, y), 2.1.dp.toPx(), StrokeCap.Round)
        drawLine(active, Offset(r, y), Offset(x, y), 2.1.dp.toPx(), StrokeCap.Round)
        drawCircle(thumb, r, Offset(x, y))
    }
}

@Composable
internal fun PanelSwitch(on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) = ThinSwitch(on, modifier, onChange = onChange)

@Composable
internal fun ColourSwatch(argb: Int, size: Dp, modifier: Modifier = Modifier, round: Boolean = false, onClick: (() -> Unit)? = null) {
    Canvas(modifier.size(size).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        val w = this.size.width
        val inset = if (round) 0f else w * 0.095f
        val box = androidx.compose.ui.geometry.Rect(inset, inset, w - inset, w - inset)
        val shape = Path().apply { if (round) addOval(box) else addRoundRect(androidx.compose.ui.geometry.RoundRect(box, CornerRadius(w * 0.08f))) }
        clipPath(shape) {
            val cell = w / 4
            for (r in 0 until 4) for (c in 0 until 4) {
                drawRect(if ((r + c) % 2 == 0) Color(0xFFCCCCCC) else Color(0xFF888888), Offset(c * cell, r * cell), androidx.compose.ui.geometry.Size(cell, cell))
            }
            drawRect(Color(argb))
            if (!round) drawPath(Path().apply { moveTo(0f, 0f); lineTo(inset + w * 0.27f, 0f); lineTo(0f, inset + w * 0.27f); close() }, Color.White)
        }
        if (!round) drawPath(shape, Color.White, style = Stroke(w * 0.07f))
    }
}

internal val StrokeEnds = listOf(
    "none", "arrow", "t-line", "square", "square-filled", "diamond", "diamond-filled", "arrow-hollow", "arrow-filled",
    "circle-hollow", "circle-filled", "teardrop-hollow", "teardrop-filled",
)

@Composable
internal fun EndGlyph(style: String, tint: Color, size: Dp = 30.dp) {
    Canvas(Modifier.size(size)) { drawEnd(style, tint) }
}

private fun DrawScope.drawEnd(style: String, tint: Color) {
    val r = size.minDimension * 0.2f
    val width = r / 1.5f
    val end = Offset(size.width * 0.5f, size.height / 2)
    fun shape(closed: Boolean, vararg points: Float) = Path().apply {
        moveTo(end.x + points[0] * r, end.y + points[1] * r)
        for (i in 2 until points.size step 2) lineTo(end.x + points[i] * r, end.y + points[i + 1] * r)
        if (closed) close()
    }
    drawLine(tint, Offset(size.width * 0.1f, end.y), end, width, StrokeCap.Round)
    val path = when (style) {
        "arrow" -> shape(false, -1f, -1f, 1f, 0f, -1f, 1f)
        "t-line" -> shape(false, 0f, -1f, 0f, 1f)
        "square", "square-filled" -> shape(true, 1f, 1f, 1f, -1f, -1f, -1f, -1f, 1f)
        "diamond", "diamond-filled" -> shape(true, 1f, 0f, 0f, -1f, -1f, 0f, 0f, 1f)
        "arrow-hollow", "arrow-filled" -> shape(true, 1f, 0f, -1f, -1f, -1f, 1f)
        "circle-hollow", "circle-filled" -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(end, r)) }
        "teardrop-hollow", "teardrop-filled" -> Path().apply {
            moveTo(end.x + 2 * r, end.y)
            arcTo(androidx.compose.ui.geometry.Rect(end, r), 60f, 240f, false)
            close()
        }
        else -> return
    }
    drawPath(path, tint, style = if (style.endsWith("-filled")) Fill else Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Bevel))
}

internal fun child(path: String) = PropRef(PropRef.Kind.Child, path)

internal fun Modifier.at(x: Int, y: Int, w: Int, h: Int): Modifier = offset(x.px, y.px).size(w.px, h.px)
