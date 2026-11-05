package app.absentmotion.ui.home

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.export.ExportKind
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.platform.rememberMediaPicker
import app.absentmotion.platform.rememberStorageAccess
import app.absentmotion.tools.Compressed
import app.absentmotion.tools.PickedMedia
import app.absentmotion.tools.clearCompressed
import app.absentmotion.tools.readPicked
import app.absentmotion.tools.saveToGallery
import app.absentmotion.tools.shareCompressed
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ToolStep {
    data object Ready : ToolStep
    data class Running(val percent: Int) : ToolStep
    data class Done(val result: Compressed, val bytes: Long, val saved: Boolean = false) : ToolStep
    data class Failed(val message: String) : ToolStep
}

internal class ToolWork(private val scope: CoroutineScope, private val context: android.content.Context, private val storage: app.absentmotion.platform.StorageAccess) {
    var step by mutableStateOf<ToolStep>(ToolStep.Ready)
        private set
    private var job: Job? = null
    val running: Boolean get() = step is ToolStep.Running

    fun start(block: suspend (progress: (Float) -> Unit) -> Compressed) {
        if (running) return
        step = ToolStep.Running(0)
        job = scope.launch {
            try {
                val result = block { fraction ->
                    val percent = (fraction * 100).toInt().coerceIn(0, 100)
                    if (step != ToolStep.Running(percent)) step = ToolStep.Running(percent)
                }
                step = ToolStep.Done(result, result.file.length())
            } catch (e: CancellationException) {
                step = ToolStep.Ready
                throw e
            } catch (e: Exception) {
                step = ToolStep.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                step = ToolStep.Failed(context.getString(R.string.tools_too_big))
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun reset() { if (!running) step = ToolStep.Ready }

    fun change(block: () -> Unit) {
        if (running) return
        block()
        step = ToolStep.Ready
    }

    fun save(done: ToolStep.Done) {
        fun write() {
            scope.launch {
                try {
                    val where = saveToGallery(context, done.result)
                    if (step == done) step = done.copy(saved = true)
                    Toast.makeText(context, context.getString(R.string.compress_saved_to, where), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.compress_save_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
        if (storage.ready { write() }) write()
    }

    fun share(done: ToolStep.Done) { runCatching { shareCompressed(context, done.result) } }
}

@Composable
internal fun rememberToolWork(): ToolWork {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { clearCompressed(context) } }
    return remember { ToolWork(scope, context, storage) }
}

internal class VideoPick {
    var picked by mutableStateOf<PickedMedia?>(null)
    var problem by mutableStateOf<Int?>(null)
    var launch: () -> Unit = {}
}

@Composable
internal fun rememberVideoPick(initial: Uri?, onNew: () -> Unit): VideoPick {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = remember { VideoPick() }
    val latest by rememberUpdatedState(onNew)
    suspend fun read(uri: Uri) {
        val found = readPicked(context, uri)
        pick.picked = found?.takeIf { it.source.video && it.source.durationUs > 0 }
        pick.problem = if (found == null || found.source.video && found.source.durationUs <= 0) R.string.compress_unreadable else if (!found.source.video) R.string.tools_pick_video else null
    }
    LaunchedEffect(initial) { if (initial != null) read(initial) }
    val picker = rememberMediaPicker(videoOnly = true) { uri ->
        if (uri != null) scope.launch {
            latest()
            read(uri)
        }
    }
    pick.launch = { picker.launch() }
    return pick
}

@Composable
internal fun ToolBar(
    work: ToolWork, hint: String?, warn: Boolean, doneText: String, @StringRes running: Int, startLabel: String, startIcon: ImageVector, canStart: Boolean, onStart: () -> Unit,
) {
    val step = work.step
    val view = LocalView.current
    DisposableEffect(work.running) {
        view.keepScreenOn = work.running
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            when (step) {
                is ToolStep.Running -> Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(running, step.percent), color = Am.Navy, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { step.percent / 100f }, Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(4.dp)),
                        color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f),
                    )
                }
                is ToolStep.Done -> BarText(doneText, Am.Navy)
                is ToolStep.Failed -> BarText(stringResource(R.string.tools_failed, step.message), Am.Accent)
                ToolStep.Ready -> if (hint != null) BarText(hint, if (warn) Am.Accent else Am.Navy)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                is ToolStep.Running -> BigButton(stringResource(R.string.cancel), Modifier.weight(1f), primary = false) { work.cancel() }
                is ToolStep.Done -> {
                    BigButton(stringResource(if (step.saved) R.string.compress_saved else R.string.tools_save), Modifier.weight(1f).fillMaxHeight(), HomeIcons.Download, enabled = !step.saved) { work.save(step) }
                    BigButton(stringResource(R.string.compress_share), Modifier.weight(1f).fillMaxHeight(), ExportIcons.Share, primary = false) { work.share(step) }
                }
                is ToolStep.Failed -> {
                    var reporting by remember { mutableStateOf(false) }
                    if (reporting) ReportDialog(stringResource(R.string.tools_failed, step.message)) { reporting = false }
                    BigButton(startLabel, Modifier.weight(1f), startIcon, enabled = canStart, onClick = onStart)
                    BigButton(stringResource(R.string.report_button), Modifier.weight(1f), primary = false) { reporting = true }
                }
                else -> BigButton(startLabel, Modifier.weight(1f), startIcon, enabled = canStart, onClick = onStart)
            }
        }
    }
}

@Composable
internal fun ToolResult(result: Compressed, kind: ExportKind) {
    SectionTitle(stringResource(R.string.compress_result))
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)).background(Am.Scrim)) {
        androidx.compose.runtime.key(result) { app.absentmotion.platform.ExportMediaPreview(kind, listOf(android.net.Uri.fromFile(result.file))) }
    }
}

@Composable
internal fun SoundResult(result: Compressed, facts: String) {
    SectionTitle(stringResource(R.string.compress_result))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(Am.Navy, CircleShape), contentAlignment = Alignment.Center) {
            Icon(HomeIcons.Audio, null, Modifier.size(24.dp), tint = Am.OnNavy)
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(result.file.name, color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(facts, color = Am.HomeDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
