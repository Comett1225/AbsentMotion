package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskSp
import app.absentmotion.ui.adaptive.deskLayout
import app.absentmotion.ui.editor.deskVerticalScroll
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import app.absentmotion.ui.editor.effects.EffectIcons
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FaceState
import app.absentmotion.editor.faceState
import app.absentmotion.editor.pickFace
import app.absentmotion.editor.FullColor
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.effects.PalettePreset
import app.absentmotion.effects.Palettes
import app.absentmotion.model.*
import app.absentmotion.platform.readAtMost
import app.absentmotion.platform.rememberDocumentPicker
import app.absentmotion.ui.editor.pickBlipSound
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.MotionSheet
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.Tile
import app.absentmotion.ui.editor.TileGrid
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.withUiFont
import kotlin.math.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EffectSettings(state: EditorState, layer: Layer, instance: XmlNode, definition: EffectDefinition,
                            active: EffectParam?, select: (EffectParam?) -> Unit, scroll: ScrollState? = null) {
    val index = state.effectIndex
    val displayed = app.absentmotion.ui.editor.linkedNumbers(state, layer, definition.params.map { PropRef.effect(index, it.id) })
    val enabled = definition.available
    val turn = effectTurn(definition, state.effectIndex)
    if (turn != null && enabled) TurnHook(state, turn)
    if (definition.category != "move" && !(definition.native && definition.category == "repeat") && state.scene.hasChildren(layer)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apply_to_children), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
            PanelSwitch(instance.appliesToChildren) { on ->
                if (enabled) state.editSelected { it.withEffect(state.effectIndex) { node -> node.applyingToChildren(on) } }
            }
        }
    }
    if (definition.key == "facetrack") FaceTrackRow(state, layer, instance, enabled)
    val mix = definition.key in PieceEffects && (definition.key != "particles" || instance.effectProperty("depth")?.valueAt(layer.t) == "true")
    if (mix && !deskLayout) MixPiecesRow(state, layer, enabled)
    fun raw(param: EffectParam): String =
        displayed[PropRef.effect(index, param.id)]?.take(param.default.split(',').size)?.joinToString(",") ?: instance.effectProperty(param.id)?.valueAt(layer.t) ?: param.missingDefault
    fun set(param: EffectParam, text: String, gesture: Boolean = false) {
        if (state.selectedId != layer.id || state.effectIndex != index || state.selected?.effects?.getOrNull(index)?.get("id") != instance["id"]) return
        select(param)
        state.editSelected(if (gesture) "effect-$index-${param.id}" else null) { current ->
            current.withEffect(index) { it.withEffectProperty(param.id, param.type, text, current.t, current.snap) }
        }
    }
    val visible = definition.params.filter { definition.shown(it, instance, layer.t) }
    val shown = visible.filter { p -> !p.header && (p.section == null || EffectSections.isOpen(definition.key, p.section)) }
    val cut = rowCut(scroll, "${layer.id}:$index:${definition.id}", shown.count { it.tall })
    CompositionLocalProvider(LocalRowCut provides cut) {
        val drawn = mutableSetOf<String>()
        visible.forEach { param ->
            val section = param.section
            if (section == null) {
                key(param.id) { EffectRow(state, layer, instance, definition, param, raw(param), enabled, active, select, ::set) }
                return@forEach
            }
            if (!drawn.add(section)) return@forEach
            val members = visible.filter { it.section == section }
            val toggle = members.firstOrNull { it.header }
            val open = EffectSections.isOpen(definition.key, section)
            key("section:$section") {
                SectionBar(toggle?.label ?: sectionTitle(section), open, toggle?.let { raw(it) == "true" }, enabled, { on -> toggle?.let { set(it, on.toString()) } }) {
                    EffectSections.toggle(definition.key, section)
                    if (open && active?.section == section) select(null)
                }
            }
            if (open) members.filterNot { it.header }.forEach { member ->
                key(member.id) { EffectRow(state, layer, instance, definition, member, raw(member), enabled, active, select, ::set) }
            }
        }
    }
    if (mix && deskLayout) MixPiecesRow(state, layer, enabled)
}

