package app.absentmotion.ui.home

import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.library.AppSettings
import app.absentmotion.library.NewProjectChoice
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.presenceScrim
import kotlin.math.roundToInt

private val Ink get() = Am.HomeInk
private val DimText get() = Am.HomeDim
private val ListColor get() = Am.Navy
private val ListLine get() = Am.NavyLine
private val FieldShape = RoundedCornerShape(10.dp)
private const val MIN_SIDE = 32
private const val MAX_SIDE = 4096

private val Resolutions = listOf(
    2160 to "2160p (4k)", 1440 to "1440p (QHD)", 1080 to "1080p (FHD)", 720 to "720p (HD)",
    540 to "540p (SD)", 360 to "360p", 270 to "270p", 180 to "180p",
)
private val FrameRates = listOf(12, 15, 18, 20, 24, 25, 30, 48, 50, 60)

private class BackgroundChoice(val name: String, val argb: Int)

private val Backgrounds = listOf(
    BackgroundChoice("Black", 0xFF000000.toInt()), BackgroundChoice("White", 0xFFFFFFFF.toInt()),
    BackgroundChoice("Light Grey", 0xFFD8D8D8.toInt()), BackgroundChoice("Green", 0xFF00B140.toInt()),
    BackgroundChoice("Blue", 0xFF0047BB.toInt()), BackgroundChoice("Transparent", 0x00000000),
)

private class AspectDef(val key: String, val w: Int, val h: Int, val boxW: Dp, val boxH: Dp)

private val Aspects = listOf(
    AspectDef("16:9", 16, 9, 55.dp, 31.dp), AspectDef("9:16", 9, 16, 31.dp, 55.dp),
    AspectDef("4:5", 4, 5, 31.dp, 39.dp), AspectDef("1:1", 1, 1, 31.dp, 31.dp),
    AspectDef("4:3", 4, 3, 41.dp, 31.dp),
)

private const val CUSTOM = "custom"

private class Form(saved: NewProjectChoice, val titles: List<String>) {
    var element by mutableStateOf(false)
    var name by mutableStateOf(defaultName(false, titles))
    var aspect by mutableStateOf(saved.aspect)
    var resolution by mutableIntStateOf(saved.resolution)
    var customW by mutableStateOf(saved.customWidth.toString())
    var customH by mutableStateOf(saved.customHeight.toString())
    var linked by mutableStateOf(false)
    var fps by mutableIntStateOf(saved.fps)
    var background by mutableIntStateOf(saved.background)
    private var ratio = 1f

    val size: Pair<Int, Int>
        get() {
            if (aspect == CUSTOM) return side(customW) to side(customH)
            val (a, b) = Aspects.firstOrNull { it.key == aspect }?.let { it.w to it.h } ?: (1 to 1)
            val long = ((resolution * maxOf(a, b) / minOf(a, b).toDouble()) / 2).roundToInt() * 2
            return if (a >= b) long to resolution else resolution to long
        }

    fun switchType(on: Boolean) {
        if (on == element) return
        if (name == defaultName(element, titles)) name = defaultName(on, titles)
        element = on
    }

    fun editWidth(text: String) {
        customW = text.filter(Char::isDigit).take(4)
        if (linked) customW.toIntOrNull()?.let { customH = (it / ratio).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE).toString() }
    }

    fun editHeight(text: String) {
        customH = text.filter(Char::isDigit).take(4)
        if (linked) customH.toIntOrNull()?.let { customW = (it * ratio).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE).toString() }
    }

    fun toggleLink() {
        linked = !linked
        if (linked) ratio = side(customW).toFloat() / side(customH)
    }

    fun commitSides() {
        customW = side(customW).toString()
        customH = side(customH).toString()
    }

    fun choice() = NewProjectChoice(aspect, resolution, side(customW), side(customH), fps, background)

    fun scene(): Scene {
        val (w, h) = size
        val title = name.trim().ifEmpty { defaultName(element, titles) }
        val scene = Scene.create(title, w, h, fps, if (element) 0 else background)
        return if (element) Scene(scene.node.with("type", "element")) else scene
    }
}

private fun side(text: String) = text.toIntOrNull()?.coerceIn(MIN_SIDE, MAX_SIDE) ?: MIN_SIDE

