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
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.tools.ExtractChoice
import app.absentmotion.tools.SoundKbps
import app.absentmotion.tools.canCopySound
import app.absentmotion.tools.clockWhole
import app.absentmotion.tools.estimateAudio
import app.absentmotion.tools.extractAudio
import app.absentmotion.tools.planAudio

@Composable
internal fun ExtractAudioScreen(initial: Uri? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val work = rememberToolWork()
    val pick = rememberVideoPick(initial) { work.cancel(); work.reset() }
    var choice by remember { mutableStateOf(ExtractChoice()) }
    val picked = pick.picked
    val source = picked?.source
    LaunchedEffect(picked) { choice = ExtractChoice(false, if (source?.audioAac == true) 0 else SoundKbps[1]) }
    val hasSound = source?.audio == true
    val job = remember(source, choice) { source?.takeIf { it.audio }?.let { planAudio(it, choice) } }
    val canCopy = source != null && canCopySound(source, choice)
    val kbps = if (canCopy) listOf(0) + SoundKbps else SoundKbps
    val length = clockWhole(source?.durationUs ?: 0)
    val hint = when {
        picked == null -> stringResource(R.string.tools_choose_first)
        !hasSound -> stringResource(R.string.tools_no_sound)
        job != null -> stringResource(R.string.tools_extract_estimate, formatBytes(estimateAudio(job, source.durationUs)), length)
        else -> null
    }
    val back = { if (work.running) work.cancel() else onClose() }
    ToolSheet(stringResource(R.string.tools_extract), back, bottom = {
        ToolBar(
            work, hint, picked != null && !hasSound,
            (work.step as? ToolStep.Done)?.let { stringResource(R.string.tools_done_facts, formatBytes(it.bytes), length) }.orEmpty(),
            R.string.tools_extracting, stringResource(R.string.tools_extract_start), HomeIcons.Audio, job != null,
        ) {
            val media = picked ?: return@ToolBar
            val plan = job ?: return@ToolBar
            work.start { progress -> extractAudio(context, media, plan, progress) }
        }
    }, side = {
        Label(stringResource(R.string.compress_format))
        Chips(listOf("M4A", "MP3"), if (choice.mp3) 1 else 0, !work.running) { i ->
            work.change { choice = choice.copy(mp3 = i == 1, kbps = if (i == 1 && choice.kbps <= 0) SoundKbps[1] else choice.kbps) }
        }
        Label(stringResource(R.string.compress_quality))
        Chips(
            kbps.map { stringResource(when (it) { 0 -> R.string.compress_original; SoundKbps[0] -> R.string.compress_quality_small; SoundKbps[1] -> R.string.compress_quality_good; else -> R.string.compress_quality_best }) },
            kbps.indexOf(choice.kbps).coerceAtLeast(0), !work.running,
        ) { i -> work.change { choice = choice.copy(kbps = kbps[i]) } }
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, pick.problem != null, !work.running, R.string.tools_choose_video, pick.problem ?: R.string.compress_unreadable) { pick.launch() }
        val done = work.step as? ToolStep.Done
        if (done != null) SoundResult(done.result, stringResource(R.string.tools_facts, formatBytes(done.bytes), length))
    }
}
