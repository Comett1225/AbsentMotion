package app.absentmotion.pc.engine

import app.absentmotion.pc.platform.DesktopPlatform
import java.nio.ByteBuffer

object DesktopNative {
    init {
        val library = java.io.File(DesktopPlatform.nativeLibrary())
        if (DesktopPlatform.windows) listOf("avutil-", "swresample-", "swscale-", "avcodec-", "avformat-").forEach { prefix ->
            library.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".dll") }?.firstOrNull()?.let { System.load(it.absolutePath) }
        }
        System.load(library.absolutePath)
    }

    external fun detectFaces(pixels: IntArray, width: Int, height: Int): FloatArray?
    external fun create(): Long
    external fun gpuState(): Int
    external fun destroy(handle: Long)
    external fun loadEffect(handle: Long, xml: String): String
    external fun setScene(handle: Long, xml: String): String?
    external fun setPicture(handle: Long, uri: String, path: String): Boolean
    external fun setFrame(handle: Long, uri: String, width: Int, height: Int, rgba: ByteArray): Boolean
    external fun clearLayerFrames(handle: Long)
    external fun dropVideo(handle: Long, uri: String)
    external fun setLayerFrame(handle: Long, uri: String, layerId: Long, width: Int, height: Int, rgba: ByteArray): Boolean
    external fun interpolate(handle: Long, uri: String, layerId: Long, width: Int, height: Int, first: ByteArray, second: ByteArray,
        before: ByteArray?, after: ByteArray?, made: ByteArray?, t: Float, mode: Int, blur: Float, parameters: FloatArray): Boolean
    external fun moshStart(handle: Long, uri: String, layerId: Long, stamps: LongArray, params: FloatArray): Int
    external fun moshFrame(handle: Long, uri: String, layerId: Long, step: Int, width: Int, height: Int, rgba: ByteArray): Boolean
    external fun render(handle: Long, timeMs: Double, width: Int, height: Int, output: ByteBuffer): Boolean
    external fun hitTest(handle: Long, x: Float, y: Float, timeMs: Double): Long
    external fun layerQuad(handle: Long, id: Long, timeMs: Double): FloatArray?
    external fun renderPreview(handle: Long, timeMs: Double, width: Int, height: Int, address: Long, rowBytes: Long, scale: Float, dx: Float, dy: Float, background: Int,
        selectedId: Long, handles: Int, pixels: Boolean, pixelDivisor: Int, gridSpacing: Float, solo: Int, soloIds: LongArray?, guides: FloatArray, refPx: Float): Boolean
    external fun still(handle: Long, timeMs: Double, width: Int, height: Int, accurate: Boolean): ByteArray?
    external fun stillInto(handle: Long, timeMs: Double, width: Int, height: Int, out: ByteArray): Boolean
    external fun setPixels(handle: Long, uri: String, width: Int, height: Int, argb: IntArray): Boolean
    external fun setModel(uri: String, positions: FloatArray?, normals: FloatArray?, uvs: FloatArray?, colors: IntArray?, triangles: IntArray?, slots: IntArray?, twoSided: Boolean): Boolean
    external fun dropPicture(handle: Long, uri: String)
    external fun setCameraView(handle: Long, on: Boolean)
    external fun setAccurateBlur(handle: Long, on: Boolean)
    external fun propertyNumbers(handle: Long, layerId: Long, effect: Int, name: String, timeMs: Double): FloatArray?
    external fun layerMatrix(handle: Long, id: Long, timeMs: Double): FloatArray?
    external fun layerBounds(handle: Long, id: Long, timeMs: Double): FloatArray?
    external fun layerOutline(handle: Long, id: Long, timeMs: Double): String?
    external fun shapeIcon(handle: Long, xml: String, size: Int, color: Int): ByteArray?
    external fun ease(easing: String, u: Float): Float
    external fun shapeOutline(scene: String, t: Float, rounded: Boolean): String?
    external fun encodeWebp(rgba: ByteArray, width: Int, height: Int, quality: Int, lossless: Boolean): ByteArray?
    external fun videoOpen(path: String): Long
    external fun videoInfo(video: Long): DoubleArray
    external fun videoFrame(handle: Long, video: Long, uri: String, timeUs: Long, exact: Boolean): Int
    external fun videoFrameLayer(handle: Long, video: Long, uri: String, layer: Long, timeUs: Long, waitMs: Int): Int
    external fun videoClose(video: Long)
    external fun blipTimes(text: String, speed: Float, pause: Float, every: Int): FloatArray
    external fun pickFiles(owner: Long, mode: Int, multiple: Boolean, title: String, name: String, filters: Array<String>): Array<String>?
    external fun systemMenu(window: Long): Boolean
}
