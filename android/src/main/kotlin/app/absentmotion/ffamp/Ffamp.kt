package app.absentmotion.ffamp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.absentmotion.ai.argb
import app.absentmotion.ai.turned
import app.absentmotion.audio.PcmChunk
import app.absentmotion.audio.PcmSource
import app.absentmotion.audio.downmixGains
import app.absentmotion.library.openableUri
import app.absentmotion.media.DecodedFrame
import app.absentmotion.media.Demuxer
import app.absentmotion.media.DesktopFrameInput
import app.absentmotion.media.FfampReaders
import app.absentmotion.media.FrameGrabber
import app.absentmotion.media.FrameReader
import app.absentmotion.media.StopReading
import app.absentmotion.media.VideoSources
import app.absentmotion.media.Yuv
import app.absentmotion.media.decoderNames
import app.absentmotion.media.videoColorStandard
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

internal object FfampNative {
    init { System.loadLibrary("amffamp") }
    external fun open(path: String): Long
    external fun error(): String
    external fun close(handle: Long)
    external fun container(handle: Long): String
    external fun tracks(handle: Long): Int
    external fun mime(handle: Long, track: Int): String
    external fun track(handle: Long, track: Int): LongArray?
    external fun csd(handle: Long, track: Int): Array<ByteArray>
    external fun table(handle: Long, track: Int, pts: LongArray, sizes: IntArray, syncs: BooleanArray)
    external fun read(handle: Long, track: Int, index: Int, buffer: ByteBuffer, offset: Int): Int
    external fun videoOpen(handle: Long, track: Int): Long
    external fun videoName(video: Long): String
    external fun videoFeed(video: Long, index: Int): Int
    external fun videoTake(video: Long, buffer: ByteBuffer, shape: IntArray): Long
    external fun videoReset(video: Long)
    external fun videoClose(video: Long)
    external fun audioOpen(handle: Long, track: Int): Long
    external fun audioShape(audio: Long): LongArray
    external fun audioRead(audio: Long, start: Long, frames: Int, out: FloatArray): Boolean
    external fun audioClose(audio: Long)
    external fun picture(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, yRow: Int, uvRow: Int, width: Int, height: Int, coefficients: IntArray): Long
    external fun pictureBuffer(picture: Long): android.hardware.HardwareBuffer?
    external fun pictureRelease(picture: Long)
}

internal class FfampSource private constructor(private val descriptor: ParcelFileDescriptor?, val handle: Long) : Closeable {
    val tracks: Int = FfampNative.tracks(handle)

    fun info(track: Int): LongArray = FfampNative.track(handle, track) ?: throw IOException("No track $track")

    fun first(kind: Int): Int = (0 until tracks).firstOrNull { info(it)[0] == kind.toLong() } ?: -1

    fun format(track: Int): MediaFormat {
        val v = info(track)
        return MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, FfampNative.mime(handle, track))
            if (v[6] > 0) setLong(MediaFormat.KEY_DURATION, v[6])
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (v[8] + v[8] / 4 + 4096).toInt())
            if (v[0] == 1L) {
                setInteger(MediaFormat.KEY_WIDTH, v[1].toInt())
                setInteger(MediaFormat.KEY_HEIGHT, v[2].toInt())
                if (v[3] != 0L) setInteger(MediaFormat.KEY_ROTATION, v[3].toInt())
                if (v[17] > 0) setInteger(MediaFormat.KEY_PROFILE, v[17].toInt())
                if (v[18] > 0) setInteger(MediaFormat.KEY_LEVEL, v[18].toInt())
                if (v[6] > 0 && v[7] > 1) {
                    val fps = v[7] * 1_000_000.0 / v[6]
                    if (kotlin.math.abs(fps - Math.rint(fps)) < 0.01) setInteger(MediaFormat.KEY_FRAME_RATE, Math.rint(fps).toInt()) else setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
                }
                if (v[9] != 0L) {
                    colorStandardOf(v[10], v[12])?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, when (v[11].toInt()) { 16 -> MediaFormat.COLOR_TRANSFER_ST2084; 18 -> MediaFormat.COLOR_TRANSFER_HLG; else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO })
                    setInteger(MediaFormat.KEY_COLOR_RANGE, if (v[13] != 0L) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
                }
            } else if (v[0] == 2L) {
                setInteger(MediaFormat.KEY_SAMPLE_RATE, v[4].toInt())
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, v[5].toInt())
            }
            FfampNative.csd(handle, track).forEachIndexed { i, bytes -> setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
        }
    }

    fun systemDecodes(track: Int): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format(track)) != null

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        FfampNative.close(handle)
        descriptor?.close()
    }

    companion object {
        fun open(context: Context, uri: Uri): FfampSource {
            val descriptor = if (uri.scheme == "file") null else context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
            val path = descriptor?.let { "/proc/self/fd/${it.fd}" } ?: uri.path ?: throw IOException("Cannot open $uri")
            val handle = FfampNative.open(path)
            if (handle == 0L) {
                descriptor?.close()
                throw IOException("FFamp cannot open $uri: ${FfampNative.error()}")
            }
            return FfampSource(descriptor, handle)
        }

        fun open(context: Context, uri: String): FfampSource = open(context, Uri.parse(openableUri(uri)))
    }
}

