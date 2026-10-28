package app.absentmotion.ui.editor

import app.absentmotion.platform.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.openCrop
import app.absentmotion.editor.keepCopy
import app.absentmotion.model.replaceMedia
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.canClip
import app.absentmotion.editor.markBeats
import app.absentmotion.editor.toggleClippingMask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.absentmotion.editor.compositionArea
import app.absentmotion.editor.convertToOutline
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.editor.resetTransform
import app.absentmotion.ui.editor.panels.Turn
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.canPaste
import app.absentmotion.editor.canSavePreset
import app.absentmotion.editor.savePreset
import app.absentmotion.library.PresetStore
import app.absentmotion.library.SavedPreset
import app.absentmotion.editor.changeSettings
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.duplicateChosen
import app.absentmotion.editor.pasteCount
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.extractAudio
import app.absentmotion.editor.startTracking
import app.absentmotion.editor.markIntroEnd
import app.absentmotion.editor.markOutroStart
import app.absentmotion.editor.setElementOption
import app.absentmotion.editor.flip
import app.absentmotion.editor.pasteLayer
import app.absentmotion.model.LabelColour
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.presenceScrim
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val MenuBg get() = Am.Field
private val MenuLine get() = Am.MenuLine
private val ListLine get() = Am.Line
private val MenuShape = RoundedCornerShape(8.dp)
private val FieldShape = RoundedCornerShape(10.dp)

internal class RightEdge(private val top: (IntRect) -> Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(anchorBounds.right - popupContentSize.width, top(anchorBounds))
}

