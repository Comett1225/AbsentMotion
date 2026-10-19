package app.absentmotion.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToInt

val SpeedRef = PropRef(PropRef.Kind.Child, "speedMap")

class VideoTiming(layer: Layer) {
    private data class Point(val media: Double, val speed: Double, val item: Double)
    private val origin = layer.mediaFill?.inTime?.toDouble() ?: layer.node["inTime"]?.toDoubleOrNull() ?: 0.0
    private val points: List<Point>

    init {
        val map = layer.node.child("speedMap")
        val keys = map?.keyframes.orEmpty().distinctBy { it.t }.filter { it.v.toDoubleOrNull() != null }
        val samples = mutableListOf<Pair<Double, Double>>()
        if (keys.isEmpty()) {
            samples += 0.0 to ((map?.get("value")?.toDoubleOrNull() ?: layer.node["speed"]?.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.001))
        } else {
            samples += keys.first().t.toDouble() / 1_000_000 to keys.first().v.toDouble().coerceAtLeast(0.001)
            for ((a, b) in keys.zipWithNext()) {
                val easing = Easing.parse(b.e)
                val count = if (easing.isLinear) 1 else ceil((b.t - a.t) / 50_000_000.0).toInt().coerceIn(1, 250)
                for (i in 1..count) {
                    val u = i.toFloat() / count
                    val value = a.v.toDouble() + (b.v.toDouble() - a.v.toDouble()) * (if (easing.isLinear) u else easing.at(u))
                    samples += (a.t + (b.t - a.t) * u).toDouble() / 1_000_000 to value.coerceAtLeast(0.001)
                }
            }
        }
        var item = 0.0
        points = samples.mapIndexed { i, (media, speed) ->
            if (i > 0) item += 2 * (media - samples[i - 1].first) / (speed + samples[i - 1].second)
            Point(media, speed, item)
        }
    }

    private fun itemAt(media: Double): Double {
        val first = points.first(); val last = points.last()
        if (media <= first.media) return first.item + (media - first.media) / first.speed
        if (media >= last.media) return last.item + (media - last.media) / last.speed
        val next = points.indexOfFirst { it.media > media }
        val a = points[next - 1]; val b = points[next]
        val acceleration = (b.speed - a.speed) / (b.item - a.item)
        val distance = media - a.media
        val time = if (abs(acceleration) < 1e-12) distance / a.speed
            else 2 * distance / (sqrt(a.speed * a.speed + 2 * acceleration * distance) + a.speed)
        return a.item + time
    }

    private fun mediaAt(item: Double): Double {
        val first = points.first(); val last = points.last()
        if (item <= first.item) return first.media + (item - first.item) * first.speed
        if (item >= last.item) return last.media + (item - last.item) * last.speed
        val next = points.indexOfFirst { it.item > item }
        val a = points[next - 1]; val b = points[next]
        val elapsed = item - a.item
        return a.media + a.speed * elapsed + (b.speed - a.speed) * elapsed * elapsed / (2 * (b.item - a.item))
    }

    fun mediaMs(localMs: Double): Double = mediaAt(itemAt(origin) + localMs)
    fun localMs(mediaMs: Double): Double = itemAt(mediaMs) - itemAt(origin)
    fun speed(localMs: Double): Float {
        val item = itemAt(origin) + localMs
        if (item <= points.first().item) return points.first().speed.toFloat()
        if (item >= points.last().item) return points.last().speed.toFloat()
        val next = points.indexOfFirst { it.item > item }
        val a = points[next - 1]; val b = points[next]
        return (a.speed + (b.speed - a.speed) * (item - a.item) / (b.item - a.item)).toFloat()
    }
}

fun Layer.mediaTimeMs(timeMs: Double): Double = VideoTiming(this).mediaMs(timeMs - startTime)

fun Layer.videoTimeUs(timeMs: Int, scene: Scene): Long {
    val fill = mediaFill ?: return 0
    return (fill.bounded(mediaTimeMs(scene.frameTime(scene.frameAt(timeMs)).toDouble()), node["loop"] == "true") * 1000).toLong() - 500
}

private fun MediaFill.bounded(mapped: Double, loop: Boolean): Double {
    val span = (outTime.toDouble() - inTime).coerceAtLeast(1.0)
    return if (loop) inTime + ((mapped - inTime) % span + span) % span
        else mapped.coerceIn(inTime.toDouble(), outTime.toDouble().coerceAtLeast(inTime.toDouble()))
}

fun exportFrameMs(timeMs: Int, fps: Int): Double = ((((timeMs + 1L) * fps + 999) / 1000) - 1) * 1000.0 / fps

private fun Layer.localMs(timeMs: Double, scene: Scene): Double {
    val first = scene.frameAt(startTime)
    return timeMs - if (scene.frameStart(first) == startTime) first * 1000.0 / scene.fps else startTime.toDouble()
}