@Composable
private fun EffectRow(state: EditorState, layer: Layer, instance: XmlNode, definition: EffectDefinition, param: EffectParam, raw: String, enabled: Boolean,
                      active: EffectParam?, select: (EffectParam?) -> Unit, set: (EffectParam, String, Boolean) -> Unit) {
    val keyed = instance.effectProperty(param.id)?.isKeyframed == true
    when (param.kind) {
        "switch" -> Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
            Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
            PanelSwitch(raw == "true") { if (enabled) set(param, it.toString(), false) }
        }
        "color" -> key(layer.id, state.effectIndex, param.id) { EffectColor(state, param, raw, enabled, active == param, keyed, { select(param) }) { text, gesture -> set(param, text, gesture) } }
        "choice" -> if (definition.key == "textblips" && param.id == "voice") BlipVoice(state, param, raw, instance["src"] != null, enabled) { set(param, it, false) }
            else EffectChoice(param, raw, enabled, definition.key) { set(param, it, false) }
        "text" -> if (param.palette) EffectPalette(param, raw, enabled, "${layer.id}:${state.effectIndex}") { set(param, it, false) }
            else EffectText(param, raw, enabled, state::endGesture) { set(param, it, true) }
        else -> {
            val bounded = definition.bounded(param, (layer.endTime - layer.startTime) / 1000f) { p ->
                (instance.effectProperty(p.id)?.valueAt(layer.t) ?: p.missingDefault).finiteFloat() ?: 0f
            }
            val within = if (bounded === param) raw else formatFloats(floatArrayOf((raw.finiteFloat() ?: 0f).coerceIn(bounded.min, bounded.max)))
            EffectNumbers(state, bounded, within, enabled, active == param, { select(param) }, keyed) { text, gesture -> set(param, text, gesture) }
        }
    }
}

private val EffectParam.tall: Boolean get() = kind == "float" || kind == "angle" || kind == "point"

internal object EffectSections {
    private val open = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun isOpen(effect: String, section: String?): Boolean = section == null || open["$effect/$section"] == true

    fun toggle(effect: String, section: String) { open["$effect/$section"] = !isOpen(effect, section) }
}

internal val LocalRowCut = androidx.compose.runtime.compositionLocalOf { 0.dp }

private val MaxRowCut = 8.dp

@Composable
private fun rowCut(scroll: ScrollState?, owner: String, tall: Int): Dp {
    var cut by remember(owner) { mutableStateOf(0.dp) }
    if (scroll == null) return 0.dp
    val density = LocalDensity.current
    val viewport = scroll.viewportSize
    val rows by rememberUpdatedState(tall)
    LaunchedEffect(owner, viewport) {
        if (viewport <= 0) return@LaunchedEffect
        val applied = with(density) { (cut * rows).toPx() }
        val natural = snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE } + applied
        val each = if (rows > 0 && natural > 0f) with(density) { ceil(natural / rows).toDp() } else 0.dp
        cut = if (each <= MaxRowCut) each else 0.dp
    }
    return cut
}

@Composable
private fun sectionTitle(key: String): String = when (key) {
    "range" -> stringResource(R.string.effect_section_range)
    "transform" -> stringResource(R.string.effect_section_transform)
    "visual" -> stringResource(R.string.effect_section_visual)
    "easing" -> stringResource(R.string.effect_section_easing)
    "light" -> stringResource(R.string.effect_section_light)
    "texture" -> stringResource(R.string.effect_section_texture)
    "roughness" -> stringResource(R.string.effect_section_roughness)
    "palette" -> stringResource(R.string.effect_section_palette)
    "back" -> stringResource(R.string.effect_section_back)
    "colorblend" -> stringResource(R.string.effect_section_colorblend)
    "randomization" -> stringResource(R.string.effect_section_randomization)
    else -> key
}

