package app.absentmotion.ui.home

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.i18n.text
import app.absentmotion.library.CrashEntry
import app.absentmotion.library.CrashLogs
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CrashLogsScreen(onClose: () -> Unit) {
    var open by remember { mutableStateOf<CrashEntry?>(null) }
    Dialog({ if (open != null) open = null else onClose() }, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Box(Modifier.fillMaxSize().background(Am.SheetBg)) {
                val shown = open
                if (shown == null) CrashList(onClose) { open = it } else CrashDetails(shown) { open = null }
            }
        }
    }
}

@Composable
private fun CrashList(onClose: () -> Unit, onOpen: (CrashEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    val logs by produceState<List<CrashEntry>?>(null, cleared) { value = withContext(Dispatchers.IO) { CrashLogs.list(context) } }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.crash_logs_delete_all), color = Am.Navy) },
        confirmButton = {
            TextButton({
                asking = false
                scope.launch {
                    withContext(Dispatchers.IO) { CrashLogs.clear(context) }
                    cleared++
                }
            }) { Text(stringResource(R.string.delete), color = Am.Navy) }
        },
        dismissButton = { TextButton({ asking = false }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
        containerColor = Am.Card,
    )
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBar(
            stringResource(R.string.crash_logs), HomeIcons.Close, stringResource(R.string.action_close), onClose,
            action = HomeIcons.Delete.takeIf { !logs.isNullOrEmpty() }, actionDescription = stringResource(R.string.delete_all),
        ) { asking = true }
        val list = logs
        if (list != null && list.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.crash_logs_empty), color = Am.HomeMid, fontSize = 14.sp)
        } else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list.orEmpty(), key = { it.file.name }) { log ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Am.Card).clickable { onOpen(log) }
                        .padding(start = 15.dp, end = 7.dp, top = 10.dp, bottom = 10.dp),
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
    val context = LocalContext.current
    val detail by produceState("", log.file) { value = withContext(Dispatchers.IO) { runCatching { log.file.readText() }.getOrDefault("") } }
    val sentence = stringResource(log.reason.sentence)
    val time = versionLabel(log.time).text()
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBar(stringResource(R.string.crash_logs), ExportIcons.Back, stringResource(R.string.home_back), onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(time, color = Am.HomeMid, fontSize = 13.sp)
            Text(sentence, Modifier.padding(top = 2.dp), color = Am.Navy, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(14.dp)).background(Am.Navy).clickable {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AbsentMotion", "$time\n$sentence\n\n$detail"))
                }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.crash_copy), color = Am.OnNavy, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.height(14.dp))
            SelectionContainer {
                Text(detail, color = Am.HomeInk, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
