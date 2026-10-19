package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.StyleColors
import app.absentmotion.model.borders
import app.absentmotion.model.formatNumber
import app.absentmotion.model.parseColor
import app.absentmotion.model.shadow
import app.absentmotion.model.stroke
import app.absentmotion.model.valueAt
import app.absentmotion.model.withBorderAdded
import app.absentmotion.model.withBorderDirection
import app.absentmotion.model.withBorderMoved
import app.absentmotion.model.withBorderRemoved
import app.absentmotion.model.withShadowEnabled
import app.absentmotion.model.withStrokeAttr
import app.absentmotion.model.withStrokeEnabled
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am

private var tab by mutableIntStateOf(0)
private var colourEditing by mutableStateOf<PropRef?>(null)
private var colourDefault = StyleColors.Black
private var openBorder by mutableIntStateOf(-1)
private var strokeField by mutableIntStateOf(0)
private var borderField by mutableIntStateOf(0)
private var shadowField by mutableIntStateOf(0)
private var endPicker by mutableIntStateOf(-1)

private fun resetBorderShadow() {
    tab = 0
    colourEditing = null
    colourDefault = StyleColors.Black
    openBorder = -1
    strokeField = 0
    borderField = 0
    shadowField = 0
    endPicker = -1
}

@Composable
internal fun BorderShadowLifetime(owner: Any, layerId: Long, panel: app.absentmotion.editor.EditorPanel) {
    DisposableEffect(owner, layerId, panel) {
        resetBorderShadow()
        onDispose { resetBorderShadow() }
    }
}

private val StrokeSize = child("path-stroke/size")
private val StrokeColour = child("path-stroke/color")
private val ShadowColour = child("shadow/color")
private val ShadowRefs = listOf(ShadowColour, child("shadow/size"), child("shadow/opacity"), child("shadow/offset"))
private val ShadowTargets = listOf(KeyTarget(ShadowColour, "#ff000000", "color"), KeyTarget(ShadowRefs[1], "4.000000"),
    KeyTarget(ShadowRefs[2], "1.000000"), KeyTarget(ShadowRefs[3], "3.000000,3.000000", "vec2"))

private fun spinner(label: String, step: String, max: String? = null, type: String? = null) =
    EffectParam(label, label, "float", "0", 0f, max?.toFloat() ?: Float.MAX_VALUE, step.toFloat(), percent = type == "percent")
private val SizeParam = spinner("Size", "1")
private val AlphaParam = spinner("Alpha", "0.01", "1", "percent")
private val PositionParam = EffectParam("Position", "Position", "point", "0,0", step = 1f)

private val CardBg get() = Am.Field
private val ButtonBg get() = Am.PanelInset

internal fun borderShadowTarget(layer: Layer): KeyTarget? {
    colourEditing?.let { return KeyTarget(it, String.format(java.util.Locale.US, "#%08x", colourDefault), "color") }
    return when (tab) {
        0 -> if (layer.stroke.present) (if (strokeField == 1) KeyTarget(StrokeColour, "#ff000000", "color") else KeyTarget(StrokeSize, "4.000000")) else null
        1 -> layer.borders.getOrNull(openBorder)?.let { if (borderField == 0) KeyTarget(it.colorRef, "#ffffffff", "color") else KeyTarget(it.sizeRef, "6.000000") }
        else -> if (layer.shadow.present) ShadowTargets[shadowField] else null
    }
}

@Composable
internal fun BorderShadowTabs(modifier: Modifier) {
    val usable = colourEditing == null
    Column(modifier.then(if (usable) Modifier else Modifier.dimmed())) {
        for (i in 0..2) {
            Box(Modifier.weight(1f).fillMaxWidth().then(if (usable) Modifier.clickable { tab = i; endPicker = -1 } else Modifier).padding(10.px, 11.px, 11.px, 11.px)) {
                Box(Modifier.fillMaxSize().clip(PanelShape).then(if (tab == i) Modifier.background(PanelDark) else Modifier), contentAlignment = Alignment.Center) {
                    PanelIcon(TabIcons[i], if (tab == i) Am.Accent else Am.OnDark)
                }
            }
        }
    }
}

