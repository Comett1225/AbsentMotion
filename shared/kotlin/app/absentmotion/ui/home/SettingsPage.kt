package app.absentmotion.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.adaptive.AppLogo
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.delay

private val iconPaths = mapOf(
    "palette" to "M12.5,6.5a1.0,1.0 0 1,0 2.0,0a1.0,1.0 0 1,0 -2.0,0 M16.5,10.5a1.0,1.0 0 1,0 2.0,0a1.0,1.0 0 1,0 -2.0,0 M7.5,7.5a1.0,1.0 0 1,0 2.0,0a1.0,1.0 0 1,0 -2.0,0 M5.5,12.5a1.0,1.0 0 1,0 2.0,0a1.0,1.0 0 1,0 -2.0,0 M12 2a10 10 0 0 0 0 20c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1.1.9-2 2-2h2.4a4.6 4.6 0 0 0 4.6-4.6C22 6 17.5 2 12 2z",
    "editor" to "M3.0,6.0a3.0,3.0 0 1,0 6.0,0a3.0,3.0 0 1,0 -6.0,0 M3.0,18.0a3.0,3.0 0 1,0 6.0,0a3.0,3.0 0 1,0 -6.0,0 M20 4 8.1 15.9M14.5 14.5 20 20M8.1 8.1 12 12",
    "play" to "M7 4l13 8-13 8z",
    "folder" to "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    "ai" to "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z",
    "tools" to "M4.5,3.0h4.0a1.5,1.5 0 0 1 1.5,1.5v4.0a1.5,1.5 0 0 1 -1.5,1.5h-4.0a1.5,1.5 0 0 1 -1.5,-1.5v-4.0a1.5,1.5 0 0 1 1.5,-1.5z M15.5,3.0h4.0a1.5,1.5 0 0 1 1.5,1.5v4.0a1.5,1.5 0 0 1 -1.5,1.5h-4.0a1.5,1.5 0 0 1 -1.5,-1.5v-4.0a1.5,1.5 0 0 1 1.5,-1.5z M4.5,14.0h4.0a1.5,1.5 0 0 1 1.5,1.5v4.0a1.5,1.5 0 0 1 -1.5,1.5h-4.0a1.5,1.5 0 0 1 -1.5,-1.5v-4.0a1.5,1.5 0 0 1 1.5,-1.5z M15.5,14.0h4.0a1.5,1.5 0 0 1 1.5,1.5v4.0a1.5,1.5 0 0 1 -1.5,1.5h-4.0a1.5,1.5 0 0 1 -1.5,-1.5v-4.0a1.5,1.5 0 0 1 1.5,-1.5z",
    "backup" to "M20 16.5A4.5 4.5 0 0 0 17.5 8H16A6 6 0 1 0 4 14.3 M12 12v8M9 15l3-3 3 3",
    "globe" to "M3.0,12.0a9.0,9.0 0 1,0 18.0,0a9.0,9.0 0 1,0 -18.0,0 M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18",
    "help" to "M3.0,12.0a9.0,9.0 0 1,0 18.0,0a9.0,9.0 0 1,0 -18.0,0 M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 1-1 1.7M12 17h.01",
    "info" to "M3.0,12.0a9.0,9.0 0 1,0 18.0,0a9.0,9.0 0 1,0 -18.0,0 M12 11v6M12 7.5h.01",
    "chev" to "M9 6l6 6-6 6",
    "back" to "M19 12H5M11 6l-6 6 6 6",
    "alert" to "M12 8v5M12 16h.01 M10.3 3.9L2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
    "bulb" to "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z",
    "chat" to "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z",
    "flag" to "M5 21V4M5 4h11l-2 4 2 4H5",
)

private val icons = HashMap<String, ImageVector>()

private fun icon(name: String): ImageVector = icons.getOrPut(name) {
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(iconPaths.getValue(name)), fill = null, stroke = SolidColor(Color.White),
        strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}

internal enum class SettingsSection(@StringRes val title: Int, @StringRes val summary: Int, val icon: String, val tint: Color) {
    Customization(R.string.settings_section_customization, R.string.settings_summary_customization, "palette", Color(0xFFDB5322)),
    Editor(R.string.settings_group_editor, R.string.settings_summary_editor, "editor", Color(0xFF3B82F6)),
    Playback(R.string.settings_group_playback, R.string.settings_summary_playback, "play", Color(0xFF22A06B)),
    Projects(R.string.settings_group_projects, R.string.settings_summary_projects, "folder", Color(0xFFD98A0B)),
    Ai(R.string.settings_section_ai, R.string.settings_summary_ai, "ai", Color(0xFF8B5CF6)),
    Backup(R.string.settings_group_backup, R.string.settings_summary_backup, "backup", Color(0xFF6366F1)),
    Tools(R.string.home_tab_tools, R.string.settings_summary_tools, "tools", Color(0xFF0EA5A5)),
    Language(R.string.settings_language, 0, "globe", Color(0xFFE0508F)),
    Help(R.string.settings_help, R.string.settings_summary_help, "help", Color(0xFF64748B)),
    Advanced(R.string.settings_section_advanced, R.string.settings_summary_advanced, "alert", Color(0xFFE0A100)),
}

