package app.absentmotion.media

import app.absentmotion.desk.DeskMedia
import app.absentmotion.pc.engine.FfmpegVideoSource
import app.absentmotion.pc.engine.VideoSource
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

object FfmpegEngine : MediaEngine {
    private fun start(command: List<String>) = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    override fun probe(file: File, entries: String?): String {
        val shown = entries?.let { listOf("-show_entries", it) } ?: listOf("-show_streams", "-show_format")
        val process = start(listOf(DeskMedia.ffprobe, "-v", "error") + shown + listOf("-of", "json", file.path))
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text
    }

    override fun packets(file: File, stream: Int): List<MediaPacket> {
        val list = ArrayList<MediaPacket>()
        val process = start(listOf(DeskMedia.ffprobe, "-v", "error", "-select_streams", "$stream",
            "-show_entries", "packet=pts_time,dts_time,size,pos,flags", "-of", "compact=p=0", file.path))
        process.inputStream.bufferedReader().forEachLine { line ->
            val kv = line.split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
            val time = (kv["pts_time"]?.toDoubleOrNull() ?: kv["dts_time"]?.toDoubleOrNull() ?: return@forEachLine)
            list += MediaPacket(time, kv["size"]?.toIntOrNull() ?: 0, kv["flags"]?.startsWith("K") == true)
        }
        process.waitFor()
        return list
    }

    override fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage? {
        val process = start(listOf(DeskMedia.ffmpeg, "-v", "error", "-ss", "%.3f".format(Locale.ROOT, timeMs / 1000.0), "-i", file.path, "-frames:v", "1",
            "-vf", "scale='min($maxSide,iw)':'min($maxSide,ih)':force_original_aspect_ratio=decrease", "-f", "image2pipe", "-vcodec", "png", "-"))
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        return ImageIO.read(bytes.inputStream())
    }

    override fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream {
        val command = mutableListOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error")
        if (beforeUs > 0) command += listOf("-ss", seconds(beforeUs))
        command += listOf("-i", file.path)
        if (afterUs > 0) command += listOf("-ss", seconds(afterUs))
        command += listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-af", "aresample=async=1:first_pts=0", "-ar", "$rate", "-ac", "$channels", "-f", "f32le", "-")
        val started = start(command)
        return MediaStream(started.inputStream.buffered(1 shl 16), started, true)
    }

    override fun playback(file: File, fromMs: Long, rate: Int): MediaStream {
        val started = start(listOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", "%.3f".format(Locale.ROOT, fromMs / 1000.0), "-i", file.path,
            "-map", "0:a:0", "-vn", "-ar", "$rate", "-ac", "2", "-f", "s16le", "-"))
        return MediaStream(started.inputStream.buffered(1 shl 16), started, true)
    }

    override fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream {
        val started = start(listOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", seconds, "-noautorotate", "-i", file.path, "-map", "0:v:0", "-frames:v", "$count") +
            (if (batch) listOf("-fps_mode", "passthrough") else emptyList()) + listOf("-pix_fmt", "yuv420p", "-f", "rawvideo", "-"))
        return MediaStream(started.inputStream, started)
    }

    override fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream {
        val from = if (loop) listOf("-re", "-stream_loop", "-1") else listOf("-ss", "%.3f".format(Locale.ROOT, fromMs / 1000.0), "-re")
        val started = start(listOf(DeskMedia.ffmpeg, "-v", "error") + from + listOf("-i", file.path, "-an", "-vf", "scale=$width:$height", "-f", "rawvideo", "-pix_fmt", "rgba", "-"))
        return MediaStream(started.inputStream, started)
    }

    override fun videoSource(file: File, maxSide: Int, maxPixels: Int): VideoSource = FfmpegVideoSource(file, DeskMedia.ffmpeg, DeskMedia.ffprobe, maxSide, maxPixels)

    private fun seconds(us: Long) = "%.6f".format(Locale.ROOT, us / 1e6)
}