internal fun colorStandardOf(primaries: Long, matrix: Long): Int? = when (matrix) {
    1L -> MediaFormat.COLOR_STANDARD_BT709
    5L -> MediaFormat.COLOR_STANDARD_BT601_PAL
    6L -> MediaFormat.COLOR_STANDARD_BT601_NTSC
    9L, 10L -> MediaFormat.COLOR_STANDARD_BT2020
    2L -> when (primaries) {
        1L -> MediaFormat.COLOR_STANDARD_BT709
        5L -> MediaFormat.COLOR_STANDARD_BT601_PAL
        6L -> MediaFormat.COLOR_STANDARD_BT601_NTSC
        9L -> MediaFormat.COLOR_STANDARD_BT2020
        else -> null
    }
    else -> null
}

internal fun nearestStamp(stamps: LongArray, timeUs: Long): Long {
    if (stamps.isEmpty()) return timeUs
    val at = stamps.binarySearch(timeUs)
    if (at >= 0) return stamps[at]
    val after = -at - 1
    if (after == 0) return stamps[0]
    if (after == stamps.size) return stamps[after - 1]
    return if (timeUs - stamps[after - 1] <= stamps[after] - timeUs) stamps[after - 1] else stamps[after]
}

internal class Table(source: FfampSource, track: Int) {
    val count = source.info(track)[7].toInt()
    val pts = LongArray(count)
    val sizes = IntArray(count)
    val syncs = BooleanArray(count)
    init { FfampNative.table(source.handle, track, pts, sizes, syncs) }
    private val keys = syncs.indices.filter { syncs[it] }.toIntArray()

    fun syncBefore(timeUs: Long): Int = keys.lastOrNull { pts[it] <= timeUs } ?: keys.firstOrNull() ?: 0
    fun syncAfter(timeUs: Long): Int = keys.firstOrNull { pts[it] >= timeUs } ?: count
    fun syncNear(timeUs: Long): Int = listOf(syncBefore(timeUs), syncAfter(timeUs)).filter { it < count }.minByOrNull { kotlin.math.abs(pts[it] - timeUs) } ?: 0
}

internal class FfampExtractor(private val source: FfampSource) : Demuxer {
    private var track = -1
    private var table: Table? = null
    private var at = 0

    override val trackCount: Int get() = source.tracks

    override fun getTrackFormat(index: Int): MediaFormat = source.format(index)

    override fun selectTrack(index: Int) {
        track = index
        table = Table(source, index)
        at = 0
    }

    private val valid: Boolean get() = table?.let { at < it.count } == true
    override val sampleTime: Long get() = if (valid) table!!.pts[at] else -1L
    override val hasSample: Boolean get() = valid
    override val sampleFlags: Int get() = if (valid) (if (table!!.syncs[at]) MediaExtractor.SAMPLE_FLAG_SYNC else 0) else -1

    override fun advance(): Boolean {
        if (!valid) return false
        at++
        return valid
    }

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        if (!valid) return -1
        val size = FfampNative.read(source.handle, track, at, buffer, offset)
        if (size < 0) return -1
        buffer.limit(offset + size).position(offset)
        return size
    }

    override fun seekTo(timeUs: Long, mode: Int) {
        val t = table ?: return
        at = when (mode) {
            MediaExtractor.SEEK_TO_NEXT_SYNC -> t.syncAfter(timeUs)
            MediaExtractor.SEEK_TO_CLOSEST_SYNC -> t.syncNear(timeUs)
            else -> t.syncBefore(timeUs)
        }
    }

    override fun release() {
        source.close()
        table = null
        track = -1
    }
}

