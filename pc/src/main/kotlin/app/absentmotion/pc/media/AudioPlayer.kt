package app.absentmotion.pc.media

import app.absentmotion.model.Scene
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

class AudioPlayer(scene: Scene, base: File?, repo: File?, private val startMs: Int) : AutoCloseable {
    private val mixer = AudioMixer(scene, base, repo)
    private val format = AudioFormat(AudioMixer.RATE.toFloat(), 16, 2, true, false)
    private val line: SourceDataLine
    private val thread: Thread
    @Volatile private var running = true
    @Volatile var failure: String? = null
        private set
    val timeMs: Int get() = startMs + (line.longFramePosition * 1000 / AudioMixer.RATE).toInt()

    init {
        try {
            line = AudioSystem.getSourceDataLine(format)
            line.open(format, 8192 * 4)
            line.start()
        } catch (error: Throwable) { mixer.close(); throw error }
        thread = Thread({
            try {
                var frame = startMs.toLong() * AudioMixer.RATE / 1000
                val end = (scene.totalTime + 1L) * AudioMixer.RATE / 1000
                while (running && frame < end) {
                    val count = minOf(2048L, end - frame).toInt()
                    val samples = mixer.mix(frame, count)
                    val bytes = ByteArray(samples.size * 2)
                    for (i in samples.indices) {
                        val value = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                        bytes[i * 2] = value.toByte()
                        bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    var at = 0
                    while (running && at < bytes.size) at += line.write(bytes, at, bytes.size - at)
                    frame += count
                }
            } catch (error: Throwable) { if (running) failure = error.message ?: "Audio playback failed" }
        }, "audio-player").apply { isDaemon = true; start() }
    }

    override fun close() {
        running = false
        line.stop()
        line.flush()
        line.close()
        thread.join()
        mixer.close()
    }
}