@Composable
private fun SectionBar(title: String, open: Boolean, on: Boolean?, enabled: Boolean, onSwitch: (Boolean) -> Unit, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x40000000)))
        Row(Modifier.fillMaxWidth().height(37.dp).background(Color(0x1F000000))
            .semantics { if (open) collapse { onToggle(); true } else expand { onToggle(); true } }
            .clickable(role = Role.Button, onClick = onToggle).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) EffectIcons.Expanded else EffectIcons.Expand, null, Modifier.size(18.dp), tint = Am.OnDark)
            Text(title, Modifier.weight(1f).padding(start = 8.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (on != null) PanelSwitch(on) { if (enabled) onSwitch(it) }
        }
    }
}

private val PieceEffects = setOf("scatter3d", "fracture3d", "particles")

@Composable
internal fun MixPiecesRow(state: EditorState, layer: Layer, enabled: Boolean = true) {
    if (state.inGroup) return
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.mix_pieces), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
        PanelSwitch(state.scene.mixing(layer)) { on -> if (enabled) state.edit { it.withMixing(layer.id, on) } }
    }
}

@Composable
internal fun PropertyLabel(label: String, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(PanelShape).background(if (active) PanelDark else Am.FieldHi.copy(alpha = 0.3f)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, Modifier.padding(3.dp), color = if (active) Am.Accent else Am.OnDark, fontSize = if (label.length <= 11) 12.sp else 10.sp,
            lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2, style = androidx.compose.ui.text.TextStyle(hyphens = androidx.compose.ui.text.style.Hyphens.Auto))
    }
}

internal fun Modifier.keyframeMark(keyed: Boolean?): Modifier = if (keyed == null) this else drawWithContent {
    drawContent()
    val r = 2.2.dp.toPx()
    val y = 2.5.dp.toPx() + r
    val color = if (keyed) Am.Accent else Am.Caption
    for (i in 0..1) {
        val x = 3.dp.toPx() + r + i * r * 1.35f
        drawPath(Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }, color)
    }
}

