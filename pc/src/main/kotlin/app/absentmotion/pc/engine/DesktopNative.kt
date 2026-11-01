package app.absentmotion.pc.engine

import app.absentmotion.pc.platform.DesktopPlatform
import java.nio.ByteBuffer

object DesktopNative {
    init {
        System.load(DesktopPlatform.nativeLibrary())
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun loadEffect(handle: Long, xml: String): String
    external fun setScene(handle: Long, xml: String): String?
    external fun setPicture(handle: Long, uri: String, path: String): Boolean
    external fun setFrame(handle: Long, uri: String, width: Int, height: Int, rgba: ByteArray): Boolean
    external fun render(handle: Long, timeMs: Double, width: Int, height: Int, output: ByteBuffer): Boolean
    external fun hitTest(handle: Long, x: Float, y: Float, timeMs: Double): Long
    external fun layerQuad(handle: Long, id: Long, timeMs: Double): FloatArray?
    external fun renderPreview(handle: Long, timeMs: Double, width: Int, height: Int, address: Long, rowBytes: Long, scale: Float, dx: Float, dy: Float, background: Int,
        selectedId: Long, handles: Int, pixels: Boolean, pixelDivisor: Int, gridSpacing: Float, solo: Int, soloIds: LongArray?, guides: FloatArray, refPx: Float): Boolean
    external fun still(handle: Long, timeMs: Double, width: Int, height: Int, accurate: Boolean): ByteArray?
    external fun setPixels(handle: Long, uri: String, width: Int, height: Int, argb: IntArray): Boolean
    external fun dropPicture(handle: Long, uri: String)
    external fun setCameraView(handle: Long, on: Boolean)
    external fun setAccurateBlur(handle: Long, on: Boolean)
    external fun layerMatrix(handle: Long, id: Long, timeMs: Double): FloatArray?
    external fun layerBounds(handle: Long, id: Long, timeMs: Double): FloatArray?
    external fun layerOutline(handle: Long, id: Long, timeMs: Double): String?
    external fun shapeIcon(handle: Long, xml: String, size: Int, color: Int): ByteArray?
    external fun ease(easing: String, u: Float): Float
    external fun encodeWebp(rgba: ByteArray, width: Int, height: Int, quality: Int, lossless: Boolean): ByteArray?
    external fun videoOpen(path: String): Long
    external fun videoInfo(video: Long): DoubleArray
    external fun videoFrame(handle: Long, video: Long, uri: String, timeUs: Long, exact: Boolean): Int
    external fun videoClose(video: Long)
    external fun blipTimes(text: String, speed: Float, pause: Float, every: Int): FloatArray
}
