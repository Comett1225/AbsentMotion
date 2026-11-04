package app.absentmotion.ui.home

import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.export.ExportKind
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.tools.GIF_BIG_BYTES
import app.absentmotion.tools.GifChoice
import app.absentmotion.tools.GifRates
import app.absentmotion.tools.GifSides
import app.absentmotion.tools.MAX_GIF_US
import app.absentmotion.tools.TrimRange
import app.absentmotion.tools.estimateGif
import app.absentmotion.tools.fullRange
import app.absentmotion.tools.gifSizeEstimate
import app.absentmotion.tools.makeGif
import app.absentmotion.tools.planGif
import kotlinx.coroutines.delay

@Composable
internal fun GifScreen(initial: Uri? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val work = rememberToolWork()
    val pick = rememberVideoPick(initial) { work.cancel(); work.reset() }
    val picked = pick.picked
    val source = picked?.source
    var range by remember(picked) { mutableStateOf(fullRange(source?.durationUs ?: 0, MAX_GIF_US)) }
    var choice by remember { mutableStateOf(GifChoice()) }
    val job = remember(picked, choice, range) { picked?.let { planGif(it.source, it.rotation, choice, range) } }
    var measured by remember { mutableStateOf<Long?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(job, work.running) {
        measured = null
        checked = false
        if (picked == null || job == null || work.running) return@LaunchedEffect
        delay(450)
        measured = gifSizeEstimate(context, picked, job)
        checked = true
    }
    val bytes = job?.let { measured ?: if (checked) estimateGif(it) else null }
    val hint = when {
        picked == null -> stringResource(R.string.tools_choose_first)
        bytes == null -> stringResource(R.string.tools_checking_size)
        else -> stringResource(R.string.tools_gif_estimate, formatBytes(bytes), job.frames) +
            if (bytes >= GIF_BIG_BYTES) "\n" + stringResource(R.string.tools_gif_big) else ""
    }
    val back = { if (work.running) work.cancel() else onClose() }
    ToolSheet(stringResource(R.string.tools_gif), back, bottom = {
        ToolBar(
            work, hint, bytes != null && bytes >= GIF_BIG_BYTES,
            (work.step as? ToolStep.Done)?.let { stringResource(R.string.tools_done_gif, formatBytes(it.bytes), it.result.frames) }.orEmpty(),
            R.string.tools_making_gif, stringResource(R.string.tools_gif_start), HomeIcons.Gif, picked != null && job != null,
        ) {
            val media = picked ?: return@ToolBar
            val plan = job ?: return@ToolBar
            work.start { progress -> makeGif(context, media, plan, progress) }
        }
    }, side = {
        Label(stringResource(R.string.compress_size))
        Chips(GifSides.map { "$it px" }, GifSides.indexOf(choice.side).coerceAtLeast(0), !work.running) { i -> work.change { choice = choice.copy(side = GifSides[i]) } }
        Label(stringResource(R.string.compress_frame_rate))
        Chips(GifRates.map { "$it" }, GifRates.indexOf(choice.fps).coerceAtLeast(0), !work.running) { i -> work.change { choice = choice.copy(fps = GifRates[i]) } }
        Label(stringResource(R.string.tools_gif_loop))
        Chips(listOf(R.string.tools_gif_forever, R.string.tools_gif_once).map { stringResource(it) }, if (choice.loop) 0 else 1, !work.running) { i -> work.change { choice = choice.copy(loop = i == 0) } }
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, pick.problem != null, !work.running, R.string.tools_choose_video, pick.problem ?: R.string.compress_unreadable) { pick.launch() }
        val done = work.step as? ToolStep.Done
        if (done != null) ToolResult(done.result, ExportKind.Gif)
        if (picked != null && source != null) {
            SectionTitle(stringResource(R.string.tools_part))
            RangePicker(picked.uri, source.durationUs, range, null, false, MAX_GIF_US, !work.running) { next: TrimRange -> work.change { range = next } }
            if (source.durationUs > MAX_GIF_US) BarText(stringResource(R.string.tools_gif_limit, (MAX_GIF_US / 1_000_000).toInt()), app.absentmotion.ui.theme.Am.HomeDim)
        }
    }
}
