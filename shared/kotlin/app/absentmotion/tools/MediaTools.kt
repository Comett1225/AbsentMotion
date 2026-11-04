package app.absentmotion.tools

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import app.absentmotion.R
import app.absentmotion.ai.argbInto
import app.absentmotion.export.ExportError
import app.absentmotion.export.GifWriter
import app.absentmotion.export.gifSampleEstimate
import app.absentmotion.i18n.txt
import app.absentmotion.library.openableUri
import app.absentmotion.media.FrameReader
import app.absentmotion.media.Stamps
import app.absentmotion.media.Yuv
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.platform.extractAudioOnDesktop
import app.absentmotion.platform.scaledFrame
import app.absentmotion.platform.trimCopyOnDesktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

private const val STILL_SIDE = 480
private const val ESTIMATE_PICKS = 3

private fun turnOf(picked: PickedMedia): Int = ((picked.rotation % 360) + 360) % 360 / 90 * 90

private fun needSound(picked: PickedMedia) {
    if (!picked.source.audio) throw ExportError(txt(R.string.tools_no_sound))
}

private fun nonEmpty(out: File) {
    if (out.length() <= 0) throw ExportError(txt(R.string.tools_nothing_made))
}

suspend fun extractAudio(context: Context, picked: PickedMedia, job: AudioJob, progress: (Float) -> Unit): Compressed = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val out = File(compressFolder(app).apply { mkdirs() }, outputName(picked, job.extension, "audio"))
    val uri = picked.uri.toString()
    val durationUs = picked.source.durationUs
    var finished = false
    try {
        needSound(picked)
        when {
            PlatformFeatures.desktop -> extractAudioOnDesktop(uri, job, durationUs, out, progress)
            job.copy -> copyRange(app, uri, 0, Long.MAX_VALUE, false, 0, out, progress, durationUs)
            else -> encodeSound(app, uri, durationUs, job.kbps, out, job.mp3, 0) { progress(it) }
        }
        nonEmpty(out)
        finished = true
        Compressed(out, job.mime, false)
    } finally {
        if (!finished) out.delete()
    }
}

suspend fun trimVideo(context: Context, picked: PickedMedia, job: TrimJob, progress: (Float) -> Unit): Compressed = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val folder = compressFolder(app).apply { mkdirs() }
    val out = File(folder, outputName(picked, "mp4", "trimmed"))
    val sound = File(folder, "sound.m4a")
    val uri = picked.uri.toString()
    val range = job.range
    var finished = false
    try {
        if (job.copy) {
            if (PlatformFeatures.desktop) trimCopyOnDesktop(uri, range.startUs, range.endUs, out, progress)
            else copyRange(app, uri, range.startUs, range.endUs, true, picked.rotation, out, progress, range.lengthUs)
        } else {
            val plan = encodable(trimVideoJob(picked.source))
            val encoded = plan.audioKbps > 0 && !plan.copyAudio && !PlatformFeatures.desktop
            if (encoded) encodeSound(app, uri, range.lengthUs, plan.audioKbps, sound, false, range.startUs) { progress(it * 0.1f) }
            val soundFrom = if (plan.audioKbps <= 0) null else if (encoded) Uri.fromFile(sound).toString() else uri
            val start = if (encoded) 0.1f else 0f
            runVideo(app, uri, plan, picked.rotation, soundFrom, out, range.lengthUs, range.startUs, range.endUs, soundRanged = !encoded) { progress(start + (1 - start) * it) }
        }
        nonEmpty(out)
        finished = true
        Compressed(out, "video/mp4", true)
    } finally {
        sound.delete()
        if (!finished) out.delete()
    }
}

private class GifFrames(val job: GifJob, private val turn: Int) {
    private val turned = turn % 180 != 0
    val codedWidth = if (turned) job.height else job.width
    val codedHeight = if (turned) job.width else job.height
    private val argb = IntArray(codedWidth * 2 * codedHeight * 2)
    val rgba = ByteArray(job.width * job.height * 4)

