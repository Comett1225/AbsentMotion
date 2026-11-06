package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.pc.engine.DesktopNative
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class DeskFrameReader(context: Context, uri: String) : DesktopFrameInput {
    private val file = requireNotNull(Uri.parse(uri).toFile())
    private val stream = DeskProbe.of(file).streams.first { it.type == "video" }
    private val width = stream.width
    private val height = stream.height
    override val timestamps = VideoSources.timestamps(context, uri)
    override val colorStandard = when (stream.primaries) { "bt709" -> 1; "bt2020" -> 6; else -> 0 }
    override val fullRange = stream.range == "pc"

    init {
        require(width > 0 && height > 0 && width.toLong() * height <= 64_000_000)
        DesktopNative.hashCode()
    }

    override suspend fun read(wanted: List<Long>, consume: suspend (Long, Yuv) -> Unit): Int {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val ySize = width * height
        val chroma = cw * ch
        if (wanted.isEmpty()) return 0
        val indices = wanted.map { pts -> timestamps.binarySearch(pts).let { if (it >= 0) it else -it - 1 } }
        if (indices.all { it in timestamps.indices } && indices.zipWithNext().all { (a, b) -> a <= b } && indices.last() - indices.first() <= 96) {
            val seconds = String.format(Locale.ROOT, "%.6f", (timestamps[indices.first()] - 1).coerceAtLeast(0) / 1_000_000.0)
            val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", seconds, "-noautorotate", "-i", file.path, "-map", "0:v:0",
                "-frames:v", "${indices.last() - indices.first() + 1}", "-fps_mode", "passthrough", "-pix_fmt", "yuv420p", "-f", "rawvideo", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val data = ByteArray(ySize + 2 * chroma)
                var frame = indices.first()
                var delivered = 0
                process.inputStream.use { input ->
                    while (frame <= indices.last()) {
                        coroutineContext.ensureActive()
                        var at = 0
                        while (at < data.size) {
                            val n = input.read(data, at, data.size - at)
                            if (n < 0) return delivered
                            at += n
                        }
                        while (delivered < wanted.size && indices[delivered] == frame) {
                            val y = ByteBuffer.wrap(data, 0, ySize).slice()
                            val u = ByteBuffer.wrap(data, ySize, chroma).slice()
                            val v = ByteBuffer.wrap(data, ySize + chroma, chroma).slice()
                            consume(wanted[delivered++], Yuv(y, width, 1, u, v, cw, 1, 0, 0, width, height))
                        }
                        frame++
                    }
                }
                return delivered
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        var count = 0
        for (pts in wanted) {
            coroutineContext.ensureActive()
            val seconds = String.format(Locale.ROOT, "%.6f", (pts - 1).coerceAtLeast(0) / 1_000_000.0)
            val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", seconds, "-noautorotate", "-i", file.path, "-map", "0:v:0",
                "-frames:v", "1", "-pix_fmt", "yuv420p", "-f", "rawvideo", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val data = ByteArray(ySize + 2 * chroma)
                var at = 0
                process.inputStream.use { input ->
                    while (at < data.size) {
                        val n = input.read(data, at, data.size - at)
                        if (n < 0) break
                        at += n
                    }
                }
                if (at != data.size || process.waitFor() != 0) break
                val y = ByteBuffer.wrap(data, 0, ySize).slice()
                val u = ByteBuffer.wrap(data, ySize, chroma).slice()
                val v = ByteBuffer.wrap(data, ySize + chroma, chroma).slice()
                consume(pts, Yuv(y, width, 1, u, v, cw, 1, 0, 0, width, height))
                count++
            } finally { process.destroyForcibly() }
        }
        return count
    }

    override fun close() {}
}
