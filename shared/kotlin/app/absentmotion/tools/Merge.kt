package app.absentmotion.tools

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.ai.AudioCopy
import app.absentmotion.audio.AudioClip
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.audio.Resampler
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportAudio
import app.absentmotion.export.openVideoEncoder
import app.absentmotion.i18n.txt
import app.absentmotion.library.openableUri
import app.absentmotion.media.transcodeJoined
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.mergeOnDesktop
import app.absentmotion.platform.numberOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class MergeFacts(val source: CompressSource, val rotation: Int, val video: String, val sound: String?)

class MergeClip(val picked: PickedMedia, val facts: MergeFacts)

data class MergeJob(val copy: Boolean, val width: Int, val height: Int, val fps: Int, val bitrate: Int, val audioKbps: Int)

private val VideoKeys = listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION, MediaFormat.KEY_PROFILE, "level", "am-profile",
    MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE, "csd-0", "csd-1", "csd-2")
private val SoundKeys = listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_AAC_PROFILE, "csd-0")

private fun valueOf(format: MediaFormat, key: String): String = when {
    !format.containsKey(key) -> ""
    key.startsWith("csd-") -> format.getByteBuffer(key)?.duplicate()?.let { ByteArray(it.remaining()).also(it::get).contentHashCode().toString() }.orEmpty()
    key == MediaFormat.KEY_MIME || key == "am-profile" -> format.getString(key).orEmpty()
    else -> format.numberOf(key)?.toString().orEmpty()
}

private fun signature(format: MediaFormat, keys: List<String>, fps: Boolean): String =
    (keys.map { valueOf(format, it) } + if (fps) listOf(format.numberOf(MediaFormat.KEY_FRAME_RATE)?.toFloat()?.times(100)?.roundToInt()?.toString().orEmpty()) else emptyList()).joinToString("|")