private fun defaultName(element: Boolean, titles: List<String>): String {
    if (element) return "New Asset"
    val highest = titles.mapNotNull { Regex("""New Project (\d+)""").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
    return "New Project ${(highest ?: titles.size) + 1}"
}

@Composable
fun NewProjectSheet(
    store: ProjectStore,
    settings: AppSettings,
    existingTitles: List<String>,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
) {
    BackHandler(onBack = onClose)
    val form = remember { Form(settings.newProject, existingTitles) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().presenceScrim(Color.Black.copy(alpha = 0.7f)).pointerInput(Unit) { detectTapGestures { } })
        Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 480.dp).presence(slide = 1f, fade = false)) {
            SheetBody(form, onClose, onCreate = {
                settings.newProject = form.choice()
                val id = store.create(form.scene())
                onClose()
                onOpen(id)
            })
        }
    }
}

@Composable
private fun SheetBody(form: Form, onClose: () -> Unit, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Am.SheetBg)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(30.dp))
        TypeToggle(form)
        Spacer(Modifier.height(35.dp))
        NameField(form)
        Spacer(Modifier.height(10.dp))
        AspectRow(form)
        Spacer(Modifier.height(16.dp))
        SizeRow(form)
        Spacer(Modifier.height(16.dp))
        FormRow("Frame Rate") { FrameRateField(form) }
        Spacer(Modifier.height(16.dp))
        FormRow("Background") { BackgroundField(form) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 32.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.height(48.dp).clickable(onClick = onCreate), contentAlignment = Alignment.Center) {
                Text(if (form.element) "CREATE ASSET" else "CREATE PROJECT", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(52.dp))
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(60.dp).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.Close, "Cancel", Modifier.size(24.dp), tint = Am.Navy)
        }
    }
}