@Composable
internal fun EffectNumbers(state: EditorState, param: EffectParam, raw: String, enabled: Boolean, active: Boolean,
                          select: () -> Unit, keyframed: Boolean? = null, set: (String, Boolean) -> Unit) {
    val count = if (param.kind == "point") 2 else 1
    val parsed = raw.split(',').map { it.trim().finiteFloat() ?: 0f }
    fun stored(v: List<Float>) = v.joinToString(",")
    val values = List(count) { parsed.getOrElse(it) { 0f } }
    val defaults = param.default.split(',').map { it.trim().finiteFloat() }
    var component by remember(param.id, count) { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(values)
    val percent = param.percent
    val factor = if (percent) 100f else 1f
    fun adjust(value: Float, gesture: Boolean) {
        val rounded = if (param.step > 0) round(value/param.step)*param.step else value
        val changed = latest.toMutableList().also { it[component] = rounded.coerceIn(param.min, param.max) }
        if (gesture && changed == latest) return
        set(stored(changed), gesture)
    }
    val decimals = param.step.toBigDecimal().stripTrailingZeros().scale().coerceAtLeast(0).let { if (percent) (it - 2).coerceAtLeast(0) else it }
    val unit = if (percent) "%" else if (param.kind == "angle") "\u00BA" else param.unit
    fun keypad(index: Int) {
        state.keypad = KeypadRequest(values.mapIndexed { i, v -> KeypadField(if (count == 1) param.label else listOf("X", "Y")[i], v * factor, unit, param.min * factor, param.max * factor) }, index) { next ->
            set(stored(next.map { v -> (v/factor).coerceIn(param.min, param.max) }), false)
        }
    }
    fun tap(index: Int) { component = index; select() }
    fun type(index: Int) { tap(index); keypad(index) }
    val scalarRuler = count == 1
    val label = Modifier.width(75.dp).height(45.dp).keyframeMark(keyframed)
    Row(Modifier.fillMaxWidth().height(57.dp - LocalRowCut.current)
        .then(if (scalarRuler) Modifier.padding(start = 3.dp) else Modifier.padding(3.dp))
        .alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        PropertyLabel(param.label, active, enabled, label, select)
        if (scalarRuler) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val slider = param.slider
                key(param.id) {
                    if (slider != null) EffectSlider(values[0], slider, defaults.getOrNull(0), enabled, select, { adjust(it, true) }, state::endGesture,
                        Modifier.matchParentSize().padding(end = 58.dp))
                    else EffectRuler(values[0], param.rulerStep, param.min, param.max, enabled,
                        select, { adjust(it, true) }, state::endGesture,
                        Modifier.matchParentSize().padding(end = 45.dp, top = .5.dp, bottom = .5.dp), defaults.getOrNull(0), state.settings.vibration)
                }
                ValueBox(formatFixed(values[0] * factor, decimals) + unit, false, false,
                    Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(56.dp).height(45.dp), 8f, enabled, { type(0) }) { type(0) }
            }
            return@Row
        }
        key(param.id, component) {
            EffectRuler(values[component], param.rulerStep, param.min, param.max, enabled,
                select, { adjust(it, true) }, state::endGesture, Modifier.weight(1f).fillMaxHeight(), defaults.getOrNull(component), state.settings.vibration)
        }
        Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp).fillMaxHeight(), Arrangement.spacedBy(2.dp)) {
            values.forEachIndexed { i, value ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == 0) "X:" else "Y:", Modifier.padding(end = 5.dp), color = Am.Caption, fontSize = deskSp(10f))
                    ValueBox(formatFixed(value * factor, decimals), i == component, active && i == component, Modifier.width(55.dp).fillMaxHeight(), 5.5f, enabled, { type(i) }) { type(i) }
                }
            }
        }
    }
}

