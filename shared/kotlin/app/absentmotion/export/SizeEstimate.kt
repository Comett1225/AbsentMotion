package app.absentmotion.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal const val SizeSampleWindows = 5
internal const val SizeSampleMostWindows = 12
internal const val SizeSamplePixels = 640_000_000L
internal const val SizeSampleStartFrames = 240

internal fun sizeSampleStart(fps: Int): Int = fps.coerceAtLeast(1).let { window -> (SizeSampleStartFrames + window - 1) / window * window }

internal fun videoSizeSample(frames: Int, fps: Int, framePixels: Long = 0): IntArray? {
    val window = fps.coerceAtLeast(1)
    val start = sizeSampleStart(window)
    val gops = frames / window
    val most = (SizeSamplePixels / (2L * window * framePixels).coerceAtLeast(1)).toInt().coerceIn(SizeSampleWindows, SizeSampleMostWindows)
    val windows = (gops / 3).coerceIn(SizeSampleWindows, most)
    if (frames <= start + windows * window) return null
    val first = start / window
    val later = gops - first
    val picked = (0 until windows).map { i -> first + ((i + 0.5) * later / windows).toInt() }.distinct()
    return (List(start) { it } + picked.flatMap { g -> (g * window until (g + 1) * window) }).toIntArray()
}

internal fun videoBytesFromSample(sampledBytes: Long, spreadBytes: Long, spreadFrames: Int, restFrames: Int): Long =
    sampledBytes - spreadBytes + (spreadBytes.toDouble() * restFrames / spreadFrames).toLong()

internal fun videoBytesFrom(file: File, fromUs: Long): Long {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        extractor.selectTrack(0)
        val buffer by lazy { ByteBuffer.allocate(extractor.getTrackFormat(0).run { if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 24 }) }
        val packets = ArrayList<Pair<Long, Long>>()
        while (extractor.sampleTime >= 0) {
            packets += extractor.sampleTime to if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else extractor.readSampleData(buffer, 0).toLong()
            extractor.advance()
        }
        val first = packets.minOf { it.first }
        return packets.filter { it.first - first >= fromUs }.sumOf { it.second }
    } finally {
        extractor.release()
    }
}

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
        if (sample == null) file.length() else {
            val start = sizeSampleStart(f.fps)
            videoBytesFromSample(file.length(), videoBytesFrom(file, ((start - 0.5) * 1_000_000 / f.fps).toLong()), sample.size - start, frames - start)
        }
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
