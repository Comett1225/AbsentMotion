package app.absentmotion.ui.home

import app.absentmotion.platform.readAtMost
import app.absentmotion.export.formatBytes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.theme.Motion
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
internal val CardGap = 10.dp

@Composable
fun SettingsDrawer(settings: AppSettings, drawer: androidx.compose.material3.DrawerState, onRestored: () -> Unit = {}) {
    var about by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var crashLogs by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(false) }
    var demoNote by remember { mutableStateOf(false) }
    ModalDrawerSheet(
        drawer, Modifier.width(280.dp), drawerShape = RectangleShape, drawerContainerColor = Am.HomeDrawer,
        windowInsets = WindowInsets(0),
    ) {
        Column(Modifier.fillMaxSize().background(Am.Card).statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.settings), Modifier.fillMaxWidth().height(50.dp).wrapContentHeight(Alignment.CenterVertically).padding(start = 11.dp),
                color = Am.HomeInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Category(R.string.settings_group_editor, HomeIcons.Pencil) {
                ThemeRow(settings)
                DurationRow(settings)
                SwitchRow(stringResource(R.string.settings_vibration), settings.vibration, enabled = true) { settings.vibration = it }
                SwitchRow(stringResource(R.string.preview_cache_hint_setting), settings.previewCacheHint, enabled = true) { settings.previewCacheHint = it }
                Divider(10.dp)
                ChoicesBlock(R.string.settings_emoji) {
                    Choices(listOf(R.string.settings_emoji_system, R.string.settings_emoji_android), if (settings.androidEmoji) 1 else 0) { settings.androidEmoji = it == 1 }
                }
            }
            Category(R.string.settings_group_playback, HomeIcons.ProjectsTabOff) {
                ChoicesBlock(R.string.preview_quality) {
                    Choices(PreviewQuality.entries.map { it.label }, settings.previewQuality.ordinal) { settings.previewQuality = PreviewQuality.entries[it] }
                }
                Divider(10.dp)
                ChoicesBlock(R.string.playback_resampling) {
                    Choices(listOf(R.string.settings_level_low, R.string.settings_level_high, R.string.settings_level_ultra), settings.audioResampling) { settings.audioResampling = it }
                }
                Divider(10.dp)
                SwitchRow(stringResource(R.string.settings_preview_copies), settings.previewCopies, enabled = true) { settings.previewCopies = it }
                PreviewCopiesRow()
            }
            Category(R.string.settings_group_projects, HomeIcons.Folder) {
                SwitchRow(stringResource(R.string.settings_open_xml), settings.xmlUpload, enabled = true) { settings.xmlUpload = it }
                SwitchRow(stringResource(R.string.settings_keep_media_copies), settings.keepMediaCopies, enabled = true) { settings.keepMediaCopies = it }
                if (settings.demoModeShown) SwitchRow(stringResource(R.string.settings_demo_mode), settings.demoMode, enabled = true) { settings.demoMode = it; demoNote = it }
                CaptionModelRow()
                CutoutRows()
                AiModelRows()
                AiGpuRow()
            }
            Category(R.string.settings_group_backup, ChromeIcons.SavePreset) {
                BackupRows(settings, onRestored)
            }
            LinkRow(stringResource(R.string.settings_language), currentLanguage(LocalContext.current).nativeName) { language = true }
            Spacer(Modifier.height(CardGap))
            LinkRow(stringResource(R.string.settings_help)) { help = true }
            Spacer(Modifier.height(CardGap))
            app.absentmotion.ui.tutorial.TutorialRows(settings)
            Spacer(Modifier.height(CardGap))
            LinkRow(stringResource(R.string.settings_about)) { about = true }
            Spacer(Modifier.height(CardGap))
            UpdateRows(settings) { crashLogs = true }
        }
    }
    if (language) LanguageDialog { language = false }
    if (help) HelpScreen { help = false }
    if (about) AboutScreen { about = false }
    if (crashLogs) CrashLogsScreen { crashLogs = false }
    if (demoNote) AlertDialog(
        onDismissRequest = { demoNote = false },
        title = { Text(stringResource(R.string.demo_mode_is_on), color = Am.Navy) },
        text = { Text(stringResource(R.string.the_media_picker_lists_only_albums), color = Am.Navy) },
        confirmButton = { TextButton({ demoNote = false }) { Text(stringResource(R.string.ok), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
private fun Category(@StringRes name: Int, icon: ImageVector, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.enter())
    Column(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(PanelShape).background(PanelColor)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable { open = !open }
                .semantics { if (open) collapse { open = false; true } else expand { open = true; true } }
                .padding(start = 13.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = Am.HomeInk)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(name), Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp)
            Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp).rotate(turn), tint = Am.HomeMid)
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Divider(10.dp)
                content()
            }
        }
    }
    Spacer(Modifier.height(CardGap))
}