@Composable
internal fun MenuPopup(
    position: PopupPositionProvider,
    onDismiss: () -> Unit,
    origin: TransformOrigin = TransformOrigin(1f, 0f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Popup(position, onDismiss, PopupProperties(focusable = true)) {
        Column(Modifier.presence(scaleFrom = 0.92f, origin = origin).width(250.dp).clip(MenuShape).background(MenuBg), content = content)
    }
}

@Composable
internal fun MenuRow(
    text: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    subText: String? = null,
    trailing: ImageVector? = null,
    divider: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit = {},
) {
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { if (divider) drawRect(MenuLine, size = Size(size.width, 1.dp.toPx())) },
    ) {
        Row(Modifier.fillMaxSize().alpha(if (enabled) 1f else DimAlpha), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Box(Modifier.size(40.dp), Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = Am.OnDark) }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.Center) {
                Text(text, color = Am.OnDark, fontSize = 15.sp, lineHeight = if (subText == null) 20.sp else 19.5.sp, maxLines = 1)
                if (subText != null) Text(subText, color = Am.OnDark, fontSize = 10.sp, lineHeight = 20.5.sp, maxLines = 1)
            }
            if (trailing != null) Icon(trailing, null, Modifier.padding(end = 7.dp).size(24.dp), tint = Am.OnDark)
            if (busy) CircularProgressIndicator(Modifier.padding(end = 14.dp).size(18.dp), color = Am.Accent, strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun LayerMenu(state: EditorState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val position = remember { RightEdge { it.bottom } }
    var mediaInfo by remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val media = state.selected?.mediaFill
    val video = media?.takeIf { it.video }
    val mediaRecord = media?.let { state.scene.media(it.uri) }
    if (mediaInfo && mediaRecord != null) {
        MediaInfoDialog(mediaRecord, onReplace = { picked -> val kept = state.keepCopy(context, picked); state.edit { s -> s.replaceMedia(mediaRecord.uri, kept) } }) { mediaInfo = false; onDismiss() }
        return
    }
    val saveElement = rememberSaveToMyElements(state)
    MenuPopup(position, onDismiss) {
        if (state.selected?.link == null && !MultiSelect.active) MenuRow(stringResource(R.string.menu_save_to_my_assets)) { saveElement(onDismiss) }
        if (state.selected?.kind != "audio") {
            MenuRow(stringResource(R.string.menu_flip_horizontally)) {
                state.flip(horizontal = true)
                onDismiss()
            }
            MenuRow(stringResource(R.string.menu_flip_vertically)) {
                state.flip(horizontal = false)
                onDismiss()
            }
            if (media != null && !MultiSelect.active) MenuRow(stringResource(R.string.layer_tool_crop)) {
                onDismiss()
                state.openCrop()
            }
            MenuRow(stringResource(R.string.menu_fit_to_canvas)) { scope.launch { state.compositionArea("fit"); onDismiss() } }
            MenuRow(stringResource(R.string.menu_fill_the_canvas)) { scope.launch { state.compositionArea("fill"); onDismiss() } }
            MenuRow(stringResource(R.string.menu_stretch_to_canvas)) { scope.launch { state.compositionArea("stretch"); onDismiss() } }
            MenuRow(stringResource(if (state.selected?.node?.get("clippingMask") == "true") R.string.menu_release_clipping_mask else R.string.menu_create_clipping_mask), enabled = state.canClip, divider = true) {
                state.toggleClippingMask()
                onDismiss()
            }
            MenuRow(stringResource(R.string.menu_convert_to_outline), enabled = state.selected?.let { it.kind == "text" || (it.kind == "shape" && it.shape.isNotEmpty()) } == true) {
                scope.launch { state.convertToOutline(); onDismiss() }
            }
        }
        if (video != null) {
            MenuRow(stringResource(R.string.menu_extract_audio)) {
                state.extractAudio()
                onDismiss()
            }
            if (!MultiSelect.active) MenuRow(stringResource(R.string.draw_title_track_motion)) {
                onDismiss()
                state.startTracking()
            }
        }
        if (state.selected?.soundUri != null && !MultiSelect.active) MenuRow(stringResource(R.string.menu_mark_beats), enabled = !marking, busy = marking) {
            marking = true
            scope.launch {
                val note = try {
                    state.markBeats(context).let { if (it == 0) context.getString(R.string.menu_no_new_beats) else context.resources.getQuantityString(R.plurals.menu_beats_marked, it, it.toString()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.absentmotion.i18n.failureText(context.getString(R.string.menu_beats_not_found), e)
                }
                Toast.makeText(context, note, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
        if (mediaRecord != null) MenuRow(stringResource(R.string.media_info)) { mediaInfo = true }
        LabelColours(state, onDismiss)
    }
}

internal object DropOrRise : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceAtLeast(0),
        if (anchorBounds.bottom + popupContentSize.height <= windowSize.height) anchorBounds.bottom else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
    )
}

internal object AboveLeft : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(0, anchorBounds.top - popupContentSize.height)
}

@Composable
internal fun TransformMenu(state: EditorState, tab: Int, turn: Turn?, onDismiss: () -> Unit) {
    val resetName = listOf(R.string.menu_reset_location, R.string.menu_reset_rotation, R.string.menu_reset_scale, R.string.menu_reset_3d_rotation, R.string.menu_reset_skew, R.string.menu_reset_pivot)[tab]
    MenuPopup(AboveLeft, onDismiss, TransformOrigin(0f, 1f)) {
        MenuRow(stringResource(resetName)) {
            if (tab == ROTATE_TAB && turn != null) state.editSelected { turn.reset(it) } else state.resetTransform(listOf(tab))
            onDismiss()
        }
        MenuRow(stringResource(R.string.menu_reset_transform), divider = true) {
            state.resetTransform(listOf(0, 1, 2, 3, 4, 5))
            onDismiss()
        }
    }
}

@Composable
internal fun CopyPasteMenu(state: EditorState, onDismiss: () -> Unit) {
    val drop = with(LocalDensity.current) { 33.dp.roundToPx() }
    val position = remember(drop) { RightEdge { it.top + drop } }
    val layer = state.selected
    val count = state.chosenLayers.size
    var styleOpen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedPreset?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    saved?.let { PresetSavedDialog(it, PresetStore(context), onDismiss); return }
    MenuPopup(position, onDismiss) {
        MenuRow(stringResource(R.string.menu_select_all_layers), enabled = state.scene.layers.isNotEmpty(), icon = ChromeIcons.SelectAll) {
            state.selectedId = null
            MultiSelect.ids = state.scene.layers.map { it.id }.toSet()
            MultiSelect.active = true
            onDismiss()
        }
        MenuRow(pluralStringResource(R.plurals.menu_duplicate_layers, count, count.toString()), enabled = count > 0, icon = ChromeIcons.Duplicate) {
            state.duplicateChosen()
            onDismiss()
        }
        MenuRow(stringResource(R.string.menu_save_preset), enabled = layer != null && canSavePreset(layer), icon = ChromeIcons.SavePreset) {
            scope.launch {
                saved = try {
                    state.savePreset(PresetStore(context))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.preset_save_failed), Toast.LENGTH_LONG).show()
                    null
                }
                if (saved == null) onDismiss()
            }
        }
        MenuRow(
            pluralStringResource(R.plurals.menu_copy_layers, count, count.toString()), enabled = count > 0, icon = ChromeIcons.Copy,
            subText = stringResource(R.string.menu_with_its_style),
        ) {
            state.copySelected()
            onDismiss()
        }
        MenuRow(pluralStringResource(R.plurals.menu_paste_layers, maxOf(1, state.pasteCount), maxOf(1, state.pasteCount).toString()), enabled = state.canPaste, icon = ChromeIcons.Paste) {
            state.pasteLayer()
            onDismiss()
        }
        MenuRow(stringResource(R.string.menu_paste_style), enabled = layer != null && state.canPaste, icon = ChromeIcons.PasteStyle, trailing = ChromeIcons.ChevronRight) { styleOpen = true }
        if (state.scene.isElement) {
            MenuRow(stringResource(R.string.menu_set_intro_end), divider = true) { state.markIntroEnd(); onDismiss() }
            MenuRow(stringResource(R.string.menu_set_outro_start)) { state.markOutroStart(); onDismiss() }
        }
    }
    Presence(styleOpen) { PasteStyleMenu(state) { styleOpen = false; onDismiss() } }
}

@Composable
private fun LabelColours(state: EditorState, onDismiss: () -> Unit) {
    val chosen = state.selected?.labelColour
    Row(
        Modifier.fillMaxWidth().height(40.dp).drawBehind { drawRect(MenuLine, size = Size(size.width, 1.dp.toPx())) }
            .horizontalScroll(rememberScrollState()).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (colour in listOf(null) + LabelColour.entries) {
            Box(
                Modifier.width(34.dp).fillMaxHeight().clickable {
                    state.editSelected { it.withLabelColour(colour) }
                    onDismiss()
                },
                Alignment.Center,
            ) { LabelSwatch(colour, colour == chosen) }
        }
    }
}

@Composable
private fun LabelSwatch(colour: LabelColour?, chosen: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val dp = density
        val inset = if (chosen) 1.8f * dp else 0f
        val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
        if (chosen) drawRoundRect(Am.OnDark, cornerRadius = CornerRadius(4f * dp))
        if (colour == null) {
            drawRoundRect(Am.Faint, Offset(inset, inset), inner, CornerRadius(2.5f * dp))
            val a = 3.8f * dp
            val b = size.width - a
            drawLine(Color.White, Offset(a, a), Offset(b, b), 1.3f * dp, StrokeCap.Round)
            drawLine(Color.White, Offset(b, a), Offset(a, b), 1.3f * dp, StrokeCap.Round)
        } else {
            drawRoundRect(Brush.linearGradient(listOf(Color(colour.start), Color(colour.end)), Offset(inset, inset), Offset(size.width - inset, size.height - inset)), Offset(inset, inset), inner, CornerRadius(if (chosen) 2.5f * dp else 3f * dp))
        }
    }
}

private const val MIN_SIDE = 32
private const val MAX_SIDE = 4096
private const val CUSTOM = "custom"

private val Resolutions = listOf(
    2160 to "2160p (4k)", 1440 to "1440p (QHD)", 1080 to "1080p (FHD)", 720 to "720p (HD)",
    540 to "540p (SD)", 360 to "360p", 270 to "270p", 180 to "180p",
)
private val FrameRates = listOf(12, 15, 18, 20, 24, 25, 30, 48, 50, 60)

private class BackgroundChoice(@StringRes val name: Int, val argb: Int)

private val Backgrounds = listOf(
    BackgroundChoice(R.string.menu_bg_black, 0xFF000000.toInt()), BackgroundChoice(R.string.menu_bg_white, 0xFFFFFFFF.toInt()),
    BackgroundChoice(R.string.menu_bg_light_grey, 0xFFD8D8D8.toInt()), BackgroundChoice(R.string.menu_bg_green, 0xFF00B140.toInt()),
    BackgroundChoice(R.string.menu_bg_blue, 0xFF0047BB.toInt()), BackgroundChoice(R.string.menu_bg_transparent, 0x00000000),
)

private class Aspect(val key: String, val w: Int, val h: Int, val boxW: Dp, val boxH: Dp)

private val Aspects = listOf(
    Aspect("16:9", 16, 9, 55.dp, 31.dp), Aspect("9:16", 9, 16, 31.dp, 55.dp), Aspect("4:5", 4, 5, 31.dp, 39.dp),
    Aspect("1:1", 1, 1, 31.dp, 31.dp), Aspect("4:3", 4, 3, 41.dp, 31.dp),
)

private fun sizeFor(aspect: Aspect, resolution: Int): Pair<Int, Int> {
    val short = minOf(aspect.w, aspect.h)
    val long = maxOf(aspect.w, aspect.h)
    if (short == long) return resolution to resolution
    val res = minOf(resolution, MAX_SIDE * short / long)
    val side = ((res * long / short.toDouble()) / 2).roundToInt() * 2
    return if (aspect.w >= aspect.h) side to res else res to side
}

private fun aspectOf(width: Int, height: Int): String =
    Aspects.firstOrNull { sizeFor(it, minOf(width, height)) == width to height }?.key ?: CUSTOM

private class FixedPosition(private val at: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = at
}

@Composable
internal fun ProjectSettingsPopup(state: EditorState, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    val area = with(density) { DpSize(window.width.toDp(), (window.height - top - bottom).toDp()) }
    val position = remember(top) { FixedPosition(IntOffset(0, top)) }
    Popup(position, onDismiss, PopupProperties(focusable = true)) {
        Box(Modifier.size(area).presenceScrim(Color.Black.copy(alpha = 0.3f)).pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
            SettingsPanel(state, onDismiss, Modifier.align(Alignment.BottomCenter).presence(slide = 1f, fade = false))
        }
    }
}

@Composable
private fun SettingsPanel(state: EditorState, onDismiss: () -> Unit, modifier: Modifier) {
    val scene = state.scene
    val focus = LocalFocusManager.current
    val derived = aspectOf(scene.width, scene.height)
    var custom by remember { mutableStateOf(derived == CUSTOM) }
    val aspect = if (custom) CUSTOM else derived
    val resolution = minOf(scene.width, scene.height)
    fun change(width: Int = scene.width, height: Int = scene.height, fps: Int = scene.fps, bgColor: Int = scene.bgColor) =
        state.changeSettings(width, height, fps, bgColor)
    fun resize(ratio: Aspect, short: Int) {
        val (w, h) = sizeFor(ratio, short)
        change(width = w, height = h)
    }
    Column(
        modifier.fillMaxWidth().height(if (scene.isElement) 447.dp else 377.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(MenuBg)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(Modifier.size(40.dp).clickable(onClick = onDismiss), Alignment.Center) {
            Icon(ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.OnDark)
        }
        AspectRow(
            aspect,
            onPick = {
                focus.clearFocus()
                custom = false
                resize(it, resolution)
            },
            onCustom = {
                focus.clearFocus()
                custom = true
            },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(stringResource(if (scene.isElement) R.string.menu_asset_resolution else R.string.menu_resolution)) {
            if (aspect == CUSTOM) {
                CustomSize(scene.width, scene.height) { w, h -> change(width = w, height = h) }
            } else {
                val ratio = Aspects.first { it.key == aspect }
                Dropdown("${resolution}p") { close ->
                    Resolutions.forEachIndexed { i, (short, label) ->
                        PickRow(label, divider = i < Resolutions.lastIndex) { resize(ratio, short); close() }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingRow(stringResource(R.string.menu_frame_rate)) {
            Dropdown("${scene.fps} fps") { close ->
                FrameRates.forEachIndexed { i, rate ->
                    PickRow("$rate fps", divider = i < FrameRates.lastIndex) { change(fps = rate); close() }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingRow(stringResource(R.string.menu_background)) {
            val shown = Backgrounds.firstOrNull { it.argb == scene.bgColor || (it.argb ushr 24 == 0 && scene.bgColor ushr 24 == 0) }
            Dropdown(stringResource(shown?.name ?: R.string.menu_custom), swatch = scene.bgColor) { close ->
                Backgrounds.forEachIndexed { i, choice ->
                    PickRow(stringResource(choice.name), swatch = choice.argb, divider = i < Backgrounds.lastIndex) { change(bgColor = choice.argb); close() }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingRow(stringResource(R.string.version_history)) {
            Row(
                Modifier.fillMaxSize().clip(FieldShape).background(Am.Card).clickable { onDismiss(); state.historyOpen = true }.padding(start = 10.dp, end = 4.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.earlier_versions), Modifier.weight(1f), color = Am.Navy, fontSize = 15.sp, maxLines = 1)
                Icon(ChromeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.Navy)
            }
        }
        if (scene.isElement) {
            Spacer(Modifier.height(10.dp))
            SettingRow(stringResource(R.string.menu_precompose)) {
                Dropdown(stringResource(PrecomposeOptions.firstOrNull { it.first == scene.precompose }?.second ?: PrecomposeOptions[0].second)) { close ->
                    PrecomposeOptions.forEachIndexed { i, (value, label) ->
                        PickRow(stringResource(label), divider = i < PrecomposeOptions.lastIndex) { state.setElementOption("precompose", value); close() }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingRow(stringResource(R.string.menu_retiming)) {
                Dropdown(stringResource(RetimingOptions.firstOrNull { it.first == scene.retime }?.second ?: RetimingOptions[0].second)) { close ->
                    RetimingOptions.forEachIndexed { i, (value, label) ->
                        PickRow(stringResource(label), divider = i < RetimingOptions.lastIndex) { state.setElementOption("retime", value); close() }
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectRow(selected: String, onPick: (Aspect) -> Unit, onCustom: () -> Unit) {
    Row(Modifier.padding(start = 22.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Aspects.forEach { a ->
            AspectButton(a.boxW, a.boxH, a.key == selected, onClick = { onPick(a) }) {
                Text(a.key, color = if (a.key == selected) Am.OnAccent else Am.Navy, fontSize = 10.sp, maxLines = 1)
            }
        }
        val custom = selected == CUSTOM
        AspectButton(31.dp, 31.dp, custom, onClick = onCustom) {
            Icon(ChromeIcons.Pencil, stringResource(R.string.menu_custom_aspect_ratio), Modifier.size(16.dp), tint = if (custom) Am.OnAccent else Am.HomeDim)
        }
    }
}

@Composable
private fun AspectButton(w: Dp, h: Dp, selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 10.dp), Alignment.Center) {
        val shape = RoundedCornerShape(2.dp)
        Box(
            Modifier.size(w, h).background(if (selected) Am.Accent else Am.Card, shape)
                .then(if (selected) Modifier.border(1.dp, Am.Navy, shape) else Modifier),
            Alignment.Center, content = content,
        )
    }
}

@Composable
private fun SettingRow(label: String, field: @Composable BoxScope.() -> Unit) {
    Row(Modifier.padding(start = 32.dp).height(37.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(140.dp), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
        Box(Modifier.width(180.dp).fillMaxHeight(), content = field)
    }
}

private object DropdownPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height
        val y = if (fitsBelow) anchorBounds.bottom else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun Dropdown(text: String, swatch: Int? = null, options: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().clip(FieldShape).background(Am.Card)
                .clickable {
                    focus.clearFocus()
                    open = true
                }
                .padding(start = if (swatch != null) 6.dp else 10.dp, end = 4.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatch != null) {
                Swatch(swatch)
                Spacer(Modifier.width(5.dp))
            }
            Text(text, Modifier.weight(1f), color = Am.Navy, fontSize = 15.sp, maxLines = 1)
            Icon(ChromeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.Navy)
        }
        Presence(open) {
            Popup(DropdownPosition, { open = false }, PopupProperties(focusable = true)) {
                Column(
                    Modifier.presence(scaleFrom = 0.94f, origin = TransformOrigin(1f, 0f)).width(180.dp).heightIn(max = 330.dp).clip(FieldShape).background(MenuBg)
                        .verticalScroll(rememberScrollState()),
                ) { options { open = false } }
            }
        }
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
        Text(text, color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(ListLine))
}

@Composable
private fun Swatch(argb: Int) {
    val shape = RoundedCornerShape(5.dp)
    val fill = if (argb ushr 24 == 0) Modifier.checkerboard(3.dp) else Modifier.background(Color(argb))
    Box(Modifier.size(24.dp).clip(shape).then(fill).border(1.dp, Color(0x40808080), shape))
}

private fun Modifier.checkerboard(cell: Dp): Modifier = drawWithCache {
    val c = cell.toPx()
    val dark = Path()
    for (row in 0 until (size.height / c).toInt() + 1) {
        for (col in 0 until (size.width / c).toInt() + 1) {
            if ((row + col) % 2 == 0) dark.addRect(Rect(col * c, row * c, (col + 1) * c, (row + 1) * c))
        }
    }
    onDrawBehind {
        drawRect(Color(0xFFE5E5E5))
        drawPath(dark, Color(0xFFCCCCCC))
    }
}

@Composable
private fun CustomSize(width: Int, height: Int, onChange: (Int, Int) -> Unit) {
    var w by remember(width) { mutableStateOf(width.toString()) }
    var h by remember(height) { mutableStateOf(height.toString()) }
    var dirty by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState {
        val changed = dirty
        dirty = false
        val newW = w.toIntOrNull()?.coerceIn(MIN_SIDE, MAX_SIDE) ?: width
        val newH = h.toIntOrNull()?.coerceIn(MIN_SIDE, MAX_SIDE) ?: height
        w = newW.toString()
        h = newH.toString()
        if (changed && (newW != width || newH != height)) onChange(newW, newH)
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        SizeField(w, { w = it.filter(Char::isDigit).take(4); dirty = true }, { commit() }, Modifier.weight(1f))
        Text("x", Modifier.padding(horizontal = 8.dp), color = Am.OnDark, fontSize = 15.sp)
        SizeField(h, { h = it.filter(Char::isDigit).take(4); dirty = true }, { commit() }, Modifier.weight(1f))
    }
}

@Composable
private fun SizeField(value: String, onValue: (String) -> Unit, onLeave: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    BasicTextField(
        value, onValue,
        modifier.fillMaxHeight().clip(FieldShape).background(Am.Card).onFocusChanged {
            if (focused && !it.isFocused) onLeave()
            focused = it.isFocused
        },
        textStyle = TextStyle(color = Am.Navy, fontSize = 15.sp, textAlign = TextAlign.Center),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        cursorBrush = SolidColor(Am.Navy),
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), Alignment.Center) { inner() } },
    )
}
