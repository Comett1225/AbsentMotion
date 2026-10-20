package app.absentmotion.audio

import java.io.Closeable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

internal interface ClipFx : Closeable {
    val latency: Int get() = 0
    fun fits(spec: FxSpec): Boolean
    fun set(values: FloatArray)
    fun process(buf: FloatArray, frames: Int)
    fun reset()
}

internal typealias FxMaker = (FxSpec, Int) -> ClipFx?

internal object NativeFx {
    const val LIMITER = 7
    const val MAX_BLOCK = 1024

    @Volatile private var loaded = runCatching { System.loadLibrary("amengine") }.isSuccess

    val available: Boolean get() = loaded || runCatching { nativeLatency(0L) }.isSuccess.also { loaded = it }

    fun chain(spec: FxSpec, rate: Int): ClipFx? {
        if (!available) return null
        val handle = nativeCreate(spec.kinds, rate, MAX_BLOCK)
        return if (handle == 0L) null else NativeChain(spec.kinds.copyOf(), handle)
    }

    fun limiter(rate: Int): ClipFx? {
        val spec = FxSpec(intArrayOf(LIMITER), floatArrayOf(1f, 0f, 3f, CEILING_DB, 100f))
        return chain(spec, rate)?.also { it.set(spec.values) }
    }

    @JvmStatic external fun nativeCreate(kinds: IntArray, rate: Int, maxBlock: Int): Long
    @JvmStatic external fun nativeSet(handle: Long, values: FloatArray): Boolean
    @JvmStatic external fun nativeProcess(handle: Long, data: FloatArray, frames: Int)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeLatency(handle: Long): Int
    @JvmStatic external fun nativeDestroy(handle: Long)
}

private class NativeChain(private val kinds: IntArray, private var handle: Long) : ClipFx {
    override val latency: Int get() = NativeFx.nativeLatency(handle)
    override fun fits(spec: FxSpec) = spec.kinds contentEquals kinds
    override fun set(values: FloatArray) { NativeFx.nativeSet(handle, values) }
    override fun process(buf: FloatArray, frames: Int) = NativeFx.nativeProcess(handle, buf, frames)
    override fun reset() = NativeFx.nativeReset(handle)
    override fun close() {
        if (handle != 0L) NativeFx.nativeDestroy(handle)
        handle = 0L
    }
}

internal const val CEILING_DB = -1f

internal class SafeLimiter(rate: Int, releaseMs: Double = 100.0) : ClipFx {
    private val ceiling = 10.0.pow(CEILING_DB / 20.0).toFloat()
    private val release = exp(-1.0 / (releaseMs * 0.001 * rate)).toFloat()
    private var gain = 1f

    var peak = 0f
        private set

    override fun fits(spec: FxSpec) = false
    override fun set(values: FloatArray) {}

    override fun process(buf: FloatArray, frames: Int) {
        var top = 0f
        for (k in 0 until frames) {
            val l = buf[2 * k]
            val r = buf[2 * k + 1]
            val p = maxOf(abs(l), abs(r))
            if (p > top) top = p
            val want = if (p > ceiling) ceiling / p else 1f
            gain = if (want < gain) want else want + (gain - want) * release
            buf[2 * k] = (l * gain).coerceIn(-ceiling, ceiling)
            buf[2 * k + 1] = (r * gain).coerceIn(-ceiling, ceiling)
        }
        peak = top
    }

    override fun reset() { gain = 1f }
    override fun close() {}
}

internal class FxRack(private val rate: Int, private val make: FxMaker = NativeFx::chain) : Closeable {
    private class Slot(val fx: ClipFx?) {
        var values: FloatArray? = null
        var tail = 0
        var quiet = 0
        var seen = 0
    }

    private val slots = HashMap<Long, Slot>()
    private var scratch = FloatArray(2 * NativeFx.MAX_BLOCK)
    private var generation = 0
    private val tailFrames = rate * TAIL_S
    private val fadeFrames = rate * FADE_S
    private val quietFrames = rate * QUIET_S

    fun target(clip: AudioClip, out: FloatArray, frames: Int): FloatArray {
        if (clip.fx == null) return out
        if (scratch.size < 2 * frames) scratch = FloatArray(2 * frames)
        java.util.Arrays.fill(scratch, 0, 2 * frames, 0f)
        return scratch
    }

    fun finish(clip: AudioClip, out: FloatArray, frames: Int, rendered: Boolean) {
        val spec = clip.fx
        var slot = slots[clip.id]
        if (slot != null && (spec == null || slot.fx?.fits(spec) == false)) {
            slot.fx?.close()
            slots.remove(clip.id)
            slot = null
        }
        if (spec == null) return
        if (slot == null) {
            if (!rendered) return
            slot = Slot(make(spec, rate)).also { slots[clip.id] = it }
        } else if (slot.tail <= 0) {
            if (!rendered) { slot.seen = generation; return }
            slot.fx?.reset()
        }
        slot.seen = generation
        val fx = slot.fx
        if (fx != null) {
            if (slot.values !== spec.values) { fx.set(spec.values); slot.values = spec.values }
            fx.process(scratch, frames)
        }
        if (rendered) {
            slot.tail = tailFrames
            slot.quiet = 0
        } else {
            val left = slot.tail
            var peak = 0f
            for (k in 0 until frames) {
                val fade = if (left - k >= fadeFrames) 1f else ((left - k).toFloat() / fadeFrames).coerceAtLeast(0f)
                scratch[2 * k] *= fade
                scratch[2 * k + 1] *= fade
                peak = maxOf(peak, abs(scratch[2 * k]), abs(scratch[2 * k + 1]))
            }
            slot.tail = left - frames
            slot.quiet = if (peak < QUIET_LEVEL) slot.quiet + frames else 0
            if (slot.quiet >= quietFrames) slot.tail = 0
        }
        for (i in 0 until 2 * frames) out[i] += scratch[i]
    }

    fun sweep() {
        val it = slots.values.iterator()
        while (it.hasNext()) {
            val slot = it.next()
            if (slot.seen != generation) { slot.fx?.close(); it.remove() }
        }
        generation++
    }

    fun reset() {
        for (slot in slots.values) {
            slot.fx?.reset()
            slot.tail = 0
            slot.quiet = 0
        }
    }

    override fun close() {
        slots.values.forEach { it.fx?.close() }
        slots.clear()
    }

    private companion object {
        const val TAIL_S = 10
        const val FADE_S = 1
        const val QUIET_S = 3
        const val QUIET_LEVEL = 1e-5f
    }
}
