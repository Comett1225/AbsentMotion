package app.absentmotion.model

import androidx.annotation.StringRes
import app.absentmotion.R
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

val CameraFov = PropRef(PropRef.Kind.Child, "fov")

const val CAMERA_FOV_MIN = 0.1f
const val CAMERA_FOV_MAX = 179.9f
const val CAMERA_MOVE_MIN_MS = 500
const val CAMERA_MOVE_MAX_MS = 10_000

fun cameraZoomOf(fov: Float, size: Int): Float = (size / (2 * tan(Math.toRadians(fov / 2.0)))).toFloat()

fun cameraFovOf(zoom: Float, size: Int): Float = Math.toDegrees(2 * atan(size / (2.0 * zoom))).toFloat()

val Layer.cameraFov: Float get() = element(CameraFov)?.valueAt(t)?.toFloatOrNull() ?: 60f

fun Layer.withCameraFov(fov: Float): Layer = withValue(CameraFov, formatFloats(floatArrayOf(fov.coerceIn(CAMERA_FOV_MIN, CAMERA_FOV_MAX))))

fun Layer.cameraFrame(width: Int, height: Int): Pair<Float, Float> {
    val reach = max(-location[2], 0f) / cameraZoomOf(cameraFov, max(width, height))
    return width * reach to height * reach
}

fun cameraFovScaled(fov: Float, factor: Float): Float =
    if (!(factor > 0f)) fov else Math.toDegrees(2 * atan(tan(Math.toRadians(fov / 2.0)) * factor)).toFloat().coerceIn(CAMERA_FOV_MIN, CAMERA_FOV_MAX)

fun Layer.withCameraFrameScaled(factor: Float): Layer = withCameraFov(cameraFovScaled(cameraFov, factor))

fun Layer.cameraGesture(dx: Float, dy: Float, zoom: Float): Layer {
    val at = location
    val moved = if (dx != 0f || dy != 0f) withTransform("location", at[0] + dx, at[1] + dy, at[2]) else this
    return if (zoom != 1f) moved.withCameraFrameScaled(zoom) else moved
}

val Scene.defaultCameraId: Long? get() = layers.firstOrNull { it.kind == "camera" && !it.hidden }?.id

enum class CameraMove(val id: String, @StringRes val label: Int) {
    PushIn("push-in", R.string.camera_move_push_in),
    PullOut("pull-out", R.string.camera_move_pull_out),
    Orbit("orbit", R.string.camera_move_orbit),
    Pan("pan", R.string.camera_move_pan),
    DollyZoom("dolly-zoom", R.string.camera_move_dolly_zoom),
    Handheld("handheld", R.string.camera_move_handheld),
}

private data class Pose(val x: Float, val y: Float, val z: Float, val turn: Float, val fov: Float)

private const val EDGE = 1e-4f

private fun smooth(u: Float): Float = u * u * (3 - 2 * u)

fun Scene.cameraMoveSpan(id: Long, fromMs: Int, ms: Int): Pair<Int, Int>? {
    val camera = layer(id)?.takeIf { it.kind == "camera" } ?: return null
    val from = if (fromMs in camera.startTime until camera.endTime) fromMs else camera.startTime
    val to = minOf(from + ms.coerceIn(CAMERA_MOVE_MIN_MS, CAMERA_MOVE_MAX_MS), camera.endTime)
    return (from to to).takeIf { to > from }
}

fun Scene.withCameraMove(id: Long, move: CameraMove, fromMs: Int, ms: Int, clearToMs: Int = 0): Scene {
    val (from, to) = cameraMoveSpan(id, fromMs, ms) ?: return this
    return update(id) { it.withCameraMove(move, from, to, max(to, clearToMs), width, height) }
}

private fun Layer.poseAt(timeMs: Int): Pose {
    val here = at(timeMs, 1000)
    val at = here.location
    return Pose(at[0], at[1], at[2], here.transform("rotation", 0f)[0], here.cameraFov)
}

internal fun Layer.withCameraMove(move: CameraMove, fromMs: Int, toMs: Int, clearToMs: Int, width: Int, height: Int): Layer {
    val start = poseAt(fromMs)
    val size = max(width, height)
    val distance = (-start.z).takeIf { it > 1f } ?: cameraZoomOf(start.fov, size)
    val frameWidth = width * distance / cameraZoomOf(start.fov, size)
    val poses = when (move) {
        CameraMove.PushIn -> listOf(0f to start, 1f to start.copy(z = start.z + 0.35f * distance))
        CameraMove.PullOut -> listOf(0f to start, 1f to start.copy(z = start.z - 0.5f * distance))
        CameraMove.Pan -> listOf(0f to start, 1f to start.copy(x = start.x + 0.5f * frameWidth))
        CameraMove.Orbit -> {
            val r = 0.12f * frameWidth
            List(17) { i ->
                val u = i / 16f
                val a = 2 * PI.toFloat() * smooth(u)
                u to if (i % 16 == 0) start else start.copy(x = start.x + r * sin(a), y = start.y - r + r * cos(a))
            }
        }
        CameraMove.DollyZoom -> {
            val half = tan(Math.toRadians(start.fov / 2.0)).toFloat()
            List(9) { i ->
                val u = i / 8f
                val d = distance * (1 - 0.5f * smooth(u))
                val fov = Math.toDegrees(2.0 * atan(half * distance / d)).toFloat().coerceIn(CAMERA_FOV_MIN, CAMERA_FOV_MAX)
                u to start.copy(z = start.z + (distance - d), fov = fov)
            }
        }
        CameraMove.Handheld -> {
            val steps = ((toMs - fromMs) / 160f).roundToInt().coerceAtLeast(4)
            val random = Random(id xor fromMs.toLong())
            val reach = 0.012f * frameWidth
            List(steps + 1) { i ->
                val u = i.toFloat() / steps
                if (i == 0 || i == steps) u to start
                else u to start.copy(
                    x = start.x + reach * (random.nextFloat() * 2 - 1), y = start.y + reach * (random.nextFloat() * 2 - 1),
                    turn = start.turn + 0.8f * (random.nextFloat() * 2 - 1),
                )
            }
        }
    }
    val eased = move == CameraMove.PushIn || move == CameraMove.PullOut || move == CameraMove.Pan || move == CameraMove.Handheld
    val span = (endTime - startTime).coerceAtLeast(1).toFloat()
    fun local(ms: Int) = ((ms - startTime) / span).coerceIn(0f, 1f)
    val t0 = local(fromMs)
    val t1 = local(toMs)
    val clear = local(clearToMs)
    fun keys(value: (Pose) -> String) = poses.mapIndexed { i, (u, pose) ->
        Keyframe(t0 + u * (t1 - t0), value(pose), if (i > 0 && eased) EaseInOut.toString() else null)
    }
    var layer = this
    fun write(ref: PropRef, changed: Boolean, value: (Pose) -> String) {
        if (!changed) return
        val element = layer.element(ref) ?: XmlNode(ref.name)
        val kept = element.keyframes.filter { it.t < t0 - EDGE || it.t > clear + EDGE }
        layer = layer.withElement(ref, element.withKeyframes(kept + keys(value)))
    }
    val moves = poses.map { it.second }
    write(PropRef.Location, true) { formatFloats(floatArrayOf(it.x, it.y, it.z)) }
    write(PropRef.Rotation, moves.any { it.turn != start.turn }) { formatFloats(floatArrayOf(it.turn)) }
    write(CameraFov, moves.any { it.fov != start.fov }) { formatFloats(floatArrayOf(it.fov)) }
    return layer
}
