package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.desk.MenuKey
import app.absentmotion.desk.ModifierKeys
import app.absentmotion.desk.Shortcut
import app.absentmotion.i18n.AppLanguage
import app.absentmotion.i18n.applyLanguage
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.appVersion
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.editor.SizedBox
import app.absentmotion.ui.adaptive.AdaptiveSpace
import app.absentmotion.ui.adaptive.LocalAdaptiveSpace
import app.absentmotion.ui.editor.deskScrollbar
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.theme.Am
import java.util.Locale

enum class SettingsPage(val title: Int, val icon: () -> ImageVector) {
    Editor(R.string.settings_group_editor, { HomeIcons.Pencil }),
    Playback(R.string.settings_group_playback, { HomeIcons.ProjectsTabOff }),
    Projects(R.string.settings_group_projects, { HomeIcons.Folder }),
    Backup(R.string.settings_group_backup, { ChromeIcons.SavePreset }),
    Updates(R.string.desk_updates, { HomeIcons.Download }),
    Shortcuts(R.string.desk_shortcuts, { DeskIcons.Keyboard }),
    Language(R.string.settings_language, { DeskIcons.Globe }),
    Help(R.string.settings_help, { ExportIcons.Help }),
    Tutorial(R.string.desk_tutorial_page, { ChromeIcons.Play }),
    About(R.string.settings_about, { ExportIcons.Info }),
    Crash(R.string.crash_logs, { DeskIcons.Warning }),
}

