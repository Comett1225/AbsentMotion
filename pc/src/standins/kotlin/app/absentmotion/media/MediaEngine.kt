package app.absentmotion.media

import app.absentmotion.desk.DeskOs
import app.absentmotion.pc.engine.VideoSource
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.InputStream

class MediaPacket(val seconds: Double, val size: Int, val sync: Boolean)

class MediaStream(val input: InputStream, private val process: Process? = null, private val gently: Boolean = false) : Closeable {
    fun finished(): Boolean = process == null || process.waitFor() == 0

    override fun close() {
        runCatching { input.close() }
        if (gently) process?.destroy() else process?.destroyForcibly()
    }
}

interface MediaEngine {
    fun probe(file: File, entries: String? = null): String
    fun packets(file: File, stream: Int): List<MediaPacket>
    fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage?
    fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream
    fun playback(file: File, fromMs: Long, rate: Int): MediaStream
    fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream
    fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream
    fun videoSource(file: File, maxSide: Int = 1080, maxPixels: Int = Int.MAX_VALUE): VideoSource

    companion object {
        val current: MediaEngine = if (DeskOs.mac) FfampEngine else FfmpegEngine
    }
}
