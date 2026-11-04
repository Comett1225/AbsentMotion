package android.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.absentmotion.audio.DeskOutput
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskProbe
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

open class MediaPlayer {
    fun interface OnPreparedListener { fun onPrepared(mp: MediaPlayer) }
    fun interface OnCompletionListener { fun onCompletion(mp: MediaPlayer) }
    fun interface OnErrorListener { fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean }

    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null
    private var prepared: OnPreparedListener? = null
    private var completed: OnCompletionListener? = null
    private var failed: OnErrorListener? = null
    @Volatile private var probe: DeskProbe? = null
    @Volatile private var released = false
    @Volatile private var runner: Runner? = null
    @Volatile private var positionMs = 0L
    @Volatile private var warm: Playback? = null

    var isLooping = false

    val isPlaying: Boolean get() = runner?.done == false
    val duration: Int get() = probe?.durationUs?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: -1
    val currentPosition: Int get() = (runner?.positionMs() ?: positionMs).toInt()
    val videoWidth: Int get() = probe?.streams?.firstOrNull { it.type == "video" }?.width ?: 0
    val videoHeight: Int get() = probe?.streams?.firstOrNull { it.type == "video" }?.height ?: 0

    fun setDataSource(context: Context?, uri: Uri) { file = uri.toFile() ?: throw java.io.IOException("Cannot open $uri") }
    fun setDataSource(path: String) { file = Uri.parse(path).toFile() ?: File(path) }
    fun setOnPreparedListener(listener: OnPreparedListener?) { prepared = listener }
    fun setOnCompletionListener(listener: OnCompletionListener?) { completed = listener }
    fun setOnErrorListener(listener: OnErrorListener?) { failed = listener }
    fun setSurface(surface: android.view.Surface?) {}
    fun setVolume(left: Float, right: Float) {}

    fun prepare() { probe = DeskProbe.of(file ?: throw IllegalStateException("No data source")).takeIf { it.streams.isNotEmpty() } ?: throw java.io.IOException("Unreadable media") }

    fun prepareAsync() {
        val source = file ?: throw IllegalStateException("No data source")
        Thread({
            val found = runCatching { DeskProbe.of(source) }.getOrNull()?.takeIf { it.streams.isNotEmpty() }
            val ready = if (DeskOs.windows && found?.audio != null) runCatching { Playback.open(source, 0) }.getOrNull() else null
            main.post {
                if (released) { ready?.close(); return@post }
                if (found == null) { if (failed?.onError(this, MEDIA_ERROR_UNKNOWN, 0) != true) completed?.onCompletion(this) }
                else { probe = found; warm?.close(); warm = ready; prepared?.onPrepared(this) }
            }
        }, "am-media-player").apply { isDaemon = true }.start()
    }

    fun start() {
        val p = probe ?: throw IllegalStateException("Not prepared")
        if (isPlaying) return
        if (duration in 0..positionMs) positionMs = 0
        val ready = warm?.also { warm = null }?.takeIf { positionMs == 0L || run { it.close(); false } }
        runner = Runner(file!!, p, positionMs, ready).also { it.start() }
    }

    fun pause() { positionMs = halt() }
    fun stop() { positionMs = halt() }

    fun seekTo(ms: Int) = seekTo(ms.toLong(), SEEK_PREVIOUS_SYNC)

    fun seekTo(ms: Long, mode: Int) {
        val playing = isPlaying
        halt()
        positionMs = ms.coerceAtLeast(0)
        if (playing) start()
    }

    fun reset() { halt(); warm?.close(); warm = null; positionMs = 0; probe = null; file = null }

    fun release() {
        released = true
        halt()
        warm?.close()
        warm = null
    }

    private fun halt(): Long {
        val r = runner ?: return positionMs
        runner = null
        return r.finish()
    }

    private fun finished(r: Runner) = main.post {
        if (runner !== r || released) return@post
        runner = null
        positionMs = 0
        if (isLooping) start() else completed?.onCompletion(this)
    }

    private class Playback(val process: Process, val line: SourceDataLine) {
        fun close() { process.destroy(); runCatching { line.close() } }

        companion object {
            fun open(source: File, fromMs: Long): Playback {
                val rate = DeskOutput.nativeRate
                val command = listOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, fromMs / 1000.0), "-i", source.path,
                    "-map", "0:a:0", "-vn", "-ar", "$rate", "-ac", "2", "-f", "s16le", "-")
                val started = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
                val line = runCatching { AudioSystem.getSourceDataLine(format).apply { open(format, rate * 100 / 1000 * 4) } }.getOrElse { started.destroy(); throw it }
                return Playback(started, line)
            }
        }
    }

    private inner class Runner(private val source: File, private val probe: DeskProbe, private val fromMs: Long, private val ready: Playback? = null) : Thread("am-media-player") {
        @Volatile var done = false
        @Volatile private var line: SourceDataLine? = null
        @Volatile private var process: Process? = null
        @Volatile private var began = 0L
        private val rate = DeskOutput.nativeRate

        init { isDaemon = true }

        fun positionMs(): Long {
            val l = line
            return if (l != null) fromMs + l.longFramePosition * 1000 / rate else if (began > 0) fromMs + (System.nanoTime() - began) / 1_000_000 else fromMs
        }

        fun finish(): Long {
            val at = positionMs()
            done = true
            process?.destroy()
            interrupt()
            return at
        }

        override fun run() {
            try {
                began = System.nanoTime()
                if (probe.audio == null) {
                    ready?.close()
                    while (!done && positionMs() < probe.durationUs / 1000) sleep(10)
                } else play()
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
            } finally {
                runCatching { line?.close() }
                process?.destroy()
                if (!done) { done = true; finished(this) }
            }
        }

        private fun play() {
            val playback = ready ?: Playback.open(source, fromMs)
            val started = playback.process
            process = started
            val input = started.inputStream.buffered(1 shl 16)
            val out = playback.line
            line = out
            val buf = ByteArray(4096)
            while (!done) {
                val n = input.readNBytes(buf, 0, buf.size)
                if (n <= 0) break
                if (DeskOutput.capture != null) buf.fill(0, 0, n)
                out.write(buf, 0, n - n % 4)
                if (!out.isRunning) out.start()
            }
            if (!done) out.drain()
        }
    }

    companion object {
        const val SEEK_PREVIOUS_SYNC = 0
        const val SEEK_NEXT_SYNC = 1
        const val SEEK_CLOSEST_SYNC = 2
        const val SEEK_CLOSEST = 3
        const val MEDIA_ERROR_UNKNOWN = 1
    }
}
