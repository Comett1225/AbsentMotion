package app.absentmotion.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

const val MOSH_STEPS = 60

class MoshPlan(val frames: List<Int>, val params: FloatArray)

fun Layer.moshPlan(timeMs: Double, scene: Scene): MoshPlan? {
    if (mediaFill?.video != true || endTime <= startTime) return null
    val effect = effects.firstOrNull { it["id"] == "absent.datamosh" && it["hidden"] != "true" } ?: return null
    fun value(name: String, fallback: Float, t: Float) = effect.effectProperty(name)?.valueAt(t)?.toFloatOrNull() ?: fallback
    val length = (endTime - startTime) / 1000f
    val from = value("start", 0f, 0f).coerceIn(0f, length)
    val start = startTime + from * 1000.0
    val at = from / length
    val end = startTime + value("end", 2f, at).coerceIn(from, length) * 1000.0
    if (timeMs < start || timeMs >= end) return null
    val first = scene.frameAt(start.roundToInt())
    val window = (scene.frameAt(end.roundToInt() - 1) - first + 1).coerceAtLeast(1)
    val stride = ceil(window / MOSH_STEPS.toDouble()).toInt()
    val index = ((scene.frameAt(floor(timeMs).toInt()) - first) / stride).coerceAtLeast(0)
    val params = floatArrayOf(value("intensity", 1f, at), value("block", 16f, at), value("decay", 0.05f, at), value("glitch", 0.1f, at), value("bloom", 0.3f, at))
    return MoshPlan((0..index).map { scene.frameStart(first + it * stride) }, params)
}
