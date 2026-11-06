package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun wavFits(frames: Long, channels: Int, bits: Int): Boolean = frames * channels * (bits / 8) <= 0xFFFFFFFFL - 36

internal suspend fun exportAudio(s: ExportSettings, totalUs: Long, mix: AudioMix, out: FileDescriptor, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val rate = audioRate(s)
    val channels = s.audio.channels
    val totalFrames = totalUs * rate / 1_000_000
    val deep = s.audioFormat == AudioFormat.Wav24
    if (s.audioFormat == AudioFormat.Wav16 || deep) exportCheck(wavFits(totalFrames, channels, if (deep) 24 else 16)) { txt(R.string.export_error_wav_too_long) }
    val bitrate = listOf("-b:a", "${s.audio.bitrateKbps}k")
    fun coder(name: String, label: String) = name.takeIf { it in DeskEncoders.available } ?: throw ExportError(txt(R.string.export_support_no_encoder, label))
    val encode = when (s.audioFormat) {
        AudioFormat.Wav16 -> listOf("-c:a", "pcm_s16le", "-f", "wav")
        AudioFormat.Wav24 -> listOf("-c:a", "pcm_s24le", "-f", "wav")
        AudioFormat.Flac -> listOf("-c:a", coder("flac", "FLAC"), "-compression_level", "5", "-f", "flac")
        AudioFormat.M4a -> listOf("-c:a", DeskEncoders.audio(AudioCodec.Aac, s.audio.bitrateKbps).firstOrNull() ?: coder("aac", AudioCodec.Aac.label)) + bitrate + listOf("-f", "ipod")
        AudioFormat.Ogg -> (DeskEncoders.audio(AudioCodec.Opus).firstOrNull() ?: coder("libopus", AudioCodec.Opus.label)).let { listOf("-c:a", it) + DeskEncoders.audioExtra(it) } + bitrate + listOf("-f", "ogg")
        AudioFormat.Mp3 -> listOf("-c:a", coder("libmp3lame", "MP3")) + bitrate + listOf("-f", "mp3")
    }
    val floats = deep && mix is FloatMix
    val result = exportTemp(s.audioFormat.ext)
    val log = exportTemp("log")
    val ffmpeg = Ffmpeg(listOf("-f", if (floats) "f32le" else "s16le", "-ar", "$rate", "-ac", "2", "-i", "-", "-ar", "$rate", "-ac", "$channels") + encode + listOf("-y", result.path), log)
    val watcher = launch { try { awaitCancellation() } finally { ffmpeg.kill() } }
    try {
        val stdin = ffmpeg.process.outputStream.buffered(1 shl 16)
        var frame = 0L
        try {
            while (frame < totalFrames) {
                currentCoroutineContext().ensureActive()
                val next = minOf(totalFrames, frame + rate / 2)
                val from = frame * 1_000_000 / rate
                val to = next * 1_000_000 / rate
                val wanted = ((next - frame) * 2).toInt()
                val bytes = if (floats) ByteBuffer.allocate(wanted * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put((mix as FloatMix).floats(from, to).let { f -> f.copyOf(wanted) }) }
                    else ByteBuffer.allocate(wanted * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(mix(from, to).copyOf(wanted)) }
                stdin.write(bytes.array())
                frame = next
                ensureStorage()
                progress(frame.toFloat() / totalFrames * 0.99f)
            }
            stdin.flush()
        } catch (e: java.io.IOException) {
            throw ExportError(txt(R.string.library_text_value, ffmpeg.problem()), e)
        }
        if (!runInterruptible { ffmpeg.finish() }) throw ExportError(txt(R.string.library_text_value, ffmpeg.problem()))
        copyInto(result, out)
    } finally {
        watcher.cancel()
        withContext(NonCancellable) { ffmpeg.kill(); result.delete(); log.delete() }
    }
    progress(1f)
}
