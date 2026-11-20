package app.absentmotion.editor

import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.atVideoTime
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.parseColor
import app.absentmotion.model.valueAt
import app.absentmotion.model.withKeyframes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

internal data class RecordedSample(val frame: Int, val time: Float, val value: String, val start: Float, val end: Float)

private fun recordingNumbers(value: String): List<Float>? {
    if (value.startsWith('#')) {
        val color = parseColor(value) ?: return null
        return listOf(24, 16, 8, 0).map { ((color ushr it) and 255).toFloat() }
    }
    return value.split(',').map { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: return null }
}

internal fun simplifyRecording(samples: List<RecordedSample>, tolerance: Float): List<RecordedSample> {
    if (samples.size <= 2) return samples
    val values = samples.map { recordingNumbers(it.value) }
    if (values.any { it == null } || values.any { it?.size != values.first()?.size }) {
        return samples.filterIndexed { i, sample -> i == 0 || i == samples.lastIndex || sample.value != samples[i - 1].value }
    }
    val numbers = values.filterNotNull()
    val keep = BooleanArray(samples.size)
    keep[0] = true
    keep[keep.lastIndex] = true
    val pending = ArrayDeque<Pair<Int, Int>>()
    pending.add(0 to samples.lastIndex)
    while (pending.isNotEmpty()) {
        val (first, last) = pending.removeLast()
        val duration = samples[last].time - samples[first].time
        var worst = tolerance
        var chosen = -1
        for (i in first + 1 until last) {
            val u = if (duration == 0f) 0f else (samples[i].time - samples[first].time) / duration
            val error = sqrt(numbers[i].indices.sumOf { component ->
                val expected = numbers[first][component] + (numbers[last][component] - numbers[first][component]) * u
                val difference = (numbers[i][component] - expected).toDouble()
                difference * difference
            }).toFloat()
            if (error > worst) { worst = error; chosen = i }
        }
        if (chosen >= 0) { keep[chosen] = true; pending.add(first to chosen); pending.add(chosen to last) }
    }
    return samples.filterIndexed { index, _ -> keep[index] }
}

internal fun mergeRecording(original: List<Keyframe>, samples: List<RecordedSample>, overwrite: Boolean, tolerance: Float): List<Keyframe> {
    if (samples.isEmpty()) return original
    val first = samples.first().start
    val last = samples.last().end
    val kept = if (tolerance < 0f) samples else simplifyRecording(samples, tolerance)
    val retained = original.filter { key ->
        if (overwrite) key.t < first || key.t > last
        else kept.none { key.t >= it.start && key.t <= it.end }
    }
    return (retained + kept.map { Keyframe(it.time, it.value) }).sortedBy { it.t }
}

internal data class RecordedProperty(val layer: Long, val path: List<Int>)

private fun XmlNode.propertyAt(path: List<Int>): XmlNode? =
    if (path.isEmpty()) this else children.filter { it.name != "kf" }.getOrNull(path.first())?.propertyAt(path.drop(1))

private fun XmlNode.withRecordedProperty(path: List<Int>, change: (XmlNode) -> XmlNode): XmlNode {
    if (path.isEmpty()) return change(this)
    var index = -1
    return copy(children = children.map { child ->
        if (child.name == "kf") child
        else if (++index == path.first()) child.withRecordedProperty(path.drop(1), change) else child
    })
}

