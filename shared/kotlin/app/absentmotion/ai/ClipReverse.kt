package app.absentmotion.ai

import android.content.Context
import android.media.Image
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.audio.PcmChunk
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioMix
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.Mux
import app.absentmotion.export.MuxerSink
import app.absentmotion.export.aacDelayFrames
import app.absentmotion.export.createAudioEncoder
import app.absentmotion.export.drain
import app.absentmotion.export.feedAudio
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import app.absentmotion.media.FrameReader
import app.absentmotion.media.Stamps
import app.absentmotion.media.VideoSources
import app.absentmotion.media.Yuv
import app.absentmotion.media.YuvEncoder
import app.absentmotion.media.estimatedFps
import app.absentmotion.media.visibleSize
import app.absentmotion.model.Easing
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.fpsText
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.loopStart
import app.absentmotion.model.reversed
import app.absentmotion.model.withKeyframes
import app.absentmotion.platform.intOr
import app.absentmotion.platform.numberOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class ReversedClip(val media: AiMedia, val mirrorUs: Long)

private const val WHOLE_SOURCE_US = 60_000_000L
private const val HELD_BYTES = 192L shl 20
private const val MAX_HELD_FRAMES = 240

internal fun Layer.usedMediaMs(): ClosedFloatingPointRange<Double> {
    val fill = mediaFill ?: return 0.0..0.0
    val start = minOf(fill.inTime, loopStart ?: fill.inTime).toDouble()
    val end = if (node["loop"] == "true") fill.outTime.toDouble()
        else minOf(VideoTiming(this).mediaMs((endTime - startTime).toDouble()), fill.outTime.toDouble())
    return start..maxOf(start, end)
}

internal fun Layer.mirroredMedia(mirrorMs: Double): Layer {
    val fill = mediaFill ?: return this
    fun ms(value: Double) = "${value.roundToLong().coerceIn(0L, Int.MAX_VALUE - 1L)}"
    val end = VideoTiming(this).mediaMs((endTime - startTime).toDouble())
    var n = node
    if (node["loop"] == "true" && fill.outTime != Int.MAX_VALUE) {
        val from = (loopStart ?: fill.inTime).toDouble()
        val to = fill.outTime.toDouble()
        val span = (to - from).coerceAtLeast(1.0)
        val wrapped = from + ((end - from) % span + span) % span
        n = n.with("inTime", ms(mirrorMs - wrapped)).with("loopIn", ms(mirrorMs - to)).with("outTime", ms(mirrorMs - from))
    } else {
        n = n.with("inTime", ms(mirrorMs - minOf(end, fill.outTime.toDouble()))).with("outTime", ms(mirrorMs - fill.inTime))
    }
    val map = node.child("speedMap")
    if (map?.isKeyframed == true) {
        val keys = map.keyframes
        val turned = keys.indices.map { i ->
            val key = keys[keys.size - 1 - i]
            val ease = if (i == 0) keys.first().e else keys[keys.size - i].e?.let { Easing.parse(it).reversed()?.toString() ?: it }
            key.copy(t = (mirrorMs * 1_000_000 - key.t).toFloat(), e = ease)
        }
        n = n.withChild("speedMap", map.withKeyframes(turned))
    }
    return copy(node = n)
}

internal val AiMark.mirrorUs: Long? get() = node["mirror"]?.toLongOrNull()

internal val Layer.reverseMark: AiMark?
    get() = aiMarks.firstOrNull { it.kind == AiKind.Reverse && it.result == mediaFill?.uri && it.original != null && it.mirrorUs != null }

private fun Layer.withoutReverseMarks(): Layer = copy(node = node.copy(children = node.children.filterNot { it.name == AI_TAG && it["type"] == AiKind.Reverse.xml }))

internal fun Layer.reversedTo(result: String, mirrorUs: Long, original: MediaDeclaration?): Layer {
    val source = mediaFill?.uri
    val turned = withoutReverseMarks().mirroredMedia(mirrorUs / 1000.0)
    return turned.copy(node = turned.node.with("fillVideo", result))
        .withAiMark(AiKind.Reverse, source, result, media = original, extra = mapOf("mirror" to "$mirrorUs"))
}

internal fun Scene.unreversed(id: Long): Scene {
    val layer = layer(id) ?: return this
    val mark = layer.reverseMark ?: return this
    val back = layer.mirroredMedia(mark.mirrorUs!! / 1000.0).withoutReverseMarks()
    return (mark.media?.let(::withMedia) ?: this).update(id) { back.copy(node = back.node.with("fillVideo", mark.original)) }
}

