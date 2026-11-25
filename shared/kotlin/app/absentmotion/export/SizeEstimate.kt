package app.absentmotion.export

import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import java.io.File
import java.io.RandomAccessFile

internal const val SizeSampleWindows = 5
internal const val SizeSampleMostWindows = 12
internal const val SizeSamplePixels = 640_000_000L

internal fun videoSizeSample(frames: Int, fps: Int, framePixels: Long = 0): IntArray? {
    val window = fps.coerceAtLeast(1)
    val gops = frames / window
    val most = (SizeSamplePixels / (2L * window * framePixels).coerceAtLeast(1)).toInt().coerceIn(SizeSampleWindows, SizeSampleMostWindows)
    val windows = (gops / 3).coerceIn(SizeSampleWindows, most)
    if (frames <= 2 * windows * window) return null
    val picked = (0 until windows).map { i -> ((i + 0.5) * gops / windows).toInt().coerceIn(0, gops - 1) }.distinct()
    val once = picked.flatMap { g -> (g * window until (g + 1) * window) }
    return (once + once).toIntArray()
}

internal fun videoBytesFromSample(sampledBytes: Long, sampledFrames: Int, frames: Int): Long =
    if (sampledFrames <= 0 || sampledFrames >= frames) sampledBytes else (sampledBytes.toDouble() * frames / sampledFrames).toLong()

internal fun audioTrackBytes(scene: Scene, s: ExportSettings, hasAudio: Boolean): Long =
    if (!hasAudio) 0L else (s.audio.bitrateKbps * 1000L / 8.0 * (scene.totalTime + 1) / 1000.0).toLong()

internal suspend fun measureVideoBytes(engine: EngineThread, scene: Scene, s: ExportSettings, cacheDir: File, hasAudio: Boolean = false): Long {
    val f = s.fixedFormat().let { if (it.interpolation == FrameInterpolation.Ai) it.copy(interpolation = FrameInterpolation.Flow) else it }
    val frames = frameCount(scene, f.fps)
    val (width, height) = exportSize(scene, f.shortSide, even = true)
    val sample = videoSizeSample(frames, f.fps, width.toLong() * height)
    val file = File(cacheDir, "size-sample-${System.nanoTime()}.${f.container.ext}")
    val video = try {
        RandomAccessFile(file, "rw").use { exportVideo(engine, scene, scene.toXml(), f, it.fd, null, sample = sample) {} }
        videoBytesFromSample(file.length(), sample?.size ?: frames, frames)
    } finally {
        file.delete()
    }
    if (!hasAudio) return video
    val sound = f.copy(audioFormat = if (f.audio.codec == AudioCodec.Opus) AudioFormat.Ogg else AudioFormat.M4a)
    return video + try { measureAudioBytes(sound, (scene.totalTime + 1) * 1000L, { ExportAudio.mixFor?.invoke(scene, f.audio.effectiveRate) }, cacheDir) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { audioTrackBytes(scene, f, true) }
}

internal const val AudioSampleWindows = 6
internal const val AudioWholeSeconds = 12
internal const val AudioHeaderUs = 20_000L

internal fun audioSampleStarts(totalUs: Long): LongArray? {
    val seconds = totalUs / 1_000_000
    if (seconds <= AudioWholeSeconds) return null
    return LongArray(AudioSampleWindows) { i -> ((i + 0.5) * seconds / AudioSampleWindows).toLong().coerceAtMost(seconds - 1) * 1_000_000 }
}

internal fun windowedMix(mix: AudioMix, starts: LongArray, windowUs: Long = 1_000_000): AudioMix = { from, to ->
    val parts = ArrayList<ShortArray>()
    var at = from
    while (at < to) {
        val w = (at / windowUs).toInt().coerceAtMost(starts.size - 1)
        val end = if (w == starts.size - 1) to else minOf(to, (w + 1) * windowUs)
        parts += mix(starts[w] + at - w * windowUs, starts[w] + end - w * windowUs)
        at = end
    }
    if (parts.size == 1) parts[0] else ShortArray(parts.sumOf { it.size }).also { joined -> var n = 0; parts.forEach { it.copyInto(joined, n); n += it.size } }
}

internal fun wavBytes(s: ExportSettings, totalUs: Long): Long =
    44L + totalUs * audioRate(s) / 1_000_000 * s.audio.channels * if (s.audioFormat == AudioFormat.Wav24) 3 else 2

internal suspend fun measureAudioBytes(s: ExportSettings, totalUs: Long, mix: () -> AudioMix?, cacheDir: File): Long {
    if (s.audioFormat == AudioFormat.Wav16 || s.audioFormat == AudioFormat.Wav24) return wavBytes(s, totalUs)
    val sound = mix() ?: error("No sound to sample")
    try {
        suspend fun encoded(us: Long, from: AudioMix): Long {
            val file = File(cacheDir, "size-sample-${System.nanoTime()}.${s.audioFormat.ext}")
            try {
                RandomAccessFile(file, "rw").use { exportAudio(s, us, from, it.fd) {} }
                return file.length()
            } finally {
                file.delete()
            }
        }
        val starts = audioSampleStarts(totalUs) ?: return encoded(totalUs, sound)
        val sampleUs = starts.size * 1_000_000L
        val sampled = encoded(sampleUs, windowedMix(sound, starts))
        val fixed = encoded(AudioHeaderUs, sound).coerceAtMost(sampled)
        return fixed + ((sampled - fixed).toDouble() * totalUs / sampleUs).toLong()
    } finally {
        (sound as? java.io.Closeable)?.close()
    }
}