internal object DeskIcons {
    val Globe = deskIcon("Globe", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12ZM2 12L22 12M12 2C14.5 4.7 15.8 8.2 15.8 12C15.8 15.8 14.5 19.3 12 22C9.5 19.3 8.2 15.8 8.2 12C8.2 8.2 9.5 4.7 12 2Z")
    val Warning = deskIcon("Warning", "M10.3 3.9L1.8 18A2 2 0 0 0 3.5 21L20.5 21A2 2 0 0 0 22.2 18L13.7 3.9A2 2 0 0 0 10.3 3.9ZM12 9L12 13M12 17L12.01 17")
    val Keyboard = deskIcon("Keyboard", "M4 6L20 6A2 2 0 0 1 22 8L22 16A2 2 0 0 1 20 18L4 18A2 2 0 0 1 2 16L2 8A2 2 0 0 1 4 6ZM6 10L6.01 10M10 10L10.01 10M14 10L14.01 10M18 10L18.01 10M8 14L16 14")
}

private fun deskIcon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(addPathNodes(path), pathFillType = PathFillType.NonZero, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

@Composable
internal fun DeskSettings(settings: AppSettings, initial: SettingsPage, onClose: () -> Unit, onRestored: () -> Unit) {
    var page by remember { mutableStateOf(initial) }
    val links = LocalUriHandler.current
    DeskModal(onClose) {
        DeskDialogHeader(stringResource(R.string.settings), onClose)
        SizedBox(Modifier.weight(1f).fillMaxWidth()) { maxWidth, _ ->
        val side = if (maxWidth < 720.dp) 224.dp else 236.dp
        val narrow = maxWidth < 720.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(side).fillMaxHeight().background(Am.HomeBg).deskVerticalScroll(color = Am.Navy).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (entry in SettingsPage.entries) {
                    if (entry == SettingsPage.Language) Spacer(Modifier.height(10.dp))
                    SideItem(stringResource(entry.title), entry.icon(), page == entry, narrow) { page = entry }
                }
                Spacer(Modifier.height(16.dp))
                val found = rememberReportLinks()
                SideLink(stringResource(R.string.settings_report_issue)) { runCatching { links.openUri(found.form) } }
                SideLink(stringResource(R.string.settings_suggest_feature)) { runCatching { links.openUri(found.feature) } }
                SideLink(stringResource(R.string.join_discord)) { runCatching { links.openUri(found.discord) } }
                val version = appVersion().trim()
                if (version.isNotEmpty()) Text("V $version", Modifier.padding(start = 10.dp, top = 6.dp), color = Am.HomeDim, fontSize = 12.sp)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Am.HomeLine))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalAdaptiveSpace provides AdaptiveSpace(maxWidth, maxHeight)) {
                    when (page) {
                        SettingsPage.Help -> DeskHelpPage()
                        SettingsPage.Tutorial -> DeskTutorialPage(settings, onClose)
                        SettingsPage.About -> DeskAbout(settings)
                        SettingsPage.Crash -> DeskCrashLogs()
                        SettingsPage.Language -> DeskLanguages()
                        SettingsPage.Shortcuts -> DeskShortcutsPage()
                        else -> Column(Modifier.fillMaxSize().deskVerticalScroll(color = Am.Navy).padding(24.dp)) {
                            Text(stringResource(page.title), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.HomePanel).padding(vertical = 6.dp)) {
                                when (page) {
                                    SettingsPage.Editor -> {
                                        EditorSettings(settings)
                                        var systemBar by remember { mutableStateOf(app.absentmotion.desk.DeskWindow.systemBarChosen) }
                                        SwitchRow(stringResource(R.string.desk_system_title_bar), systemBar, enabled = true) { systemBar = it; app.absentmotion.desk.DeskWindow.systemBarChosen = it }
                                        Text(stringResource(R.string.desk_system_title_bar_note), Modifier.padding(start = 15.dp, end = 15.dp, bottom = 8.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 16.sp)
                                    }
                                    SettingsPage.Playback -> PlaybackSettings(settings)
                                    SettingsPage.Projects -> ProjectsSettings(settings) {}
                                    SettingsPage.Backup -> BackupRows(settings, onRestored)
                                    else -> UpdateSettings(settings)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SideItem(label: String, icon: ImageVector, selected: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(shape).background(if (selected) Am.Navy else Color.Transparent).then(if (selected) Modifier else Modifier.deskPress(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) Am.OnNavy else Am.HomeMid)
        Text(label, Modifier.padding(start = 10.dp), color = if (selected) Am.OnNavy else Am.HomeInk, fontSize = if (narrow) 13.sp else 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SideLink(label: String, onClick: () -> Unit) {
    Text(
        label, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).deskPress(8.dp).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        color = Am.HomeMid, fontSize = 13.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
    )
}

@Composable
internal fun DeskLanguages() {
    val context = LocalContext.current
    val top = remember { app.absentmotion.desk.Strings.loaded }
    var chosen by remember { mutableStateOf(context.getSharedPreferences("language", android.content.Context.MODE_PRIVATE).getString("tag", null)?.let { currentLanguage(context) } ?: top) }
    val ui = remember { Locale.getDefault() }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.settings_language), Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(if (chosen != top) R.string.desk_language_restart else R.string.desk_language_note), Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 12.dp),
            color = if (chosen != top) Am.HomeAccent else Am.HomeMid, fontSize = 13.sp,
        )
        val languageList = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().deskScrollbar(languageList, Am.Navy).selectableGroup(), state = languageList, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AppLanguage.entries, key = { it.tag }) { language ->
                val on = language == chosen
                val local = remember(language) { Locale.forLanguageTag(language.tag).getDisplayName(ui).replaceFirstChar { it.titlecase(ui) } }
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(shape).background(Am.HomePanel).border(2.dp, if (on) Am.Navy else Color.Transparent, shape).deskPress(10.dp)
                        .selectable(on, role = Role.RadioButton) { chosen = language; applyLanguage(context, language) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(language.nativeName, color = Am.HomeInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (!local.equals(language.nativeName, ignoreCase = true)) Text(local, Modifier.padding(start = 12.dp).weight(1f), color = Am.HomeMid, fontSize = 13.sp, maxLines = 1)
                    else Spacer(Modifier.weight(1f))
                    if (on) Icon(PanelIcons.Check, null, Modifier.size(22.dp), tint = Am.Navy)
                }
            }
        }
    }
}

@Composable
private fun DeskTutorialPage(settings: AppSettings, onClose: () -> Unit) {
    val running = settings.tutorialStep != null
    Column(Modifier.fillMaxSize().deskVerticalScroll(color = Am.Navy).padding(24.dp)) {
        Text(stringResource(R.string.desk_tutorial_page), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.desk_tutorial_page_note), Modifier.padding(top = 6.dp, bottom = 16.dp), color = Am.HomeMid, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                DeskButton(stringResource(R.string.tutorial_settings_continue), { onClose(); app.absentmotion.ui.tutorial.Tutorial.start(resume = true) }, primary = true)
                DeskButton(stringResource(R.string.tutorial_settings_restart), { onClose(); app.absentmotion.ui.tutorial.Tutorial.start(resume = false) })
            } else DeskButton(stringResource(R.string.tutorial_settings_start), { onClose(); app.absentmotion.ui.tutorial.Tutorial.start(resume = false) }, primary = true)
        }
    }
}

@Composable
internal fun DeskShortcutsPage() {
    var capture by remember { mutableStateOf<Shortcut?>(null) }
    var clash by remember { mutableStateOf<Pair<Shortcut, List<MenuKey>>?>(null) }
    val custom = DeskShortcuts.custom
    DeskBack.Handler(capture != null || clash != null) { capture = null; clash = null }
    val listen = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val target = capture
        when {
            target == null -> false
            event.key in ModifierKeys || !DeskKeys.bindable(event) -> true
            else -> {
                val chord = DeskShortcuts.chord(event)
                capture = null
                if (DeskShortcuts.conflict(target, chord) == null) DeskShortcuts.bind(target, chord) else clash = target to listOf(chord)
                true
            }
        }
    }
    DisposableEffect(capture) {
        val active = capture != null
        val before = DeskKeys.viewer
        if (active) DeskKeys.viewer = { listen.value(it) }
        onDispose { if (active) DeskKeys.viewer = before }
    }
    Column(Modifier.fillMaxSize().deskVerticalScroll(color = Am.Navy).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.desk_shortcuts), Modifier.weight(1f), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            DeskButton(stringResource(R.string.desk_shortcuts_reset_all), { capture = null; clash = null; DeskShortcuts.resetAll() }, enabled = custom.isNotEmpty())
        }
        Text(stringResource(R.string.desk_shortcuts_note), Modifier.padding(top = 6.dp, bottom = 8.dp), color = Am.HomeMid, fontSize = 13.sp)
        for ((group, actions) in DeskShortcuts.shown.groupBy { it.group }) {
            Text(stringResource(group), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.HomePanel).padding(vertical = 6.dp)) {
                actions.forEachIndexed { i, action ->
                    if (i > 0) Divider(10.dp)
                    val pending = clash?.takeIf { it.first == action }?.second
                    ShortcutRow(
                        action, capture == action, action in custom, pending,
                        onPick = { clash = null; capture = if (capture == action) null else action },
                        onReset = { capture = null; clash = null; if (DeskShortcuts.conflict(action, action.defaults) == null) DeskShortcuts.reset(action) else clash = action to action.defaults },
                        onRemove = { capture = null; clash = null; DeskShortcuts.clear(action) },
                        onReplace = { pending?.let { DeskShortcuts.assign(action, it) }; clash = null },
                        onCancel = { clash = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(action: Shortcut, capturing: Boolean, changed: Boolean, pending: List<MenuKey>?, onPick: () -> Unit, onReset: () -> Unit, onRemove: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val lit = capturing || pending != null
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 15.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action.text, Modifier.weight(1f).padding(end = 8.dp), color = Am.HomeInk, fontSize = 14.sp)
            Box(
                Modifier.widthIn(min = 132.dp).height(32.dp).clip(shape).background(if (lit) Am.Navy else Am.Card).border(1.dp, Am.HomeLine, shape)
                    .deskPress(8.dp).clickable(role = Role.Button, onClick = onPick).padding(horizontal = 12.dp),
                Alignment.Center,
            ) {
                val shown = when {
                    capturing -> stringResource(R.string.desk_shortcuts_press)
                    pending != null -> pending.firstOrNull()?.let { app.absentmotion.desk.shortcutText(it, app.absentmotion.desk.DeskOs.mac) } ?: "–"
                    else -> DeskShortcuts.text(action).ifEmpty { "–" }
                }
                Text(shown, color = if (lit) Am.OnNavy else Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Box(Modifier.padding(start = 4.dp).size(32.dp), Alignment.Center) {
                if (DeskShortcuts.keys(action).isNotEmpty()) DeskIconButton(ChromeIcons.Close, stringResource(R.string.desk_shortcut_remove), onRemove, size = 32.dp, tint = Am.HomeMid)
            }
            Box(Modifier.size(32.dp), Alignment.Center) {
                if (changed) DeskIconButton(ChromeIcons.Undo, stringResource(R.string.audiofx_reset), onReset, size = 32.dp, tint = Am.HomeMid)
            }
        }
        if (pending != null) Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val other = DeskShortcuts.conflict(action, pending)?.text.orEmpty()
            Text(stringResource(R.string.desk_shortcuts_conflict, other), Modifier.weight(1f), color = Am.HomeAccent, fontSize = 13.sp)
            DeskButton(stringResource(R.string.cancel), onCancel)
            DeskButton(stringResource(R.string.template_replace), onReplace, primary = true)
        }
    }
}
