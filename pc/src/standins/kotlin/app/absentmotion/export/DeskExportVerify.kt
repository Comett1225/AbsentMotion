package app.absentmotion.export

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class FrameLooks(val middleUs: Long, val first: FloatArray, val middle: FloatArray)

data class Expectation(val video: Boolean, val audio: Boolean, val durationUs: Long, val frameUs: Long, val looks: FrameLooks? = null)

data class ProbedTrack(val mime: String, val durationUs: Long)

data class Verification(
    val problems: List<UiText>,
    val durationUs: Long,
    val firstFrame: Boolean?,
    val lastFrame: Boolean?,
    val ms: Long,
    val middleFrame: Boolean? = null,
    val firstLooks: Boolean? = null,
    val unchecked: Boolean = false,
) {
    val ok: Boolean get() = problems.isEmpty()
}

internal const val AudioOverhangUs = 100_000L

internal fun judge(tracks: List<ProbedTrack>, expect: Expectation, firstFrame: Boolean?, lastFrame: Boolean?): List<UiText> {
    val problems = ArrayList<UiText>()
    val video = tracks.filter { it.mime.startsWith("video/") }
    val audio = tracks.filter { it.mime.startsWith("audio/") }
    if (tracks.isEmpty()) return listOf(txt(R.string.export_verify_empty))
    if (expect.video && video.isEmpty()) problems += txt(R.string.export_verify_no_video)
    if (expect.audio && audio.isEmpty()) problems += txt(R.string.export_verify_no_audio)
    val slack = expect.frameUs + 1_000
    fun lasts(durationUs: Long) = txt(R.string.export_verify_duration, "%.3f".format(java.util.Locale.ROOT, durationUs / 1e6), "%.3f".format(java.util.Locale.ROOT, expect.durationUs / 1e6))
    fun check(of: List<ProbedTrack>, below: Long, above: Long) {
        val duration = of.maxOfOrNull { it.durationUs }?.takeIf { it > 0 } ?: return
        val over = duration - expect.durationUs
        if (over < -below || over > above) problems += lasts(duration)
    }
    val longest = tracks.maxOf { it.durationUs }
    if (longest <= 0) problems += lasts(longest)
    else {
        if (expect.video) check(video, slack, slack + if (expect.audio) AudioOverhangUs else 0)
        if (expect.audio) check(audio, if (expect.video) slack + AudioOverhangUs else slack, slack + AudioOverhangUs)
    }
    if (expect.video && video.isNotEmpty()) {
        if (firstFrame == false) problems += txt(R.string.export_verify_first_frame)
        if (lastFrame == false) problems += txt(R.string.export_verify_last_frame)
    }
    return problems
}