fun Layer.needsFrameInterpolation(timeMs: Double, scene: Scene, stamps: LongArray, fps: Int): Boolean =
    pairAround(timeMs, scene, stamps)?.let { needs(it, timeMs, scene, fps) } == true

fun Layer.exportFrame(timeMs: Double, scene: Scene, stamps: LongArray, fps: Int): FramePair? {
    val around = pairAround(timeMs, scene, stamps) ?: return null
    return when {
        needs(around, timeMs, scene, fps) -> around.pair
        fps == scene.fps -> null
        else -> FramePair(around.pair.a, around.pair.a, 0f)
    }
}

private fun Layer.needs(around: Around, timeMs: Double, scene: Scene, fps: Int): Boolean =
    around.gap > 0 && VideoTiming(this).speed(localMs(timeMs, scene)) * 1e6 / around.gap < fps * 0.99

data class FramePair(val a: Long, val b: Long, val t: Float)

private class Around(val pair: FramePair, val gap: Long)

private const val PAIR_SNAP = 0.005

fun Layer.framePair(timeMs: Double, scene: Scene, stamps: LongArray): FramePair =
    pairAround(timeMs, scene, stamps)?.pair ?: FramePair(stamps.first(), stamps.first(), 0f)

private fun Layer.pairAround(timeMs: Double, scene: Scene, stamps: LongArray): Around? {
    val fill = mediaFill ?: return null
    val loop = node["loop"] == "true"
    val first = fill.inTime * 1000.0 - 500
    val last = fill.outTime * 1000.0 - 500
    val mapped = VideoTiming(this).mediaMs(localMs(timeMs, scene))
    val us = if (!loop && mapped >= fill.outTime) last else fill.bounded(mapped, loop) * 1000
    val i = stamps.atOrBefore(us)
    val gap = if (i + 1 < stamps.size) stamps[i + 1] - stamps[i] else if (i > 0) stamps[i] - stamps[i - 1] else 0
    fun held(at: Long) = Around(FramePair(at, at, 0f), gap)
    val a = stamps[i]
    val b = stamps.getOrNull(i + 1)
    if (a > last) return held(stamps[stamps.atOrBefore(last)])
    if (b == null || b > last) return held(a)
    if (a < first) return held(b)
    val t = ((us - a) / (b - a)).coerceIn(0.0, 1.0)
    return when {
        t < PAIR_SNAP -> held(a)
        t > 1 - PAIR_SNAP -> held(b)
        else -> Around(FramePair(a, b, t.toFloat()), gap)
    }
}