private val Ease = tween<Float>(280, easing = FastOutSlowInEasing)

@Composable
internal fun SettingsPage(settings: AppSettings, onRestored: () -> Unit, onClose: () -> Unit) {
    var open by remember { mutableStateOf<SettingsSection?>(null) }
    var about by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var crashLogs by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(false) }
    var demoNote by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    val mainScroll = rememberScrollState()
    BackHandler { if (open != null) open = null else onClose() }
    fun choose(section: SettingsSection) {
        when (section) {
            SettingsSection.Tools -> tools = true
            SettingsSection.Language -> language = true
            else -> open = section
        }
    }
    Box(Modifier.fillMaxSize().background(Am.HomeBg).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            AnimatedContent(open, transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(240, 60))) togetherWith
                    (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(160)))
            }, label = "settings") { page ->
                if (page == null) Main(settings, mainScroll, ::choose, { whatsNew = true }, { about = true }, { crashLogs = true }, onClose)
                else Page(page, settings, onRestored, { help = true }, { demoNote = it }) { open = null }
            }
        }
    }
    if (language) app.absentmotion.ui.onboarding.LanguageDialog { language = false }
    if (tools) CustomizeToolsSheet(settings) { tools = false }
    if (help) HelpScreen { help = false }
    if (about) AboutScreen(settings) { about = false }
    if (crashLogs) CrashLogsScreen { crashLogs = false }
    if (whatsNew) CurrentWhatsNew(settings) { whatsNew = false }
    if (demoNote) AlertDialog(
        onDismissRequest = { demoNote = false },
        title = { Text(stringResource(R.string.demo_mode_is_on), color = Am.Navy) },
        text = { Text(stringResource(R.string.the_media_picker_lists_only_albums), color = Am.Navy) },
        confirmButton = { DialogPill(stringResource(R.string.ok)) { demoNote = false } },
        containerColor = Am.Card,
    )
}

@Composable
private fun Header(title: String, scroll: ScrollState, onBack: () -> Unit) {
    val lined by animateFloatAsState(if (scroll.value > 4) 1f else 0f, Ease, label = "line")
    Column {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(icon("back"), stringResource(R.string.home_back), Modifier.size(24.dp), tint = Am.HomeInk)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, color = Am.HomeInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = lined }.background(Am.HomeLine))
    }
}

@Composable
private fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(source, androidx.compose.foundation.LocalIndication.current, onClick = onClick)
}

@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(25L * index); shown.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 24.dp.toPx() }) { content() }
}

@Composable
private fun Badge(name: String, tint: Color, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon(name), null, Modifier.size((size * 0.52f).dp), tint = tint)
    }
}

@Composable
private fun summary(section: SettingsSection): String =
    if (section == SettingsSection.Language) currentLanguage(LocalContext.current).nativeName else stringResource(section.summary)

@Composable
private fun Main(
    settings: AppSettings, scroll: ScrollState, onOpen: (SettingsSection) -> Unit, onWhatsNew: () -> Unit,
    onAbout: () -> Unit, onCrashLogs: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.settings), scroll, onClose)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var i = 0
            Appear(i++) { AppCard(settings, onWhatsNew) }
            Appear(i++) { WideTile(SettingsSection.Customization, swatches = true) { onOpen(SettingsSection.Customization) } }
            listOf(SettingsSection.Editor, SettingsSection.Playback, SettingsSection.Projects, SettingsSection.Ai, SettingsSection.Backup,
                SettingsSection.Tools, SettingsSection.Language, SettingsSection.Help).chunked(2).forEach { pair ->
                Appear(i++) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { section -> Tile(section, Modifier.weight(1f)) { onOpen(section) } }
                    }
                }
            }
            Appear(i++) { WideTile(SettingsSection.Advanced) { onOpen(SettingsSection.Advanced) } }
            Appear(i++) { Text(stringResource(R.string.settings_section_support), Modifier.padding(start = 4.dp, top = 8.dp), color = Am.HomeDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Appear(i++) { SupportCard(onCrashLogs) }
            Appear(i) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Am.Card).pressable(onAbout).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Badge("info", Color(0xFF8A8F98), 36)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_about), Modifier.weight(1f), color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(app.absentmotion.platform.appVersion().trim(), color = Am.HomeDim, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Icon(icon("chev"), null, Modifier.size(18.dp), tint = Am.HomeMid)
                }
            }
        }
    }
}