internal class FfampRetriever(context: Context, uri: Uri) : FrameGrabber {
    private val context = context.applicationContext
    private val uri = uri.toString()
    private val source = FfampSource.open(context, uri)
    private val video = source.first(1)
    private var reader: FrameReader? = null

    init {
        if (video < 0) {
            source.close()
            throw IOException("No video track in $uri")
        }
    }

    override fun extractMetadata(key: Int): String? {
        val v = source.info(video)
        return when (key) {
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION -> v[3].toString()
            MediaMetadataRetriever.METADATA_KEY_DURATION -> (v[6] / 1000).toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH -> v[1].toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT -> v[2].toString()
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO -> "yes"
            MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO -> if (source.first(2) >= 0) "yes" else null
            else -> null
        }
    }

    override fun scaledFrame(timeUs: Long, option: Int, width: Int, height: Int): Bitmap? {
        val frames = reader ?: FrameReader(context, uri).also { reader = it }
        val wanted = if (option == MediaMetadataRetriever.OPTION_CLOSEST) nearestStamp(frames.timestamps, timeUs) else frames.timestamps.let { stamps ->
            stamps.lastOrNull { it <= timeUs } ?: stamps.firstOrNull() ?: timeUs
        }
        val turn = Math.floorMod(source.info(video)[3].toInt(), 360)
        val sideways = turn % 180 != 0
        var picture: Bitmap? = null
        runBlocking { frames.read(listOf(wanted)) { _, yuv -> picture = yuv.toBitmap(if (sideways) height else width, if (sideways) width else height, frames.colorStandard, frames.fullRange) } }
        return picture?.let { flat -> flat.turned(turn).also { if (it !== flat) flat.recycle() } }
    }

    override fun release() {
        reader?.close()
        reader = null
        source.close()
    }
}

internal object FfampMedia : FfampReaders {
    override fun demuxer(context: Context, uri: Uri): Demuxer = FfampExtractor(FfampSource.open(context, uri))
    override fun grabber(context: Context, uri: Uri): FrameGrabber = FfampRetriever(context, uri)
    override fun softFrames(context: Context, uri: String, rescue: Boolean): DesktopFrameInput? = FfampSoftFrames.openIfNeeded(context, uri, rescue)
    override fun softFrame(yuv: Yuv, pts: Long, standard: Int, fullRange: Boolean): DecodedFrame = SoftFrame(yuv, pts, standard, fullRange)
    override fun reads(context: Context, uri: Uri, kind: String): Boolean = runCatching {
        FfampSource.open(context, uri).use { source ->
            val audio = kind.startsWith("audio/")
            val track = source.first(if (audio) 2 else 1)
            track >= 0 && (decoderNames(source.format(track)).isNotEmpty() || if (audio) {
                FfampNative.audioOpen(source.handle, track).also { if (it != 0L) FfampNative.audioClose(it) } != 0L
            } else {
                FfampNative.videoOpen(source.handle, track).also { if (it != 0L) FfampNative.videoClose(it) } != 0L
            })
        }
    }.getOrDefault(false)
}

internal class SoftFrame(yuv: Yuv, pts: Long, standard: Int, fullRange: Boolean) : DecodedFrame {
    private var picture = FfampNative.picture(yuv.y, yuv.u, yuv.v, yuv.yRow, yuv.uvRow, yuv.width, yuv.height, yuvToRgb(shownStandard(standard, yuv.height), fullRange)).also { check(it != 0L) { "cannot make a ${yuv.width} x ${yuv.height} picture" } }
    override val timestamp: Long = pts * 1000
    override val cropRect = android.graphics.Rect(0, 0, yuv.width, yuv.height)
    override val hardwareBuffer: android.hardware.HardwareBuffer? get() = if (picture == 0L) null else FfampNative.pictureBuffer(picture)
    override fun close() {
        if (picture != 0L) FfampNative.pictureRelease(picture)
        picture = 0L
    }
}

internal fun shownStandard(standard: Int, height: Int): Int = if (standard != 0) standard else if (height <= 576) MediaFormat.COLOR_STANDARD_BT601_PAL else MediaFormat.COLOR_STANDARD_BT709

