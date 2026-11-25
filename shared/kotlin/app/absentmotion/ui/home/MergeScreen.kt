package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.export.ExportKind
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.rememberVideosPicker
import app.absentmotion.platform.slotPicture
import app.absentmotion.tools.MergeClip
import app.absentmotion.tools.clockWhole
import app.absentmotion.tools.estimateMerge
import app.absentmotion.tools.mergeLengthUs
import app.absentmotion.tools.mergeVideos
import app.absentmotion.tools.planMerge
import app.absentmotion.tools.readMergeClip
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

@Composable
internal fun MergeScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work = rememberToolWork()
    var clips by remember { mutableStateOf(emptyList<MergeClip>()) }
    var unreadable by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    val picker = rememberVideosPicker { uris ->
        if (uris.isNotEmpty()) scope.launch {
            reading = true
            val read = try { uris.map { readMergeClip(context, it) } } finally { reading = false }
            work.change { clips = clips + read.filterNotNull(); unreadable = read.any { it == null } }
        }
    }
    val facts = clips.map { it.facts }
    val job = remember(clips) { planMerge(facts) }
    val estimate = stringResource(R.string.compress_estimate, formatBytes(estimateMerge(facts, job)), formatBytes(facts.sumOf { it.source.bytes }))
    val hint = when {
        clips.size < 2 -> stringResource(R.string.tools_merge_need_two)
        job.copy -> estimate
        else -> stringResource(R.string.tools_merge_no_fast) + "\n" + estimate
    }
    val done = work.step as? ToolStep.Done
    ToolSheet(stringResource(R.string.tools_merge), { if (work.running) work.cancel() else onClose() }, bottom = {
        ToolBar(
            work, hint, false, done?.let { stringResource(R.string.tools_done_facts, formatBytes(it.bytes), clockWhole(mergeLengthUs(facts))) }.orEmpty(),
            R.string.tools_merging, stringResource(R.string.tools_merge_start), HomeIcons.Merge, clips.size >= 2 && !reading,
        ) {
            val list = clips
            work.start { progress -> mergeVideos(context, list, job, progress) }
        }
    }) {
        Spacer(Modifier.height(20.dp))
        clips.forEachIndexed { index, clip ->
            key(clip) {
                ClipRow(clip, index > 0, index < clips.size - 1, !work.running, { step ->
                    work.change { clips = clips.toMutableList().apply { add(index + step, removeAt(index)) } }
                }) { work.change { clips = clips - clip } }
            }
        }
        PickCard(null, unreadable, !work.running && !reading, R.string.tools_merge_add) { picker.launch() }
        if (done != null) ToolResult(done.result, ExportKind.Video)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ClipRow(clip: MergeClip, up: Boolean, down: Boolean, enabled: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, clip.picked.uri) { value = slotPicture(context, clip.picked.uri.toString(), true) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).height(80.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Am.HomeLine)) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(clip.picked.name, color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(details(clip.picked), color = Am.HomeDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RowButton(HomeIcons.ChevronUp, stringResource(R.string.tools_merge_up), enabled && up) { onMove(-1) }
        RowButton(HomeIcons.ChevronDown, stringResource(R.string.tools_merge_down), enabled && down) { onMove(1) }
        RowButton(HomeIcons.Close, stringResource(R.string.added_effect_remove), enabled, onRemove)
    }
}

@Composable
private fun RowButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).alpha(if (enabled) 1f else 0.3f).clip(CircleShape).deskPress(22.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = Am.Navy)
    }
}