private val TabIcons = listOf(PanelIcons.TabStroke, PanelIcons.TabBorder, PanelIcons.BorderShadow)

@Composable
internal fun BorderShadowPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    Box(modifier) {
        val editing = colourEditing
        if (editing != null) ColourEditor(state, layer, editing)
        else Box(Modifier.width(744.px).fillMaxHeight()) {
            when {
                tab == 0 -> if (endPicker >= 0) EndPicker(state, layer) else StrokeTab(state, layer)
                tab == 1 -> BorderTab(state, layer)
                else -> ShadowTab(state, layer)
            }
        }
    }
}

private fun colourOf(layer: Layer, ref: PropRef, default: Int): Int = parseColor(layer.element(ref)?.valueAt(layer.t)) ?: default

private fun editColour(ref: PropRef, default: Int) {
    colourEditing = ref
    colourDefault = default
}

private fun EditorState.setRow(ref: PropRef, text: String, gesture: Boolean) =
    editSelected(if (gesture) ref.name else null) { it.withValue(ref, text.split(',').joinToString(",") { v -> formatNumber(v.toFloatOrNull() ?: 0f) }) }

@Composable
private fun ColourEditor(state: EditorState, layer: Layer, ref: PropRef) {
    val argb = colourOf(layer, ref, colourDefault)
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.offset(28.px, 37.px).size(856.px, 83.px).clip(PanelShape).background(Am.Field), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(83.px).clickable { colourEditing = null }, contentAlignment = Alignment.Center) {
                PanelIcon(PanelIcons.Check, Am.Accent)
            }
            Text("Color", color = Am.OnDark, fontSize = 15.sp, maxLines = 1, softWrap = false)
        }
        ColorPicker(state, layer, Modifier.offset(28.px, 162.px).size(856.px, 359.px), argb) { c, key -> state.setColour(ref, c, key) }
    }
}

@Composable
private fun TitleRow(title: String, on: Boolean, swatch: Int?, onSwatch: () -> Unit, onSwitch: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().height(105.px)) {
        if (swatch != null) ColourSwatch(swatch, 69.px, Modifier.offset(36.px, 18.px), onClick = onSwatch)
        Text(title, Modifier.offset((if (swatch != null) 130 else 36).px, 22.px), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, softWrap = false)
        PanelSwitch(on, Modifier.offset(575.px, 15.px), onSwitch)
    }
}

@Composable
private fun ColourRow(argb: Int, active: Boolean, select: () -> Unit, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        PropertyLabel("Color", active, true, Modifier.width(75.dp).fillMaxHeight(), select)
        Spacer(Modifier.weight(1f))
        Text("${(argb shr 16) and 255} ${(argb shr 8) and 255} ${argb and 255}", Modifier.clickable { select(); open() },
            color = Am.Caption, fontSize = 9.sp, maxLines = 1, softWrap = false)
        ColourSwatch(argb, 69.px, Modifier.padding(start = 23.px, end = 29.px)) { select(); open() }
    }
}