private fun LongArray.atOrBefore(us: Double): Int {
    val found = binarySearch(kotlin.math.floor(us).toLong())
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

fun Layer.trimmedTo(start: Int, end: Int, mediaMs: Int = Int.MAX_VALUE): Layer {
    val fill = mediaFill
    val timing = if (fill?.video == true || kind == "audio") VideoTiming(this) else null
    var from = start
    var to = end
    if (timing != null) {
        if (start < startTime) from = maxOf(start, startTime + minOf(0, timing.localMs(0.0).roundToInt()))
        if (end > endTime && mediaMs != Int.MAX_VALUE) to = minOf(end, maxOf(endTime, startTime + timing.localMs(mediaMs.toDouble()).roundToInt()))
    }
    var trimmed = withTimes(from, to)
    if (timing != null) {
        if (from != startTime) trimmed = trimmed.copy(node = trimmed.node.with("inTime", "${timing.mediaMs((from - startTime).toDouble()).roundToInt().coerceAtLeast(0)}"))
        if (to != endTime) trimmed = trimmed.copy(node = trimmed.node.with("outTime", "${timing.mediaMs((to - startTime).toDouble()).roundToInt().coerceAtMost(mediaMs)}"))
    }
    return trimmed
}

fun Scene.trimStartAt(layer: Layer, frame: Int): Layer? =
    frameStart(frame).takeIf { it > layer.startTime && showsAnyFrame(it, layer.endTime) }?.let { layer.trimmedTo(it, layer.endTime) }

fun Scene.trimEndAt(layer: Layer, frame: Int): Layer? =
    frameEnd(frame).takeIf { it < layer.endTime && showsAnyFrame(layer.startTime, it) }?.let { layer.trimmedTo(layer.startTime, it) }

fun Scene.splitAt(layer: Layer, frame: Int, newId: Long): Pair<Layer, Layer>? {
    val cut = frameStart(frame)
    if (cut <= layer.startTime || !showsAnyFrame(layer.startTime, cut - 1) || !showsAnyFrame(cut, layer.endTime)) return null
    return layer.trimmedTo(layer.startTime, cut - 1) to layer.withId(newId).trimmedTo(cut, layer.endTime)
}

fun Layer.withVideoSpeedMap(map: XmlNode): Layer {
    val old = VideoTiming(this)
    var changed = copy(node = node.withChild("speedMap", map).with("speed", null))
    val timing = VideoTiming(changed)
    val duration = timing.localMs(old.mediaMs((endTime - startTime).toDouble())).roundToInt().coerceAtLeast(1)
    fun adjust(n: XmlNode): XmlNode {
        if (n.name == "speedMap") return n
        if (n.name == "kf") {
            val local = (n["t"]?.toDoubleOrNull() ?: 0.0) * (endTime - startTime)
            return n.with("t", (timing.localMs(old.mediaMs(local)) / duration).toString())
        }
        return n.copy(children = n.children.map(::adjust))
    }
    changed = changed.copy(node = adjust(changed.node).with("endTime", "${startTime + duration}"))
    val keys = map.keyframes
    if (keys.isEmpty()) {
        val value = map["value"] ?: "1"
        changed = changed.copy(node = changed.node.copy(children = changed.node.children.filterNot { it.name == "speedMap" })
            .with("speed", value.takeUnless { it.toFloatOrNull() == 1f }))
    }
    return changed
}

fun Layer.atVideoTime(timeMs: Int, fps: Int): Layer {
    val timing = VideoTiming(this)
    val local = (timeMs - startTime).toDouble()
    return copy(t = (timing.mediaMs(local) * 1_000_000).toFloat(), snap = timing.speed(local) * 500_000_000f / fps)
}

fun Layer.withSpeedKeyframeToggled(projectTime: Int, fps: Int): Layer {
    val local = (projectTime - startTime).toDouble()
    val timing = VideoTiming(this)
    val media = (timing.mediaMs(local) * 1_000_000).toFloat()
    val map = node.child("speedMap") ?: XmlNode("speedMap", mapOf("value" to (node["speed"] ?: "1")))
    val keys = map.keyframes
    val here = keys.indexOfFirst { abs(timing.localMs(it.t.toDouble() / 1_000_000) - local) <= 500.0 / fps }
    val changed = if (here >= 0) {
        if (keys.size == 1) map.copy(children = map.children.filterNot { it.name == "kf" }).with("value", keys[0].v)
        else map.withKeyframes(keys.filterIndexed { index, _ -> index != here })
    } else {
        val next = keys.indexOfFirst { it.t > media }
        val value = timing.speed(local).toString()
        val inserted = Keyframe(media, value)
        if (next > 0 && !Easing.parse(keys[next].e).isLinear) {
            val a = keys[next - 1]; val b = keys[next]
            val curve = Easing.parse(b.e)
            val split = splitSpeedBezier(curve, (media - a.t) / (b.t - a.t))
            val speed = a.v.toFloat() + (b.v.toFloat() - a.v.toFloat()) * curve.at((media - a.t) / (b.t - a.t))
            map.withKeyframes(keys.mapIndexed { i, key -> if (i == next) key.copy(e = split.second.toString()) else key } +
                inserted.copy(v = speed.coerceAtLeast(0.001f).toString(), e = split.first.toString()))
        } else map.withKeyframes(keys + inserted)
    }
    return copy(node = node.withChild("speedMap", changed))
}

private fun splitSpeedBezier(curve: Easing, x: Float): Pair<Easing, Easing> {
    if (curve.type != "cubicBezier" || curve.p.size < 4) return Easing.Linear to Easing.Linear
    data class P(val x: Float, val y: Float)
    fun mix(a: P, b: P, t: Float) = P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    val p0 = P(0f, 0f); val p1 = P(curve.p[0], curve.p[1]); val p2 = P(curve.p[2], curve.p[3]); val p3 = P(1f, 1f)
    var lo = 0f; var hi = 1f
    repeat(24) {
        val t = (lo + hi) / 2
        val u = 1 - t
        val at = 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t
        if (at < x) lo = t else hi = t
    }
    val t = (lo + hi) / 2
    val a = mix(p0, p1, t); val b = mix(p1, p2, t); val c = mix(p2, p3, t)
    val d = mix(a, b, t); val e = mix(b, c, t); val point = mix(d, e, t)
    fun unit(v: Float) = v.coerceIn(0f, 1f)
    val left = Easing("cubicBezier", listOf(unit(a.x / point.x), unit(a.y / point.y.coerceAtLeast(1e-6f)),
        unit(d.x / point.x), unit(d.y / point.y.coerceAtLeast(1e-6f))))
    val right = Easing("cubicBezier", listOf(unit((e.x - point.x) / (1 - point.x)), unit((e.y - point.y) / (1 - point.y).coerceAtLeast(1e-6f)),
        unit((c.x - point.x) / (1 - point.x)), unit((c.y - point.y) / (1 - point.y).coerceAtLeast(1e-6f))))
    return left to right
}