internal fun reversePieces(stamps: LongArray, syncs: LongArray, first: Int, last: Int, most: Int): List<IntRange> {
    val starts = (listOf(first) + syncs.map { stamps.binarySearch(it) }.filter { it in (first + 1) until last }).distinct().sorted()
    val pieces = ArrayList<IntRange>()
    for ((a, b) in (starts + last).zipWithNext()) {
        var at = a
        while (at < b) {
            val end = minOf(b, at + most.coerceAtLeast(1))
            pieces += at until end
            at = end
        }
    }
    return pieces.asReversed()
}

internal fun reverseSecondsLeft(done: Int, total: Int, elapsedNs: Long): Int? {
    if (done < 3 || elapsedNs < 1_000_000_000L) return null
    return ceil(elapsedNs / 1e9 / done * (total - done)).toInt().coerceAtLeast(1)
}

private fun reverseStatus(left: Int?): String = AppText.get(R.string.speed_reversing) + "\n" + when {
    left == null -> AppText.get(R.string.speed_reverse_estimating)
    left < 60 -> AppText.get(R.string.speed_reverse_seconds_left, left.toString())
    else -> AppText.get(R.string.speed_reverse_minutes_left, ceil(left / 60.0).toInt().toString())
}

private fun LongArray.atOrBefore(us: Long): Int = binarySearch(us).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }

private fun LongArray.atOrAfter(us: Long): Int = binarySearch(us).let { if (it >= 0) it else -it - 1 }

internal object ClipReverse {
    suspend fun run(context: Context, layer: Layer, media: MediaDeclaration, progress: AiProgress): ReversedClip {
        if (app.absentmotion.platform.PlatformFeatures.desktop) return app.absentmotion.platform.desktopReverse(context, layer, media, progress)
        val uri = checkNotNull(layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }.uri
        progress(0f, reverseStatus(null))
        val job = currentCoroutineContext().job
        val stamps = VideoSources.timestamps(context, uri)
        val (syncs, format) = syncTimes(context, uri)
        val (visibleWidth, visibleHeight) = visibleSize(format)
        val width = visibleWidth and 1.inv()
        val height = visibleHeight and 1.inv()
        check(width >= 2 && height >= 2) { AppText.get(R.string.ai_unable_to_process) }
        val fps = format.numberOf(MediaFormat.KEY_FRAME_RATE)?.toFloat()?.takeIf { it > 0 } ?: estimatedFps(stamps) ?: 30f
        val end = stamps.last() + if (stamps.size > 1) stamps.last() - stamps[stamps.size - 2] else (1_000_000 / fps).toLong()
        val used = layer.usedMediaMs()
        val whole = end <= WHOLE_SOURCE_US
        val first = if (whole) 0 else stamps.atOrBefore((used.start * 1000).toLong())
        val last = if (whole) stamps.size else stamps.atOrAfter((used.endInclusive * 1000).toLong()).coerceIn(first + 1, stamps.size)
        val mirror = if (last < stamps.size) stamps[last] else end
        val spanUs = mirror - stamps[first]
        val seconds = spanUs / 1e6
        val bitrate = (format.intOr(MediaFormat.KEY_BIT_RATE, 0).takeIf { it > 0 }
            ?: media.size.takeIf { it > 0 }?.let { (it * 8_000_000 / end.coerceAtLeast(1)).toInt() }
            ?: (width.toLong() * height * fps / 5).toInt()).coerceIn(1_000_000, 100_000_000)
        val frameBytes = width * height * 3 / 2
        val held = (HELD_BYTES / frameBytes).toInt().coerceIn(1, MAX_HELD_FRAMES).coerceAtMost(last - first)
        val file = aiFile(context, "mp4")
        val decoder = runCatching { PcmDecoder(context, uri) }.getOrNull()
        val yuvFile = File(context.cacheDir, "reverse-${UUID.randomUUID()}.yuv")
        val pcmFile = File(context.cacheDir, "reverse-${UUID.randomUUID()}.pcm")
        var encoder: YuvEncoder? = null
        var mux: Mux? = null
        var success = false
        try {
            if (stamps[first] > 0) decoder?.seek(stamps[first])
            val head = decoder?.next()
            val need = held.toLong() * frameBytes + (bitrate / 8.0 * seconds).toLong() + (head?.let { spanUs * it.rate / 1_000_000 * 4 } ?: 0L) + 1_000_000
            check(minOf(file.parentFile!!.usableSpace, context.cacheDir.usableSpace) >= need) { AppText.get(R.string.ai_not_enough_storage, (need / 1_000_000).toString()) }
            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(displayTurn(media.orientation)) }
            val output = Mux(MuxerSink(muxer), if (head != null) 2 else 1) { throw it }.also { mux = it }
            if (head != null) reverseSound(decoder, head, stamps[first], spanUs, pcmFile, output) { job.ensureActive() }
            decoder?.close()
            val total = last - first
            var done = 0
            val began = System.nanoTime()
            RandomAccessFile(yuvFile, "rw").use { store ->
                val channel = store.channel
                val frame = ByteBuffer.allocateDirect(frameBytes)
                FrameReader(context, uri).use { reader ->
                    for (piece in reversePieces(stamps, syncs, first, last, held)) {
                        job.ensureActive()
                        var got = 0
                        reader.read(piece.map { stamps[it] }) { _, yuv ->
                            check(yuv.width >= width && yuv.height >= height) { AppText.get(R.string.ai_unable_to_process) }
                            yuv.packInto(frame, width, height)
                            channel.writeAll(frame, got.toLong() * frameBytes)
                            got++
                        }
                        check(got > 0 || done > 0) { AppText.get(R.string.ai_unable_to_process) }
                        for (k in piece.last downTo piece.first) {
                            job.ensureActive()
                            val slot = k - piece.first
                            if (slot < got) channel.readAll(frame, slot.toLong() * frameBytes)
                            val video = encoder ?: YuvEncoder.open(output, width, height, fps.roundToInt().coerceIn(1, 240), bitrate,
                                if (reader.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED,
                                reader.colorStandard.takeIf { it == MediaFormat.COLOR_STANDARD_BT601_PAL || it == MediaFormat.COLOR_STANDARD_BT601_NTSC }
                                    ?: MediaFormat.COLOR_STANDARD_BT709).also { encoder = it }
                            video.write(mirror - if (k + 1 < last) stamps[k + 1] else mirror) { image -> frame.unpackInto(image, width, height) }
                            done++
                            progress(0.05f + 0.93f * done / total, reverseStatus(reverseSecondsLeft(done, total, System.nanoTime() - began)))
                        }
                    }
                }
            }
            val video = checkNotNull(encoder) { AppText.get(R.string.ai_unable_to_process) }
            video.finish()
            encoder = null
            video.close()
            return ReversedClip(aiMedia(context, file, "video/mp4", mapOf("width" to "$width", "height" to "$height", "orientation" to "${media.orientation}",
                "duration" to "$spanUs", "fps" to fpsText(fps))), mirror).also { success = true }
        } finally {
            runCatching { decoder?.close() }
            runCatching { encoder?.close() }
            mux?.release()
            yuvFile.delete()
            pcmFile.delete()
            if (!success) file.delete()
        }
    }

