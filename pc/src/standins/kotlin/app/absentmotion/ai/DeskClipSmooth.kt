package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.Smoothing
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.fpsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal suspend fun desktopClipSmooth(context: Context, layer: Layer, media: MediaDeclaration, projectFps: Int, smoothing: Smoothing, progress: AiProgress): AiMedia {
    val fill = checkNotNull(layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
    val plan = checkNotNull(smoothPlan(layer, media, projectFps, smoothing)) { AppText.get(R.string.smooth_nothing) }
    check(plan.seconds <= MAX_MATTE_SECONDS && plan.frames <= MAX_SMOOTH_FRAMES && plan.width.toLong() * plan.height <= 16_000_000L) { AppText.get(R.string.ai_unable_to_process) }
    val input = Uri.parse(openableUri(fill.uri)).toFile() ?: error(AppText.get(R.string.ai_unable_to_process))
    val source = DeskProbe.of(input).streams.first { it.type == "video" }
    val fps = source.fps.takeIf { it > 0 } ?: 30.0
    val start = String.format(Locale.US, "%.6f", fill.inTime / 1000.0)
    val span = String.format(Locale.US, "%.6f", plan.seconds)
    val file = aiFile(context, "mp4")
    check(file.parentFile!!.usableSpace >= plan.bytes * 2) { AppText.get(R.string.ai_not_enough_storage, (plan.bytes * 2 / 1_000_000).toString()) }
    var success = false
    try {
        if (smoothing.method == FrameInterpolation.Ai) {
            AiSmooth.open(context, smoothing.level).use { net ->
                val reader = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", start, "-noautorotate", "-i", input.path, "-t", span,
                    "-vf", "fps=$fps,scale=${plan.width}:${plan.height}", "-pix_fmt", "rgb24", "-f", "rawvideo", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val writer = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${plan.width}x${plan.height}",
                    "-r", "${plan.fps}", "-i", "-", "-ss", start, "-t", span, "-i", input.path, "-map", "0:v:0", "-map", "1:a:0?",
                    "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", "-c:a", "aac", "-t", span, file.path)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    val bytes = ByteArray(plan.width * plan.height * 3)
                    val encoded = ByteArray(bytes.size)
                    val sink = writer.outputStream.buffered()
                    var previous: IntArray? = null
                    var sourceIndex = 0
                    var written = 0
                    val total = (plan.seconds * plan.fps).roundToInt().coerceAtLeast(1)
                    suspend fun writeFrame(frame: IntArray) {
                        for (i in frame.indices) { encoded[3*i] = (frame[i] shr 16).toByte(); encoded[3*i+1] = (frame[i] shr 8).toByte(); encoded[3*i+2] = frame[i].toByte() }
                        sink.write(encoded)
                        written++
                        progress((0.98f * written / total).coerceAtMost(0.98f), AppText.get(R.string.smooth_frame, written.toString(), total.toString()))
                    }
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.inputStream.readNBytes(bytes, 0, bytes.size)
                        if (count == 0) break
                        check(count == bytes.size) { AppText.get(R.string.ai_unable_to_process) }
                        val current = IntArray(plan.width * plan.height) { i -> (0xff shl 24) or ((bytes[3*i].toInt() and 255) shl 16) or ((bytes[3*i+1].toInt() and 255) shl 8) or (bytes[3*i+2].toInt() and 255) }
                        val before = previous
                        if (before != null) {
                            while (written < total && written.toDouble() / plan.fps < sourceIndex / fps) {
                                val t = ((written.toDouble() / plan.fps - (sourceIndex - 1) / fps) * fps).toFloat().coerceIn(0f, 1f)
                                val frame = if (t <= 0f) before else if (t >= 1f) current else net.between(before, current, plan.width, plan.height, t)
                                if (smoothing.blur == MotionBlur.Off) writeFrame(frame) else {
                                    val strength = (smoothing.blur.shutter / 360f * plan.blurScale).coerceIn(0f, 1f)
                                    val blended = IntArray(frame.size) { i ->
                                        var value = frame[i] and -0x1000000
                                        for (c in 0..2) {
                                            val shift = c * 8
                                            val centre = (frame[i] shr shift) and 255
                                            val sides = (((before[i] shr shift) and 255) + ((current[i] shr shift) and 255)) / 2f
                                            value = value or ((centre * (1f - strength) + sides * strength).roundToInt().coerceIn(0, 255) shl shift)
                                        }
                                        value
                                    }
                                    writeFrame(blended)
                                }
                            }
                        }
                        previous = current
                        sourceIndex++
                    }
                    val last = checkNotNull(previous) { AppText.get(R.string.ai_no_video_frames) }
                    while (written < total) writeFrame(last)
                    sink.close()
                    check(reader.waitFor() == 0 && writer.waitFor() == 0) { AppText.get(R.string.ai_unable_to_process) }
                } finally {
                    if (reader.isAlive) reader.destroyForcibly()
                    if (writer.isAlive) writer.destroyForcibly()
                }
            }
        } else {
            val filter = mutableListOf("trim=duration=$span", "setpts=PTS-STARTPTS", "scale=${plan.width}:${plan.height}")
            if (smoothing.method != FrameInterpolation.Off) {
                filter += "tpad=stop_mode=clone:stop_duration=1"
                filter += "minterpolate=fps=${plan.fps}:mi_mode=" + if (smoothing.method == FrameInterpolation.Blend) "blend" else "mci"
            }
            if (smoothing.blur != MotionBlur.Off) filter += "tmix=frames=2:weights='1 1'"
            val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-ss", start, "-noautorotate", "-i", input.path, "-t", span,
                "-vf", filter.joinToString(","), "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", "-c:a", "aac", file.path)
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
                check(process.exitValue() == 0) { AppText.get(R.string.ai_unable_to_process) }
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        success = true
        return aiMedia(context, file, "video/mp4", mapOf("width" to "${plan.width}", "height" to "${plan.height}", "duration" to "${(plan.seconds * 1_000_000).toLong()}", "fps" to fpsText(plan.fps.toFloat()), "orientation" to "${media.orientation}"))
    } finally { if (!success) file.delete() }
}
