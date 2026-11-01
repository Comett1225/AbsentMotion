package android.view

import android.content.Context
import android.content.Desk

interface ViewParent { fun requestDisallowInterceptTouchEvent(disallow: Boolean) {} }

open class View(val context: Context = Desk.context) {
    val resources: android.content.res.Resources get() = android.content.res.Resources
    open var isEnabled = true
    var keepScreenOn = false
    var systemGestureExclusionRects: List<android.graphics.Rect> = emptyList()
    open val width: Int get() = 0
    open val height: Int get() = 0
    val parent: ViewParent? get() = null
    val rootView: View get() = this
    val isAttachedToWindow: Boolean get() = true
    fun performHapticFeedback(type: Int): Boolean = false
    fun performHapticFeedback(type: Int, flags: Int): Boolean = false
    fun post(action: Runnable): Boolean { javax.swing.SwingUtilities.invokeLater(action); return true }
    fun postDelayed(action: Runnable, delay: Long): Boolean { android.os.Handler().postDelayed(action, delay); return true }
    fun removeCallbacks(action: Runnable): Boolean = true
    fun invalidate() {}
    fun requestLayout() {}
    fun announceForAccessibility(text: CharSequence?) {}
    fun sendAccessibilityEvent(type: Int) {}
    fun getLocationOnScreen(out: IntArray) { out[0] = 0; out[1] = 0 }
    companion object {
        val Desktop = View()
        const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8
    }
}

object HapticFeedbackConstants {
    const val CLOCK_TICK = 4; const val CONFIRM = 16; const val REJECT = 17; const val LONG_PRESS = 0; const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3; const val CONTEXT_CLICK = 6; const val GESTURE_START = 12; const val GESTURE_END = 13; const val SEGMENT_TICK = 26
    const val SEGMENT_FREQUENT_TICK = 27; const val DRAG_START = 25; const val TOGGLE_ON = 21; const val TOGGLE_OFF = 22; const val TEXT_HANDLE_MOVE = 9
}
