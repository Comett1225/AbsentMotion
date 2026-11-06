package app.absentmotion.pc.engine

import org.json.JSONObject
import java.io.File
import java.io.InputStream

interface VideoSource : AutoCloseable {
    val info: VideoInfo
    fun frame(timeMs: Long): VideoFrame?
}

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long, val fps: Double)

data class VideoFrame(val width: Int, val height: Int, val rgba: ByteArray)

class NativeVideoSource(file: File) : AutoCloseable {
    private var video = DesktopNative.videoOpen(file.absolutePath).also { require(it != 0L) { "native decoder cannot open ${file.name}" } }
    private val values = DesktopNative.videoInfo(video)
    val info = VideoInfo(values[0].toInt(), values[1].toInt(), values[2].toLong() / 1000, values[3])
    val orientation = values[4].toInt()
    val hasAudio = values[5] != 0.0
    val hardware = values[6] != 0.0

    fun show(handle: Long, uri: String, timeUs: Long, exact: Boolean): Int = if (video == 0L) -1 else DesktopNative.videoFrame(handle, video, uri, timeUs, exact)

    fun showLayer(handle: Long, uri: String, layer: Long, timeUs: Long, exact: Boolean, waitMs: Int = if (exact) 15000 else 30): Int = if (video == 0L) -1 else DesktopNative.videoFrameLayer(handle, video, uri, layer, timeUs, waitMs)

    override fun close() {
        if (video != 0L) DesktopNative.videoClose(video)
        video = 0L
    }
}

class FfmpegVideoSource(private val file: File, private val ffmpeg: String, ffprobe: String, maxSide: Int = 1080, maxPixels: Int = Int.MAX_VALUE) : VideoSource {
    override val info: VideoInfo
    private val width: Int
    private val height: Int
    private val fps: Double
    private var process: Process? = null
    private var stream: InputStream? = null
    private var decodedFrame = -1L
    private var bytes: ByteArray
    private val exactFrames = object : LinkedHashMap<Long, VideoFrame>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VideoFrame>?) = size > 2
    }

    init {
        val probe = ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,avg_frame_rate:format=duration", "-of", "json", file.absolutePath).start()
        val json = JSONObject(probe.inputStream.bufferedReader().readText())
        check(probe.waitFor() == 0)
        val video = json.getJSONArray("streams").getJSONObject(0)
        val originalWidth = video.getInt("width")
        val originalHeight = video.getInt("height")
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(originalWidth, originalHeight), kotlin.math.sqrt(maxPixels.toDouble() / (originalWidth.toDouble() * originalHeight)))
        width = maxOf(2, (originalWidth * scale).toInt() / 2 * 2)
        height = maxOf(2, (originalHeight * scale).toInt() / 2 * 2)
        val rate = video.optString("avg_frame_rate", "30/1").split('/')
        fps = ((rate.getOrNull(0)?.toDoubleOrNull() ?: 30.0) / (rate.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0)).coerceIn(0.001, 1000.0)
        val duration = (json.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()?.times(1000) ?: 0.0).toLong()
        info = VideoInfo(originalWidth, originalHeight, duration, fps)
        bytes = ByteArray(width * height * 4)
    }

    override fun frame(timeMs: Long): VideoFrame? {
        val target = (timeMs.coerceAtLeast(0) * fps / 1000.0).toLong()
        if (target == decodedFrame) return VideoFrame(width, height, bytes)
        if (stream == null || target < decodedFrame || target > decodedFrame + 3) {
            close()
            process = ProcessBuilder(ffmpeg, "-v", "error", "-ss", "${target / fps}", "-noautorotate", "-i", file.absolutePath, "-an", "-vf", "scale=$width:$height", "-pix_fmt", "rgba", "-f", "rawvideo", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            stream = process!!.inputStream
            decodedFrame = target - 1
        }
        val input = stream ?: return null
        while (decodedFrame < target) {
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) return null
                read += n
            }
            decodedFrame++
        }
        return VideoFrame(width, height, bytes)
    }

    fun frameExact(timeUs: Long): VideoFrame? {
        exactFrames[timeUs]?.let { return it }
        val seconds = String.format(java.util.Locale.ROOT, "%.6f", (timeUs - 1).coerceAtLeast(0) / 1_000_000.0)
        val process = ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-ss", seconds, "-noautorotate", "-i", file.absolutePath,
            "-frames:v", "1", "-an", "-vf", "scale=$width:$height", "-pix_fmt", "rgba", "-f", "rawvideo", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val data = process.inputStream.use { it.readNBytes(width * height * 4) }
            return if (data.size == width * height * 4 && process.waitFor() == 0) VideoFrame(width, height, data).also { exactFrames[timeUs] = it } else null
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    override fun close() {
        exactFrames.clear()
        stream?.close()
        stream = null
        process?.destroyForcibly()
        process = null
        decodedFrame = -1L
    }
}