@Composable
private fun AppCard(settings: AppSettings, onWhatsNew: () -> Unit) {
    val context = LocalContext.current
    val version = app.absentmotion.platform.appVersion().trim()
    val notes = remember { WhatsNewNotes.currentName(context) != null }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Am.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("AbsentMotion", color = Am.HomeInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                if (version.isNotEmpty()) Text(stringResource(R.string.about_version, version), color = Am.HomeDim, fontSize = 14.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardButton(stringResource(R.string.update_check_now), Modifier.weight(1f), primary = true, busy = Updates.checking) { Updates.check(context, settings, manual = true) }
            if (notes) CardButton(stringResource(R.string.settings_whats_new), Modifier.weight(1f), primary = false, onClick = onWhatsNew)
        }
    }
}

@Composable
private fun CardButton(label: String, modifier: Modifier, primary: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(if (primary) Am.Navy else Am.HomePanel).pressable(onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) {
        val ink = if (primary) Am.OnNavy else Am.HomeInk
        Text(label, Modifier.alpha(if (busy) 0f else 1f), color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
    }
}

@Composable
private fun WideTile(section: SettingsSection, swatches: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Am.Card).pressable(onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(section.icon, section.tint, 44)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(section.title), color = Am.HomeInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(summary(section), color = Am.HomeDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (swatches) Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            listOf(Am.Accent, Am.HomeAccent, Am.Navy, Am.TopBar).forEach { Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, Am.Card, CircleShape).background(it)) }
        } else Icon(icon("chev"), null, Modifier.size(18.dp), tint = Am.HomeMid)
    }
}

@Composable
private fun Tile(section: SettingsSection, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(132.dp).clip(RoundedCornerShape(20.dp)).background(Am.Card).pressable(onClick).padding(14.dp)) {
        Badge(section.icon, section.tint, 38)
        Spacer(Modifier.weight(1f))
        Text(stringResource(section.title), color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(summary(section), color = Am.HomeDim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SupportCard(onCrashLogs: () -> Unit) {
    val links = LocalUriHandler.current
    val found = rememberReportLinks()
    val rows = listOf(
        Triple("flag", R.string.settings_report_issue, Color(0xFFE5484D)) to { runCatching { links.openUri(found.form) }; Unit },
        Triple("bulb", R.string.settings_suggest_feature, Color(0xFFE0A100)) to { runCatching { links.openUri(found.feature) }; Unit },
        Triple("chat", R.string.join_discord, Color(0xFF5865F2)) to { runCatching { links.openUri(found.discord) }; Unit },
        Triple("alert", R.string.crash_logs, Color(0xFF8A8F98)) to onCrashLogs,
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Am.Card)) {
        rows.forEachIndexed { i, (row, action) ->
            val (name, label, tint) = row
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp).height(1.dp).background(Am.HomeLine))
            Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(name, tint, 36)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(label), Modifier.weight(1f), color = Am.HomeInk, fontSize = 15.sp)
                Icon(icon("chev"), null, Modifier.size(18.dp), tint = Am.HomeMid)
            }
        }
    }
}

@Composable
private fun Page(
    section: SettingsSection, settings: AppSettings, onRestored: () -> Unit, onHelp: () -> Unit, onDemoMode: (Boolean) -> Unit, onBack: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(section.title), scroll, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Appear(0) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(section.icon, section.tint, 40)
                    Spacer(Modifier.width(12.dp))
                    Text(summary(section), color = Am.HomeMid, fontSize = 14.sp)
                }
            }
            Appear(1) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(OpenColor).padding(vertical = 6.dp)) {
                    when (section) {
                        SettingsSection.Customization -> CustomizationRows(settings)
                        SettingsSection.Editor -> EditorRows(settings)
                        SettingsSection.Playback -> {
                            PreviewRows(settings)
                            Divider(10.dp)
                            PreviewCopyRows(settings)
                        }
                        SettingsSection.Projects -> ProjectRows(settings)
                        SettingsSection.Ai -> AiRows()
                        SettingsSection.Backup -> BackupRows(settings, onRestored)
                        SettingsSection.Help -> {
                            LinkRow(stringResource(R.string.settings_help), onClick = onHelp)
                            app.absentmotion.ui.tutorial.TutorialRows(settings)
                        }
                        SettingsSection.Advanced -> {
                            SwitchRow(stringResource(R.string.update_check_on_open), settings.autoUpdateCheck, enabled = true) { settings.chooseUpdates(it) }
                            Divider(10.dp)
                            PlaybackAdvancedRows(settings)
                            DemoModeRow(settings, onDemoMode)
                            AiGpuRow()
                        }
                        SettingsSection.Tools, SettingsSection.Language -> Unit
                    }
                }
            }
        }
    }
}
