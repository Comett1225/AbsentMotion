package app.absentmotion.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.system.Os
import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

internal interface SampleSink {
    val negativeTimes: Boolean get() = true

    fun addTrack(format: MediaFormat): Int
    fun start()
    fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo)
    fun end(track: Int) {}
    fun queued(track: Int): Long = 0
    fun stop()
    fun release()
}

internal class Interleaver(private val emit: (track: Int, data: ByteArray, timeUs: Long, key: Boolean) -> Unit) {
    private class Sample(val data: ByteArray, val timeUs: Long, val key: Boolean)

    private val queues = ArrayList<ArrayDeque<Sample>>()
    private val ended = ArrayList<Boolean>()
    private val bytes = ArrayList<Long>()

    fun addTrack() {
        queues += ArrayDeque<Sample>()
        ended += false
        bytes += 0L
    }

    fun add(track: Int, data: ByteArray, timeUs: Long, key: Boolean) {
        queues[track].addLast(Sample(data, timeUs, key))
        bytes[track] += data.size.toLong()
        flush(all = false)
    }

    fun end(track: Int) {
        ended[track] = true
        flush(all = false)
    }

    fun queued(track: Int): Long = bytes[track]

    fun flush(all: Boolean) {
        while (all || queues.indices.none { queues[it].isEmpty() && !ended[it] }) {
            val next = queues.indices.filter { queues[it].isNotEmpty() }.minByOrNull { queues[it].first().timeUs } ?: return
            val sample = queues[next].removeFirst()
            bytes[next] -= sample.data.size.toLong()
            emit(next, sample.data, sample.timeUs, sample.key)
        }
    }
}

internal class MuxerSink(private val muxer: MediaMuxer) : SampleSink {
    constructor(fd: FileDescriptor, format: Int) : this(MediaMuxer(fd, format))
    constructor(path: String, format: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) : this(MediaMuxer(path, format))

    override val negativeTimes: Boolean get() = muxerTakesNegativeTimes(Build.VERSION.SDK_INT)

    override fun addTrack(format: MediaFormat) = muxer.addTrack(format)
    override fun start() = muxer.start()
    override fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) = muxer.writeSampleData(track, data, info)
    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

internal class MatroskaSink(fd: FileDescriptor, private val fps: Int, webm: Boolean = false, private val bt601: Boolean = false) : SampleSink {
    private val writer = MatroskaWriter(FileOutputStream(fd).channel, if (webm) "webm" else "matroska")
    private val tracks = ArrayList<MatroskaWriter.Track>()
    private val mimes = ArrayList<String>()
    private val queues = Interleaver(writer::write)
    private val shiftUs = ArrayList<Long>()

    override fun addTrack(format: MediaFormat): Int {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        fun csd(i: Int) = format.getByteBuffer("csd-$i")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
        val nals = (0..2).mapNotNull(::csd).flatMap { NalUnits.split(it) }
        fun video(id: String, private: ByteArray?) = MatroskaWriter.Track(
            true, id, private, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), frameDurationNs(fps), bt601 = bt601,
        )
        fun audio(id: String, private: ByteArray?, delayNs: Long = 0, preRollNs: Long = 0) = MatroskaWriter.Track(
            false, id, private, sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble(),
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), codecDelayNs = delayNs, seekPreRollNs = preRollNs,
        )
        tracks += when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> video("V_MPEG4/ISO/AVC", NalUnits.avcC(nals))
            MediaFormat.MIMETYPE_VIDEO_HEVC -> video("V_MPEGH/ISO/HEVC", NalUnits.hvcC(nals))
            MediaFormat.MIMETYPE_VIDEO_AV1 -> video("V_AV1", csd(0)?.let(::av1Config))
            MediaFormat.MIMETYPE_VIDEO_VP9 -> video("V_VP9", null)
            MediaFormat.MIMETYPE_AUDIO_AAC -> audio("A_AAC", csd(0), primingFrames(format) * 1_000_000_000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            MediaFormat.MIMETYPE_AUDIO_OPUS -> opusConfig(csd(0) ?: ByteArray(0), csd(1), csd(2)).let { audio("A_OPUS", it.head, it.delayNs, it.preRollNs) }
            else -> error("MKV files cannot hold $mime")
        }
        mimes += mime
        queues.addTrack()
        shiftUs +=if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) primingFrames(format) * 1_000_000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0L
        return tracks.size - 1
    }

    override fun start() = writer.start(tracks)

    override fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        data.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(bytes)
        val payload = when (mimes[track]) {
            MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC -> NalUnits.lengthPrefixed(bytes)
            MediaFormat.MIMETYPE_VIDEO_AV1 -> NalUnits.withoutTemporalDelimiter(bytes)
            MediaFormat.MIMETYPE_VIDEO_VP9 -> vp9ColourSpace(bytes, if (bt601) Vp9Bt601 else Vp9Bt709)
            else -> bytes
        }
        val key = !tracks[track].video || info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        queues.add(track, payload, info.presentationTimeUs + shiftUs[track], key)
    }

    override fun end(track: Int) = queues.end(track)

    override fun queued(track: Int): Long = queues.queued(track)

    override fun stop() {
        queues.flush(all = true)
        writer.finish()
    }

    override fun release() {}
}

private fun primingFrames(format: MediaFormat): Long =
    if (format.containsKey(PrimingKey)) format.getInteger(PrimingKey).toLong() else 0L

