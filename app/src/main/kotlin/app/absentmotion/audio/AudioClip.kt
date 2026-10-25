package app.absentmotion.audio

import app.absentmotion.model.finiteFloat
import app.absentmotion.model.finiteDouble
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.loopStart
import app.absentmotion.model.valueAt
import kotlin.math.ceil
import kotlin.math.abs
import app.absentmotion.model.LayerTrack
import app.absentmotion.model.layerTracks

val GainRef = PropRef(PropRef.Kind.Child, "gain")

const val MAX_GAIN = 1.5f

val Layer.gain: Float get() = element(GainRef)?.valueAt(t)?.finiteFloat() ?: 1f

val XmlNode.mutes: Boolean get() = !isKeyframed && (this["value"]?.finiteFloat() ?: 1f) <= 0f

val Layer.soundUri: String? get() = if (kind == "audio") node["src"] else mediaFill?.takeIf { it.video }?.uri

internal class AudioClip(layer: Layer, val uri: String, private val track: LayerTrack? = null, durationMs: Double = Double.POSITIVE_INFINITY) {
    val id = layer.id
    val startMs = track?.startMs ?: layer.startTime
    val endMs = track?.endMs ?: (layer.endTime + 1)
    val inMs = layer.loopStart?.toDouble() ?: layer.node["inTime"]?.finiteDouble() ?: 0.0
    private val declaredOutMs = layer.node["outTime"]?.finiteDouble()
    @Volatile private var sourceEndMs = durationMs
    val outMs: Double get() = declaredOutMs ?: sourceEndMs

    fun resolveDuration(durationUs: Long) { if (durationUs > 0) sourceEndMs = durationUs / 1000.0 }
    val loop = layer.node["loop"] == "true"
    val fx = layer.audioEffects.spec()
    private val timing = VideoTiming(layer)
    private val gainLayer = layer
    private val length = (layer.endTime - layer.startTime).coerceAtLeast(1).toFloat()

    fun gainAt(projectMs: Double): Float =
        gainLayer.copy(t = (((track?.timeAt(projectMs, false, false)?.ms ?: projectMs) - gainLayer.startTime) / length).toFloat()).gain.coerceIn(0f, MAX_GAIN)

    fun mediaAt(projectMs: Double): Double = timing.mediaMs((track?.timeAt(projectMs, false, false)?.ms ?: projectMs) - gainLayer.startTime)

    fun cue(t0: Double): Pair<Double, Boolean> {
        val at = maxOf(t0, startMs.toDouble())
        val local = (track?.timeAt(at, false, false)?.ms ?: at) - gainLayer.startTime
        return wrap(timing.mediaMs(maxOf(local, 0.0))) to (t0 < startMs || local < 0)
    }

    fun wrap(media: Double): Double {
        if (!loop || outMs.isInfinite()) return media
        val span = (outMs - inMs).coerceAtLeast(1.0)
        return inMs + ((media - inMs) % span + span) % span
    }

    fun span(t0: Double, n: Int, dt: Double, after: Int = 0): Span? {
        if (track != null) return nestedSpan(t0, n, dt, after)
        val from = ceil((startMs - t0) / dt).toInt().coerceIn(after, n)
        var to = ceil((endMs - t0) / dt).toInt().coerceIn(0, n)
        if (to <= from) return null
        val a = t0 + from * dt
        val first = mediaAt(a)
        val pos = wrap(first)
        val step = (mediaAt(t0 + to * dt) - first) / (to - from)
        val mark = when {
            step == 0.0 -> Double.NaN
            pos >= inMs && pos < outMs -> if (step > 0) outMs else inMs
            pos < inMs && step > 0 -> inMs
            pos >= outMs && step < 0 -> outMs
            else -> Double.NaN
        }
        val samples = ceil((mark - pos) / step).coerceAtLeast(1.0)
        if (samples < to - from) to = from + samples.toInt()
        val b = t0 + to * dt
        return Span(from, to, pos, step, gainAt(a), gainAt(b), a - startMs, (endMs - startMs).toDouble(),
            inMedia = pos >= inMs && pos < outMs)
    }

    private fun nestedSpan(t0: Double, n: Int, dt: Double, after: Int): Span? {
        val source = track ?: return null
        var from = after
        while (from < n && source.timeAt(t0 + from * dt, true, false) == null) from++
        if (from >= n) return null
        val at = t0 + from * dt
        val local = source.timeAt(at, true, false)!!
        val raw = timing.mediaMs(local.ms - gainLayer.startTime)
        val pos = wrap(raw)
        val step = timing.speed(local.ms - gainLayer.startTime) * local.rate * dt
        val inMedia = pos >= inMs && pos < outMs && local.rate > 0
        var to = from + 1
        while (to < n) {
            val next = source.timeAt(t0 + to * dt, true, false) ?: break
            val media = wrap(timing.mediaMs(next.ms - gainLayer.startTime))
            if ((media >= inMs && media < outMs && next.rate > 0) != inMedia ||
                abs(media - (pos + step * (to - from))) > maxOf(0.01, abs(step) * 0.05)) break
            to++
        }
        return Span(from, to, pos, step, gainAt(at), gainAt(t0 + (to - 1) * dt),
            local.ms - gainLayer.startTime, length.toDouble(), inMedia)
    }

    class Span(
        val from: Int, val to: Int, val pos: Double, val step: Double, val g0: Float, val g1: Float,
        val clipMs0: Double, val lengthMs: Double,
        val inMedia: Boolean,
    )

    companion object {
        fun of(scene: Scene): List<AudioClip> = scene.layerTracks().mapNotNull { track ->
            val layer = track.layer
            val uri = layer.soundUri
            val media = uri?.let { scene.media(it) ?: track.scene.media(it) }
            if (uri == null || layer.hidden || layer.element(GainRef)?.mutes == true || media?.node?.get("audio") == "none") null
            else AudioClip(layer, uri, track.takeIf { it.parents.isNotEmpty() }, media?.duration?.takeIf { it > 0 }?.div(1000.0) ?: Double.POSITIVE_INFINITY)
        }
    }
}