suspend fun readMergeClip(context: Context, uri: Uri): MergeClip? {
    val picked = readPicked(context, uri)?.takeIf { it.source.video && it.source.durationUs > 0 } ?: return null
    return withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(openableUri(uri.toString())), null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") } ?: return@withContext null
            val sound = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            MergeClip(picked, MergeFacts(picked.source, ((picked.rotation % 360) + 360) % 360 / 90 * 90, signature(video, VideoKeys, true), sound?.let { signature(it, SoundKeys, false) }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}

fun planMerge(clips: List<MergeFacts>): MergeJob {
    val first = clips.firstOrNull() ?: return MergeJob(false, 0, 0, 30, 0, 0)
    val copy = clips.size > 1 && clips.all { it.video == first.video && it.sound == first.sound }
    val turned = first.rotation % 180 != 0
    val (w, h) = scaledToShort(if (turned) first.source.height else first.source.width, if (turned) first.source.width else first.source.height, 0)
    val fps = clips.maxOf { it.source.fps.roundToInt() }.coerceIn(1, 60)
    val total = clips.sumOf { it.source.durationUs }.coerceAtLeast(1)
    val average = clips.sumOf { sourceVideoBitrate(it.source).toDouble() * it.source.durationUs } / total
    val bitrate = min(average, bitsFor(w, h, fps, 0.25).toDouble()).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)
    val kbps = clips.filter { it.source.audio }.maxOfOrNull { it.source.audioKbps.takeIf { k -> k > 0 } ?: FALLBACK_SOUND_KBPS }?.coerceIn(64, 320) ?: 0
    return MergeJob(copy, w, h, fps, bitrate, kbps)
}

fun mergeLengthUs(clips: List<MergeFacts>): Long = clips.sumOf { it.source.durationUs }

fun estimateMerge(clips: List<MergeFacts>, job: MergeJob): Long =
    if (job.copy) clips.sumOf { it.source.bytes } else estimateVideo(VideoJob(job.width, job.height, job.fps, job.bitrate, job.audioKbps, false, 0), mergeLengthUs(clips))

fun mergeStarts(clips: List<MergeFacts>): LongArray = LongArray(clips.size + 1).also { starts -> for (i in clips.indices) starts[i + 1] = starts[i] + clips[i].source.durationUs }

suspend fun mergeVideos(context: Context, clips: List<MergeClip>, job: MergeJob, progress: (Float) -> Unit): Compressed = withContext(Dispatchers.IO) {
    if (clips.size < 2) throw ExportError(txt(R.string.tools_merge_need_two))
    val app = context.applicationContext
    val folder = compressFolder(app).apply { mkdirs() }
    val out = File(folder, outputName(clips.first().picked, "mp4", "merged"))
    val sound = File(folder, "sound.m4a")
    val facts = clips.map { it.facts }
    var finished = false
    try {
        when {
            PlatformFeatures.desktop -> mergeOnDesktop(clips.map { it.picked.uri.toString() }, facts, job, out, progress)
            job.copy -> joinCopies(app, clips, out, progress)
            else -> {
                val heard = job.audioKbps > 0
                if (heard) mixSounds(app, clips, job.audioKbps, sound) { progress(it * 0.1f) }
                val start = if (heard) 0.1f else 0f
                val plan = encodable(VideoJob(job.width, job.height, job.fps, job.bitrate, job.audioKbps, false, 0))
                val work = currentCoroutineContext().job
                val joined = (if (heard) AudioCopy(app, Uri.fromFile(sound).toString()) else null).use { copy ->
                    transcodeJoined(app, clips.map { it.picked.uri.toString() to it.facts.rotation }, mergeStarts(facts), out, plan.width, plan.height, copy, { !work.isActive },
                        { openVideoEncoder(ExportSettings(min(plan.width, plan.height), plan.fps), plan.width, plan.height, false, bitrate = plan.bitrate) }) { progress(start + (1 - start) * it) }
                }
                if (!joined) throw CancellationException()
            }
        }
        if (out.length() <= 0) throw ExportError(txt(R.string.tools_nothing_made))
        finished = true
        Compressed(out, "video/mp4", true)
    } finally {
        sound.delete()
        if (!finished) out.delete()
    }
}

private suspend fun mixSounds(context: Context, clips: List<MergeClip>, kbps: Int, out: File, progress: (Float) -> Unit) {
    val starts = mergeStarts(clips.map { it.facts })
    val sounding = clips.indices.filter { clips[it].picked.source.audio }.map { i ->
        val uri = clips[i].picked.uri.toString()
        val from = starts[i] / 1000
        val ms = (clips[i].picked.source.durationUs / 1000).coerceAtLeast(1)
        AudioClip(Layer(XmlNode("audio", linkedMapOf("id" to "${i + 1}", "startTime" to "$from", "endTime" to "${from + ms}", "src" to uri, "outTime" to "$ms", "mediaFillMode" to "fill"))), uri)
    }
    OfflineMix({ PcmDecoder(context, it) }, sounding, Resampler.ULTRA).use { mix ->
        RandomAccessFile(out, "rw").use { file ->
            file.setLength(0)
            exportAudio(ExportSettings(0, 30, audio = AudioSettings(AudioCodec.Aac, kbps), audioFormat = AudioFormat.M4a), mergeLengthUs(clips.map { it.facts }), mix, file.fd, progress)
        }
    }
}

private suspend fun joinCopies(context: Context, clips: List<MergeClip>, out: File, progress: (Float) -> Unit) {
    val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    try {
        val total = mergeLengthUs(clips.map { it.facts }).coerceAtLeast(1)
        val info = MediaCodec.BufferInfo()
        var data = ByteBuffer.allocateDirect(2 shl 20)
        var videoOut = -1
        var soundOut = -1
        var offset = 0L
        for (clip in clips) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(openableUri(clip.picked.uri.toString())), null)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
                val sound = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
                if (video < 0) throw ExportError(txt(R.string.compress_unreadable))
                if (!started) {
                    try {
                        videoOut = muxer.addTrack(formats[video])
                        if (sound >= 0) soundOut = muxer.addTrack(formats[sound])
                    } catch (e: IllegalArgumentException) { throw ExportError(txt(R.string.compress_unreadable), e) }
                    if (clip.facts.rotation != 0) muxer.setOrientationHint(clip.facts.rotation)
                    muxer.start()
                    started = true
                }
                extractor.selectTrack(video)
                if (sound >= 0 && soundOut >= 0) extractor.selectTrack(sound)
                val needed = formats.filter { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }.maxOfOrNull { it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } ?: 0
                if (needed > data.capacity()) data = ByteBuffer.allocateDirect(needed)
                val step = 1_000_000L / (formats[video].numberOf(MediaFormat.KEY_FRAME_RATE)?.toDouble()?.takeIf { it > 0 } ?: 30.0).toLong().coerceAtLeast(1)
                var lastSound = -1L
                var soundStep = 0L
                var end = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val track = extractor.sampleTrackIndex
                    if (track < 0) break
                    val time = extractor.sampleTime.coerceAtLeast(0)
                    data.clear()
                    val size = extractor.readSampleData(data, 0)
                    if (size < 0) break
                    val isVideo = track == video
                    if (!isVideo) { if (lastSound >= 0) soundStep = time - lastSound; lastSound = time }
                    end = maxOf(end, time + if (isVideo) step else soundStep)
                    info.set(0, size, offset + time, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(if (isVideo) videoOut else soundOut, data, info)
                    progress(((offset + time).toFloat() / total).coerceIn(0f, 0.99f))
                    if (!extractor.advance()) break
                }
                offset += end
            } finally {
                extractor.release()
            }
        }
        started = false
        muxer.stop()
    } finally {
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