internal fun av1Config(csd: ByteArray): ByteArray {
    if (csd.isNotEmpty() && csd[0].toInt() and 0xFF == 0x81) return csd
    val (profile, level, tier) = runCatching { av1Sequence(csd) }.getOrNull() ?: Triple(0, 31, 0)
    val chroma = when (profile) { 1 -> 0x00; 2 -> 0x08; else -> 0x0C }
    return byteArrayOf(0x81.toByte(), ((profile shl 5) or level).toByte(), ((tier shl 7) or chroma).toByte(), 0) + csd
}

private fun av1Sequence(obus: ByteArray): Triple<Int, Int, Int>? {
    var at = 0
    while (at < obus.size) {
        val header = obus[at].toInt() and 0xFF
        var body = at + 1 + ((header shr 2) and 1)
        var size = obus.size - body
        if (header and 2 != 0) {
            size = 0
            var shift = 0
            while (true) {
                val b = obus[body++].toInt() and 0xFF
                size = size or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0 || shift > 28) break
            }
        }
        if ((header shr 3) and 15 == 1) return av1Levels(obus, body)
        at = body + size
    }
    return null
}

private fun av1Levels(data: ByteArray, start: Int): Triple<Int, Int, Int> {
    var bit = start * 8L
    fun read(count: Int): Int {
        var value = 0
        repeat(count) {
            value = (value shl 1) or ((data[(bit ushr 3).toInt()].toInt() shr (7 - (bit and 7).toInt())) and 1)
            bit++
        }
        return value
    }
    val profile = read(3)
    read(1)
    if (read(1) == 1) return Triple(profile, read(5), 0)
    if (read(1) == 1) {
        bit += 64
        if (read(1) == 1) {
            var zeros = 0
            while (read(1) == 0) zeros++
            bit += zeros
        }
        if (read(1) == 1) bit += 47
    }
    read(1)
    read(5)
    read(12)
    val level = read(5)
    return Triple(profile, level, if (level > 7) read(1) else 0)
}

internal fun openSink(container: Container, fd: FileDescriptor, fps: Int, bt601: Boolean = false): SampleSink = when (container) {
    Container.Mp4, Container.Mov -> MuxerSink(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    Container.WebM -> MatroskaSink(fd, fps, webm = true, bt601 = bt601)
    Container.Mkv -> MatroskaSink(fd, fps, bt601 = bt601)
}

internal fun quickTimeBrands(head: ByteArray): ByteArray? {
    if (head.size < 16 || String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return null
    val size = ByteBuffer.wrap(head, 0, 4).int
    if (size < 16 || size > head.size || size % 4 != 0) return null
    val out = head.copyOf(size)
    val qt = "qt  ".toByteArray(Charsets.US_ASCII)
    qt.copyInto(out, 8)
    ByteBuffer.wrap(out, 12, 4).putInt(0x200)
    for (at in 16 until size step 4) qt.copyInto(out, at)
    return out
}

internal fun markQuickTime(fd: FileDescriptor) {
    val head = ByteArray(64)
    val read = Os.pread(fd, head, 0, head.size, 0)
    val patched = quickTimeBrands(head.copyOf(read)) ?: error("The MP4 writer produced no file type box")
    Os.pwrite(fd, patched, 0, patched.size, 0)
}

internal const val MuxBudgetBytes = 8L shl 20

internal class Mux(
    private val sink: SampleSink, private val tracks: Int, private val budget: Long = Long.MAX_VALUE,
    private val stopped: () -> Boolean = { false }, private val onError: (Throwable) -> Unit,
) {
    val negativeTimes: Boolean get() = sink.negativeTimes

    private var added = 0
    private var started = false
    private var broken = false
    private var released = false
    private val early = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()
    private val earlyBytes = HashMap<Int, Long>()
    private val endedEarly = ArrayList<Int>()

    private inline fun <T> guarded(otherwise: T, op: () -> T): T {
        if (broken) return otherwise
        return try { op() } catch (t: Exception) { broken = true; onError(t); otherwise }
    }

    @Synchronized private fun full(track: Int): Boolean =
        !broken && !released && (if (started) sink.queued(track) else earlyBytes[track] ?: 0L) > budget

    @Synchronized fun addTrack(format: MediaFormat): Int = guarded(-1) {
        val id = sink.addTrack(format)
        if (++added == tracks) {
            sink.start()
            started = true
            for ((track, data, info) in early) sink.write(track, data, info)
            early.clear()
            earlyBytes.clear()
            endedEarly.forEach(sink::end)
        }
        id
    }

    fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        while (full(track) && !stopped()) Thread.sleep(5)
        put(track, data, info)
    }

    @Synchronized private fun put(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) = guarded(Unit) {
        if (started) return@guarded sink.write(track, data, info)
        val copy = ByteBuffer.allocate(info.size)
        copy.put(data.duplicate().apply { position(info.offset); limit(info.offset + info.size) }).flip()
        early += Triple(track, copy, MediaCodec.BufferInfo().also { it.size = info.size; it.presentationTimeUs = info.presentationTimeUs; it.flags = info.flags })
        earlyBytes[track] = (earlyBytes[track] ?: 0L) + info.size
    }

    @Synchronized fun end(track: Int) = guarded(Unit) {
        if (started) sink.end(track) else endedEarly += track
    }

    @Synchronized fun finish() {
        exportCheck(started) { txt(R.string.export_error_no_video) }
        sink.stop()
    }

    @Synchronized fun release() {
        if (!released) { released = true; runCatching { sink.release() } }
    }
}
