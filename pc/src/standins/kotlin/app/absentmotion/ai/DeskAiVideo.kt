package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import app.absentmotion.model.fpsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlin.math.ceil

internal suspend fun desktopRewriteVideo(context: Context, job: VideoJob, progress: AiProgress, work: FrameWork): AiMedia {
    val fill = checkNotNull(job.layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
    val input = Uri.parse(openableUri(fill.uri)).toFile() ?: error(AppText.get(R.string.ai_unable_to_process))
    val probe = DeskProbe.of(input)
    val video = probe.streams.firstOrNull { it.type == "video" } ?: error(AppText.get(R.string.ai_video_no_size))
    val width = video.width
    val height = video.height
    check(width > 0 && height > 0 && job.scale in 1..4 && width.toLong() * height * job.scale * job.scale <= 16_000_000L) { AppText.get(R.string.ai_unable_to_process) }
    val fps = (job.media.fps?.toDouble() ?: video.fps).takeIf { it > 0 }?.coerceIn(1.0, 240.0) ?: 30.0
    val outFps = fps * job.fpsScale
    val startMs = fill.inTime.toLong().coerceAtLeast(0)
    val endMs = minOf(video.durationUs / 1000, if (fill.outTime == Int.MAX_VALUE) Long.MAX_VALUE else fill.outTime.toLong())
    val span = endMs - startMs
    check(span in 1..MAX_MATTE_SECONDS * 1000L) { AppText.get(R.string.ai_unable_to_process) }
    val count = ceil(span / 1000.0 * fps).toInt().coerceAtLeast(1)
    val output = aiFile(context, "mp4")
    val needed = (videoBitrate(width * job.scale, height * job.scale, outFps.toFloat()) / 8.0 * span / 1000).toLong() * 2 + 50_000_000L
    check(output.parentFile!!.usableSpace >= needed) { AppText.get(R.string.ai_not_enough_storage, (needed / 1_000_000).toString()) }
    fun number(value: Double) = String.format(Locale.US, "%.6f", value)
    val start = number(startMs / 1000.0)
    val rate = number(fps)
    val outRate = number(outFps)
    val reader = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", start, "-noautorotate", "-i", input.path, "-t", number(span / 1000.0),
        "-vf", "fps=$rate", "-pix_fmt", "rgb24", "-f", "rawvideo", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val writerArgs = mutableListOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${width * job.scale}x${height * job.scale}",
        "-r", outRate, "-i", "-", "-i", input.path, "-map", "0:v:0", "-map", "1:a:0?")
    if (startMs > 0) writerArgs += listOf("-vf", "setpts=if(eq(N\\,0)\\,0\\,(N-1)/$outRate/TB+$start/TB)", "-fps_mode", "vfr")
    writerArgs += listOf("-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", "-c:a", "aac", "-t", number(endMs / 1000.0), output.path)
    val writer = ProcessBuilder(writerArgs).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    var success = false
    try {
        val bytes = ByteArray(width * height * 3)
        val pixels = IntArray(width * height)
        val encoded = ByteArray(width * height * job.scale * job.scale * 3)
        val sink = writer.outputStream.buffered()
        var done = 0
        var emitted = 0
        val emit: suspend (Long, IntArray) -> Unit = { _, frame ->
            check(frame.size == width * height * job.scale * job.scale) { AppText.get(R.string.ai_unable_to_process) }
            for (i in frame.indices) {
                encoded[3 * i] = (frame[i] shr 16).toByte()
                encoded[3 * i + 1] = (frame[i] shr 8).toByte()
                encoded[3 * i + 2] = frame[i].toByte()
            }
            if (emitted == 0 && startMs > 0) sink.write(encoded)
            sink.write(encoded)
            emitted++
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val got = reader.inputStream.readNBytes(bytes, 0, bytes.size)
            if (got == 0) break
            check(got == bytes.size) { AppText.get(R.string.ai_unable_to_process) }
            for (i in pixels.indices) pixels[i] = (0xff shl 24) or ((bytes[3*i].toInt() and 255) shl 16) or ((bytes[3*i+1].toInt() and 255) shl 8) or (bytes[3*i+2].toInt() and 255)
            work.frame(startMs * 1000 + (done * 1_000_000.0 / fps).toLong(), pixels, width, height, emit)
            done++
            progress((0.01f + 0.97f * done / count).coerceAtMost(0.98f), AppText.get(R.string.ai_saving_video))
        }
        check(emitted > 0) { AppText.get(R.string.ai_unable_to_process) }
        sink.close()
        check(reader.waitFor() == 0 && writer.waitFor() == 0) { AppText.get(R.string.ai_unable_to_process) }
        success = true
        return aiMedia(context, output, "video/mp4", mapOf("width" to "${width * job.scale}", "height" to "${height * job.scale}", "duration" to "${endMs * 1000}", "fps" to fpsText(outFps.toFloat()), "orientation" to "${job.media.orientation}"))
    } finally {
        if (reader.isAlive) reader.destroyForcibly()
        if (writer.isAlive) writer.destroyForcibly()
        if (!success) output.delete()
    }
}
