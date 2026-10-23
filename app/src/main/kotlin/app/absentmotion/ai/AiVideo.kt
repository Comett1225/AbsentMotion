package app.absentmotion.ai

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import app.absentmotion.R
import app.absentmotion.export.ThermalLevel
import app.absentmotion.export.ThermalMonitor
import app.absentmotion.i18n.AppText
import app.absentmotion.media.FrameReader
import app.absentmotion.media.videoDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import app.absentmotion.library.openableUri

internal fun videoBitrate(width: Int, height: Int, fps: Float): Int = (width.toDouble() * height * fps * 0.1).coerceIn(1_500_000.0, 50_000_000.0).toInt()

internal fun writeYuv(argb: IntArray, stride: Int, width: Int, height: Int, image: Image) {
    val (yPlane, uPlane, vPlane) = image.planes
    val y = yPlane.buffer
    for (row in 0 until height) {
        val base = row * yPlane.rowStride
        val line = row * stride
        for (col in 0 until width) {
            val p = argb[line + col]
            y.put(base + col * yPlane.pixelStride, lumaOf(p).toByte())
        }
    }
    val u = uPlane.buffer
    val v = vPlane.buffer
    for (row in 0 until (height + 1) / 2) {
        val uBase = row * uPlane.rowStride
        val vBase = row * vPlane.rowStride
        for (col in 0 until (width + 1) / 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = argb[minOf(height - 1, row * 2 + dy) * stride + minOf(width - 1, col * 2 + dx)]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            u.put(uBase + col * uPlane.pixelStride, ((-26 * r - 87 * g + 113 * b + 512) / 1024 + 128).coerceIn(0, 255).toByte())
            v.put(vBase + col * vPlane.pixelStride, ((113 * r - 102 * g - 11 * b + 512) / 1024 + 128).coerceIn(0, 255).toByte())
        }
    }
}

internal fun lumaOf(p: Int): Int = ((47 * ((p shr 16) and 0xFF) + 157 * ((p shr 8) and 0xFF) + 16 * (p and 0xFF) + 128) shr 8) + 16

private class AudioCopy(context: Context, uri: String) : Closeable {
    private val extractor = MediaExtractor()
    val format: MediaFormat?
    var track = -1
    private val buffer: ByteBuffer
    private val info = MediaCodec.BufferInfo()
    private var ended = false

    init {
        extractor.setDataSource(context, Uri.parse(openableUri(uri)), null)
        val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        format = index?.let(extractor::getTrackFormat)
        index?.let(extractor::selectTrack)
        ended = index == null
        buffer = ByteBuffer.allocateDirect(format?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(65_536) ?: 1_048_576)
    }

    fun copyUntil(muxer: MediaMuxer, pts: Long) {
        while (!ended && track >= 0) {
            if (extractor.sampleTrackIndex < 0) { ended = true; return }
            val time = extractor.sampleTime
            if (time > pts) return
            if (time < 0) { extractor.advance(); continue }
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) { ended = true; return }
            info.set(0, size, time, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    override fun close() = extractor.release()
}

internal class FrameEncoder private constructor(
    private val codec: MediaCodec, private val muxer: MediaMuxer, private val audio: AudioCopy?, private val stride: Int, val width: Int, val height: Int,
) : Closeable {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var finished = false

    fun write(pts: Long, argb: IntArray) {
        check(argb.size >= stride * height) { AppText.get(R.string.ai_mask_wrong_size) }
        val index = input()
        val size = codec.getInputBuffer(index)!!.capacity()
        val image = checkNotNull(codec.getInputImage(index)) { AppText.get(R.string.ai_encoder_no_image) }
        writeYuv(argb, stride, width, height, image)
        codec.queueInputBuffer(index, 0, size, pts, 0)
        drain(false)
    }

    fun finish() {
        codec.queueInputBuffer(input(), 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        check(track >= 0) { AppText.get(R.string.ai_encoder_not_finished) }
        audio?.copyUntil(muxer, Long.MAX_VALUE)
        finished = true
    }

    private fun input(): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueInputBuffer(5_000)
            if (index >= 0) return index
            drain(false)
        }
        error(AppText.get(R.string.ai_encoder_not_responding))
    }

    private fun drain(end: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    audio?.format?.let { format -> audio.track = runCatching { muxer.addTrack(format) }.getOrDefault(-1) }
                    muxer.start()
                }
                index >= 0 -> {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        check(track >= 0) { AppText.get(R.string.ai_encoder_frames_before_format) }
                        muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        audio?.copyUntil(muxer, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                !end -> return
                System.nanoTime() > deadline -> error(AppText.get(R.string.ai_encoder_not_finished))
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        try {
            if (finished && track >= 0) muxer.stop()
        } finally {
            runCatching { muxer.release() }
            audio?.close()
        }
    }

    companion object {
        fun open(context: Context, file: File, stride: Int, rows: Int, fps: Float, rotation: Int, soundFrom: String?): FrameEncoder {
            val width = maxOf(2, stride and 1.inv())
            val height = maxOf(2, rows and 1.inv())
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(width, height, fps))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceIn(1, 480))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                val name = list.findEncoderForFormat(format) ?: continue
                val codec = MediaCodec.createByCodecName(name)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                } catch (_: Exception) {
                    codec.release()
                    continue
                }
                val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer.setOrientationHint(((rotation % 360) + 360) % 360 / 90 * 90)
                val audio = soundFrom?.let { runCatching { AudioCopy(context, it) }.getOrNull() }
                return FrameEncoder(codec, muxer, audio, stride, width, height)
            }
            error(AppText.get(R.string.ai_video_too_large_to_save, width.toString(), height.toString()))
        }
    }
}

