package app.absentmotion.ui.home

import app.absentmotion.export.formatBytes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.absentmotion.library.CrashEntry
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.ProjectVersions
import app.absentmotion.model.Scene
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Log
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

internal class HistoryStyle(
    val bar: Color, val surface: Color, val ink: Color, val dim: Color, val line: Color, val edge: Color,
    val accent: Color, val onAccent: Color, val button: Color, val onButton: Color, val well: Color, val danger: Color,
)

private fun historyStyle(editor: Boolean) = if (editor) HistoryStyle(
    bar = Am.TopBar, surface = Am.Panel, ink = Am.OnDark, dim = Am.OnDarkDim, line = Am.Line, edge = Am.FieldHi,
    accent = Am.Accent, onAccent = Am.OnAccent, button = Am.Button, onButton = Am.OnDark, well = Am.Deepest, danger = Am.Warning,
) else HistoryStyle(
    bar = Am.Card, surface = Am.Card, ink = Am.Navy, dim = Am.HomeMid, line = Am.HomeLine, edge = Am.HomeLine,
    accent = Am.Navy, onAccent = Am.OnNavy, button = Am.HomePanel, onButton = Am.Navy, well = Am.HomePanel, danger = Am.HomeAccent,
)

internal object VersionPanel {
    const val WIDTH = 560f
    const val CONFIRM_WIDTH = 420f
    const val HEIGHT = 720f
    private const val ROOMY = 600f
    private const val TALL = 500f
    private const val WIDE_THUMB = 72f
    private const val THUMB = 64f
    private const val ROW_CHROME = 72f
    private const val TEXT_NEEDS = 220f

    fun width(available: Float, wanted: Float = WIDTH): Float {
        val margin = if (available >= ROOMY) 32f else 16f
        return (available - 2 * margin).coerceAtLeast(0f).coerceAtMost(wanted)
    }

    fun height(available: Float): Float {
        val margin = if (available >= TALL) 24f else 12f
        return (available - 2 * margin).coerceAtLeast(0f).coerceAtMost(HEIGHT)
    }

    fun thumb(panelWidth: Float): Float = if (panelWidth >= 420f) WIDE_THUMB else THUMB

    fun stacked(rowWidth: Float, fontScale: Float): Boolean = fontScale.coerceAtLeast(1f) * TEXT_NEEDS > rowWidth - ROW_CHROME
}

private data class VersionItem(val version: ProjectVersions.Version, val bytes: Long)

@Composable
fun VersionHistory(
    store: ProjectStore, id: String, title: String, engine: EngineThread, onRestore: (ProjectVersions.Version) -> Unit,
    onDelete: (() -> Unit)? = null, editor: Boolean = false, onDismiss: () -> Unit,
) {
    val style = historyStyle(editor)
    val items by produceState<List<VersionItem>?>(null, id) {
        value = withContext(Dispatchers.IO) {
            try {
                store.versions.list(id).map { VersionItem(it, it.file.length()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AMVersions", "cannot list the versions of $id", e)
                emptyList()
            }
        }
    }
    var chosenTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val damaged = onDelete != null
    val list = items
    val chosen = chosenTime?.let { time -> list?.firstOrNull { it.version.time == time }?.version }
    if (chosenTime != null && list != null && chosen == null) LaunchedEffect(list) { chosenTime = null }
    SquareDialog(style, VersionPanel.WIDTH, onDismiss) { width ->
        Column(Modifier.fillMaxWidth().background(style.bar).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(if (damaged) R.string.library_damaged_project else R.string.version_history), Modifier.semantics { heading() },
                color = style.ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
            )
            if (title.isNotBlank()) Text(title, Modifier.padding(top = 2.dp), color = style.dim, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Rule(style)
        if (list == null) Box(Modifier.fillMaxWidth().heightIn(min = 112.dp).weight(1f, fill = false), Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = style.accent, strokeWidth = 2.dp)
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (damaged) item(key = "explain") {
                Text(
                    stringResource(if (list.isEmpty()) R.string.damaged_explain_no_versions else R.string.damaged_explain_versions),
                    Modifier.fillMaxWidth().padding(16.dp), color = style.ink, fontSize = 15.sp, lineHeight = 21.sp,
                )
            } else if (list.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.no_earlier_versions_yet_a_version), Modifier.fillMaxWidth().padding(16.dp), color = style.ink, fontSize = 15.sp, lineHeight = 21.sp)
            }
            itemsIndexed(list, key = { _, item -> item.version.time }) { index, item ->
                Column(Modifier.fillMaxWidth()) {
                    if (index > 0 || damaged) Rule(style)
                    VersionRow(style, item, engine, width) { chosenTime = item.version.time }
                }
            }
        }
        Rule(style)
        Row(Modifier.fillMaxWidth().background(style.bar).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (damaged) {
                SquareButton(stringResource(R.string.delete), null, style.danger, style.danger, Modifier.weight(1f)) { deleting = true }
                SquareButton(stringResource(R.string.action_close), style.button, style.onButton, null, Modifier.weight(1f), onDismiss)
            } else {
                Spacer(Modifier.weight(1f))
                SquareButton(stringResource(R.string.action_close), style.button, style.onButton, null, Modifier.widthIn(min = 120.dp), onDismiss)
            }
        }
    }
    if (deleting && onDelete != null) SquareDialog(style, VersionPanel.CONFIRM_WIDTH, { deleting = false }) {
        Confirmation(
            style, stringResource(R.string.home_delete_project_title), null, stringResource(R.string.delete), destructive = true,
            onCancel = { deleting = false }, onConfirm = { if (deleting) { deleting = false; onDelete() } },
        )
    }
    chosen?.let { version ->
        SquareDialog(style, VersionPanel.CONFIRM_WIDTH, { chosenTime = null }) {
            Confirmation(
                style, stringResource(R.string.restore_this_version), versionRestoreText(version.time).text(), stringResource(R.string.restore), destructive = false,
                onCancel = { chosenTime = null }, onConfirm = { if (chosenTime != null) { chosenTime = null; onRestore(version) } },
            )
        }
    }
}

