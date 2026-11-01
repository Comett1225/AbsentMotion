package app.absentmotion.engine

import app.absentmotion.pc.engine.DesktopNative

object NativeEngine {
    fun ease(easing: String, u: Float): Float = DesktopNative.ease(easing, u)
    fun ease(easing: String, u: FloatArray): FloatArray = FloatArray(u.size) { ease(easing, u[it]) }
}
