package app.absentmotion.audio

import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.export.AudioMix
import app.absentmotion.export.ExportAudio
import app.absentmotion.model.Scene
import java.io.Closeable
import kotlin.math.min
import kotlin.math.roundToInt

internal class OfflineMix(
    private val open: (String) -> PcmSource, private val clips: List<AudioClip>, private val quality: Int,
    makeFx: FxMaker = NativeFx::chain,
) : AudioMix, Closeable {
    private val sources = HashMap<Long, OfflineSource>()
    private val voices = HashMap<Long, Voice>()
    private val rack = FxRack(RATE, makeFx)
    private val block = FloatArray(BLOCK * 2)
    private var index = -1L

    override fun invoke(startUs: Long, endUs: Long): ShortArray {
        val first = Math.round(startUs * RATE / 1e6)
        val frames = (Math.round(endUs * RATE / 1e6) - first).toInt()
        val out = ShortArray(frames * 2)
        var f = first
        while (f < first + frames) {
            val i = Math.floorDiv(f, BLOCK.toLong())
            val mixed = blockAt(i)
            val offset = (f - i * BLOCK).toInt()
            val take = min(BLOCK - offset, (first + frames - f).toInt())
            for (k in 0 until 2 * take) out[(2 * (f - first)).toInt() + k] = (mixed[2 * offset + k] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            f += take
        }
        return out
    }

    private fun blockAt(i: Long): FloatArray {
        if (i == index) return block
        if (i != index + 1) { voices.values.forEach { it.ready = false }; rack.reset() }
        mix(i * BLOCK, BLOCK)
        Voice.finish(block, block.size)
        index = i
        return block
    }

    private fun mix(first: Long, frames: Int) {
        java.util.Arrays.fill(block, 0, 2 * frames, 0f)
        val dt = 1000.0 / RATE
        val t0 = first * dt
        for (clip in clips) {
            if (clip.loop && clip.outMs.isInfinite() && t0 + frames * dt > clip.startMs && t0 <= clip.endMs) {
                clip.resolveDuration(sources.getOrPut(clip.id) { OfflineSource(clip.uri, open) }.durationUs)
            }
            val dst = rack.target(clip, block, frames)
            var rendered = false
            var next = 0
            while (true) {
                val span = clip.span(t0, frames, dt, next) ?: break
                next = span.to
                if (!span.inMedia) { voices[clip.id]?.ready = false; continue }
                val source = sources.getOrPut(clip.id) { OfflineSource(clip.uri, open) }
                val rate = source.open(span.pos)
                if (rate == 0) break
                val voice = voices.getOrPut(clip.id) { Voice() }
                val count = span.to - span.from
                val pos0 = span.pos * rate / 1000.0
                val step = span.step * rate / 1000.0
                voice.begin(pos0, step, count)
                if (voice.jump) { source.window(voice.ghostBase, voice.ghostSize, voice.ghostWindow()); voice.ghostOk = true }
                source.window(voice.base, voice.size, voice.win)
                voice.render(dst, span.from, count, pos0, step, span.g0, span.g1, quality, span.clipMs0, dt, span.lengthMs)
                rendered = true
            }
            rack.finish(clip, block, frames, rendered)
        }
        rack.sweep()
        for (clip in clips) if (t0 > clip.endMs + 300) sources.remove(clip.id)?.close()
    }

    override fun close() {
        sources.values.forEach { it.close() }
        sources.clear()
        rack.close()
    }

    companion object {
        private const val RATE = ExportAudio.SAMPLE_RATE
        private const val BLOCK = 1024

        fun of(context: Context, scene: Scene, quality: Int): AudioMix? =
            AudioClip.of(scene).takeIf { it.isNotEmpty() }?.let { clips ->
                val app = context.applicationContext
                OfflineMix({ uri -> PcmDecoder(app, uri) }, clips, quality)
            }
    }
}

internal class OfflineSource(private val uri: String, open: (String) -> PcmSource) : Closeable {
    private val decoder: PcmSource? = try { open(uri) } catch (e: Exception) { Log.w("AMAudio", "Cannot decode $uri for export", e); null }
    val durationUs: Long get() = decoder?.durationUs ?: -1
    private var rate = 0
    private var buf = ShortArray(0)
    private var start = 0L
    private var end = 0L
    private var eof = false
    private var positioned = false

    fun open(posMs: Double): Int {
        val d = decoder ?: return 0
        if (positioned) return rate
        positioned = true
        d.seek(((posMs - LayerStream.LEAD_MS).coerceAtLeast(0.0) * 1000).toLong())
        val first = d.next() ?: return 0.also { eof = true }
        rate = first.rate
        start = Math.round((posMs - LayerStream.LEAD_MS).coerceAtLeast(0.0) * rate / 1000.0)
        end = start
        append(first)
        return rate
    }

    fun window(base: Long, frames: Int, out: FloatArray) {
        val d = decoder
        if (d != null && rate > 0) {
            val from = maxOf(base, 0L)
            if (from < start || from > end + 2L * rate) {
                start = maxOf(from - 256, 0L); end = start; eof = false
                d.seek((start * 1_000_000L / rate))
            }
            while (end < base + frames && !eof) { val c = d.next(); if (c == null) eof = true else append(c) }
            val drop = minOf(from - 32768 - start, end - start)
            if (drop > 0) {
                System.arraycopy(buf, (2 * drop).toInt(), buf, 0, (2 * (end - start - drop)).toInt())
                start += drop
            }
        }
        for (i in 0 until frames) {
            val abs = base + i
            if (abs < 0 || abs < start || abs >= end) { out[2 * i] = 0f; out[2 * i + 1] = 0f; continue }
            val at = (2 * (abs - start)).toInt()
            out[2 * i] = buf[at] / 32768f
            out[2 * i + 1] = buf[at + 1] / 32768f
        }
    }

    private fun append(c: PcmChunk) {
        val a = (c.ptsUs * c.rate + 500_000L) / 1_000_000L
        var off = 0
        var n = c.frames
        if (a + n <= end) return
        if (a < end) { off = (end - a).toInt(); n -= off }
        else if (a - end > 64) { if (a - end <= 3L * rate) add(null, 0, (a - end).toInt()) else { start = a; end = a } }
        add(c.pcm, off, n)
    }

    private fun add(src: ShortArray?, off: Int, count: Int) {
        val needed = (2 * (end - start + count)).toInt()
        if (buf.size < needed) buf = buf.copyOf(maxOf(needed, buf.size * 2))
        val at = (2 * (end - start)).toInt()
        if (src == null) java.util.Arrays.fill(buf, at, at + 2 * count, 0.toShort()) else System.arraycopy(src, 2 * off, buf, at, 2 * count)
        end += count
    }

    override fun close() { decoder?.close() }
}
