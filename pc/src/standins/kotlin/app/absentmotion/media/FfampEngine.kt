package app.absentmotion.media

import app.absentmotion.desk.DeskProbe
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.VideoFrame
import app.absentmotion.pc.engine.VideoInfo
import app.absentmotion.pc.engine.VideoSource
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object FfampNative {
    init { DesktopNative.hashCode() }

    external fun probe(path: String): String?
    external fun packets(path: String, stream: Int): DoubleArray?
    external fun pcmOpen(path: String, rate: Int, channels: Int, seconds: Double): Long
    external fun pcmRead(handle: Long, out: ByteArray, frames: Int, whole: Boolean): Int
    external fun pcmClose(handle: Long)
    external fun framesOpen(path: String, width: Int, height: Int, layout: Int, rotate: Boolean): Long
    external fun framesInfo(handle: Long): DoubleArray
    external fun framesIndexAt(handle: Long, seconds: Double): Int
    external fun framesSeconds(handle: Long, index: Int): Double
    external fun framesRead(handle: Long, index: Int, out: ByteArray): Boolean
    external fun framesClose(handle: Long)
}

private const val RGBA = 0
private const val I420 = 2
private const val BGRA = 3

private abstract class ChunkInput(size: Int) : InputStream() {
    protected val chunk = ByteArray(size)
    private var at = 0
    private var filled = 0

    protected abstract fun fill(): Int

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (at == filled) {
            filled = fill().coerceAtLeast(0)
            at = 0
            if (filled == 0) return -1
        }
        val n = minOf(len, filled - at)
        System.arraycopy(chunk, at, b, off, n)
        at += n
        return n
    }
}

private class PcmInput(file: File, rate: Int, channels: Int, seconds: Double, private val whole: Boolean) : ChunkInput(PCM_FRAMES * channels * if (whole) 2 else 4) {
    private var handle = FfampNative.pcmOpen(file.path, rate, channels, seconds)
    private val unit = channels * if (whole) 2 else 4

    @Synchronized override fun fill(): Int = if (handle == 0L) 0 else FfampNative.pcmRead(handle, chunk, PCM_FRAMES, whole) * unit

    @Synchronized override fun close() {
        if (handle != 0L) FfampNative.pcmClose(handle)
        handle = 0L
    }

    private companion object {
        const val PCM_FRAMES = 4096
    }
}

private class Frames(file: File, askedWidth: Int, askedHeight: Int, layout: Int, rotate: Boolean) : Closeable {
    private var handle = FfampNative.framesOpen(file.path, askedWidth, askedHeight, layout, rotate)
    private val info = if (handle != 0L) FfampNative.framesInfo(handle) else DoubleArray(8)
    val open = handle != 0L
    val width = info[0].toInt()
    val height = info[1].toInt()
    val count = info[5].toInt()
    val length = info[7]
    val bytes = if (layout == I420) width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2) else width * height * 4

    @Synchronized fun indexAt(seconds: Double): Int = if (handle == 0L) count else FfampNative.framesIndexAt(handle, seconds)
    @Synchronized fun seconds(index: Int): Double = if (handle == 0L) 0.0 else FfampNative.framesSeconds(handle, index)
    @Synchronized fun read(index: Int, out: ByteArray): Boolean = handle != 0L && index in 0 until count && FfampNative.framesRead(handle, index, out)

    @Synchronized override fun close() {
        if (handle != 0L) FfampNative.framesClose(handle)
        handle = 0L
    }
}

private class FrameInput(private val frames: Frames, private val next: () -> Int) : ChunkInput(frames.bytes) {
    override fun fill(): Int {
        val index = next()
        return if (index >= 0 && frames.read(index, chunk)) chunk.size else 0
    }

    override fun close() = frames.close()
}

