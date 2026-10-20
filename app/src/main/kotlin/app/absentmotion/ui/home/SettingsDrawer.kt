package app.absentmotion.ui.home

import app.absentmotion.platform.Formatter
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
import app.absentmotion.ui.theme.Themes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.CaptionModel
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
fun SettingsDrawer(settings: AppSettings) {
    var about by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(false) }
    var demoNote by remember { mutableStateOf(false) }
    ModalDrawerSheet(
        Modifier.width(280.dp), drawerShape = RectangleShape, drawerContainerColor = Am.HomeDrawer,
        windowInsets = WindowInsets(0),
    ) {
        Column(Modifier.fillMaxWidth().background(Am.Card).statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Text(
                "Settings", Modifier.fillMaxWidth().height(50.dp).wrapContentHeight(Alignment.CenterVertically).padding(start = 11.dp),
                color = Am.HomeInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Column(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(PanelShape).background(PanelColor)) {
                SwitchRow("Demo Mode", settings.demoMode, enabled = true) { settings.demoMode = it; demoNote = it }
                SwitchRow("Open XML Projects", settings.xmlUpload, enabled = true) { settings.xmlUpload = it }
                SwitchRow("Vibration", settings.vibration, enabled = true) { settings.vibration = it }
                Divider(15.dp)
                DurationRow(settings)
                ThemeRow(settings)
                Spacer(Modifier.height(10.dp))
                Divider(10.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Playback Resampling", Modifier.padding(start = 10.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Choices(listOf("LOW", "HIGH", "ULTRA"), settings.audioResampling) { settings.audioResampling = it }
                Spacer(Modifier.height(8.dp))
                Divider(10.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Preview Quality", Modifier.padding(start = 10.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Choices(PreviewQuality.entries.map { it.name.uppercase() }, settings.previewQuality.ordinal) { settings.previewQuality = PreviewQuality.entries[it] }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(15.dp))
            LinkRow(stringResource(R.string.settings_language), currentLanguage(LocalContext.current).nativeName) { language = true }
            Spacer(Modifier.height(15.dp))
            LinkRow("About") { about = true }
            CaptionModelRow()
        }
    }
    if (language) LanguageDialog { language = false }
    if (about) AboutScreen { about = false }
    if (demoNote) AlertDialog(
        onDismissRequest = { demoNote = false },
        title = { Text("Demo Mode is on", color = Am.Navy) },
        text = { Text("The media picker lists only albums and folders whose name contains Demo.", color = Am.Navy) },
        confirmButton = { TextButton({ demoNote = false }) { Text("OK", color = Am.Navy) } },
        containerColor = Am.Card,
    )
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
        Text("Remove caption model", Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp)
        Text(Formatter.formatShortFileSize(context, bytes), color = Am.HomeMid, fontSize = 13.sp)
    }
}

@Composable
private fun Divider(inset: androidx.compose.ui.unit.Dp) {
    Box(Modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(DividerColor))
}

@Composable
private fun LinkRow(label: String, value: String? = null, onClick: () -> Unit) {
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
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
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
        Text("New Layer Length", Modifier.weight(1f), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    Toast.makeText(context, e.message ?: "Could not read this file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Theme", Modifier.weight(1f), color = Am.HomeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.size(150.dp, 37.dp)) {
            Row(
                Modifier.fillMaxSize().clip(shape).background(Am.Card).clickable { open = true }.padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(settings.themeLabel, Modifier.weight(1f), color = Am.Navy, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.Navy)
            }
            RefMenu(open, { open = false }, Am.Navy, shape, Modifier.width(210.dp), maxHeight = 327.dp) {
                for ((id, label) in settings.themes) ThemeItem(label, id == settings.theme) { settings.theme = id; open = false }
                Box(Modifier.fillMaxWidth().height(1.dp).background(ListLineColor))
                ThemeItem("Import theme file\u2026", false) { open = false; picker.launch() }
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
    val bytes = stream?.use { it.readNBytes(Themes.MAX_BYTES + 1) } ?: throw IllegalArgumentException("Could not open this file")
    require(bytes.size <= Themes.MAX_BYTES) { "This is not a theme file" }
    return String(bytes, Charsets.UTF_8)
}

@Composable
private fun Choices(labels: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(38.dp).background(Am.Card, shape).padding(3.dp)) {
        for ((index, label) in labels.withIndex()) {
            val selected = index == chosen
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (selected) Am.Navy else Color.Transparent, shape).clickable { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) Am.OnNavyAccent else Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
