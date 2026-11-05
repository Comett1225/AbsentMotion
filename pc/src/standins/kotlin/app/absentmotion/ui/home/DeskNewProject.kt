package app.absentmotion.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.widthIn
import app.absentmotion.library.SavedSize
import app.absentmotion.library.SavedSizes
import app.absentmotion.platform.Hint
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectStore
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeskNewProject(
    store: ProjectStore, settings: AppSettings, existingTitles: List<String>, initialElement: Boolean, opening: Int,
    onClose: () -> Unit, onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val form = remember(opening) { Form(settings.newProject, existingTitles, DefaultNames(context), initialElement) }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val create: () -> Unit = create@{
        if (creating) return@create
        creating = true
        form.commitSides()
        settings.newProject = form.choice()
        val scene = form.scene()
        scope.launch {
            val id = try {
                withContext(Dispatchers.IO) { store.create(scene) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                creating = false
                throw e
            } catch (e: Exception) {
                app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.home_create_project_failed), app.absentmotion.platform.Toast.LENGTH_LONG).show()
                creating = false
                return@launch
            }
            onClose()
            onOpen(id)
        }
    }
    DeskModal(onClose, 560.dp, onEnter = create) {
        DeskDialogHeader(stringResource(if (form.element) R.string.desk_new_asset else R.string.home_new_project), onClose)
        Column(Modifier.weight(1f, fill = false).deskVerticalScroll(color = Am.Navy).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TypeToggle(form)
            NameRow(form)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.desk_aspect_ratio), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Aspects.forEach { a -> AspectTile(a, form.aspect == a.key) { form.aspect = a.key } }
                    AspectTile(null, form.aspect == CUSTOM) { form.aspect = CUSTOM }
                }
            }
            val (w, h) = form.size
            Column {
            FormRow(stringResource(R.string.newproject_resolution), "${stringResource(R.string.newproject_canvas_size)}: $w × $h") {
                if (form.aspect == CUSTOM) CustomSize(form)
                else DeskDropdown(
                    Resolutions.firstOrNull { it.first == form.resolution }?.second ?: "${form.resolution}p", Resolutions, { it.second }, Resolutions.firstOrNull { it.first == form.resolution },
                    { form.resolution = it.first }, Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(form.aspect == CUSTOM, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(top = 10.dp)) { DeskSavedSizes(form, settings) }
            }
            }
            FormRow(stringResource(R.string.newproject_frame_rate), null) {
                DeskDropdown("${form.fps} fps", FrameRates, { "$it fps" }, form.fps, { form.fps = it }, Modifier.fillMaxWidth())
            }
            val shown = if (form.element) Backgrounds.last() else Backgrounds.firstOrNull { it.argb == form.background } ?: Backgrounds.last()
            FormRow(stringResource(R.string.newproject_background), null) {
                DeskDropdown(
                    stringResource(shown.name), Backgrounds, { stringResource(it.name) }, shown, { form.background = it.argb }, Modifier.fillMaxWidth(), enabled = !form.element,
                    leading = { item -> Swatch(item?.argb ?: shown.argb) },
                )
            }
        }
        DeskDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            DeskButton(stringResource(R.string.cancel), onClose, minWidth = 96.dp, height = 38.dp)
            DeskButton(stringResource(if (form.element) R.string.desk_create_asset else R.string.desk_create_project), create, primary = true, minWidth = 140.dp, height = 38.dp, enabled = !creating)
        }
    }
}

@Composable
private fun TypeToggle(form: Form) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(40.dp).background(Am.HomeBg, shape).border(1.dp, Am.HomeLine, shape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Segment(titleCase(stringResource(R.string.newproject_segment_project)), !form.element, Modifier.weight(1f)) { form.switchType(false) }
        Segment(titleCase(stringResource(R.string.newproject_segment_asset)), form.element, Modifier.weight(1f)) { form.switchType(true) }
    }
}

private fun titleCase(text: String) = text.lowercase().replaceFirstChar { it.titlecase() }

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.height(34.dp).clip(shape).background(if (selected) Am.Navy else Color.Transparent).then(if (selected) Modifier else Modifier.deskPress(8.dp)).clickable(onClick = onClick),
        Alignment.Center,
    ) { Text(text, color = if (selected) Am.OnNavy else Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun NameRow(form: Form) {
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(form.name, TextRange(0, form.name.length))) }
    LaunchedEffect(form.name) { if (value.text != form.name) value = TextFieldValue(form.name, TextRange(form.name.length)) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.desk_name), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().height(40.dp).clip(shape).background(Am.HomeBg).border(1.dp, Am.HomeLine, shape).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value, { value = it; form.name = it.text }, Modifier.weight(1f).focusRequester(focus),
                textStyle = TextStyle(color = Am.HomeInk, fontSize = 15.sp), singleLine = true, cursorBrush = SolidColor(Am.Navy),
            )
            DeskIconButton(HomeIcons.ClearCircle, stringResource(R.string.newproject_clear_title), { form.name = "" }, size = 36.dp, tooltip = null)
        }
    }
}