    private fun syncTimes(context: Context, uri: String): Pair<LongArray, MediaFormat> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(openableUri(uri)), null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val syncs = Stamps()
            while (extractor.sampleTime >= 0) {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            return syncs.sortedDistinct() to extractor.getTrackFormat(track)
        } finally {
            extractor.release()
        }
    }

    private fun reverseSound(decoder: PcmDecoder, head: PcmChunk, fromUs: Long, spanUs: Long, temp: File, mux: Mux, alive: () -> Unit) {
        val rate = head.rate
        val frames = spanUs * rate / 1_000_000
        RandomAccessFile(temp, "rw").use { store ->
            val channel = store.channel
            writeForward(decoder, head, rate, Math.round(fromUs * rate / 1e6), frames, channel, alive)
            val back = Backwards(channel, frames)
            val outRate = aacRate(rate)
            val mix: AudioMix = if (outRate == rate) { a, b -> alive(); back.read((Math.round(b * rate / 1e6) - Math.round(a * rate / 1e6)).toInt()) }
                else Downsampled({ count -> alive(); back.read(count) }, rate, outRate).mix
            val stop = AtomicBoolean()
            val failure = AtomicReference<Throwable>()
            val delay = AtomicInteger(aacDelayFrames(null))
            val codec = createAudioEncoder(AudioSettings(AudioCodec.Aac, if (outRate >= 32_000) 160 else 64, outRate, 2))
            val output = thread(name = "am-reverse-sound") {
                try { drain(codec, mux, stop, 0, delay, spanUs, rate = outRate) } catch (t: Throwable) { failure.compareAndSet(null, t); stop.set(true) }
            }
            try {
                feedAudio(codec, mix, spanUs, stop, delay, outRate)
                output.join()
                failure.get()?.let { throw it }
            } finally {
                stop.set(true)
                output.join()
                runCatching { codec.stop() }
                codec.release()
            }
        }
    }

    private fun writeForward(decoder: PcmDecoder, head: PcmChunk, rate: Int, base: Long, frames: Long, out: FileChannel, alive: () -> Unit) {
        var bytes = ByteBuffer.allocate(0)
        fun put(pcm: ShortArray?, offset: Int, count: Int, at: Long) {
            if (bytes.capacity() < count * 4) bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.clear()
            if (pcm == null) java.util.Arrays.fill(bytes.array(), 0, count * 4, 0) else bytes.asShortBuffer().put(pcm, offset * 2, count * 2)
            bytes.limit(count * 4)
            while (bytes.hasRemaining()) out.write(bytes, at * 4 + bytes.position())
        }
        fun zeros(from: Long, to: Long) {
            var at = from
            while (at < to) {
                val count = minOf(to - at, 1L shl 14).toInt()
                put(null, 0, count, at)
                at += count
            }
        }
        var end = 0L
        var chunk: PcmChunk? = head
        while (chunk != null && end < frames) {
            alive()
            check(chunk.rate == rate) { AppText.get(R.string.ai_sample_rate_changed) }
            val at = Math.round(chunk.ptsUs * rate / 1e6) - base
            val from = minOf(maxOf(at, end), frames)
            zeros(end, from)
            end = from
            val count = minOf(chunk.frames - (from - at), frames - from).toInt()
            if (count > 0) {
                put(chunk.pcm, (from - at).toInt(), count, from)
                end += count
            }
            chunk = decoder.next()
        }
        zeros(end, frames)
    }

    private class Backwards(private val channel: FileChannel, private val frames: Long) {
        private var done = 0L
        private var bytes = ByteBuffer.allocate(0)

        fun read(count: Int): ShortArray {
            val out = ShortArray(count * 2)
            val end = frames - done
            val n = minOf(count.toLong(), end).coerceAtLeast(0).toInt()
            if (n > 0) {
                if (bytes.capacity() < n * 4) bytes = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.clear()
                bytes.limit(n * 4)
                val at = (end - n) * 4
                while (bytes.hasRemaining()) check(channel.read(bytes, at + bytes.position()) >= 0) { AppText.get(R.string.ai_unable_to_process) }
                for (i in 0 until n) {
                    val source = (n - 1 - i) * 4
                    out[2 * i] = bytes.getShort(source)
                    out[2 * i + 1] = bytes.getShort(source + 2)
                }
            }
            done += count
            return out
        }
    }
}

