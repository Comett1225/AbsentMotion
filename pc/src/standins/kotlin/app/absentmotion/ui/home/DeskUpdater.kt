package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskOs
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Log
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface DeskStep {
    val release: Release? get() = null
    data object Idle : DeskStep
    data class Downloading(override val release: Release, val done: Long, val total: Long, val waiting: Boolean = false) : DeskStep
    data class Ready(override val release: Release, val file: File) : DeskStep
    data class Opened(override val release: Release, val file: File) : DeskStep
    data class Failed(override val release: Release, val failure: UpdateFailure) : DeskStep
}

internal object Updater {
    var step by mutableStateOf<DeskStep>(DeskStep.Idle)
        private set
    var hidden by mutableStateOf(false)
    private val scope = MainScope()
    private var job: Job? = null
    private var http: TrustedHttp? = null
    private var generation = 0

    fun inApp(context: Context, source: UpdateSource): Boolean = true

    fun reveal(): Boolean {
        if (step == DeskStep.Idle) return false
        hidden = false
        return true
    }

    fun start(context: Context, release: Release) {
        val apk = release.apk ?: return
        hidden = false
        if (job?.isActive == true) return
        val client = TrustedHttp(Updates.source)
        http = client
        val mine = ++generation
        val files = updateFiles(context.cacheDir, context.filesDir)
        step = DeskStep.Downloading(release, 0, apk.size)
        job = scope.launch {
            try {
                val file = fetch(client, release, files) { done, waiting -> scope.launch { if (generation == mine) step = DeskStep.Downloading(release, done, apk.size, waiting) } }
                if (generation == mine) step = DeskStep.Ready(release, file)
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { if (generation != mine || step == DeskStep.Idle) files.part.delete() }
            } catch (e: Exception) {
                Log.i("AMUpdate", "update download stopped: $e (${e.cause})")
                if (generation == mine) step = DeskStep.Failed(release, failureOf(e, apk.size))
            } finally {
                if (generation == mine) http = null
            }
        }
    }

    private suspend fun fetch(client: TrustedHttp, release: Release, files: UpdateFiles, shown: (Long, Boolean) -> Unit): File = withContext(Dispatchers.IO) {
        val apk = checkNotNull(release.apk)
        val wait: suspend (Long) -> Unit = { ms -> shown(files.part.length(), true); delay(ms) }
        val downloaded = patiently(apk.size, { files.part.length() }, wait) {
            ensureActive()
            val sha = verifiedChecksum(client, apk)
            files.dir.mkdirs()
            checkSpace(files, release, sha, files.dir.usableSpace)
            var last = -1L
            val stride = (apk.size / 200).coerceAtLeast(64L * 1024)
            downloadApk(client, release, sha, files, { ensureActive() }) { done ->
                if (done == apk.size || done - last >= stride || last < 0) {
                    last = done
                    shown(done, false)
                }
            }
        }
        files.dir.listFiles().orEmpty().filter { it.isFile && it.name != downloaded.name && installerExtension(it.name) != null }.forEach { it.delete() }
        val named = File(files.dir, safeName(apk.name))
        named.delete()
        if (!downloaded.renameTo(named)) throw UpdateProblem(UpdateFailure.Save)
        files.stamp.delete()
        named
    }

    fun cancel() {
        generation++
        http?.abort()
        job?.cancel()
        step = DeskStep.Idle
        hidden = false
    }

    fun later() {
        generation++
        step = DeskStep.Idle
        hidden = false
    }

    fun open(ready: DeskStep.Ready): Boolean {
        val opened = DeskOs.open(ready.file)
        if (opened) step = DeskStep.Opened(ready.release, ready.file)
        return opened
    }
}

internal fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_")

internal fun verifiedChecksum(http: TrustedHttp, apk: ApkAsset): String {
    val listed = apk.sums?.let { fetchSums(http, it, apk.name) } ?: throw UpdateProblem(UpdateFailure.NoChecksum)
    if (apk.sha256 != null && apk.sha256 != listed) throw UpdateProblem(UpdateFailure.Checksum)
    return listed
}

