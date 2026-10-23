package app.absentmotion.engine

import android.hardware.HardwareBuffer
import android.graphics.Rect
import android.graphics.Bitmap
import android.view.Surface

class NativeEngine(backend: Backend) : AutoCloseable {
    enum class Backend { GRAPHITE, GANESH }

    private var handle = nativeCreate(backend.ordinal)
    val failed: Boolean get() = nativeFailed(handle)

    val info: String get() = nativeInfo(handle)

    fun attach(surface: Surface, forEncoder: Boolean): Long =
        nativeAttach(handle, surface, forEncoder).also { check(it != 0L) { "attach failed" } }

    fun detach(target: Long) = nativeDetach(handle, target)

    fun timing(target: Long): String = nativeTiming(handle, target)

    fun setScene(xml: String) {
        nativeSetScene(handle, xml.toByteArray())?.let { throw IllegalArgumentException(it) }
    }

    fun loadEffect(xml: String): String = nativeLoadEffect(handle, xml.toByteArray())

    val maxPictureSize: Int get() = nativeMaxPictureSize(handle)

    fun setPicture(uri: String, bitmap: Bitmap?, layerId: Long = 0): Boolean =
        nativeSetPicture(handle, uri.toByteArray(), layerId, bitmap, null, bitmap?.hasAlpha() == false, 0, 0, 0, 0, 0, 0)

    fun setPicture(uri: String, buffer: HardwareBuffer, layerId: Long = 0, opaque: Boolean = true, standard: Int = 0, range: Int = 0, crop: Rect? = null): Boolean =
        nativeSetPicture(handle, uri.toByteArray(), layerId, null, buffer, opaque, standard, range, crop?.left ?: 0, crop?.top ?: 0, crop?.right ?: 0, crop?.bottom ?: 0)

    fun setVideoFrame(uri: String, layerId: Long, pts: Long, buffer: HardwareBuffer, standard: Int = 0, range: Int = 0, crop: Rect? = null): Boolean =
        nativeSetVideoFrame(handle, uri.toByteArray(), layerId, pts, buffer, standard, range, crop?.left ?: 0, crop?.top ?: 0, crop?.right ?: 0, crop?.bottom ?: 0)

    fun interpolateVideo(uri: String, layerId: Long, frames: LongArray, t: Float, mode: Int, soft: Boolean, blur: Float, params: FloatArray, made: Bitmap? = null): Boolean =
        nativeInterpolateVideo(handle, uri.toByteArray(), layerId, frames, t, mode, soft, blur, params, made)

    fun moshStart(uri: String, layerId: Long, pts: LongArray, mosh: FloatArray, flow: FloatArray): Int =
        nativeMoshStart(handle, uri.toByteArray(), layerId, pts, mosh, flow)

    fun moshFrame(uri: String, layerId: Long, step: Int, buffer: HardwareBuffer, standard: Int = 0, range: Int = 0, crop: Rect? = null): Boolean =
        nativeMoshFrame(handle, uri.toByteArray(), layerId, step, buffer, standard, range, crop?.left ?: 0, crop?.top ?: 0, crop?.right ?: 0, crop?.bottom ?: 0)

    fun renderScene(
        target: Long, timeMs: Double, scale: Float, dx: Float, dy: Float, background: Int, selectedId: Long, handles: Int,
        view: PreviewView,
    ): Int = nativeRenderScene(
        handle, target, timeMs, scale, dx, dy, background, selectedId, handles,
        view.pixels, view.divisor, view.gridSpacing, view.solo, view.soloIds.takeIf { it.isNotEmpty() }?.toLongArray(),
        view.guides.flatMap { listOf(if (it.vertical) 1f else 0f, it.at, if (it.wide) 1f else 0f) }.toFloatArray(), view.refPx,
    )

    fun setCameraView(on: Boolean) = nativeSetCameraView(handle, on)

    fun setAccurateBlur(on: Boolean) = nativeSetAccurateBlur(handle, on)

    fun renderFrame(target: Long, timeMs: Double, background: Int, presentTimeNs: Long): Int =
        nativeRenderFrame(handle, target, timeMs, background, presentTimeNs)

    fun renderBuffer(buffer: HardwareBuffer, timeMs: Double, background: Int): Int = nativeRenderBuffer(handle, buffer, timeMs, background)

    fun releaseBuffers() = nativeReleaseBuffers(handle)

    val displayTiming: Boolean get() = org.json.JSONObject(info).optBoolean("displayTiming")

    fun layerQuad(layerId: Long, timeMs: Double): FloatArray? = nativeLayerQuad(handle, layerId, timeMs)

    fun layerMatrix(layerId: Long, timeMs: Double): FloatArray? = nativeLayerMatrix(handle, layerId, timeMs)

    fun layerBounds(layerId: Long, timeMs: Double): FloatArray? = nativeLayerBounds(handle, layerId, timeMs)

    fun layerOutline(layerId: Long, timeMs: Double): String? = nativeLayerOutline(handle, layerId, timeMs)

    fun hitTest(x: Float, y: Float, timeMs: Double): Long = nativeHitTest(handle, x, y, timeMs)