@Composable
private fun ChoicesBlock(@StringRes label: Int, choices: @Composable () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(stringResource(label), Modifier.padding(start = 10.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(10.dp))
    choices()
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PreviewCopiesRow() {
    val context = LocalContext.current
    val made by VideoProxies.revision.collectAsState()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(made) { bytes = withContext(Dispatchers.IO) { VideoProxies.bytes(context) } }
    ActionRow(stringResource(R.string.settings_clear_preview_copies), formatBytes(bytes), chevron = false) { VideoProxies.clear(context) }
}

@Composable
private fun CaptionModelRow() {
    val context = LocalContext.current
    var bytes by remember { mutableLongStateOf(CaptionModel.file(context).length()) }
    val downloaded = remember { bytes > 0L }
    if (!downloaded) return
    ActionRow(stringResource(R.string.remove_caption_model), formatBytes(bytes), chevron = false) { CaptionModel.remove(context); bytes = CaptionModel.file(context).length() }
}

@Composable
private fun AiModelRows() {
    val context = LocalContext.current
    var models by remember { mutableStateOf(app.absentmotion.ai.AiModel.downloaded(context) - app.absentmotion.ai.AiModel.Cutout) }
    for (model in models) {
        ActionRow(stringResource(R.string.ai_delete_model, stringResource(model.title)), formatBytes(model.onDisk(context)), chevron = false) {
            model.delete(context)
            models = app.absentmotion.ai.AiModel.downloaded(context) - app.absentmotion.ai.AiModel.Cutout
        }
    }
}

@Composable
private fun CutoutRows() {
    if (!app.absentmotion.ai.BestCutout.offered) return
    val context = LocalContext.current
    val model = app.absentmotion.ai.AiModel.Cutout
    val choice = remember { app.absentmotion.ai.CutoutChoice.of(context) }
    var chosen by remember { mutableStateOf(choice.saved) }
    var bytes by remember { mutableLongStateOf(model.onDisk(context)) }
    val qualities = app.absentmotion.ai.CutoutQuality.entries
    Divider(10.dp)
    ChoicesBlock(R.string.settings_cutout_quality) {
        Choices(qualities.map { it.title }, qualities.indexOf(chosen)) { choice.choose(qualities[it]); chosen = qualities[it] }
    }
    Divider(10.dp)
    if (bytes > 0L) ActionRow(stringResource(R.string.ai_delete_model, stringResource(model.title)), formatBytes(bytes), chevron = false) {
        model.delete(context)
        bytes = model.onDisk(context)
        choice.choose(app.absentmotion.ai.CutoutQuality.Standard)
        chosen = app.absentmotion.ai.CutoutQuality.Standard
    }
}

@Composable
private fun AiGpuRow() {
    if (!app.absentmotion.Tier.has(app.absentmotion.Feature.AiSmooth) && !app.absentmotion.Tier.has(app.absentmotion.Feature.AiEnhance)) return
    val context = LocalContext.current
    var state by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.Default) {
            if (app.absentmotion.ai.AiNative.gpu?.software == false) app.absentmotion.ai.AiDevice.gpuOn(context) to app.absentmotion.ai.AiDevice.gpuRisky(context) else null
        }
        loaded = true
    }
    val current = state
    if (current == null) {
        if (!loaded) Spacer(Modifier.height(44.dp))
        return
    }
    val (on, risky) = current
    fun choose(value: Boolean) {
        app.absentmotion.ai.AiDevice.setGpu(context, value)
        state = value to risky
    }
    SwitchRow(stringResource(R.string.settings_ai_gpu), on, enabled = true) { if (it && risky) ask = true else choose(it) }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(stringResource(R.string.settings_ai_gpu), color = Am.Navy) },
        text = { Text(stringResource(R.string.ai_gpu_warning), color = Am.Navy) },
        confirmButton = { TextButton({ ask = false; choose(true) }) { Text(stringResource(R.string.audiofx_turn_on), color = Am.Navy) } },
        dismissButton = { TextButton({ ask = false }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
private fun Divider(inset: androidx.compose.ui.unit.Dp) {
    Box(Modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(DividerColor))
}

@Composable
private fun UpdateRows(settings: AppSettings, onCrashLogs: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(PanelShape).background(PanelColor)) {
        ActionRow(stringResource(R.string.crash_logs), onClick = onCrashLogs)
        Divider(10.dp)
        SwitchRow(stringResource(R.string.update_check_on_open), settings.autoUpdateCheck, enabled = true) { settings.chooseUpdates(it) }
        Divider(10.dp)
        ActionRow(stringResource(R.string.update_check_now), busy = Updates.checking) { Updates.check(context, settings, manual = true) }
    }
}

@Composable
internal fun LinkRow(label: String, value: String? = null, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(PanelShape).background(PanelColor)) {
        ActionRow(label, value, chevron = true, onClick = onClick)
    }
}

@Composable
internal fun ActionRow(label: String, value: String? = null, chevron: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick).padding(start = 15.dp, end = if (chevron) 7.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 13.sp)
        if (value != null) Text(value, color = Am.HomeMid, fontSize = 13.sp, maxLines = 1)
        if (busy) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Am.HomeMid, strokeWidth = 2.dp)
        } else if (chevron) Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
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
internal fun RefSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
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
    val bytes = stream?.use { it.readAtMost(Themes.MAX_BYTES + 1) } ?: throw ThemeError(txt(R.string.settings_theme_open_failed))
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
