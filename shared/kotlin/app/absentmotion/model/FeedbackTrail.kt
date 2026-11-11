package app.absentmotion.model

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
