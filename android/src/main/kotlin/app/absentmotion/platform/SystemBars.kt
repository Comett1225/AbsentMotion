package app.absentmotion.platform

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

internal object SystemBars {
    private const val CLEAR = 0
    private const val LIGHT_SCRIM = 0xE6FFFFFF.toInt()
    private const val DARK_SCRIM = 0x66000000

    private var screen = true
    var cover: Boolean? = null
        private set
    private val light get() = cover ?: screen

    private val status = SystemBarStyle.auto(CLEAR, CLEAR) { !light }
    private val navigation = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { !light }

    fun screen(activity: ComponentActivity, light: Boolean) {
        screen = light
        activity.enableEdgeToEdge(status, navigation)
    }

    fun cover(activity: ComponentActivity, light: Boolean?) {
        cover = light
        activity.enableEdgeToEdge(status, navigation)
    }
}
