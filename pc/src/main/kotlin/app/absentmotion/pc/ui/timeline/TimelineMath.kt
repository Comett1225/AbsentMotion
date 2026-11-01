package app.absentmotion.pc.ui.timeline

import kotlin.math.abs
import kotlin.math.roundToInt

fun rulerStep(extent: Int, width: Float, fps: Int): Float {
    val minimum = extent * 90f / width.coerceAtLeast(1f)
    val frames = listOf(1, 2, 5, 10, 15).map { it * 1000f / fps.coerceAtLeast(1) }
    val times = listOf(1000f, 2000f, 5000f, 10000f, 15000f, 30000f, 60000f, 120000f, 300000f, 600000f, 1800000f, 3600000f)
    return (frames + times).sorted().firstOrNull { it >= minimum } ?: 3600000f
}

fun rulerLabel(time: Float, step: Float, fps: Int): String {
    val ms = time.roundToInt().coerceAtLeast(0)
    return if (step < 1000f) "%02d:%02d:%02d".format(ms / 60000, ms / 1000 % 60, ((time * fps / 1000f) + .01f).toInt() % fps.coerceAtLeast(1))
    else if (ms >= 3600000) "%d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
    else "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
}

fun snapTime(time: Int, targets: List<Int>, scale: Float, disabled: Boolean, fps: Int): Pair<Int, Int?> {
    val target = if (disabled) null else targets.minByOrNull { abs(it.toLong() - time) }?.takeIf { abs(it.toLong() - time) * scale <= 8f }
    if (target != null) return target to target
    val frame = (time.toDouble() * fps.coerceAtLeast(1) / 1000).roundToInt()
    return (frame.toLong() * 1000 / fps.coerceAtLeast(1)).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() to null
}

fun clipSnap(reference: Int, delta: Int, length: Int, targets: List<Int>, scale: Float, disabled: Boolean, fps: Int): Pair<Int, Int?> {
    val start = (reference.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val first = snapTime(start, targets, scale, disabled, fps)
    if (length <= 0 || disabled) return first
    val end = (start.toLong() + length).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val second = snapTime(end, targets, scale, false, fps)
    return if (second.second != null && (first.second == null || abs(second.first.toLong() - end) < abs(first.first.toLong() - start))) (second.first - length) to second.second else first
}
