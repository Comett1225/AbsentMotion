package app.absentmotion.model

import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

val CameraFov = PropRef(PropRef.Kind.Child, "fov")

const val CAMERA_FOV_MIN = 0.1f
const val CAMERA_FOV_MAX = 179.9f

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
