package app.absentmotion.ui.home

import app.absentmotion.platform.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import app.absentmotion.platform.LocalContext
import app.absentmotion.i18n.text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.withUiFont
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.Themes
import app.absentmotion.ui.theme.ThemeError
import app.absentmotion.ui.theme.ThemeFile
import app.absentmotion.platform.Toast
import app.absentmotion.platform.rememberDocumentPicker
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class ThemeGroup(@StringRes val title: Int, val colors: List<Pair<String, Int>>)

internal val ThemeGroups = listOf(
    ThemeGroup(R.string.theme_group_editor, listOf(
        "TopBar" to R.string.theme_color_topbar, "PreviewBg" to R.string.theme_color_previewbg, "TimelineBg" to R.string.theme_color_timelinebg,
        "Panel" to R.string.theme_color_panel, "PanelInset" to R.string.theme_color_panelinset, "Button" to R.string.theme_color_button,
        "Field" to R.string.theme_color_field, "FieldHi" to R.string.theme_color_fieldhi, "Chosen" to R.string.theme_color_chosen,
        "Well" to R.string.theme_color_well, "Deepest" to R.string.theme_color_deepest, "Line" to R.string.theme_color_line,
        "MenuLine" to R.string.theme_color_menuline, "PressEditor" to R.string.theme_color_presseditor,
    )),
    ThemeGroup(R.string.theme_group_timeline, listOf(
        "LayerBar" to R.string.theme_color_layerbar, "LayerBarStripe" to R.string.theme_color_layerbarstripe, "TextBar" to R.string.theme_color_textbar,
        "TextBarStripe" to R.string.theme_color_textbarstripe, "AudioBar" to R.string.theme_color_audiobar, "AudioWave" to R.string.theme_color_audiowave,
        "OnBar" to R.string.theme_color_onbar, "OnAudioBar" to R.string.theme_color_onaudiobar, "Bookmark" to R.string.theme_color_bookmark,
        "Gold" to R.string.theme_color_gold, "SelectionBorder" to R.string.theme_color_selectionborder, "SelectionHandle" to R.string.theme_color_selectionhandle,
    )),
    ThemeGroup(R.string.theme_group_keypad, listOf(
        "KeyDigit" to R.string.theme_color_keydigit, "KeySymbol" to R.string.theme_color_keysymbol, "Cool" to R.string.theme_color_cool,
        "CoolDeep" to R.string.theme_color_cooldeep, "Scrim" to R.string.theme_color_scrim, "OnScrim" to R.string.theme_color_onscrim,
    )),
    ThemeGroup(R.string.theme_group_accent, listOf(
        "Accent" to R.string.theme_color_accent, "AccentLit" to R.string.theme_color_accentlit, "AccentGlow" to R.string.theme_color_accentglow,
        "AccentDeep" to R.string.theme_color_accentdeep, "OnAccent" to R.string.theme_color_onaccent, "Warning" to R.string.theme_color_warning,
        "Favorite" to R.string.theme_color_favorite,
    )),
    ThemeGroup(R.string.theme_group_text, listOf(
        "OnDark" to R.string.theme_color_ondark, "OnDarkDim" to R.string.theme_color_ondarkdim, "Caption" to R.string.theme_color_caption,
        "Faint" to R.string.theme_color_faint, "Inactive" to R.string.theme_color_inactive,
    )),
    ThemeGroup(R.string.theme_group_home, listOf(
        "HomeBg" to R.string.theme_color_homebg, "Card" to R.string.theme_color_card, "SheetBg" to R.string.theme_color_sheetbg,
        "HomeDrawer" to R.string.theme_color_homedrawer, "HomePanel" to R.string.theme_color_homepanel, "Navy" to R.string.theme_color_navy,
        "OnNavy" to R.string.theme_color_onnavy, "OnNavyAccent" to R.string.theme_color_onnavyaccent, "NavyHi" to R.string.theme_color_navyhi,
        "NavyLine" to R.string.theme_color_navyline, "HomeAccent" to R.string.theme_color_homeaccent, "HomeInk" to R.string.theme_color_homeink,
        "HomeMid" to R.string.theme_color_homemid, "HomeDim" to R.string.theme_color_homedim, "HomeFaint" to R.string.theme_color_homefaint,
        "HomeLine" to R.string.theme_color_homeline, "MenuScrim" to R.string.theme_color_menuscrim, "PressHome" to R.string.theme_color_presshome,
    )),
    ThemeGroup(R.string.theme_group_switches, listOf(
        "SwitchOff" to R.string.theme_color_switchoff, "Thumb" to R.string.theme_color_thumb,
    )),
)