@Composable
private fun EffectSlider(value: Float, range: Pair<Float, Float>, default: Float?, enabled: Boolean,
                         onStart: () -> Unit, onValue: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val (low, high) = range
    val current by rememberUpdatedState(value)
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onValue)
    val end by rememberUpdatedState(onEnd)
    val inset = 12.dp
    Canvas(modifier
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(low, high), low..high)
            if (enabled) setProgress { v -> start(); change(v.coerceIn(low, high)); end(); true }
        }
        .pointerInput(enabled, low, high) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val from = current.coerceIn(low, high)
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() } ?: return@awaitEachGesture
                val track = (size.width - 2 * inset.toPx()).coerceAtLeast(1f)
                fun at(x: Float) = (from + (x - down.position.x) / track * (high - low)).coerceIn(low, high)
                start()
                change(at(drag.position.x))
                horizontalDrag(drag.id) { c -> c.consume(); change(at(c.position.x)) }
                end()
            }
        }
        .pointerInput(enabled, default) {
            if (!enabled || default == null) return@pointerInput
            detectTapGestures(onDoubleTap = { start(); change(default.coerceIn(low, high)); end() })
        }
    ) {
        val left = inset.toPx()
        val width = (size.width - 2 * left).coerceAtLeast(1f)
        fun x(v: Float) = left + (v.coerceIn(low, high) - low) / (high - low) * width
        val y = size.height / 2f
        val line = if (enabled) Am.OnDarkDim else Am.Inactive
        drawLine(line, Offset(left, y), Offset(left + width, y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(if (enabled) Am.Accent else Am.Inactive, Offset(x(0f), y), Offset(x(value), y), 3.dp.toPx(), StrokeCap.Round)
        if (default != null) {
            val d = x(default)
            for (side in listOf(-1f, 1f)) for (k in 0..1) {
                val from = y + side * (10.dp.toPx() + k * 4.dp.toPx())
                drawLine(line, Offset(d, from), Offset(d, from + side * 2.5.dp.toPx()), 1.dp.toPx())
            }
        }
        drawCircle(if (enabled) Am.OnDark else Am.Inactive, 8.dp.toPx(), Offset(x(value), y))
    }
}

@Composable
internal fun ValueBox(text: String, chosen: Boolean, lit: Boolean, modifier: Modifier, endPadding: Float, enabled: Boolean, onSelect: () -> Unit = {}, onType: () -> Unit) {
    Box(modifier.clip(PanelShape).background(if (lit) PanelDark else Color(0x33000000)).typeOnDoubleTap(enabled, onSelect = onSelect, onType = onType), contentAlignment = Alignment.CenterEnd) {
        Text(text, Modifier.padding(start = 2.dp, end = endPadding.dp), color = if (chosen) PanelGreen else Am.OnDark, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun BlipVoice(state: EditorState, param: EffectParam, raw: String, picked: Boolean, enabled: Boolean, set: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val index = state.effectIndex
    val layerId = state.selectedId
    val picker = rememberDocumentPicker { uri -> if (uri != null && layerId != null) scope.launch { state.pickBlipSound(context, uri, layerId, index) } }
    val custom = app.absentmotion.audio.TextBlips.CUSTOM
    EffectChoice(param, raw, enabled, "textblips") { next ->
        val chosen = next.finiteFloat()?.roundToInt() == custom
        if (!chosen) set(next) else if (picked && raw.finiteFloat()?.roundToInt() != custom) set(next) else picker.launch()
    }
}

@Composable
private fun EffectChoice(param: EffectParam, raw: String, enabled: Boolean, effect: String, set: (String) -> Unit) {
    val chosen = (raw.finiteFloat() ?: 0f).roundToInt().coerceIn(param.options.indices)
    var open by remember(param.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val pictures by produceState<List<ImageBitmap>?>(null, effect, param.id) {
        value = withContext(Dispatchers.IO) { choicePictures(context, effect, param.id, param.options.size) }
    }
    val sheet = pictures != null
    fun pick(i: Int) = set(formatFloats(floatArrayOf(i.toFloat())))
    Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 8.dp, end = 2.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, Modifier.width(70.dp).padding(end = 4.dp), color = Am.OnDark, maxLines = 2, lineHeight = 13.sp,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(8.sp, 12.sp, 0.5.sp))
        Spacer(Modifier.width(3.dp))
        if (param.options.size <= 3) Row(Modifier.weight(1f).height(30.dp).clip(PanelShape).selectableGroup(), Arrangement.spacedBy(1.dp)) {
            param.options.forEachIndexed { i, option ->
                val on = i == chosen
                Box(Modifier.weight(1f).fillMaxHeight().background(if (on) PanelDark else Am.FieldHi.copy(alpha = 0.3f))
                    .selectable(on, enabled, Role.RadioButton) { if (!on) pick(i) }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(option, color = if (on) Am.Accent else Am.OnDark, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(8.sp, 13.sp, 0.5.sp))
                }
            }
        } else Box(Modifier.weight(1f).height(30.dp)) {
            Row(Modifier.fillMaxSize().clip(PanelShape).background(Color(0x33000000)).clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(param.options[chosen], Modifier.weight(1f), color = Am.Accent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                Icon(app.absentmotion.ui.editor.ChromeIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(16.dp), tint = Am.Accent)
            }
            if (!sheet) Presence(open) {
                MenuPopup(DropOrRise, { open = false }) {
                    Column(Modifier.heightIn(max = 360.dp).deskVerticalScroll()) {
                        param.options.forEachIndexed { i, option -> MenuRow(option) { pick(i); open = false } }
                    }
                }
            }
        }
    }
    if (sheet && open) MotionSheet(param.label, { open = false }) {
        TileGrid(param.options.indices.toList()) { i, modifier ->
            val picture = pictures?.getOrNull(i)
            Tile(param.options[i], i == chosen, modifier, { pick(i) }, picture?.let { p -> { drawCropped(p) } })
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun choicePictures(context: Context, effect: String, param: String, count: Int): List<ImageBitmap>? = runCatching {
    List(count) { i -> context.assets.open("effects/thumb/$effect.$param.$i.webp").use { BitmapFactory.decodeStream(it)!!.asImageBitmap() } }
}.getOrNull()

private fun DrawScope.drawCropped(image: ImageBitmap) {
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val w = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val h = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(image, IntOffset((image.width - w) / 2, (image.height - h) / 2), IntSize(w, h),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.None)
}

@Composable
private fun EffectText(param: EffectParam, raw: String, enabled: Boolean, end: () -> Unit, set: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (focused) end() } }
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, Modifier.padding(end = 12.dp), color = Am.OnDark, fontSize = 14.sp)
        BasicTextField(raw, { if (enabled) set(it.replace('\n', ' ').take(200)) }, Modifier.weight(1f)
            .onFocusChanged { if (focused && !it.isFocused) end(); focused = it.isFocused }.clip(PanelShape).background(Color(0x33000000))
            .padding(horizontal = 10.dp, vertical = 6.dp), enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = Am.OnDark, fontSize = 14.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content).withUiFont(), cursorBrush = SolidColor(Am.Accent))
    }
}

internal const val MaxPaletteBytes = 16 shl 20
internal const val MaxPalettePictureBytes = 64 shl 20
private const val MaxPalettePixels = 2_000_000
private const val MaxSwatchRows = 12

@Composable
private fun EffectPalette(param: EffectParam, raw: String, enabled: Boolean, target: String, set: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = remember(raw) { Palettes.read(raw) }
    val preset = remember(raw) { Palettes.presetOf(raw) }
    var menu by remember(param.id) { mutableStateOf(false) }
    var asked by rememberSaveable(param.id) { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(target)
    val unreadable = stringResource(R.string.effect_palette_unreadable)
    val picker = rememberDocumentPicker { uri ->
        val from = asked
        asked = null
        if (uri != null) scope.launch {
            val picked = withContext(Dispatchers.IO) { readPalette(context, uri) }
            if (from != current) return@launch
            if (picked == null) Toast.makeText(context, unreadable, Toast.LENGTH_LONG).show()
            else set(Palettes.format(picked))
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp).alpha(if (enabled) 1f else 0.35f)) {
        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(param.label, color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            Text(preset?.name ?: pluralStringResource(R.plurals.effect_palette_colors, colors.size, colors.size.toString()), Modifier.weight(1f).padding(horizontal = 8.dp),
                color = Am.OnDarkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PaletteButton(stringResource(R.string.effect_palette_import), enabled) { asked = target; picker.launch() }
            Spacer(Modifier.width(4.dp))
            Box {
                PaletteButton(stringResource(R.string.effect_palette_presets), enabled) { menu = true }
                Presence(menu) {
                    MenuPopup(DropOrRise, { menu = false }) {
                        Column(Modifier.heightIn(max = 320.dp).deskVerticalScroll()) {
                            Palettes.presets.forEach { p -> PalettePresetRow(p, p == preset) { set(Palettes.format(p.colors)); menu = false } }
                        }
                    }
                }
            }
        }
        PaletteSwatches(colors, Modifier.fillMaxWidth().height(36.dp))
    }
}

@Composable
private fun FaceTrackRow(state: EditorState, layer: Layer, instance: XmlNode, enabled: Boolean) {
    val index = state.effectIndex
    val scene = state.scene
    val face = remember(layer.node, instance, scene.fps) { scene.faceState(layer, instance) }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(when (face) {
            FaceState.NotVideo -> R.string.face_tracking_video_only
            FaceState.NoFace -> R.string.face_tracking_none
            FaceState.Partial -> R.string.face_tracking_partial
            FaceState.Ready -> R.string.face_tracking_ready
        }), Modifier.weight(1f).padding(end = 8.dp), color = Am.Caption, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2)
        PaletteButton(stringResource(if (face == FaceState.Partial) R.string.face_tracking_again else R.string.face_tracking_pick), enabled && face != FaceState.NotVideo) {
            state.pickFace(index, again = face == FaceState.Partial)
        }
    }
}

@Composable
private fun PaletteButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(text, Modifier.clip(PanelShape).background(Color(0x33000000)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
}

@Composable
private fun PalettePresetRow(preset: PalettePreset, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(preset.name, Modifier.weight(1f), color = if (chosen) Am.Accent else Am.OnDark, fontSize = 15.sp, maxLines = 1)
        PaletteStrip(preset.colors, Modifier.width(88.dp).height(14.dp).clip(PanelShape))
    }
}

@Composable
internal fun PaletteStrip(colors: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        colors.forEachIndexed { i, rgb ->
            val left = (size.width * i / colors.size).roundToInt()
            val right = (size.width * (i + 1) / colors.size).roundToInt()
            if (right > left) drawRect(Color(rgb or (0xFF shl 24)), Offset(left.toFloat(), 0f), Size((right - left).toFloat(), size.height))
        }
    }
}

internal fun swatchRows(count: Int, width: Float, height: Float): Int {
    var rows = 2
    while (rows < MaxSwatchRows && floor(width * rows / height).toInt() * rows < count) rows++
    return rows
}

@Composable
private fun PaletteSwatches(colors: List<Int>, modifier: Modifier) {
    Canvas(modifier.clip(PanelShape).background(Color(0x33000000)).padding(4.dp)) {
        val rows = swatchRows(colors.size, size.width, size.height)
        val cell = size.height / rows
        val columns = floor(size.width / cell).toInt().coerceAtLeast(1)
        val top = (rows - minOf(rows, (colors.size + columns - 1) / columns)) * cell / 2
        colors.take(columns * rows).forEachIndexed { i, rgb ->
            val left = (i % columns * cell).roundToInt()
            val up = (top + i / columns * cell).roundToInt()
            val right = ((i % columns + 1) * cell).roundToInt()
            val down = (top + (i / columns + 1) * cell).roundToInt()
            drawRect(Color(rgb or (0xFF shl 24)), Offset(left.toFloat(), up.toFloat()), Size((right - left).toFloat(), (down - up).toFloat()))
        }
    }
}

internal fun paletteSource(context: Context, uri: Uri, limit: Int = MaxPaletteBytes): Pair<String, ByteArray>? = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment.orEmpty()
    resolver.openInputStream(uri)?.use { it.readAtMost(limit + 1) }?.takeIf { it.size <= limit }?.let { name to it }
}.getOrNull()

