package app.absentmotion.ui.home

import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.text
import app.absentmotion.i18n.txt
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.freeSpace
import app.absentmotion.library.AppSettings
import app.absentmotion.library.Backup
import app.absentmotion.library.ImportFailed
import app.absentmotion.library.ProjectStore
import app.absentmotion.platform.ActivityResultContracts
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Log
import app.absentmotion.platform.Uri
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private class BackupTask(val title: UiText) {
    var progress by mutableFloatStateOf(0f)
    var result by mutableStateOf<List<UiText>?>(null)
    @Volatile var stopped = false

    fun report(fraction: Float) {
        if (stopped) throw Stopped()
        if (fraction - progress >= 0.005f || fraction >= 1f) progress = fraction
    }
}

private class Stopped : RuntimeException("stopped")

@Composable
internal fun BackupRows(settings: AppSettings, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<BackupTask?>(null) }
    fun run(title: UiText, stoppedText: UiText, after: () -> Unit = {}, work: (BackupTask) -> List<UiText>) {
        if (task != null) return
        val started = BackupTask(title)
        task = started
        scope.launch {
            started.result = withContext(Dispatchers.IO) {
                try {
                    work(started)
                } catch (e: Stopped) {
                    listOf(stoppedText)
                } catch (e: ImportFailed) {
                    listOf(e.text)
                } catch (e: Exception) {
                    Log.w("AMBackup", "backup task failed", e)
                    listOf(txt(R.string.backup_error_generic, e.message ?: e.javaClass.simpleName))
                }
            }
            after()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run(txt(R.string.backup_restoring_title), txt(R.string.backup_restore_stopped), onRestored) {
            restoreBackup(context, uri, it)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        LinkRow(stringResource(R.string.backup_all_projects)) { run(txt(R.string.backup_backing_up_title), txt(R.string.backup_stopped)) { backUp(context, it) } }
        Spacer(Modifier.height(6.dp))
        LinkRow(stringResource(R.string.backup_restore)) { picker.launch(arrayOf("*/*")) }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Am.HomePanel)) {
            SwitchRow(stringResource(R.string.backup_weekly), settings.weeklyBackup, enabled = true) { settings.weeklyBackup = it }
        }
    }
    task?.let { current ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(current.title.text(), color = Am.Navy) },
            text = {
                Box(Modifier.fillMaxWidth().height(96.dp)) {
                    val result = current.result
                    if (result == null) Column {
                        LinearProgressIndicator(progress = { current.progress }, Modifier.fillMaxWidth().padding(top = 8.dp), color = Am.Accent)
                        Text("${(current.progress * 100).toInt()}%", Modifier.padding(top = 12.dp), color = Am.Navy, fontSize = 15.sp)
                    } else Text(result.map { it.text() }.joinToString(" "), color = Am.Navy, fontSize = 15.sp)
                }
            },
            confirmButton = {
                if (current.result == null) TextButton({ current.stopped = true }) { Text(stringResource(R.string.stop), color = Am.Navy) }
                else TextButton({ task = null }) { Text(stringResource(R.string.ok), color = Am.Navy) }
            },
            containerColor = Am.Card,
        )
    }
}

private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))

private fun backUp(context: Context, task: BackupTask): List<UiText> {
    val root = context.filesDir
    val ai = context.getExternalFilesDir(null)?.resolve("ai")
    val needed = Backup.files(root, includeMedia = true, ai).sumOf { it.second.length() }
    if (needed + (50L shl 20) > freeSpace()) return listOf(txt(R.string.backup_no_space, fileSize(needed)))
    val name = "AbsentMotion-backup-${stamp()}.${Backup.EXTENSION}"
    MediaStoreExport(context).save(null, name, "application/octet-stream") { out ->
        Backup.write(root, out, includeMedia = true, ai) { done, total -> task.report(done.toFloat() / total) }
    }
    task.report(1f)
    return listOf(txt(R.string.backup_saved, name))
}

private fun restoreBackup(context: Context, uri: Uri, task: BackupTask): List<UiText> {
    val size = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
    }.getOrNull() ?: -1L
    if (size > 0 && context.filesDir.usableSpace < size + (100L shl 20)) return listOf(txt(R.string.backup_restore_no_space, fileSize(size)))
    val restored = (context.contentResolver.openInputStream(uri) ?: return listOf(txt(R.string.backup_open_failed))).use { input ->
        Backup.restore(ProjectStore(context), context.filesDir, input, ai = context.getExternalFilesDir(null)?.resolve("ai")) { read -> if (size > 0) task.report(read.toFloat() / size) else task.report(0f) }
    }
    task.report(1f)
    fun count(n: Int) = UiText.plural(R.plurals.backup_projects, n, n.toString())
    return listOfNotNull(
        txt(R.string.backup_restored, count(restored.added)),
        restored.renamed.takeIf { it > 0 }?.let { txt(R.string.backup_restored_copies, count(it)) },
        restored.skipped.takeIf { it > 0 }?.let { txt(R.string.backup_restored_skipped, count(it)) },
    )
}

suspend fun autoBackupIfDue(context: Context, settings: AppSettings) = withContext(Dispatchers.IO) {
    if (!settings.weeklyBackup || System.currentTimeMillis() - settings.lastAutoBackup < WEEK_MS) return@withContext
    try {
        val name = "AbsentMotion-weekly-${stamp()}.${Backup.EXTENSION}"
        val saved = MediaStoreExport(context).save(null, name, "application/octet-stream") { Backup.write(context.filesDir, it, includeMedia = false) }
        settings.autoBackupUri?.takeIf { it != saved.toString() }?.let { old -> runCatching { context.contentResolver.delete(Uri.parse(old), null, null) } }
        settings.autoBackupUri = saved.toString()
        settings.lastAutoBackup = System.currentTimeMillis()
        Log.i("AMBackup", "weekly backup written: $name")
    } catch (e: Exception) {
        Log.w("AMBackup", "weekly backup failed", e)
    }
}

private const val WEEK_MS = 7L * 24 * 3600 * 1000