internal class LiveRecording(
    private val initial: Scene,
    val key: Any?,
    private val overwrite: Boolean,
    val pivotId: Long? = null,
    private val normalized: (effect: String, name: String) -> Boolean = { _, _ -> false },
) {
    private class Track(val original: XmlNode, var tolerance: Float, val effect: String?, val samples: MutableList<RecordedSample> = mutableListOf())
    private val tracks = linkedMapOf<RecordedProperty, Track>()
    val active: Boolean get() = tracks.isNotEmpty()

    private fun sampled(layer: Layer, node: XmlNode, frame: Int, scene: Scene): Layer =
        if (node.name == "speedMap") layer.atVideoTime(scene.frameTime(frame), scene.fps) else layer.at(scene.frameTime(frame), scene.fps)

    private fun tolerance(scene: Scene, layer: Layer, node: XmlNode, frame: Int, effect: String?): Float = when {
        effect != null && (node["type"] == "vec2" || node["type"] == "vec3") && normalized(effect, node["name"].orEmpty()) -> 0.5f / max(1, max(scene.width, scene.height))
        node.name == "location" || node.name == "pivot" || node["type"] == "vec2" || node["type"] == "vec3" -> {
            val place = scene.place(layer, scene.frameTime(frame))
            0.5f / max(1f, max(abs(place.sx), abs(place.sy)))
        }
        node.name == "scale" -> 0.005f
        node.name == "skew" -> Math.toRadians(0.25).toFloat()
        node.name in listOf("rotation", "tilt", "fov") || node["type"] == "angle" -> 0.25f
        node.valueAt(sampled(layer, node, frame, scene).t)?.startsWith('#') == true -> 0.5f
        else -> 0.001f
    }

    private fun append(track: Track, layer: Layer, node: XmlNode, frame: Int, value: String, scene: Scene) {
        val at = sampled(layer, node, frame, scene)
        var recorded = value
        if (node.name == "rotation" && key != null) {
            val previous = track.samples.lastOrNull()?.value?.toFloatOrNull()
            val angle = value.toFloatOrNull()
            if (previous != null && angle != null && abs(angle - previous) in 180f..360.01f) recorded = (angle - round((angle - previous) / 360f) * 360f).toString()
        }
        val sample = RecordedSample(frame, at.t, recorded, at.snap.start, at.snap.endInclusive)
        if (track.samples.lastOrNull()?.frame == frame) track.samples[track.samples.lastIndex] = sample else track.samples.add(sample)
        track.tolerance = minOf(track.tolerance, tolerance(scene, layer, node, frame, track.effect))
    }

    fun capture(before: Scene, after: Scene, frame: Int, fresh: Set<Long> = emptySet()): Boolean {
        if (before.fps != after.fps || before.layers.map { it.id } != after.layers.map { it.id }) return false
        for (layer in before.layers) {
            val next = after.layer(layer.id) ?: continue
            if (layer.startTime != next.startTime || layer.endTime != next.endTime) return false
        }
        class Found(val address: RecordedProperty, val old: XmlNode, val layer: Layer, val node: XmlNode, val value: String, val effect: String?)
        val found = ArrayList<Found>()
        var whole = false
        fun walk(layer: Layer, next: Layer, old: XmlNode, node: XmlNode, path: List<Int>, effect: String?) {
            if (old.name != node.name || old["name"] != node["name"] || old["id"] != node["id"] || node.name == "scene") return
            val address = RecordedProperty(layer.id, path)
            val pivot = pivotId == layer.id && node.name == "pivot" && path.size == 2
            if (pivotId == null || pivot) {
                val at = sampled(next, node, frame, after)
                val was = old.valueAt(sampled(layer, old, frame, before).t)
                val value = node.valueAt(at.t)
                val keys = node.keyframes
                if (old.isKeyframed && node.isKeyframed && node.children.none { it.name == "valueLink" } &&
                    old.keyframes.map { it.t to it.v } != keys.map { it.t to it.v } && value != null && was != value) {
                    fun away(list: List<Keyframe>) = list.filter { it.t !in at.snap }.map { it.t to it.v }
                    if (pivotId == null && (away(old.keyframes) != away(keys) || keys.none { it.t in at.snap })) whole = true
                    else found += Found(address, old, next, node, value, effect)
                } else if (pivotId == null && layer.id in fresh && !old.isKeyframed && !node.isKeyframed && node.children.none { it.name == "valueLink" } &&
                    value != null && was != null && was != value && recordingNumbers(value) != null && recordingNumbers(was) != null) {
                    found += Found(address, old, next, node, value, effect)
                }
            }
            val inner = if (node.name == "effect") node["id"] else effect
            val previous = old.children.filter { it.name != "kf" }
            node.children.filter { it.name != "kf" }.forEachIndexed { i, child -> previous.getOrNull(i)?.let { walk(layer, next, it, child, path + i, inner) } }
        }
        for (layer in before.layers) walk(layer, after.layer(layer.id)!!, layer.node, after.layer(layer.id)!!.node, emptyList(), null)
        if (whole) return false
        for (f in found) append(tracks.getOrPut(f.address) { Track(f.old, tolerance(after, f.layer, f.node, frame, f.effect), f.effect) }, f.layer, f.node, frame, f.value, after)
        return found.isNotEmpty()
    }

    fun nextLap(scene: Scene, frame: Int): LiveRecording = LiveRecording(scene, key, overwrite, pivotId, normalized).also { lap ->
        for ((address, track) in tracks) {
            val layer = scene.layer(address.layer) ?: continue
            val node = layer.node.propertyAt(address.path) ?: continue
            val value = track.samples.lastOrNull()?.value ?: continue
            lap.append(Track(node, track.tolerance, track.effect).also { lap.tracks[address] = it }, layer, node, frame, value, scene)
        }
    }

    fun hold(scene: Scene, frame: Int): Scene {
        if (pivotId != null) {
            val track = tracks.values.singleOrNull() ?: return scene
            val value = recordingNumbers(track.samples.last().value) ?: return scene
            val next = scene.withPivotMoved(pivotId, scene.frameTime(frame), value[0], value[1], scene.frameStart(frame))
            val layer = next.layer(pivotId) ?: return next
            val node = layer.element(PropRef.Pivot) ?: return next
            append(track, layer, node, frame, track.samples.last().value, next)
            return next
        }
        for ((address, track) in tracks) {
            val layer = scene.layer(address.layer) ?: continue
            val node = layer.node.propertyAt(address.path) ?: continue
            append(track, layer, node, frame, track.samples.last().value, scene)
        }
        return render(scene, false)
    }

    fun render(scene: Scene, simplify: Boolean): Scene {
        var next = scene
        for ((address, track) in tracks) {
            val keys = mergeRecording(track.original.keyframes, track.samples, overwrite, if (simplify) track.tolerance else -1f)
            if (pivotId != null) {
                if (simplify) return initial.withRecordedPivot(pivotId, track.original.withKeyframes(keys))
            } else next = next.update(address.layer) { layer ->
                layer.copy(node = layer.node.withRecordedProperty(address.path) { it.withKeyframes(keys) })
            }
        }
        return next
    }
}
