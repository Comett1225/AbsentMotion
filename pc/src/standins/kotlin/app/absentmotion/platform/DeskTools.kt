package app.absentmotion.platform

import app.absentmotion.R
import app.absentmotion.export.ExportError
import app.absentmotion.i18n.txt
import app.absentmotion.media.MediaEngine
import app.absentmotion.tools.AudioJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun seconds(us: Long): String = String.format(Locale.ROOT, "%.6f", us / 1_000_000.0)

suspend fun extractAudioOnDesktop(uri: String, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val input = android.net.Uri.parse(uri).toFile() ?: throw ExportError(txt(R.string.compress_unreadable))
    val context = currentCoroutineContext()
    MediaEngine.current.extractAudio(input, job, durationUs, out, progress) { context.isActive }
}

suspend fun trimCopyOnDesktop(uri: String, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val input = android.net.Uri.parse(uri).toFile() ?: throw ExportError(txt(R.string.compress_unreadable))
    val context = currentCoroutineContext()
    MediaEngine.current.trim(input, startUs, endUs, out, progress) { context.isActive }
}
