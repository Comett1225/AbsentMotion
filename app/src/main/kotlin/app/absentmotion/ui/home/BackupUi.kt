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

private class BackupTask(val title: String) {
    var progress by mutableFloatStateOf(0f)
    var result by mutableStateOf<String?>(null)
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
    fun run(title: String, stoppedText: String, after: () -> Unit = {}, work: (BackupTask) -> String) {
        if (task != null) return
        val started = BackupTask(title)
        task = started
        scope.launch {
            started.result = withContext(Dispatchers.IO) {
                try {
                    work(started)
                } catch (e: Stopped) {
                    stoppedText
                } catch (e: ImportFailed) {
                    e.message ?: "This file is not an AbsentMotion backup."
                } catch (e: Exception) {
                    Log.w("AMBackup", "$title failed", e)
                    "Something went wrong: ${e.message ?: e.javaClass.simpleName}. Check the free storage and try again."
                }
            }
            after()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run("Restoring a backup", "Restore stopped. Projects restored before you stopped are kept; restoring the same backup again finishes the rest.", onRestored) {
            restoreBackup(context, uri, it)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        LinkRow("Back up all projects") { run("Backing up all projects", "Backup stopped. No backup file was kept.") { backUp(context, it) } }
        Spacer(Modifier.height(6.dp))
        LinkRow("Restore from backup") { picker.launch(arrayOf("*/*")) }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Am.HomePanel)) {
            SwitchRow("Weekly automatic backup (projects only)", settings.weeklyBackup, enabled = true) { settings.weeklyBackup = it }
        }
    }
    task?.let { current ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(current.title, color = Am.Navy) },
            text = {
                Box(Modifier.fillMaxWidth().height(96.dp)) {
                    val result = current.result
                    if (result == null) Column {
                        LinearProgressIndicator(progress = { current.progress }, Modifier.fillMaxWidth().padding(top = 8.dp), color = Am.Accent)
                        Text("${(current.progress * 100).toInt()}%", Modifier.padding(top = 12.dp), color = Am.Navy, fontSize = 15.sp)
                    } else Text(result, color = Am.Navy, fontSize = 15.sp)
                }
            },
            confirmButton = {
                if (current.result == null) TextButton({ current.stopped = true }) { Text("Stop", color = Am.Navy) }
                else TextButton({ task = null }) { Text("OK", color = Am.Navy) }
            },
            containerColor = Am.Card,
        )
    }
}

private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))

private fun backUp(context: Context, task: BackupTask): String {
    val root = context.filesDir
    val needed = Backup.files(root, includeMedia = true).sumOf { it.second.length() }
    if (needed + (50L shl 20) > freeSpace()) return "Not enough free storage for the backup. It needs about ${fileSize(needed)}."
    val name = "AbsentMotion-backup-${stamp()}.${Backup.EXTENSION}"
    MediaStoreExport(context).save(null, name, "application/octet-stream") { out ->
        Backup.write(root, out, includeMedia = true) { done, total -> task.report(done.toFloat() / total) }
    }
    task.report(1f)
    return "Saved as Documents/AbsentMotion/$name. It stays on this phone even if AbsentMotion is uninstalled; copy it to a computer or cloud storage to keep it safe."
}

private fun restoreBackup(context: Context, uri: Uri, task: BackupTask): String {
    val size = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
    }.getOrNull() ?: -1L
    if (size > 0 && context.filesDir.usableSpace < size + (100L shl 20)) return "Not enough free storage to restore this backup. It needs about ${fileSize(size)}."
    val restored = (context.contentResolver.openInputStream(uri) ?: return "Could not open this file.").use { input ->
        Backup.restore(ProjectStore(context), context.filesDir, input) { read -> if (size > 0) task.report(read.toFloat() / size) else task.report(0f) }
    }
    task.report(1f)
    fun count(n: Int) = if (n == 1) "1 project" else "$n projects"
    return listOfNotNull(
        "Restored ${count(restored.added)}.",
        restored.renamed.takeIf { it > 0 }?.let { "${count(it)} were added as separate copies because a different version was already here." },
        restored.skipped.takeIf { it > 0 }?.let { "${count(it)} were already here and were left as they are." },
    ).joinToString(" ")
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
