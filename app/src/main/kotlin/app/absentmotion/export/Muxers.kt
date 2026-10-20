package app.absentmotion.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.system.Os
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

internal interface SampleSink {
    fun addTrack(format: MediaFormat): Int
    fun start()
    fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo)
    fun stop()
    fun release()
}

internal class MuxerSink(private val muxer: MediaMuxer) : SampleSink {
    constructor(fd: FileDescriptor, format: Int) : this(MediaMuxer(fd, format))
    constructor(path: String, format: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) : this(MediaMuxer(path, format))

    override fun addTrack(format: MediaFormat) = muxer.addTrack(format)
    override fun start() = muxer.start()
    override fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) = muxer.writeSampleData(track, data, info)
    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

internal class MatroskaSink(fd: FileDescriptor, private val fps: Int, webm: Boolean = false) : SampleSink {
    private class Sample(val data: ByteArray, val timeUs: Long, val key: Boolean)

    private val writer = MatroskaWriter(FileOutputStream(fd).channel, if (webm) "webm" else "matroska")
    private val tracks = ArrayList<MatroskaWriter.Track>()
    private val mimes = ArrayList<String>()
    private val queues = ArrayList<ArrayDeque<Sample>>()
    private val shiftUs = ArrayList<Long>()

    override fun addTrack(format: MediaFormat): Int {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        fun csd(i: Int) = format.getByteBuffer("csd-$i")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
        val nals = (0..2).mapNotNull(::csd).flatMap { NalUnits.split(it) }
        fun video(id: String, private: ByteArray?) = MatroskaWriter.Track(
            true, id, private, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), frameDurationNs(fps),
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
        queues += ArrayDeque<Sample>()
        shiftUs += if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) primingFrames(format) * 1_000_000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0L
        return tracks.size - 1
    }

    override fun start() = writer.start(tracks)

    override fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        data.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(bytes)
        val payload = when (mimes[track]) {
            MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC -> NalUnits.lengthPrefixed(bytes)
            MediaFormat.MIMETYPE_VIDEO_AV1 -> NalUnits.withoutTemporalDelimiter(bytes)
            else -> bytes
        }
        val key = !tracks[track].video || info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        queues[track].addLast(Sample(payload, info.presentationTimeUs + shiftUs[track], key))
        flush(all = false)
    }

    private fun flush(all: Boolean) {
        while (all || queues.none { it.isEmpty() }) {
            val next = queues.indices.filter { queues[it].isNotEmpty() }.minByOrNull { queues[it].first().timeUs } ?: return
            val sample = queues[next].removeFirst()
            writer.write(next, sample.data, sample.timeUs, sample.key)
        }
    }

    override fun stop() {
        flush(all = true)
        writer.finish()
    }

    override fun release() {}
}

private fun primingFrames(format: MediaFormat): Long =
    if (format.containsKey(PrimingKey)) format.getInteger(PrimingKey).toLong() else 0L

internal fun av1Config(csd: ByteArray): ByteArray =
    if (csd.isNotEmpty() && csd[0].toInt() and 0xFF == 0x81) csd else byteArrayOf(0x81.toByte(), 0, 0x0C, 0) + csd

internal fun openSink(container: Container, fd: FileDescriptor, fps: Int): SampleSink = when (container) {
    Container.Mp4, Container.Mov -> MuxerSink(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    Container.WebM -> MatroskaSink(fd, fps, webm = true)
    Container.Mkv -> MatroskaSink(fd, fps)
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

internal class Mux(private val sink: SampleSink, private val tracks: Int, private val onError: (Throwable) -> Unit) {
    private var added = 0
    private var started = false
    private var broken = false
    private var released = false
    private val early = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    private inline fun <T> guarded(otherwise: T, op: () -> T): T {
        if (broken) return otherwise
        return try { op() } catch (t: Exception) { broken = true; onError(t); otherwise }
    }

    @Synchronized fun addTrack(format: MediaFormat): Int = guarded(-1) {
        val id = sink.addTrack(format)
        if (++added == tracks) {
            sink.start()
            started = true
            for ((track, data, info) in early) sink.write(track, data, info)
            early.clear()
        }
        id
    }

    @Synchronized fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) = guarded(Unit) {
        if (started) return@guarded sink.write(track, data, info)
        val copy = ByteBuffer.allocate(info.size)
        copy.put(data.duplicate().apply { position(info.offset); limit(info.offset + info.size) }).flip()
        early += Triple(track, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
    }

    @Synchronized fun finish() {
        check(started) { "The encoder produced no video" }
        sink.stop()
    }

    @Synchronized fun release() {
        if (!released) { released = true; runCatching { sink.release() } }
    }
}
