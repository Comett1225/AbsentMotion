package app.absentmotion.model

import app.absentmotion.engine.NativeEngine
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

data class CurvePoint(val u: Float, val speed: Float, val ease: String? = RAMP_EASE)

const val CURVE_SLOWEST = 0.1f
const val CURVE_FASTEST = 10f
const val CURVE_GAP = 0.04f
const val CURVE_MOST_POINTS = 10

private const val EDGE = 0.01
private const val MATCH = 0.01f

val SpeedTicks: List<Float> = listOf(200 to 5, 500 to 10, 1000 to 20, 2000 to 50, 5000 to 100, 10000 to 200)
    .fold(mutableListOf<Int>()) { ticks, (end, step) ->
        var next = (ticks.lastOrNull() ?: 0) + step
        while (next <= end) { ticks += next; next += step }
        ticks
    }.map { it / 100f }

fun rulerTick(speed: Float): Int = SpeedTicks.indices.minBy { abs(SpeedTicks[it] - speed) } + 1

fun tickSpeed(tick: Int): Float = SpeedTicks[(tick - 1).coerceIn(SpeedTicks.indices)]

fun steppedSpeed(value: Float): Float = ((value * 20).roundToInt() / 20f).coerceIn(SLOWEST_SPEED, FASTEST_SPEED)

fun curveHeight(speed: Float): Float = ln(speed.coerceIn(CURVE_SLOWEST, CURVE_FASTEST) / CURVE_SLOWEST) / ln(CURVE_FASTEST / CURVE_SLOWEST)

fun curveSpeed(height: Float): Float = CURVE_SLOWEST * (CURVE_FASTEST / CURVE_SLOWEST).pow(height.coerceIn(0f, 1f))

fun snappedToOne(speed: Float, reach: Float): Float = if (abs(ln(speed)) <= reach) 1f else speed

fun flatCurve(speed: Float, count: Int = 5): List<CurvePoint> = List(count) { i -> CurvePoint(i / (count - 1f), speed, RAMP_EASE.takeIf { i > 0 }) }

val SpeedRamp.curve: List<CurvePoint> get() = points.mapIndexed { i, (u, speed) -> CurvePoint(u, speed, RAMP_EASE.takeIf { i > 0 }) }

fun List<CurvePoint>.ramp(): SpeedRamp? = SpeedRamp.entries.firstOrNull { ramp ->
    ramp.points.size == size && ramp.curve.zip(this).all { (a, b) -> abs(a.u - b.u) < MATCH && abs(a.speed - b.speed) < MATCH && a.ease == b.ease }
}

fun List<CurvePoint>.speedAt(u: Float): Float {
    if (u <= first().u) return first().speed
    if (u >= last().u) return last().speed
    val next = indexOfFirst { it.u > u }
    val a = this[next - 1]
    val b = this[next]
    val easing = Easing.parse(b.ease)
    val x = (u - a.u) / (b.u - a.u)
    return a.speed + (b.speed - a.speed) * if (easing.isLinear) x else easing.at(x)
}

fun List<CurvePoint>.outline(steps: Int = 16): List<Pair<Float, Float>> {
    val out = mutableListOf(first().u to first().speed)
    val us = FloatArray(steps) { (it + 1f) / steps }
    for ((a, b) in zipWithNext()) {
        val easing = Easing.parse(b.ease)
        val eased = if (easing.isLinear) us else NativeEngine.ease(easing.toString(), us)
        for (i in 0 until steps) out += (a.u + (b.u - a.u) * us[i]) to (a.speed + (b.speed - a.speed) * eased[i])
    }
    return out
}

fun List<CurvePoint>.moved(index: Int, u: Float, speed: Float): List<CurvePoint> {
    if (index !in indices) return this
    val x = when (index) {
        0 -> 0f
        lastIndex -> 1f
        else -> {
            val lo = this[index - 1].u + CURVE_GAP
            val hi = this[index + 1].u - CURVE_GAP
            if (lo <= hi) u.coerceIn(lo, hi) else this[index].u
        }
    }
    return toMutableList().also { it[index] = it[index].copy(u = x, speed = speed) }
}

