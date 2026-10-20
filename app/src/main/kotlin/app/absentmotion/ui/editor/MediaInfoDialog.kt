package app.absentmotion.ui.editor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
internal fun MediaInfoDialog(record: MediaDeclaration, onReplace: ((MediaDeclaration) -> Unit)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { mediaDeclaration(context, uri)?.let { onReplace?.invoke(it) } }
        }
    }
    val rows by produceState(listOf(R.string.mediainfo_name to record.title, R.string.mediainfo_dimensions to "${record.width} x ${record.height}"), record) {
        value = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(record.uri), null)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val video = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val audio = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                val seconds = (record.duration / 1_000_000).coerceAtLeast(0)
                val format = listOfNotNull(video?.getString(MediaFormat.KEY_MIME), audio?.getString(MediaFormat.KEY_MIME)).joinToString("\n")
                buildList {
                    add(R.string.mediainfo_name to record.title)
                    add(R.string.mediainfo_dimensions to "${record.width} x ${record.height}")
                    if (video != null) record.fps?.let { add(R.string.mediainfo_frame_rate to String.format(Locale.US, "%.02f", it)) }
                    add(R.string.mediainfo_duration to String.format(Locale.US, "%03d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60))
                    add(R.string.mediainfo_format to format.ifEmpty { record.type })
                    add(R.string.mediainfo_size to sizeText(record.size.coerceAtLeast(0)))
                    audio?.getNumber(MediaFormat.KEY_SAMPLE_RATE)?.let { add(R.string.mediainfo_sample_rate to kilohertz(it.toFloat() / 1000)) }
                }
            } catch (_: Exception) {
                listOf(R.string.mediainfo_name to record.title, R.string.mediainfo_dimensions to "${record.width} x ${record.height}", R.string.mediainfo_format to record.type)
            } finally { extractor.release() }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.media_info)) },
        text = {
            Column {
                for ((name, value) in rows) Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(name), Modifier.width(100.dp).padding(end = 15.dp), fontSize = 14.sp)
                    Text(value, Modifier.weight(1f), fontSize = 14.sp)
                }
                Text(
                    stringResource(R.string.swap_media_file), Modifier.fillMaxWidth().padding(top = 12.dp).alpha(if (onReplace == null) 0.35f else 1f)
                        .clickable(enabled = onReplace != null) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType(record.type.substringBefore('/', "image") + "/*")))
                        },
                    color = Am.Accent, fontSize = 14.sp,
                )
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok), color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark)
}

private fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "${bytes}b"
    val (unit, name) = when {
        bytes < 1L shl 20 -> 1024L to "KB"
        bytes < 1L shl 30 -> (1L shl 20) to "MB"
        else -> (1L shl 30) to "GB"
    }
    return "${bytes / unit}.${(bytes % unit) * 10 / unit}$name"
}

private fun kilohertz(value: Float): String {
    val decimals = value.toString().substringAfter('.', "0")
    return String.format(Locale.US, "%.${if (decimals.toInt() == 0) 0 else decimals.length}fkHz", value)
}
