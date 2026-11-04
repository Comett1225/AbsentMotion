package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskSp
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.effects.PalettePreset
import app.absentmotion.effects.Palettes
import app.absentmotion.model.*
import app.absentmotion.platform.readAtMost
import app.absentmotion.platform.rememberDocumentPicker
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.MotionSheet
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
import kotlin.math.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EffectSettings(state: EditorState, layer: Layer, instance: XmlNode, definition: EffectDefinition,
                            active: EffectParam?, select: (EffectParam) -> Unit) {
    val displayed = app.absentmotion.ui.editor.linkedNumbers(state, layer, definition.params.map { PropRef.effect(state.effectIndex, it.id) })
    val enabled = definition.available
    if (definition.category != "move" && !(definition.native && definition.category == "repeat") && state.scene.hasChildren(layer)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apply_to_children), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
            PanelSwitch(instance.appliesToChildren) { on ->
                if (enabled) state.editSelected { it.withEffect(state.effectIndex) { node -> node.applyingToChildren(on) } }
            }
        }
    }
    val mix = definition.key in PieceEffects && (definition.key != "particles" || instance.effectProperty("depth")?.valueAt(layer.t) == "true")
    if (mix && !app.absentmotion.platform.PlatformFeatures.desktop) MixPiecesRow(state, layer, enabled)
    definition.params.filter { definition.shown(it, instance, layer.t) }.forEach { param ->
        val raw = displayed[PropRef.effect(state.effectIndex, param.id)]?.take(param.default.split(',').size)?.joinToString(",") ?: instance.effectProperty(param.id)?.valueAt(layer.t) ?: param.missingDefault
        fun set(text: String, gesture: Boolean = false) {
            select(param)
            state.editSelected(if (gesture) "effect-${state.effectIndex}-${param.id}" else null) { current ->
                current.withEffect(state.effectIndex) { it.withEffectProperty(param.id, param.type, text, current.t, current.snap) }
            }
        }
        when (param.kind) {
            "switch" -> Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
                Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
                PanelSwitch(raw == "true") { if (enabled) set(it.toString()) }
            }
            "color" -> EffectColor(state, param, raw, enabled, active == param, { select(param) }, { set(it) })
            "choice" -> EffectChoice(param, raw, enabled, definition.key) { set(it) }
            "text" -> if (param.palette) EffectPalette(param, raw, enabled, "${layer.id}:${state.effectIndex}") { set(it) } else EffectText(param, raw, enabled, state::endGesture) { set(it, true) }
            else -> {
                val shown = definition.bounded(param, (layer.endTime - layer.startTime) / 1000f) { p ->
                    (instance.effectProperty(p.id)?.valueAt(layer.t) ?: p.missingDefault).finiteFloat() ?: 0f
                }
                val within = if (shown === param) raw else formatFloats(floatArrayOf((raw.finiteFloat() ?: 0f).coerceIn(shown.min, shown.max)))
                EffectNumbers(state, shown, within, enabled, active == param, { select(param) }, { text, gesture -> set(text, gesture) })
            }
        }
    }
    if (mix && app.absentmotion.platform.PlatformFeatures.desktop) MixPiecesRow(state, layer, enabled)
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
            lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun EffectNumbers(state: EditorState, param: EffectParam, raw: String, enabled: Boolean, active: Boolean,
                          select: () -> Unit, set: (String, Boolean) -> Unit) {
    val count = if (param.kind == "point") 2 else 1
    val parsed = raw.split(',').map { it.trim().finiteFloat() ?: 0f }
    fun stored(v: List<Float>) = v.joinToString(",")
    val values = List(count) { parsed.getOrElse(it) { 0f } }
    val defaults = param.default.split(',').map { it.trim().finiteFloat() }
    var component by remember(param.id) { mutableIntStateOf(0) }
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
    val unit = if (percent) "%" else if (param.kind == "angle") "°" else param.unit
    fun keypad(index: Int) {
        state.keypad = KeypadRequest(values.mapIndexed { i, v -> KeypadField(if (count == 1) param.label else listOf("X", "Y")[i], v * factor, unit, param.min * factor, param.max * factor) }, index) { next ->
            set(stored(next.map { v -> (v/factor).coerceIn(param.min, param.max) }), false)
        }
    }
    fun tap(index: Int) { if (active && component == index) keypad(index) else { component = index; select() } }
    val scalarRuler = count == 1
    Row(Modifier.fillMaxWidth().height(57.dp)
        .then(if (scalarRuler) Modifier.padding(start = 3.dp) else Modifier.padding(3.dp))
        .alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        PropertyLabel(param.label, active, enabled, Modifier.width(75.dp).height(45.dp), select)
        if (scalarRuler) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                key(param.id) {
                    EffectRuler(values[0], param.step / param.slow, param.min, param.max, enabled,
                        select, { adjust(it, true) }, state::endGesture,
                        Modifier.matchParentSize().padding(end = 45.dp, top = .5.dp, bottom = .5.dp), defaults.getOrNull(0), state.settings.vibration)
                }
                ValueBox(formatFixed(values[0] * factor, decimals) + unit, false, false,
                    Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(56.dp).height(45.dp), 8f, enabled) { tap(0) }
            }
            return@Row
        }
        key(param.id, component) {
            EffectRuler(values[component], param.step / param.slow, param.min, param.max, enabled,
                select, { adjust(it, true) }, state::endGesture, Modifier.weight(1f).fillMaxHeight(), defaults.getOrNull(component), state.settings.vibration)
        }
        Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp).fillMaxHeight(), Arrangement.spacedBy(2.dp)) {
            values.forEachIndexed { i, value ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == 0) "X:" else "Y:", Modifier.padding(end = 5.dp), color = Am.Caption, fontSize = deskSp(10f))
                    ValueBox(formatFixed(value * factor, decimals), i == component, active && i == component, Modifier.width(55.dp).fillMaxHeight(), 5.5f, enabled) { tap(i) }
                }
            }
        }
    }
}