private fun copyRows(src: ByteBuffer, from: Int, srcRow: Int, srcPixel: Int, dst: ByteBuffer, to: Int, dstRow: Int, dstPixel: Int, cols: Int, rows: Int) {
    for (r in 0 until rows) {
        val a = from + r * srcRow
        val b = to + r * dstRow
        if (srcPixel == 1 && dstPixel == 1) dst.duplicate().apply { position(b) }.put(src.duplicate().apply { limit(a + cols); position(a) })
        else for (c in 0 until cols) dst.put(b + c * dstPixel, src.get(a + c * srcPixel))
    }
}

private fun Yuv.packInto(frame: ByteBuffer, w: Int, h: Int) {
    val cw = w / 2
    val ch = h / 2
    copyRows(y, top * yRow + left * yPixel, yRow, yPixel, frame, 0, w, 1, w, h)
    copyRows(u, top / 2 * uvRow + left / 2 * uvPixel, uvRow, uvPixel, frame, w * h, cw, 1, cw, ch)
    copyRows(v, top / 2 * uvRow + left / 2 * uvPixel, uvRow, uvPixel, frame, w * h + cw * ch, cw, 1, cw, ch)
}

private fun ByteBuffer.unpackInto(image: Image, w: Int, h: Int) {
    val cw = w / 2
    val ch = h / 2
    val (yPlane, uPlane, vPlane) = image.planes
    copyRows(this, 0, w, 1, yPlane.buffer, 0, yPlane.rowStride, yPlane.pixelStride, w, h)
    copyRows(this, w * h, cw, 1, uPlane.buffer, 0, uPlane.rowStride, uPlane.pixelStride, cw, ch)
    copyRows(this, w * h + cw * ch, cw, 1, vPlane.buffer, 0, vPlane.rowStride, vPlane.pixelStride, cw, ch)
}

private fun FileChannel.writeAll(buffer: ByteBuffer, at: Long) {
    buffer.clear()
    while (buffer.hasRemaining()) write(buffer, at + buffer.position())
}

private fun FileChannel.readAll(buffer: ByteBuffer, at: Long) {
    buffer.clear()
    while (buffer.hasRemaining()) check(read(buffer, at + buffer.position()) >= 0) { AppText.get(R.string.ai_unable_to_process) }
}
