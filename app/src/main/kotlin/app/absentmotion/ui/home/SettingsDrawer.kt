package app.absentmotion.ui.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.platform.Uri
import app.absentmotion.platform.rememberDocumentPicker
import app.absentmotion.ui.theme.ThemeError
import app.absentmotion.ui.theme.Themes
import app.absentmotion.i18n.text
import app.absentmotion.i18n.txt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.CaptionModel
import app.absentmotion.media.VideoProxies
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import app.absentmotion.library.AppSettings
import app.absentmotion.library.PreviewQuality
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.R
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.ui.onboarding.LanguageDialog
import androidx.compose.ui.res.stringResource

private val PanelColor get() = Am.HomePanel
private val PanelShape = RoundedCornerShape(7.dp)
private val DividerColor get() = Am.HomeLine
private val ListLineColor get() = Am.NavyLine
private val ChosenRow get() = Am.NavyHi

@Composable
fun SettingsDrawer(settings: AppSettings, onRestored: () -> Unit = {}) {
    var about by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(false) }
    var demoNote by remember { mutableStateOf(false) }
    ModalDrawerSheet(
        Modifier.width(280.dp), drawerShape = RectangleShape, drawerContainerColor = Am.HomeDrawer,
        windowInsets = WindowInsets(0),
    ) {
        Column(Modifier.fillMaxWidth().background(Am.Card).statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.settings), Modifier.fillMaxWidth().height(50.dp).wrapContentHeight(Alignment.CenterVertically).padding(start = 11.dp),
                color = Am.HomeInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Column(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(PanelShape).background(PanelColor)) {
                SwitchRow(stringResource(R.string.settings_demo_mode), settings.demoMode, enabled = true) { settings.demoMode = it; demoNote = it }
                SwitchRow(stringResource(R.string.settings_open_xml), settings.xmlUpload, enabled = true) { settings.xmlUpload = it }
                SwitchRow(stringResource(R.string.settings_vibration), settings.vibration, enabled = true) { settings.vibration = it }
                SwitchRow(stringResource(R.string.settings_keep_media_copies), settings.keepMediaCopies, enabled = true) { settings.keepMediaCopies = it }
                Divider(15.dp)
                DurationRow(settings)
                ThemeRow(settings)
                Spacer(Modifier.height(10.dp))
                Divider(10.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.playback_resampling), Modifier.padding(start = 10.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Choices(listOf(R.string.settings_level_low, R.string.settings_level_high, R.string.settings_level_ultra), settings.audioResampling) { settings.audioResampling = it }
                Spacer(Modifier.height(8.dp))
                Divider(10.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.preview_quality), Modifier.padding(start = 10.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Choices(PreviewQuality.entries.map { it.label }, settings.previewQuality.ordinal) { settings.previewQuality = PreviewQuality.entries[it] }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Use preview copies of large videos", settings.previewCopies, enabled = true) { settings.previewCopies = it }
            }
            PreviewCopiesRow()
            Spacer(Modifier.height(15.dp))
            LinkRow(stringResource(R.string.settings_language), currentLanguage(LocalContext.current).nativeName) { language = true }
            BackupRows(settings, onRestored)
            Spacer(Modifier.height(15.dp))
            LinkRow(stringResource(R.string.settings_about)) { about = true }
            CaptionModelRow()
        }
    }
    if (language) LanguageDialog { language = false }
    if (about) AboutScreen { about = false }
    if (demoNote) AlertDialog(
        onDismissRequest = { demoNote = false },
        title = { Text(stringResource(R.string.demo_mode_is_on), color = Am.Navy) },
        text = { Text(stringResource(R.string.the_media_picker_lists_only_albums), color = Am.Navy) },
        confirmButton = { TextButton({ demoNote = false }) { Text(stringResource(R.string.ok), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
private fun PreviewCopiesRow() {
    val context = LocalContext.current
    val made by VideoProxies.revision.collectAsState()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(made) { bytes = withContext(Dispatchers.IO) { VideoProxies.bytes(context) } }
    Spacer(Modifier.height(15.dp))
    Row(
        Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(44.dp).clip(PanelShape).background(PanelColor)
            .clickable { VideoProxies.clear(context) }.padding(start = 15.dp, end = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Clear preview copies", Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp)
        Text(fileSize(bytes), color = Am.HomeMid, fontSize = 13.sp)
    }
}

@Composable
private fun CaptionModelRow() {
    val context = LocalContext.current
    var bytes by remember { mutableLongStateOf(CaptionModel.file(context).length()) }
    if (bytes == 0L) return
    Spacer(Modifier.height(15.dp))
    Row(
        Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(44.dp).clip(PanelShape).background(PanelColor)
            .clickable { CaptionModel.remove(context); bytes = CaptionModel.file(context).length() }.padding(start = 15.dp, end = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.remove_caption_model), Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp)
        Text(app.absentmotion.export.formatBytes(bytes), color = Am.HomeMid, fontSize = 13.sp)
    }
}

@Composable
private fun Divider(inset: androidx.compose.ui.unit.Dp) {
    Box(Modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(DividerColor))
}

@Composable
internal fun LinkRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(44.dp).clip(PanelShape).background(PanelColor)
            .clickable(onClick = onClick).padding(start = 15.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp)
        if (value != null) Text(value, color = Am.HomeMid, fontSize = 13.sp, maxLines = 1)
        Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).alpha(if (enabled) 1f else 0.35f).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        RefSwitch(checked, enabled, onChange)
    }
}

@Composable
private fun RefSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
    ThinSwitch(checked, enabled = enabled, offTrack = Am.SwitchOff, onTrack = Am.HomeAccent.copy(alpha = 0.5f), onThumb = Am.HomeAccent, onChange = onChange)

@Composable
private fun DurationRow(settings: AppSettings) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.new_layer_length), Modifier.weight(1f), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.size(90.dp, 37.dp)) {
            Row(
                Modifier.fillMaxSize().clip(shape).background(Am.Card).clickable { open = true }.padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${settings.defaultLayerDuration}s", Modifier.weight(1f), color = Am.Navy, fontSize = 16.sp)
                Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.Navy)
            }
            RefMenu(open, { open = false }, Am.Navy, shape, Modifier.width(90.dp), maxHeight = 327.dp) {
                AppSettings.LAYER_DURATIONS.forEachIndexed { i, seconds ->
                    val chosen = seconds == settings.defaultLayerDuration
                    Box(
                        Modifier.fillMaxWidth().height(40.dp).background(if (chosen) ChosenRow else Color.Transparent)
                            .clickable { settings.defaultLayerDuration = seconds; open = false }.padding(start = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("${seconds}s", color = Am.OnNavy, fontSize = 16.sp)
                    }
                    if (i < AppSettings.LAYER_DURATIONS.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(ListLineColor))
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(settings: AppSettings) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val scope = rememberCoroutineScope()
    val picker = rememberDocumentPicker { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { readThemeText(context, uri) }
                    settings.importTheme(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = (e as? ThemeError)?.text?.resolve(context) ?: context.getString(R.string.settings_theme_read_failed)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.theme), Modifier.weight(1f), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.size(150.dp, 37.dp)) {
            Row(
                Modifier.fillMaxSize().clip(shape).background(Am.Card).clickable { open = true }.padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(settings.themeLabel.text(), Modifier.weight(1f), color = Am.Navy, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.Navy)
            }
            RefMenu(open, { open = false }, Am.Navy, shape, Modifier.width(210.dp), maxHeight = 327.dp) {
                for ((id, label) in settings.themes) ThemeItem(label.text(), id == settings.theme) { settings.theme = id; open = false }
                Box(Modifier.fillMaxWidth().height(1.dp).background(ListLineColor))
                ThemeItem(stringResource(R.string.settings_import_theme), false) { open = false; picker.launch() }
            }
        }
    }
}

@Composable
private fun ThemeItem(label: String, chosen: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).background(if (chosen) ChosenRow else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, color = Am.OnNavy, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private fun readThemeText(context: Context, uri: Uri): String {
    val stream: java.io.InputStream? = try { context.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
    val bytes = stream?.use { it.readNBytes(Themes.MAX_BYTES + 1) } ?: throw ThemeError(txt(R.string.settings_theme_open_failed))
    if (bytes.size > Themes.MAX_BYTES) throw ThemeError(txt(R.string.settings_theme_not_theme_file))
    return String(bytes, Charsets.UTF_8)
}

@Composable
private fun Choices(labels: List<Int>, chosen: Int, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(38.dp).background(Am.Card, shape).padding(3.dp)) {
        for ((index, label) in labels.withIndex()) {
            val selected = index == chosen
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (selected) Am.Navy else Color.Transparent, shape).clickable { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label).uppercase(java.util.Locale.getDefault()), Modifier.padding(horizontal = 2.dp), color = if (selected) Am.OnNavyAccent else Am.Navy,
                    fontWeight = FontWeight.Medium, maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, 14.sp))
            }
        }
    }
}
