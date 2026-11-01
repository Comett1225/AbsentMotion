package app.absentmotion.engine

import android.graphics.Bitmap
import app.absentmotion.pc.engine.DesktopNative
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeEngine(backend: Backend = Backend.GANESH) : AutoCloseable {
    enum class Backend { GRAPHITE, GANESH, GL }

    private var handle = DesktopNative.create()
    private var buffer: ByteBuffer? = null
    val failed: Boolean get() = false
    val info: String get() = "{\"backend\":\"desktop\"}"
    val displayTiming: Boolean get() = false
    val openGl: Boolean get() = false
    val maxPictureSize: Int get() = 8192

    fun setScene(xml: String) {
        val error = DesktopNative.setScene(handle, xml)
        require(error == null) { error ?: "Invalid scene" }
    }

    fun loadEffect(xml: String): String = DesktopNative.loadEffect(handle, xml)

    fun setPicture(uri: String, bitmap: Bitmap?, layerId: Long = 0): Boolean {
        if (bitmap == null) { DesktopNative.dropPicture(handle, uri); return true }
        val px = bitmap.image.getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
        return DesktopNative.setPixels(handle, uri, bitmap.width, bitmap.height, px)
    }

    fun setPictureFile(uri: String, path: String): Boolean = DesktopNative.setPicture(handle, uri, path)

    fun setFrame(uri: String, width: Int, height: Int, rgba: ByteArray): Boolean = DesktopNative.setFrame(handle, uri, width, height, rgba)

    fun setCameraView(on: Boolean) = DesktopNative.setCameraView(handle, on)
    fun setAccurateBlur(on: Boolean) = DesktopNative.setAccurateBlur(handle, on)
    fun trim() {}
    fun releaseBuffers() { buffer = null }

    fun renderPreview(width: Int, height: Int, timeMs: Double, scale: Float, dx: Float, dy: Float, background: Int, selectedId: Long, handles: Int, view: PreviewView, out: IntArray): Boolean {
        val bytes = width * height * 4
        val target = buffer?.takeIf { it.capacity() == bytes } ?: ByteBuffer.allocateDirect(bytes).also { buffer = it }
        target.clear()
        val ok = DesktopNative.renderPreview(handle, timeMs, width, height, target, scale, dx, dy, background, selectedId, handles,
            view.pixels, view.divisor, view.gridSpacing, view.solo, view.soloIds.takeIf { it.isNotEmpty() }?.toLongArray(),
            view.guides.flatMap { listOf(if (it.vertical) 1f else 0f, it.at, if (it.wide) 1f else 0f) }.toFloatArray(), view.refPx)
        if (ok) target.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out, 0, width * height)
        return ok
    }

    fun layerQuad(layerId: Long, timeMs: Double): FloatArray? = DesktopNative.layerQuad(handle, layerId, timeMs)
    fun layerMatrix(layerId: Long, timeMs: Double): FloatArray? = DesktopNative.layerMatrix(handle, layerId, timeMs)
    fun layerBounds(layerId: Long, timeMs: Double): FloatArray? = DesktopNative.layerBounds(handle, layerId, timeMs)
    fun layerOutline(layerId: Long, timeMs: Double): String? = DesktopNative.layerOutline(handle, layerId, timeMs)
    fun hitTest(x: Float, y: Float, timeMs: Double): Long = DesktopNative.hitTest(handle, x, y, timeMs)
    fun still(timeMs: Double, width: Int, height: Int): ByteArray? = DesktopNative.still(handle, timeMs, width, height, true)
    fun previewStill(timeMs: Double, width: Int, height: Int, accurateBlur: Boolean): ByteArray? = DesktopNative.still(handle, timeMs, width, height, accurateBlur)
    fun shapeIcon(xml: String, size: Int, color: Int): ByteArray? = DesktopNative.shapeIcon(handle, xml, size, color)

    override fun close() {
        if (handle != 0L) DesktopNative.destroy(handle)
        handle = 0
    }

    companion object {
        fun ease(easing: String, us: FloatArray): FloatArray = FloatArray(us.size) { ease(easing, us[it]) }
        fun ease(easing: String, u: Float): Float = if (easing.isEmpty()) u else DesktopNative.ease(easing, u)
        fun blipTimes(text: String, speed: Float, pause: Float, every: Int): FloatArray = DesktopNative.blipTimes(text, speed, pause, every)
        fun fastCaptionCpu(): Boolean = false
    }
}