@Composable
private fun TypeToggle(form: Form) {
    Row(
        Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(44.dp).background(Am.Card, FieldShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Segment("PROJECT", !form.element, Modifier.weight(1f)) { form.switchType(false) }
        Segment("ASSET", form.element, Modifier.weight(1f)) { form.switchType(true) }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight()
            .then(if (selected) Modifier.shadow(3.dp, FieldShape).background(Am.Navy, FieldShape) else Modifier)
            .clip(FieldShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Am.OnNavyAccent else Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NameField(form: Form) {
    Box(Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(40.dp)) {
        BasicTextField(
            form.name, { form.name = it },
            Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(start = 4.dp, end = 40.dp),
            textStyle = TextStyle(color = Ink, fontSize = 16.sp), singleLine = true, cursorBrush = SolidColor(Am.Navy),
        )
        Box(Modifier.align(Alignment.TopStart).offset(y = 31.dp).padding(horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Am.HomeMid))
        Box(
            Modifier.align(Alignment.CenterEnd).offset(x = 12.dp).size(48.dp).clickable { form.name = "" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.ClearCircle, "Clear title field", Modifier.size(24.dp), tint = Ink)
        }
    }
}

@Composable
private fun AspectRow(form: Form) {
    Row(Modifier.padding(start = 27.dp).height(56.dp)) {
        Aspects.forEach { a ->
            AspectButton(a.boxW, a.boxH, form.aspect == a.key, onClick = { form.aspect = a.key }) {
                Text(a.key, color = if (form.aspect == a.key) Am.OnAccent else Am.Navy, fontSize = 10.sp, maxLines = 1)
            }
        }
        val custom = form.aspect == CUSTOM
        AspectButton(31.dp, 31.dp, custom, onClick = { form.aspect = CUSTOM }) {
            Icon(HomeIcons.Pencil, "Custom aspect ratio", Modifier.size(16.dp), tint = if (custom) Am.OnAccent else Am.HomeDim)
        }
    }
}

@Composable
private fun AspectButton(w: Dp, h: Dp, selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.height(56.dp).clickable(onClick = onClick).padding(horizontal = 9.25.dp), contentAlignment = Alignment.Center) {
        val frame = Modifier.size(w, h)
        val look = if (selected) {
            val shape = RoundedCornerShape(2.dp)
            Modifier.background(Am.Accent, shape).border(1.dp, Am.Navy, shape)
        } else {
            Modifier.background(Am.Card).corners(Am.Navy)
        }
        Box(frame.then(look), contentAlignment = Alignment.Center, content = content)
    }
}

private fun Modifier.corners(color: Color): Modifier = drawBehind {
    val arm = 5.dp.toPx()
    val t = 1.3.dp.toPx()
    val half = t / 2
    for (left in listOf(true, false)) {
        for (top in listOf(true, false)) {
            val x = if (left) half else size.width - half
            val y = if (top) half else size.height - half
            val dx = if (left) 1f else -1f
            val dy = if (top) 1f else -1f
            drawLine(color, Offset(x - dx * half, y), Offset(x + dx * arm, y), t)
            drawLine(color, Offset(x, y - dy * half), Offset(x, y + dy * arm), t)
        }
    }
}

@Composable
private fun FormRow(label: String, details: List<String> = emptyList(), field: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(45.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(140.dp).align(if (details.isEmpty()) Alignment.CenterVertically else Alignment.Top)
                .wrapContentHeight(Alignment.Top, unbounded = true),
        ) {
            Text(label, color = Am.Navy, fontSize = 16.sp, lineHeight = 22.sp)
            details.forEach { Text(it, color = DimText, fontSize = 10.sp, lineHeight = 13.sp) }
        }
        Box(Modifier.width(180.dp).fillMaxHeight()) { field() }
    }
}

@Composable
private fun SizeRow(form: Form) {
    val (w, h) = form.size
    FormRow("Resolution", listOf("Canvas Size", "$w ⨉ $h")) {
        if (form.aspect == CUSTOM) CustomSize(form) else ResolutionField(form)
    }
}

@Composable
private fun ResolutionField(form: Form) {
    DropdownField(Resolutions.firstOrNull { it.first == form.resolution }?.second ?: "${form.resolution}p") { close ->
        Resolutions.forEachIndexed { i, (value, label) ->
            PickRow(label, divider = i < Resolutions.lastIndex) { form.resolution = value; close() }
        }
    }
}

@Composable
private fun FrameRateField(form: Form) {
    DropdownField("${form.fps} fps") { close ->
        FrameRates.forEachIndexed { i, rate ->
            PickRow("$rate fps", divider = i < FrameRates.lastIndex) { form.fps = rate; close() }
        }
    }
}

@Composable
private fun BackgroundField(form: Form) {
    val shown = if (form.element) Backgrounds.last() else Backgrounds.firstOrNull { it.argb == form.background } ?: Backgrounds.last()
    DropdownField(shown.name, enabled = !form.element, swatch = shown.argb) { close ->
        Backgrounds.forEachIndexed { i, choice ->
            PickRow(choice.name, swatch = choice.argb, divider = i < Backgrounds.lastIndex) { form.background = choice.argb; close() }
        }
    }
}

@Composable
private fun DropdownField(
    text: String,
    enabled: Boolean = true,
    swatch: Int? = null,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.35f)) {
        Row(
            Modifier.fillMaxSize().clip(FieldShape).background(Am.Card).clickable(enabled = enabled) { open = true }
                .padding(start = 16.dp, end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatch != null) {
                Swatch(swatch)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp, maxLines = 1)
            Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.HomeInk)
        }
        RefMenu(open, { open = false }, ListColor, FieldShape, Modifier.width(180.dp), maxHeight = 330.dp) { menu { open = false } }
    }
}

@Composable
private fun PickRow(text: String, swatch: Int? = null, divider: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Swatch(swatch)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Am.OnNavy, fontSize = 16.sp, maxLines = 1)
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(ListLine))
}

@Composable
private fun Swatch(argb: Int) {
    val shape = RoundedCornerShape(5.dp)
    val fill = if (argb ushr 24 == 0) Modifier.checkerboard(3.dp) else Modifier.background(Color(argb))
    Box(Modifier.size(24.dp).clip(shape).then(fill).border(1.dp, Color(0x40808080), shape))
}

@Composable
private fun CustomSize(form: Form) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        NumberField(form.customW, form::editWidth, form::commitSides, Modifier.width(68.dp))
        val tint = if (form.linked) Am.Navy else Am.HomeFaint
        Box(Modifier.size(44.dp).clickable(onClick = form::toggleLink), contentAlignment = Alignment.Center) {
            Box(Modifier.size(27.dp).border(1.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(HomeIcons.Link, "Link", Modifier.size(20.dp), tint = tint)
            }
        }
        NumberField(form.customH, form::editHeight, form::commitSides, Modifier.width(68.dp))
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, onLeave: () -> Unit, modifier: Modifier) {
    BasicTextField(
        value, onChange,
        modifier.fillMaxHeight().clip(FieldShape).background(Am.Card).onFocusChanged { if (!it.isFocused) onLeave() },
        textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp, textAlign = TextAlign.Center),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(Am.Navy),
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() } },
    )
}