internal class VideoJob(val layer: Layer, val media: MediaDeclaration, val scale: Int = 1, val fpsScale: Int = 1)

internal fun interface FrameWork {
    suspend fun frame(pts: Long, argb: IntArray, width: Int, height: Int, emit: suspend (Long, IntArray) -> Unit)
}

internal suspend fun rewriteVideo(context: Context, job: VideoJob, progress: AiProgress, work: FrameWork): AiMedia {
    val fill = checkNotNull(job.layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
    val media = job.media
    check(media.width > 0 && media.height > 0) { AppText.get(R.string.ai_video_no_size) }
    val seconds = job.layer.matteSeconds(media)
    check(seconds <= MAX_MATTE_SECONDS) { AppText.get(R.string.ai_clip_too_long, (MAX_MATTE_SECONDS / 60).toString()) }
    val fps = (media.fps ?: 30f) * job.fpsScale
    val file = aiFile(context, "mp4")
    val needed = (videoBitrate(media.width * job.scale, media.height * job.scale, fps) / 8.0 * seconds).roundToLong() * 2 + 50_000_000L
    check(file.parentFile!!.usableSpace >= needed) { AppText.get(R.string.ai_not_enough_storage, (needed / 1_000_000).toString()) }
    val thermal = ThermalMonitor.of(context)
    thermal.start()
    var success = false
    var encoder: FrameEncoder? = null
    try {
        FrameReader(context, fill.uri).use { reader ->
            val stamps = matteStamps(reader.timestamps, fill.inTime, fill.outTime)
            check(stamps.isNotEmpty()) { AppText.get(R.string.ai_no_video_frames) }
            var size = 0 to 0
            val emit: suspend (Long, IntArray) -> Unit = { pts, argb ->
                val out = encoder ?: FrameEncoder.open(context, file, size.first * job.scale, size.second * job.scale, fps, media.orientation, fill.uri).also {
                    encoder = it
                    if (pts > 0) it.write(0L, argb)
                }
                out.write(pts, argb)
            }
            val started = System.nanoTime()
            var done = 0
            for (batch in stamps.chunked(BATCH)) {
                val frames = ArrayList<Pair<Long, IntArray>>(batch.size)
                reader.read(batch) { pts, yuv ->
                    size = yuv.width to yuv.height
                    frames += pts to yuv.argb(yuv.width, yuv.height, reader.colorStandard, reader.fullRange)
                }
                for ((pts, argb) in frames) {
                    cool(thermal, progress, done.toFloat() / stamps.size)
                    work.frame(pts, argb, size.first, size.second, emit)
                    done++
                    progress(0.01f + 0.97f * done / stamps.size, frameStatus(done, stamps.size, System.nanoTime() - started))
                }
            }
            progress(0.99f, AppText.get(R.string.ai_saving_video))
            checkNotNull(encoder) { AppText.get(R.string.ai_no_video_frames) }.finish()
        }
        encoder?.close()
        encoder = null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val declared = checkNotNull(videoDeclaration(context, uri)) { AppText.get(R.string.ai_unable_to_process) }
        return AiMedia(file, declared).also { success = true }
    } finally {
        encoder?.close()
        thermal.stop()
        if (!success) file.delete()
    }
}

private suspend fun cool(thermal: ThermalMonitor, progress: AiProgress, at: Float) {
    if (thermal.state.value.level == ThermalLevel.Hot) delay(HOT_PAUSE_MS)
    while (thermal.state.value.level >= ThermalLevel.Critical) {
        progress(at, AppText.get(R.string.ai_cooling_down))
        delay(HOT_PAUSE_MS)
    }
}

internal fun frameStatus(done: Int, total: Int, elapsedNs: Long): String {
    if (done < 3) return AppText.get(R.string.ai_frames_start, done.toString(), total.toString())
    val left = elapsedNs / 1e9 / done * (total - done)
    return if (left < 60) AppText.get(R.string.ai_frames_under_minute, done.toString(), total.toString())
        else AppText.get(R.string.ai_frames_minutes_left, done.toString(), total.toString(), (left / 60).roundToInt().toString())
}

private const val BATCH = 8
private const val HOT_PAUSE_MS = 2_000L
