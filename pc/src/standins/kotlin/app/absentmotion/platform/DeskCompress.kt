package app.absentmotion.platform

import app.absentmotion.export.ExportError
import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.media.MediaEngine
import app.absentmotion.tools.VideoJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

suspend fun compressVideoOnDesktop(uri: String, job: VideoJob, audio: Boolean, durationUs: Long, out: File, progress: (Float) -> Unit, startUs: Long = 0, endUs: Long = 0) = withContext(Dispatchers.IO) {
    val input = android.net.Uri.parse(uri).toFile() ?: throw ExportError(txt(R.string.compress_unreadable))
    val context = currentCoroutineContext()
    MediaEngine.current.compress(input, job, audio, durationUs, out, startUs, endUs, progress) { context.isActive }
}
