package app.absentmotion.engine

import android.graphics.Bitmap
import app.absentmotion.pc.engine.DesktopNative

class NativeEngine(backend: Backend = Backend.GANESH) : AutoCloseable {
    enum class Backend { GRAPHITE, GANESH, GL }

    private var handle = DesktopNative.create()
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

    fun setModel(uri: String, mesh: app.absentmotion.media.Mesh?): Boolean = DesktopNative.setModel(uri, mesh?.positions, mesh?.normals, mesh?.uvs, mesh?.colors, mesh?.triangles, mesh?.slots, mesh?.twoSided == true)

    fun setPictureFile(uri: String, path: String): Boolean = DesktopNative.setPicture(handle, uri, path)

    fun setFrame(uri: String, width: Int, height: Int, rgba: ByteArray): Boolean = DesktopNative.setFrame(handle, uri, width, height, rgba)

    fun clearLayerFrames() = DesktopNative.clearLayerFrames(handle)
    fun dropVideo(uri: String) = DesktopNative.dropVideo(handle, uri)
    fun setLayerFrame(uri: String, layer: Long, width: Int, height: Int, rgba: ByteArray) = DesktopNative.setLayerFrame(handle, uri, layer, width, height, rgba)
    fun interpolate(uri: String, layer: Long, width: Int, height: Int, first: ByteArray, second: ByteArray, before: ByteArray?, after: ByteArray?, made: ByteArray?,
        t: Float, mode: Int, blur: Float, parameters: FloatArray) = DesktopNative.interpolate(handle, uri, layer, width, height, first, second, before, after, made, t, mode, blur, parameters)

    fun moshStart(uri: String, layer: Long, stamps: LongArray, params: FloatArray) = DesktopNative.moshStart(handle, uri, layer, stamps, params)
    fun moshFrame(uri: String, layer: Long, step: Int, width: Int, height: Int, rgba: ByteArray) = DesktopNative.moshFrame(handle, uri, layer, step, width, height, rgba)

    fun showVideo(uri: String, source: app.absentmotion.pc.engine.NativeVideoSource, timeUs: Long, exact: Boolean): Int = source.show(handle, uri, timeUs, exact)

    fun showVideoLayer(uri: String, layer: Long, source: app.absentmotion.pc.engine.NativeVideoSource, timeUs: Long, exact: Boolean, waitMs: Int = if (exact) 15000 else 30): Int = source.showLayer(handle, uri, layer, timeUs, exact, waitMs)

    fun setCameraView(on: Boolean) = DesktopNative.setCameraView(handle, on)
    fun setAccurateBlur(on: Boolean) = DesktopNative.setAccurateBlur(handle, on)
    fun trim() {}
    fun releaseBuffers() {}

    fun renderPreview(target: org.jetbrains.skia.Bitmap, timeMs: Double, scale: Float, dx: Float, dy: Float, background: Int, selectedId: Long, handles: Int, view: PreviewView): Boolean {
        val pixels = target.peekPixels() ?: return false
        val ok = DesktopNative.renderPreview(handle, timeMs, target.width, target.height, pixels.addr, pixels.rowBytes.toLong(), scale, dx, dy, background, selectedId, handles,
            view.pixels, view.divisor, view.gridSpacing, view.solo, view.soloIds.takeIf { it.isNotEmpty() }?.toLongArray(),
            view.guides.flatMap { listOf(if (it.vertical) 1f else 0f, it.at, if (it.wide) 1f else 0f) }.toFloatArray(), view.refPx)
        if (ok) target.notifyPixelsChanged()
        return ok
    }

    fun layerQuad(layerId: Long, timeMs: Double): FloatArray? = DesktopNative.layerQuad(handle, layerId, timeMs)
    fun propertyNumbers(layerId: Long, effect: Int, name: String, timeMs: Double): FloatArray? = DesktopNative.propertyNumbers(handle, layerId, effect, name, timeMs)
    fun layerMatrix(layerId: Long, timeMs: Double): FloatArray? = DesktopNative.layerMatrix(handle, layerId, timeMs)
    fun layerBounds(layerId: Long, timeMs: Double): FloatArray? = DesktopNative.layerBounds(handle, layerId, timeMs)
    fun layerOutline(layerId: Long, timeMs: Double): String? = DesktopNative.layerOutline(handle, layerId, timeMs)
    fun hitTest(x: Float, y: Float, timeMs: Double): Long = DesktopNative.hitTest(handle, x, y, timeMs)
    fun still(timeMs: Double, width: Int, height: Int): ByteArray? = DesktopNative.still(handle, timeMs, width, height, true)
    fun stillInto(timeMs: Double, width: Int, height: Int, out: ByteArray): Boolean = DesktopNative.stillInto(handle, timeMs, width, height, out)
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
        fun shapeOutline(scene: String, t: Float, rounded: Boolean): String? = DesktopNative.shapeOutline(scene, t, rounded)
    }
}