private fun readPalette(context: Context, uri: Uri): List<Int>? = runCatching {
    paletteSource(context, uri)?.let { (name, bytes) -> Palettes.fromFile(name, bytes) ?: pictureColors(bytes) }
}.getOrNull()

internal fun pictureColors(bytes: ByteArray, limit: Int = Palettes.MAX_PICTURE_COLORS): List<Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > MaxPalettePixels) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val (width, height) = bitmap.width to bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()
    return (Palettes.fromStrip(pixels, width, height) ?: Palettes.fromPixels(pixels, limit)).takeIf { it.isNotEmpty() }
}

@Composable
private fun EffectColor(state: EditorState, param: EffectParam, raw: String, enabled: Boolean, active: Boolean, keyed: Boolean, select: () -> Unit, set: (String, Boolean) -> Unit) {
    val color = parseColor(raw) ?: 0xFFFFFFFF.toInt()
    var expanded by remember(param.id) { mutableStateOf(false) }
    var library by remember(param.id) { mutableStateOf(false) }
    val latestColor by rememberUpdatedState(color)
    val latestSet by rememberUpdatedState(set)
    val apply = remember { { rgb: Int -> latestSet(formatColor((latestColor and 0xFF000000.toInt()) or (rgb and 0xFFFFFF)), true) } }
    val picking = state.eyedropper === apply
    val stopPicking = { if (state.eyedropper === apply) state.eyedropper = null }
    DisposableEffect(state) { onDispose(stopPicking) }
    if (library) PaletteSheet(color) { library = false }
    val picker = stringResource(R.string.a11y_full_color_picker)
    val eyedropper = stringResource(R.string.a11y_eyedropper)
    Column {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(3.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
            PropertyLabel(param.label, active, enabled, Modifier.width(75.dp).fillMaxHeight().keyframeMark(keyed), select)
            Box(Modifier.weight(1f).fillMaxHeight().padding(5.dp).clip(PanelShape).background(Color(color))
                .semantics { contentDescription = picker }.clickable(enabled = enabled, role = Role.Button) {
                    select()
                    stopPicking()
                    expanded = false
                    state.fullColor = FullColor(color) { c, drag -> latestSet(formatColor(c), drag) }
                })
            Box(Modifier.size(32.dp).clickable(enabled = enabled, role = Role.Button) { select(); expanded = !expanded }, Alignment.Center) {
                Icon(PanelIcons.Palette, stringResource(R.string.palettes), Modifier.size(20.dp), tint = Am.OnDark)
            }
            Text(raw.uppercase(), Modifier.width(82.dp).typeOnDoubleTap(enabled, onSelect = select) {
                select()
                state.keypad = KeypadRequest(listOf("R", "G", "B", "A").mapIndexed { i, label -> KeypadField(label, ((color ushr intArrayOf(16,8,0,24)[i]) and 255).toFloat(), "", 0f, 255f) }, 0) { next ->
                    var out = 0
                    next.forEachIndexed { i, value -> out = out or (value.roundToInt().coerceIn(0,255) shl intArrayOf(16,8,0,24)[i]) }
                    set(formatColor(out), false)
                }
            }, color = Am.OnDark, fontSize = deskSp(10f), textAlign = TextAlign.Center)
        }
        val rose = remember(param.id) { mutableStateOf(false) }
        val side = remember(rose) { app.absentmotion.ui.editor.DropOrRiseSide(rose) }
        Presence(expanded) { MenuPopup(side, { expanded = false }) {
            val swatches = listOf(0xFF4444,0xF4B446,0xFAE965,0x1ED91E,0x17E1E1,0x3D4CF5,0xDD44DD,0xFFFFFF,0xBEBEBE,0x888888,0x444444,0x000000,0x5EB7D9,0x98C99C)
            @Composable fun Swatches(colours: List<Int>) = colours.chunked(7).forEach { row -> Row(Modifier.fillMaxWidth().height(32.dp).padding(3.dp), Arrangement.spacedBy(2.dp)) {
                row.forEach { rgb -> Box(Modifier.width(30.dp).fillMaxHeight().background(Color(rgb or 0xFF000000.toInt())).clickable(enabled = enabled) { stopPicking(); set(formatColor(rgb or 0xFF000000.toInt()), false); expanded = false }) }
            } }
            val palettes = rememberPaletteLibrary()
            val chip = @Composable { PaletteChip(palettes.chosen, Am.OnDark, Modifier.fillMaxWidth().height(36.dp).padding(3.dp)) { expanded = false; library = true } }
            val own = @Composable { Column(Modifier.heightIn(max = 160.dp).deskVerticalScroll()) { Swatches(palettes.swatches(state.settings.savedColours)) } }
            MenuRow(eyedropper, icon = PanelIcons.Eyedropper, trailing = PanelIcons.Check.takeIf { picking }) {
                expanded = false
                if (picking) { stopPicking(); state.endGesture() } else state.eyedropper = apply
            }
            if (rose.value) { own(); chip(); Swatches(swatches) } else { Swatches(swatches); chip(); own() }
        } }
    }
}