    fun fill(yuv: Yuv, standard: Int, fullRange: Boolean) {
        val ss = if (yuv.width >= codedWidth * 2 && yuv.height >= codedHeight * 2) 2 else 1
        yuv.argbInto(argb, codedWidth * ss, codedHeight * ss, standard, fullRange)
        framePixels(argb, ss, turn, job.width, job.height, rgba)
    }
}

suspend fun makeGif(context: Context, picked: PickedMedia, job: GifJob, progress: (Float) -> Unit): Compressed = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val out = File(compressFolder(app).apply { mkdirs() }, outputName(picked, "gif", ""))
    var finished = false
    try {
        val made = FrameReader(app, picked.uri.toString()).use { reader ->
            val stamps = reader.timestamps
            if (stamps.isEmpty()) throw ExportError(txt(R.string.compress_unreadable))
            val times = gifFrameTimes(job).also { t -> for (i in t.indices) t[i] = min(t[i], stamps.last()) }
            val frames = GifFrames(job, turnOf(picked))
            var written = 0
            out.outputStream().buffered(1 shl 16).use { stream ->
                val writer = GifWriter(stream, job.width, job.height, job.loop)
                for (chunk in frameChunks(times, stamps)) {
                    currentCoroutineContext().ensureActive()
                    val got = reader.read(chunk.map { times[it] }) { _, yuv ->
                        currentCoroutineContext().ensureActive()
                        frames.fill(yuv, reader.colorStandard, reader.fullRange)
                        writer.frame(frames.rgba, gifFrameEndMs(job, written))
                        progress(++written / job.frames.toFloat() * 0.99f)
                    }
                    if (got < chunk.last - chunk.first + 1) break
                }
                if (written == 0) throw ExportError(txt(R.string.tools_nothing_made))
                writer.finish()
            }
            written
        }
        finished = true
        Compressed(out, "image/gif", false, made)
    } finally {
        if (!finished) out.delete()
    }
}

suspend fun gifSizeEstimate(context: Context, picked: PickedMedia, job: GifJob): Long? = withContext(Dispatchers.IO) {
    try {
        FrameReader(context.applicationContext, picked.uri.toString()).use { reader ->
            val last = reader.timestamps.lastOrNull() ?: return@use null
            val times = (0 until ESTIMATE_PICKS).map { min(job.range.startUs + job.range.lengthUs * (2 * it + 1) / (2 * ESTIMATE_PICKS), last) }
            val frames = GifFrames(job, turnOf(picked))
            val samples = ArrayList<ByteArray>()
            reader.read(times) { _, yuv ->
                currentCoroutineContext().ensureActive()
                frames.fill(yuv, reader.colorStandard, reader.fullRange)
                samples += frames.rgba.copyOf()
            }
            if (samples.isEmpty()) null else gifSampleEstimate(job.width, job.height, job.frames, samples).bytes
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

suspend fun keyframeTimes(context: Context, uri: Uri): LongArray = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val keys = Stamps()
        var count = 0
        while (extractor.sampleTime >= 0) {
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys.add(extractor.sampleTime)
            if (++count % 4096 == 0) currentCoroutineContext().ensureActive()
            if (!extractor.advance()) break
        }
        keys.sortedDistinct()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LongArray(0)
    } finally {
        extractor.release()
    }
}

suspend fun videoStills(context: Context, uri: Uri, timesUs: List<Long>, side: Int = STILL_SIDE, exact: Boolean = false): List<ImageBitmap?> = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, Uri.parse(openableUri(uri.toString())))
        timesUs.map { time ->
            currentCoroutineContext().ensureActive()
            runCatching {
                retriever.scaledFrame(time, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)?.asImageBitmap()
            }.getOrNull()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        timesUs.map { null }
    } finally {
        retriever.release()
    }
}
