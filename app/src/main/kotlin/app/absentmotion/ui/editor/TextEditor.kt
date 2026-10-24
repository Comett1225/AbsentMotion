package app.absentmotion.ui.editor

import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import app.absentmotion.model.*
import app.absentmotion.ui.editor.panels.ColorPicker
import app.absentmotion.ui.editor.panels.ColourSwatch
import app.absentmotion.ui.editor.panels.ThinSlider
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.editor.panels.askNumber
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.enterFade
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val TextControl get() = Am.Button
private val TextSizes = listOf(5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 96, 112, 144)

@Composable
internal fun TextEditor(state: EditorState) {
    val layer = state.selected ?: return
    val context = LocalContext.current
    val library = remember { state.engine.fonts ?: FontLibrary(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    var popup by remember { mutableStateOf("") }
    var browser by remember { mutableStateOf(false) }
    val finish = { state.endGesture(); keyboard?.hide(); state.panel = EditorPanel.Tools }
    LaunchedEffect(layer.id) { state.playing = false }
    BackHandler { if (popup.isNotEmpty()) popup = "" else finish() }
    DisposableEffect(layer.id) { onDispose { state.endGesture() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding().imePadding()) {
        if (browser) {
            Box(Modifier.fillMaxSize().enterFade(rise = 12.dp)) {
                FontBrowser(library, onSelect = { name ->
                    library.remember(name)
                    state.editSelected { it.withFont(name) }
                    browser = false; popup = ""
                }, onClose = { browser = false })
            }
        } else {
            val previewHeight = minOf(400.dp, (maxHeight - 102.dp).coerceAtLeast(80.dp))
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(42.dp).background(Am.Deepest), verticalAlignment = Alignment.CenterVertically) {
                    val alignIcon = when (layer.textAlign) { "center" -> TextIcons.Centre; "right" -> TextIcons.Right; "justify" -> TextIcons.Justify; else -> TextIcons.Left }
                    Box(Modifier.padding(1.5.dp).width(38.dp).fillMaxHeight().background(TextControl, RoundedCornerShape(2.dp)).clickable {
                        val alignments = listOf("left", "center", "right", "justify")
                        state.editSelected { it.withTextAlign(alignments[(alignments.indexOf(it.textAlign) + 1) % alignments.size]) }
                    }, contentAlignment = Alignment.Center) { Icon(alignIcon, stringResource(R.string.text_alignment), Modifier.size(24.dp), tint = Am.OnDark) }
                    Row(Modifier.padding(1.5.dp).weight(1f).fillMaxHeight().background(TextControl).clickable { popup = if (popup == "font") "" else "font" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(FontName.parse(layer.font).label, Modifier.weight(1f).padding(start = 6.dp), fontSize = 16.sp, fontFamily = rememberFontFamily(library, layer.font),
                            color = if (popup == "font") Am.Accent else Am.OnDark, maxLines = 1)
                        Icon(if (popup == "font") TextIcons.Up else TextIcons.Down, null, Modifier.padding(horizontal = 6.dp).size(16.dp),
                            tint = if (popup == "font") Am.Accent else Am.OnDark)
                    }
                    Row(Modifier.padding(1.5.dp).width(80.dp).fillMaxHeight().background(TextControl).clickable {
                        keyboard?.hide(); popup = if (popup == "size") "" else "size"
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text("${layer.textSize.roundToInt()}pt", Modifier.weight(1f).padding(start = 6.dp), color = if (popup == "size") Am.Accent else Am.OnDark, fontSize = 16.sp)
                        Icon(if (popup == "size") TextIcons.DownOutline else TextIcons.Down, null, Modifier.padding(horizontal = 6.dp).size(16.dp),
                            tint = if (popup == "size") Am.Accent else Am.OnDark)
                    }
                    Box(Modifier.padding(1.5.dp).width(38.dp).fillMaxHeight().background(TextControl).clickable { keyboard?.hide(); popup = if (popup == "color") "" else "color" }, contentAlignment = Alignment.Center) {
                        ColourSwatch(layer.fillColor, 24.dp)
                    }
                    FontIconButton(TextIcons.Tick, stringResource(R.string.done), finish)
                }
                Preview(state, Modifier.fillMaxWidth().height(previewHeight))
                TextContent(state, layer, Modifier.fillMaxWidth().height(60.dp))
            }
            if (popup.isNotEmpty() && state.eyedropper == null) Box(Modifier.fillMaxSize().padding(top = 42.dp).clickable(interactionSource = null, indication = null) { popup = "" })
            Swap(popup, Modifier.fillMaxSize(), rise = (-8).dp) {
                when (popup) {
                    "size" -> {
                        val index = TextSizes.indexOfFirst { it >= layer.textSize.roundToInt() }.let { if (it < 0) TextSizes.lastIndex else it }
                        Row(Modifier.padding(top = 42.dp).fillMaxWidth().height(40.dp).background(TextControl), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(start = 6.dp).width(54.dp).height(30.dp).background(Am.Field, RoundedCornerShape(4.dp)).clickable {
                                keyboard?.hide()
                                state.askNumber("", layer.textSize, "", 1f, 1000f) { v -> state.editSelected { it.withTextSize(v) } }
                            }, contentAlignment = Alignment.Center) {
                                Text(app.absentmotion.ui.editor.panels.formatPlain(layer.textSize), color = Am.Accent, fontSize = 14.sp, maxLines = 1, softWrap = false)
                            }
                            ThinSlider(index.toFloat(), { value -> state.editSelected("text-size") { it.withTextSize(TextSizes[value.roundToInt()].toFloat()) } },
                                Modifier.weight(1f).padding(horizontal = 9.dp),
                                valueRange = 0f..TextSizes.lastIndex.toFloat(),
                                onValueChangeFinished = { state.endGesture() })
                        }
                    }
                    "font" -> FontStrip(library, layer.font, Modifier.padding(top = 42.dp),
                        onPick = { name -> library.remember(name); state.editSelected { it.withFont(name) } },
                        onViewAll = { keyboard?.hide(); browser = true })
                    "color" -> ColorPicker(state, layer, Modifier.align(Alignment.TopEnd).padding(top = 42.dp).size(304.dp, 128.dp).background(Am.Panel))
                }
            }
        }
    }
        state.keypad?.let { ValueKeypad(it, onClose = { state.keypad = null }, Modifier.fillMaxSize()) }
}

@Composable
private fun TextContent(state: EditorState, layer: Layer, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(layer.id) { mutableStateOf(TextFieldValue(layer.content, TextRange(layer.content.length))) }
    var sent by remember(layer.id) { mutableStateOf(layer.content) }
    if (layer.content != sent) {
        sent = layer.content
        value = TextFieldValue(layer.content, TextRange(layer.content.length))
    }
    LaunchedEffect(layer.id) { focus.requestFocus(); keyboard?.show() }
    Box(modifier.background(Am.TimelineBg).padding(4.dp).border(1.dp, Am.Accent.copy(alpha = .5f), RoundedCornerShape(2.dp)), contentAlignment = Alignment.CenterStart) {
        BasicTextField(value, { next ->
            value = next
            if (next.text != sent) {
                sent = next.text
                state.editSelected("text-content") { it.withContent(next.text) }
            }
        },
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).focusRequester(focus),
            textStyle = TextStyle(color = Am.OnDark, fontSize = 18.sp), cursorBrush = SolidColor(Am.Accent),
            decorationBox = { inner -> if (value.text.isEmpty()) Text(stringResource(R.string.enter_your_text_here), color = Am.OnDark.copy(alpha = .19f), fontSize = 18.sp); inner() })
    }
}

@Composable
private fun FontStrip(library: FontLibrary, current: String, modifier: Modifier, onPick: (String) -> Unit, onViewAll: () -> Unit) {
    val strip = remember {
        val favourites = library.favourites.sortedBy { it.lowercase() }
        val dedupe = favourites.take(3)
        val recent = library.recent.filterNot { it in dedupe }
        val count = if (favourites.isEmpty()) recent.size else maxOf(6 - favourites.size, 3)
        val shown = recent.take(count).let { if (current.isEmpty() || current in it + dedupe) it else listOf(current) + recent.take((count - 1).coerceAtLeast(0)) }
        shown to favourites
    }
    val (recent, favourites) = strip
    Column(modifier.fillMaxWidth()) {
        LazyRow(Modifier.fillMaxWidth().height(68.dp).background(TextControl)) {
            itemsIndexed(recent + favourites) { index, name ->
                val header = when { index == 0 && recent.isNotEmpty() -> TextIcons.Recent; index == recent.size -> TextIcons.FilledStar; else -> null }
                val active = name == current
                Row(Modifier.width(if (header != null) 85.dp else 65.dp).fillMaxHeight()
                    .then(if (header != null) Modifier.background(Brush.horizontalGradient(listOf(Am.Chosen.copy(alpha = 0.69f), Am.Chosen.copy(alpha = 0f))), RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)) else Modifier)) {
                    if (header != null) Icon(header, null, Modifier.width(20.dp).fillMaxHeight().padding(2.dp), tint = if (header == TextIcons.FilledStar) Am.Gold else Am.OnDark)
                    Column(Modifier.width(65.dp).fillMaxHeight().padding(3.dp)
                        .border(if (active) 1.dp else 0.dp, if (active) Am.Accent else Color.Transparent, RoundedCornerShape(5.dp))
                        .clickable { onPick(name) }.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FontPreview(library, name, "Abc", Modifier.fillMaxWidth().height(35.dp))
                        Text(FontName.parse(name).label, color = if (active) Am.Accent else Am.OnDark, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Row(Modifier.height(33.dp).background(TextControl).clickable(onClick = onViewAll).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TextIcons.ViewAll, null, Modifier.size(20.dp), tint = Am.OnDark)
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.show_all_fonts), color = Am.OnDark, fontSize = 16.sp)
        }
    }
}
