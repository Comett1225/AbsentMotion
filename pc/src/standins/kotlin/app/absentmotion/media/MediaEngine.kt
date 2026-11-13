package app.absentmotion.media

import app.absentmotion.desk.DeskOs
import app.absentmotion.export.ExportSettings
import app.absentmotion.tools.AudioJob
import app.absentmotion.tools.Plan
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoJob
import app.absentmotion.ui.camera.CameraDevices
import app.absentmotion.ui.camera.CameraFeed
import app.absentmotion.ui.camera.CameraInput
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

abstract class MediaSink : Closeable {
    abstract val output: java.io.OutputStream
    abstract fun finish(): Boolean
    abstract fun problem(): String
}

class FrameSinkSpec(val width: Int, val height: Int, val format: String, val rate: String, val start: String? = null,
    val audioFrom: File? = null, val audioStart: String? = null, val audioLength: String? = null, val limit: String? = null)

class ReverseParts(val input: File, val output: File, val folder: File, val firstUs: Long, val lastUs: Long, val fps: Double, val total: Int, val perPart: Int, val sound: Boolean)

class GifPlan(val source: File, val colour: File, val matte: File?, val width: Int, val height: Int)

interface MediaEngine {
    fun probe(file: File, entries: String? = null): String
    fun packets(file: File, stream: Int): List<MediaPacket>
    fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage?
    fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream
    fun playback(file: File, fromMs: Long, rate: Int): MediaStream
    fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream
    fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream
    fun videoSource(file: File, maxSide: Int = 1080, maxPixels: Int = Int.MAX_VALUE): VideoSource
    fun encoders(): Set<String>
    fun encoderWorks(name: String, s: ExportSettings, width: Int, height: Int): Boolean
    fun videoExport(encoder: String, s: ExportSettings, width: Int, height: Int, pcm: File?, result: File, log: File): MediaSink
    fun audioExport(s: ExportSettings, rate: Int, floats: Boolean, result: File, log: File): MediaSink
    fun frameTimes(file: File, decoded: Boolean, from: String): List<Long>
    fun compress(input: File, job: VideoJob, audio: Boolean, durationUs: Long, out: File, startUs: Long, endUs: Long, progress: (Float) -> Unit, alive: () -> Boolean)
    fun extractAudio(input: File, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean)
    fun trim(input: File, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean)
    fun reverse(parts: ReverseParts, progress: (Float) -> Unit, alive: () -> Boolean): Pair<Int, Int>?
    fun gifVideo(plan: GifPlan, alive: () -> Boolean)
    fun steadyFrames(file: File, start: String, length: String, rate: String, width: Int = 0, height: Int = 0): MediaStream
    fun frameSink(out: File, spec: FrameSinkSpec): MediaSink
    fun speech(input: File, out: File, rate: Int, tempo: Float, pitch: Float, timeoutMs: Long): Boolean
    fun cameraDevices(): CameraDevices
    fun cameraEncoder(): List<String>
    fun camera(input: CameraInput, record: File?, encoder: List<String>, onFrame: (ByteArray) -> Unit, onEnd: (Int, String) -> Unit): CameraFeed
    fun downloadOptions(plan: Plan, mode: SaveMode, dir: File, ffmpeg: String): List<String>
    fun downloaded(plan: Plan, mode: SaveMode, dir: File): File?
    fun withoutAudio(file: File, ffmpeg: String): File

    companion object {
        val current: MediaEngine = if (DeskOs.mac) FfampEngine else FfmpegEngine
    }
}