    fun still(timeMs: Double, width: Int, height: Int): ByteArray? = nativeStill(handle, timeMs, width, height)

    fun shapeIcon(xml: String, size: Int, color: Int): ByteArray? = nativeShapeIcon(handle, xml.toByteArray(), size, color)

    fun setModel(uri: String, mesh: app.absentmotion.media.Mesh?): Boolean =
        nativeSetModel(uri.toByteArray(), mesh?.positions, mesh?.normals, mesh?.uvs, mesh?.colors, mesh?.triangles, mesh?.slots, mesh?.twoSided == true)

    override fun close() {
        nativeDestroy(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("amengine")
        }

        fun ease(easing: String, us: FloatArray): FloatArray = nativeEase(easing, us)

        fun ease(easing: String, u: Float): Float = if (easing.isEmpty()) u else nativeEase(easing, floatArrayOf(u))[0]

        fun fastCaptionCpu(): Boolean = nativeFastCaptionCpu()

        @JvmStatic private external fun nativeCreate(backend: Int): Long
        @JvmStatic private external fun nativeDestroy(engine: Long)
        @JvmStatic private external fun nativeInfo(engine: Long): String
        @JvmStatic private external fun nativeFailed(engine: Long): Boolean
        @JvmStatic private external fun nativeAttach(engine: Long, surface: Surface, forEncoder: Boolean): Long
        @JvmStatic private external fun nativeDetach(engine: Long, target: Long)
        @JvmStatic private external fun nativeTiming(engine: Long, target: Long): String
        @JvmStatic private external fun nativeSetScene(engine: Long, xml: ByteArray): String?
        @JvmStatic private external fun nativeLoadEffect(engine: Long, xml: ByteArray): String
        @JvmStatic private external fun nativeMaxPictureSize(engine: Long): Int
        @JvmStatic private external fun nativeSetPicture(engine: Long, uri: ByteArray, layerId: Long,
                                                        bitmap: Bitmap?, buffer: HardwareBuffer?, opaque: Boolean, standard: Int, range: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean
        @JvmStatic private external fun nativeSetVideoFrame(engine: Long, uri: ByteArray, layerId: Long, pts: Long, buffer: HardwareBuffer, standard: Int, range: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean
        @JvmStatic private external fun nativeInterpolateVideo(engine: Long, uri: ByteArray, layerId: Long, frames: LongArray, t: Float,
                                                               mode: Int, soft: Boolean, blur: Float, params: FloatArray, made: Bitmap?): Boolean
        @JvmStatic private external fun nativeMoshStart(engine: Long, uri: ByteArray, layerId: Long, pts: LongArray, mosh: FloatArray, flow: FloatArray): Int
        @JvmStatic private external fun nativeMoshFrame(engine: Long, uri: ByteArray, layerId: Long, step: Int, buffer: HardwareBuffer, standard: Int, range: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean
        @JvmStatic private external fun nativeRenderScene(
            engine: Long, target: Long, timeMs: Double, scale: Float, dx: Float, dy: Float, background: Int,
            selectedId: Long, handles: Int, pixels: Boolean, pixelDivisor: Int, gridSpacing: Float, solo: Int, soloIds: LongArray?,
            guides: FloatArray, refPx: Float,
        ): Int
        @JvmStatic private external fun nativeSetCameraView(engine: Long, on: Boolean)
        @JvmStatic private external fun nativeSetAccurateBlur(engine: Long, on: Boolean)
        @JvmStatic private external fun nativeRenderFrame(
            engine: Long, target: Long, timeMs: Double, background: Int, presentTimeNs: Long,
        ): Int
        @JvmStatic private external fun nativeRenderBuffer(engine: Long, buffer: HardwareBuffer, timeMs: Double, background: Int): Int
        @JvmStatic private external fun nativeReleaseBuffers(engine: Long)
        @JvmStatic private external fun nativeEase(easing: String, us: FloatArray): FloatArray
        @JvmStatic private external fun nativeFastCaptionCpu(): Boolean
        @JvmStatic private external fun nativeLayerBounds(engine: Long, layerId: Long, timeMs: Double): FloatArray?
        @JvmStatic private external fun nativeLayerQuad(engine: Long, layerId: Long, timeMs: Double): FloatArray?
        @JvmStatic private external fun nativeLayerMatrix(engine: Long, layerId: Long, timeMs: Double): FloatArray?
        @JvmStatic private external fun nativeLayerOutline(engine: Long, layerId: Long, timeMs: Double): String?
        @JvmStatic private external fun nativeHitTest(engine: Long, x: Float, y: Float, timeMs: Double): Long
        @JvmStatic private external fun nativeStill(engine: Long, timeMs: Double, width: Int, height: Int): ByteArray?
        @JvmStatic private external fun nativeShapeIcon(engine: Long, xml: ByteArray, size: Int, color: Int): ByteArray?
        @JvmStatic private external fun nativeSetModel(uri: ByteArray, positions: FloatArray?, normals: FloatArray?, uvs: FloatArray?,
                                                      colors: IntArray?, triangles: IntArray?, slots: IntArray?, twoSided: Boolean): Boolean
    }
}