@Composable
private fun SquareDialog(style: HistoryStyle, wanted: Float, onDismiss: () -> Unit, content: @Composable ColumnScope.(Float) -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { dismiss() } }, contentAlignment = Alignment.Center) {
            val width = VersionPanel.width(maxWidth.value, wanted)
            val height = VersionPanel.height(maxHeight.value)
            Column(Modifier.width(width.dp).heightIn(max = height.dp).background(style.surface).border(1.dp, style.edge).pointerInput(Unit) { detectTapGestures { } }) {
                content(width)
            }
        }
    }
}

@Composable
private fun ColumnScope.Confirmation(
    style: HistoryStyle, title: String, body: String?, confirm: String, destructive: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).background(style.surface).padding(16.dp)) {
        Text(title, Modifier.semantics { heading() }, color = style.ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
        if (body != null) Text(body, Modifier.padding(top = 8.dp), color = style.ink, fontSize = 15.sp, lineHeight = 21.sp)
    }
    Rule(style)
    Row(Modifier.fillMaxWidth().background(style.bar).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SquareButton(stringResource(R.string.cancel), style.button, style.onButton, null, Modifier.weight(1f), onCancel)
        if (destructive) SquareButton(confirm, null, style.danger, style.danger, Modifier.weight(1f), onConfirm)
        else SquareButton(confirm, style.accent, style.onAccent, null, Modifier.weight(1f), onConfirm)
    }
}

@Composable
private fun Rule(style: HistoryStyle) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(style.line))
}

@Composable
private fun SquareButton(label: String, fill: Color?, ink: Color, edge: Color?, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).then(if (fill != null) Modifier.background(fill) else Modifier).then(if (edge != null) Modifier.border(1.dp, edge) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VersionRow(style: HistoryStyle, item: VersionItem, engine: EngineThread, panelWidth: Float, onRestore: () -> Unit) {
    val version = item.version
    val picture by produceState<ImageBitmap?>(null, version.file) { value = versionThumbnail(version, engine) }
    val thumb = VersionPanel.thumb(panelWidth)
    val stacked = VersionPanel.stacked(panelWidth, LocalDensity.current.fontScale)
    val restore = stringResource(R.string.restore)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(thumb.dp).background(style.well).border(1.dp, style.line), Alignment.Center) {
            picture?.let { bitmap ->
                val ratio = (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).coerceIn(0.2f, 5f)
                Box(Modifier.then(if (ratio >= 1f) Modifier.fillMaxWidth() else Modifier.fillMaxHeight()).aspectRatio(ratio).checkerboard(4.dp)) {
                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(versionLabel(version.time).text(), color = style.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(item.bytes), Modifier.padding(top = 2.dp), color = style.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
            if (stacked) SquareButton(restore, null, style.accent, style.accent, Modifier.padding(top = 8.dp), onRestore)
        }
        if (!stacked) SquareButton(restore, null, style.accent, style.accent, Modifier.padding(start = 8.dp), onRestore)
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