internal fun yuvToRgb(standard: Int, fullRange: Boolean): IntArray {
    val kr = if (standard == MediaFormat.COLOR_STANDARD_BT709) 0.2126 else if (standard == MediaFormat.COLOR_STANDARD_BT2020) 0.2627 else 0.299
    val kb = if (standard == MediaFormat.COLOR_STANDARD_BT709) 0.0722 else if (standard == MediaFormat.COLOR_STANDARD_BT2020) 0.0593 else 0.114
    val kg = 1 - kr - kb
    val ys = if (fullRange) 256.0 else 256 * 255.0 / 219
    val cs = if (fullRange) 256.0 else 256 * 255.0 / 224
    return intArrayOf(if (fullRange) 0 else 16, Math.round(ys).toInt(), Math.round(2 * (1 - kr) * cs).toInt(), Math.round(2 * kb * (1 - kb) / kg * cs).toInt(),
        Math.round(2 * kr * (1 - kr) / kg * cs).toInt(), Math.round(2 * (1 - kb) * cs).toInt())
}

internal fun Yuv.toBitmap(maxWidth: Int, maxHeight: Int, standard: Int, fullRange: Boolean): Bitmap {
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height, 1f)
    val w = maxOf(1, Math.round(width * scale))
    val h = maxOf(1, Math.round(height * scale))
    return Bitmap.createBitmap(argb(w, h, shownStandard(standard, height), fullRange), w, h, Bitmap.Config.ARGB_8888)
}

internal class FfampSoftFrames private constructor(context: Context, uri: String, private val source: FfampSource, track: Int, private val video: Long) : DesktopFrameInput {
    private val table = Table(source, track)
    private val shape = source.info(track)
    private var frame = ByteBuffer.allocateDirect((shape[1] * shape[2] * 3 / 2 + shape[1] * 2 + shape[2] * 2 + 64).toInt())
    private val dims = IntArray(3)
    private var next = 0
    private var queued = 0
    private var drained = false
    private var positioned = false
    private var lastPts = Long.MIN_VALUE
    override val timestamps: LongArray = VideoSources.timestamps(context, uri)
    override var colorStandard: Int = source.format(track).let { if (it.containsKey(MediaFormat.KEY_COLOR_STANDARD)) videoColorStandard(it.getInteger(MediaFormat.KEY_COLOR_STANDARD)) else 0 }
        private set
    override var fullRange: Boolean = shape[9] != 0L && shape[13] != 0L
        private set

    override suspend fun read(wanted: List<Long>, consume: suspend (Long, Yuv) -> Unit): Int {
        if (wanted.isEmpty()) return 0
        if (!positioned || wanted.first() <= lastPts || wanted.first() - lastPts > SKIP_US) {
            FfampNative.videoReset(video)
            next = table.syncBefore(wanted.first())
            drained = false
            positioned = true
            queued = 0
        }
        var i = 0
        val stop = coroutineContext[StopReading]
        while (i < wanted.size) {
            coroutineContext.ensureActive()
            if (stop?.now?.invoke() == true) break
            if (queued == 0) {
                val count = when {
                    next < table.count -> FfampNative.videoFeed(video, next++)
                    !drained -> { drained = true; FfampNative.videoFeed(video, -1) }
                    else -> break
                }
                if (count < 0) break
                queued = count
                continue
            }
            val stamp = FfampNative.videoTake(video, frame, dims)
            if (stamp == Long.MIN_VALUE + 2) {
                val needed = dims[0] * dims[1] + 2 * ((dims[0] + 1) / 2) * ((dims[1] + 1) / 2)
                if (needed <= frame.capacity()) throw IOException("FFamp cannot hand over a ${dims[0]} x ${dims[1]} picture")
                frame = ByteBuffer.allocateDirect(needed)
                continue
            }
            queued--
            if (stamp == Long.MIN_VALUE) { queued = 0; continue }
            if (stamp == Long.MIN_VALUE + 1) continue
            lastPts = stamp
            if (dims[2] != 0) {
                colorStandard = MediaFormat.COLOR_STANDARD_BT709
                fullRange = false
            }
            if (wanted[i] <= stamp) {
                val luma = dims[0] * dims[1]
                val cw = (dims[0] + 1) / 2
                val chroma = cw * ((dims[1] + 1) / 2)
                val y = frame.duplicate().apply { position(0); limit(luma) }.slice()
                val u = frame.duplicate().apply { position(luma); limit(luma + chroma) }.slice()
                val v = frame.duplicate().apply { position(luma + chroma); limit(luma + 2 * chroma) }.slice()
                val yuv = Yuv(y, dims[0], 1, u, v, cw, 1, 0, 0, dims[0], dims[1])
                while (i < wanted.size && wanted[i] <= stamp) consume(wanted[i++], yuv)
            }
        }
        if (drained && next >= table.count && queued == 0) lastPts = Long.MAX_VALUE
        return i
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        FfampNative.videoClose(video)
        source.close()
    }