@Composable
private fun StrokeTab(state: EditorState, layer: Layer) {
    val s = layer.stroke
    val on = s.present && s.enabled
    Box(Modifier.fillMaxSize()) {
        TitleRow("Stroke", on, s.color, { strokeField = 1; if (s.present) editColour(StrokeColour, StyleColors.Black) }) {
            state.editSelected { l -> l.withStrokeEnabled(it) }
        }
        Box(Modifier.at(0, 119, 721, 160)) {
            EffectRuler(s.width, 0.1f, 0f, 10000f, s.present, { strokeField = 0 }, { state.setRow(StrokeSize, "$it", true) }, state::endGesture,
                Modifier.fillMaxSize().padding(end = 104.px))
            ValueBox(formatFixed(s.width, 1), false, strokeField == 0, Modifier.align(Alignment.CenterEnd).size(155.px, 127.px), 8f, s.present) {
                if (strokeField == 0) state.askNumber("", s.width) { v -> state.setRow(StrokeSize, "${v.coerceAtLeast(0f)}", false) } else strokeField = 0
            }
        }
        val caps = listOf("butt", "round", "square")
        val joins = listOf("bevel", "round", "miter")
        Choices(3, app.absentmotion.model.strokeCapIndex(s.cap), Modifier.at(23, 302, 338, 84), s.present, { state.editSelected { l -> l.withStrokeAttr("cap", caps[it]) } }) { i, lit -> capGlyph(i, lit) }
        Choices(3, joins.indexOf(s.join).coerceAtLeast(0), Modifier.at(383, 302, 338, 84), s.present, { state.editSelected { l -> l.withStrokeAttr("join", joins[it]) } }) { i, lit -> joinGlyph(i, lit) }
        for (end in 0..1) {
            Box(
                Modifier.at(if (end == 0) 23 else 383, 409, 338, 84).clip(PanelShape).background(ButtonBg)
                    .then(if (s.present) Modifier.clickable { endPicker = end } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val u = 1.px.toPx()
                    val x = if (end == 0) size.width - 8 * u else 8 * u
                    val dx = if (end == 0) -10 * u else 10 * u
                    drawPath(Path().apply { moveTo(x, 8 * u); lineTo(x + dx, 8 * u); lineTo(x, 18 * u); close() }, Am.Caption)
                }
                val style = if (end == 0) s.start else s.end
                if (style == "none") Text(if (end == 0) "Start" else "End", color = Am.OnDark, fontSize = 16.sp, maxLines = 1, softWrap = false)
                else EndGlyph(style, Am.OnDark, 40.dp)
            }
        }
    }
}

@Composable
private fun Choices(count: Int, selected: Int, modifier: Modifier, enabled: Boolean, onPick: (Int) -> Unit, glyph: DrawScope.(Int, Boolean) -> Unit) {
    Row(modifier.clip(PanelShape).background(ButtonBg), Arrangement.spacedBy(3.px)) {
        for (i in 0 until count) {
            Canvas(
                Modifier.weight(1f).fillMaxHeight().clip(PanelShape).background(if (i == selected) CardBg else ButtonBg)
                    .then(if (enabled) Modifier.clickable { onPick(i) } else Modifier),
            ) { glyph(i, i == selected) }
        }
    }
}

private val GlyphFill get() = Am.OnDark
private val GlyphEdge get() = Am.Caption
private val GlyphLine get() = Am.Faint
private val GlyphLit get() = Am.Accent

private fun DrawScope.node(at: Offset, lit: Boolean, u: Float) {
    drawCircle(GlyphFill, 7.5f * u, at)
    drawCircle(if (lit) GlyphLit else GlyphEdge, 7.5f * u, at, style = Stroke(2 * u))
}

private fun DrawScope.capGlyph(cap: Int, lit: Boolean) {
    val u = 1.px.toPx()
    val top = 20 * u; val bottom = 65 * u; val right = 98 * u; val h = (bottom - top) / 2
    val nodeX = (if (cap == 0) 34 else 46) * u
    val y = (top + bottom) / 2
    val left = nodeX - if (cap == 0) 0f else h
    val body = Path().apply {
        moveTo(right, top)
        if (cap == 1) { lineTo(nodeX, top); arcTo(androidx.compose.ui.geometry.Rect(left, top, nodeX + h, bottom), 270f, -180f, false); lineTo(right, bottom) }
        else { lineTo(left, top); lineTo(left, bottom); lineTo(right, bottom) }
    }
    drawPath(body, GlyphFill)
    drawPath(body, if (lit) GlyphLit else GlyphEdge, style = Stroke(3 * u))
    drawLine(if (lit) GlyphLit else GlyphLine, Offset(nodeX, y), Offset(right - 4 * u, y), 2 * u)
    node(Offset(nodeX, y), lit, u)
}