private class FfampVideoSource(file: File, maxSide: Int, maxPixels: Int) : VideoSource {
    override val info: VideoInfo
    private val frames: Frames
    private val fps: Double
    private val bytes: ByteArray
    private var decoded = -1
    private val exactFrames = object : LinkedHashMap<Long, VideoFrame>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VideoFrame>?) = size > 6
    }

    init {
        val probe = DeskProbe.of(file)
        val video = checkNotNull(probe.video)
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(video.width, video.height), kotlin.math.sqrt(maxPixels.toDouble() / (video.width.toDouble() * video.height)))
        val width = maxOf(2, (video.width * scale).toInt() / 2 * 2)
        val height = maxOf(2, (video.height * scale).toInt() / 2 * 2)
        fps = (video.fps.takeIf { it > 0 && it.isFinite() } ?: 30.0).coerceIn(0.001, 1000.0)
        info = VideoInfo(video.width, video.height, (probe.durationUs / 1000).coerceAtLeast(0), fps)
        frames = Frames(file, width, height, RGBA, false)
        check(frames.open)
        bytes = ByteArray(frames.bytes)
    }

    override fun frame(timeMs: Long): VideoFrame? {
        val target = (timeMs.coerceAtLeast(0) * fps / 1000.0).toLong()
        val index = frames.indexAt(target / fps)
        if (index != decoded) {
            if (!frames.read(index, bytes)) return null
            decoded = index
        }
        return VideoFrame(frames.width, frames.height, bytes)
    }

    override fun frameExact(timeUs: Long): VideoFrame? {
        exactFrames[timeUs]?.let { return it }
        val data = ByteArray(frames.bytes)
        return if (frames.read(frames.indexAt((timeUs - 1).coerceAtLeast(0) / 1_000_000.0), data)) VideoFrame(frames.width, frames.height, data).also { exactFrames[timeUs] = it } else null
    }

    override fun close() {
        exactFrames.clear()
        frames.close()
        decoded = -1
    }
}

object FfampEngine : MediaEngine {
    override fun probe(file: File, entries: String?): String = FfampNative.probe(file.path).orEmpty()

    override fun packets(file: File, stream: Int): List<MediaPacket> {
        val values = FfampNative.packets(file.path, stream) ?: return emptyList()
        return List(values.size / 3) { MediaPacket(values[it * 3], values[it * 3 + 1].toInt(), values[it * 3 + 2] != 0.0) }
    }

    override fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage? {
        val video = DeskProbe.of(file).video ?: return null
        val turned = video.rotation % 180 != 0
        val sw = if (turned) video.height else video.width
        val sh = if (turned) video.width else video.height
        if (sw <= 0 || sh <= 0) return null
        val bw = minOf(maxSide, sw)
        val bh = minOf(maxSide, sh)
        val width = minOf(Math.round(bh.toDouble() * sw / sh).toInt(), bw).coerceAtLeast(1)
        val height = minOf(Math.round(bw.toDouble() * sh / sw).toInt(), bh).coerceAtLeast(1)
        Frames(file, width, height, BGRA, true).use { frames ->
            val data = ByteArray(frames.bytes)
            if (!frames.read(frames.indexAt(timeMs / 1000.0), data)) return null
            val pixels = IntArray(width * height)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, pixels, 0, width) }
        }
    }

    override fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream =
        MediaStream(PcmInput(file, rate, channels, (beforeUs + afterUs) / 1e6, false))

    override fun playback(file: File, fromMs: Long, rate: Int): MediaStream = MediaStream(PcmInput(file, rate, 2, fromMs / 1000.0, true))

    override fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream {
        val frames = Frames(file, 0, 0, I420, false)
        val first = frames.indexAt(seconds.toDouble())
        var sent = 0
        return MediaStream(FrameInput(frames) { if (sent < count && first + sent < frames.count) first + sent++ else -1 })
    }

    override fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream {
        val frames = Frames(file, width, height, RGBA, true)
        val first = if (loop) 0 else frames.indexAt(fromMs / 1000.0)
        val origin = frames.seconds(first)
        var index = first - 1
        var began = System.nanoTime()
        fun waitFor(seconds: Double) {
            val left = began + (seconds * 1e9).toLong() - System.nanoTime()
            if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
        }
        return MediaStream(FrameInput(frames) {
            index++
            if (index >= frames.count && loop && first < frames.count) {
                waitFor(frames.length - origin)
                began = System.nanoTime()
                index = first
            }
            if (index >= frames.count) -1 else index.also { waitFor(frames.seconds(it) - origin) }
        })
    }

    override fun videoSource(file: File, maxSide: Int, maxPixels: Int): VideoSource = FfampVideoSource(file, maxSide, maxPixels)
}