private fun ffprobe(file: File, vararg args: String): String {
    val process = ProcessBuilder(listOf(DeskMedia.ffprobe, "-v", "error") + args + file.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    return process.inputStream.bufferedReader().readText().also { process.waitFor() }
}

private fun seconds(text: String?): Long? = text?.trim()?.toDoubleOrNull()?.let { (it * 1e6).toLong() }

private fun clock(text: String?): Long? = text?.split(':')?.takeIf { it.size == 3 }?.let { (h, m, s) ->
    val sec = s.toDoubleOrNull() ?: return null
    ((h.toLongOrNull() ?: return null) * 3600 + (m.toLongOrNull() ?: return null) * 60) * 1_000_000 + (sec * 1e6).toLong()
}

private fun times(file: File, entry: String, from: String): List<Long> =
    ffprobe(file, "-select_streams", "v:0", "-read_intervals", from, "-show_entries", "$entry=pts_time", "-of", "csv=p=0").lines().mapNotNull { seconds(it.trim().trimEnd(',')) }

suspend fun verifyExport(context: Context, uri: Uri, expect: Expectation): Verification = withContext(Dispatchers.IO) {
    val start = SystemClock.elapsedRealtime()
    try {
        val file = uri.toFile() ?: throw java.io.FileNotFoundException(uri.toString())
        val json = JSONObject(ffprobe(file, "-show_entries", "stream=codec_type,codec_name,duration:stream_tags=DURATION:format=duration", "-of", "json"))
        val whole = seconds(json.optJSONObject("format")?.optString("duration")) ?: 0L
        val streams = json.optJSONArray("streams")
        val tracks = (0 until (streams?.length() ?: 0)).map { streams!!.getJSONObject(it) }.filter { it.optString("codec_type") in setOf("video", "audio") }.map { s ->
            ProbedTrack(s.optString("codec_type") + "/" + s.optString("codec_name"),
                seconds(s.optString("duration")) ?: clock(s.optJSONObject("tags")?.optString("DURATION")) ?: whole)
        }
        val video = tracks.any { it.mime.startsWith("video/") }
        val first = if (!video) null else times(file, "frame", "%+#1").isNotEmpty()
        val last = if (!video) null else {
            val from = "${maxOf(0.0, whole / 1e6 - 2.0)}%"
            val packets = times(file, "packet", from).maxOrNull()
            val frames = times(file, "frame", from).maxOrNull()
            if (packets == null) null else frames != null && packets - frames <= 1_000
        }
        Verification(judge(tracks, expect, first, last), tracks.maxOfOrNull { it.durationUs } ?: 0, first, last, SystemClock.elapsedRealtime() - start,
            unchecked = expect.video && video && first == null)
    } catch (e: Exception) {
        Verification(listOf(txt(R.string.export_verify_unreadable, e.message ?: e.javaClass.simpleName)), 0, null, null, SystemClock.elapsedRealtime() - start)
    }
}

internal fun expectationFor(kind: ExportKind, scene: app.absentmotion.model.Scene, s: ExportSettings, hasAudio: Boolean, looks: FrameLooks? = null): Expectation? = when (kind) {
    ExportKind.Video -> Expectation(true, hasAudio, frameCount(scene, s.fps) * 1_000_000L / s.fps, 1_000_000L / s.fps, looks)
    ExportKind.Audio -> Expectation(false, true, (scene.totalTime + 1) * 1000L, 30_000)
    else -> null
}

data class PictureExpectation(val width: Int, val height: Int, val frames: Int = 1, val durationUs: Long = 0, val frameUs: Long = 0) {
    val animated: Boolean get() = durationUs > 0
}

internal fun pictureExpectationFor(kind: ExportKind, scene: app.absentmotion.model.Scene, s: ExportSettings): PictureExpectation? = when (kind) {
    ExportKind.Gif -> {
        val (width, height) = exportSize(scene, s.shortSide, even = false)
        val frames = frameCount(scene, s.fps)
        PictureExpectation(width, height, frames, frames * 1_000_000L / s.fps, 1_000_000L / s.fps)
    }
    ExportKind.Frame, ExportKind.FrameAs -> PictureExpectation(scene.width, scene.height)
    else -> null
}

suspend fun verifyPicture(context: Context, uri: Uri, expect: PictureExpectation): Verification = withContext(Dispatchers.IO) {
    val start = SystemClock.elapsedRealtime()
    try {
        val bytes = uri.toFile()?.readBytes() ?: throw java.io.FileNotFoundException(uri.toString())
        val codec = runCatching { org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) }.getOrNull()
        val problems = ArrayList<UiText>()
        var durationUs = 0L
        if (codec == null) problems += txt(R.string.export_verify_picture)
        else codec.use {
            if (it.width != expect.width || it.height != expect.height) problems += txt(R.string.export_verify_size, "${it.width}x${it.height}", "${expect.width}x${expect.height}")
            if (expect.animated) {
                val frames = it.framesInfo
                durationUs = frames.sumOf { f -> f.duration.toLong() } * 1000
                if (frames.any { f -> !f.isFullyReceived }) problems += txt(R.string.export_verify_picture)
                if (frames.size != expect.frames) problems += txt(R.string.export_verify_frames, frames.size.toString(), expect.frames.toString())
                if (abs(durationUs - expect.durationUs) > expect.frameUs + 10_000) {
                    problems += txt(R.string.export_verify_duration, "%.3f".format(java.util.Locale.ROOT, durationUs / 1e6), "%.3f".format(java.util.Locale.ROOT, expect.durationUs / 1e6))
                }
            }
        }
        Verification(problems, durationUs, codec != null, null, SystemClock.elapsedRealtime() - start)
    } catch (e: Exception) {
        Verification(listOf(txt(R.string.export_verify_unreadable, e.message ?: e.javaClass.simpleName)), 0, null, null, SystemClock.elapsedRealtime() - start)
    }
}
