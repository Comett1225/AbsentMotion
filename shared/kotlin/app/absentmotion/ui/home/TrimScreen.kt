package app.absentmotion.ui.home

import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.export.ExportKind
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.tools.TrimRange
import app.absentmotion.tools.clockWhole
import app.absentmotion.tools.estimateTrim
import app.absentmotion.tools.fullRange
import app.absentmotion.tools.keyframeTimes
import app.absentmotion.tools.planTrim
import app.absentmotion.tools.snapStart
import app.absentmotion.tools.trimVideo
import app.absentmotion.ui.theme.Am

@Composable
internal fun TrimScreen(initial: Uri? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val work = rememberToolWork()
    val pick = rememberVideoPick(initial) { work.cancel(); work.reset() }
    val picked = pick.picked
    val source = picked?.source
    var fast by rememberSaveable { mutableStateOf(true) }
    var range by remember(picked) { mutableStateOf(fullRange(source?.durationUs ?: 0)) }
    val keys by produceState<LongArray?>(null, picked?.uri) {
        value = null
        picked?.let { value = keyframeTimes(context, it.uri) }
    }
    val cuts = keys
    LaunchedEffect(fast, cuts) {
        if (fast && cuts != null && cuts.isNotEmpty()) range = range.copy(startUs = snapStart(range.startUs, cuts, range.endUs))
    }
    val waiting = fast && cuts == null
    val job = remember(range, cuts, fast) { planTrim(range, cuts ?: LongArray(0), fast) }
    val hint = when {
        picked == null -> stringResource(R.string.tools_choose_first)
        waiting -> stringResource(R.string.tools_finding_cuts)
        fast && !job.copy -> stringResource(R.string.tools_trim_no_fast)
        else -> stringResource(R.string.compress_estimate, formatBytes(estimateTrim(source!!, job)), formatBytes(source.bytes))
    }
    val back = { if (work.running) work.cancel() else onClose() }
    ToolSheet(stringResource(R.string.tools_trim), back, bottom = {
        ToolBar(
            work, hint, false,
            (work.step as? ToolStep.Done)?.let { stringResource(R.string.tools_done_facts, formatBytes(it.bytes), clockWhole(job.range.lengthUs)) }.orEmpty(),
            R.string.tools_trimming, stringResource(R.string.tools_trim_start), HomeIcons.Trim, picked != null && !waiting,
        ) {
            val media = picked ?: return@ToolBar
            work.start { progress -> trimVideo(context, media, job, progress) }
        }
    }, side = {
        SectionTitle(stringResource(R.string.tools_trim_how))
        ChoiceRow(stringResource(R.string.tools_trim_fast), stringResource(R.string.tools_trim_fast_about), fast, !work.running) { work.change { fast = true } }
        ChoiceRow(stringResource(R.string.tools_trim_exact), stringResource(R.string.tools_trim_exact_about), !fast, !work.running) { work.change { fast = false } }
        if (picked != null && fast && cuts != null && cuts.size <= 1) BarText(stringResource(R.string.tools_trim_few_cuts), Am.Accent)
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, pick.problem != null, !work.running, R.string.tools_choose_video, pick.problem ?: R.string.compress_unreadable) { pick.launch() }
        val done = work.step as? ToolStep.Done
        if (done != null) ToolResult(done.result, ExportKind.Video)
        if (picked != null && source != null) {
            SectionTitle(stringResource(R.string.tools_part))
            RangePicker(picked.uri, source.durationUs, range, cuts, fast, Long.MAX_VALUE, !work.running && !waiting) { next: TrimRange -> work.change { range = next } }
        }
    }
}
