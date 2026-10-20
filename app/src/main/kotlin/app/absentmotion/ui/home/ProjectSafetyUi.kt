package app.absentmotion.ui.home

import app.absentmotion.export.formatBytes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.absentmotion.library.CrashEntry
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.ProjectVersions
import app.absentmotion.model.Scene
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.text
import app.absentmotion.i18n.txt

@Composable
fun OpeningOverlay(onCancel: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); shown = true; delay(4750); slow = true }
    Box(
        Modifier.fillMaxSize().background(if (shown) Color.Black.copy(alpha = 0.35f) else Color.Transparent).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        if (shown) Column(Modifier.size(220.dp, 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(44.dp), color = Am.Accent)
            if (slow) {
                Text(stringResource(R.string.still_working), Modifier.padding(top = 16.dp), color = Color.White, fontSize = 15.sp)
                TextButton(onCancel) { Text(stringResource(R.string.cancel), color = Color.White, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
fun VersionHistory(
    store: ProjectStore, id: String, title: String, engine: EngineThread, onRestore: (ProjectVersions.Version) -> Unit,
    onDelete: (() -> Unit)? = null, onDismiss: () -> Unit,
) {
    val versions by produceState<List<ProjectVersions.Version>?>(null, id) { value = withContext(Dispatchers.IO) { store.versions.list(id) } }
    var chosen by remember { mutableStateOf<ProjectVersions.Version?>(null) }
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(if (onDelete != null) R.string.library_damaged_project else R.string.version_history), color = Am.Navy)
                Text(title, color = Am.HomeMid, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            val list = versions
            Column(Modifier.fillMaxWidth()) {
                if (onDelete != null && list != null) Text(
                    stringResource(if (list.isEmpty()) R.string.damaged_explain_no_versions else R.string.damaged_explain_versions),
                    Modifier.padding(bottom = 8.dp), color = Am.Navy, fontSize = 15.sp,
                )
                if (list == null) Box(Modifier.fillMaxWidth().height(72.dp))
                else if (list.isEmpty()) {
                    if (onDelete == null) Text(stringResource(R.string.no_earlier_versions_yet_a_version), color = Am.Navy, fontSize = 15.sp)
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(list, key = { it.time }) { version -> VersionRow(version, engine) { chosen = version } }
                }
            }
        },
        dismissButton = onDelete?.let { { TextButton({ deleting = true }) { Text(stringResource(R.string.delete), color = Am.Navy) } } },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close), color = Am.Navy) } },
        containerColor = Am.Card,
    )
    if (deleting && onDelete != null) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.home_delete_project_title), color = Am.Navy) },
        dismissButton = { TextButton({ deleting = false }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
        confirmButton = { TextButton({ deleting = false; onDelete() }) { Text(stringResource(R.string.delete), color = Am.Navy) } },
        containerColor = Am.Card,
    )
    chosen?.let { version ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(stringResource(R.string.restore_this_version), color = Am.Navy) },
            text = { Text(versionRestoreText(version.time).text(), color = Am.Navy) },
            dismissButton = { TextButton({ chosen = null }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
            confirmButton = { TextButton({ chosen = null; onRestore(version) }) { Text(stringResource(R.string.restore), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

@Composable
private fun VersionRow(version: ProjectVersions.Version, engine: EngineThread, onRestore: () -> Unit) {
    val picture by produceState<ImageBitmap?>(null, version.file) { value = versionThumbnail(version, engine) }
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).checkerboard(4.dp)) {
            picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(versionLabel(version.time).text(), color = Am.Navy, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 16.sp,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(11.sp, 15.sp))
            Text(formatBytes(version.file.length()), color = Am.HomeMid, fontSize = 13.sp, maxLines = 1)
        }
        TextButton(onRestore) { Text(stringResource(R.string.restore), color = Am.Navy) }
    }
}

private suspend fun versionThumbnail(version: ProjectVersions.Version, engine: EngineThread): ImageBitmap? = try {
    val png = version.thumbnail
    val missing = withContext(Dispatchers.IO) { if (png.exists()) null else version.file.readText().let { it to Scene.parse(it) } }
    if (missing != null) {
        val (xml, scene) = missing
        val long = maxOf(scene.width, scene.height, 1)
        val temp = File(png.parentFile, png.name + ".tmp")
        if (engine.stillPng(xml, scene.totalTime / 2, (160 * scene.width / long).coerceAtLeast(1), (160 * scene.height / long).coerceAtLeast(1), temp)) withContext(Dispatchers.IO) { temp.renameTo(png) }
    }
    withContext(Dispatchers.IO) { BitmapFactory.decodeFile(png.path)?.asImageBitmap() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

internal fun versionLabel(time: Long, now: Long = System.currentTimeMillis()): UiText =
    versionMoment(time, now, R.string.safety_version_today, R.string.safety_version_yesterday, R.string.safety_version_on_date)

private fun versionRestoreText(time: Long, now: Long = System.currentTimeMillis()): UiText =
    versionMoment(time, now, R.string.safety_restore_today, R.string.safety_restore_yesterday, R.string.safety_restore_on_date)

private fun versionMoment(time: Long, now: Long, today: Int, yesterday: Int, other: Int): UiText {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(time).atZone(zone)
    val current = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (at.toLocalDate()) {
        current -> txt(today, clock)
        current.minusDays(1) -> txt(yesterday, clock)
        else -> txt(other, at.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).withDecimalStyle(DecimalStyle.STANDARD)), clock)
    }
}

@Composable
internal fun ClosedDialog(
    crash: CrashEntry?, recoveryTitle: String?, outdated: Outdated?, onRestore: () -> Unit, onDiscard: () -> Unit, onClose: () -> Unit,
) {
    val recovering = recoveryTitle != null
    AlertDialog(
        onDismissRequest = { if (!recovering) onClose() },
        title = { Text(stringResource(R.string.absentmotion_closed_unexpectedly), color = Am.Navy) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (crash != null) Text(closedText(crash.time).text() + " " + stringResource(crash.reason.sentence), color = Am.Navy)
                OutdatedHint(outdated, Modifier.padding(top = 8.dp))
                if (recoveryTitle != null) Text(
                    stringResource(R.string.safety_recovery_body, recoveryTitle.ifBlank { stringResource(R.string.recovery_your_project) }),
                    Modifier.padding(top = if (crash != null) 12.dp else 0.dp), color = Am.Navy,
                )
            }
        },
        dismissButton = if (!recovering) null else {
            { TextButton(onDiscard) { Text(stringResource(R.string.discard), color = Am.Navy) } }
        },
        confirmButton = {
            if (recovering) TextButton(onRestore) { Text(stringResource(R.string.restore), color = Am.Navy) }
            else TextButton(onClose) { Text(stringResource(R.string.ok), color = Am.Navy) }
        },
        containerColor = Am.Card,
    )
}

internal fun closedText(time: Long, now: Long = System.currentTimeMillis()): UiText =
    versionMoment(time, now, R.string.crash_closed_today, R.string.crash_closed_yesterday, R.string.crash_closed_on_date)

@Composable
internal fun OpenFailedDialog(onHistory: (() -> Unit)?, outdated: Outdated?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.could_not_open_this_project), color = Am.Navy) },
        text = {
            Column {
                Text(stringResource(if (onHistory != null) R.string.safety_open_failed_with_history else R.string.the_project_file_is_missing_or), color = Am.Navy)
                OutdatedHint(outdated, Modifier.padding(top = 8.dp))
            }
        },
        dismissButton = onHistory?.let { { TextButton(it) { Text(stringResource(R.string.version_history), color = Am.Navy) } } },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}
