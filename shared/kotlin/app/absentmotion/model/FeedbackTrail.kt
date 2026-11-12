package app.absentmotion.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

fun Layer.feedbackTrail(timeMs: Double, scene: Scene): List<Int>? {
    if (mediaFill?.video != true || endTime <= startTime) return null
    val effect = effects.firstOrNull { it["id"] == "absent.videofeedback" && it["hidden"] != "true" } ?: return null
    val t = ((steppedMs(timeMs) - startTime) / (endTime - startTime)).toFloat()
    fun value(name: String, fallback: Float) = effect.effectProperty(name)?.valueAt(t)?.finiteFloat() ?: fallback
    if (value("trails", 0f) < 0.5f) return null
    val copies = value("copies", 24f).roundToInt().coerceIn(1, 64)
    val fade = value("fade", 0.9f).coerceIn(0f, 1f)
    val delay = value("delay", 0f).coerceIn(0f, 1f)
    val frame = scene.frameAt(floor(timeMs).toInt())
    val times = ArrayList<Int>()
    var alpha = 1f
    for (i in 1..copies) {
        alpha *= fade
        if (alpha < 1f / 255) break
        val at = if (delay > 0f) (timeMs - 1000.0 * delay * i).roundToInt() else scene.frameStart(frame - i)
        times += at.coerceAtLeast(startTime)
    }
    return times.takeIf { it.isNotEmpty() }
}

fun Layer.feedbackStep(timeMs: Double, scene: Scene): Float {
    val effect = effects.firstOrNull { it["id"] == "absent.videofeedback" && it["hidden"] != "true" } ?: return 1f
    val t = ((steppedMs(timeMs) - startTime) / (endTime - startTime).coerceAtLeast(1)).toFloat()
    fun value(name: String, fallback: Float) = effect.effectProperty(name)?.valueAt(t)?.finiteFloat() ?: fallback
    val own = if (value("useLayerTransform", 1f) >= 0.5f) at(timeMs.toInt(), scene.fps).scale.maxOf { abs(it) } else 1f
    return value("zoom", 0.85f).coerceIn(0.1f, 1.5f) * own
}

fun trailBudgetMb(totalMb: Long, lowRam: Boolean, memoryClassMb: Int, compatible: Boolean): Int =
    if (lowRam || compatible || memoryClassMb < 256 || totalMb < 7000) 24 else if (totalMb < 11000) 48 else 64