@Composable
private fun AspectTile(a: AspectDef?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(64.dp).height(66.dp).clip(shape).background(if (selected) Am.Accent.copy(alpha = 0.22f) else Am.HomeBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Am.HomeAccent else Am.HomeLine, shape).deskPress(10.dp).clickable(onClick = onClick),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        if (a != null) {
            Box(Modifier.size(a.boxW * 0.62f, a.boxH * 0.62f).background(if (selected) Am.Accent else Am.Card, RoundedCornerShape(3.dp)).border(1.5.dp, Am.Navy, RoundedCornerShape(3.dp)))
            Spacer(Modifier.height(6.dp))
            Text(a.key, color = Am.Navy, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        } else {
            Icon(HomeIcons.Pencil, stringResource(R.string.newproject_custom_aspect), Modifier.size(20.dp), tint = Am.Navy)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.desk_custom), color = Am.Navy, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun FormRow(label: String, detail: String?, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(184.dp).padding(end = 16.dp)) {
            Text(label, color = Am.HomeInk, fontSize = 14.sp)
            if (detail != null) Text(detail, color = Am.HomeDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f)) { field() }
    }
}

@Composable
private fun Swatch(argb: Int) {
    val shape = RoundedCornerShape(5.dp)
    val fill = if (argb ushr 24 == 0) Modifier.checkerboard(3.dp) else Modifier.background(Color(argb))
    Box(Modifier.size(20.dp).clip(shape).then(fill).border(1.dp, Color(0x40808080), shape))
}

@Composable
private fun CustomSize(form: Form) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(form.customW, form::editWidth, form::commitSides, Modifier.weight(1f))
        val tint = if (form.linked) Am.Navy else Am.HomeFaint
        DeskIconButton(HomeIcons.Link, stringResource(R.string.newproject_link), form::toggleLink, tint = tint, tooltip = stringResource(R.string.newproject_link))
        NumberField(form.customH, form::editHeight, form::commitSides, Modifier.weight(1f))
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, onLeave: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value, onChange, modifier.height(36.dp).clip(shape).background(Am.Card).border(1.dp, Am.HomeLine, shape).onFocusChanged { if (!it.isFocused) onLeave() },
        textStyle = TextStyle(color = Am.HomeInk, fontSize = 14.sp, textAlign = TextAlign.Center), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), cursorBrush = SolidColor(Am.Navy),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth().height(36.dp), Alignment.Center) { inner() } },
    )
}

@Composable
private fun DeskSavedSizes(form: Form, settings: AppSettings) {
    var naming by remember { mutableStateOf<Pair<SavedSize?, String>?>(null) }
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
        Hint(stringResource(R.string.newproject_save_size_title)) {
            Box(
                Modifier.height(40.dp).clip(shape).border(1.dp, Am.Navy, shape).deskPress(10.dp).clickable {
                    form.commitSides()
                    val (w, h) = form.size
                    naming = null to "$w × $h"
                }.padding(horizontal = 14.dp),
                Alignment.Center,
            ) { Text(stringResource(R.string.newproject_save_size_chip), color = Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }
        settings.savedSizes.forEach { size ->
            var menuAt by remember { mutableStateOf<IntOffset?>(null) }
            Hint("${size.name}  ${size.label}") {
                Column(
                    Modifier.height(40.dp).clip(shape).background(Am.HomeBg).border(1.dp, Am.HomeLine, shape).deskPress(10.dp)
                        .onSecondaryPress { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) }.clickable { form.pick(size.width, size.height) }.padding(horizontal = 12.dp),
                    Arrangement.Center,
                ) {
                    Text(size.name, Modifier.widthIn(max = 160.dp), color = Am.HomeInk, fontSize = 13.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(size.label, color = Am.HomeDim, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                }
            }
            menuAt?.let { at ->
                DeskPopupMenu(AtPosition(at), { menuAt = null }, 160.dp) {
                    DeskMenuItem(stringResource(R.string.rename), { menuAt = null; naming = size to size.name }, HomeIcons.Pencil)
                    DeskMenuDivider()
                    DeskMenuItem(stringResource(R.string.delete), { menuAt = null; settings.savedSizes = SavedSizes.without(settings.savedSizes, size) }, HomeIcons.Delete, danger = true)
                }
            }
        }
    }
    naming?.let { (target, initial) ->
        DeskSizeName(stringResource(if (target == null) R.string.newproject_save_size_title else R.string.rename), initial, { naming = null }) { name ->
            naming = null
            settings.savedSizes = if (target == null) {
                val (w, h) = form.size
                SavedSizes.with(settings.savedSizes, name, w, h)
            } else SavedSizes.renamed(settings.savedSizes, target, name)
        }
    }
}

@Composable
private fun DeskSizeName(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val done = { onDone(text.text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DeskModal(onDismiss, 440.dp, onEnter = done) {
        Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            text, { text = it.copy(text = it.text.take(SavedSizes.MAX_NAME)) }, Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg)
                .border(1.dp, Am.HomeLine, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(color = Am.Navy, fontSize = 14.sp), singleLine = true, cursorBrush = SolidColor(Am.Navy),
        )
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            DeskButton(stringResource(R.string.cancel), onDismiss, minWidth = 92.dp)
            DeskButton(stringResource(R.string.done), done, primary = true, minWidth = 92.dp)
        }
    }
}