@Composable
internal fun ValueBox(text: String, chosen: Boolean, lit: Boolean, modifier: Modifier, endPadding: Float, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier.clip(PanelShape).background(if (lit) PanelDark else Color(0x33000000)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.CenterEnd) {
        Text(text, Modifier.padding(start = 2.dp, end = endPadding.dp), color = if (chosen) PanelGreen else Am.OnDark, fontSize = 14.sp, maxLines = 1, softWrap = false)
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
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
        Box {
            Text(param.options[chosen] + if (sheet) "  \u203A" else "", Modifier.clip(PanelShape).background(Color(0x33000000)).clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            if (!sheet) Presence(open) {
                MenuPopup(DropOrRise, { open = false }) {
                    param.options.forEachIndexed { i, option -> MenuRow(option) { set(formatFloats(floatArrayOf(i.toFloat()))); open = false } }
                }
            }
        }
    }
    if (sheet && open) MotionSheet(param.label, { open = false }) {
        TileGrid(param.options.indices.toList()) { i, modifier ->
            val picture = pictures?.getOrNull(i)
            Tile(param.options[i], i == chosen, modifier, { set(formatFloats(floatArrayOf(i.toFloat()))) }, picture?.let { p -> { drawCropped(p) } })
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
            textStyle = TextStyle(color = Am.OnDark, fontSize = 14.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content), cursorBrush = SolidColor(Am.Accent))
    }
}

internal const val MaxPaletteBytes = 16 shl 20
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

internal fun paletteSource(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment.orEmpty()
    resolver.openInputStream(uri)?.use { it.readAtMost(MaxPaletteBytes + 1) }?.takeIf { it.size <= MaxPaletteBytes }?.let { name to it }
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
private fun EffectColor(state: EditorState, param: EffectParam, raw: String, enabled: Boolean, active: Boolean, select: () -> Unit, set: (String) -> Unit) {
    val color = parseColor(raw) ?: 0xFFFFFFFF.toInt()
    var expanded by remember(param.id) { mutableStateOf(false) }
    var library by remember(param.id) { mutableStateOf(false) }
    if (library) PaletteSheet(color) { library = false }
    Column {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(3.dp).alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
            PropertyLabel(param.label, active, enabled, Modifier.width(75.dp).fillMaxHeight(), select)
            Box(Modifier.weight(1f).fillMaxHeight().padding(5.dp).clip(PanelShape).background(Color(color)).clickable(enabled = enabled) { select(); expanded = !expanded })
            Text(raw.uppercase(), Modifier.width(82.dp).clickable(enabled = enabled) {
                select()
                state.keypad = KeypadRequest(listOf("R", "G", "B", "A").mapIndexed { i, label -> KeypadField(label, ((color ushr intArrayOf(16,8,0,24)[i]) and 255).toFloat(), "", 0f, 255f) }, 0) { next ->
                    var out = 0
                    next.forEachIndexed { i, value -> out = out or (value.roundToInt().coerceIn(0,255) shl intArrayOf(16,8,0,24)[i]) }
                    set(formatColor(out))
                }
            }, color = Am.OnDark, fontSize = deskSp(10f), textAlign = TextAlign.Center)
        }
        val rose = remember(param.id) { mutableStateOf(false) }
        val side = remember(rose) { app.absentmotion.ui.editor.DropOrRiseSide(rose) }
        Presence(expanded) { MenuPopup(side, { expanded = false }) {
            val swatches = listOf(0xFF4444,0xF4B446,0xFAE965,0x1ED91E,0x17E1E1,0x3D4CF5,0xDD44DD,0xFFFFFF,0xBEBEBE,0x888888,0x444444,0x000000,0x5EB7D9,0x98C99C)
            @Composable fun Swatches(colours: List<Int>) = colours.chunked(7).forEach { row -> Row(Modifier.fillMaxWidth().height(32.dp).padding(3.dp), Arrangement.spacedBy(2.dp)) {
                row.forEach { rgb -> Box(Modifier.width(30.dp).fillMaxHeight().background(Color(rgb or 0xFF000000.toInt())).clickable(enabled = enabled) { set(formatColor(rgb or 0xFF000000.toInt())); expanded = false }) }
            } }
            val palettes = rememberPaletteLibrary()
            val chip = @Composable { PaletteChip(palettes.chosen, Am.OnDark, Modifier.fillMaxWidth().height(36.dp).padding(3.dp)) { expanded = false; library = true } }
            val own = @Composable { Column(Modifier.heightIn(max = 160.dp).deskVerticalScroll()) { Swatches(palettes.swatches(state.settings.savedColours)) } }
            if (rose.value) { own(); chip(); Swatches(swatches) } else { Swatches(swatches); chip(); own() }
        } }
    }
}
