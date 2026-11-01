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
    external fun ease(easing: String, u: Float): Float
    external fun blipTimes(text: String, speed: Float, pause: Float, every: Int): FloatArray
}
