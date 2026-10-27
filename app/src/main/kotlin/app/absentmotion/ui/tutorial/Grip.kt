package app.absentmotion.ui.tutorial

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window

internal class GripWatch(private val publish: (Boolean) -> Unit) {
    var held: Boolean = false
        private set

    fun touch(action: Int) {
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> set(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(false)
        }
    }

    fun interrupt() = set(false)

    private fun set(value: Boolean) {
        if (held == value) return
        held = value
        publish(value)
    }
}

internal class GripCallback(val base: Window.Callback, private val watch: GripWatch) : Window.Callback by base {
    var live = true

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (live) watch.touch(event.actionMasked)
        return base.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (live && !hasFocus) watch.interrupt()
        base.onWindowFocusChanged(hasFocus)
    }

    override fun onDetachedFromWindow() {
        if (live) watch.interrupt()
        base.onDetachedFromWindow()
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        base.onProvideKeyboardShortcuts(data, menu, deviceId)

    override fun onPointerCaptureChanged(hasCapture: Boolean) = base.onPointerCaptureChanged(hasCapture)
}

internal class GripLink private constructor(private val window: Window, private val callback: GripCallback) {
    fun release() {
        callback.live = false
        if (window.callback === callback) window.callback = callback.base
    }

    companion object {
        fun attach(window: Window, watch: GripWatch): GripLink? {
            val current = window.callback ?: return null
            val base = if (current is GripCallback) current.also { it.live = false }.base else current
            val callback = GripCallback(base, watch)
            window.callback = callback
            return GripLink(window, callback)
        }

        fun activityOf(context: Context): Activity? {
            var at: Context? = context
            while (at is ContextWrapper) {
                if (at is Activity) return at
                at = at.baseContext
            }
            return null
        }
    }
}