@Composable
internal fun UpdateDialog(onQuit: () -> Unit = {}) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val step = Updater.step
    if (step != DeskStep.Idle) {
        if (!Updater.hidden) UpdateFlow(step, onQuit)
        return
    }
    val shown = Updates.answer ?: return
    val close = { Updates.answer = null }
    val available = shown as? UpdateAnswer.Available
    val release = available?.release
    DeskModal(close, 480.dp, onEnter = {
        if (release?.apk != null) { close(); Updater.start(context, release) }
        else if (release != null) { close(); runCatching { links.openUri(release.link) } } else close()
    }) {
        DeskDialogHeader(
            if (release != null) stringResource(R.string.update_available, release.version) else stringResource(if (shown == UpdateAnswer.Latest) R.string.update_latest else R.string.update_failed), close,
        )
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (release != null) {
                Text(stringResource(R.string.update_installed, Updates.installed(context)), color = Am.HomeInk, fontSize = 14.sp)
                release.apk?.let { Text(stringResource(R.string.update_size, formatBytes(it.size)), color = Am.HomeMid, fontSize = 13.sp) }
                if (available.external) Text(stringResource(R.string.update_external), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp)
                Updates.source.label?.let { Text(it, color = Am.HomeMid, fontSize = 13.sp) }
            } else Text(stringResource(R.string.update_installed, Updates.installed(context)), color = Am.HomeMid, fontSize = 13.sp, fontWeight = FontWeight.Normal)
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (release != null) {
                DeskButton(stringResource(R.string.update_changelog), { runCatching { links.openUri(release.link) } })
                DeskButton(stringResource(R.string.update_later), close)
                if (release.apk != null) DeskButton(stringResource(R.string.update_download_update), { close(); Updater.start(context, release) }, primary = true)
                else DeskButton(stringResource(R.string.update_download), { close(); runCatching { links.openUri(release.link) } }, primary = true)
            } else DeskButton(stringResource(R.string.ok), close, primary = true, minWidth = 96.dp)
        }
    }
}

@Composable
private fun UpdateFlow(step: DeskStep, onQuit: () -> Unit) {
    val context = LocalContext.current
    val release = step.release ?: return
    val hide = { Updater.hidden = true }
    DeskModal(if (step is DeskStep.Downloading) hide else Updater::later, 480.dp, dismissOnScrim = false, onEnter = {
        when (step) {
            is DeskStep.Ready -> Updater.open(step)
            is DeskStep.Opened -> onQuit()
            else -> Unit
        }
    }) {
        DeskDialogHeader(
            stringResource(when (step) {
                is DeskStep.Downloading -> R.string.update_downloading
                is DeskStep.Ready -> R.string.desk_update_ready
                is DeskStep.Opened -> R.string.desk_update_opened_title
                else -> R.string.update_stopped
            }),
            if (step is DeskStep.Downloading) hide else Updater::later, release.version,
        )
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                is DeskStep.Downloading -> {
                    val fraction = if (step.total > 0) (step.done.toFloat() / step.total).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Am.HomeLine)) { Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Am.Accent)) }
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_progress, formatBytes(step.done), formatBytes(step.total)), Modifier.weight(1f), color = Am.HomeMid, fontSize = 13.sp)
                        Text("${(fraction * 100).toInt()}%", color = Am.Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (step.waiting) Text(stringResource(R.string.update_reconnecting), color = Am.HomeMid, fontSize = 13.sp)
                }
                is DeskStep.Ready -> Text(stringResource(R.string.desk_update_ready_body), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)
                is DeskStep.Opened -> Text(stringResource(R.string.desk_update_opened_body), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp)
                is DeskStep.Failed -> {
                    val failure = step.failure
                    Text(
                        when (failure) {
                            is UpdateFailure.Server -> stringResource(failure.message, failure.code.toString())
                            is UpdateFailure.Storage -> stringResource(failure.message, formatBytes(failure.needed))
                            else -> stringResource(failure.message)
                        },
                        color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
                DeskStep.Idle -> Unit
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            when (step) {
                is DeskStep.Downloading -> DeskButton(stringResource(R.string.cancel), Updater::cancel)
                is DeskStep.Ready -> {
                    DeskButton(stringResource(R.string.update_later), Updater::later)
                    DeskButton(stringResource(R.string.desk_update_open), { if (!Updater.open(step)) { app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.desk_update_open_failed, step.file.path), app.absentmotion.platform.Toast.LENGTH_LONG).show() } }, primary = true)
                }
                is DeskStep.Opened -> {
                    DeskButton(stringResource(R.string.update_not_now), Updater::later)
                    DeskButton(stringResource(R.string.desk_update_quit), { Updater.later(); onQuit() }, primary = true)
                }
                is DeskStep.Failed -> {
                    DeskButton(stringResource(R.string.update_later), Updater::later)
                    if (step.failure.retry) DeskButton(stringResource(R.string.home_save_failed_retry), { Updater.start(context, step.release) }, primary = true)
                }
                DeskStep.Idle -> Unit
            }
        }
    }
}
