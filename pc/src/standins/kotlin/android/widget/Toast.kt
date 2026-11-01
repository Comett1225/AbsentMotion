package android.widget

import android.content.Context
import androidx.compose.runtime.mutableStateOf

class Toast private constructor(val text: CharSequence, val duration: Int) {
    fun show() { current.value = this; shown++ }
    fun cancel() { if (current.value === this) current.value = null }
    val serial = shown

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        val current = mutableStateOf<Toast?>(null)
        private var shown = 0
        @JvmStatic fun makeText(context: Context?, text: CharSequence, duration: Int) = Toast(text, duration)
        @JvmStatic fun makeText(context: Context?, id: Int, duration: Int) = Toast(app.absentmotion.desk.Strings.get(id), duration)
    }
}