private fun DrawScope.joinGlyph(join: Int, lit: Boolean) {
    val u = 1.px.toPx()
    val left = 26 * u; val top = 14 * u; val right = 82 * u; val bottom = size.height; val k = 26 * u
    val body = Path().apply {
        moveTo(left, bottom)
        when (join) {
            0 -> { lineTo(left, top + k); lineTo(left + k, top) }
            1 -> { lineTo(left, top + k); quadraticTo(left, top, left + k, top) }
            else -> lineTo(left, top)
        }
        lineTo(right, top)
    }
    drawPath(Path().apply { addPath(body); lineTo(right, bottom); close() }, GlyphFill)
    drawPath(body, if (lit) GlyphLit else GlyphEdge, style = Stroke(3 * u))
    val n = Offset(55 * u, 43.5f * u)
    val line = if (lit) GlyphLit else GlyphLine
    drawLine(line, n, Offset(right, n.y), 2 * u)
    drawLine(line, n, Offset(n.x, bottom), 2 * u)
    node(n, lit, u)
}

@Composable
private fun EndPicker(state: EditorState, layer: Layer) {
    val s = layer.stroke
    val which = endPicker
    val current = if (which == 0) s.start else s.end
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clickable { endPicker = -1 }, contentAlignment = Alignment.Center) { PanelIcon(PanelIcons.Back, Am.OnDark) }
            Text(if (which == 0) "Start" else "End", Modifier.padding(start = 8.dp).weight(1f), color = Am.OnDark, fontSize = 15.sp)
        }
        EffectNumbers(state, spinner("Size", "0.1"), "${s.endSize}", true, true, {}) { text, gesture ->
            state.editSelected(if (gesture) "end-size" else null) { it.withStrokeAttr("end-size", formatNumber((text.toFloatOrNull() ?: 1.5f).coerceAtLeast(0f))) }
        }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StrokeEnds.forEach { style ->
                Box(
                    Modifier.size(52.dp, 50.dp).clip(PanelShape).background(if (style == current) CardBg else ButtonBg)
                        .clickable { state.editSelected { it.withStrokeAttr(if (which == 0) "start" else "end", style) } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (style == "none") Text("None", color = if (style == current) Am.Accent else Am.OnDark, fontSize = 11.sp) else EndGlyph(style, if (style == current) Am.Accent else Am.OnDark)
                }
            }
        }
    }
}

private val Directions = listOf("inside", "centered", "outside")
private val DirectionLabels = listOf("Inside", "Center", "Outside")