internal fun colorText(color: Color): String {
    val argb = color.toArgb()
    return if (argb ushr 24 == 0xFF) String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF) else String.format(java.util.Locale.ROOT, "#%08X", argb)
}

internal fun themeJson(name: String, colors: Map<String, Color>): String {
    val lines = buildList {
        if (name.isNotBlank()) add("\"name\": " + JSONObject.quote(name.trim()))
        for (group in ThemeGroups) for ((key, _) in group.colors) colors[key]?.let { add("\"$key\": \"${colorText(it)}\"") }
    }
    return lines.joinToString(",\n  ", "{\n  ", "\n}\n")
}

@Composable
internal fun ThemeMaker(settings: AppSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val starts = remember { settings.themes.filter { it.first != Themes.SYSTEM } }
    val colors = remember { mutableStateMapOf<String, Color>().apply { putAll(Themes.palette(context, settings.theme, settings.systemDark).toMap()) } }
    var name by remember { mutableStateOf(if (settings.theme == Themes.CUSTOM) Themes.custom(context)?.name.orEmpty() else "") }
    var editing by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    fun show() { Am.palette = Palette.from(colors) }
    DisposableEffect(Unit) { onDispose { if (!saved) settings.restoreLook() } }
    val scope = rememberCoroutineScope()
    val load = rememberDocumentPicker { uri ->
        if (uri != null) scope.launch {
            try {
                val theme = ThemeFile.parse(withContext(Dispatchers.IO) { readThemeText(context, uri) })
                colors.putAll(theme.palette.toMap())
                theme.name?.let { name = it }
                show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, (e as? ThemeError)?.text?.resolve(context) ?: context.getString(R.string.settings_theme_read_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(themeJson(name, colors).toByteArray()) } }
    }
    ToolSheet(stringResource(R.string.theme_maker_title), onClose, closeButton = true, bottom = {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(stringResource(R.string.export), Modifier.weight(1f), primary = false) { export.launch(name.trim().ifEmpty { "theme" } + ".json") }
            BigButton(stringResource(R.string.theme_maker_save), Modifier.weight(1f)) {
                settings.importTheme(themeJson(name, colors))
                settings.accent = null
                saved = true
                onClose()
            }
        }
    }) {
        BigButton(stringResource(R.string.settings_import_theme), Modifier.fillMaxWidth().padding(top = 12.dp), primary = false) { load.launch() }
        Text(stringResource(R.string.theme_maker_about), Modifier.padding(top = 12.dp), color = Am.HomeDim, fontSize = 14.sp, lineHeight = 19.sp)
        Text(stringResource(R.string.theme_maker_help), Modifier.padding(top = 4.dp).clickable { help = true }.padding(vertical = 6.dp), color = Am.HomeAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        SectionTitle(stringResource(R.string.theme_maker_name))
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip12().background(Am.Card).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp).withUiFont(), singleLine = true, cursorBrush = SolidColor(Am.Navy))
        }
        SectionTitle(stringResource(R.string.theme_maker_start))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in starts) {
                Text(label.text(), Modifier.clip12().background(Am.Card).clickable {
                    colors.putAll(Themes.palette(context, id).toMap())
                    show()
                }.padding(horizontal = 14.dp, vertical = 10.dp), color = Am.Navy, fontSize = 15.sp, maxLines = 1)
            }
        }
        for (group in ThemeGroups) {
            SectionTitle(stringResource(group.title))
            Column(Modifier.fillMaxWidth().clip12().background(Am.Card)) {
                group.colors.forEachIndexed { i, (key, about) ->
                    val color = colors[key] ?: Color.Black
                    Row(Modifier.fillMaxWidth().clickable { editing = key }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(color).border(1.dp, Am.HomeLine, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(key, color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(stringResource(about), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(colorText(color), Modifier.padding(start = 8.dp), color = Am.HomeMid, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    if (i < group.colors.lastIndex) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(1.dp).background(Am.HomeLine))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    editing?.let { key ->
        val request = remember(key) { app.absentmotion.editor.FullColor(colors[key]?.toArgb() ?: 0xFF000000.toInt()) { c, _ -> colors[key] = Color(c); show() } }
        androidx.compose.ui.window.Dialog({ editing = null }, app.absentmotion.platform.sheetDialogProperties()) {
            app.absentmotion.ui.editor.PhoneScreen { app.absentmotion.ui.editor.panels.FullColorPicker(settings, request, { editing = null }, {}) }
        }
    }
    if (help) HelpScreen("themes.html") { help = false }
}

private fun Modifier.clip12() = clip(RoundedCornerShape(12.dp))
