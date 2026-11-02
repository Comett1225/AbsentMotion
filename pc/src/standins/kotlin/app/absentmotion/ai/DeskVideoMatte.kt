package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.fpsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal suspend fun desktopVideoMatte(context: Context, layer: Layer, media: MediaDeclaration, side: Int, keep: List<Tap>, progress: AiProgress): VideoMatte {
    val fill = checkNotNull(layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
    val source = Uri.parse(openableUri(fill.uri)).toFile() ?: error(AppText.get(R.string.ai_unable_to_process))
    val stream = DeskProbe.of(source).streams.firstOrNull { it.type == "video" } ?: error(AppText.get(R.string.ai_video_no_size))
    check(stream.width > 0 && stream.height > 0) { AppText.get(R.string.ai_unable_to_process) }
    val (width, height) = matteSize(stream.width, stream.height, side)
    check(width.toLong() * height <= 16_000_000L) { AppText.get(R.string.ai_unable_to_process) }
    val fps = (media.fps?.toDouble() ?: stream.fps).takeIf { it > 0 }?.coerceIn(1.0, 240.0) ?: 30.0
    val endMs = minOf(if (fill.outTime == Int.MAX_VALUE) Long.MAX_VALUE else fill.outTime.toLong(), stream.durationUs / 1000)
    val startMs = fill.inTime.toLong().coerceAtLeast(0)
    val spanMs = endMs - startMs
    check(spanMs > 0 && spanMs <= MAX_MATTE_SECONDS * 1000L) { AppText.get(R.string.ai_unable_to_process) }
    val count = ceil(spanMs / 1000.0 * fps).toInt().coerceAtLeast(1)
    val file = aiFile(context, "mp4")
    val needed = matteBytes(spanMs / 1000.0, width, height) * 2 + 50_000_000L
    check(file.parentFile!!.usableSpace >= needed) { AppText.get(R.string.ai_not_enough_storage, (needed / 1_000_000).toString()) }
    val rate = String.format(Locale.US, "%.6f", fps)
    val start = String.format(Locale.US, "%.6f", startMs / 1000.0)
    val duration = String.format(Locale.US, "%.6f", spanMs / 1000.0)
    val reader = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", start, "-noautorotate", "-i", source.path,
        "-t", duration, "-vf", "fps=$rate,scale=$width:$height", "-pix_fmt", "rgb24", "-f", "rawvideo", "-")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val writerArgs = mutableListOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", "${width}x$height", "-r", rate, "-i", "-")
    if (startMs > 0) writerArgs += listOf("-vf", "setpts=if(eq(N\\,0)\\,0\\,(N-1)/$rate/TB+$start/TB)", "-fps_mode", "vfr")
    writerArgs += listOf("-an", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", file.path)
    val writer = ProcessBuilder(writerArgs).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    var success = false
    try {
        progress(0f, AppText.get(R.string.ai_loading_model))
        BackgroundRemoval.withFrameMatte(context) { matte ->
            val smoother = MatteSmoother()
            val follower = if (keep.isEmpty()) null else PartFollower(keep)
            val bytes = ByteArray(width * height * 3)
            val pixels = IntArray(width * height)
            val output = writer.outputStream.buffered()
            if (startMs > 0) output.write(ByteArray(width * height))
            var done = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.inputStream.readNBytes(bytes, 0, bytes.size)
                if (read == 0) break
                check(read == bytes.size) { AppText.get(R.string.ai_unable_to_process) }
                for (i in pixels.indices) pixels[i] = (0xff shl 24) or ((bytes[3 * i].toInt() and 255) shl 16) or ((bytes[3 * i + 1].toInt() and 255) shl 8) or (bytes[3 * i + 2].toInt() and 255)
                val alpha = matte(pixels, width, height)
                check(alpha.size == pixels.size) { AppText.get(R.string.ai_unable_to_process) }
                smoother.push(done.toLong(), follower?.next(alpha, width, height) ?: alpha)?.let { output.write(encodeMatte(it.second, width, height)) }
                done++
                progress((0.02f + 0.96f * done / count).coerceAtMost(0.98f), AppText.get(R.string.ai_removing_background))
            }
            check(done > 0) { AppText.get(R.string.ai_no_video_frames) }
            smoother.flush()?.let { output.write(encodeMatte(it.second, width, height)) }
            output.close()
        }
        check(reader.waitFor() == 0 && writer.waitFor() == 0) { AppText.get(R.string.ai_cannot_encode_mask) }
        val result = aiMedia(context, file, "video/mp4", mapOf("width" to "$width", "height" to "$height", "orientation" to "0", "duration" to "${media.duration}", "fps" to fpsText(fps.toFloat())))
        success = true
        return VideoMatte(result, stream.width, stream.height)
    } finally {
        if (reader.isAlive) reader.destroyForcibly()
        if (writer.isAlive) writer.destroyForcibly()
        if (!success) file.delete()
    }
}