fun List<CurvePoint>.withPointNear(u: Float): Pair<List<CurvePoint>, Int>? {
    if (size < 2 || size >= CURVE_MOST_POINTS) return null
    fun clear(x: Float) = none { abs(it.u - x) < CURVE_GAP }
    val (a, b) = zipWithNext().maxBy { (from, to) -> to.u - from.u }
    val x = u.takeIf { it in 0f..1f && clear(it) } ?: ((a.u + b.u) / 2).takeIf(::clear) ?: return null
    val index = indexOfFirst { it.u > x }
    return (take(index) + CurvePoint(x, speedAt(x), this[index].ease) + drop(index)) to index
}

fun List<CurvePoint>.without(index: Int): List<CurvePoint> = if (index in 1 until lastIndex) filterIndexed { i, _ -> i != index } else this

fun Layer.curveSpan(timing: VideoTiming = VideoTiming(this)): ClosedFloatingPointRange<Double> =
    timing.mediaMs(0.0)..timing.mediaMs((endTime - startTime).toDouble())

fun Layer.speedCurve(): List<CurvePoint>? {
    val keys = node.child("speedMap")?.keyframes.orEmpty().distinctBy { it.t }.filter { it.v.finiteFloat() != null }
    if (keys.isEmpty()) return null
    val timing = VideoTiming(this)
    val span = curveSpan(timing)
    val length = span.endInclusive - span.start
    if (length <= 0.0) return null
    fun u(key: Keyframe) = (key.t / 1e6 - span.start) / length
    fun at(media: Double) = timing.speed(timing.localMs(media))
    val first = keys.first().takeIf { abs(u(it)) <= EDGE }
    val last = keys.last().takeIf { it !== first && abs(u(it) - 1) <= EDGE }
    val inside = keys.filter { it !== first && it !== last && u(it) > 0 && u(it) < 1 }.map { CurvePoint(u(it).toFloat(), it.v.finiteFloat()!!, it.e) }
    return listOf(CurvePoint(0f, first?.v?.finiteFloat() ?: at(span.start), null)) + inside +
        CurvePoint(1f, last?.v?.finiteFloat() ?: at(span.endInclusive), last?.e)
}

fun Layer.withSpeedCurve(points: List<CurvePoint>?): Layer {
    if (points == null || points.size < 2) return withVideoSpeedMap(XmlNode("speedMap", mapOf("value" to "1")))
    val span = curveSpan()
    val length = span.endInclusive - span.start
    val keys = points.mapIndexed { i, p -> Keyframe(((span.start + p.u * length) * 1e6).toFloat(), speedText(p.speed), p.ease.takeIf { i > 0 }) }
    return withVideoSpeedMap(XmlNode("speedMap").withKeyframes(keys))
}

fun Layer.lengthWithCurve(points: List<CurvePoint>?): Int = withSpeedCurve(points).let { it.endTime - it.startTime }

fun Layer.normalSpeed(): Float {
    val timing = VideoTiming(this)
    val length = endTime - startTime
    if (node.child("speedMap")?.isKeyframed != true || length <= 0) return timing.speed(0.0)
    return ((timing.mediaMs(length.toDouble()) - timing.mediaMs(0.0)) / length).toFloat()
}

fun Layer.withNormalSpeed(speed: Float): Layer {
    val map = node.child("speedMap")
    if (map?.isKeyframed != true) return withVideoSpeedMap(XmlNode("speedMap", mapOf("value" to speedText(speed))))
    val keys = map.keyframes
    val top = keys.maxOf { it.v.finiteFloat() ?: 0f }
    val factor = (speed / normalSpeed()).let { if (top > 0f) minOf(it, FASTEST_SPEED / top) else it }
    return withVideoSpeedMap(map.withKeyframes(keys.map { key -> key.v.finiteFloat()?.let { key.copy(v = speedText(it * factor)) } ?: key }))
}

private fun speedText(speed: Float): String = speed.toBigDecimal().stripTrailingZeros().toPlainString()
