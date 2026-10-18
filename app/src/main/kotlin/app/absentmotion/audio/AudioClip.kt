package app.absentmotion.audio

import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.valueAt
import kotlin.math.ceil

val GainRef = PropRef(PropRef.Kind.Child, "gain")

const val MAX_GAIN = 1.5f

val Layer.gain: Float get() = element(GainRef)?.valueAt(t)?.toFloatOrNull() ?: 1f

val Layer.soundUri: String? get() = if (kind == "audio") node["src"] else mediaFill?.takeIf { it.video }?.uri

internal class AudioClip(layer: Layer, val uri: String) {
    val id = layer.id
    val startMs = layer.startTime
    val endMs = layer.endTime + 1
    val inMs = layer.node["inTime"]?.toDoubleOrNull() ?: 0.0
    val outMs = layer.node["outTime"]?.toDoubleOrNull() ?: Double.MAX_VALUE
    val loop = layer.node["loop"] == "true"
    private val timing = VideoTiming(layer)
    private val gainLayer = layer
    private val length = (layer.endTime - layer.startTime).coerceAtLeast(1).toFloat()

    fun gainAt(projectMs: Double): Float =
        gainLayer.copy(t = ((projectMs - startMs) / length).toFloat()).gain.coerceIn(0f, MAX_GAIN)

    fun mediaAt(projectMs: Double): Double = timing.mediaMs(projectMs - startMs)

    fun wrap(media: Double): Double {
        if (!loop) return media
        val span = (outMs - inMs).coerceAtLeast(1.0)
        return inMs + ((media - inMs) % span + span) % span
    }

    fun span(t0: Double, n: Int, dt: Double, after: Int = 0): Span? {
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

    class Span(
        val from: Int, val to: Int, val pos: Double, val step: Double, val g0: Float, val g1: Float,
        val clipMs0: Double, val lengthMs: Double,
        val inMedia: Boolean,
    )

    companion object {
        fun of(scene: Scene): List<AudioClip> = scene.layers.mapNotNull { layer ->
            val uri = layer.soundUri
            val gain = layer.element(GainRef)
            val silent = gain != null && !gain.isKeyframed && (gain["value"]?.toFloatOrNull() ?: 1f) <= 0f
            if (uri == null || layer.hidden || silent) null else AudioClip(layer, uri)
        }
    }
}
