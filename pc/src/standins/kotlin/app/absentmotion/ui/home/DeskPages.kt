package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.Tier
import app.absentmotion.desk.DeskBack
import app.absentmotion.i18n.text
import app.absentmotion.library.CrashEntry
import app.absentmotion.library.CrashLogs
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.appVersion
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun PageHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (onBack != null) 12.dp else 24.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) DeskIconButton(ExportIcons.Back, stringResource(R.string.home_back), onBack, tooltip = null)
        Text(title, Modifier.padding(start = if (onBack != null) 4.dp else 0.dp).weight(1f), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        trailing()
    }
}

@Composable
internal fun DeskAbout() {
    var licences by remember { mutableStateOf(false) }
    DeskBack.Handler(licences) { licences = false }
    if (licences) DeskLicences { licences = false } else AboutBody { licences = true }
}

@Composable
private fun AboutBody(onLicences: () -> Unit) {
    val version = appVersion()
    val links = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AbsentMotion", color = Am.Navy, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (version.isNotEmpty()) Text(stringResource(if (Tier.compatible) R.string.about_version_compatible else R.string.about_version, version), color = Am.HomeMid, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        AboutText(stringResource(R.string.about_licence_line))
        AboutText(stringResource(R.string.about_independent_note))
        AboutText(stringResource(R.string.about_compatibility_note))
        compatibleNote()?.let { AboutText(it) }
        Spacer(Modifier.height(4.dp))
        DiscordButton(Modifier.widthIn(max = 420.dp))
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Am.HomePanel).deskPress(10.dp).clickable(onClick = onLicences).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.open_source_licences), Modifier.weight(1f), color = Am.HomeInk, fontSize = 14.sp)
            Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
        }
        Spacer(Modifier.height(4.dp))
        AboutLink(stringResource(R.string.project_home_page), HOME_PAGE) { runCatching { links.openUri(it) } }
        AboutLink(stringResource(R.string.about_website), WEBSITE) { runCatching { links.openUri(it) } }
    }
}

@Composable
private fun AboutText(text: String) = Text(text, color = Am.HomeInk, fontSize = 14.sp, lineHeight = 21.sp)

@Composable
private fun AboutLink(label: String, url: String, open: (String) -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).deskPress(6.dp).clickable { open(url) }.padding(vertical = 4.dp)) {
        Text(label, color = Am.HomeMid, fontSize = 12.sp)
        Text(url, Modifier.padding(top = 2.dp), color = Am.HomeAccent, fontSize = 14.sp, textDecoration = TextDecoration.Underline)
    }
}

@Composable
private fun DeskLicences(onBack: () -> Unit) {
    val context = LocalContext.current
    val parts by produceState<List<LicencePart>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { licenceParts(context.assets.open(LICENCES_ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.open_source_licences), onBack)
        val shown = parts
        if (shown == null || shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Text(stringResource(if (shown == null) R.string.about_loading else R.string.about_licence_unreadable), color = Am.HomeMid, fontSize = 14.sp)
        } else SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                items(shown) { part ->
                    val gap = if (part.last) 12.dp else 0.dp
                    when (part.heading) {
                        0 -> Text(part.text, Modifier.padding(bottom = gap), color = Am.HomeInk, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                        else -> Text(
                            part.text, Modifier.padding(top = if (part.heading == 2) 16.dp else 6.dp, bottom = gap), color = Am.Navy,
                            fontSize = if (part.heading == 2) 18.sp else 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeskCrashLogs() {
    var open by remember { mutableStateOf<CrashEntry?>(null) }
    DeskBack.Handler(open != null) { open = null }
    val shown = open
    if (shown == null) CrashList { open = it } else CrashDetails(shown) { open = null }
}

@Composable
private fun CrashList(onOpen: (CrashEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    val logs by produceState<List<CrashEntry>?>(null, cleared) { value = withContext(Dispatchers.IO) { CrashLogs.list(context) } }
    if (asking) DeskConfirm(stringResource(R.string.crash_logs_delete_all), null, null, stringResource(R.string.delete), danger = true, onCancel = { asking = false }) {
        asking = false
        scope.launch { withContext(Dispatchers.IO) { CrashLogs.clear(context) }; cleared++ }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.crash_logs)) {
            if (!logs.isNullOrEmpty()) DeskButton(stringResource(R.string.delete_all), { asking = true }, danger = true)
        }
        val list = logs
        if (list != null && list.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Text(stringResource(R.string.crash_logs_empty), color = Am.HomeMid, fontSize = 14.sp)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.orEmpty(), key = { it.file.name }) { log ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Am.HomePanel).deskPress(10.dp).clickable { onOpen(log) }.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(versionLabel(log.time).text(), color = Am.HomeMid, fontSize = 12.sp)
                        Text(stringResource(log.reason.sentence), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                    Icon(HomeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.HomeMid)
                }
            }
        }
    }
}

@Composable
private fun CrashDetails(log: CrashEntry, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val detail by produceState("", log.file) { value = withContext(Dispatchers.IO) { CrashLogs.text(log) } }
    val sentence = stringResource(log.reason.sentence)
    val time = versionLabel(log.time).text()
    var reporting by remember { mutableStateOf(false) }
    if (reporting) CrashReportDialog(log) { reporting = false }
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.crash_logs), onBack) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskButton(stringResource(R.string.crash_copy), { clipboard.setText(AnnotatedString("$time\n$sentence\n\n$detail")) })
                DeskButton(stringResource(R.string.report_button), { reporting = true }, primary = true)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(time, color = Am.HomeMid, fontSize = 13.sp)
            Text(sentence, Modifier.padding(top = 2.dp), color = Am.Navy, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            SelectionContainer {
                Text(detail, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Am.HomePanel).padding(14.dp), color = Am.HomeInk, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
