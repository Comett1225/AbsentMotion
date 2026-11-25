package app.absentmotion.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.export.ExportKind
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.rememberMediaPicker
import app.absentmotion.tools.PickedMedia
import app.absentmotion.tools.PictureFormat
import app.absentmotion.tools.convertPicture
import app.absentmotion.tools.readPicked
import kotlinx.coroutines.launch

@Composable
internal fun ConvertImageScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work = rememberToolWork()
    var picked by remember { mutableStateOf<PickedMedia?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(PictureFormat.Png) }
    val picker = rememberMediaPicker(imageOnly = true) { uri ->
        if (uri != null) scope.launch {
            val media = readPicked(context, uri)?.takeIf { !it.source.video }
            work.change { picked = media; unreadable = media == null }
        }
    }
    val done = work.step as? ToolStep.Done
    val doneText = done?.let {
        if (it.result.frames > 1) stringResource(R.string.tools_done_gif, formatBytes(it.bytes), it.result.frames)
        else stringResource(R.string.tools_done_facts, formatBytes(it.bytes), target.label)
    }.orEmpty()
    ToolSheet(stringResource(R.string.tools_convert_image), { if (work.running) work.cancel() else onClose() }, bottom = {
        ToolBar(work, null, false, doneText, R.string.tools_converting, stringResource(R.string.tools_convert_start), HomeIcons.ImageConvert, picked != null) {
            val media = picked ?: return@ToolBar
            val to = target
            work.start { progress -> convertPicture(context, media, to, progress) }
        }
    }, side = {
        Label(stringResource(R.string.tools_convert_to))
        Chips(PictureFormat.entries.map { it.label }, target.ordinal, !work.running) { i -> work.change { target = PictureFormat.entries[i] } }
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, unreadable, !work.running, R.string.tools_convert_pick) { picker.launch() }
        if (done != null) ToolResult(done.result, if (done.result.frames > 1 || done.result.mime == PictureFormat.Gif.mime) ExportKind.Gif else ExportKind.FrameAs)
    }
}