    companion object {
        private const val SKIP_US = 2_000_000L
        private const val MPEG4_PART2 = 0x6d703476L

        fun openIfNeeded(context: Context, uri: String, rescue: Boolean = false): DesktopFrameInput? {
            val source = runCatching { FfampSource.open(context, uri) }.getOrNull() ?: return null
            val track = source.first(1)
            val video = if (track >= 0 && (rescue || source.info(track)[15] == MPEG4_PART2 || !source.systemDecodes(track))) FfampNative.videoOpen(source.handle, track) else 0L
            if (video == 0L) { source.close(); return null }
            return try { FfampSoftFrames(context, uri, source, track, video) } catch (e: Exception) {
                FfampNative.videoClose(video)
                source.close()
                throw e
            }
        }
    }
}

internal class FfampPcm private constructor(private val source: FfampSource, private val audio: Long, private val lead: Long) : PcmSource {
    private val rate: Int
    private val channels: Int
    private val length: Long
    private val chunk = PcmChunk()
    private var position = 0L
    private val gains: FloatArray?
    private val floats: FloatArray

    init {
        val s = FfampNative.audioShape(audio)
        rate = s[0].toInt()
        channels = s[1].toInt()
        length = s[2]
        gains = if (channels > 2) downmixGains(channels, (s.getOrElse(3) { 0L } shl 2).toInt()) else null
        floats = FloatArray(CHUNK * channels)
        chunk.pcm = ShortArray(CHUNK * 2)
        chunk.rate = rate
    }

    override val durationUs: Long get() = length * 1_000_000L / rate

    override fun seek(us: Long) { position = frameAt(us, lead, rate, length) }

    override fun next(): PcmChunk? {
        if (position >= length) return null
        val frames = minOf(CHUNK.toLong(), length - position).toInt()
        if (!FfampNative.audioRead(audio, position, frames, floats)) return null
        val out = chunk.pcm
        for (f in 0 until frames) {
            var l: Float
            var r: Float
            if (gains != null) {
                l = 0f; r = 0f
                for (c in 0 until channels) {
                    val x = floats[f * channels + c]
                    l += gains[2 * c] * x
                    r += gains[2 * c + 1] * x
                }
            } else {
                l = floats[f * channels]
                r = if (channels > 1) floats[f * channels + 1] else l
            }
            out[2 * f] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * f + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        chunk.frames = frames
        chunk.ptsUs = ptsOf(position, lead, rate)
        position += frames
        return chunk
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        FfampNative.audioClose(audio)
        source.close()
    }

    companion object {
        private const val CHUNK = 4096
        private const val LPCM = 0x6c70636dL

        fun openIfNeeded(context: Context, uri: String, rescue: Boolean = false): PcmSource? {
            val source = runCatching { FfampSource.open(context, uri) }.getOrNull() ?: return null
            val track = source.first(2)
            val audio = if (track >= 0 && (rescue || !source.systemDecodes(track) || source.info(track).let { pcmNeedsFfamp(it[15], it[16], it.getOrElse(20) { 0L }) })) FfampNative.audioOpen(source.handle, track) else 0L
            if (audio == 0L) { source.close(); return null }
            return FfampPcm(source, audio, source.info(track)[19])
        }

        fun frameAt(us: Long, lead: Long, rate: Int, length: Long): Long = ((us - lead).coerceAtLeast(0) * rate / 1_000_000L).coerceAtMost(length)

        fun ptsOf(frame: Long, lead: Long, rate: Int): Long = frame * 1_000_000L / rate + lead

        fun pcmNeedsFfamp(codec: Long, bits: Long, bigEndian: Long = 0L): Boolean = codec == LPCM && (bits != 16L || bigEndian != 0L)
    }
}