@Composable
private fun BorderTab(state: EditorState, layer: Layer) {
    val borders = layer.borders
    val open = borders.getOrNull(openBorder)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.px), verticalArrangement = Arrangement.spacedBy(18.px)) {
        if (open != null) {
            Column(Modifier.fillMaxWidth().clip(PanelShape).background(CardBg).padding(bottom = 8.px)) {
                Row(Modifier.fillMaxWidth().height(104.px).clickable { openBorder = -1 }, verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.padding(start = 36.px).size(51.px)) { triangle(down = true) }
                    Text("Border", Modifier.padding(start = 30.px).weight(1f), color = Am.OnDark, fontSize = 16.sp, maxLines = 1)
                    Box(Modifier.size(101.px).clickable { state.editSelected { it.withBorderRemoved(open.index) }; openBorder = -1 }, contentAlignment = Alignment.Center) {
                        PanelIcon(ChromeIcons.Trash, Am.OnDark, size = 28.dp)
                    }
                }
                Row(Modifier.padding(start = 8.px, end = 8.px, top = 15.px).fillMaxWidth().height(77.px), Arrangement.spacedBy(3.px)) {
                    Directions.forEachIndexed { i, d ->
                        val chosen = open.direction == d
                        Box(Modifier.weight(1f).fillMaxHeight().background(if (chosen) Am.Chosen else Am.FieldHi)
                            .clickable { state.editSelected { it.withBorderDirection(open.index, d) } }, contentAlignment = Alignment.Center) {
                            Text(DirectionLabels[i].uppercase(), color = if (chosen) Am.Accent else Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.height(15.px))
                ColourRow(open.color, borderField == 0, { borderField = 0 }) { editColour(open.colorRef, StyleColors.White) }
                EffectNumbers(state, SizeParam, "${open.size}", true, borderField == 1, { borderField = 1 }) { text, gesture -> state.setRow(open.sizeRef, text, gesture) }
            }
            return@Column
        }
        borders.forEach { b ->
            Row(
                Modifier.fillMaxWidth().height(104.px).clip(PanelShape).background(CardBg).clickable { openBorder = b.index; borderField = 0 },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.padding(start = 36.px).size(51.px)) { triangle(down = false) }
                Text(formatPlain(b.size) + "px", Modifier.padding(start = 28.px).width(78.px), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, softWrap = false)
                ColourSwatch(b.color, 56.px, Modifier.padding(start = 28.px), round = true)
                Text(DirectionLabels[Directions.indexOf(b.direction).coerceAtLeast(0)], Modifier.padding(start = 28.px).weight(1f), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, softWrap = false)
                Canvas(Modifier.size(84.px).clickable { state.editSelected { it.withBorderMoved(b.index, b.index - 1) } }) {
                    val u = 1.px.toPx()
                    for (i in -1..1) drawLine(Am.Inactive, Offset(size.width / 2 - 22 * u, size.height / 2 + i * 9 * u), Offset(size.width / 2 + 22 * u, size.height / 2 + i * 9 * u), 2.5f * u)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(104.px).clip(PanelShape).background(ButtonBg)
                .clickable { state.editSelected { it.withBorderAdded() }; openBorder = borders.size; borderField = 0 },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIcon(ChromeIcons.Plus, Am.OnDark, size = 16.dp)
            Text("Add Border", Modifier.padding(start = 18.px), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, softWrap = false)
        }
    }
}

private fun DrawScope.triangle(down: Boolean) {
    val w = size.width
    val p = if (down) Path().apply { moveTo(w * 0.15f, w * 0.3f); lineTo(w * 0.85f, w * 0.3f); lineTo(w * 0.5f, w * 0.75f); close() }
    else Path().apply { moveTo(w * 0.3f, w * 0.15f); lineTo(w * 0.75f, w * 0.5f); lineTo(w * 0.3f, w * 0.85f); close() }
    drawPath(p, Am.OnDark)
}

@Composable
private fun ShadowTab(state: EditorState, layer: Layer) {
    val s = layer.shadow
    Column(Modifier.fillMaxSize()) {
        TitleRow("Shadow", s.present && s.enabled, null, {}) { on -> state.editSelected { it.withShadowEnabled(on) } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(if (s.present) Modifier else Modifier.dimmed())) {
            fun select(i: Int) = { shadowField = i }
            ColourRow(s.color, shadowField == 0, select(0)) { if (s.present) editColour(ShadowColour, StyleColors.Black) }
            EffectNumbers(state, SizeParam, "${s.size}", s.present, shadowField == 1, select(1)) { text, g -> state.setRow(ShadowRefs[1], text, g) }
            EffectNumbers(state, AlphaParam, "${s.opacity}", s.present, shadowField == 2, select(2)) { text, g -> state.setRow(ShadowRefs[2], text, g) }
            EffectNumbers(state, PositionParam, "${s.x},${s.y}", s.present, shadowField == 3, select(3)) { text, g -> state.setRow(ShadowRefs[3], text, g) }
        }
    }
}
