package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskProbe
import app.absentmotion.i18n.AppText
import app.absentmotion.R
import app.absentmotion.library.openableUri
import app.absentmotion.media.MediaEngine
import app.absentmotion.media.ReverseParts
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.fpsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.util.UUID
import kotlin.math.ceil

internal suspend fun desktopReverse(context: Context, layer: Layer, media: MediaDeclaration, progress: AiProgress): ReversedClip {
    val input = Uri.parse(openableUri(checkNotNull(layer.mediaFill).uri)).toFile() ?: error(AppText.get(R.string.ai_unable_to_process))
    val probe = DeskProbe.of(input)
    val video = probe.streams.firstOrNull { it.type == "video" } ?: error(AppText.get(R.string.ai_select_video_layer))
    val end = video.durationUs.takeIf { it > 0 } ?: probe.durationUs
    check(end > 0 && video.width > 0 && video.height > 0) { AppText.get(R.string.ai_unable_to_process) }
    val used = layer.usedMediaMs()
    val first = if (end <= 60_000_000) 0L else (used.start * 1000).toLong().coerceIn(0, end - 1)
    val last = if (end <= 60_000_000) end else (used.endInclusive * 1000).toLong().coerceIn(first + 1, end)
    val fps = video.fps.takeIf { it > 0 } ?: 30.0
    val frameBytes = video.width.toLong() * video.height * 4
    val total = Math.round((last - first) / 1_000_000.0 * fps).toInt().coerceAtLeast(1)
    val perPart = (128_000_000.0 / frameBytes).toInt().coerceIn(1, (2.0 * fps).toInt().coerceAtLeast(1))
    val count = ceil(total.toDouble() / perPart).toInt()
    check(count in 1..2000) { AppText.get(R.string.ai_unable_to_process) }
    val folder = File(context.cacheDir, "reverse-${UUID.randomUUID()}")
    check(folder.mkdirs()) { AppText.get(R.string.ai_unable_to_process) }
    val output = aiFile(context, "mp4")
    var success = false
    try {
        val parts = ReverseParts(input, output, folder, first, last, fps, total, perPart, probe.audio != null)
        val done = java.util.concurrent.atomic.AtomicInteger(-1)
        val size = coroutineScope {
            val work = async(Dispatchers.IO) { MediaEngine.current.reverse(parts, { done.set((it * 1000).toInt()) }) { isActive } }
            var shown = -1
            while (work.isActive) {
                delay(100)
                val now = done.get()
                if (now != shown) { shown = now; progress(now / 1000f, AppText.get(R.string.speed_reversing)) }
            }
            work.await()
        }
        val width = size?.first ?: ((video.width + 1) and 1.inv())
        val height = size?.second ?: ((video.height + 1) and 1.inv())
        val result = aiMedia(context, output, "video/mp4", mapOf("width" to "$width", "height" to "$height", "orientation" to "${video.rotation}", "duration" to "${last - first}", "fps" to fpsText(fps.toFloat())))
        success = true
        return ReversedClip(result, last)
    } finally {
        folder.deleteRecursively()
        if (!success) output.delete()
    }
}
